"""Generate Grbl G-code for the plywood hex-dome parts.

Work origin: X0 Y0 = lower-left corner of the sheet stack (the DXF origin),
             Z0    = TOP of the stack.
Spindle: by default the MK300 VFD is switched by M3/M5 (Grbl built with
USE_SPINDLE_DIR_AS_ENABLE_PIN: D13 -> optocoupler -> X1/COM); --spindle manual
restores the M0 pauses for a hand-switched router.
Strategy per part (nearest-neighbour order to cut travel):
  1. holes, peck-drilled with the endmill (hole = tool diameter)
  2. outside profile, offset by the tool radius, conventional milling (CCW),
     full depth in equal step-downs, no tabs.
"""
import argparse
import datetime
import math
import os

from shapely.geometry import LineString, Point, Polygon, box
from shapely.ops import unary_union
from shapely.geometry.polygon import orient

import dxf_parts

p = argparse.ArgumentParser()
p.add_argument("dxf")
p.add_argument("-o", "--out", required=True)
p.add_argument("--tool", type=float, default=8.0, help="endmill diameter mm")
p.add_argument("--stock", type=float, default=30.0, help="stack thickness mm (6 x 5 mm)")
p.add_argument("--through", type=float, default=0.5, help="extra depth into the spoilboard mm")
p.add_argument("--step", type=float, default=4.0, help="max depth per pass mm")
p.add_argument("--start-depth", type=float, default=0.0, help="profile passes start below this depth mm (re-cut an existing slot deeper; the tool feeds down the slot from --approach)")
p.add_argument("--feed", type=float, default=800, help="cutting feed mm/min")
p.add_argument("--plunge", type=float, default=500, help="plunge feed mm/min (Grbl caps it at $112)")
p.add_argument("--peck", default="6", help="peck length mm, or a list like 15,16 (last one repeats); retract to clear chips after each (0 = one plunge)")
p.add_argument("--hole-depth", type=float, default=None, help="hole depth below Z0 mm (default: stock + through)")
p.add_argument("--safe", type=float, default=5.0, help="safe Z above the stack mm")
p.add_argument("--hole-lift", type=float, default=None,
               help="Z for hops between holes of the same part mm (default: --safe); moves between parts still lift to --safe")
p.add_argument("--approach", type=float, default=5.0, help="rapid down to this Z, then feed into the stack (covers an uneven top) mm")
p.add_argument("--spindle", choices=["vfd", "manual"], default="vfd",
               help="vfd: M3/M5 switch the MK300 via Grbl's spindle-enable pin (D13); manual: M0 pauses to switch by hand")
p.add_argument("--ops", choices=["all", "holes", "profile"], default="all",
               help="holes: drill only; profile: outside cuts only (two files for two tools, same X/Y zero)")
p.add_argument("--rpm", type=float, default=20000, help="spindle speed word for M3 (keep Grbl $30 equal to it)")
p.add_argument("--tabs", type=int, default=0, help="full-height bridges per part outline (0 = none)")
p.add_argument("--tab-width", type=float, default=5.0, help="wood left in each bridge mm")
p.add_argument("--tab-min-seg", type=float, default=30.0, help="shortest straight side a bridge may sit on mm")
p.add_argument("--add-tab", action="append", default=[], metavar="INSERT:X,Y",
               help="with --tabs: one more bridge on part INSERT, at the clear spot nearest X,Y (repeatable)")
p.add_argument("--spinup", type=float, default=10.0, help="dwell after M3 so the spindle reaches speed, s")
p.add_argument("--holes-first", action="store_true",
               help="with --ops all: every hole on the sheet first, then every outline (default: per part)")
p.add_argument("--depth-first", action="store_true",
               help="with --tabs: cut each side between two bridges to full depth (alternating direction) before hopping on, instead of lifting over every bridge on every pass")
p.add_argument("--helix-pitch", type=float, default=2.0, help="Z drop per turn when a hole is milled as an inside contour mm")
p.add_argument("--rev",default="", help="version tag written into the header, e.g. \"v2 - safe Z30\"")
args = p.parse_args()
args.rev = args.rev.replace("(", "").replace(")", "")   # Grbl comments cannot nest

