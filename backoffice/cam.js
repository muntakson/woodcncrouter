// AI CAM: Claude reads the DXF's loops and decides the cut plan (which loop is a part outline, a hole,
// a drill point or the sheet border; feeds, depths, step-downs, tabs). The G-code itself is written
// here, deterministically: Clipper offsets each loop by the tool radius, then depth passes, tabs and
// peck drilling are emitted the same way as cam/dome_gcode.py. Claude never writes coordinates.
'use strict';

const Anthropic = require('@anthropic-ai/sdk');
const ClipperLib = require('clipper-lib');
const { polyArea } = require('./dxf');

const MODEL = process.env.CAM_MODEL || 'claude-opus-5-5';
const EFFORT = process.env.CAM_EFFORT || 'high';
// Machine limits (Grbl $110-$112 = 1000 mm/min on this router); the plan is clamped to them
const MAX_FEED = Number(process.env.CAM_MAX_FEED || 1000);
const MAX_PLUNGE = Number(process.env.CAM_MAX_PLUNGE || 1000);
const MAX_RPM = Number(process.env.CAM_MAX_RPM || 24000);
const RAPID = MAX_FEED;          // mm/min, for the time estimate
const CLEAR = 1;                 // mm above the stock before each plunge
const SCALE = 1000;              // Clipper works in integers: µm

let client = null;
function anthropic() {
  if (!process.env.ANTHROPIC_API_KEY) throw new Error('ANTHROPIC_API_KEY is not set (backoffice/.env or ~/.secrets)');
  return (client ??= new Anthropic());
}

// ---------------------------------------------------------------- what Claude sees
const f1 = (v) => (Math.round(v * 10) / 10).toString();
const f2 = (v) => (Math.round(v * 100) / 100).toString();

function describeGeometry(g, file) {
  const layers = {};
  for (const l of g.loops) layers[l.layer] = (layers[l.layer] || 0) + 1;
  const out = [
    `File: ${file}`,
    `DXF ${g.format} ${g.version}, drawing units: ${g.units} (all numbers below are converted to mm)`,
    `Extents: X ${f2(g.bbox[0])} … ${f2(g.bbox[2])}, Y ${f2(g.bbox[1])} … ${f2(g.bbox[3])}  (${f1(g.bbox[2] - g.bbox[0])} × ${f1(g.bbox[3] - g.bbox[1])} mm)`,
    `Top-level entities: ${Object.entries(g.entityCounts).map(([k, v]) => `${k} ${v}`).join(', ')}`,
    `Layers (loops per layer): ${Object.entries(layers).map(([k, v]) => `"${k}" ${v}`).join(', ')}`,
  ];
  if (g.texts.length) {
    out.push('', 'Text in the drawing:');
    for (const t of g.texts.slice(0, 60)) out.push(`  at (${f1(t.x)}, ${f1(t.y)}) h ${f1(t.h)}: ${JSON.stringify(t.text)}`);
  }
  out.push('', 'Loops. "depth" = how many closed loops contain it (0 = outermost); "in" = the loop that directly contains it.',
    'id | layer | closed/open | depth | in | shape | size W×H mm | area mm² | length mm | lines/arcs');
  // Circles with the same size in the same parent are listed once with all their ids, so big nests stay short
  const groups = new Map();
  for (const l of g.loops) {
    if (l.circle) {
      const k = `${l.parent}|${l.layer}|${f2(2 * l.circle.r)}`;
      if (!groups.has(k)) groups.set(k, []);
      groups.get(k).push(l);
      continue;
    }
    const w = l.bbox[2] - l.bbox[0], h = l.bbox[3] - l.bbox[1];
    out.push(`${l.id} | ${l.layer} | ${l.closed ? 'closed' : 'OPEN'} | ${l.depth ?? '-'} | ${l.parent ?? '-'} | ${l.lines && !l.arcs ? 'lines' : !l.lines ? 'arcs' : 'lines+arcs'}`
      + ` | ${f1(w)} × ${f1(h)} at (${f1(l.bbox[0])}, ${f1(l.bbox[1])}) | ${l.closed ? Math.round(l.area) : '-'} | ${f1(l.perimeter)} | ${l.lines}/${l.arcs}`);
  }
  for (const ls of groups.values()) {
    const l = ls[0];
    out.push(`${ls.map((x) => x.id).join(',')} | ${l.layer} | closed | ${l.depth} | ${l.parent ?? '-'} | CIRCLE Ø${f2(2 * l.circle.r)}`
      + ` | ${ls.length} circle${ls.length > 1 ? 's' : ''} | ${Math.round(l.area)} each | ${f1(l.perimeter)} each | -`);
  }
  return out.join('\n');
}

