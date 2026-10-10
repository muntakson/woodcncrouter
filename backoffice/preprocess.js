// CAM preprocessor: scans a parsed DXF and decides whether the cut plan can be made by fixed rules
// (no AI call) or needs Claude's judgment. When rules suffice it also returns that plan, in the same
// shape Claude returns, so cam.js turns either into G-code the same way.
//
// Rules handle the common case: closed part outlines (optionally on a sheet border), round holes,
// and annotation on clearly named layers, in a material from the table below. Anything that needs
// interpretation (open lines, islands, layer names that encode operations, notes in the drawing,
// odd holes, unknown materials, loops outside the sheet) is sent to Claude, with the reasons.
'use strict';

const ClipperLib = require('clipper-lib');

const MATERIALS = [
  // name, pattern, feed mm/min, plunge mm/min, step-down as a fraction of the tool diameter, rpm
  { name: 'plywood', re: /ply|osb|baltic/i, feed: 900, plunge: 300, step: 0.75, rpm: 18000 },
  { name: 'MDF', re: /\b(mdf|hdf)\b|particle ?board|chip ?board/i, feed: 1000, plunge: 300, step: 0.75, rpm: 18000 },
  { name: 'softwood', re: /pine|spruce|fir\b|cedar|poplar|soft ?wood|balsa|paulownia/i, feed: 1000, plunge: 300, step: 0.75, rpm: 18000 },
  { name: 'hardwood', re: /oak|maple|walnut|beech|\bash\b|cherry|hard ?wood|birch|teak|mahogany/i, feed: 700, plunge: 200, step: 0.5, rpm: 18000 },
  { name: 'acrylic', re: /acrylic|pmma|plexi|perspex/i, feed: 600, plunge: 150, step: 0.3, rpm: 16000 },
  { name: 'plastic', re: /hdpe|polyethylene|uhmw|delrin|\bpom\b|acetal|polycarbonate|\bpvc\b|foamex/i, feed: 800, plunge: 200, step: 0.5, rpm: 16000 },
  { name: 'foam', re: /foam|\beps\b|\bxps\b|cork/i, feed: 1000, plunge: 400, step: 2, rpm: 12000 },
];
const SHEET_LAYER = /sheet|stock|material|board|blank|bed|table/i;
const ANNOT_LAYER = /stat|note|text|title|dim|anno|label|info|legend|frame|border|defpoint|construct|guide|ref/i;
const ENGRAVE_LAYER = /engrav|mark|score|scribe|etch|v.?carve|text.?cut/i;
const OP_LAYER = /pocket|depth|\d+(\.\d+)?\s*mm|\bdeep\b|drill|inside|outside|profile|on.?line|v.?carve|chamfer|rebate|rabbet|dado|groove|half/i;
const OP_TEXT = /pocket|depth|deep|drill|do not cut|don'?t cut|chamfer|countersink|counterbore|rebate|rabbet|dado|groove|v.?carve|tab|grain|mm\s+(deep|depth)/i;
const BED = [2438.4, 1219.2];

const isRect = (l) => l.closed && !l.circle && l.area > 0 && Math.abs(l.area - (l.bbox[2] - l.bbox[0]) * (l.bbox[3] - l.bbox[1])) < 0.005 * l.area;
const f2 = (v) => (Math.round(v * 100) / 100).toString();
const ids = (ls, n = 12) => ls.slice(0, n).map((l) => l.id).join(', ') + (ls.length > n ? ` … (${ls.length})` : '');

