"""Synthetic DXF test set for the backoffice CAM (rules preprocessor + Claude planner).

  test_R*.dxf  the rule-based preprocessor should plan these alone (no AI call)
  test_A*.dxf  these need Claude: each carries one thing fixed rules cannot settle

The expectation is in the name, so backoffice/check_cam_samples.js can check the verdicts.
Every case assumes a 6 mm end mill in 18 mm plywood (the check script's defaults).

    cd cam && .venv/bin/python make_test_dxfs.py [out_dir]   # default ../backoffice/dxf
"""
import math
import os
import sys

import ezdxf
from ezdxf import units
from ezdxf.math import global_bspline_interpolation

OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), "..", "backoffice", "dxf")
os.makedirs(OUT, exist_ok=True)


def new(unit=units.MM):
    doc = ezdxf.new("R2010", setup=True)
    doc.units = unit
    return doc, doc.modelspace()


def save(doc, name, binary=False):
    path = os.path.join(OUT, name)
    doc.saveas(path, fmt="bin" if binary else "asc")
    print("wrote", path)


def rounded_rect(msp, x0, y0, x1, y1, r, layer="0"):
    """Closed LWPOLYLINE with bulge-arc corners."""
    b = math.tan(math.pi / 8)          # 90° arc
    pts = [(x0 + r, y0, 0), (x1 - r, y0, b), (x1, y0 + r, 0), (x1, y1 - r, b),
           (x1 - r, y1, 0), (x0 + r, y1, b), (x0, y1 - r, 0), (x0, y0 + r, b)]
    msp.add_lwpolyline(pts, format="xyb", close=True, dxfattribs={"layer": layer})


def rect(msp, x0, y0, x1, y1, layer="0"):
    msp.add_lwpolyline([(x0, y0), (x1, y0), (x1, y1), (x0, y1)], close=True, dxfattribs={"layer": layer})


def slot(msp, cx, cy, length, width, layer="0"):
    """Stadium slot from separate LINE and ARC entities (tests end-to-end chaining)."""
    r, h = width / 2, length / 2 - width / 2
    a = {"layer": layer}
    msp.add_line((cx - h, cy - r), (cx + h, cy - r), dxfattribs=a)
    msp.add_arc((cx + h, cy), r, -90, 90, dxfattribs=a)
    msp.add_line((cx + h, cy + r), (cx - h, cy + r), dxfattribs=a)
    msp.add_arc((cx - h, cy), r, 90, 270, dxfattribs=a)


def gear_lines(msp, cx, cy, teeth, r_root, r_tip, layer="0"):
    """Gear-like outline drawn as many LINE entities."""
    pts = []
    for i in range(teeth):
        a = 2 * math.pi * i / teeth
        w = 2 * math.pi / teeth
        for frac, r in ((0.0, r_root), (0.15, r_tip), (0.45, r_tip), (0.6, r_root)):
            pts.append((cx + r * math.cos(a + frac * w), cy + r * math.sin(a + frac * w)))
    for p, q in zip(pts, pts[1:] + pts[:1]):
        msp.add_line(p, q, dxfattribs={"layer": layer})


def closed_spline(msp, pts, layer="0"):
    curve = global_bspline_interpolation(pts + [pts[0]], degree=3)
    s = msp.add_spline(dxfattribs={"layer": layer})
    s.apply_construction_tool(curve)


# ------------------------------------------------------------------ rules can plan these

def r01_plate():
    """Rounded plate (bulges): 4 Ø6 corner holes to drill, a Ø30 hole and a slot to cut inside."""
    doc, msp = new()
    rounded_rect(msp, 0, 0, 160, 100, 10)
    for x, y in ((12, 12), (148, 12), (148, 88), (12, 88)):
        msp.add_circle((x, y), 3)
    msp.add_circle((50, 50), 15)
    slot(msp, 110, 50, 50, 12)
    save(doc, "test_R01_plate_holes_slot.dxf")


