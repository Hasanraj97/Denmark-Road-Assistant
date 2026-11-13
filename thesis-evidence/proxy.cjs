// A small recording proxy between the backend and the four outside services.
//
//   live    : every request is passed on to the real service, and the answer is
//             recorded (this is still a live test, just with a log of each call)
//   replay  : recorded answers are served again and nothing leaves the laptop, so
//             repeat and concurrency timings put no load on the public servers
//   weather down : Open-Meteo answers 503, to show the seasonal fallback
//
// Control:  GET /__mode?set=live|replay   GET /__weather?down=1|0   GET /__stats
// Usage:    node proxy.cjs  (port 8099; log goes to EVIDENCE_OUT/logs/proxy.log)
'use strict';
const http = require('http');
const fs = require('fs');
const path = require('path');

const OUT = process.env.EVIDENCE_OUT || path.join(__dirname, 'output');
fs.mkdirSync(path.join(OUT, 'logs'), { recursive: true });
const LOG = path.join(OUT, 'logs', 'proxy.log');
const PORT = Number(process.env.PROXY_PORT || 8099);

const UPSTREAM = {
  nominatim: 'https://nominatim.openstreetmap.org',
  osrm: 'https://router.project-osrm.org',
  openmeteo: 'https://api.open-meteo.com',
  events: 'https://storage.googleapis.com/trafikkort-data/geojson/big-screen-events.json'
};

let mode = 'live';
let weatherDown = false;
const recorded = new Map();
const stats = { live: {}, replay: {}, missing: 0, weatherRefused: 0 };
const bump = (o, k) => (o[k] = (o[k] || 0) + 1);
const log = (line) => {
  try {
    fs.mkdirSync(path.dirname(LOG), { recursive: true });
    fs.appendFileSync(LOG, `${new Date().toISOString()} ${line}\n`);
  } catch (_) {
    // logging must never stop the proxy
  }
};

function target(urlPath) {
  // /nominatim/search?... -> https://nominatim.openstreetmap.org/search?...
  const m = urlPath.match(/^\/(nominatim|osrm|openmeteo|events)(\/.*)?$/);
  if (!m) return null;
  const service = m[1];
  const rest = m[2] || '';
  return { service, url: service === 'events' ? UPSTREAM.events : UPSTREAM[service] + rest };
}

http
  .createServer(async (req, res) => {
    const u = new URL(req.url, 'http://x');
    if (u.pathname === '/__mode') {
      mode = u.searchParams.get('set') === 'replay' ? 'replay' : 'live';
      log(`MODE ${mode}`);
      res.end(JSON.stringify({ mode }));
      return;
    }
    if (u.pathname === '/__weather') {
      weatherDown = u.searchParams.get('down') === '1';
      log(`WEATHER_DOWN ${weatherDown}`);
      res.end(JSON.stringify({ weatherDown }));
      return;
    }
    if (u.pathname === '/__stats') {
      res.setHeader('content-type', 'application/json');
      res.end(JSON.stringify({ mode, weatherDown, recorded: recorded.size, ...stats }));
      return;
    }
    const t = target(u.pathname + u.search);
    if (!t) {
      res.statusCode = 404;
      res.end('unknown path');
      return;
    }
    const key = t.url;
    if (t.service === 'openmeteo' && weatherDown) {
      stats.weatherRefused++;
      log(`503 ${t.service} (weather switched off for the fallback test)`);
      res.statusCode = 503;
      res.end('{"error":true,"reason":"switched off for the fallback test"}');
      return;
    }
    if (mode === 'replay') {
      const hit = recorded.get(key);
      if (!hit) {
        stats.missing++;
        log(`MISS ${t.service} ${key}`);
        res.statusCode = 504;
        res.end('{"error":"not recorded"}');
        return;
      }
      bump(stats.replay, t.service);
      res.statusCode = hit.status;
      res.setHeader('content-type', hit.type);
      res.end(hit.body);
      return;
    }
    const t0 = Date.now();
    try {
      const r = await fetch(t.url, {
        headers: {
          'User-Agent': req.headers['user-agent'] || 'denmark-road-condition-assistant/1.0',
          Accept: req.headers['accept'] || 'application/json'
        }
      });
      const body = Buffer.from(await r.arrayBuffer());
      const type = r.headers.get('content-type') || 'application/json';
      if (r.ok) recorded.set(key, { status: r.status, type, body });
      bump(stats.live, t.service);
      log(`LIVE ${t.service} ${r.status} ${Date.now() - t0}ms ${t.service === 'events' ? '' : key.slice(0, 160)}`);
      res.statusCode = r.status;
      res.setHeader('content-type', type);
      res.end(body);
    } catch (e) {
      log(`ERROR ${t.service} ${e.message}`);
      res.statusCode = 502;
      res.end('{"error":"upstream failed"}');
    }
  })
  .listen(PORT, '127.0.0.1', () => log(`proxy listening on ${PORT}`));