const SYSTEM = `You are the CAM planner for a small 3-axis CNC wood router (Grbl 1.1, 2438 × 1219 mm bed).
You receive a DXF drawing already reduced to closed and open loops, plus the tool and the material.
You do NOT write G-code. You return a cut plan as JSON; a deterministic generator turns it into G-code
(Clipper tool-radius offsets, equal depth passes, tabs, peck drilling).

Machine facts:
- Grbl max rate is ${MAX_FEED} mm/min on X/Y and ${MAX_PLUNGE} mm/min on Z; acceleration is low (30 mm/s² XY, 10 mm/s² Z), so short moves rarely reach full feed.
- Spindle: trim-router motor on a VFD, switched by M3 S<rpm> / M5, about 10 000–24 000 rpm, typically run at 18 000–20 000.
- Work origin: X0 Y0 = the DXF origin, Z0 = top of the stock. Through cuts go a little into a spoilboard.
- End mills are usually 2-flute flat, upcut.

Operations you can assign to loops:
- "outside": cut on the outside of a closed loop (a part outline: the part keeps its drawn size).
- "inside": cut on the inside of a closed loop (a hole or cut-out bigger than the tool).
- "drill": plunge at the centre of a CIRCLE with the end mill (peck drilling). Only sensible when the hole
  diameter is about the tool diameter or a little smaller (the hole then comes out at the tool diameter).
- "engrave": follow the line exactly at engrave_depth_mm (open lines, marks, text outlines).
- "skip": do not cut (sheet borders, stock outlines, title blocks, dimension or note boxes, construction lines).

How to plan:
- Work out what the drawing is (nesting reports, labels and layer names are strong hints) and which loops are real parts.
- Every loop id must appear in exactly one feature entry; group many loops into one entry when they share an operation.
- A hole smaller than the tool can only be drilled; say so in warnings if its size will change.
- Choose feed, plunge, rpm and step-down for the material and tool on this light machine; stay within the limits above.
- cut_depth_mm for through cuts = stock thickness plus a small breakthrough (about 0.3–1 mm).
- Tabs (bridges) keep cut-free parts and cut-out slugs from moving or flying off (there is no vacuum table);
  per_part 0 means none. When the operator has chosen the bridges, use exactly their settings.
- Put anything the operator must know (clamping, tool length, risks, assumptions) in warnings, briefly.`;

const PLAN_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  required: ['summary', 'spindle_rpm', 'feed_mm_min', 'plunge_mm_min', 'stepdown_mm', 'cut_depth_mm', 'engrave_depth_mm',
    'safe_z_mm', 'milling', 'tabs', 'peck_mm', 'features', 'warnings'],
  properties: {
    summary: { type: 'string', description: 'What the drawing is and how it will be cut, 1-3 sentences' },
    spindle_rpm: { type: 'integer' },
    feed_mm_min: { type: 'number' },
    plunge_mm_min: { type: 'number' },
    stepdown_mm: { type: 'number', description: 'Max depth per pass' },
    cut_depth_mm: { type: 'number', description: 'Total depth of through cuts, positive' },
    engrave_depth_mm: { type: 'number' },
    safe_z_mm: { type: 'number', description: 'Rapid height above the stock' },
    milling: { type: 'string', enum: ['climb', 'conventional'] },
    tabs: {
      type: 'object', additionalProperties: false, required: ['per_part', 'width_mm', 'height_mm', 'on_cutouts'],
      properties: { per_part: { type: 'integer', description: 'Bridges per cut loop; 0 = none' }, width_mm: { type: 'number' }, height_mm: { type: 'number' },
        on_cutouts: { type: 'boolean', description: 'Also bridge inside cut-outs so their waste slugs stay put' } },
    },
    peck_mm: { type: 'number', description: 'Retract to clear chips every N mm when drilling' },
    features: {
      type: 'array',
      items: {
        type: 'object', additionalProperties: false, required: ['loops', 'op', 'reason'],
        properties: {
          loops: { type: 'array', items: { type: 'integer' } },
          op: { type: 'string', enum: ['outside', 'inside', 'drill', 'engrave', 'skip'] },
          reason: { type: 'string' },
        },
      },
    },
    warnings: { type: 'array', items: { type: 'string' } },
  },
};