R = args.tool / 2
DEPTH = args.stock + args.through
TOP = args.start_depth
NPASS = math.ceil((DEPTH - TOP) / args.step - 1e-9)
STEP = (DEPTH - TOP) / NPASS
HOLE_LIFT = args.safe if args.hole_lift is None else args.hole_lift
if not args.approach <= HOLE_LIFT <= args.safe:
    raise SystemExit("--hole-lift must be between --approach and --safe")
HOLE_DEPTH = args.hole_depth if args.hole_depth is not None else DEPTH
PECKS = [float(v) for v in args.peck.split(",")]
if PECKS == [0]:
    PECKS = [HOLE_DEPTH]
if min(PECKS) <= 0:
    raise SystemExit("--peck: lengths must be > 0")

sheet, parts, problems, _ = dxf_parts.load(args.dxf)
if problems:
    raise SystemExit("DXF problems: " + "; ".join(problems))

out = []
def g(s): out.append(s)
def f3(v): return f"{v:.3f}".rstrip("0").rstrip(".") if abs(v) >= 0.0005 else "0"

cut_len = rapid_len = plunge_len = 0.0
pos = [0.0, 0.0, None]   # Z None = still at the homed height, above any safe Z

def rapid_xy(x, y):
    global rapid_len
    if pos[2] is None:       # Z may be down at a touch-off: lift to the homed top (MPos Z0) first; no move if already home
        g("G53 G0 Z0 (lift to the homed top before any X/Y move)")
    rapid_len += math.dist(pos[:2], (x, y))
    g(f"G0 X{f3(x)} Y{f3(y)}")
    pos[0], pos[1] = x, y
    if pos[2] is None:       # first move from home: X/Y at the homed height, only then down to the safe Z
        pos[2] = args.safe
        g(f"G0 Z{f3(args.safe)}")

def rapid_z(z):
    global rapid_len
    if pos[2] is None and z >= args.safe:
        return
    if abs(pos[2] - z) < 1e-9:
        return
    rapid_len += abs(pos[2] - z)
    g(f"G0 Z{f3(z)}")
    pos[2] = z

def plunge(z, feed=None):
    global plunge_len
    plunge_len += abs(pos[2] - z)
    g(f"G1 Z{f3(z)} F{feed or args.plunge:.0f}")
    pos[2] = z

# ---------------------------------------------------------------- toolpaths

def profile_path(part):
    """Outside offset of the part outline, CCW (conventional cut), as a closed point list."""
    off = part.outline.buffer(R, quad_segs=16, join_style=1)
    off = orient(Polygon(off.exterior), sign=1.0)            # CCW
    off = off.simplify(0.005, preserve_topology=True)
    pts = list(off.exterior.coords)[:-1]
    return pts

TAB_CLEAR = 3.0  # mm of solid waste a bridge needs beyond the slot
ISLAND_MIN = 500.0  # mm2: waste islands at least this big get a bridge of their own
FIT_TOL = 0.01   # mm: max deviation of the original points from a fitted line/arc


def _circle(a, b, c):
    """Circle through three points -> (cx, cy, r) or None if collinear."""
    ax, ay = a; bx, by = b; cx, cy = c
    d = 2 * (ax * (by - cy) + bx * (cy - ay) + cx * (ay - by))
    if abs(d) < 1e-12:
        return None
    ux = ((ax * ax + ay * ay) * (by - cy) + (bx * bx + by * by) * (cy - ay) + (cx * cx + cy * cy) * (ay - by)) / d
    uy = ((ax * ax + ay * ay) * (cx - bx) + (bx * bx + by * by) * (ax - cx) + (cx * cx + cy * cy) * (bx - ax)) / d
    return ux, uy, math.dist((ux, uy), a)


def _cross(o, a, b):
    return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])


