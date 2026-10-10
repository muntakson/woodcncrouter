// Checks the synthetic DXF set from cam/make_test_dxfs.py:
//  1. the preprocessor's verdict matches the file name (test_R* = rules, test_A* = AI needed)
//  2. for rule-planned files, the G-code really cuts what the plan says. The G-code is parsed again
//     here (not with cam.js code): every "outside" loop has a closed cut lap around it at the tool
//     radius, every "inside" loop one inside it, every drill plunges at the hole centre to full depth.
//
// Bridges (tabs) are checked from the Z moves alone: a rise during a cut, a level move, a drop.
//
//   node check_cam_samples.js [toolDiameter=6] [thickness=18] [material=plywood] [bridges: 4x8x3+c | 4x8x3 | off]
'use strict';

const fs = require('node:fs');
const path = require('node:path');
const { parseDxf, inside } = require('./dxf');
const { analyze } = require('./preprocess');
const cam = require('./cam');

const params = { toolDiameter: Number(process.argv[2] || 6), thickness: Number(process.argv[3] || 18), material: process.argv[4] || 'plywood' };
const bm = /^(\d+)x([\d.]+)x([\d.]+)(\+c)?$/.exec(process.argv[5] || '4x8x3+c');
params.bridges = bm ? { enabled: true, count: Number(bm[1]), width: Number(bm[2]), height: Number(bm[3]), cutouts: !!bm[4] } : { enabled: false };
const R = params.toolDiameter / 2, TOL = 0.05;
const DIR = path.join(__dirname, 'dxf');

// ---- independent G-code reader: closed laps of feed moves at one Z, and drill plunges
function readGcode(text) {
  let x = 0, y = 0, z = 0, mode = 0, lap = [];
  const laps = [], drills = [], feeds = [], bridges = [];
  let bridge = null;   // feeds: every XY feed move end point, with its Z
  let plunge = null;
  const endLap = () => { if (lap.length > 2) laps.push(lap); lap = []; };
  for (const raw of text.split('\n')) {
    const line = raw.replace(/\(.*?\)/g, '').trim().toUpperCase();
    if (!line || line === '%') continue;
    const w = {};
    for (const m of line.matchAll(/([A-Z])([-+]?\d*\.?\d+)/g)) w[m[1]] = Number(m[2]);
    if ('G' in w && w.G <= 3) mode = w.G;
    const nx = w.X ?? x, ny = w.Y ?? y, nz = w.Z ?? z;
    if (nz !== z) {
      if (mode === 1 && nx === x && ny === y && z < 0 && nz > z && nz < 0) bridge = { z: nz, len: 0, pts: [] };
      else if (mode === 1 && bridge && nz < z) { bridges.push(bridge); bridge = null; }
      else bridge = null;
      if (bridge) { z = nz; continue; }      // a bridge lift does not end the lap
      endLap();
      if (mode === 1 && nx === x && ny === y && nz < z) plunge = { x, y, z: nz };
      else if (mode === 0 && nz > z && plunge && nx === x && ny === y) { drills.push(plunge); plunge = null; }
      z = nz;
    }
    if (nx !== x || ny !== y || ((mode === 2 || mode === 3) && ('I' in w || 'J' in w))) {
      plunge = null;
      if (mode === 0) endLap();
      else {
        if (!lap.length) lap.push([x, y, z]);
        feeds.push([nx, ny, z]);
        if (bridge && mode === 1) { bridge.len += Math.hypot(nx - x, ny - y); bridge.pts.push([nx, ny]); }
        if (mode === 1) lap.push([nx, ny, z]);
        else {
          const cx = x + (w.I || 0), cy = y + (w.J || 0), r = Math.hypot(x - cx, y - cy);
          const a0 = Math.atan2(y - cy, x - cx); let a1 = Math.atan2(ny - cy, nx - cx), sw = a1 - a0;
          if (mode === 2) { if (sw >= 0) sw -= 2 * Math.PI; } else if (sw <= 0) sw += 2 * Math.PI;
          const n = Math.max(4, Math.ceil(Math.abs(sw) * r / 0.2));
          for (let i = 1; i <= n; i++) lap.push([cx + r * Math.cos(a0 + sw * i / n), cy + r * Math.sin(a0 + sw * i / n), z]);
        }
      }
      x = nx; y = ny;
    }
  }
  endLap();
  return { laps: laps.filter((l) => Math.hypot(l[0][0] - l[l.length - 1][0], l[0][1] - l[l.length - 1][1]) < 0.01), drills, feeds, bridges };
}
function segDist(p, a, b) {
  const dx = b[0] - a[0], dy = b[1] - a[1], L = dx * dx + dy * dy;
  const t = L ? Math.max(0, Math.min(1, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / L)) : 0;
  return Math.hypot(p[0] - a[0] - t * dx, p[1] - a[1] - t * dy);
}
// smallest distance between the loop's outline and the lap
function gap(loopPts, lap) {
  let d = Infinity;
  for (const p of lap) for (let i = 0; i < loopPts.length; i++) d = Math.min(d, segDist(p, loopPts[i], loopPts[(i + 1) % loopPts.length]));
  return d;
}

