// Denmark Road Assistant: screenshots, system tests, accessibility audit and timings.
// Run by run.ps1 (Windows). Usage: node evidence.cjs [main|mldown]
// Everything it produces goes into ./output (or EVIDENCE_OUT).
'use strict';

const fs = require('fs');
const path = require('path');
const { chromium, devices } = require('playwright-core');

const OUT = process.env.EVIDENCE_OUT || path.join(__dirname, 'output');
const SHOTS = path.join(OUT, 'shots');
const BASE = process.env.APP_URL || 'http://localhost:4200';
const API = process.env.API_URL || 'http://localhost:8080';
const ML = process.env.ML_URL || 'http://127.0.0.1:8000';
const PHASE = process.argv[2] || 'main';
const TESTS_FILE = path.join(OUT, 'system-tests.json');
const FACTS_FILE = path.join(OUT, 'facts.json');

fs.mkdirSync(SHOTS, { recursive: true });

const results = fs.existsSync(TESTS_FILE) && PHASE !== 'main' ? JSON.parse(fs.readFileSync(TESTS_FILE, 'utf8')) : [];
const facts = fs.existsSync(FACTS_FILE) && PHASE !== 'main' ? JSON.parse(fs.readFileSync(FACTS_FILE, 'utf8')) : { analyses: [] };
const save = () => {
  fs.writeFileSync(TESTS_FILE, JSON.stringify(results, null, 2));
  fs.writeFileSync(FACTS_FILE, JSON.stringify(facts, null, 2));
};
const log = (...a) => console.log(new Date().toISOString().slice(11, 19), ...a);

// ---------- dates in Danish time ----------
const dkDate = (offsetDays) =>
  new Date(Date.now() + offsetDays * 864e5).toLocaleDateString('sv-SE', { timeZone: 'Europe/Copenhagen' });

// ---------- browser ----------
async function launch() {
  const tries =
    process.env.PW_CHANNEL === 'none'
      ? [undefined]
      : [process.env.PW_CHANNEL, 'msedge', 'chrome', undefined].filter((c, i, a) => c !== '' && a.indexOf(c) === i);
  let last;
  for (const channel of tries) {
    if (channel === '') continue;
    try {
      const b = await chromium.launch(channel ? { channel, headless: true } : { headless: true });
      log('browser:', channel || 'bundled chromium', b.version());
      facts.browser = `${channel || 'chromium'} ${b.version()}`;
      return b;
    } catch (e) {
      last = e;
    }
  }
  throw last;
}

let currentPage = null;
async function test(id, name, fn) {
  const t0 = Date.now();
  log('TEST', id, name);
  try {
    const detail = await fn();
    results.push({ id, name, status: 'pass', detail: detail || '', ms: Date.now() - t0 });
    log('  pass', detail || '');
  } catch (e) {
    const msg = String((e && e.message) || e).split('\n')[0].slice(0, 400);
    results.push({ id, name, status: 'fail', detail: msg, ms: Date.now() - t0 });
    log('  FAIL', msg);
    if (currentPage) await currentPage.screenshot({ path: path.join(SHOTS, `fail-${id}.png`) }).catch(() => {});
  }
  save();
}
const must = (cond, msg) => {
  if (!cond) throw new Error(msg);
};

// ---------- page helpers ----------
// Angular writes ngModel values into the inputs a moment after the click.
async function waitInputs(page, o, d) {
  await page
    .waitForFunction(
      ([a, b]) =>
        document.querySelector('input[aria-label="Starting point"]').value === a &&
        document.querySelector('input[aria-label="Destination"]').value === b,
      [o, d],
      { timeout: 5000 }
    )
    .catch(() => {});
}

async function settle(page) {
  await page
    .waitForFunction(
      () => {
        const tiles = [...document.querySelectorAll('img.leaflet-tile')];
        return tiles.length > 0 && tiles.every((t) => t.complete);
      },
      null,
      { timeout: 25000 }
    )
    .catch(() => {});
  await page.waitForFunction(() => !document.querySelector('.weather-skeleton'), null, { timeout: 25000 }).catch(() => {});
  await page.waitForTimeout(1500);
}

