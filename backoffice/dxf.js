// DXF reader for the AI CAM page: ASCII and binary DXF (R12 … 2018) to plain 2D geometry in mm.
// INSERTs are exploded (nested, rotated, scaled, mirrored), OCS extrusion (0,0,-1) is honoured, and
// LINE / ARC / CIRCLE / (LW)POLYLINE / ELLIPSE / SPLINE are chained end to end into loops that the
// viewer draws and the CAM planner offsets. TEXT / MTEXT come along as labels.
'use strict';

const UNIT_MM = { 0: 1, 1: 25.4, 2: 304.8, 4: 1, 5: 10, 6: 1000, 8: 0.0000254, 9: 0.0254, 10: 914.4, 14: 100, 15: 10000 };
const UNIT_NAME = { 0: 'unitless (mm assumed)', 1: 'inches', 2: 'feet', 4: 'mm', 5: 'cm', 6: 'm', 8: 'microinches', 9: 'mils', 10: 'yards' };
const CHAIN_TOL = 0.01;     // mm: endpoints closer than this are joined
const ARC_TOL = 0.01;       // mm: chord deviation when arcs are turned into points

// ---------------------------------------------------------------- tags
// Value type by group code (same table as ezdxf / the DXF reference)
function codeType(c) {
  if ((c >= 10 && c < 60) || (c >= 110 && c < 150) || (c >= 210 && c < 240) || (c >= 460 && c < 470) || (c >= 1010 && c < 1060)) return 'd';
  if ((c >= 60 && c < 80) || (c >= 170 && c < 180) || (c >= 270 && c < 290) || (c >= 370 && c < 390) || (c >= 400 && c < 410) || (c >= 1060 && c < 1071)) return 'h';
  if ((c >= 90 && c < 100) || (c >= 420 && c < 460) || c === 1071) return 'i';
  if (c >= 160 && c < 170) return 'q';
  if (c >= 290 && c < 300) return 'b';
  if ((c >= 310 && c < 320) || c === 1004) return 'x';
  return 's';
}

const BINARY_SENTINEL = 'AutoCAD Binary DXF\r\n\x1a\x00';

function binaryTags(buf) {
  const head = buf.subarray(0, 1024).toString('latin1');
  let ver = 'AC1009';
  const vi = head.indexOf('$ACADVER');
  if (vi >= 0) { let s = vi + 10; if (head[s] !== 'A') s++; ver = head.slice(s, s + 6); }
  const r12 = ver <= 'AC1009';
  const enc = ver >= 'AC1021' ? 'utf8' : 'latin1';
  const tags = [];
  let i = 22;
  while (i < buf.length) {
    let code = buf[i];
    if (r12) {
      if (code === 255) { code = buf.readUInt16LE(i + 1); i += 3; } else i += 1;
    } else { code = buf.readUInt16LE(i); i += 2; }
    let v;
    switch (codeType(code)) {
      case 'd': v = buf.readDoubleLE(i); i += 8; break;
      case 'h': v = buf.readInt16LE(i); i += 2; break;
      case 'i': v = buf.readInt32LE(i); i += 4; break;
      case 'q': v = Number(buf.readBigInt64LE(i)); i += 8; break;
      case 'b': v = buf[i]; i += 1; break;
      case 'x': { const n = buf[i]; v = null; i += 1 + n; break; }
      default: {
        let e = buf.indexOf(0, i);
        if (e < 0) e = buf.length;
        v = buf.toString(enc, i, e); i = e + 1;
      }
    }
    tags.push([code, v]);
  }
  return { tags, version: ver };
}

function asciiTags(buf) {
  let text = buf.toString('utf8');
  if (text.includes('�')) text = buf.toString('latin1');
  const lines = text.split(/\r?\n/);
  const tags = [];
  for (let i = 0; i + 1 < lines.length; i += 2) {
    const code = parseInt(lines[i].trim(), 10);
    if (Number.isNaN(code)) throw new Error(`not a DXF file (bad group code at line ${i + 1})`);
    const raw = lines[i + 1];
    const t = codeType(code);
    tags.push([code, t === 'd' ? parseFloat(raw) : t === 's' || t === 'x' ? raw.trim() : parseInt(raw, 10)]);
  }
  const vi = tags.findIndex(([c, v]) => c === 9 && v === '$ACADVER');
  return { tags, version: vi >= 0 ? String(tags[vi + 1]?.[1] || '') : '' };
}