// Ask Claude for the plan. onProgress({phase, thinking, chars}) is called while it streams.
async function requestPlan(g, file, params, onProgress = () => {}, findings = []) {
  const notes = findings.length ? `

A rule-based preprocessor scanned this drawing first. Its findings ([ai] = why the rules could not plan it alone):
${findings.map((f) => `- [${f.level}] ${f.text}`).join('\n')}` : '';
  const prompt = `${describeGeometry(g, file)}${notes}

${params.bridges ? `Bridges (tabs), chosen by the operator: ${params.bridges.enabled ? `${params.bridges.count} per loop, ${params.bridges.width} mm wide, ${params.bridges.height} mm high, ${params.bridges.cutouts ? 'on part outlines and on cut-outs' : 'on part outlines only'}` : 'none'}.

` : ''}Tool: ${params.toolDiameter} mm flat end mill
Material: ${params.material}
Stock thickness: ${params.thickness} mm

Return the cut plan.`;
  const stream = anthropic().beta.messages.stream({
    model: MODEL,
    max_tokens: 32000,
    betas: ['server-side-fallback-2026-07-01'],
    fallbacks: 'default',
    thinking: { type: 'adaptive', display: 'summarized' },
    output_config: { effort: EFFORT, format: { type: 'json_schema', schema: PLAN_SCHEMA } },
    system: SYSTEM,
    messages: [{ role: 'user', content: prompt }],
  });
  let thinking = '', chars = 0;
  for await (const ev of stream) {
    if (ev.type === 'content_block_delta') {
      if (ev.delta.type === 'thinking_delta') { thinking += ev.delta.thinking; onProgress({ phase: 'thinking', thinking: thinking.slice(-1200), chars }); }
      else if (ev.delta.type === 'text_delta') { chars += ev.delta.text.length; onProgress({ phase: 'writing plan', thinking: thinking.slice(-1200), chars }); }
    }
  }
  const msg = await stream.finalMessage();
  if (msg.stop_reason === 'refusal') throw new Error(`Claude declined the request${msg.stop_details?.explanation ? ': ' + msg.stop_details.explanation : ''}`);
  if (msg.stop_reason === 'max_tokens') throw new Error('Claude ran out of output tokens before finishing the plan');
  const text = msg.content.filter((b) => b.type === 'text').map((b) => b.text).join('');
  let plan;
  try { plan = JSON.parse(text); } catch { throw new Error('Claude returned a plan that is not valid JSON'); }
  return { plan, model: msg.model, prompt, thinking, usage: { input: msg.usage.input_tokens, output: msg.usage.output_tokens } };
}

function apiErrorMessage(e) {
  const detail = e?.error?.error?.message || e?.message;     // the API's own sentence, not the raw JSON body
  if (e instanceof Anthropic.AuthenticationError) return 'Anthropic API key rejected (check ANTHROPIC_API_KEY)';
  if (e instanceof Anthropic.PermissionDeniedError) return 'The API key has no access to this model';
  if (e instanceof Anthropic.RateLimitError) return 'Anthropic rate limit reached; try again in a minute';
  if (e instanceof Anthropic.BadRequestError) return `Anthropic API rejected the request: ${detail}`;
  if (e instanceof Anthropic.APIConnectionError) return 'Could not reach the Anthropic API';
  if (e instanceof Anthropic.APIError) return `Anthropic API error ${e.status ?? ''}: ${detail}`;
  return String(e.message || e);
}

// ---------------------------------------------------------------- plan checks
const clamp = (v, lo, hi, def) => (Number.isFinite(Number(v)) ? Math.min(hi, Math.max(lo, Number(v))) : def);

