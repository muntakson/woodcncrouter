"""Load the nested dome-part DXF into shapely polygons (mm).

Each INSERT is exploded into world-space LINE/ARC/CIRCLE entities; lines and arcs
are chained into closed outlines, circles become holes.
"""
import math
from dataclasses import dataclass, field

import ezdxf
from shapely.geometry import Polygon, Point

INCH = 25.4
UNIT_SCALE = {1: INCH, 4: 1.0, 5: 10.0, 6: 1000.0}   # $INSUNITS -> mm


@dataclass
class Part:
    name: str                     # block name
    index: int                    # insert number on the sheet
    outline: Polygon              # outer boundary (mm)
    holes: list = field(default_factory=list)   # (cx, cy, r) in mm


def _arc_points(cx, cy, r, a0, a1, tol):
    """CCW arc from a0 to a1 (degrees), chord tolerance tol (drawing units)."""
    a0, a1 = math.radians(a0), math.radians(a1)
    while a1 <= a0:
        a1 += 2 * math.pi
    sweep = a1 - a0
    step = 2 * math.acos(max(-1.0, 1 - tol / r)) if r > tol else math.pi / 8
    n = max(2, int(math.ceil(sweep / step)))
    return [(cx + r * math.cos(a0 + sweep * i / n), cy + r * math.sin(a0 + sweep * i / n)) for i in range(n + 1)]


def _chain(segments, tol=1e-4):
    """Join point-list segments end to end into closed loops."""
    segs = [list(s) for s in segments]
    loops = []
    while segs:
        loop = segs.pop(0)
        changed = True
        while changed and math.dist(loop[0], loop[-1]) > tol:
            changed = False
            for i, s in enumerate(segs):
                if math.dist(loop[-1], s[0]) <= tol:
                    loop += s[1:]
                elif math.dist(loop[-1], s[-1]) <= tol:
                    loop += s[::-1][1:]
                elif math.dist(loop[0], s[-1]) <= tol:
                    loop = s[:-1] + loop
                elif math.dist(loop[0], s[0]) <= tol:
                    loop = s[::-1][:-1] + loop
                else:
                    continue
                segs.pop(i)
                changed = True
                break
        loops.append((loop, math.dist(loop[0], loop[-1]) <= tol))
    return loops


def load(path):
    doc = ezdxf.readfile(path)
    msp = doc.modelspace()
    k = UNIT_SCALE.get(doc.header.get("$INSUNITS", 4), 1.0)
    tol = 0.01 / k          # 0.01 mm chord deviation, in drawing units

    sheet = None
    lines = [e for e in msp.query("LINE") if e.dxf.layer.lower() == "sheet"]
    if lines:
        xs = [v for e in lines for v in (e.dxf.start.x, e.dxf.end.x)]
        ys = [v for e in lines for v in (e.dxf.start.y, e.dxf.end.y)]
        sheet = (min(xs) * k, min(ys) * k, max(xs) * k, max(ys) * k)

    parts, problems = [], []
    for idx, ins in enumerate(msp.query("INSERT")):
        segs, holes = [], []
        for e in ins.virtual_entities():
            t = e.dxftype()
            if t == "LINE":
                segs.append([(e.dxf.start.x, e.dxf.start.y), (e.dxf.end.x, e.dxf.end.y)])
            elif t == "ARC":
                c = e.dxf.center
                segs.append(_arc_points(c.x, c.y, e.dxf.radius, e.dxf.start_angle, e.dxf.end_angle, tol))
            elif t == "CIRCLE":
                c = e.dxf.center
                holes.append((c.x * k, c.y * k, e.dxf.radius * k))
        loops = _chain(segs)
        closed = [Polygon([(x * k, y * k) for x, y in lp]) for lp, ok in loops if ok]
        open_ = [lp for lp, ok in loops if not ok]
        if open_ or not closed:
            problems.append(f"insert #{idx} block {ins.dxf.name}: {len(open_)} open chain(s)")
        if not closed:
            continue
        closed.sort(key=lambda p: p.area, reverse=True)
        outline = closed[0].buffer(0)
        # extra closed loops inside the outline are internal cut-outs: treat as holes in the polygon
        inner = [p for p in closed[1:] if outline.contains(p)]
        if inner:
            outline = Polygon(outline.exterior.coords, [p.exterior.coords for p in inner])
        parts.append(Part(ins.dxf.name, idx, outline, holes))
    return sheet, parts, problems, k