// Records: a group-code-0 tag and the tags up to the next one
function records(tags) {
  const out = [];
  let cur = null;
  for (const t of tags) {
    if (t[0] === 0) { cur = { type: String(t[1]), tags: [] }; out.push(cur); } else if (cur) cur.tags.push(t);
  }
  return out;
}
const tagv = (r, code, def) => { const t = r.tags.find((x) => x[0] === code); return t ? t[1] : def; };

// ---------------------------------------------------------------- 2D affine [a b c d e f]: x' = a x + c y + e, y' = b x + d y + f
const I = [1, 0, 0, 1, 0, 0];
const mul = (m, n) => [m[0] * n[0] + m[2] * n[1], m[1] * n[0] + m[3] * n[1], m[0] * n[2] + m[2] * n[3], m[1] * n[2] + m[3] * n[3],
  m[0] * n[4] + m[2] * n[5] + m[4], m[1] * n[4] + m[3] * n[5] + m[5]];
const ap = (m, p) => [m[0] * p[0] + m[2] * p[1] + m[4], m[1] * p[0] + m[3] * p[1] + m[5]];
const det = (m) => m[0] * m[3] - m[1] * m[2];
const scaleOf = (m) => Math.sqrt(Math.abs(det(m)));
function uniform(m) {
  const l1 = Math.hypot(m[0], m[1]), l2 = Math.hypot(m[2], m[3]);
  return Math.abs(l1 - l2) < 1e-9 * Math.max(l1, l2, 1) && Math.abs(m[0] * m[2] + m[1] * m[3]) < 1e-9 * Math.max(l1 * l2, 1);
}
const MIRROR_X = [-1, 0, 0, 1, 0, 0];   // OCS with extrusion (0,0,-1)
const ocs = (r) => (tagv(r, 230, 1) < 0 ? MIRROR_X : I);

// ---------------------------------------------------------------- primitives
// line {a,b} · arc {c,r,a0,a1,ccw} (radians, sweep from a0 to a1) · circle {c,r} · poly {pts:[[x,y,bulge]],closed}
function arcSweep(a0, a1, ccw) {
  let s = ccw ? a1 - a0 : a0 - a1;
  while (s <= 1e-12) s += 2 * Math.PI;
  while (s > 2 * Math.PI + 1e-12) s -= 2 * Math.PI;
  return ccw ? s : -s;
}
function arcPoints(c, r, a0, sweep, tol = ARC_TOL) {
  const step = r > tol ? 2 * Math.acos(Math.max(-1, 1 - tol / r)) : Math.PI / 4;
  const n = Math.max(2, Math.ceil(Math.abs(sweep) / Math.min(step, Math.PI / 18)));
  const pts = [];
  for (let i = 0; i <= n; i++) { const a = a0 + sweep * i / n; pts.push([c[0] + r * Math.cos(a), c[1] + r * Math.sin(a)]); }
  return pts;
}
function bulgeArc(p1, p2, b) {
  const th = 4 * Math.atan(b), L = Math.hypot(p2[0] - p1[0], p2[1] - p1[1]);
  const r = L / (2 * Math.sin(Math.abs(th) / 2));
  const mx = (p1[0] + p2[0]) / 2, my = (p1[1] + p2[1]) / 2;
  const h = r * Math.cos(Math.abs(th) / 2) * Math.sign(b) * (Math.abs(th) > Math.PI ? -1 : 1);
  const nx = -(p2[1] - p1[1]) / L, ny = (p2[0] - p1[0]) / L;
  const c = [mx + nx * h, my + ny * h];
  return { c, r, a0: Math.atan2(p1[1] - c[1], p1[0] - c[0]), a1: Math.atan2(p2[1] - c[1], p2[0] - c[0]), ccw: b > 0 };
}
// Primitive -> points (used when a transform is not a similarity, and for the viewer)
function primPoints(p) {
  if (p.t === 'line') return [p.a, p.b];
  if (p.t === 'arc') return arcPoints(p.c, p.r, p.a0, arcSweep(p.a0, p.a1, p.ccw));
  if (p.t === 'circle') return arcPoints(p.c, p.r, 0, 2 * Math.PI);
  const out = [];
  const n = p.pts.length;
  for (let i = 0; i < n; i++) {
    const a = p.pts[i], b = p.pts[(i + 1) % n];
    if (i === n - 1 && !p.closed) { out.push([a[0], a[1]]); break; }
    if (a[2]) { const g = bulgeArc(a, b, a[2]); out.push(...arcPoints(g.c, g.r, g.a0, arcSweep(g.a0, g.a1, g.ccw)).slice(0, -1)); } else out.push([a[0], a[1]]);
  }
  if (p.closed && out.length) out.push(out[0]);
  return out;
}
function transformPrim(p, m) {
  if (m === I) return p;
  const sim = uniform(m), flip = det(m) < 0, s = scaleOf(m);
  if (p.t === 'line') return { ...p, a: ap(m, p.a), b: ap(m, p.b) };
  if (!sim) { const pts = primPoints(p).map((q) => ap(m, q)); return { t: 'poly', layer: p.layer, pts: pts.map((q) => [q[0], q[1], 0]), closed: p.t === 'circle' || !!p.closed }; }
  if (p.t === 'circle') return { ...p, c: ap(m, p.c), r: p.r * s };
  if (p.t === 'arc') {
    const c = ap(m, p.c);
    const ang = (a) => { const q = ap(m, [p.c[0] + Math.cos(a), p.c[1] + Math.sin(a)]); return Math.atan2(q[1] - c[1], q[0] - c[0]); };
    return { ...p, c, r: p.r * s, a0: ang(p.a0), a1: ang(p.a1), ccw: flip ? !p.ccw : p.ccw };
  }
  return { ...p, pts: p.pts.map((q) => { const t = ap(m, q); return [t[0], t[1], flip ? -(q[2] || 0) : (q[2] || 0)]; }) };
}

