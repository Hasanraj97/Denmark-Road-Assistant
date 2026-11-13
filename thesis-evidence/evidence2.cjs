// Denmark Road Assistant: extra evidence asked for in the supervisor's review.
// Usage: node evidence2.cjs review|replay|fallback   (run by review.ps1)
// Writes review-tests.json and review-facts.json into EVIDENCE_OUT.
'use strict';

const fs = require('fs');
const path = require('path');
const { chromium, devices } = require('playwright-core');

const OUT = process.env.EVIDENCE_OUT || path.join(__dirname, 'output');
const SHOTS = path.join(OUT, 'shots');
const BASE = process.env.APP_URL || 'http://localhost:4200';
const API = process.env.API_URL || 'http://localhost:8080';
const ML = process.env.ML_URL || 'http://127.0.0.1:8000';
const PROXY = process.env.PROXY_URL || 'http://127.0.0.1:8099';
const OPEN_METEO = process.env.OPEN_METEO_URL || 'https://api.open-meteo.com/v1/forecast';
const PHASE = process.argv[2] || 'review';
const TESTS = path.join(OUT, 'review-tests.json');
const FACTS = path.join(OUT, 'review-facts.json');
fs.mkdirSync(SHOTS, { recursive: true });

const results = fs.existsSync(TESTS) ? JSON.parse(fs.readFileSync(TESTS, 'utf8')) : [];
const facts = fs.existsSync(FACTS) ? JSON.parse(fs.readFileSync(FACTS, 'utf8')) : {};
const save = () => {
  fs.writeFileSync(TESTS, JSON.stringify(results, null, 2));
  fs.writeFileSync(FACTS, JSON.stringify(facts, null, 2));
};
const log = (...a) => console.log(new Date().toISOString().slice(11, 19), ...a);
const must = (c, m) => {
  if (!c) throw new Error(m);
};
const dkDate = (d) => new Date(Date.now() + d * 864e5).toLocaleDateString('sv-SE', { timeZone: 'Europe/Copenhagen' });
const tomorrow = dkDate(1);

let currentPage = null;
async function test(id, name, fn) {
  const t0 = Date.now();
  log('TEST', id, name);
  try {
    const detail = await fn();
    results.push({ id, name, phase: PHASE, status: 'pass', detail: detail || '', ms: Date.now() - t0 });
    log('  pass', detail || '');
  } catch (e) {
    const msg = String((e && e.message) || e).split('\n')[0].slice(0, 400);
    results.push({ id, name, phase: PHASE, status: 'fail', detail: msg, ms: Date.now() - t0 });
    log('  FAIL', msg);
    if (currentPage) await currentPage.screenshot({ path: path.join(SHOTS, `fail-${id}.png`) }).catch(() => {});
  }
  save();
}

async function launch() {
  const tries = process.env.PW_CHANNEL === 'none' ? [undefined] : ['msedge', 'chrome', undefined];
  let last;
  for (const channel of tries) {
    try {
      return await chromium.launch(channel ? { channel, headless: true } : { headless: true });
    } catch (e) {
      last = e;
    }
  }
  throw last;
}

async function hideBar(page, hidden) {
  await page.evaluate((h) => {
    const bar = document.querySelector('.topbar');
    if (bar) bar.style.visibility = h ? 'hidden' : '';
  }, hidden);
}
async function shotEl(locator, file) {
  const page = locator.page();
  await hideBar(page, true);
  await locator.scrollIntoViewIfNeeded();
  await page.waitForTimeout(500);
  await locator.screenshot({ path: path.join(SHOTS, file) });
  await hideBar(page, false);
  log('  shot', file);
}