function normalizePlan(raw, g, params) {
  const D = params.toolDiameter, T = params.thickness;
  const warnings = [...(Array.isArray(raw.warnings) ? raw.warnings.map(String) : [])];
  const p = {
    summary: String(raw.summary || ''),
    spindle_rpm: Math.round(clamp(raw.spindle_rpm, 0, MAX_RPM, 18000)),
    feed_mm_min: clamp(raw.feed_mm_min, 50, MAX_FEED, Math.min(800, MAX_FEED)),
    plunge_mm_min: clamp(raw.plunge_mm_min, 20, MAX_PLUNGE, 300),
    stepdown_mm: clamp(raw.stepdown_mm, 0.2, Math.max(D, 0.2), Math.max(D / 2, 0.2)),
    cut_depth_mm: clamp(raw.cut_depth_mm, 0.1, T + 3, T + 0.5),
    engrave_depth_mm: clamp(raw.engrave_depth_mm, 0.05, T, 1),
    safe_z_mm: clamp(raw.safe_z_mm, 2, 50, 5),
    milling: raw.milling === 'climb' ? 'climb' : 'conventional',
    tabs: {
      per_part: Math.round(clamp(raw.tabs?.per_part, 0, 12, 0)),
      width_mm: clamp(raw.tabs?.width_mm, 1, 50, 8),
      height_mm: clamp(raw.tabs?.height_mm, 0.5, T, Math.min(3, T / 3)),
      on_cutouts: !!raw.tabs?.on_cutouts,
    },
    peck_mm: clamp(raw.peck_mm, 0.5, 50, Math.max(D, 3)),
    features: [],
    warnings,
  };
  for (const k of ['feed_mm_min', 'plunge_mm_min', 'spindle_rpm', 'stepdown_mm', 'cut_depth_mm']) {
    if (Number(raw[k]) !== p[k]) warnings.push(`${k} ${raw[k]} was limited to ${p[k]}`);
  }
  // the operator's Bridges setting wins over the plan's
  const b = params.bridges;
  if (b) p.tabs = b.enabled ? { per_part: b.count, width_mm: b.width, height_mm: b.height, on_cutouts: !!b.cutouts } : { ...p.tabs, per_part: 0 };
  if (p.tabs.per_part && p.tabs.height_mm >= p.cut_depth_mm) { warnings.push(`bridge height ${p.tabs.height_mm} mm is not below the cut depth; no bridges`); p.tabs.per_part = 0; }
  const byId = new Map(g.loops.map((l) => [l.id, l]));
  const assigned = new Map();
  for (const f of Array.isArray(raw.features) ? raw.features : []) {
    const op = ['outside', 'inside', 'drill', 'engrave', 'skip'].includes(f.op) ? f.op : 'skip';
    for (const id of Array.isArray(f.loops) ? f.loops : []) {
      const l = byId.get(Number(id));
      if (!l || assigned.has(l.id)) continue;
      let o = op;
      if ((o === 'outside' || o === 'inside') && !l.closed) { warnings.push(`loop ${l.id} is open, so it is engraved instead of cut ${o}`); o = 'engrave'; }
      if (o === 'drill' && !l.circle) { warnings.push(`loop ${l.id} is not a circle, so it cannot be drilled; skipped`); o = 'skip'; }
      assigned.set(l.id, { op: o, reason: String(f.reason || '') });
    }
  }
  const missing = g.loops.filter((l) => !assigned.has(l.id));
  for (const l of missing) assigned.set(l.id, { op: 'skip', reason: 'not in the plan' });
  if (missing.length) warnings.push(`${missing.length} loop(s) were left out of the plan and are not cut: ${missing.slice(0, 20).map((l) => l.id).join(', ')}${missing.length > 20 ? '…' : ''}`);
  p.features = [...assigned].map(([loop, f]) => ({ loop, ...f }));
  return p;
}

// ---------------------------------------------------------------- toolpaths
const toClip = (pts) => pts.map((p) => ({ X: Math.round(p[0] * SCALE), Y: Math.round(p[1] * SCALE) }));
const fromClip = (path) => path.map((q) => [q.X / SCALE, q.Y / SCALE]);

function offset(pts, delta) {
  const src = polyArea(pts) < 0 ? [...pts].reverse() : pts;        // CCW, so +delta grows and -delta shrinks
  const co = new ClipperLib.ClipperOffset(2, 0.005 * SCALE);
  co.AddPath(toClip(src), ClipperLib.JoinType.jtRound, ClipperLib.EndType.etClosedPolygon);
  const sol = new ClipperLib.Paths();
  co.Execute(sol, delta * SCALE);
  return sol.map(fromClip).filter((p) => p.length > 2);
}
function orient(pts, ccw) { return (polyArea(pts) > 0) === ccw ? pts : [...pts].reverse(); }
function startNear(pts, at) {
  let best = 0, bd = Infinity;
  pts.forEach((p, i) => { const d = (p[0] - at[0]) ** 2 + (p[1] - at[1]) ** 2; if (d < bd) { bd = d; best = i; } });
  return [...pts.slice(best), ...pts.slice(0, best)];
}

