"""Re-nest the plywood hex-dome DXF for a smaller endmill.

Each part is a block inserted at (0, 0) in world coordinates, so moving a part
means setting its INSERT point. Parts are slid down and left (translation only,
order kept) until every gap is exactly --gap mm, spread apart along one axis
so the margins come out equal on all four sides, then the bounding box of all
parts is centred on the --sheet area. Hole circles are set to --hole mm.
Writes a "sheet" rectangle (read by dxf_parts.load) and a "bbox" rectangle.
"""
import argparse
import itertools

import ezdxf
from shapely.affinity import translate

import dxf_parts

p = argparse.ArgumentParser()
p.add_argument("dxf")
p.add_argument("-o", "--out", required=True)
p.add_argument("--gap", type=float, default=7.0, help="min distance between part outlines mm")
p.add_argument("--hole", type=float, default=7.0, help="hole diameter mm")
p.add_argument("--sheet", type=float, nargs=2, default=(1220.0, 2440.0), metavar=("W", "H"), help="plywood size mm")
p.add_argument("--rounds", type=int, default=30, help="max down/left compaction rounds")
args = p.parse_args()

_, parts, problems, k = dxf_parts.load(args.dxf)
if problems:
    raise SystemExit("DXF problems: " + "; ".join(problems))
if k != 1.0:
    raise SystemExit("expected a mm drawing")

P = [q.outline for q in parts]
off = [[0.0, 0.0] for _ in P]


def clear(i, poly):
    if poly.bounds[0] < 0 or poly.bounds[1] < 0:
        return False
    return all(poly.distance(P[j]) >= args.gap - 1e-6 for j in range(len(P)) if j != i)


def slide(i, dx, dy):
    """Move part i by the largest fraction of (dx, dy) that keeps it clear."""
    lo, hi = 0.0, 1.0
    if clear(i, translate(P[i], dx, dy)):
        lo = 1.0
    else:
        for _ in range(30):
            m = (lo + hi) / 2
            if clear(i, translate(P[i], dx * m, dy * m)):
                lo = m
            else:
                hi = m
    P[i] = translate(P[i], dx * lo, dy * lo)
    off[i][0] += dx * lo
    off[i][1] += dy * lo
    return abs(dx * lo) + abs(dy * lo)


# start from the lower-left corner, then tighten the gaps
x0 = min(q.bounds[0] for q in P)
y0 = min(q.bounds[1] for q in P)
for i in range(len(P)):
    P[i] = translate(P[i], -x0, -y0)
    off[i] = [-x0, -y0]
for r in range(args.rounds):
    moved = 0.0
    for i in sorted(range(len(P)), key=lambda i: P[i].bounds[1]):
        moved += slide(i, 0, -P[i].bounds[1])
    for i in sorted(range(len(P)), key=lambda i: P[i].bounds[0]):
        moved += slide(i, -P[i].bounds[0], 0)
    if moved < 0.01:
        break

def bounds():
    return (min(q.bounds[0] for q in P), min(q.bounds[1] for q in P),
            max(q.bounds[2] for q in P), max(q.bounds[3] for q in P))


W, H = args.sheet
bx0, by0, bx1, by1 = bounds()
# equal margins: spread the parts apart along the axis that has the larger margin.
# Each part moves by its share of the extra length (by its position along the axis),
# far end first; slide() stops a part early rather than close a gap below --gap.
ax = 1 if (H - (by1 - by0)) > (W - (bx1 - bx0)) else 0
extra = (H - W) - ((by1 - by0) - (bx1 - bx0)) if ax else (W - H) - ((bx1 - bx0) - (by1 - by0))
lo0 = min(q.bounds[ax] for q in P)
hi0 = max(q.bounds[2 + ax] for q in P)
for i in sorted(range(len(P)), key=lambda i: -P[i].bounds[2 + ax]):
    d = extra * (P[i].bounds[ax] - lo0) / (hi0 - P[i].bounds[2 + ax] + P[i].bounds[ax] - lo0)
    slide(i, *((0, d) if ax else (d, 0)))
bx0, by0, bx1, by1 = bounds()
if bx1 - bx0 > W or by1 - by0 > H:
    raise SystemExit(f"parts need {bx1-bx0:.1f} x {by1-by0:.1f} mm, more than the {W:g} x {H:g} sheet")
cx = (W - (bx1 + bx0)) / 2
cy = (H - (by1 + by0)) / 2

doc = ezdxf.readfile(args.dxf)
msp = doc.modelspace()
inserts = list(msp.query("INSERT"))
for q, (dx, dy) in zip(parts, off):
    ins = inserts[q.index]
    x, y, _ = ins.dxf.insert
    ins.dxf.insert = (x + dx + cx, y + dy + cy, 0)
    for e in doc.blocks[ins.dxf.name].query("CIRCLE"):
        e.dxf.radius = args.hole / 2

for e in list(msp.query("LINE")):
    if e.dxf.layer.lower() in ("sheet", "bbox"):
        msp.delete_entity(e)


def rect(x0, y0, x1, y1, layer):
    c = [(x0, y0), (x1, y0), (x1, y1), (x0, y1)]
    for a, b in zip(c, c[1:] + c[:1]):
        msp.add_line(a, b, dxfattribs={"layer": layer})


for name, colour in (("sheet", 7), ("bbox", 1)):
    if name not in doc.layers:
        doc.layers.add(name, color=colour)
rect(0, 0, W, H, "sheet")
rect(bx0 + cx, by0 + cy, bx1 + cx, by1 + cy, "bbox")
doc.saveas(args.out)

# check the result as the CAM script will read it
_, parts2, _, _ = dxf_parts.load(args.out)
gap = min(a.outline.distance(b.outline) for a, b in itertools.combinations(parts2, 2))
print(f"wrote {args.out}: {len(parts2)} parts, min gap {gap:.2f} mm, holes D{args.hole:g}")
print(f"bounding box {bx1-bx0:.1f} x {by1-by0:.1f} mm at X{bx0+cx:.1f}..{bx1+cx:.1f} Y{by0+cy:.1f}..{by1+cy:.1f} on {W:g} x {H:g}")