// ---------- API helpers ----------
async function api(method, url, body, token) {
  const t0 = Date.now();
  const r = await fetch(url, {
    method,
    headers: { 'content-type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) },
    body: body ? JSON.stringify(body) : undefined
  });
  const text = await r.text();
  let json = null;
  try {
    json = JSON.parse(text);
  } catch (_) {}
  return { status: r.status, json, text, ms: Date.now() - t0 };
}
const routeReq = (o, d, t = '08:00', date = tomorrow) =>
  api('POST', API + '/api/route-and-predict', { origin: o, destination: d, selectedDepartureDate: date, selectedDepartureTime: t });

function metres(a, b) {
  const R = 6371000;
  const dLat = ((b.lat - a.lat) * Math.PI) / 180;
  const dLng = ((b.lng - a.lng) * Math.PI) / 180;
  const h = Math.sin(dLat / 2) ** 2 + Math.cos((a.lat * Math.PI) / 180) * Math.cos((b.lat * Math.PI) / 180) * Math.sin(dLng / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(h));
}
function halfway(g) {
  let total = 0;
  for (let i = 1; i < g.length; i++) total += metres(g[i - 1], g[i]);
  let walked = 0;
  for (let i = 1; i < g.length; i++) {
    const s = metres(g[i - 1], g[i]);
    if (walked + s >= total / 2 && s > 0) {
      const t = (total / 2 - walked) / s;
      return { lat: g[i - 1].lat + t * (g[i].lat - g[i - 1].lat), lng: g[i - 1].lng + t * (g[i].lng - g[i - 1].lng) };
    }
    walked += s;
  }
  return g[g.length - 1];
}
const pct = (arr, p) => {
  const s = [...arr].sort((a, b) => a - b);
  return s[Math.min(s.length - 1, Math.ceil((p / 100) * s.length) - 1)];
};
const summary = (arr) => ({ n: arr.length, median: pct(arr, 50), p95: pct(arr, 95), max: Math.max(...arr), min: Math.min(...arr) });

const TIMING_ROUTES = [
  ['Copenhagen', 'Roskilde', '16:00'],
  ['Copenhagen', 'Odense', '08:00'],
  ['Aarhus', 'Aalborg', '07:30'],
  ['Esbjerg', 'Kolding', '15:00'],
  ['Copenhagen', 'Aarhus', '08:00']
];

async function timingRounds(label) {
  const rounds = [];
  for (let round = 1; round <= 3; round++) {
    const ms = [];
    for (const [o, d, t] of TIMING_ROUTES) {
      const r = await routeReq(o, d, t);
      must(r.status === 200, `${o}-${d}: HTTP ${r.status} ${r.text.slice(0, 120)}`);
      ms.push(r.ms);
    }
    rounds.push(ms);
  }
  facts[label] = { rounds, all: summary(rounds.flat()) };
  save();
  return facts[label];
}

// ======================================================================
async function review() {
  const browser = await launch();
  facts.browser = browser.version();
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, acceptDownloads: true });
  const page = await ctx.newPage();
  currentPage = page;
  const stamp = Date.now().toString(36);
  const user = { fullName: 'Review Tester', email: `review.${stamp}@example.com`, password: 'Review-test-2026' };
  let token = null;

  await test('R01', 'Sign up with only name, email and password (phone and address left empty)', async () => {
    await page.goto(BASE + '/signup');
    await page.locator('input[formcontrolname="fullName"]').fill(user.fullName);
    await page.locator('input[formcontrolname="email"]').fill(user.email);
    await page.locator('input[formcontrolname="password"]').fill(user.password);
    await shotEl(page.locator('form').first(), 'R01-signup-optional.png');
    await page.locator('button[type="submit"]').click();
    await page.locator('.user-pill').waitFor({ timeout: 20000 });
    token = await page.evaluate(() => localStorage.getItem('road_assistant_token'));
    must(token, 'no token stored');
    const me = await api('GET', API + '/api/users/me', null, token);
    must(me.status === 200, `GET /me HTTP ${me.status}`);
    return `Account created; phone=${JSON.stringify(me.json.phone)}, address=${JSON.stringify(me.json.address)}`;
  });

  await test('R02', 'An ordinary signed-in user cannot retrain the shared model, and the app shows no retrain button', async () => {
    const r = await api('POST', API + '/api/retrain-model', {}, token);
    must(r.status === 403, `HTTP ${r.status}`);
    await page.goto(BASE + '/analysis');
    await page.locator('.ra-stat', { hasText: 'Accuracy' }).waitFor({ timeout: 60000 });
    const buttons = await page.getByRole('button', { name: /retrain/i }).count();
    must(buttons === 0, `${buttons} retrain buttons visible`);
    const anon = await api('POST', API + '/api/retrain-model', {});
    return `Signed-in user: HTTP ${r.status} "${(r.json && r.json.message) || ''}"; anonymous: HTTP ${anon.status}; retrain buttons on the page: ${buttons}`;
  });

  let mainBody = null;
  await test('R03', 'The advisory shows "Model score", a "Rough buffer" and the factors pulling the other way', async () => {
    await page.getByLabel('Starting point').fill('Copenhagen');
    await page.getByLabel('Destination', { exact: true }).fill('Aarhus');
    await page.locator('#departureDate').fill(tomorrow);
    await page.locator('#departureTime').fill('08:00');
    const respP = page.waitForResponse((r) => r.url().includes('/api/route-and-predict') && r.request().method() === 'POST', { timeout: 150000 });
    await page.getByRole('button', { name: 'Analyze Route' }).click();
    const resp = await respP;
    mainBody = await resp.json();
    must(resp.ok(), `HTTP ${resp.status()}`);
    await page.locator('.advisory-card .condition-pill').waitFor({ timeout: 60000 });
    await page.waitForTimeout(2500);
    const card = await page.locator('section.advisory-card').innerText();
    must(/Model score/.test(card), 'no "Model score" on the card');
    must(/Rough buffer/.test(card), 'no "Rough buffer" on the card');
    const counter = mainBody.prediction.counterFeatures || [];
    const shown = await page.locator('.counter-item').count();
    must(shown === counter.length, `${shown} counter rows shown, ${counter.length} in the response`);
    await shotEl(page.locator('section.advisory-card'), 'R03-advisory.png');
    await shotEl(page.locator('.explainability-footer'), 'R03-explanation.png');
    facts.mainReview = { prediction: mainBody.prediction, weather: mainBody.weather, features: mainBody.features, disclaimer: mainBody.disclaimer };
    return `${mainBody.prediction.roadConditionLabel}, model score ${Math.round(mainBody.prediction.confidence * 100)}%, ${counter.length} counter factor(s): ${counter.map((c) => c.feature).join(', ') || 'none'}; weather ${mainBody.weather.source}`;
  });

  await test('R04', 'A saved analysis can be deleted from the History page', async () => {
    const second = await routeReq('Copenhagen', 'Roskilde', '16:00');
    must(second.status === 200, 'second analysis failed');
    // The anonymous request above is not saved, so save one more through the page.
    await page.getByLabel('Starting point').fill('Copenhagen');
    await page.getByLabel('Destination', { exact: true }).fill('Roskilde');
    const respP = page.waitForResponse((r) => r.url().includes('/api/route-and-predict') && r.request().method() === 'POST', { timeout: 150000 });
    await page.getByRole('button', { name: 'Analyze Route' }).click();
    await respP;
    await page.getByRole('link', { name: 'History', exact: true }).click();
    await page.locator('.history-item').first().waitFor({ timeout: 20000 });
    const before = await page.locator('.history-item').count();
    const list = await api('GET', API + '/api/history', null, token);
    const firstId = list.json[0].id;
    await page.locator('.history-item').first().locator('.delete-button').click();
    await shotEl(page.locator('.history-item').first(), 'R04-history-confirm.png');
    await page.locator('.history-item').first().getByRole('button', { name: 'Confirm delete' }).click();
    await page.waitForFunction((n) => document.querySelectorAll('.history-item').length === n - 1, before, { timeout: 15000 });
    const after = await page.locator('.history-item').count();
    const gone = await api('GET', API + `/api/history/${firstId}`, null, token);
    must(gone.status === 404, `deleted item still answers HTTP ${gone.status}`);
    await page.screenshot({ path: path.join(SHOTS, 'R04-history-after.png') });
    return `${before} saved analyses before, ${after} after; GET /api/history/${firstId} now HTTP ${gone.status}`;
  });

  await test('R05', 'The user can download everything the app stores about them', async () => {
    await page.getByRole('link', { name: 'Profile', exact: true }).click().catch(async () => page.goto(BASE + '/profile'));
    await page.locator('#privacy-heading').waitFor({ timeout: 20000 });
    await shotEl(page.locator('section.profile-privacy'), 'R05-your-data.png');
    const [dl] = await Promise.all([page.waitForEvent('download', { timeout: 20000 }), page.getByRole('button', { name: 'Download my data' }).click()]);
    const file = path.join(OUT, 'review-export.json');
    await dl.saveAs(file);
    const raw = fs.readFileSync(file, 'utf8');
    const data = JSON.parse(raw);
    must(data.profile && data.profile.email === user.email, 'profile missing from export');
    must(Array.isArray(data.savedAnalyses), 'savedAnalyses missing');
    must(!/password/i.test(raw), 'export mentions a password');
    facts.exportKeys = Object.keys(data);
    return `Export has ${Object.keys(data).join(', ')}; ${data.savedAnalyses.length} saved analyses; no password field`;
  });

  await test('R06', 'The user can delete the account, after which the login no longer works', async () => {
    await page.getByRole('button', { name: 'Delete my account' }).click();
    await shotEl(page.locator('section.profile-privacy'), 'R06-delete-confirm.png');
    await page.getByRole('button', { name: 'Yes, delete everything' }).click();
    await page.waitForURL(/\/analysis/, { timeout: 20000 });
    const login = await api('POST', API + '/api/auth/login', { email: user.email, password: user.password });
    must(login.status === 401, `login after deletion: HTTP ${login.status}`);
    return `Redirected to /analysis; login with the deleted account: HTTP ${login.status}`;
  });

  // ---------------- keyboard, focus and touch targets ----------------
  await test('R07', 'Keyboard: the scrolling areas can be reached with Tab and every focus stop is visible', async () => {
    await page.goto(BASE + '/analysis');
    const { status } = await (async () => {
      await page.getByLabel('Starting point').fill('Odense');
      await page.getByLabel('Destination', { exact: true }).fill('Aarhus');
      const respP = page.waitForResponse((r) => r.url().includes('/api/route-and-predict'), { timeout: 150000 });
      await page.getByRole('button', { name: 'Analyze Route' }).click();
      const r = await respP;
      await page.locator('.advisory-card .condition-pill').waitFor({ timeout: 60000 });
      await page.waitForTimeout(2500);
      return { status: r.status() };
    })();
    must(status === 200, `analysis HTTP ${status}`);
    // Record how every focusable element looks before it has focus, so a focus
    // indicator can be detected as any visible change (outline, shadow, border, background).
    await page.evaluate(() => {
      window.scrollTo(0, 0);
      if (document.activeElement) document.activeElement.blur();
      const look = (el) => {
        const c = getComputedStyle(el);
        return [c.outlineStyle, c.outlineWidth, c.outlineColor, c.boxShadow, c.borderTopColor, c.borderBottomColor, c.backgroundColor].join('|');
      };
      window.__kbLook = look;
      window.__kbBefore = {};
      document.querySelectorAll('a[href], button, input, select, textarea, [tabindex]').forEach((el, i) => {
        el.dataset.kbIdx = String(i);
        window.__kbBefore[i] = look(el);
      });
    });
    const stops = [];
    for (let i = 0; i < 150; i++) {
      await page.keyboard.press('Tab');
      const s = await page.evaluate(() => {
        const el = document.activeElement;
        if (!el || el === document.body) return null;
        const now = window.__kbLook(el);
        const before = el.dataset.kbIdx !== undefined ? window.__kbBefore[el.dataset.kbIdx] : null;
        const c = getComputedStyle(el);
        const visible = (before !== null && now !== before) || (c.outlineStyle !== 'none' && parseFloat(c.outlineWidth) > 0) || c.boxShadow !== 'none';
        const label = (el.getAttribute('aria-label') || el.innerText || el.getAttribute('placeholder') || el.id || el.tagName).trim().slice(0, 40);
        const cls = typeof el.className === 'string' ? el.className.slice(0, 40) : '';
        return { key: el.dataset.kbIdx || label, tag: el.tagName.toLowerCase(), cls, label, visible };
      });
      if (s) stops.push(s);
    }
    const reached = (re) => stops.some((s) => re.test(s.cls) || re.test(s.label));
    const regions = {
      explanation: reached(/compact-inputs-list|What shaped/i),
      weatherStrip: reached(/weather-strip|hourly/i),
      directions: reached(/nav-steps|directions/i)
    };
    const unique = [...new Map(stops.map((s) => [s.key, s])).values()];
    const invisible = unique.filter((s) => !s.visible);
    facts.keyboard = { tabPresses: 150, uniqueStops: unique.length, regions, invisible: invisible.slice(0, 20), invisibleCount: invisible.length };
    save();
    must(regions.explanation && regions.weatherStrip && regions.directions, 'not reached: ' + JSON.stringify(regions));
    return `${unique.length} different elements reached in 150 Tab presses; explanation, weather strip and directions all reached; ${invisible.length} of them showed no visible change when focused`;
  });

  // axe again on the pages with problems before (and the rest)
  const AXE = fs.readFileSync(require.resolve('axe-core/axe.min.js'), 'utf8');
  async function audit(name) {
    await page.addScriptTag({ content: AXE });
    const r = await page.evaluate(async () => {
      // eslint-disable-next-line no-undef
      const res = await axe.run(document, { runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'] } });
      return {
        passes: res.passes.length,
        passIds: res.passes.map((p) => p.id),
        violations: res.violations.map((v) => ({ id: v.id, nodes: v.nodes.length, sample: v.nodes.slice(0, 3).map((n) => n.target.join(' ')) }))
      };
    });
    facts.axeAfter = facts.axeAfter || {};
    facts.axeAfter[name] = r;
    save();
    return r;
  }
  await test('R08', 'Automated accessibility check repeated after the contrast and keyboard fixes', async () => {
    const out = [];
    out.push(['analysis-result', await audit('analysis-result')]);
    await page.goto(BASE + '/login');
    await page.locator('input[formcontrolname="email"]').fill('nobody@example.com');
    await page.locator('input[formcontrolname="password"]').fill('wrong-password');
    await page.locator('button[type="submit"]').click();
    await page.locator('.auth-error').waitFor({ timeout: 15000 }).catch(() => {});
    out.push(['login-error', await audit('login-error')]);
    await page.goto(BASE + '/signup');
    await page.waitForTimeout(800);
    out.push(['signup', await audit('signup')]);
    // a fresh account for History and Profile
    const u2 = { fullName: 'Axe Tester', email: `axe.${stamp}@example.com`, password: 'Review-test-2026' };
    const s = await api('POST', API + '/api/auth/signup', u2);
    await page.evaluate((t) => localStorage.setItem('road_assistant_token', t), s.json.token);
    await routeReq('Copenhagen', 'Roskilde', '16:00');
    await page.goto(BASE + '/analysis');
    await page.getByLabel('Starting point').fill('Copenhagen');
    await page.getByLabel('Destination', { exact: true }).fill('Roskilde');
    const respP = page.waitForResponse((r) => r.url().includes('/api/route-and-predict'), { timeout: 150000 });
    await page.getByRole('button', { name: 'Analyze Route' }).click();
    await respP;
    await page.goto(BASE + '/history');
    await page.locator('.history-item').first().waitFor({ timeout: 20000 });
    await page.locator('.history-item').first().click();
    await page.waitForTimeout(1500);
    out.push(['history', await audit('history')]);
    await page.goto(BASE + '/profile');
    await page.locator('#privacy-heading').waitFor({ timeout: 20000 });
    out.push(['profile', await audit('profile')]);
    await api('DELETE', API + '/api/users/me', null, s.json.token);
    return out.map(([n, r]) => `${n}: ${r.passes} rules passed, ${r.violations.map((v) => `${v.id} x${v.nodes}`).join(', ') || 'no problems'}`).join('; ');
  });
  await ctx.close();

  // ---------------- phone: touch targets ----------------
  const phone = await browser.newContext({ ...devices['Pixel 7'] });
  const p = await phone.newPage();
  currentPage = p;
  await test('R09', 'Touch targets on a phone-sized screen (Pixel 7 size)', async () => {
    await p.goto(BASE + '/analysis');
    await p.getByLabel('Starting point').fill('Copenhagen');
    await p.getByLabel('Destination', { exact: true }).fill('Roskilde');
    const respP = p.waitForResponse((r) => r.url().includes('/api/route-and-predict'), { timeout: 150000 });
    await p.getByRole('button', { name: 'Analyze Route' }).click();
    await respP;
    await p.locator('.advisory-card .condition-pill').waitFor({ timeout: 60000 });
    await p.waitForTimeout(2000);
    const sizes = await p.evaluate(() =>
      [...document.querySelectorAll('button, a[href], input, select, textarea, [role="button"]')]
        .filter((e) => {
          const r = e.getBoundingClientRect();
          const cs = getComputedStyle(e);
          return r.width > 0 && r.height > 0 && cs.visibility !== 'hidden' && cs.display !== 'none';
        })
        .map((e) => {
          const r = e.getBoundingClientRect();
          return { w: Math.round(r.width), h: Math.round(r.height), what: (e.getAttribute('aria-label') || e.innerText || e.tagName).trim().slice(0, 30) };
        })
    );
    const under24 = sizes.filter((s) => s.w < 24 || s.h < 24);
    const under44 = sizes.filter((s) => s.w < 44 || s.h < 44);
    facts.touch = { total: sizes.length, under24: under24.length, under44: under44.length, examples24: under24.slice(0, 12), examples44: under44.slice(0, 20) };
    save();
    return `${sizes.length} controls; ${under24.length} smaller than 24x24 px; ${under44.length} smaller than 44x44 px`;
  });
  await phone.close();
  await browser.close();

  // ---------------- weather sampling: old midpoint vs halfway along the route ----------------
  await test('R10', 'Weather sampling: straight-line midpoint at departure hour versus halfway along the route at the passing hour', async () => {
    const pairs = [
      ['Copenhagen', 'Aarhus'],
      ['Copenhagen', 'Aalborg'],
      ['Esbjerg', 'Copenhagen'],
      ['Copenhagen', 'Odense'],
      ['Aarhus', 'Aalborg'],
      ['Odense', 'Esbjerg'],
      ['Kolding', 'Aarhus'],
      ['Copenhagen', 'Roskilde']
    ];
    const rows = [];
    for (const [o, d] of pairs) {
      const r = await routeReq(o, d, '08:00');
      must(r.status === 200, `${o}-${d}: HTTP ${r.status}`);
      const b = r.json;
      const g = b.route.geometry;
      const mid = { lat: (g[0].lat + g[g.length - 1].lat) / 2, lng: (g[0].lng + g[g.length - 1].lng) / 2 };
      const half = halfway(g);
      const offRoute = Math.min(...g.filter((_, i) => i % 3 === 0).map((pt) => metres(mid, pt))) / 1000;
      // the old method: straight-line midpoint, at the departure hour
      const q = `${OPEN_METEO}?latitude=${mid.lat.toFixed(4)}&longitude=${mid.lng.toFixed(4)}&hourly=temperature_2m,precipitation,relative_humidity_2m,wind_speed_10m&timezone=Europe%2FCopenhagen&forecast_days=7`;
      const om = await (await fetch(q)).json();
      const idx = om.hourly.time.indexOf(`${tomorrow}T08:00`);
      const oldW = {
        temperatureC: om.hourly.temperature_2m[idx],
        rainfallMm: Math.max(0, om.hourly.precipitation[idx]),
        humidity: Math.min(1, Math.max(0, om.hourly.relative_humidity_2m[idx] / 100)),
        windSpeedKmh: om.hourly.wind_speed_10m[idx],
        source: 'open-meteo'
      };
      const f = b.features;
      let urban = f.urbanDensity;
      if (f.distanceKm < 20) urban -= 0.15;
      else if (f.distanceKm > 100) urban += 0.15;
      const obs = (w) => ({ observation: { distanceKm: f.distanceKm, durationMin: f.estimatedDurationMin, departureIso: `${tomorrow}T08:00`, urbanSignal: urban, weather: w } });
      const pNew = (await api('POST', ML + '/predict-road-condition', obs(b.weather))).json;
      const pOld = (await api('POST', ML + '/predict-road-condition', obs(oldW))).json;
      rows.push({
        route: `${o}-${d}`,
        km: b.route.distanceKm,
        minutes: Math.round(b.route.durationMinutes),
        pointGapKm: +(metres(mid, half) / 1000).toFixed(1),
        midpointOffRouteKm: +offRoute.toFixed(1),
        oldWeather: oldW,
        newWeather: b.weather,
        apiGrade: b.prediction.roadConditionLabel,
        newGrade: pNew.roadConditionLabel,
        oldGrade: pOld.roadConditionLabel,
        oldWeatherRisk: pOld.features.weatherRisk,
        newWeatherRisk: pNew.features.weatherRisk
      });
    }
    facts.weatherSampling = rows;
    save();
    const changed = rows.filter((r) => r.oldGrade !== r.newGrade).length;
    const consistent = rows.every((r) => r.apiGrade === r.newGrade);
    return `${rows.length} routes; gap between the two points ${Math.min(...rows.map((r) => r.pointGapKm))} to ${Math.max(...rows.map((r) => r.pointGapKm))} km; grade differs on ${changed}; reconstruction matches the API on all routes: ${consistent}`;
  });

  // ---------------- live timings ----------------
  await test('R11', 'Live timings: five routes, three rounds, one request at a time', async () => {
    const t = await timingRounds('timingLive');
    return `median ${t.all.median} ms, p95 ${t.all.p95} ms, max ${t.all.max} ms over ${t.all.n} requests`;
  });
}

// ======================================================================
async function replay() {
  await fetch(PROXY + '/__mode?set=replay');
  const before = await (await fetch(PROXY + '/__stats')).json();
  await test('P01', 'Replayed outside services: five routes, three rounds, one request at a time', async () => {
    const t = await timingRounds('timingReplay');
    return `median ${t.all.median} ms, p95 ${t.all.p95} ms, max ${t.all.max} ms over ${t.all.n} requests`;
  });
  for (const n of [5, 10, 20]) {
    await test(`P0${n === 5 ? 2 : n === 10 ? 3 : 4}`, `${n} analyses sent at the same moment (replayed outside services)`, async () => {
      const jobs = [];
      for (let i = 0; i < n; i++) {
        const [o, d, t] = TIMING_ROUTES[i % TIMING_ROUTES.length];
        jobs.push(routeReq(o, d, t));
      }
      const t0 = Date.now();
      const res = await Promise.all(jobs);
      const wall = Date.now() - t0;
      const ok = res.filter((r) => r.status === 200).length;
      const s = summary(res.map((r) => r.ms));
      facts[`concurrent${n}`] = { ok, wall, ...s, statuses: res.map((r) => r.status) };
      save();
      must(ok === n, `${n - ok} of ${n} failed: ${res.filter((r) => r.status !== 200).map((r) => r.status).join(',')}`);
      return `all ${n} answered 200; median ${s.median} ms, p95 ${s.p95} ms, max ${s.max} ms; all done after ${wall} ms`;
    });
  }
  const after = await (await fetch(PROXY + '/__stats')).json();
  facts.proxyReplay = { before, after };
  save();
  await fetch(PROXY + '/__mode?set=live');
}

// ======================================================================
async function fallback() {
  await fetch(PROXY + '/__weather?down=1');
  const browser = await launch();
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await ctx.newPage();
  currentPage = page;
  await test('F01', 'With Open-Meteo unreachable, the API says the weather is a seasonal estimate (regression test for the source bug)', async () => {
    const r = await routeReq('Copenhagen', 'Aarhus', '08:00');
    must(r.status === 200, `HTTP ${r.status}`);
    const b = r.json;
    must(b.weather.source === 'seasonal-estimate', `source ${b.weather.source}`);
    must(/Live weather was not available/.test(b.disclaimer), 'disclaimer still claims live weather');
    must(/seasonal average/.test(b.prediction.advisory), 'advisory does not mention the seasonal average');
    must(!/live Open-Meteo forecast/.test(b.prediction.advisory), 'advisory still says live forecast');
    facts.fallback = { weather: b.weather, disclaimer: b.disclaimer, advisory: b.prediction.advisory };
    save();
    return `weather.source=${b.weather.source}; advisory: "${b.prediction.advisory.match(/[^.]*seasonal average[^.]*\./)[0].trim()}"`;
  });
  await test('F02', 'The same fallback is shown in the app', async () => {
    await page.goto(BASE + '/analysis');
    await page.getByLabel('Starting point').fill('Copenhagen');
    await page.getByLabel('Destination', { exact: true }).fill('Aarhus');
    await page.locator('#departureDate').fill(tomorrow);
    await page.locator('#departureTime').fill('08:00');
    const respP = page.waitForResponse((r) => r.url().includes('/api/route-and-predict'), { timeout: 150000 });
    await page.getByRole('button', { name: 'Analyze Route' }).click();
    await respP;
    await page.locator('.advisory-card .condition-pill').waitFor({ timeout: 60000 });
    await page.waitForTimeout(2000);
    const intro = await page.locator('.explainability-footer .mini-card-intro').first().innerText();
    must(/Live weather was not available/.test(intro), 'explanation intro does not mention the fallback');
    const cardText = await page.locator('section.advisory-card').innerText();
    must(/seasonal average/.test(cardText), 'advisory card does not mention the seasonal average');
    await shotEl(page.locator('section.advisory-card'), 'F02-fallback-advisory.png');
    await shotEl(page.locator('.explainability-footer'), 'F02-fallback-explanation.png');
    return 'Advisory card and explanation both say a seasonal average was used';
  });
  await browser.close();
  await fetch(PROXY + '/__weather?down=0');
}

(async () => {
  try {
    if (PHASE === 'review') await review();
    else if (PHASE === 'replay') await replay();
    else if (PHASE === 'fallback') await fallback();
    log('DONE', PHASE);
  } catch (e) {
    log('FATAL', e.message);
    process.exitCode = 1;
  }
})();