// B-spline by de Boor (weights honoured); fit points when there are no control points
function splinePoints(r) {
  const deg = tagv(r, 71, 3), flags = tagv(r, 70, 0);
  const knots = [], ctrl = [], weights = [], fit = [];
  let cx = null, fx = null;
  for (const [c, v] of r.tags) {
    if (c === 40) knots.push(v);
    else if (c === 41) weights.push(v);
    else if (c === 10) cx = v; else if (c === 20 && cx !== null) { ctrl.push([cx, v]); cx = null; }
    else if (c === 11) fx = v; else if (c === 21 && fx !== null) { fit.push([fx, v]); fx = null; }
  }
  if (ctrl.length < deg + 1 || knots.length !== ctrl.length + deg + 1) return { pts: fit.length ? fit : ctrl, closed: !!(flags & 1) };
  const w = weights.length === ctrl.length ? weights : ctrl.map(() => 1);
  const t0 = knots[deg], t1 = knots[knots.length - deg - 1];
  const N = Math.min(2000, Math.max(16, ctrl.length * 12));
  const pts = [];
  for (let s = 0; s <= N; s++) {
    const t = t0 + (t1 - t0) * s / N;
    let k = deg;
    while (k < knots.length - deg - 2 && t >= knots[k + 1]) k++;
    const d = [];
    for (let j = 0; j <= deg; j++) { const P = ctrl[k - deg + j], ww = w[k - deg + j]; d.push([P[0] * ww, P[1] * ww, ww]); }
    for (let rr = 1; rr <= deg; rr++) {
      for (let j = deg; j >= rr; j--) {
        const i = k - deg + j, den = knots[i + deg - rr + 1] - knots[i];
        const a = den ? (t - knots[i]) / den : 0;
        d[j] = [(1 - a) * d[j - 1][0] + a * d[j][0], (1 - a) * d[j - 1][1] + a * d[j][1], (1 - a) * d[j - 1][2] + a * d[j][2]];
      }
    }
    pts.push([d[deg][0] / d[deg][2], d[deg][1] / d[deg][2]]);
  }
  return { pts, closed: !!(flags & 1) };
}

