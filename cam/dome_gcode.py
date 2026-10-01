"""Generate Grbl G-code for the plywood hex-dome parts.

Work origin: X0 Y0 = lower-left corner of the sheet stack (the DXF origin),
             Z0    = TOP of the stack.
Strategy per part (nearest-neighbour order to cut travel):
  1. holes, peck-drilled with the endmill (hole = tool diameter)
  2. outside profile, offset by the tool radius, conventional milling (CCW),
     full depth in equal step-downs, no tabs.
"""
import argparse
import math
import os

from shapely.geometry import Polygon
from shapely.geometry.polygon import orient

import dxf_parts

p = argparse.ArgumentParser()
p.add_argument("dxf")
p.add_argument("-o", "--out", required=True)
p.add_argument("--tool", type=float, default=8.0, help="endmill diameter mm")
p.add_argument("--stock", type=float, default=30.0, help="stack thickness mm (6 x 5 mm)")
p.add_argument("--through", type=float, default=0.5, help="extra depth into the spoilboard mm")
p.add_argument("--step", type=float, default=4.0, help="max depth per pass mm")
p.add_argument("--feed", type=float, default=800, help="cutting feed mm/min")
p.add_argument("--plunge", type=float, default=500, help="plunge feed mm/min (Grbl caps it at $112)")
p.add_argument("--peck", type=float, default=6.0, help="retract to clear chips every N mm of hole depth")
p.add_argument("--safe", type=float, default=5.0, help="safe Z above the stack mm")
args = p.parse_args()

R = args.tool / 2
DEPTH = args.stock + args.through
NPASS = math.ceil(DEPTH / args.step - 1e-9)
STEP = DEPTH / NPASS

sheet, parts, problems, _ = dxf_parts.load(args.dxf)
if problems:
    raise SystemExit("DXF problems: " + "; ".join(problems))

out = []
def g(s): out.append(s)
def f3(v): return f"{v:.3f}".rstrip("0").rstrip(".") if abs(v) >= 0.0005 else "0"

cut_len = rapid_len = plunge_len = 0.0
pos = [0.0, 0.0, args.safe]

def rapid_xy(x, y):
    global rapid_len
    rapid_len += math.dist(pos[:2], (x, y))
    g(f"G0 X{f3(x)} Y{f3(y)}")
    pos[0], pos[1] = x, y

def rapid_z(z):
    global rapid_len
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

def cut_profile(pts):
    global cut_len
    i0 = start_index(pts, pos[:2])
    ring = pts[i0:] + pts[:i0] + [pts[i0]]
    moves = fit_moves(ring)
    rapid_z(args.safe)
    rapid_xy(*ring[0])
    rapid_z(1.0)
    for k in range(1, NPASS + 1):
        z = -STEP * k
        plunge(z)
        feed = f" F{args.feed:.0f}"
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
    rapid_z(args.safe)

def drill_hole(cx, cy):
    """Straight peck drilling at the plunge feed; retract above the stock between pecks."""
    rapid_z(args.safe)
    rapid_xy(cx, cy)
    rapid_z(1.0)
    z = 0.0
    while z > -DEPTH + 1e-9:
        nz = max(z - args.peck, -DEPTH)
        if z < 0:
            rapid_z(z + 0.5)          # back down to just above the last depth
        plunge(nz)
        z = nz
        if z > -DEPTH + 1e-9:
            rapid_z(1.0)              # clear chips
    rapid_z(args.safe)

# ---------------------------------------------------------------- order + emit

total_holes = sum(len(p.holes) for p in parts)
g("%")
g(f"(Plywood hex dome parts - {len(parts)} parts, {total_holes} holes, sheet {sheet[2]-sheet[0]:.0f} x {sheet[3]-sheet[1]:.0f} mm)")
g(f"(Source: {os.path.basename(args.dxf)})")
g(f"(T1 D={args.tool:g} flat end mill - flute length must exceed {DEPTH:.1f} mm)")
g(f"(Stock: stack {args.stock:g} mm thick = 6 x 5 mm plywood, cut {args.through:g} mm into the spoilboard)")
g(f"(Passes: {NPASS} x {STEP:.3f} mm. Feed {args.feed:.0f}, plunge {args.plunge:.0f} mm/min. No tabs.)")
g(f"(Holes: peck-drilled D={args.tool:g} mm with the endmill, {args.peck:g} mm pecks at F{args.plunge:.0f})")
g("(ORIGIN: X0 Y0 = lower-left corner of the sheet stack, Z0 = TOP of the stack)")
g("(Keep screws/clamps away from all cut paths. Spindle is MANUAL.)")
g("G21 G90 G17 G94")
g(f"G0 Z{f3(args.safe)}")
g("(PAUSE - switch the router ON, then press Resume)")
g("M0")

remaining = parts[:]
here = (0.0, 0.0)
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
        drill_hole(h[0], h[1])
    cut_profile(profile_path(part))
    here = tuple(pos[:2])

g(f"G0 Z{f3(args.safe)}")
g("G0 X0 Y0")
g("(PAUSE - switch the router OFF)")
g("M0")
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
