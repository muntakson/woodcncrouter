// Grbl Sender backoffice: receives debug logs from the Android app, serves APK
// releases, and hosts an admin dashboard with a live debug console.
// Zero npm dependencies (node:http + node:sqlite).
'use strict';

const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { DatabaseSync } = require('node:sqlite');

// ---------------------------------------------------------------- config
for (const line of fs.readFileSync(path.join(__dirname, '.env'), 'utf8').split('\n')) {
  const m = line.match(/^\s*([A-Z_]+)\s*=\s*(.*)\s*$/);
  if (m && !(m[1] in process.env)) process.env[m[1]] = m[2];
}
const PORT = Number(process.env.PORT || 3077);
const INGEST_KEY = process.env.INGEST_KEY;
const DASH_USER = process.env.DASH_USER || 'admin';
const DASH_PASSWORD = process.env.DASH_PASSWORD || '';
const APK_DIR = path.join(__dirname, 'apk');
const PUBLIC_DIR = path.join(__dirname, 'public');
const GCODE_DIR = path.join(__dirname, 'gcode');
const FIRMWARE_DIR = path.join(__dirname, 'firmware');
const MAX_LOG_ROWS = 200000;
// Bearer token for the remote-control HTTP API (scripts, other servers); the dashboard login works too
const CNC_API_KEY = process.env.CNC_API_KEY || '';

// ---------------------------------------------------------------- db
const db = new DatabaseSync(path.join(__dirname, 'data', 'logs.db'));
db.exec(`
  PRAGMA journal_mode = WAL;
  CREATE TABLE IF NOT EXISTS devices (
    id TEXT PRIMARY KEY, model TEXT, manufacturer TEXT, android TEXT, sdk INTEGER,
    app_version TEXT, app_code INTEGER, ip TEXT, first_seen INTEGER, last_seen INTEGER
  );
  CREATE TABLE IF NOT EXISTS logs (
    id INTEGER PRIMARY KEY AUTOINCREMENT, device_id TEXT, ts INTEGER, received INTEGER,
    level TEXT, tag TEXT, msg TEXT
  );
  CREATE INDEX IF NOT EXISTS logs_device ON logs(device_id, id);
  CREATE TABLE IF NOT EXISTS jobs (
    id TEXT PRIMARY KEY, device_id TEXT, file TEXT, lines INTEGER, est_sec INTEGER, source TEXT,
    started INTEGER, ended INTEGER, result TEXT, last_line INTEGER, errors INTEGER, paused_ms INTEGER
  );
  CREATE INDEX IF NOT EXISTS jobs_device ON jobs(device_id, started);
  CREATE TABLE IF NOT EXISTS job_events (
    uid TEXT PRIMARY KEY, job_id TEXT, device_id TEXT, ts INTEGER, event TEXT, line INTEGER, detail TEXT
  );
  CREATE INDEX IF NOT EXISTS job_events_job ON job_events(job_id, ts);
`);
const upsertDevice = db.prepare(`
  INSERT INTO devices (id, model, manufacturer, android, sdk, app_version, app_code, ip, first_seen, last_seen)
  VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
  ON CONFLICT(id) DO UPDATE SET model=excluded.model, manufacturer=excluded.manufacturer,
    android=excluded.android, sdk=excluded.sdk, app_version=excluded.app_version,
    app_code=excluded.app_code, ip=excluded.ip, last_seen=excluded.last_seen`);
const insertLog = db.prepare(
  'INSERT INTO logs (device_id, ts, received, level, tag, msg) VALUES (?, ?, ?, ?, ?, ?)');

// ---------------------------------------------------------------- releases
function listReleases() {
  const file = path.join(APK_DIR, 'releases.json');
  if (!fs.existsSync(file)) return [];
  return JSON.parse(fs.readFileSync(file, 'utf8')).sort((a, b) => b.versionCode - a.versionCode);
}

// ---------------------------------------------------------------- live stream (SSE)
const sseClients = new Set();
function broadcast(rows) {
  const payload = `data: ${JSON.stringify(rows)}\n\n`;
  for (const res of sseClients) res.write(payload);
}
setInterval(() => { for (const res of sseClients) res.write(': ping\n\n'); }, 25000);

// ---------------------------------------------------------------- helpers
function send(res, status, body, headers = {}) {
  const isJson = typeof body !== 'string' && !Buffer.isBuffer(body);
  res.writeHead(status, {
    'Content-Type': isJson ? 'application/json' : 'text/plain; charset=utf-8',
    'Cache-Control': 'no-store',
    ...headers,
  });
  res.end(isJson ? JSON.stringify(body) : body);
}

function readBody(req, limit = 2 * 1024 * 1024) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', (c) => {
      size += c.length;
      if (size > limit) { reject(new Error('body too large')); req.destroy(); return; }
      chunks.push(c);
    });
    req.on('end', () => resolve(Buffer.concat(chunks)));
    req.on('error', reject);
  });
}