// One entity record -> primitives in its block's coordinates
function entityPrims(r) {
  const layer = String(tagv(r, 8, '0'));
  const pt = (cx, cy) => [tagv(r, cx, 0), tagv(r, cy, 0)];
  const m = ocs(r);
  switch (r.type) {
    case 'LINE': return [{ t: 'line', layer, a: pt(10, 20), b: pt(11, 21) }];
    case 'CIRCLE': return [transformPrim({ t: 'circle', layer, c: pt(10, 20), r: tagv(r, 40, 0) }, m)];
    case 'ARC': {
      const d = Math.PI / 180;
      return [transformPrim({ t: 'arc', layer, c: pt(10, 20), r: tagv(r, 40, 0), a0: tagv(r, 50, 0) * d, a1: tagv(r, 51, 360) * d, ccw: true }, m)];
    }
    case 'LWPOLYLINE': {
      const pts = [];
      let x = null;
      for (const [c, v] of r.tags) {
        if (c === 10) x = v;
        else if (c === 20 && x !== null) { pts.push([x, v, 0]); x = null; }
        else if (c === 42 && pts.length) pts[pts.length - 1][2] = v;
      }
      return pts.length > 1 ? [transformPrim({ t: 'poly', layer, pts, closed: !!(tagv(r, 70, 0) & 1) }, m)] : [];
    }
    case 'POLYLINE': {
      const flags = tagv(r, 70, 0);
      if (flags & (16 | 64)) return [];                       // meshes / polyface
      const pts = (r.vertices || []).filter((v) => !(tagv(v, 70, 0) & 16))   // skip spline frame points
        .map((v) => [tagv(v, 10, 0), tagv(v, 20, 0), tagv(v, 42, 0)]);
      return pts.length > 1 ? [transformPrim({ t: 'poly', layer, pts, closed: !!(flags & 1) }, flags & 8 ? I : m)] : [];
    }
    case 'ELLIPSE': {
      const c = pt(10, 20), mj = pt(11, 21), ratio = tagv(r, 40, 1);
      let p0 = tagv(r, 41, 0), p1 = tagv(r, 42, 2 * Math.PI);
      if (p1 <= p0) p1 += 2 * Math.PI;
      const mn = [-mj[1] * ratio, mj[0] * ratio];
      const n = Math.max(24, Math.ceil((p1 - p0) / (Math.PI / 90)));
      const pts = [];
      for (let i = 0; i <= n; i++) { const t = p0 + (p1 - p0) * i / n; pts.push([c[0] + mj[0] * Math.cos(t) + mn[0] * Math.sin(t), c[1] + mj[1] * Math.cos(t) + mn[1] * Math.sin(t), 0]); }
      const full = Math.abs(p1 - p0 - 2 * Math.PI) < 1e-6;
      if (full) pts.pop();
      return [transformPrim({ t: 'poly', layer, pts, closed: full }, tagv(r, 230, 1) < 0 ? MIRROR_X : I)];
    }
    case 'SPLINE': {
      const s = splinePoints(r);
      return s.pts.length > 1 ? [{ t: 'poly', layer, pts: s.pts.map((p) => [p[0], p[1], 0]), closed: s.closed }] : [];
    }
    case 'TEXT': case 'MTEXT': {
      let text = String(tagv(r, 1, ''));
      for (const [c, v] of r.tags) if (c === 3) text = v + text;     // MTEXT continuation chunks come first
      text = text.replace(/\\P/g, ' ').replace(/\\[A-Za-z][^;]*;/g, '').replace(/[{}]/g, '').trim();
      const rot = r.type === 'TEXT' ? tagv(r, 50, 0) : tagv(r, 50, 0);
      return text ? [{ t: 'text', layer, p: ap(m, pt(10, 20)), h: tagv(r, 40, 2.5), rot: rot * Math.PI / 180, text }] : [];
    }
    default: return [];
  }
}