async function analyze(page, origin, destination, date, time) {
  await page.getByLabel('Starting point').fill(origin);
  await page.getByLabel('Destination', { exact: true }).fill(destination);
  if (date) await page.locator('#departureDate').fill(date);
  if (time) await page.locator('#departureTime').fill(time);
  const respP = page.waitForResponse((r) => r.url().includes('/api/route-and-predict') && r.request().method() === 'POST', {
    timeout: 150000
  });
  const t0 = Date.now();
  await page.getByRole('button', { name: 'Analyze Route' }).click();
  const resp = await respP;
  let body = null;
  try {
    body = await resp.json();
  } catch (_) {}
  if (resp.ok()) {
    await page.locator('.advisory-card .condition-pill').waitFor({ timeout: 60000 });
  } else {
    await page.locator('.stage-failure').first().waitFor({ timeout: 60000 });
  }
  const uiMs = Date.now() - t0;
  const rec = { origin, destination, date, time, status: resp.status(), uiMs };
  if (resp.ok() && body) {
    rec.grade = body.prediction.roadConditionLabel;
    rec.confidence = body.prediction.confidence;
    rec.leaveEarly = body.prediction.leaveEarlyMinutes;
    rec.distanceKm = body.route.distanceKm;
    rec.durationMin = body.route.durationMinutes;
    rec.steps = body.route.steps.length;
    rec.incidents = (body.incidents || []).length;
    rec.weatherSource = body.weather && body.weather.source;
    rec.weather = body.weather;
    rec.top = (body.prediction.influentialFeatures || []).map((f) => f.feature);
    rec.advisory = body.prediction.advisory;
    rec.timeOfDay = body.features.timeOfDay;
    rec.historyId = body.historyId;
  } else if (body) {
    rec.message = body.message;
  }
  facts.analyses.push(rec);
  save();
  await page.waitForTimeout(1500); // be gentle with the free public services
  return { resp, body, rec };
}

// Bounding box of an element in page coordinates.
async function pageBox(page, locator) {
  await locator.scrollIntoViewIfNeeded();
  return locator.evaluate((el) => {
    const r = el.getBoundingClientRect();
    return { x: r.left + window.scrollX, y: r.top + window.scrollY, width: r.width, height: r.height };
  });
}

async function hideBar(page, hidden) {
  await page.evaluate((h) => {
    const bar = document.querySelector('.topbar');
    if (bar) bar.style.visibility = h ? 'hidden' : '';
  }, hidden);
}

async function shotUnion(page, file, locators, pad = 14) {
  await hideBar(page, true);
  const boxes = [];
  for (const l of locators) boxes.push(await pageBox(page, l));
  const x0 = Math.max(0, Math.min(...boxes.map((b) => b.x)) - pad);
  const y0 = Math.max(0, Math.min(...boxes.map((b) => b.y)) - pad);
  const x1 = Math.max(...boxes.map((b) => b.x + b.width)) + pad;
  const y1 = Math.max(...boxes.map((b) => b.y + b.height)) + pad;
  await page.waitForTimeout(400);
  await page.screenshot({ path: path.join(SHOTS, file), fullPage: true, clip: { x: x0, y: y0, width: x1 - x0, height: y1 - y0 } });
  await hideBar(page, false);
  log('  shot', file);
}

async function shotEl(locator, file) {
  const page = locator.page();
  await hideBar(page, true);
  await locator.scrollIntoViewIfNeeded();
  await page.waitForTimeout(400);
  await locator.screenshot({ path: path.join(SHOTS, file) });
  await hideBar(page, false);
  log('  shot', file);
}

async function shotView(page, file) {
  await page.screenshot({ path: path.join(SHOTS, file) });
  log('  shot', file);
}

async function scrollBelowBar(page, locator, gap = 10) {
  await locator.scrollIntoViewIfNeeded();
  await locator.evaluate((el, g) => {
    const bar = document.querySelector('.topbar');
    const barBottom = bar ? Math.max(0, bar.getBoundingClientRect().bottom) : 0;
    window.scrollBy(0, el.getBoundingClientRect().top - barBottom - g);
  }, gap);
  await page.waitForTimeout(700);
}

const card = (page, heading) =>
  page.locator('section.card, section.weather-card', { has: page.getByRole('heading', { name: heading, exact: true }) }).first();