function safeEqual(a, b) {
  const x = Buffer.from(String(a)), y = Buffer.from(String(b));
  return x.length === y.length && crypto.timingSafeEqual(x, y);
}

// The dashboard is open on the local network without sign-in. Uses the socket
// address (not X-Forwarded-For) so the check cannot be spoofed by a header.
// Requests through the public nginx proxy (cnc.iotok.org) arrive from 127.0.0.1 but
// always carry the X-Real-IP that nginx sets, so they must pass HTTP Basic auth
// (DASH_USER / DASH_PASSWORD in .env) instead.
function isLan(req) {
  if (req.headers['x-real-ip'] !== undefined) return false;
  const ip = (req.socket.remoteAddress || '').replace('::ffff:', '');
  return ip === '127.0.0.1' || ip === '::1' || /^(10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)/.test(ip);
}

function hasDashLogin(req) {
  if (!DASH_PASSWORD) return false;
  const m = /^Basic\s+(.+)$/i.exec(req.headers.authorization || '');
  if (!m) return false;
  const cred = Buffer.from(m[1], 'base64').toString('utf8');
  const i = cred.indexOf(':');
  return i >= 0 && safeEqual(cred.slice(0, i), DASH_USER) && safeEqual(cred.slice(i + 1), DASH_PASSWORD);
}

function isAdmin(req) { return isLan(req) || hasDashLogin(req); }

function clientIp(req) {
  return (req.headers['x-forwarded-for'] || req.socket.remoteAddress || '').split(',')[0].replace('::ffff:', '');
}

// ---------------------------------------------------------------- remote control (tablet <-> backoffice)
// The tablet keeps a sync loop running: POST /api/device/sync carries its live status, new console
// lines, command results and job-log events, and the reply carries the commands queued for it
// (long-poll: the server holds the request until a command arrives or `wait` ms pass).
const ONLINE_MS = 10000;
const COMMAND_TTL_MS = 30000;        // a queued command not picked up by then is dropped (never run stale)
const CONSOLE_MAX = 1000;
const CONTROL_CMDS = new Set(['run', 'resume', 'load', 'send', 'unlock', 'home', 'reset', 'zero']);
const ALL_CMDS = new Set([...CONTROL_CMDS, 'hold', 'stop', 'status']);
const cnc = new Map();               // device id -> { status, seen, console: [], seq, queue: [], waiter }
const commands = new Map();          // command id -> command record (kept 1 h)

function cncDevice(id) {
  let d = cnc.get(id);
  if (!d) { d = { status: null, seen: 0, console: [], seq: 0, queue: [], waiter: null }; cnc.set(id, d); }
  return d;
}

function publicCommand(c) {
  const { id, device, cmd, args, state, created, sent, done, ok, result } = c;
  return { id, device, cmd, args, state, created, sent, done, ok, result };
}

function wakeDevice(d) {
  if (d.waiter) { const w = d.waiter; d.waiter = null; w(); }
}

function takeCommands(d) {
  const now = Date.now(), out = [];
  for (const c of d.queue.splice(0)) {
    if (now - c.created > COMMAND_TTL_MS) { c.state = 'expired'; c.done = now; c.result = 'not picked up by the tablet in time'; continue; }
    c.state = 'sent'; c.sent = now;
    out.push({ id: c.id, cmd: c.cmd, ...c.args, age: now - c.created });
  }
  return out;
}

const upsertJob = db.prepare(`
  INSERT INTO jobs (id, device_id, file, lines, est_sec, source, started, ended, result, last_line, errors, paused_ms)
  VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
  ON CONFLICT(id) DO UPDATE SET
    file = COALESCE(excluded.file, file), lines = COALESCE(excluded.lines, lines),
    est_sec = COALESCE(excluded.est_sec, est_sec), source = COALESCE(excluded.source, source),
    started = COALESCE(excluded.started, started), ended = COALESCE(excluded.ended, ended),
    result = COALESCE(excluded.result, result), last_line = COALESCE(excluded.last_line, last_line),
    errors = COALESCE(excluded.errors, errors), paused_ms = COALESCE(excluded.paused_ms, paused_ms)`);
const insertJobEvent = db.prepare(
  'INSERT OR IGNORE INTO job_events (uid, job_id, device_id, ts, event, line, detail) VALUES (?, ?, ?, ?, ?, ?, ?)');