def _line_ok(pts, i, j):
    a, b = pts[i], pts[j]
    L = math.dist(a, b)
    if L < 1e-9:
        return False
    for k in range(i + 1, j):
        t = ((pts[k][0] - a[0]) * (b[0] - a[0]) + (pts[k][1] - a[1]) * (b[1] - a[1])) / (L * L)
        if t < 0 or t > 1 or abs(_cross(a, b, pts[k])) / L > FIT_TOL:
            return False
    return True


def _arc_ok(pts, i, j):
    """Points i..j lie on one circle (within FIT_TOL), turning one way, sweep < 330 deg."""
    m = (i + j) // 2
    c = _circle(pts[i], pts[m], pts[j])
    if c is None or c[2] > 2000:
        return None
    cx, cy, r = c
    turn = _cross(pts[i], pts[m], pts[j])
    sweep = 0.0
    for k in range(i, j + 1):
        if abs(math.dist((cx, cy), pts[k]) - r) > FIT_TOL:
            return None
        if k > i:
            if _cross((cx, cy), pts[k - 1], pts[k]) * turn < 0:   # must keep circling the same way
                return None
            # the original straight piece k-1..k must also hug the arc (sagitta check)
            half = math.dist(pts[k - 1], pts[k]) / 2
            if half >= r or r - math.sqrt(r * r - half * half) > FIT_TOL:
                return None
            a0 = math.atan2(pts[k - 1][1] - cy, pts[k - 1][0] - cx)
            a1 = math.atan2(pts[k][1] - cy, pts[k][0] - cx)
            sweep += abs((a1 - a0 + math.pi) % (2 * math.pi) - math.pi)
    if sweep > math.radians(330):
        return None
    return cx, cy, r, turn > 0       # True = CCW (G3)


def fit_moves(pts):
    """Open polyline -> list of ('L', x, y) / ('A', x, y, cx, cy, ccw) moves (start point excluded)."""
    moves, i, n = [], 0, len(pts)
    while i < n - 1:
        # longest arc starting at i (needs >= 4 points to be worth it)
        best_arc, arc = None, None
        j = i + 3
        while j < n:
            a = _arc_ok(pts, i, j)
            if a is None:
                break
            best_arc, arc = j, a
            j += 1
        # longest straight run starting at i
        best_line = i + 1
        while best_line + 1 < n and _line_ok(pts, i, best_line + 1):
            best_line += 1
        if best_arc is not None and best_arc > best_line:
            cx, cy, r, ccw = arc
            moves.append(("A", *pts[best_arc], cx, cy, ccw))
            i = best_arc
        else:
            moves.append(("L", *pts[best_line]))
            i = best_line
    return moves


def start_index(pts, near):
    return min(range(len(pts)), key=lambda i: math.dist(pts[i], near))

def tab_spots(pts, part, min_seg=None):
    """Clear bridge spots on the closed point list, one per long straight side: (seg, L, t centre, XY).
    A bridge must reach solid waste: no neighbour's slot and no stack edge within it."""
    min_seg = args.tab_min_seg if min_seg is None else min_seg
    n = len(pts)
    gap = args.tab_width + args.tool
    others = unary_union([q.outline for q in parts if q is not part]).buffer(args.tool + TAB_CLEAR)
    inside = box(*sheet).buffer(-TAB_CLEAR)
    spots = []
    for i in range(n):
        a, b = pts[i], pts[(i + 1) % n]
        L = math.dist(a, b)
        if L < max(min_seg, gap + 2):
            continue
        # slide along the side in 2 mm steps, keep the clear spot nearest its middle
        h = gap / 2 / L
        for tc in sorted((h + k * 2 / L for k in range(int((1 - 2 * h) * L / 2) + 1)), key=lambda t: abs(t - 0.5)):
            band = LineString([_lerp(a, b, tc - h), _lerp(a, b, tc + h)]).buffer(R)
            if inside.contains(band) and not band.intersects(others):
                spots.append((i, L, tc, _lerp(a, b, tc)))
                break
    return spots