def r02_nest_inches():
    """Binary DXF in inches: sheet border (layer SHEET) + 6 rotated / mirrored block inserts of a bracket,
    nesting report text and a stats box, like the dome file's nesting software output."""
    doc, msp = new(units.IN)
    blk = doc.blocks.new("BRACKET")
    # L-shaped bracket 6 x 4 in with a 0.5 in fillet, a Ø0.25 hole and a 1.5 x 0.5 slot
    blk.add_lwpolyline([(0, 0, 0), (6, 0, 0), (6, 1.5, 0), (2, 1.5, -math.tan(math.pi / 8)), (1.5, 2, 0), (1.5, 4, 0), (0, 4, 0)],
                       format="xyb", close=True)
    blk.add_circle((0.75, 3.25), 0.125)
    blk.add_circle((5.25, 0.75), 0.125)
    slot(blk, 3.5, 0.75, 1.5, 0.5)
    rect(msp, 0, 0, 48, 24, layer="SHEET")
    # insertion point, rotation, x scale -> footprint in inches (none overlap, gaps >= 1 in)
    for x, y, rot, xs in [(1, 1, 0, 1),        # [1, 7]   x [1, 5]
                          (15, 1, 0, -1),      # [9, 15]  x [1, 5]   mirrored: grows to the left
                          (22, 1, 90, 1),      # [18, 22] x [1, 7]
                          (31, 9, 180, 1),     # [25, 31] x [5, 9]
                          (33, 1, 0, 1),       # [33, 39] x [1, 5]
                          (41, 12, 270, -1)]:  # [41, 45] x [12, 18] mirrored + rotated
        msp.add_blockref("BRACKET", (x, y), dxfattribs={"rotation": rot, "xscale": xs})
    rect(msp, 0, -3, 10, -0.5, layer="Stats")
    msp.add_text("Sheet 1 (48.00 x 24.00)", height=0.4, dxfattribs={"layer": "Stats"}).set_placement((0.2, -1.2))
    msp.add_text("Parts Nested: 6", height=0.4, dxfattribs={"layer": "Stats"}).set_placement((0.2, -2.0))
    save(doc, "test_R02_nested_brackets_inch_binary.dxf", binary=True)


def r03_sign():
    """Sign: closed SPLINE outline, a lettering line on layer ENGRAVE, two hanging holes."""
    doc, msp = new()
    pts = [(100 + 90 * math.cos(t) * (1 + 0.12 * math.cos(3 * t)), 60 + 50 * math.sin(t)) for t in [i * 2 * math.pi / 14 for i in range(14)]]
    closed_spline(msp, pts)
    msp.add_circle((40, 60), 3)
    msp.add_circle((160, 60), 3)
    msp.add_lwpolyline([(70, 50), (80, 75), (90, 50), (100, 75), (110, 50), (120, 75), (130, 50)], dxfattribs={"layer": "ENGRAVE"})
    save(doc, "test_R03_spline_sign_engrave.dxf")


def r04_parts():
    """Loose parts, no sheet: a gear of 96 LINEs with a centre hole, an ELLIPSE plate, a disc."""
    doc, msp = new()
    gear_lines(msp, 60, 60, 24, 45, 55)
    msp.add_circle((60, 60), 10)
    msp.add_ellipse((200, 60), major_axis=(60, 0), ratio=0.6)
    msp.add_circle((200, 60), 3)
    msp.add_circle((320, 60), 40)
    save(doc, "test_R04_gear_ellipse_disc.dxf")


# ------------------------------------------------------------------ these need Claude

def a01_centerlines():
    """Plate with centre lines and a dimension-like line on layer 0: open lines (engrave? cut? construction?)."""
    doc, msp = new()
    rect(msp, 0, 0, 120, 80)
    msp.add_circle((60, 40), 20)
    msp.add_line((30, 40), (90, 40))
    msp.add_line((60, 10), (60, 70))
    msp.add_line((0, -10), (120, -10))
    msp.add_text("120", height=4).set_placement((56, -16))
    save(doc, "test_A01_open_centerlines.dxf")