function storeJobEvent(deviceId, e) {
  if (!e || !e.uid || !e.job) return;
  const j = e.job, ts = Number(e.ts) || Date.now();
  const n = (v) => (v === undefined || v === null || v === '' ? null : Number(v));
  upsertJob.run(String(j.id), deviceId, j.file ?? null, n(j.lines), n(j.estSec), j.source ?? null,
    e.event === 'started' ? ts : null, e.event === 'ended' ? ts : null,
    e.event === 'ended' ? String(e.result || 'ended') : null, n(e.line), n(e.errors), n(e.pausedMs));
  insertJobEvent.run(String(e.uid), String(j.id), deviceId, ts, String(e.event).slice(0, 20), n(e.line),
    e.detail ? String(e.detail).slice(0, 500) : null);
}

/** Which tablet a remote call means: ?device=…, else the one that synced last. */
function pickDevice(id) {
  if (id) return cnc.has(id) ? id : null;
  let best = null, seen = 0;
  for (const [k, d] of cnc) if (d.seen > seen) { best = k; seen = d.seen; }
  return best;
}

function cncSummary(id) {
  const d = cnc.get(id);
  const dev = db.prepare('SELECT model, manufacturer, app_version FROM devices WHERE id = ?').get(id) || {};
  return { device: id, model: [dev.manufacturer, dev.model].filter(Boolean).join(' '), app: dev.app_version,
    online: Date.now() - d.seen < ONLINE_MS, lastSeen: d.seen, status: d.status };
}

function hasApiKey(req) {
  const m = /^Bearer\s+(.+)$/i.exec(req.headers.authorization || '');
  return !!(CNC_API_KEY && m && safeEqual(m[1].trim(), CNC_API_KEY));
}

setInterval(() => {
  const old = Date.now() - 3600 * 1000;
  for (const [k, c] of commands) if (c.created < old) commands.delete(k);
}, 600 * 1000);

// ---------------------------------------------------------------- camera relay (tablet -> backoffice -> browsers)
// The tablet's camera streams only while someone watches. A viewer (dashboard page, MJPEG client, snapshot)
// registers interest; the next sync reply tells the tablet camera.want = true, and the tablet then POSTs one
// JPEG per frame to /api/device/frame. Each frame reply says whether to keep going, so the camera stops
// within a few seconds after the last viewer leaves.
const CAM_SIZES = { '360p': [640, 360], '540p': [960, 540], '720p': [1280, 720] };
const CAM_VIEWER_MS = 4000;          // a viewer counts as watching this long after its last request
const CAM_STALE_MS = 5000;           // a frame older than this is not served as "live"
const cams = new Map();              // device id -> camera state

function camState(id) {
  let c = cams.get(id);
  if (!c) {
    c = { frame: null, seq: 0, ts: 0, w: 0, h: 0, recent: [], viewers: new Map(), waiters: new Set(), mjpeg: new Set(),
      lastError: null, bytes: 0 };
    cams.set(id, c);
  }
  return c;
}

/** What the viewers ask for: the largest size, the highest fps; null when nobody watches. */
function camWanted(id) {
  const c = cams.get(id);
  if (!c) return null;
  const now = Date.now();
  let fps = 0, size = '', quality = 0;
  const rank = (s) => Object.keys(CAM_SIZES).indexOf(s);
  const add = (v) => {
    fps = Math.max(fps, v.fps); quality = Math.max(quality, v.quality);
    if (rank(v.size) > rank(size)) size = v.size;
  };
  for (const [k, v] of c.viewers) { if (now - v.last > CAM_VIEWER_MS) c.viewers.delete(k); else add(v); }
  for (const m of c.mjpeg) add(m);
  if (!fps) return null;
  return { want: true, fps, size, width: CAM_SIZES[size][0], height: CAM_SIZES[size][1], quality };
}

function camPrefs(q) {
  const size = CAM_SIZES[q.get('size')] ? q.get('size') : '540p';
  const fps = Math.min(Math.max(Number(q.get('fps')) || 5, 1), 15);
  const quality = Math.min(Math.max(Number(q.get('quality')) || 70, 30), 90);
  return { size, fps, quality };
}

/** Register a viewer; if the camera was idle, wake the tablet's sync so it starts right away. */
function camWatch(id, key, prefs) {
  const c = camState(id);
  const wasIdle = !camWanted(id);
  c.viewers.set(key, { ...prefs, last: Date.now() });
  if (wasIdle) { const d = cnc.get(id); if (d) wakeDevice(d); }
}

function camInfo(id) {
  const c = cams.get(id), now = Date.now();
  const w = camWanted(id);
  if (!c) return { device: id, streaming: false, viewers: 0, wanted: null, frame: null };
  const recent = c.recent.filter((t) => now - t < 5000);
  return { device: id, streaming: !!c.frame && now - c.ts < CAM_STALE_MS, viewers: c.viewers.size + c.mjpeg.size,
    wanted: w, fps: +(recent.length / 5).toFixed(1),
    frame: c.frame ? { seq: c.seq, ts: c.ts, age: now - c.ts, width: c.w, height: c.h, bytes: c.frame.length } : null,
    camera: c.camera || null, lastError: c.lastError };
}