function analyze(g, params) {
  const D = Number(params.toolDiameter), T = Number(params.thickness);
  const findings = [];
  const ai = (text) => findings.push({ level: 'ai', text });
  const ok = (text) => findings.push({ level: 'ok', text });
  const warn = (text) => findings.push({ level: 'warn', text });
  const byId = new Map(g.loops.map((l) => [l.id, l]));
  const children = new Map(g.loops.map((l) => [l.id, []]));
  for (const l of g.loops) if (l.parent) children.get(l.parent).push(l);
  const ops = new Map();            // id -> op
  const set = (l, op) => ops.set(l.id, op);
  const parts = [], drills = [], insides = [], islands = [], tiny = [], narrow = [], engr = [], open = [];

  if (!g.loops.length) { ai('The drawing has no lines, arcs or circles to cut.'); return result(); }

  // 1. annotation layers (stats boxes, title blocks, notes)
  const annot = g.loops.filter((l) => ANNOT_LAYER.test(l.layer) && !SHEET_LAYER.test(l.layer) && !ENGRAVE_LAYER.test(l.layer));
  for (const l of annot) set(l, 'skip');
  if (annot.length) ok(`${annot.length} loop(s) on annotation layer${new Set(annot.map((l) => l.layer)).size > 1 ? 's' : ''} ${[...new Set(annot.map((l) => `"${l.layer}"`))].join(', ')} are not cut.`);

  // 2. sheet border: a top-level rectangle around the parts, on a sheet-like layer or holding several part outlines
  // A rectangle around several shapes may also be a rectangular part with cut-outs, so the rules only
  // take it as the stock when its layer or the drawing's text (nesting reports) says so.
  const nestText = g.texts.some((t) => /sheet|nest|stock|util/i.test(t.text));
  const boxes = g.loops.filter((l) => l.depth === 0 && !ops.has(l.id) && isRect(l)
    && (SHEET_LAYER.test(l.layer) || children.get(l.id).filter((c) => c.closed && !c.circle).length >= 2));
  const sheets = boxes.filter((l) => SHEET_LAYER.test(l.layer) || nestText);
  for (const l of boxes) if (!sheets.includes(l)) ai(`Loop ${l.id} is a rectangle around other shapes: the sheet border, or a part with cut-outs?`);
  if (sheets.length > 1) ai(`${sheets.length} rectangles look like sheet borders (loops ${ids(sheets)}); which one is the stock is unclear.`);
  const sheet = sheets.length === 1 ? sheets[0] : null;
  if (sheet) {
    set(sheet, 'skip');
    ok(`Loop ${sheet.id} (${f2(sheet.bbox[2] - sheet.bbox[0])} × ${f2(sheet.bbox[3] - sheet.bbox[1])} mm${sheet.layer !== '0' ? `, layer "${sheet.layer}"` : ''}) is the sheet border and is not cut.`);
    const outside = g.loops.filter((l) => l.depth === 0 && !ops.has(l.id));
    if (outside.length) ai(`${outside.length} loop(s) lie outside the sheet border (${ids(outside)}): parts, notes or a second sheet?`);
  }

  // 3. nesting level counted over loops that are cut (the skipped sheet border doesn't count)
  const level = (l) => { let n = 0; for (let p = l.parent && byId.get(l.parent); p; p = p.parent && byId.get(p.parent)) if (ops.get(p.id) !== 'skip') n++; return n; };
  const odd = new Map();
  for (const l of g.loops) {
    if (ops.has(l.id)) continue;
    if (!l.closed) { (ENGRAVE_LAYER.test(l.layer) ? engr : open).push(l); continue; }
    if (ENGRAVE_LAYER.test(l.layer)) { engr.push(l); continue; }
    const lv = level(l);
    if (lv === 0) parts.push(l);
    else if (lv === 1) {
      if (l.circle) {
        const d = 2 * l.circle.r;
        if (d < 0.5 * D) tiny.push(l);
        else if (d <= D + 0.3) { drills.push(l); if (Math.abs(d - D) > 0.3) odd.set(f2(d), (odd.get(f2(d)) || 0) + 1); }
        else insides.push(l);
      } else if (Math.min(l.bbox[2] - l.bbox[0], l.bbox[3] - l.bbox[1]) > D * 1.05) insides.push(l);
      else narrow.push(l);
    } else islands.push(l);
  }
  for (const l of parts) set(l, 'outside');
  for (const l of drills) set(l, 'drill');
  for (const l of insides) set(l, 'inside');
  for (const l of engr) set(l, 'engrave');
  if (parts.length) ok(`${parts.length} part outline${parts.length > 1 ? 's' : ''} → cut outside.`); else ai('No closed part outline was found to cut out.');
  if (drills.length) ok(`${drills.length} round hole${drills.length > 1 ? 's' : ''} up to the tool size → peck-drilled.`);
  for (const [d, n] of odd) warn(`${n} hole${n > 1 ? 's' : ''} drawn Ø${d} will come out Ø${D} (drilled with the end mill).`);
  if (insides.length) ok(`${insides.length} hole${insides.length > 1 ? 's' : ''} / cut-out${insides.length > 1 ? 's' : ''} bigger than the tool → cut inside.`);
  if (engr.length) ok(`${engr.length} line(s) on engrave layers → engraved.`);
  if (tiny.length) ai(`${tiny.length} hole(s) are much smaller than the Ø${D} tool (${ids(tiny)}): skip them, drill oversize, or change tool?`);
  if (narrow.length) ai(`${narrow.length} cut-out(s) are narrower than the tool (${ids(narrow)}).`);
  if (islands.length) ai(`${islands.length} loop(s) sit inside holes (${ids(islands)}): islands, pockets or separate parts?`);
  if (open.length) ai(`${open.length} open line(s) (${ids(open)}) on layer${new Set(open.map((l) => l.layer)).size > 1 ? 's' : ''} ${[...new Set(open.map((l) => `"${l.layer}"`))].join(', ')}: engrave, cut through, or construction lines?`);

  // parts that overlap can't both be right; parts closer than the tool get nicked by the cut between them
  const { overlaps, close } = partSpacing(parts, D);
  if (overlaps.length) ai(`Part outlines overlap (${overlaps.slice(0, 6).map(([a, b]) => `${a.id} & ${b.id}`).join(', ')}${overlaps.length > 6 ? ' …' : ''}): which outline is right?`);
  if (close.length) {
    const worst = close.reduce((m, c) => (c[2] < m[2] ? c : m));
    warn(`${close.length} pair(s) of parts are closer together than the Ø${D} tool (closest ${f2(worst[2])} mm, loops ${worst[0].id} & ${worst[1].id}): the cut between them nicks both by up to ${f2(D - worst[2])} mm.`);
  }

  // 4. drawing content that carries machining intent
  const opLayers = [...new Set(g.loops.map((l) => l.layer))].filter((n) => OP_LAYER.test(n) && !ENGRAVE_LAYER.test(n));
  if (opLayers.length) ai(`Layer names describe operations or depths (${opLayers.map((n) => `"${n}"`).join(', ')}), which the rules don't read.`);
  const notes = g.texts.filter((t) => OP_TEXT.test(t.text));
  if (notes.length) ai(`Text in the drawing may hold machining instructions: ${notes.slice(0, 3).map((t) => `"${t.text}"`).join(', ')}.`);
  const seen = new Map(), dups = [];
  for (const l of g.loops) {
    const k = `${l.bbox.map((v) => Math.round(v * 100)).join(',')}|${Math.round(l.area)}|${l.closed}`;
    if (seen.has(k)) dups.push(l); else seen.set(k, l);
  }
  if (dups.length) ai(`${dups.length} loop(s) are drawn twice on top of others (${ids(dups)}).`);

  // 5. machine, material and tool
  const cut = g.loops.filter((l) => ops.has(l.id) && ops.get(l.id) !== 'skip');
  if (cut.length) {
    const x0 = Math.min(...cut.map((l) => l.bbox[0])), y0 = Math.min(...cut.map((l) => l.bbox[1]));
    const w = Math.max(...cut.map((l) => l.bbox[2])) - x0, h = Math.max(...cut.map((l) => l.bbox[3])) - y0;
    if (w > BED[0] + 1 || h > BED[1] + 1) warn(`The cut area (${f2(w)} × ${f2(h)} mm) is bigger than the ${BED[0]} × ${BED[1]} mm bed.`);
  }
  const mat = MATERIALS.find((m) => m.re.test(params.material || ''));
  if (!mat) ai(`"${params.material}" is not in the rules' material table (plywood, MDF, softwood, hardwood, acrylic, plastics, foam).`);
  if (!(D > 0)) ai('No tool diameter given.');
  return result();

  function result() {
    const needsAi = findings.some((f) => f.level === 'ai');
    const out = {
      verdict: needsAi ? 'ai' : 'rules',
      findings,
      counts: { parts: parts.length, drills: drills.length, inside: insides.length, engrave: engr.length, skip: [...ops.values()].filter((o) => o === 'skip').length },
      plan: null,
    };
    if (!needsAi) out.plan = rulePlan(g, ops, mat, D, T, out.counts, params.bridges);
    return out;
  }
}