// ---------------------------------------------------------------- document
function readDocument(buf) {
  const isBinary = buf.length > 22 && buf.subarray(0, 22).toString('latin1') === BINARY_SENTINEL;
  const { tags, version } = isBinary ? binaryTags(buf) : asciiTags(buf);
  const recs = records(tags);
  const header = {}, blocks = new Map(), entities = [];
  let section = '', block = null, poly = null;
  for (let i = 0; i < recs.length; i++) {
    const r = recs[i];
    if (r.type === 'SECTION') {
      section = String(tagv(r, 2, ''));
      // header variables have no group-code-0 tags, so they all sit in the SECTION record
      if (section === 'HEADER') for (let k = 0; k < r.tags.length; k++) if (r.tags[k][0] === 9) header[r.tags[k][1]] = r.tags[k + 1]?.[1];
      continue;
    }
    if (r.type === 'ENDSEC') { section = ''; continue; }
    if (section !== 'BLOCKS' && section !== 'ENTITIES') continue;
    if (r.type === 'BLOCK') { block = { name: String(tagv(r, 2, '')), base: [tagv(r, 10, 0), tagv(r, 20, 0)], ents: [] }; blocks.set(block.name, block); continue; }
    if (r.type === 'ENDBLK') { block = null; continue; }
    if (r.type === 'VERTEX') { if (poly) poly.vertices.push(r); continue; }
    if (r.type === 'SEQEND') { poly = null; continue; }
    if (r.type === 'POLYLINE') { poly = r; r.vertices = []; }
    else if (r.type === 'ATTRIB') continue;
    if (tagv(r, 67, 0) === 1) continue;                      // paper space
    (section === 'BLOCKS' ? (block ? block.ents : []) : entities).push(r);
  }
  return { isBinary, version: version || String(header.$ACADVER || ''), header, blocks, entities };
}

function explode(doc, unitScale) {
  const prims = [];
  const counts = {};
  const walk = (ents, m, depth) => {
    for (const r of ents) {
      counts[r.type] = (counts[r.type] || 0) + (depth === 0 ? 1 : 0);
      if (r.type === 'INSERT') {
        const b = doc.blocks.get(String(tagv(r, 2, '')));
        if (!b || depth > 8) continue;
        const sx = tagv(r, 41, 1), sy = tagv(r, 42, 1), rot = tagv(r, 50, 0) * Math.PI / 180;
        const cols = Math.max(1, tagv(r, 70, 1)), rows = Math.max(1, tagv(r, 71, 1));
        const dc = tagv(r, 44, 0), dr = tagv(r, 45, 0);
        const ins = [tagv(r, 10, 0), tagv(r, 20, 0)];
        const cs = Math.cos(rot), sn = Math.sin(rot);
        for (let ci = 0; ci < cols; ci++) for (let ri = 0; ri < rows; ri++) {
          const off = [ci * dc, ri * dr];      // array spacing runs along the rotated axes
          let t = [1, 0, 0, 1, ins[0] + off[0] * cs - off[1] * sn, ins[1] + off[0] * sn + off[1] * cs];
          t = mul(t, [cs, sn, -sn, cs, 0, 0]);
          t = mul(t, [sx, 0, 0, sy, 0, 0]);
          t = mul(t, [1, 0, 0, 1, -b.base[0], -b.base[1]]);
          walk(b.ents, mul(m, mul(ocs(r), t)), depth + 1);
        }
        continue;
      }
      for (const p of entityPrims(r)) {
        if (p.t === 'text') {
          const q = ap(m, p.p), s = scaleOf(m);
          const ang = Math.atan2(m[1], m[0]);
          prims.push({ ...p, p: q, h: p.h * s, rot: p.rot + ang });
        } else prims.push(transformPrim(p, m));
      }
    }
  };
  walk(doc.entities, [unitScale, 0, 0, unitScale, 0, 0], 0);
  return { prims, counts };
}

// ---------------------------------------------------------------- loops
// Segments: {k:'L', a, b} or {k:'A', a, b, c, r, ccw}
function segsOf(p) {
  if (p.t === 'line') return [{ k: 'L', a: p.a, b: p.b }];
  if (p.t === 'arc') {
    const a = [p.c[0] + p.r * Math.cos(p.a0), p.c[1] + p.r * Math.sin(p.a0)];
    const b = [p.c[0] + p.r * Math.cos(p.a1), p.c[1] + p.r * Math.sin(p.a1)];
    return [{ k: 'A', a, b, c: p.c, r: p.r, ccw: p.ccw }];
  }
  const out = [], n = p.pts.length;
  for (let i = 0; i < (p.closed ? n : n - 1); i++) {
    const a = p.pts[i], b = p.pts[(i + 1) % n];
    if (Math.hypot(b[0] - a[0], b[1] - a[1]) < 1e-9) continue;
    if (a[2]) { const g = bulgeArc(a, b, a[2]); out.push({ k: 'A', a: [a[0], a[1]], b: [b[0], b[1]], c: g.c, r: g.r, ccw: g.ccw }); }
    else out.push({ k: 'L', a: [a[0], a[1]], b: [b[0], b[1]] });
  }
  return out;
}
const rev = (s) => (s.k === 'L' ? { k: 'L', a: s.b, b: s.a } : { ...s, a: s.b, b: s.a, ccw: !s.ccw });
const near = (p, q, tol = CHAIN_TOL) => Math.abs(p[0] - q[0]) <= tol && Math.abs(p[1] - q[1]) <= tol;