def pick_tabs(spots, part):
    """The longest side first, then the side farthest from the bridges already chosen."""
    cand, chosen = spots[:], []
    while cand and len(chosen) < args.tabs:
        if not chosen:
            c = max(cand, key=lambda c: c[1])
        else:
            c = max(cand, key=lambda c: min(math.dist(c[3], d[3]) for d in chosen))
        chosen.append(c)
        cand.remove(c)
    if len(chosen) < args.tabs:
        print(f"warning: part {part.index}: only {len(chosen)} of {args.tabs} bridges have solid waste behind them")
    return chosen


def tab_gaps(chosen):
    """Chosen spots -> [(seg index, t0, t1)] sorted along the ring."""
    gap = args.tab_width + args.tool
    return sorted((i, tc - gap / 2 / L, tc + gap / 2 / L) for i, L, tc, _ in chosen)


def swept_area(pts, chosen):
    pieces = split_at_tabs(pts, tab_gaps(chosen)) if chosen else [pts + pts[:1]]
    return unary_union([LineString(pc).buffer(R, quad_segs=8) for pc in pieces])


def plan_tabs():
    """Bridges for every part, plus one more for each waste island the slots would cut loose."""
    paths = {id(q): profile_path(q) for q in parts}
    spots = {id(q): tab_spots(paths[id(q)], q) for q in parts}
    chosen = {id(q): pick_tabs(spots[id(q)], q) for q in parts}
    for spec in args.add_tab:
        try:
            ins, xy = spec.split(":")
            q = next(q for q in parts if q.index == int(ins))
            want = tuple(float(v) for v in xy.split(","))
        except (ValueError, StopIteration):
            raise SystemExit(f"--add-tab {spec}: want INSERT:X,Y with an insert number from the DXF")
        # any straight side the bridge gap fits on, not just the --tab-min-seg ones
        taken = {sp[0] for sp in chosen[id(q)]}
        free = [sp for sp in tab_spots(paths[id(q)], q, 0) if sp[0] not in taken]
        if not free:
            raise SystemExit(f"--add-tab {spec}: part {q.index} has no clear bridge spot left")
        sp = min(free, key=lambda sp: math.dist(sp[3], want))
        if math.dist(sp[3], want) > 30:
            raise SystemExit(f"--add-tab {spec}: nearest clear spot is X{sp[3][0]:.0f} Y{sp[3][1]:.0f}, over 30 mm away")
        chosen[id(q)].append(sp)
        print(f"added bridge: part {q.index} at X{sp[3][0]:.1f} Y{sp[3][1]:.1f}")
    swept = unary_union([swept_area(paths[id(q)], chosen[id(q)]) for q in parts])
    solid = box(*sheet).difference(swept)
    pieces = sorted(getattr(solid, "geoms", [solid]), key=lambda g: -g.area)
    partc = unary_union([q.outline for q in parts])
    islands = [g for g in pieces[1:] if g.intersection(partc).area < 0.5 * g.area]
    small = 0
    for isl in islands:
        if isl.area < ISLAND_MIN:
            small += 1
            continue
        best = None
        for q in parts:
            for sp in spots[id(q)]:
                if sp in chosen[id(q)] or Point(sp[3]).distance(isl) > R + 1:
                    continue
                if best is None or sp[1] > best[1][1]:
                    best = (q, sp)
        if best is None:
            c = isl.centroid
            print(f"warning: loose waste {isl.area:.0f} mm2 at X{c.x:.0f} Y{c.y:.0f} has no bridge spot - take it out by hand")
            continue
        chosen[id(best[0])].append(best[1])
        tab_extra.append(best[0].index)
    if small:
        print(f"note: {small} waste slivers under {ISLAND_MIN:g} mm2 per sheet will come loose")
    return {i: tab_gaps(c) for i, c in chosen.items()}


def _lerp(a, b, t):
    return (a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)


def split_at_tabs(pts, tabs):
    """Closed ring + bridge gaps -> open pieces, each running from one bridge to the next."""
    n = len(pts)
    pieces = []
    for k, (i, t0, t1) in enumerate(tabs):
        j, u0, _ = tabs[(k + 1) % len(tabs)]
        steps = (j - i) % n or n             # ring points i+1 .. j
        piece = [_lerp(pts[i], pts[(i + 1) % n], t1)]
        piece += [pts[(i + 1 + q) % n] for q in range(steps)]
        piece.append(_lerp(pts[j], pts[(j + 1) % n], u0))
        pieces.append(piece)
    return pieces