const SCALE = 1000;
const clip = (pts) => pts.map((p) => ({ X: Math.round(p[0] * SCALE), Y: Math.round(p[1] * SCALE) }));
function segDist(p, a, b) {
  const dx = b[0] - a[0], dy = b[1] - a[1], L = dx * dx + dy * dy;
  const t = L ? Math.max(0, Math.min(1, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / L)) : 0;
  return Math.hypot(p[0] - a[0] - t * dx, p[1] - a[1] - t * dy);
}
// smallest distance between two outlines, only looking at points within `lim` of the other's box
function outlineDist(A, B, lim) {
  let d = Infinity;
  for (const [P, Q] of [[A, B], [B, A]]) {
    const bx = Q.bbox;
    for (const p of P.pts) {
      if (p[0] < bx[0] - lim || p[0] > bx[2] + lim || p[1] < bx[1] - lim || p[1] > bx[3] + lim) continue;
      for (let i = 0; i < Q.pts.length; i++) d = Math.min(d, segDist(p, Q.pts[i], Q.pts[(i + 1) % Q.pts.length]));
    }
  }
  return d;
}
function partSpacing(parts, D) {
  const overlaps = [], close = [];
  for (let i = 0; i < parts.length; i++) for (let j = i + 1; j < parts.length; j++) {
    const a = parts[i], b = parts[j];
    if (a.bbox[0] > b.bbox[2] + D || b.bbox[0] > a.bbox[2] + D || a.bbox[1] > b.bbox[3] + D || b.bbox[1] > a.bbox[3] + D) continue;
    const c = new ClipperLib.Clipper(), sol = new ClipperLib.Paths();
    c.AddPath(clip(a.pts), ClipperLib.PolyType.ptSubject, true);
    c.AddPath(clip(b.pts), ClipperLib.PolyType.ptClip, true);
    c.Execute(ClipperLib.ClipType.ctIntersection, sol, ClipperLib.PolyFillType.pftNonZero, ClipperLib.PolyFillType.pftNonZero);
    const area = sol.reduce((s, p) => s + Math.abs(ClipperLib.Clipper.Area(p)), 0) / SCALE / SCALE;
    if (area > 0.01) { overlaps.push([a, b]); continue; }
    const d = outlineDist(a, b, D);
    if (d < D - 0.01) close.push([a, b, d]);
  }
  return { overlaps, close };
}