function mjpegWrite(res, c) {
  res.write(`--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${c.frame.length}\r\nX-Frame-Seq: ${c.seq}\r\n\r\n`);
  res.write(c.frame);
  res.write('\r\n');
}

const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml' };

function serveFile(res, file, extraHeaders = {}) {
  fs.stat(file, (err, st) => {
    if (err || !st.isFile()) return send(res, 404, 'not found');
    res.writeHead(200, {
      'Content-Type': MIME[path.extname(file)] || 'application/octet-stream',
      'Content-Length': st.size,
      'Cache-Control': 'no-store',
      ...extraHeaders,
    });
    fs.createReadStream(file).pipe(res);
  });
}

// ---------------------------------------------------------------- routes
async function handle(req, res) {
  const url = new URL(req.url, 'http://x');
  const p = url.pathname;
  const isGet = req.method === 'GET' || req.method === 'HEAD';

  // ---- public: app ingest, version check, APK download
  if (req.method === 'POST' && p === '/api/ingest') {
    if (!safeEqual(req.headers['x-ingest-key'] || '', INGEST_KEY)) return send(res, 401, { error: 'bad key' });
    let data;
    try { data = JSON.parse((await readBody(req)).toString('utf8')); } catch { return send(res, 400, { error: 'bad json' }); }
    const d = data.device || {};
    if (!d.id) return send(res, 400, { error: 'device.id required' });
    const now = Date.now();
    upsertDevice.run(String(d.id), d.model ?? null, d.manufacturer ?? null, d.android ?? null, d.sdk ?? null,
      d.appVersion ?? null, d.appCode ?? null, clientIp(req), now, now);
    const rows = [];
    for (const l of (Array.isArray(data.logs) ? data.logs : []).slice(0, 2000)) {
      const ts = Number(l.ts) || now;
      const level = String(l.level || 'I').slice(0, 1);
      const tag = String(l.tag || '').slice(0, 40);
      const msg = String(l.msg ?? '').slice(0, 20000);
      const r = insertLog.run(String(d.id), ts, now, level, tag, msg);
      rows.push({ id: Number(r.lastInsertRowid), device_id: String(d.id), ts, level, tag, msg });
    }
    if (rows.length) broadcast(rows);
    const latest = listReleases()[0];
    return send(res, 200, { ok: true, stored: rows.length, latestVersionCode: latest?.versionCode ?? 0 });
  }

  // ---- tablet sync loop (ingest key): status up, commands down
  if (req.method === 'POST' && p === '/api/device/sync') {
    if (!safeEqual(req.headers['x-ingest-key'] || '', INGEST_KEY)) return send(res, 401, { error: 'bad key' });
    let data;
    try { data = JSON.parse((await readBody(req)).toString('utf8')); } catch { return send(res, 400, { error: 'bad json' }); }
    const id = String(data.device?.id || '');
    if (!id) return send(res, 400, { error: 'device.id required' });
    const d = cncDevice(id);
    const now = Date.now();
    d.seen = now;
    if (data.status) d.status = { ...data.status, at: now };
    for (const l of (Array.isArray(data.console) ? data.console : []).slice(0, 500)) {
      d.console.push({ seq: ++d.seq, ts: Number(l.ts) || now, src: String(l.src || ''), text: String(l.text ?? '').slice(0, 2000) });
    }
    if (d.console.length > CONSOLE_MAX) d.console.splice(0, d.console.length - CONSOLE_MAX);
    for (const r of Array.isArray(data.results) ? data.results : []) {
      const c = commands.get(String(r.id));
      if (c && c.device === id) {
        c.state = r.ok ? 'done' : 'failed'; c.ok = !!r.ok; c.result = String(r.msg ?? '').slice(0, 2000); c.done = now;
        for (const w of c.waiters.splice(0)) w();
      }
    }
    let stored = 0;
    for (const e of Array.isArray(data.jobEvents) ? data.jobEvents.slice(0, 200) : []) { storeJobEvent(id, e); stored++; }
    let cmds = takeCommands(d);
    const wait = Math.min(Math.max(Number(data.wait) || 0, 0), 20000);
    if (!cmds.length && wait > 0) {
      let me;
      await new Promise((resolve) => {
        const t = setTimeout(resolve, wait);
        me = () => { clearTimeout(t); resolve(); };
        if (d.waiter) d.waiter();            // an older request from the same tablet: let it return
        d.waiter = me;
        res.on('close', me);                 // the tablet hung up
      });
      if (d.waiter === me) d.waiter = null;
      cmds = takeCommands(d);
    }
    if (res.destroyed) {                      // the tablet gave up: put the commands back
      for (const c of cmds) { const rec = commands.get(c.id); if (rec) { rec.state = 'queued'; d.queue.push(rec); } }
      return;
    }
    if (data.camera) {                         // the tablet's camera state (available, lens, last error)
      const c = camState(id);
      c.camera = data.camera;
      c.lastError = data.camera.error || null;
    }
    return send(res, 200, { ok: true, commands: cmds, jobEventsStored: stored, serverTime: Date.now(),
      camera: camWanted(id) || { want: false } });
  }

  // ---- camera frames from the tablet (ingest key): one JPEG per request
  if (req.method === 'POST' && p === '/api/device/frame') {
    if (!safeEqual(req.headers['x-ingest-key'] || '', INGEST_KEY)) return send(res, 401, { error: 'bad key' });
    const id = String(req.headers['x-device-id'] || '');
    if (!id) return send(res, 400, { error: 'X-Device-Id required' });
    const body = await readBody(req, 3 * 1024 * 1024);
    if (body.length < 4 || body[0] !== 0xff || body[1] !== 0xd8) return send(res, 400, { error: 'body must be a JPEG' });
    const c = camState(id), now = Date.now();
    c.frame = body; c.seq++; c.ts = now; c.bytes += body.length;
    c.w = Number(req.headers['x-frame-width']) || 0; c.h = Number(req.headers['x-frame-height']) || 0;
    c.recent.push(now);
    while (c.recent.length && now - c.recent[0] > 5000) c.recent.shift();
    for (const w of c.waiters) w();
    c.waiters.clear();
    for (const r of c.mjpeg) mjpegWrite(r.res, c);
    if (cnc.has(id)) cnc.get(id).seen = now;
    return send(res, 200, camWanted(id) || { want: false });
  }

  // ---- remote-control API: dashboard login (LAN / Basic) or "Authorization: Bearer CNC_API_KEY"
  if (p.startsWith('/api/cnc') || (p === '/api/jobs' || p.startsWith('/api/jobs/')) || p === '/api/gcode/upload'
      || (req.method === 'DELETE' && p.startsWith('/api/gcode/')) || p === '/cnc') {
    if (!isAdmin(req) && !hasApiKey(req)) {
      res.writeHead(401, { 'WWW-Authenticate': 'Basic realm="Grbl Sender backoffice", charset="UTF-8"', 'Content-Type': 'text/plain; charset=utf-8' });
      return res.end('sign in required (dashboard login or Authorization: Bearer <CNC_API_KEY>)');
    }
    if (isGet && p === '/cnc') { res.writeHead(302, { Location: '/app#remote' }); return res.end(); }

    if (isGet && p === '/api/cnc') {
      return send(res, 200, [...cnc.keys()].map(cncSummary).sort((a, b) => b.lastSeen - a.lastSeen));
    }
    if (isGet && p === '/api/cnc/status') {
      const id = pickDevice(url.searchParams.get('device'));
      if (!id) return send(res, 404, { error: 'no tablet has connected yet' });
      return send(res, 200, cncSummary(id));
    }
    if (isGet && p === '/api/cnc/console') {
      const id = pickDevice(url.searchParams.get('device'));
      if (!id) return send(res, 404, { error: 'no tablet has connected yet' });
      const after = Number(url.searchParams.get('after') || 0);
      const limit = Math.min(Number(url.searchParams.get('limit') || 300), CONSOLE_MAX);
      return send(res, 200, { device: id, lines: cnc.get(id).console.filter((l) => l.seq > after).slice(-limit) });
    }
    if (isGet && p === '/api/cnc/command') {
      const c = commands.get(url.searchParams.get('id') || '');
      return c ? send(res, 200, publicCommand(c)) : send(res, 404, { error: 'unknown command id' });
    }
    if (req.method === 'POST' && p === '/api/cnc/command') {
      let body;
      try { body = JSON.parse((await readBody(req, 64 * 1024)).toString('utf8') || '{}'); } catch { return send(res, 400, { error: 'bad json' }); }
      const cmd = String(body.cmd || '').toLowerCase();
      if (!ALL_CMDS.has(cmd)) return send(res, 400, { error: `cmd must be one of ${[...ALL_CMDS].join(', ')}` });
      const id = pickDevice(body.device);
      if (!id) return send(res, 404, { error: 'no tablet has connected yet' });
      const d = cnc.get(id);
      if (Date.now() - d.seen > ONLINE_MS) return send(res, 409, { error: 'the tablet is offline' });
      if (CONTROL_CMDS.has(cmd) && d.status && d.status.remoteControl === false) {
        return send(res, 403, { error: 'remote control is switched off on the tablet (Settings → General)' });
      }
      const args = {};
      if (body.file !== undefined) args.file = String(body.file);
      if (body.line !== undefined) args.line = String(body.line).slice(0, 200);
      if (body.axes !== undefined) args.axes = String(body.axes);
      const c = { id: crypto.randomUUID(), device: id, cmd, args, state: 'queued', created: Date.now(), waiters: [] };
      commands.set(c.id, c);
      d.queue.push(c);
      wakeDevice(d);
      if (body.wait) {
        await new Promise((resolve) => {
          const t = setTimeout(resolve, 15000);
          c.waiters.push(() => { clearTimeout(t); resolve(); });
        });
      }
      return send(res, 202, publicCommand(c));
    }
    if (isGet && p === '/api/cnc/camera') {
      const id = pickDevice(url.searchParams.get('device'));
      if (!id) return send(res, 404, { error: 'no tablet has connected yet' });
      return send(res, 200, camInfo(id));
    }
    if (isGet && (p === '/api/cnc/camera/frame' || p === '/api/cnc/camera/snapshot.jpg')) {
      const id = pickDevice(url.searchParams.get('device'));
      if (!id) return send(res, 404, { error: 'no tablet has connected yet' });
      const snapshot = p.endsWith('.jpg');
      const key = snapshot ? 'snapshot:' + crypto.randomUUID() : String(url.searchParams.get('viewer') || clientIp(req));
      const prefs = camPrefs(url.searchParams);
      camWatch(id, key, prefs);
      const c = camState(id);
      const after = snapshot ? 0 : Number(url.searchParams.get('after') || 0);
      const fresh = () => c.frame && c.seq > after && Date.now() - c.ts < CAM_STALE_MS;
      if (!fresh()) {                               // long-poll for the next frame
        let done;
        await new Promise((resolve) => {
          const t = setTimeout(resolve, snapshot ? 10000 : 8000);
          done = () => { clearTimeout(t); resolve(); };
          c.waiters.add(done);
          res.on('close', done);
        });
        c.waiters.delete(done);
        if (res.destroyed) return;
      }
      if (snapshot) c.viewers.delete(key);
      if (!fresh()) {
        const d = cnc.get(id);
        const why = !d || Date.now() - d.seen > ONLINE_MS ? 'the tablet is offline'
          : c.lastError ? c.lastError : 'no frame from the tablet yet';
        return send(res, snapshot ? 504 : 204, snapshot ? { error: why } : '', snapshot ? {} : { 'X-Camera-Status': encodeURIComponent(why) });
      }
      res.writeHead(200, { 'Content-Type': 'image/jpeg', 'Content-Length': c.frame.length, 'Cache-Control': 'no-store',
        'X-Frame-Seq': c.seq, 'X-Frame-Ts': c.ts, 'X-Frame-Width': c.w, 'X-Frame-Height': c.h,
        ...(snapshot ? { 'Content-Disposition': `inline; filename="cnc-${new Date(c.ts).toISOString().replace(/[:.]/g, '-')}.jpg"` } : {}) });
      return res.end(c.frame);
    }
    if (isGet && p === '/api/cnc/camera.mjpeg') {
      const id = pickDevice(url.searchParams.get('device'));
      if (!id) return send(res, 404, { error: 'no tablet has connected yet' });
      const c = camState(id);
      const wasIdle = !camWanted(id);
      const client = { res, ...camPrefs(url.searchParams) };
      c.mjpeg.add(client);
      if (wasIdle && cnc.has(id)) wakeDevice(cnc.get(id));
      res.writeHead(200, { 'Content-Type': 'multipart/x-mixed-replace; boundary=frame', 'Cache-Control': 'no-store',
        Connection: 'close', 'X-Accel-Buffering': 'no' });
      if (c.frame && Date.now() - c.ts < CAM_STALE_MS) mjpegWrite(res, c);
      const keep = setInterval(() => { if (c.frame) mjpegWrite(res, c); }, 20000);   // keeps proxies from timing out
      req.on('close', () => { clearInterval(keep); c.mjpeg.delete(client); });
      return;
    }
    if (isGet && p === '/api/jobs') {
      const device = url.searchParams.get('device');
      const limit = Math.min(Number(url.searchParams.get('limit') || 50), 500);
      const rows = device
        ? db.prepare('SELECT * FROM jobs WHERE device_id = ? ORDER BY started DESC LIMIT ?').all(device, limit)
        : db.prepare('SELECT * FROM jobs ORDER BY started DESC LIMIT ?').all(limit);
      return send(res, 200, rows);
    }
    if (isGet && p.startsWith('/api/jobs/')) {
      const jid = decodeURIComponent(p.slice('/api/jobs/'.length));
      const job = db.prepare('SELECT * FROM jobs WHERE id = ?').get(jid);
      if (!job) return send(res, 404, { error: 'unknown job' });
      job.events = db.prepare('SELECT ts, event, line, detail FROM job_events WHERE job_id = ? ORDER BY ts, uid').all(jid);
      return send(res, 200, job);
    }
    if (req.method === 'POST' && p === '/api/gcode/upload') {
      const name = path.basename(url.searchParams.get('name') || '');
      if (!/^[\w.\- ()]+\.(nc|gcode|ngc|tap)$/i.test(name)) return send(res, 400, { error: 'name must end in .nc, .gcode, .ngc or .tap' });
      const body = await readBody(req, 50 * 1024 * 1024);
      fs.mkdirSync(GCODE_DIR, { recursive: true });
      fs.writeFileSync(path.join(GCODE_DIR, name), body);
      return send(res, 200, { ok: true, name, size: body.length });
    }
    if (req.method === 'DELETE' && p.startsWith('/api/gcode/')) {
      const name = path.basename(decodeURIComponent(p.slice('/api/gcode/'.length)));
      if (!/^[\w.\- ()]+\.(nc|gcode|ngc|tap)$/i.test(name)) return send(res, 400, { error: 'not a G-code file' });
      const file = path.join(GCODE_DIR, name);
      if (!fs.existsSync(file)) return send(res, 404, { error: 'no such file' });
      fs.unlinkSync(file);
      const png = path.join(GCODE_DIR, name.replace(/\.[^.]+$/, '') + '_preview.png');
      if (fs.existsSync(png)) fs.unlinkSync(png);
      return send(res, 200, { ok: true, deleted: name });
    }
    return send(res, 404, { error: 'not found' });
  }

  if (isGet && p === '/api/version') {
    const latest = listReleases()[0];
    if (!latest) return send(res, 200, { versionCode: 0 });
    return send(res, 200, { ...latest, url: `/apk/${latest.file}` });
  }

  // Release history for Settings → App update (versions and notes only)
  if (isGet && p === '/api/releases') return send(res, 200, listReleases());

  if (isGet && p === '/apk/latest.apk') {
    const latest = listReleases()[0];
    if (!latest) return send(res, 404, 'no release');
    return serveFile(res, path.join(APK_DIR, latest.file), {
      'Content-Type': 'application/vnd.android.package-archive',
      'Content-Disposition': `attachment; filename="${latest.file}"`,
    });
  }

  if (isGet && /^\/apk\/[\w.\-]+\.apk$/.test(p)) {
    const name = path.basename(p);
    return serveFile(res, path.join(APK_DIR, name), {
      'Content-Type': 'application/vnd.android.package-archive',
      'Content-Disposition': `attachment; filename="${name}"`,
    });
  }

  if (p === '/health') return send(res, 200, { ok: true });

  // Public landing page; the console itself is at /app (sign-in below)
  if (isGet && (p === '/' || p === '/index.html')) return serveFile(res, path.join(PUBLIC_DIR, 'index.html'));
  if (isGet && p === '/favicon.svg') return serveFile(res, path.join(PUBLIC_DIR, 'favicon.svg'));

  // G-code files for the tablet (download in the browser, then Open in Grbl Sender)
  // Arduino firmware catalogue (newest first) and downloads, for the app's firmware updater
  if (isGet && p === '/api/firmware') {
    const file = path.join(FIRMWARE_DIR, 'firmware.json');
    const list = fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, 'utf8')) : [];
    return send(res, 200, list.sort((a, b) => b.published - a.published).map((f) => ({ ...f, url: `/firmware/${f.file}` })));
  }
  if (isGet && /^\/firmware\/[\w.\-]+\.(hex|bin)$/.test(p)) {
    const name = path.basename(p);
    return serveFile(res, path.join(FIRMWARE_DIR, name), {
      'Content-Type': 'application/octet-stream',
      'Content-Disposition': `attachment; filename="${name}"`,
    });
  }

  if (isGet && p === '/api/gcode') {
    const files = fs.existsSync(GCODE_DIR) ? fs.readdirSync(GCODE_DIR).filter((f) => /\.(nc|gcode|ngc|tap)$/i.test(f)).sort() : [];
    return send(res, 200, files.map((f) => {
      const st = fs.statSync(path.join(GCODE_DIR, f));
      const head = fs.readFileSync(path.join(GCODE_DIR, f), 'utf8').slice(0, 600).split('\n');
      const title = (head.find((l) => /^\(.*parts|^\(.*circle/i.test(l)) || '').replace(/^\(|\)$/g, '');
      const preview = f.replace(/\.[^.]+$/, '') + '_preview.png';
      const prev2 = f.replace(/_\d+mm_\d+mm\.nc$/i, '') + '_preview.png';
      const png = [preview, prev2].find((n) => fs.existsSync(path.join(GCODE_DIR, n)));
      return { name: f, size: st.size, modified: st.mtimeMs, title, preview: png || null };
    }));
  }
  if (isGet && p === '/gcode/') {
    const files = fs.existsSync(GCODE_DIR) ? fs.readdirSync(GCODE_DIR).filter((f) => /\.(nc|gcode|ngc|tap|png)$/i.test(f)).sort() : [];
    const esc = (t) => t.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
    const rows = files.map((f) => {
      const st = fs.statSync(path.join(GCODE_DIR, f));
      return `<li><a href="/gcode/${encodeURIComponent(f)}" download>${esc(f)}</a> <small>${(st.size / 1024).toFixed(0)} KB</small></li>`;
    }).join('');
    return send(res, 200, `<!doctype html><meta name=viewport content="width=device-width,initial-scale=1"><title>G-code files</title>
<style>body{font:16px system-ui;background:#15171a;color:#e3e6ea;padding:16px}a{color:#4ea1ff;font-size:18px}li{margin:12px 0}small{color:#8b939c}</style>
<h2>G-code files</h2><p>Tap to download, then use <b>Open</b> in Grbl Sender (Downloads folder).</p><ul>${rows || '<li>none</li>'}</ul>`,
      { 'Content-Type': 'text/html; charset=utf-8' });
  }
  if (isGet && /^\/gcode\/[^/]+$/.test(p)) {
    const name = path.basename(decodeURIComponent(p));
    const type = name.endsWith('.png') ? 'image/png' : 'application/octet-stream';
    return serveFile(res, path.join(GCODE_DIR, name), { 'Content-Type': type, 'Content-Disposition': `${type === 'image/png' ? 'inline' : 'attachment'}; filename="${name}"` });
  }

  // ---- admin (HTTP Basic auth)
  if (!isAdmin(req)) {
    res.writeHead(401, { 'WWW-Authenticate': 'Basic realm="Grbl Sender backoffice", charset="UTF-8"', 'Content-Type': 'text/plain; charset=utf-8' });
    return res.end('sign in required');
  }

  if (req.method === 'GET' && (p === '/app' || p === '/app/')) return serveFile(res, path.join(PUBLIC_DIR, 'app.html'));
  if (req.method === 'GET' && (p === '/app/api' || p === '/api/docs')) return serveFile(res, path.join(PUBLIC_DIR, 'api.html'));

  if (req.method === 'GET' && p === '/api/devices') {
    return send(res, 200, db.prepare(`
      SELECT d.*, (SELECT COUNT(*) FROM logs l WHERE l.device_id = d.id) AS log_count
      FROM devices d ORDER BY last_seen DESC`).all());
  }

  if (req.method === 'GET' && p === '/api/logs') {
    const where = [], args = [];
    const device = url.searchParams.get('device');
    const after = Number(url.searchParams.get('after') || 0);
    const before = Number(url.searchParams.get('before') || 0);
    const q = url.searchParams.get('q');
    const levels = url.searchParams.get('levels');
    const limit = Math.min(Number(url.searchParams.get('limit') || 500), 5000);
    if (device) { where.push('device_id = ?'); args.push(device); }
    if (after) { where.push('id > ?'); args.push(after); }
    if (before) { where.push('id < ?'); args.push(before); }
    if (q) { where.push('(msg LIKE ? OR tag LIKE ?)'); args.push(`%${q}%`, `%${q}%`); }
    if (levels) {
      const ls = levels.split('').filter((c) => 'VDIWE'.includes(c));
      if (ls.length) { where.push(`level IN (${ls.map(() => '?').join(',')})`); args.push(...ls); }
    }
    const sql = `SELECT id, device_id, ts, level, tag, msg FROM logs
      ${where.length ? 'WHERE ' + where.join(' AND ') : ''}
      ORDER BY id ${after ? 'ASC' : 'DESC'} LIMIT ?`;
    const rows = db.prepare(sql).all(...args, limit);
    if (!after) rows.reverse();
    return send(res, 200, rows);
  }

  if (req.method === 'DELETE' && p === '/api/logs') {
    const device = url.searchParams.get('device');
    const r = device ? db.prepare('DELETE FROM logs WHERE device_id = ?').run(device) : db.prepare('DELETE FROM logs').run();
    return send(res, 200, { deleted: Number(r.changes) });
  }

  if (req.method === 'GET' && p === '/api/stream') {
    res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-store', Connection: 'keep-alive' });
    res.write(': connected\n\n');
    sseClients.add(res);
    req.on('close', () => sseClients.delete(res));
    return;
  }

  return send(res, 404, 'not found');
}

// Trim old rows hourly so the db stays bounded
setInterval(() => {
  db.prepare('DELETE FROM logs WHERE id <= (SELECT MAX(id) FROM logs) - ?').run(MAX_LOG_ROWS);
}, 3600 * 1000);

http.createServer((req, res) => {
  handle(req, res).catch((e) => {
    console.error(e);
    if (!res.headersSent) send(res, 500, { error: String(e.message || e) });
  });
}).listen(PORT, '0.0.0.0', () => console.log(`backoffice listening on :${PORT}`));