def a02_op_layers():
    """Layer names carry the operations: POCKET_5mm, PROFILE_THRU, DRILL_6."""
    doc, msp = new()
    rect(msp, 0, 0, 200, 120, layer="PROFILE_THRU")
    rect(msp, 20, 20, 90, 100, layer="POCKET_5mm")
    for y in (30, 60, 90):
        msp.add_circle((150, y), 3, dxfattribs={"layer": "DRILL_6"})
    save(doc, "test_A02_operation_layers.dxf")


def a03_islands():
    """Picture frame with a window, and a ring (logo) floating inside the window: island or separate part?"""
    doc, msp = new()
    rounded_rect(msp, 0, 0, 200, 160, 8)
    rect(msp, 30, 30, 170, 130)
    msp.add_circle((100, 80), 30)
    msp.add_circle((100, 80), 20)
    save(doc, "test_A03_islands_in_window.dxf")


def a04_notes():
    """The drawing says how to cut it in plain words."""
    doc, msp = new()
    rect(msp, 0, 0, 150, 100)
    rect(msp, 30, 30, 120, 70)
    msp.add_circle((15, 15), 3)
    msp.add_text("POCKET 6 mm DEEP - do not cut through", height=4).set_placement((0, -10))
    save(doc, "test_A04_machining_notes.dxf")


def a05_sheet_or_part():
    """A rectangle around three rectangles, no sheet layer and no nesting text: stock, or a part with cut-outs?"""
    doc, msp = new()
    rect(msp, 0, 0, 300, 200)
    rect(msp, 20, 20, 120, 180)
    rect(msp, 140, 20, 280, 90)
    rect(msp, 140, 110, 280, 180)
    save(doc, "test_A05_sheet_or_part.dxf")


def a06_tiny_holes():
    """Pilot holes Ø1.5 for screws: far smaller than a 6 mm end mill."""
    doc, msp = new()
    rounded_rect(msp, 0, 0, 100, 60, 5)
    for x in (15, 50, 85):
        msp.add_circle((x, 30), 0.75)
    save(doc, "test_A06_tiny_pilot_holes.dxf")


def a07_duplicates():
    """Every outline exported twice on top of itself (a common CAD export fault)."""
    doc, msp = new()
    for _ in range(2):
        rect(msp, 0, 0, 120, 60)
        msp.add_circle((60, 30), 12)
    save(doc, "test_A07_duplicate_outlines.dxf")


def a08_outside_sheet():
    """Sheet border on layer SHEET, but one part lies off the sheet."""
    doc, msp = new()
    rect(msp, 0, 0, 600, 400, layer="SHEET")
    rounded_rect(msp, 20, 20, 220, 160, 10)
    rounded_rect(msp, 650, 20, 850, 160, 10)
    save(doc, "test_A08_part_outside_sheet.dxf")


def a10_overlap():
    """Two part outlines overlap: which one is right?"""
    doc, msp = new()
    rounded_rect(msp, 0, 0, 120, 80, 6)
    rounded_rect(msp, 100, 20, 220, 100, 6)
    save(doc, "test_A10_overlapping_parts.dxf")


def a09_narrow_slot():
    """A 3 mm wide slot: a 6 mm tool cannot cut inside it."""
    doc, msp = new()
    rect(msp, 0, 0, 100, 50)
    slot(msp, 50, 25, 60, 3)
    save(doc, "test_A09_narrow_slot.dxf")


for f in (r01_plate, r02_nest_inches, r03_sign, r04_parts,
          a01_centerlines, a02_op_layers, a03_islands, a04_notes, a05_sheet_or_part,
          a06_tiny_holes, a07_duplicates, a08_outside_sheet, a09_narrow_slot, a10_overlap):
    f()