function rulePlan(g, ops, mat, D, T, c, bridges) {
  const small = D < 4 ? 0.6 : 1;          // small end mills break easily: slower feed
  const step = Math.min(mat.step * D * (D < 4 ? 0.7 : 1), T + 0.5);
  const b = bridges?.enabled ? bridges : null;
  const tabs = b ? { per_part: b.count, width_mm: b.width, height_mm: b.height, on_cutouts: !!b.cutouts } : { per_part: 0, width_mm: 6, height_mm: 1, on_cutouts: false };
  const bits = [`${c.parts} part${c.parts === 1 ? '' : 's'} cut outside`];
  if (c.drills) bits.push(`${c.drills} hole${c.drills === 1 ? '' : 's'} drilled`);
  if (c.inside) bits.push(`${c.inside} inside cut${c.inside === 1 ? '' : 's'}`);
  if (c.engrave) bits.push(`${c.engrave} line${c.engrave === 1 ? '' : 's'} engraved`);
  if (c.skip) bits.push(`${c.skip} loop${c.skip === 1 ? '' : 's'} skipped`);
  const groups = new Map();
  for (const [id, op] of ops) { if (!groups.has(op)) groups.set(op, []); groups.get(op).push(id); }
  return {
    summary: `Planned by rules (no AI): ${bits.join(', ')}; ${mat.name} defaults.`,
    spindle_rpm: mat.rpm,
    feed_mm_min: Math.round(mat.feed * small),
    plunge_mm_min: Math.round(mat.plunge * small),
    stepdown_mm: Math.round(step * 100) / 100,
    cut_depth_mm: Math.round((T + 0.5) * 100) / 100,
    engrave_depth_mm: Math.min(1, T / 4),
    safe_z_mm: 5,
    milling: 'conventional',
    tabs,
    peck_mm: Math.max(D, 3),
    features: [...groups].map(([op, loops]) => ({ loops, op, reason: 'rules' })),
    warnings: tabs.per_part ? [`${tabs.per_part} bridges per ${tabs.on_cutouts ? 'part and cut-out' : 'part'} hold the pieces in place; cut them free by hand afterwards.`]
      : c.parts ? ['No bridges: clamp or screw the parts down, or they can move when cut free.'] : [],
  };
}

module.exports = { analyze, MATERIALS };