function segPoints(s) {
  if (s.k === 'L') return [s.a, s.b];
  const a0 = Math.atan2(s.a[1] - s.c[1], s.a[0] - s.c[0]), a1 = Math.atan2(s.b[1] - s.c[1], s.b[0] - s.c[0]);
  const pts = arcPoints(s.c, s.r, a0, arcSweep(a0, a1, s.ccw));
  pts[0] = s.a; pts[pts.length - 1] = s.b;
  return pts;
}

function chain(segs) {
  const key = (p) => `${Math.round(p[0] / CHAIN_TOL)},${Math.round(p[1] / CHAIN_TOL)}`;
  const index = new Map();
  const add = (p, i) => {
    const [kx, ky] = [Math.round(p[0] / CHAIN_TOL), Math.round(p[1] / CHAIN_TOL)];
    for (let dx = -1; dx <= 1; dx++) for (let dy = -1; dy <= 1; dy++) {
      const k = `${kx + dx},${ky + dy}`;
      if (!index.has(k)) index.set(k, []);
      index.get(k).push(i);
    }
  };
  segs.forEach((s, i) => { add(s.a, i); add(s.b, i); });
  const used = new Array(segs.length).fill(false);
  const take = (p) => {
    for (const i of index.get(key(p)) || []) {
      if (used[i]) continue;
      if (near(segs[i].a, p)) { used[i] = true; return segs[i]; }
      if (near(segs[i].b, p)) { used[i] = true; return rev(segs[i]); }
    }
    return null;
  };
  const loops = [];
  for (let i = 0; i < segs.length; i++) {
    if (used[i]) continue;
    used[i] = true;
    const path = [segs[i]];
    let s;
    while (!near(path[path.length - 1].b, path[0].a) && (s = take(path[path.length - 1].b))) path.push(s);
    if (!near(path[path.length - 1].b, path[0].a)) {
      while ((s = take(path[0].a))) path.unshift(rev(s));
    }
    const closed = near(path[path.length - 1].b, path[0].a) && (path.length > 1 || path[0].k === 'A');
    loops.push({ segs: path, closed });
  }
  return loops;
}

function polyArea(pts) {
  let a = 0;
  for (let i = 0, n = pts.length; i < n; i++) { const p = pts[i], q = pts[(i + 1) % n]; a += p[0] * q[1] - q[0] * p[1]; }
  return a / 2;
}
function inside(pt, pts) {
  let c = false;
  for (let i = 0, j = pts.length - 1; i < pts.length; j = i++) {
    const a = pts[i], b = pts[j];
    if ((a[1] > pt[1]) !== (b[1] > pt[1]) && pt[0] < (b[0] - a[0]) * (pt[1] - a[1]) / (b[1] - a[1]) + a[0]) c = !c;
  }
  return c;
}
const r3 = (v) => Math.round(v * 1000) / 1000;