// Toolpath list per part, in cutting order: drills, engraving, inside cuts (small first), outlines last
function buildToolpaths(g, plan, params) {
  const R = params.toolDiameter / 2;
  const ops = new Map(plan.features.map((f) => [f.loop, f.op]));
  const warnings = [];
  const byId = new Map(g.loops.map((l) => [l.id, l]));
  // A part is the outermost loop that is actually cut; skipped sheet borders don't group anything
  const partOf = (l) => {
    let key = null;
    for (let p = l; p; p = p.parent ? byId.get(p.parent) : null) if ((ops.get(p.id) || 'skip') !== 'skip') key = p.id;
    return key ?? `loop${l.id}`;
  };
  const groups = new Map();      // part key -> toolpaths
  const oddHoles = new Map();    // diameter -> count of holes drilled with a different-size tool
  for (const l of g.loops) {
    const op = ops.get(l.id);
    if (!op || op === 'skip') continue;
    const key = partOf(l);
    if (!groups.has(key)) groups.set(key, []);
    const list = groups.get(key);
    if (op === 'drill') {
      const d = f2(2 * l.circle.r);
      if (Math.abs(d - params.toolDiameter) > 0.3) oddHoles.set(d, (oddHoles.get(d) || 0) + 1);
      list.push({ op, loop: l.id, kind: 'drill', at: l.circle.c, rank: 0 });
    } else if (op === 'engrave') {
      list.push({ op, loop: l.id, kind: l.closed ? 'ring' : 'line', pts: l.pts, rank: 1 });
    } else {
      const outside = op === 'outside';
      const paths = offset(l.pts, outside ? R : -R);
      if (!paths.length) { warnings.push(`loop ${l.id} is too small for a Ø${params.toolDiameter} tool to cut ${op}; skipped`); continue; }
      // climb milling with a clockwise spindle: outside contours clockwise, inside contours counter-clockwise
      const ccw = plan.milling === 'climb' ? !outside : outside;
      const use = outside ? [paths.reduce((a, b) => (Math.abs(polyArea(a)) >= Math.abs(polyArea(b)) ? a : b))] : paths;
      for (const p of use) list.push({ op, loop: l.id, kind: 'ring', pts: orient(p, ccw), tabs: outside || plan.tabs.on_cutouts, rank: outside ? 3 : 2, area: Math.abs(l.area) });
    }
  }
  for (const [d, n] of oddHoles) warnings.push(`${n} hole${n > 1 ? 's' : ''} drawn Ø${d} ${n > 1 ? 'are' : 'is'} drilled with the Ø${params.toolDiameter} tool and come${n > 1 ? '' : 's'} out Ø${params.toolDiameter}`);
  // part order: nearest neighbour from the origin
  const parts = [...groups.values()].map((tps) => {
    tps.sort((a, b) => a.rank - b.rank || (a.area || 0) - (b.area || 0));
    const p0 = tps[0].at || tps[0].pts[0];
    return { tps, p0 };
  });
  const ordered = [];
  let at = [0, 0];
  while (parts.length) {
    let bi = 0, bd = Infinity;
    parts.forEach((p, i) => { const d = Math.hypot(p.p0[0] - at[0], p.p0[1] - at[1]); if (d < bd) { bd = d; bi = i; } });
    const [p] = parts.splice(bi, 1);
    ordered.push(p.tps);
    const last = p.tps[p.tps.length - 1];
    at = last.at || last.pts[0];
  }
  return { parts: ordered, warnings };
}