def emit_moves(moves, feed):
    global cut_len
    feed = f" F{feed:.0f}"
    for mv in moves:
        if mv[0] == "L":
            _, x, y = mv
            cut_len += math.dist(pos[:2], (x, y))
            g(f"G1 X{f3(x)} Y{f3(y)}{feed}")
        else:
            _, x, y, cx, cy, ccw = mv
            r = math.dist((cx, cy), (x, y))
            a0 = math.atan2(pos[1] - cy, pos[0] - cx)
            a1 = math.atan2(y - cy, x - cx)
            sweep = (a1 - a0) % (2 * math.pi) if ccw else (a0 - a1) % (2 * math.pi)
            cut_len += sweep * r
            g(f"G{3 if ccw else 2} X{f3(x)} Y{f3(y)} I{f3(cx - pos[0])} J{f3(cy - pos[1])}{feed}")
        pos[0], pos[1] = x, y
        feed = ""

def cut_profile(pts, part):
    tabs = TABS.get(id(part), [])
    if not tabs:
        i0 = start_index(pts, pos[:2])
        ring = pts[i0:] + pts[:i0] + [pts[i0]]
        moves = fit_moves(ring)
        rapid_z(args.safe)
        rapid_xy(*ring[0])
        rapid_z(args.approach)
        for k in range(1, NPASS + 1):
            plunge(-TOP - STEP * k)
            emit_moves(moves, args.feed)
        rapid_z(args.safe)
        return
    pieces = split_at_tabs(pts, tabs)
    k0 = min(range(len(pieces)), key=lambda k: math.dist(pieces[k][0], pos[:2]))
    pieces = pieces[k0:] + pieces[:k0]
    fitted = [fit_moves(pc) for pc in pieces]
    rapid_z(args.safe)
    if args.depth_first:
        g(f"(bridges: {len(tabs)} x {args.tab_width:g} mm, full height - each side cut to full depth back and forth, one lift per bridge)")
        for pc, fwd in zip(pieces, fitted):
            rev = fit_moves(pc[::-1])
            rapid_z(args.safe)            # bridge hops lift to the safe Z: the stack is not flat
            rapid_xy(*pc[0])
            rapid_z(args.approach)
            for k in range(1, NPASS + 1):
                plunge(-TOP - STEP * k)         # at either end the tool is in its own slot
                emit_moves(fwd if k % 2 else rev, args.feed)
        rapid_z(args.safe)
        return
    for k in range(1, NPASS + 1):
        z = -TOP - STEP * k
        if k == 1:
            g(f"(bridges: {len(tabs)} x {args.tab_width:g} mm, full height - tool lifts over each one)")
        for pc, moves in zip(pieces, fitted):
            rapid_z(args.safe)            # bridge hops lift to the safe Z too: the stack is not flat
            rapid_xy(*pc[0])
            rapid_z(args.approach if k == 1 else z + STEP + 0.5)   # pass 1: above the uneven top; then down the slot already cut
            plunge(z)
            emit_moves(moves, args.feed)
    rapid_z(args.safe)

def mill_hole(cx, cy, rp):
    """Hole bigger than the tool: inside contour, helix down CW (conventional) on radius rp, one flat lap at the bottom."""
    global plunge_len
    rapid_z(args.safe)
    rapid_xy(cx + rp, cy)
    rapid_z(args.approach)
    turns = max(1, math.ceil((args.approach + HOLE_DEPTH) / args.helix_pitch - 1e-9))
    dz = (args.approach + HOLE_DEPTH) / turns
    lap = 2 * math.pi * rp
    feed = f" F{args.plunge:.0f}"
    for t in range(1, turns + 1):
        z = args.approach - dz * t
        g(f"G2 X{f3(cx + rp)} Y{f3(cy)} Z{f3(z)} I{f3(-rp)} J0{feed}")
        feed = ""
        plunge_len += math.hypot(lap, dz)
    pos[2] = -HOLE_DEPTH
    g(f"G2 X{f3(cx + rp)} Y{f3(cy)} I{f3(-rp)} J0")
    g(f"G1 X{f3(cx)} Y{f3(cy)}")      # off the wall before lifting
    plunge_len += lap + rp
    pos[0], pos[1] = cx, cy
    rapid_z(args.safe)