function buildLoops(prims) {
  const loops = [];
  const segs = [];
  for (const p of prims) {
    if (p.t === 'text') continue;
    if (p.t === 'circle') {
      if (p.r <= 0) continue;
      const a = [p.c[0] + p.r, p.c[1]];
      loops.push({ segs: [{ k: 'A', a, b: a, c: p.c, r: p.r, ccw: true }], closed: true, circle: { c: p.c, r: p.r }, layer: p.layer });
    } else if (p.t === 'poly' && p.closed) {
      const s = segsOf(p);
      if (s.length) loops.push({ segs: s, closed: true, layer: p.layer });
    } else segs.push(...segsOf(p).map((s) => ({ ...s, layer: p.layer })));
  }
  for (const l of chain(segs)) loops.push({ ...l, layer: l.segs[0].layer });

  const out = loops.map((l, i) => {
    let pts = [];
    for (const s of l.segs) { const sp = segPoints(s); pts.push(...(pts.length ? sp.slice(1) : sp)); }
    if (l.closed && pts.length > 1 && near(pts[0], pts[pts.length - 1])) pts.pop();
    // two or more arcs with one centre and radius that close up are a circle too
    let circle = l.circle || null;
    if (!circle && l.closed && l.segs.every((s) => s.k === 'A' && near(s.c, l.segs[0].c, 0.005) && Math.abs(s.r - l.segs[0].r) < 0.005)) {
      circle = { c: l.segs[0].c, r: l.segs[0].r };
    }
    let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity, per = 0;
    pts.forEach((p, k) => {
      minX = Math.min(minX, p[0]); maxX = Math.max(maxX, p[0]); minY = Math.min(minY, p[1]); maxY = Math.max(maxY, p[1]);
      if (k) per += Math.hypot(p[0] - pts[k - 1][0], p[1] - pts[k - 1][1]);
    });
    if (l.closed && pts.length > 1) per += Math.hypot(pts[0][0] - pts[pts.length - 1][0], pts[0][1] - pts[pts.length - 1][1]);
    return {
      id: i + 1, closed: l.closed, layer: l.layer, segs: l.segs, pts,
      area: l.closed ? Math.abs(polyArea(pts)) : 0, perimeter: per, bbox: [minX, minY, maxX, maxY],
      circle: circle && { c: circle.c, r: circle.r },
      lines: l.segs.filter((s) => s.k === 'L').length, arcs: l.segs.filter((s) => s.k === 'A').length,
    };
  });

  // nesting: parent = the smallest closed loop that contains this loop
  const closed = out.filter((l) => l.closed && l.pts.length > 2).sort((a, b) => a.area - b.area);
  for (const l of out) {
    const probe = l.pts[0];
    const parent = closed.find((o) => o !== l && o.area > l.area && o.bbox[0] <= l.bbox[0] + 1e-6 && o.bbox[1] <= l.bbox[1] + 1e-6
      && o.bbox[2] >= l.bbox[2] - 1e-6 && o.bbox[3] >= l.bbox[3] - 1e-6 && inside(probe, o.pts));
    l.parent = parent ? parent.id : null;
  }
  const byId = new Map(out.map((l) => [l.id, l]));
  for (const l of out) {
    let d = 0, p = l, top = l;
    while (p.parent) { p = byId.get(p.parent); top = p; d++; }
    l.depth = l.closed ? d : null;
    l.part = top.closed ? top.id : null;
  }
  return out;
}

// ---------------------------------------------------------------- public
function parseDxf(buf) {
  const doc = readDocument(buf);
  const units = Number(doc.header.$INSUNITS ?? 0);
  const unitScale = UNIT_MM[units] ?? 1;
  const { prims, counts } = explode(doc, unitScale);
  const loops = buildLoops(prims);
  const texts = prims.filter((p) => p.t === 'text').map((t) => ({ x: r3(t.p[0]), y: r3(t.p[1]), h: r3(t.h), rot: r3(t.rot), text: t.text }));
  let bbox = [Infinity, Infinity, -Infinity, -Infinity];
  for (const l of loops) bbox = [Math.min(bbox[0], l.bbox[0]), Math.min(bbox[1], l.bbox[1]), Math.max(bbox[2], l.bbox[2]), Math.max(bbox[3], l.bbox[3])];
  if (!loops.length) bbox = [0, 0, 0, 0];
  return {
    format: doc.isBinary ? 'binary' : 'ascii',
    version: doc.version,
    units: UNIT_NAME[units] || `code ${units}`,
    unitScale,
    bbox: bbox.map(r3),
    entityCounts: counts,
    blocks: [...doc.blocks.keys()].filter((n) => !n.startsWith('*')).length,
    loops,
    texts,
  };
}

// Compact form for the browser: rounded points, no segment detail
function viewerGeometry(g) {
  return {
    format: g.format, version: g.version, units: g.units, bbox: g.bbox, entityCounts: g.entityCounts, texts: g.texts,
    loops: g.loops.map((l) => ({
      id: l.id, closed: l.closed, depth: l.depth, part: l.part, layer: l.layer,
      circle: l.circle ? { c: l.circle.c.map(r3), r: r3(l.circle.r) } : null,
      pts: l.pts.map((p) => [Math.round(p[0] * 100) / 100, Math.round(p[1] * 100) / 100]),
    })),
  };
}

module.exports = { parseDxf, viewerGeometry, polyArea, inside, arcPoints, arcSweep };