// Replace runs of points that lie on one circle with G2/G3 moves (offset contours come back from
// Clipper as dense polylines). Moves: {p} for a line, {p, c, ccw} for an arc; the first point is the start.
const ARC_FIT_TOL = 0.01;   // mm
function circumcenter(a, b, c) {
  const d = 2 * (a[0] * (b[1] - c[1]) + b[0] * (c[1] - a[1]) + c[0] * (a[1] - b[1]));
  if (Math.abs(d) < 1e-12) return null;
  const a2 = a[0] ** 2 + a[1] ** 2, b2 = b[0] ** 2 + b[1] ** 2, c2 = c[0] ** 2 + c[1] ** 2;
  return [(a2 * (b[1] - c[1]) + b2 * (c[1] - a[1]) + c2 * (a[1] - b[1])) / d, (a2 * (c[0] - b[0]) + b2 * (a[0] - c[0]) + c2 * (b[0] - a[0])) / d];
}
const cross = (o, a, b) => (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
function arcFits(pts, i, j) {
  const c = circumcenter(pts[i], pts[(i + j) >> 1], pts[j]);
  if (!c) return null;
  const r = Math.hypot(pts[i][0] - c[0], pts[i][1] - c[1]);
  if (r > 2000 || r < 0.05) return null;
  const turn = Math.sign(cross(pts[i], pts[i + 1], pts[i + 2]));
  let sweep = 0;
  for (let k = i; k <= j; k++) {
    if (Math.abs(Math.hypot(pts[k][0] - c[0], pts[k][1] - c[1]) - r) > ARC_FIT_TOL) return null;
    if (k > i) {
      if (k < j && Math.sign(cross(pts[k - 1], pts[k], pts[k + 1])) !== turn) return null;
      // every chord must also stay within tolerance of the arc (no point far between samples)
      const half = Math.hypot(pts[k][0] - pts[k - 1][0], pts[k][1] - pts[k - 1][1]) / 2;
      if (r - Math.sqrt(Math.max(0, r * r - half * half)) > ARC_FIT_TOL) return null;
      sweep += 2 * Math.asin(Math.min(1, half / r));
    }
  }
  if (sweep > 1.5 * Math.PI) return null;      // keep arcs well short of a full circle
  return { c, ccw: turn > 0 };
}
function fitMoves(pts) {
  const moves = [{ p: pts[0] }];
  let i = 0;
  while (i < pts.length - 1) {
    let best = null, bj = i + 1;
    if (i + 3 < pts.length) {
      let j = i + 3, fit;
      while (j < pts.length && (fit = arcFits(pts, i, j))) { best = fit; bj = j; j++; }
    }
    if (best) { moves.push({ p: pts[bj], c: best.c, ccw: best.ccw }); i = bj; } else { moves.push({ p: pts[i + 1] }); i++; }
  }
  return moves;
}

// ---------------------------------------------------------------- G-code
const n3 = (v) => { const s = (Math.round(v * 1000) / 1000).toFixed(3).replace(/\.?0+$/, ''); return s === '-0' ? '0' : s; };

function generateGcode(g, plan, params, meta) {
  const { parts, warnings } = buildToolpaths(g, plan, params);
  const out = [];
  const L = (s) => out.push(s);
  const depth = plan.cut_depth_mm, nPass = Math.ceil(depth / plan.stepdown_mm - 1e-9);
  const passes = Array.from({ length: nPass }, (_, i) => -depth * (i + 1) / nPass);
  const tabTop = -(depth - plan.tabs.height_mm);
  const st = { cut: 0, rapid: 0, plunge: 0, x: 0, y: 0, z: plan.safe_z_mm, features: 0, holes: 0, bridges: 0 };
  const rapidXY = (x, y) => { st.rapid += Math.hypot(x - st.x, y - st.y); st.x = x; st.y = y; L(`G0 X${n3(x)} Y${n3(y)}`); };
  const rapidZ = (z) => { st.rapid += Math.abs(z - st.z); st.z = z; L(`G0 Z${n3(z)}`); };
  let feedSet = false;      // F of the cutting feed is modal; every Z feed move replaces it with the plunge feed
  const feedZ = (z) => { st.plunge += Math.abs(z - st.z); st.z = z; feedSet = false; L(`G1 Z${n3(z)} F${n3(plan.plunge_mm_min)}`); };
  const fword = () => { const f = feedSet ? '' : ` F${n3(plan.feed_mm_min)}`; feedSet = true; return f; };
  const cutXY = (x, y, note = '') => { st.cut += Math.hypot(x - st.x, y - st.y); st.x = x; st.y = y; L(`G1 X${n3(x)} Y${n3(y)}${fword()}${note}`); };
  const cutArc = (m) => {
    const r = Math.hypot(st.x - m.c[0], st.y - m.c[1]);
    const a0 = Math.atan2(st.y - m.c[1], st.x - m.c[0]), a1 = Math.atan2(m.p[1] - m.c[1], m.p[0] - m.c[0]);
    let sw = m.ccw ? a1 - a0 : a0 - a1;
    while (sw < 0) sw += 2 * Math.PI;
    st.cut += r * sw;
    L(`${m.ccw ? 'G3' : 'G2'} X${n3(m.p[0])} Y${n3(m.p[1])} I${n3(m.c[0] - st.x)} J${n3(m.c[1] - st.y)}${fword()}`);
    st.x = m.p[0]; st.y = m.p[1];
  };
  const cutMoves = (moves) => { for (const m of moves.slice(1)) (m.c ? cutArc(m) : cutXY(m.p[0], m.p[1])); };
  const goTo = (p) => { if (st.z < plan.safe_z_mm) rapidZ(plan.safe_z_mm); rapidXY(p[0], p[1]); rapidZ(CLEAR); };

  // Bridge spans along a ring, as [start, end] arc lengths. Evenly spaced, each moved to the nearest
  // straight stretch (no corner or tight curve under it) so it is easy to saw or chisel off afterwards.
  const tooSmall = { outside: 0, inside: 0 };
  const ringTabs = (pts, op) => {
    if (!plan.tabs.per_part) return [];
    const cum = [0];
    for (let i = 1; i <= pts.length; i++) { const a = pts[i - 1], b = pts[i % pts.length]; cum.push(cum[i - 1] + Math.hypot(b[0] - a[0], b[1] - a[1])); }
    const len = cum[pts.length];
    const w = plan.tabs.width_mm + params.toolDiameter;       // the tool edge leaves a bridge of width_mm
    const n = Math.min(plan.tabs.per_part, Math.floor(len / (3 * w)));
    if (n < 1) { tooSmall[op]++; return []; }
    const seg = (sArc) => { let lo = 0, hi = pts.length - 1; while (lo < hi) { const m = (lo + hi + 1) >> 1; if (cum[m] <= sArc) lo = m; else hi = m - 1; } return lo; };
    const dir = (i) => { const a = pts[i], b = pts[(i + 1) % pts.length]; return Math.atan2(b[1] - a[1], b[0] - a[0]); };
    const straight = (c) => {
      const i0 = seg(Math.max(0, c - w / 2 - 1)), i1 = seg(Math.min(len - 1e-9, c + w / 2 + 1)), d0 = dir(i0);
      for (let i = i0 + 1; i <= i1; i++) { let d = Math.abs(dir(i) - d0); if (d > Math.PI) d = 2 * Math.PI - d; if (d > 0.14) return false; }   // 8°
      return true;
    };
    const step = Math.max(0.5, len / 4000), reach = len / (2 * n) - w;
    const spans = [];
    for (let i = 0; i < n; i++) {
      const target = len * (i + 0.5) / n;
      let c = target;
      for (let d = 0; d <= reach; d += step) {
        if (target + d <= len - w / 2 && straight(target + d)) { c = target + d; break; }
        if (target - d >= w / 2 && straight(target - d)) { c = target - d; break; }
      }
      spans.push([c - w / 2, c + w / 2]);
    }
    return spans;
  };
  const cutRing = (pts, z, tabs) => {
    const ring = [...pts, pts[0]];
    let s = 0;
    const zAt = (sm) => (tabs.some(([a, b]) => sm > a && sm < b) && z < tabTop ? tabTop : z);
    for (let i = 1; i < ring.length; i++) {
      const a = ring[i - 1], b = ring[i], len = Math.hypot(b[0] - a[0], b[1] - a[1]);
      // split this edge where tabs start and end
      const cuts = [0, len];
      for (const [t0, t1] of tabs) for (const t of [t0, t1]) if (t > s && t < s + len) cuts.push(t - s);
      cuts.sort((p, q) => p - q);
      for (let k = 1; k < cuts.length; k++) {
        if (cuts[k] - cuts[k - 1] < 1e-6) continue;
        const zz = zAt(s + (cuts[k - 1] + cuts[k]) / 2);
        if (Math.abs(zz - st.z) > 1e-6) feedZ(zz);
        const t = cuts[k] / len;
        cutXY(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, zz > z ? ' (tab)' : '');
      }
      s += len;
    }
  };

  const u = (v) => n3(v);
  L('%');
  L(`(${meta.file} - AI CAM toolpath)`);
  L(`(Generated ${new Date(meta.created).toISOString()} by the backoffice; plan by ${meta.model})`);
  for (const line of wrap(plan.summary, 90)) L(`(${line})`);
  L(`(T1 D=${u(params.toolDiameter)} flat end mill - ${clean(params.material)}, stock ${u(params.thickness)} mm)`);
  L(`(Through cuts ${u(depth)} mm in ${nPass} passes of ${u(depth / nPass)} mm, ${plan.milling} milling)`);
  L(`(Feed ${u(plan.feed_mm_min)}, plunge ${u(plan.plunge_mm_min)} mm/min, spindle ${plan.spindle_rpm} rpm)`);
  L(plan.tabs.per_part ? `(Bridges: ${plan.tabs.per_part} per ${plan.tabs.on_cutouts ? 'outline and cut-out' : 'outline'}, ${u(plan.tabs.width_mm)} mm wide, ${u(plan.tabs.height_mm)} mm high - lines marked "tab")` : '(No bridges)');
  L('(ORIGIN: X0 Y0 = DXF origin, Z0 = TOP of the stock)');
  for (const w of [...plan.warnings, ...warnings]) for (const line of wrap('NOTE: ' + w, 90)) L(`(${line})`);
  const notesAt = out.length;          // bridge notes are known only after the toolpaths: inserted here
  L('G21 G90 G17 G94');
  L(`G0 Z${u(plan.safe_z_mm)}`);
  L('(PAUSE - check the stock is clamped and Z0 is set, then press Resume to start the spindle)');
  L('M0');
  if (plan.spindle_rpm > 0) { L(`M3 S${plan.spindle_rpm} (spindle ON)`); L('G4 P10 (wait for the spindle to reach speed)'); }

  parts.forEach((tps, pi) => {
    L(`(Part ${pi + 1}/${parts.length})`);
    for (const tp of tps) {
      st.features++;
      if (tp.kind === 'drill') {
        st.holes++;
        L(`(loop ${tp.loop}: drill)`);
        goTo(tp.at);
        let z = 0;
        while (z > -depth + 1e-6) {
          const next = Math.max(-depth, z - plan.peck_mm);
          if (z < 0) rapidZ(z + 0.5);
          feedZ(next);
          rapidZ(CLEAR);
          z = next;
        }
        continue;
      }
      const engrave = tp.op === 'engrave';
      const nEng = Math.ceil(plan.engrave_depth_mm / plan.stepdown_mm - 1e-9);
      const zs = engrave ? Array.from({ length: nEng }, (_, i) => -plan.engrave_depth_mm * (i + 1) / nEng) : passes;
      if (tp.kind === 'ring') {
        const pts = startNear(tp.pts, [st.x, st.y]);
        const tabs = tp.tabs ? ringTabs(pts, tp.op) : [];
        st.bridges += tabs.length;
        L(`(loop ${tp.loop}: ${tp.op}${tabs.length ? `, ${tabs.length} bridges` : ''})`);
        const moves = fitMoves([...pts, pts[0]]);
        goTo(pts[0]);
        for (const z of zs) {
          feedZ(z);
          if (tabs.length && z < tabTop) cutRing(pts, z, tabs); else cutMoves(moves);
        }
      } else {
        L(`(loop ${tp.loop}: engrave line)`);
        let pts = tp.pts;
        const d0 = Math.hypot(pts[0][0] - st.x, pts[0][1] - st.y), d1 = Math.hypot(pts[pts.length - 1][0] - st.x, pts[pts.length - 1][1] - st.y);
        if (d1 < d0) pts = [...pts].reverse();
        goTo(pts[0]);
        const fwd = fitMoves(pts), back = fitMoves([...pts].reverse());
        zs.forEach((z, k) => {            // back and forth, one pass each way
          feedZ(z);
          cutMoves(k % 2 ? back : fwd);
        });
      }
      rapidZ(plan.safe_z_mm);
    }
  });
  L(`G0 Z${u(plan.safe_z_mm)}`);
  if (plan.spindle_rpm > 0) L('M5 (spindle OFF)');
  L('G0 X0 Y0');
  L('M2');
  L('%');

  const late = [];
  if (tooSmall.outside) late.push(`${tooSmall.outside} part outline(s) are too short for a ${plan.tabs.width_mm} mm bridge and have none`);
  if (tooSmall.inside) late.push(`${tooSmall.inside} cut-out(s) are too small for a bridge: their waste comes loose on the last pass`);
  out.splice(notesAt, 0, ...late.flatMap((w) => wrap('NOTE: ' + w, 90).map((line) => `(${line})`)));
  warnings.push(...late);
  const minutes = st.cut / plan.feed_mm_min + st.plunge / plan.plunge_mm_min + st.rapid / RAPID;
  return {
    gcode: out.join('\n') + '\n',
    warnings,
    stats: {
      parts: parts.length, features: st.features, holes: st.holes, bridges: st.bridges, passes: nPass, lines: out.length,
      cut_m: Math.round(st.cut) / 1000, rapid_m: Math.round(st.rapid) / 1000, est_min: Math.round(minutes * 1.25),
    },
  };
}
const clean = (s) => String(s).replace(/[()]/g, '');
function wrap(text, n) {
  const words = clean(text).split(/\s+/).filter(Boolean), lines = [];
  let cur = '';
  for (const w of words) { if (cur && (cur + ' ' + w).length > n) { lines.push(cur); cur = w; } else cur = cur ? cur + ' ' + w : w; }
  if (cur) lines.push(cur);
  return lines;
}

module.exports = { requestPlan, normalizePlan, generateGcode, describeGeometry, apiErrorMessage, MODEL };