let failures = 0;
const files = fs.readdirSync(DIR).filter((f) => /^test_[RA]\d+.*\.dxf$/i.test(f)).sort();
const pb = params.bridges;
console.log(`tool Ø${params.toolDiameter} · ${params.material} ${params.thickness} mm · bridges ${pb.enabled ? `${pb.count} × ${pb.width}×${pb.height} mm${pb.cutouts ? ' + cut-outs' : ''}` : 'off'} · ${files.length} files\n`);
for (const f of files) {
  const want = /^test_R/i.test(f) ? 'rules' : 'ai';
  const g = parseDxf(fs.readFileSync(path.join(DIR, f)));
  const a = analyze(g, params);
  const okVerdict = a.verdict === want;
  if (!okVerdict) failures++;
  console.log(`${okVerdict ? 'PASS' : 'FAIL'}  ${f}`);
  console.log(`      ${g.units}, ${g.loops.length} loops → ${a.verdict === 'rules' ? 'RULES' : 'AI NEEDED'} (expected ${want === 'rules' ? 'RULES' : 'AI'})`);
  for (const x of a.findings.filter((x) => x.level !== 'ok')) console.log(`      ${x.level === 'ai' ? '✦' : '!'} ${x.text}`);
  if (a.verdict !== 'rules') continue;

  const plan = cam.normalizePlan(a.plan, g, params);
  const problems0 = [];
  const out = cam.generateGcode(g, plan, params, { file: f, created: Date.now(), model: 'rules' });
  const { laps, drills, feeds, bridges } = readGcode(out.gcode);
  const tb = plan.tabs, wantB = params.bridges.enabled;
  for (const b of bridges) {
    if (Math.abs(b.z + (plan.cut_depth_mm - tb.height_mm)) > 1e-3) problems0.push(`bridge at Z${b.z} instead of Z${-(plan.cut_depth_mm - tb.height_mm)}`);
    if (Math.abs(b.len - (tb.width_mm + params.toolDiameter)) > 0.05) problems0.push(`bridge ${b.len.toFixed(2)} mm long instead of ${tb.width_mm + params.toolDiameter}`);
  }
  const depth = plan.cut_depth_mm, problems = problems0;
  const opOf = new Map(plan.features.map((x) => [x.loop, x.op]));
  for (const l of g.loops) {
    const op = opOf.get(l.id);
    if (op === 'outside' || op === 'inside') {
      const hit = laps.find((lap) => (op === 'outside' ? inside(l.pts[0], lap) : inside(lap[0], l.pts)) && Math.abs(gap(l.pts, lap) - R) < TOL);
      if (!hit) problems.push(`loop ${l.id} (${op}): no closed lap at ${R} mm from it`);
      const nb = bridges.filter((b) => b.pts.every((p) => Math.abs(gap(l.pts, [p]) - R) < TOL) && (op === 'outside' ? !inside(b.pts[0], l.pts) : inside(b.pts[0], l.pts))).length;
      const shouldB = wantB && (op === 'outside' || tb.on_cutouts);
      if (!shouldB && nb) problems.push(`loop ${l.id}: ${nb} bridge(s) although none were asked for`);
      if (shouldB && nb > tb.per_part) problems.push(`loop ${l.id}: ${nb} bridges, more than ${tb.per_part}`);
      if (shouldB && !nb && !out.warnings.some((w) => /too (short|small) for/.test(w))) problems.push(`loop ${l.id}: no bridges and no warning why`);
      // full depth: the deepest pass is not one closed lap when it lifts over tabs, so look at all feed moves along the loop
      else if (!feeds.some((p) => p[2] <= -depth + 1e-6 && Math.abs(gap(l.pts, [p]) - R) < TOL)) problems.push(`loop ${l.id}: not cut to full depth`);
    } else if (op === 'engrave') {
      if (!feeds.some((p) => p[2] < 0 && gap(l.closed ? l.pts : l.pts.slice(0, -1).concat([l.pts[l.pts.length - 1]]), [p]) < TOL)) problems.push(`loop ${l.id}: not engraved`);
    } else if (op === 'drill') {
      if (!drills.some((d) => Math.hypot(d.x - l.circle.c[0], d.y - l.circle.c[1]) < 0.01 && d.z <= -depth + 1e-6)) problems.push(`hole ${l.id}: no full-depth drill at its centre`);
    }
  }
  const counts = Object.entries(a.counts).filter(([, v]) => v).map(([k, v]) => `${v} ${k}`).join(', ');
  console.log(`      G-code: ${out.stats.lines} lines, ${laps.length} closed laps, ${drills.length} drill pecks, ${bridges.length} bridges (${counts}) → ${problems.length ? 'PROBLEMS' : 'toolpaths verified'}`);
  for (const w of out.warnings.filter((w) => /bridge/.test(w))) console.log('      ! ' + w);
  for (const p of problems.slice(0, 8)) console.log('      ✖ ' + p);
  if (problems.length) failures++;
}
console.log(`\n${failures ? `${failures} FAILED` : 'all passed'}`);
process.exit(failures ? 1 : 0);