def peck_lengths():
    """The --peck lengths as drilled to HOLE_DEPTH (last one repeats, last peck shortened)."""
    out, z = [], 0.0
    while z < HOLE_DEPTH - 1e-9:
        d = min(PECKS[min(len(out), len(PECKS) - 1)], HOLE_DEPTH - z)
        out.append(d)
        z += d
    return out


def drill_hole(cx, cy, r=None):
    """Straight peck drilling at the plunge feed; retract above the stock between pecks.
    Holes more than 0.05 mm wider than the tool are milled as an inside contour instead."""
    if r is not None and r - R > 0.05 / 2:
        return mill_hole(cx, cy, r - R)
    if pos[2] is not None and pos[2] < HOLE_LIFT:
        rapid_z(HOLE_LIFT)        # stays at the hole lift from the last hole of this part; the caller lifts to --safe between parts
    rapid_xy(cx, cy)
    rapid_z(args.approach)
    z, k = 0.0, 0
    while z > -HOLE_DEPTH + 1e-9:
        nz = max(z - PECKS[min(k, len(PECKS) - 1)], -HOLE_DEPTH)
        k += 1
        if z < 0:
            rapid_z(z + 0.5)          # back down to just above the last depth
        plunge(nz)
        z = nz
        if z > -HOLE_DEPTH + 1e-9:
            rapid_z(1.0)              # clear chips
    rapid_z(HOLE_LIFT)

# ---------------------------------------------------------------- order + emit

tab_extra = []
TABS = plan_tabs() if args.ops != "holes" and args.tabs > 0 else {}
total_holes = sum(len(p.holes) for p in parts)
g("%")
OPS = {"all": "holes + outside profiles", "holes": "HOLES ONLY", "profile": "OUTSIDE PROFILES ONLY (holes are in a separate file)"}[args.ops]
g(f"(Plywood hex dome parts - {OPS} - {len(parts)} parts, {total_holes} holes, sheet {sheet[2]-sheet[0]:.0f} x {sheet[3]-sheet[1]:.0f} mm)")
g(f"(Source: {os.path.basename(args.dxf)})")
MAXD = max(DEPTH if args.ops != "holes" else 0, HOLE_DEPTH if args.ops != "profile" else 0)
g(f"(T1 D={args.tool:g} flat end mill - flute length must exceed {MAXD:.1f} mm)")   # before the version: senders take the first tool size they find
if args.rev:
    g(f"(Version: {args.rev}, generated {datetime.date.today()})")
g(f"(Stock: stack {args.stock:g} mm thick = 6 x 5 mm plywood, cut {MAXD - args.stock:g} mm into the spoilboard)")
if args.ops != "holes":
    g(f"(Passes: {NPASS} x {STEP:.3f} mm" + (f" from Z-{TOP:g} to Z-{DEPTH:g} (re-cut of the existing slots)" if TOP else "") + f". Feed {args.feed:.0f}, plunge {args.plunge:.0f} mm/min. " + (f"{args.tabs} full-height bridges per part, {args.tab_width:g} mm wide" + (f", +{len(tab_extra)} holding waste islands" if tab_extra else "") + " - saw them off by hand.)" if args.tabs else "No tabs.)"))