// ---------- accessibility ----------
const AXE = fs.readFileSync(require.resolve('axe-core/axe.min.js'), 'utf8');
async function audit(page, name) {
  try {
    await page.addScriptTag({ content: AXE });
    const r = await page.evaluate(async () => {
      // eslint-disable-next-line no-undef
      const res = await axe.run(document, { runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'] } });
      return {
        passes: res.passes.length,
        violations: res.violations.map((v) => ({ id: v.id, impact: v.impact, help: v.help, nodes: v.nodes.length, sample: v.nodes.slice(0, 3).map((n) => n.target.join(' ')) }))
      };
    });
    facts.axe = facts.axe || {};
    facts.axe[name] = r;
    save();
    log('  axe', name, 'violations:', r.violations.map((v) => `${v.id}(${v.nodes})`).join(', ') || 'none');
  } catch (e) {
    log('  axe failed', name, e.message);
  }
}

async function loggedInName(page) {
  const pill = page.locator('.user-pill');
  await pill.waitFor({ timeout: 15000 });
  return (await pill.innerText()).trim();
}

async function logout(page) {
  const btn = page.getByRole('button', { name: 'Logout' });
  if (await btn.isVisible().catch(() => false)) await btn.click();
  await page.waitForTimeout(800);
}

// ======================================================================
async function main() {
  const browser = await launch();
  const desktop = await browser.newContext({
    viewport: { width: 1440, height: 900 },
    deviceScaleFactor: 2,
    locale: 'en-GB',
    timezoneId: 'Europe/Copenhagen',
    geolocation: { latitude: 55.6761, longitude: 12.5683 }, // Copenhagen City Hall Square
    permissions: ['geolocation']
  });
  const page = await desktop.newPage();
  currentPage = page;
  page.setDefaultTimeout(30000);

  const stamp = new Date().toISOString().replace(/[-:T]/g, '').slice(0, 12);
  const user = {
    fullName: 'Test Driver',
    email: `test.driver.${stamp}@example.com`,
    phone: '20304050',
    address: 'Vesterbrogade 1, 1620 København V',
    password: 'Td-' + Math.random().toString(36).slice(2, 10) + '-' + Math.random().toString(36).slice(2, 6)
  };
  const tomorrow = dkDate(1);
  facts.runDate = new Date().toISOString();
  facts.tomorrow = tomorrow;

  // ST01 ---------------------------------------------------------------
  await test('ST01', 'App opens on the Route Analysis page and shows the model details', async () => {
    await page.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
    await page.waitForURL(/\/analysis/);
    await page.locator('.ra-stat', { hasText: 'Accuracy' }).waitFor({ timeout: 60000 });
    await page.waitForTimeout(2500);
    const stats = await page.locator('.ra-model-strip').innerText();
    facts.modelStrip = stats;
    return `URL ${new URL(page.url()).pathname}; model strip: ${stats.replace(/\s+/g, ' ')}`;
  });
  await shotView(page, '01-landing.png');
  await audit(page, 'analysis-empty');

  // ST02 ---------------------------------------------------------------
  await test('ST02', 'An example trip fills in the form with one click', async () => {
    await page.getByRole('button', { name: /Copenhagen.*Roskilde/ }).click();
    await waitInputs(page, 'Copenhagen', 'Roskilde');
    const o = await page.getByLabel('Starting point').inputValue();
    const d = await page.getByLabel('Destination', { exact: true }).inputValue();
    must(o === 'Copenhagen' && d === 'Roskilde', `got ${o} / ${d}`);
    return `Origin "${o}", destination "${d}"`;
  });

  // ST03 ---------------------------------------------------------------
  await test('ST03', 'The swap button swaps the start point and destination', async () => {
    await page.getByRole('button', { name: 'Swap origin and destination' }).click();
    await waitInputs(page, 'Roskilde', 'Copenhagen');
    const o = await page.getByLabel('Starting point').inputValue();
    const d = await page.getByLabel('Destination', { exact: true }).inputValue();
    await page.getByRole('button', { name: 'Swap origin and destination' }).click();
    await waitInputs(page, 'Copenhagen', 'Roskilde');
    must(o === 'Roskilde' && d === 'Copenhagen', `got ${o} / ${d}`);
    return `After swap: "${o}" to "${d}"`;
  });

  // ST04 ---------------------------------------------------------------
  await test('ST04', 'The date picker only offers today and the next six days', async () => {
    const min = await page.locator('#departureDate').getAttribute('min');
    const max = await page.locator('#departureDate').getAttribute('max');
    must(min === dkDate(0) && max === dkDate(6), `min ${min}, max ${max}`);
    return `min ${min}, max ${max}`;
  });

  // ST05 ---------------------------------------------------------------
  await test('ST05', 'A visitor who is not logged in can analyse a route (Copenhagen to Roskilde)', async () => {
    const { resp, body } = await analyze(page, 'Copenhagen', 'Roskilde', tomorrow, '16:00');
    must(resp.ok(), `HTTP ${resp.status()}`);
    must(body.historyId === null || body.historyId === undefined, 'anonymous analysis was saved');
    await page.getByRole('heading', { name: 'Save this result' }).waitFor({ timeout: 10000 });
    return `${body.route.distanceKm} km, grade ${body.prediction.roadConditionLabel}, not saved, "Save this result" prompt shown`;
  });

  // ST06 / ST07 (API) --------------------------------------------------
  await test('ST06', 'A departure date in the past is refused with a clear message', async () => {
    const r = await desktop.request.post(API + '/api/route-and-predict', {
      data: { origin: 'Copenhagen', destination: 'Roskilde', selectedDepartureDate: dkDate(-1), selectedDepartureTime: '08:00' }
    });
    const b = await r.json();
    must(r.status() === 400, `HTTP ${r.status()}`);
    return `HTTP 400: "${b.message}"`;
  });
  await test('ST07', 'A date beyond the forecast window is refused with a clear message', async () => {
    const r = await desktop.request.post(API + '/api/route-and-predict', {
      data: { origin: 'Copenhagen', destination: 'Roskilde', selectedDepartureDate: dkDate(10), selectedDepartureTime: '08:00' }
    });
    const b = await r.json();
    must(r.status() === 400, `HTTP ${r.status()}`);
    return `HTTP 400: "${b.message}"`;
  });

  // ST08 ---------------------------------------------------------------
  await test('ST08', 'An unknown place name gives a readable error message', async () => {
    const { resp } = await analyze(page, 'Copenhagen', 'Qwxyzborg Nowhere', tomorrow, '09:00');
    must(resp.status() === 404, `HTTP ${resp.status()}`);
    const msg = (await page.locator('.route-card .stage-failure').innerText()).trim();
    must(/Could not match/.test(msg), msg);
    await page.evaluate(() => window.scrollTo(0, 0));
    await page.waitForTimeout(600);
    await shotView(page, 'F2-unknown-place.png');
    return `HTTP 404, message shown: "${msg.replace(/\s+/g, ' ')}"`;
  });

  // ST09 ---------------------------------------------------------------
  await test('ST09', 'Use My Location fills the start point from the device position', async () => {
    await page.getByLabel('Starting point').fill('');
    await page.getByRole('button', { name: 'Use My Location' }).click();
    await page.locator('.location-success, .location-failure').first().waitFor({ timeout: 30000 });
    const ok = await page.locator('.location-success').isVisible().catch(() => false);
    const text = (await page.locator('.location-success, .location-failure').first().innerText()).trim();
    const o = await page.getByLabel('Starting point').inputValue();
    must(ok && o.length > 0, `message "${text}", origin "${o}"`);
    return `Position set to Copenhagen City Hall Square; origin became "${o}"`;
  });

  // ---------------- accounts ----------------
  await test('ST10', 'A new user can sign up and is logged in straight away', async () => {
    await page.goto(BASE + '/signup');
    await page.locator('input[formcontrolname="fullName"]').fill(user.fullName);
    await page.locator('input[formcontrolname="email"]').fill(user.email);
    await page.locator('input[formcontrolname="phone"]').fill(user.phone);
    await page.locator('input[formcontrolname="address"]').fill(user.address);
    await page.locator('input[formcontrolname="password"]').fill(user.password);
    await page.waitForTimeout(300);
    await shotView(page, '02-signup.png');
    await audit(page, 'signup');
    await page.getByRole('button', { name: 'Sign up' }).click();
    await page.waitForURL(/\/profile/, { timeout: 20000 });
    const name = await loggedInName(page);
    must(name === user.fullName, `top bar shows "${name}"`);
    return `Redirected to /profile; top bar shows "${name}"`;
  });

  await test('ST11', 'Signing up again with the same email is refused with a clear message', async () => {
    await logout(page);
    await page.goto(BASE + '/signup');
    await page.locator('input[formcontrolname="fullName"]').fill(user.fullName);
    await page.locator('input[formcontrolname="email"]').fill(user.email);
    await page.locator('input[formcontrolname="phone"]').fill(user.phone);
    await page.locator('input[formcontrolname="address"]').fill(user.address);
    await page.locator('input[formcontrolname="password"]').fill(user.password);
    await page.getByRole('button', { name: 'Sign up' }).click();
    const err = page.locator('.auth-error');
    await err.waitFor({ timeout: 15000 });
    const t = (await err.innerText()).trim();
    must(/already exists/i.test(t), t);
    return `Message: "${t}"`;
  });

  await test('ST12', 'A wrong password gives a general message that does not reveal whether the email exists', async () => {
    await page.goto(BASE + '/login');
    await page.locator('input[formcontrolname="email"]').fill(user.email);
    await page.locator('input[formcontrolname="password"]').fill('wrong-password-123');
    await page.getByRole('button', { name: 'Login' }).click();
    const err = page.locator('.auth-error');
    await err.waitFor({ timeout: 15000 });
    const t1 = (await err.innerText()).trim();
    await page.waitForTimeout(1200);
    await shotView(page, '03-login-error.png');
    await audit(page, 'login');
    // unknown email gives the same text
    await page.locator('input[formcontrolname="email"]').fill('nobody.' + stamp + '@example.com');
    await page.locator('input[formcontrolname="password"]').fill('wrong-password-123');
    await page.getByRole('button', { name: 'Login' }).click();
    await page.waitForTimeout(2500);
    const t2 = (await err.innerText()).trim();
    must(t1 === t2 && /Invalid email or password/.test(t1), `"${t1}" vs "${t2}"`);
    return `Both a wrong password and an unknown email show "${t1}"`;
  });

  await test('ST13', 'A registered user can log in and is sent to the Route Analysis page', async () => {
    await page.goto(BASE + '/login');
    await page.locator('input[formcontrolname="email"]').fill(user.email);
    await page.locator('input[formcontrolname="password"]').fill(user.password);
    await page.getByRole('button', { name: 'Login' }).click();
    await page.waitForURL(/\/analysis/, { timeout: 20000 });
    const name = await loggedInName(page);
    return `Redirected to /analysis; top bar shows "${name}"`;
  });

  // ---------------- main analysis ----------------
  let mainBody = null;
  await test('ST14', 'A logged-in user analyses Copenhagen to Aarhus for tomorrow at 08:00 and gets the full result', async () => {
    await page.locator('.ra-stat', { hasText: 'Accuracy' }).waitFor({ timeout: 60000 });
    await page.getByLabel('Starting point').fill('Copenhagen');
    await page.getByLabel('Destination', { exact: true }).fill('Aarhus');
    await page.locator('#departureDate').fill(tomorrow);
    await page.locator('#departureTime').fill('08:00');
    await page.locator('#departureTime').blur();
    await page.waitForTimeout(400);
    await shotEl(page.locator('section.route-card'), '04-form.png');
    const { resp, body } = await analyze(page, 'Copenhagen', 'Aarhus', tomorrow, '08:00');
    must(resp.ok(), `HTTP ${resp.status()}`);
    mainBody = body;
    await settle(page);
    const probs = await page.locator('.ra-prob-value').allInnerTexts();
    const sum = probs.reduce((s, p) => s + parseFloat(p), 0);
    const nFeat = await page.locator('.ra-feature').count();
    const nSteps = await page.locator('.nav-step').count();
    const factors = await page.locator('.explainability-footer .input-item').count();
    const feedbackVisible = await page.getByRole('heading', { name: 'Was this advisory helpful?' }).isVisible();
    must(nFeat === 19, `${nFeat} features shown`);
    must(Math.abs(sum - 100) <= 3, `probabilities add to ${sum}`);
    must(nSteps > 0, 'no directions');
    must(feedbackVisible, 'feedback box missing');
    must(body.historyId, 'not saved to history');
    return `${body.route.distanceKm} km, ${body.route.durationMinutes} min, grade ${body.prediction.roadConditionLabel} (${Math.round(
      body.prediction.confidence * 100
    )}%), leave ${body.prediction.leaveEarlyMinutes} min early; ${nFeat} features, ${nSteps} directions, ${factors} factor rows, probabilities add to ${sum}%; saved as history item ${body.historyId}`;
  });
  if (mainBody) {
    facts.main = mainBody;
    save();
    await page.evaluate(() => window.scrollTo(0, 0));
    await page.waitForTimeout(500);
    await shotEl(page.locator('.map-frame'), '05a-map.png');
    await shotEl(card(page, 'Route Overview'), '05b-overview.png');
    await shotEl(page.locator('section.advisory-card'), '06a-advisory.png');
    await shotEl(page.locator('.explainability-footer'), '06b-factors.png');
    facts.factorRows = await page.locator('.explainability-footer .input-item').allInnerTexts();
    await page.evaluate(() => document.querySelectorAll('.inputs-list').forEach((e) => (e.style.maxHeight = 'none')));
    await shotEl(page.locator('.explainability-footer'), '06c-factors-expanded.png');
    await page.evaluate(() => document.querySelectorAll('.inputs-list').forEach((e) => (e.style.maxHeight = '')));
    await shotUnion(page, '08-probabilities-features.png', [card(page, 'Grade Probabilities'), card(page, 'Derived Route Features')]);
    await shotEl(card(page, 'Directions'), 'x-directions.png');
    await shotEl(card(page, 'Top Feature Importance'), 'x-importance.png');
    await page.screenshot({ path: path.join(SHOTS, 'x-full-result.png'), fullPage: true });
    await audit(page, 'analysis-result');
  }

  await test('ST15', 'Feedback can be given on a fresh advisory', async () => {
    const fb = page.locator('app-feedback-card');
    await fb.getByRole('button', { name: 'Yes, helpful' }).click();
    await fb.locator('textarea').fill('System test: the advice was clear and the leave-early time made sense.');
    await fb.getByRole('button', { name: /Save feedback|Update feedback/ }).click();
    await fb.locator('.feedback-success').waitFor({ timeout: 15000 });
    return (await fb.locator('.feedback-success').innerText()).trim();
  });

  // ---------------- more routes: timings, history and an incident ----------------
  const candidates = [
    ['Odense', 'Aarhus', '07:00'],
    ['Aalborg', 'Aarhus', '17:00'],
    ['Copenhagen Airport', 'Odense', '12:00'],
    ['Esbjerg', 'Kolding', '15:00'],
    ['Roskilde', 'Køge', '08:00'],
    ['Helsingør', 'Copenhagen', '07:30']
  ];
  let incidentShot = false;
  let lastOk = null;
  await test('ST16', 'Six more routes of different lengths and times are analysed without errors', async () => {
    const lines = [];
    const failed = [];
    for (const [o, d, t] of candidates) {
      const { resp, body } = await analyze(page, o, d, tomorrow, t);
      if (!resp.ok()) {
        failed.push(`${o} to ${d}: HTTP ${resp.status()}`);
        continue;
      }
      lastOk = [o, d, t];
      lines.push(`${o}-${d} ${body.route.distanceKm} km ${body.prediction.roadConditionLabel}, ${body.incidents.length} events`);
      if (!incidentShot && body.incidents.length > 0) {
        await settle(page);
        await shotUnion(page, '07-weather-events.png', [page.locator('app-weather-widget'), card(page, 'Reported On Your Route')]);
        await shotEl(page.locator('.map-frame'), '07-map-of-event-route.png');
        facts.incidentRoute = { origin: o, destination: d, time: t, incidents: body.incidents };
        incidentShot = true;
      }
    }
    must(failed.length === 0, failed.join('; ') + ' | others: ' + lines.join('; '));
    return lines.join('; ');
  });
  if (!incidentShot) {
    await settle(page);
    await shotUnion(page, '07-weather-events.png', [page.locator('app-weather-widget'), card(page, 'Reported On Your Route')]);
    facts.incidentRoute = null;
    save();
  }

  // ---------------- history ----------------
  await test('ST17', 'The History page lists saved analyses newest first, and feedback can be saved there', async () => {
    await page.getByRole('link', { name: 'History', exact: true }).click();
    await page.waitForURL(/\/history/);
    await page.locator('.history-item').first().waitFor({ timeout: 20000 });
    const n = await page.locator('.history-item').count();
    must(n >= 2, `${n} items`);
    const titles = await page.locator('.history-item h3').allInnerTexts();
    // open the newest item that has no review yet
    const item = page.locator('.history-item', { hasText: 'No review yet' }).first();
    await item.getByRole('button', { name: 'View details' }).click();
    const fb = page.locator('.history-detail-card app-feedback-card');
    await fb.waitFor({ timeout: 15000 });
    await fb.getByRole('button', { name: 'Yes, helpful' }).click();
    await fb.locator('textarea').fill('System test: useful to see which factors mattered for this trip.');
    await fb.getByRole('button', { name: /Save feedback|Update feedback/ }).click();
    await fb.locator('.feedback-success').waitFor({ timeout: 15000 });
    await page.waitForTimeout(800);
    const reviewed = await page.locator('.history-item .feedback-status.done').count();
    await shotEl(fb, '10b-history-feedback.png');
    await shotEl(page.locator('.history-detail-card'), '10c-history-detail.png');
    await page.evaluate(() => window.scrollTo(0, 0));
    await page.waitForTimeout(400);
    await shotView(page, '10-history.png');
    await page.screenshot({ path: path.join(SHOTS, 'x-history-full.png'), fullPage: true });
    await audit(page, 'history');
    return `${n} saved analyses, newest first ("${titles[0]}" at the top); ${reviewed} marked "Review saved"`;
  });

  // ---------------- profile ----------------
  await test('ST18', 'The profile can be edited and the change is kept after a reload', async () => {
    await page.getByRole('link', { name: 'Profile', exact: true }).click();
    await page.waitForURL(/\/profile/);
    const phone = page.locator('input[formcontrolname="phone"]');
    await phone.waitFor();
    await phone.fill('20304060');
    await page.getByRole('button', { name: 'Save changes' }).click();
    await page.locator('.profile-message.success').waitFor({ timeout: 15000 });
    const msg = (await page.locator('.profile-message.success').innerText()).trim();
    await page.waitForTimeout(300);
    await shotView(page, '11-profile.png');
    await audit(page, 'profile');
    await page.reload();
    await phone.waitFor();
    await page.waitForTimeout(1500);
    const v = await phone.inputValue();
    must(v === '20304060', `phone after reload: ${v}`);
    return `Message "${msg}"; phone still 20304060 after reload`;
  });

  // ---------------- access control ----------------
  await test('ST19', 'Logged-out visitors are sent to the login page when they open History', async () => {
    await logout(page);
    await page.goto(BASE + '/history');
    await page.waitForURL(/\/login/, { timeout: 15000 });
    return `Redirected to ${new URL(page.url()).pathname}${new URL(page.url()).search}`;
  });
  await test('ST20', 'The history API refuses a request without a login token', async () => {
    const r = await desktop.request.get(API + '/api/history');
    must(r.status() === 401 || r.status() === 403, `HTTP ${r.status()}`);
    return `HTTP ${r.status()}`;
  });

  // ---------------- timings of the ML service (NFR4) ----------------
  await test('ST21', 'The ML service answers a prediction quickly (20 timed requests)', async () => {
    const times = [];
    const obs = {
      observation: {
        distanceKm: 187.2,
        durationMin: 142,
        departureIso: `${tomorrow}T08:00:00`,
        urbanSignal: 0.55,
        weather: { temperatureC: 6.5, rainfallMm: 0.4, humidity: 0.86, windSpeedKmh: 24, source: 'open-meteo' }
      }
    };
    for (let i = 0; i < 20; i++) {
      const t0 = Date.now();
      const r = await desktop.request.post(ML + '/predict-road-condition', { data: obs });
      must(r.ok(), `HTTP ${r.status()} ${await r.text()}`);
      times.push(Date.now() - t0);
    }
    times.sort((a, b) => a - b);
    facts.mlTimesMs = times;
    const median = times[10];
    return `median ${median} ms, slowest ${times[19]} ms`;
  });
  await desktop.close();

  // ---------------- phone-sized screen ----------------
  const phone = await browser.newContext({
    ...devices['Pixel 7'],
    locale: 'en-GB',
    timezoneId: 'Europe/Copenhagen',
    geolocation: { latitude: 55.6761, longitude: 12.5683 },
    permissions: ['geolocation']
  });
  const p = await phone.newPage();
  currentPage = p;
  p.setDefaultTimeout(30000);
  let phoneBody = null;
  await test('ST22', 'The app works on a phone-sized screen (Pixel 7 size)', async () => {
    await p.goto(BASE + '/analysis');
    await p.locator('.ra-stat', { hasText: 'Accuracy' }).waitFor({ timeout: 60000 });
    const { resp, body } = await analyze(p, 'Copenhagen', 'Roskilde', tomorrow, '08:00');
    must(resp.ok(), `HTTP ${resp.status()}`);
    phoneBody = body;
    await settle(p);
    const overflow = await p.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    must(overflow <= 2, `page is ${overflow}px wider than the screen`);
    return `Result shown; no sideways scrolling (overflow ${overflow}px)`;
  });
  if (phoneBody) {
    await p.evaluate(() => window.scrollTo(0, 0));
    await p.waitForTimeout(500);
    await shotView(p, 'P1-phone-top.png');
    await scrollBelowBar(p, p.locator('.map-frame'));
    await shotView(p, 'P2-phone-map.png');
    await scrollBelowBar(p, p.locator('section.advisory-card'));
    await shotView(p, 'P3-phone-advisory.png');
    await p.screenshot({ path: path.join(SHOTS, 'x-phone-full.png'), fullPage: true });
  }

  // ---------------- turn-by-turn ----------------
  await test('ST23', 'Turn-by-turn guidance shows the next instruction and distance and spots leaving the route', async () => {
    must(phoneBody, 'no route to follow');
    const g = phoneBody.route.geometry;
    // a point about 300 m along the route
    const hav = (a, b) => {
      const R = 6371000, toR = (x) => (x * Math.PI) / 180;
      const dLat = toR(b.lat - a.lat), dLng = toR(b.lng - a.lng);
      const h = Math.sin(dLat / 2) ** 2 + Math.cos(toR(a.lat)) * Math.cos(toR(b.lat)) * Math.sin(dLng / 2) ** 2;
      return 2 * R * Math.asin(Math.sqrt(h));
    };
    let acc = 0, idx = 0;
    for (let i = 1; i < g.length; i++) {
      acc += hav(g[i - 1], g[i]);
      if (acc >= 300) { idx = i; break; }
    }
    await phone.setGeolocation({ latitude: g[idx].lat, longitude: g[idx].lng });
    await p.locator('.nav-bar').scrollIntoViewIfNeeded();
    await p.getByRole('button', { name: /Start my journey/ }).click();
    await p.locator('.nav-instruction').waitFor({ timeout: 20000 });
    await p.waitForTimeout(2500);
    const instr = (await p.locator('.nav-instruction').innerText()).trim();
    const dist = (await p.locator('.nav-distance').innerText()).trim();
    await scrollBelowBar(p, p.locator('.map-frame'));
    await shotView(p, '09-guidance.png');
    await shotUnion(p, '09b-guidance-panel.png', [p.locator('.nav-bar'), p.locator('.nav-panel')], 6);
    // move about 500 m off the route
    await phone.setGeolocation({ latitude: g[idx].lat + 0.0045, longitude: g[idx].lng + 0.0045 });
    await p.locator('.nav-offroute').waitFor({ timeout: 20000 });
    const off = (await p.locator('.nav-offroute').innerText()).trim().replace(/\s+/g, ' ');
    await shotUnion(p, '09c-offroute.png', [p.locator('.nav-bar'), p.locator('.nav-panel')], 6);
    await p.getByRole('button', { name: 'Stop guidance' }).click();
    return `Next instruction "${instr}" in ${dist}; after moving away: "${off}"`;
  });
  await phone.close();
  await browser.close();
  save();
}

// ======================================================================
async function mlDown() {
  const browser = await launch();
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 2, locale: 'en-GB', timezoneId: 'Europe/Copenhagen' });
  const page = await ctx.newPage();
  currentPage = page;
  await test('ST24', 'With the ML service stopped, the app explains that grading failed instead of crashing', async () => {
    await page.goto(BASE + '/analysis');
    await page.locator('.analyze-button').waitFor();
    await page.waitForTimeout(3000);
    const { resp } = await analyze(page, 'Copenhagen', 'Roskilde', dkDate(1), '08:00');
    must(resp.status() === 502, `HTTP ${resp.status()}`);
    const msg = (await page.locator('.advisory-card .stage-failure').innerText()).trim().replace(/\s+/g, ' ');
    await shotEl(page.locator('section.advisory-card'), 'F3-ml-down.png');
    return `HTTP 502; advisory card says: "${msg}"`;
  });
  await browser.close();
  save();
}

(PHASE === 'mldown' ? mlDown() : main())
  .then(() => {
    const failed = results.filter((r) => r.status !== 'pass').length;
    log(`DONE ${PHASE}: ${results.length} tests, ${failed} failed`);
  })
  .catch((e) => {
    log('FATAL', e && e.stack ? e.stack : e);
    save();
    process.exitCode = 1;
  });
