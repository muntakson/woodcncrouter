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
const APK_DIR = path.join(__dirname, 'apk');
const PUBLIC_DIR = path.join(__dirname, 'public');
const GCODE_DIR = path.join(__dirname, 'gcode');
const FIRMWARE_DIR = path.join(__dirname, 'firmware');
const MAX_LOG_ROWS = 200000;

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

// No sign-in: the dashboard is open to the local network only. Uses the socket
// address (not X-Forwarded-For) so the check cannot be spoofed by a header.
function isAdmin(req) {
  const ip = (req.socket.remoteAddress || '').replace('::ffff:', '');
  return ip === '127.0.0.1' || ip === '::1' || /^(10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)/.test(ip);
}

function clientIp(req) {
  return (req.headers['x-forwarded-for'] || req.socket.remoteAddress || '').split(',')[0].replace('::ffff:', '');
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

  if (isGet && p === '/api/version') {
    const latest = listReleases()[0];
    if (!latest) return send(res, 200, { versionCode: 0 });
    return send(res, 200, { ...latest, url: `/apk/${latest.file}` });
  }

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

  // G-code files for the tablet (download in the browser, then Open in Grbl Sender)
  // Arduino firmware catalogue (newest first) and downloads, for the app's firmware updater
  if (isGet && p === '/api/firmware') {
    const file = path.join(FIRMWARE_DIR, 'firmware.json');
    const list = fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, 'utf8')) : [];
    return send(res, 200, list.sort((a, b) => b.published - a.published).map((f) => ({ ...f, url: `/firmware/${f.file}` })));
  }
  if (isGet && /^\/firmware\/[\w.\-]+\.hex$/.test(p)) {
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
    return send(res, 403, 'dashboard is available on the local network only');
  }

  if (req.method === 'GET' && (p === '/' || p === '/index.html')) return serveFile(res, path.join(PUBLIC_DIR, 'index.html'));

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

  if (req.method === 'GET' && p === '/api/releases') return send(res, 200, listReleases());

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