if args.ops != "profile":
    hd = sorted({round(2 * h[2], 2) for q in parts for h in q.holes})
    if hd and hd[-1] - args.tool > 0.05:
        g(f"(Holes: D={'/'.join(f'{d:g}' for d in hd)} mm inside contour, CW helix {args.helix_pitch:g} mm/turn to Z-{HOLE_DEPTH:g} at F{args.plunge:.0f})")
    else:
        how = "one plunge, no pecking" if PECKS[0] >= HOLE_DEPTH else "pecks " + " + ".join(f"{d:g}" for d in peck_lengths()) + " mm"
        g(f"(Holes: D={args.tool:g} mm with the endmill to Z-{HOLE_DEPTH:g}, {how} at F{args.plunge:.0f}" + (f", lift Z{HOLE_LIFT:g} between holes of one part, Z{args.safe:g} between parts" if HOLE_LIFT < args.safe else "") + ")")
g("(ORIGIN: X0 Y0 = lower-left corner of the sheet stack, Z0 = TOP of the stack)")
g("(Keep screws/clamps away from all cut paths.)")
if args.spindle == "vfd":
    g(f"(Spindle: M3 S{args.rpm:.0f} runs the MK300 via Grbl D13 -> X1, M5 stops it; {args.spinup:g} s spin-up dwell)")
else:
    g("(Spindle is MANUAL.)")
g("G21 G90 G17 G94")
if args.spindle == "vfd":
    g("(PAUSE - check the stack is clamped and Z0 is set, then press Resume to start the spindle)")
    g("M0")
    g(f"M3 S{args.rpm:.0f} (spindle ON)")
    g(f"G4 P{args.spinup:g} (wait for the spindle to reach speed)")
else:
    g("(PAUSE - switch the router ON, then press Resume)")
    g("M0")

if args.holes_first and args.ops == "all":
    holes = [h for q in parts for h in q.holes]
    last_part = None
    g(f"(All {len(holes)} holes first, then the {len(parts)} outlines)")
    while holes:
        h = min(holes, key=lambda c: math.dist(pos[:2], c[:2]))
        if pos[2] is not None and pos[2] < args.safe and next((q for q in parts if h in q.holes), None) is not last_part:
            rapid_z(args.safe)
        last_part = next(q for q in parts if h in q.holes)
        drill_hole(*h)
    rapid_z(args.safe)
    args.ops = "profile"

remaining = parts[:]
here = tuple(pos[:2])
n = 0
while remaining:
    part = min(remaining, key=lambda q: q.outline.distance(Polygon([here, (here[0] + 1e-3, here[1]), (here[0], here[1] + 1e-3)])))
    remaining.remove(part)
    n += 1
    minx, miny, maxx, maxy = part.outline.bounds
    g(f"(Part {n}/{len(parts)} - block {part.name}, insert {part.index}, {maxx-minx:.0f} x {maxy-miny:.0f} mm)")
    # holes in nearest-neighbour order
    holes = part.holes[:]
    while holes:
        h = min(holes, key=lambda c: math.dist(pos[:2], c[:2]))
        holes.remove(h)
        if args.ops != "profile":
            drill_hole(*h)
    rapid_z(args.safe)
    if args.ops != "holes":
        cut_profile(profile_path(part), part)
    here = tuple(pos[:2])

rapid_z(args.safe)
if args.spindle == "vfd":
    g("M5 (spindle OFF)")
g("G0 X0 Y0")
if args.spindle == "manual":
    g("(END - switch the router OFF)")
g("M2")
g("%")

with open(args.out, "w") as fh:
    fh.write("\n".join(out) + "\n")

def hms(minutes):
    s = int(minutes * 60)
    return f"{s // 3600}h{(s % 3600) // 60:02d}m"

t_cut = cut_len / args.feed + plunge_len / args.plunge
print(f"wrote {args.out}: {len(out)} lines, {os.path.getsize(args.out)/1024:.0f} KB")
print(f"passes {NPASS} x {STEP:.3f} mm to Z-{DEPTH}; cut {cut_len/1000:.1f} m, plunge {plunge_len/1000:.2f} m, rapid {rapid_len/1000:.1f} m")
print(f"time at F{args.feed:.0f}: {hms(t_cut)} (+rapids); at Grbl's current \\$110=500 cap: {hms(cut_len/min(args.feed,500) + plunge_len/args.plunge)}")
print(f"longest line {max(len(l) for l in out)} chars")
