"""Independent check of the generated G-code against the DXF part geometry."""
import math, re, sys
from shapely.geometry import LineString, Point, Polygon
from shapely.ops import unary_union
import dxf_parts

dxf, nc, R = sys.argv[1], sys.argv[2], float(sys.argv[3]) / 2
sheet, parts, _, _ = dxf_parts.load(dxf)

def arc_pts(x0, y0, x1, y1, cx, cy, ccw):
    r = math.dist((cx, cy), (x0, y0))
    a0 = math.atan2(y0 - cy, x0 - cx); a1 = math.atan2(y1 - cy, x1 - cx)
    sw = (a1 - a0) % (2 * math.pi) if ccw else -((a0 - a1) % (2 * math.pi))
    if abs(sw) < 1e-9: sw = 2 * math.pi if ccw else -2 * math.pi
    n = max(2, int(abs(sw) * r / 0.2))
    return [(cx + r * math.cos(a0 + sw * k / n), cy + r * math.sin(a0 + sw * k / n)) for k in range(1, n + 1)], r, math.dist((cx, cy), (x1, y1))

x = y = 0.0; z = 5.0; mode = 0; paths = []; cur = []; holes = set(); rerr = 0.0; nlines = 0
for ln in open(nc):
    s = re.sub(r"\(.*?\)", "", ln).strip()
    if not s or s == "%": continue
    nlines += 1
    w = {m[0]: float(m[1]) for m in re.findall(r"([A-Z])([-\d.]+)", s)}
    if "G" in w and int(w["G"]) in (0, 1, 2, 3): mode = int(w["G"])
    nx, ny, nz = w.get("X", x), w.get("Y", y), w.get("Z", z)
    seg = []
    if mode == 1 and (nx, ny) != (x, y): seg = [(nx, ny)]
    elif mode in (2, 3) and "I" in w:
        cx, cy = x + w["I"], y + w.get("J", 0)
        seg, r0, r1 = arc_pts(x, y, nx, ny, cx, cy, mode == 3)
        rerr = max(rerr, abs(r0 - r1))
        if (nx, ny) == (x, y): holes.add((round(cx, 2), round(cy, 2)))
    if nz != z and len(cur) > 1:       # each depth change starts a new lap
        paths.append(cur); cur = []
    if seg and nz < 0 and min(z, nz) < -1:
        if not cur: cur = [(x, y)]
        cur += seg
    elif mode == 0 or nz >= 0:
        if len(cur) > 1: paths.append(cur)
        cur = []
    x, y, z = nx, ny, nz
if len(cur) > 1: paths.append(cur)

profiles = [p for p in paths if len(p) > 20 and math.dist(p[0], p[-1]) < 0.01]
allparts = unary_union([p.outline for p in parts])
worst_gouge = max(LineString(p).buffer(R, quad_segs=16).intersection(allparts).area for p in profiles)
dev = max(p.outline.exterior.hausdorff_distance(Polygon(q).buffer(-R, quad_segs=16).exterior)
          for p in parts for q in profiles if Polygon(q).contains(p.outline))
enclosed = sum(1 for p in parts if any(Polygon(q).contains(p.outline) for q in profiles))
xs = [c[0] for p in paths for c in p]; ys = [c[1] for p in paths for c in p]
print(f"{nlines} G-code lines; {len(profiles)} profile laps; {len(holes)} hole centres; arc radius mismatch max {rerr:.4f} mm (Grbl limit 0.005)")
print(f"parts enclosed: {enclosed}/{len(parts)}; cutter/part overlap max {worst_gouge:.3f} mm2; profile vs ideal offset max deviation {dev:.3f} mm")
print(f"tool edge X {min(xs)-R:.2f}..{max(xs)+R:.2f} Y {min(ys)-R:.2f}..{max(ys)+R:.2f} (sheet {sheet[2]:.1f} x {sheet[3]:.1f})")

# deepest intrusion of the cutter edge into any part (positive = cuts into the part)
worst = 0.0
for q in profiles:
    line = LineString(q)
    for p in parts:
        d = line.distance(p.outline)
        if d < R + 1: worst = max(worst, R - d)
print(f"deepest cutter intrusion into any part: {worst:.3f} mm")
from shapely.ops import nearest_points
for qi, q in enumerate(profiles):
    line = LineString(q)
    for p in parts:
        d = line.distance(p.outline)
        if R - d > 0.03:
            a, b = nearest_points(line, p.outline)
            own = Polygon(q).contains(p.outline)
            print(f"lap {qi} vs insert {p.index} ({'own part' if own else 'NEIGHBOUR'}): intrudes {R-d:.3f} mm at X{a.x:.2f} Y{a.y:.2f}")
