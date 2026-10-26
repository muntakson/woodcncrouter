package org.dnaboy.grblsender;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Safety check page: reads the open program without running it and checks it against the machine
 * (depth, feeds, spindle, codes, G54, homing, travel), the plywood sheet and the clamps marked on it,
 * plus a checklist of things only the operator can confirm. Red findings and unticked items hold Run.
 */
final class SafetyCheck {

    /** Plywood sheet on the bed: the 2450 mm side runs along Y (X travel is only 1950 mm), corner at WCS X0 Y0. */
    static final float SHEET_X = 1250, SHEET_Y = 2450;
    private static final float MARGIN_WARN = 20;     // mm from the cut edge to a sheet edge
    private static final float CLAMP_R = 25;         // keep-out radius around a marked clamp/screw, mm
    private static final float SPOIL_WARN = 2, SPOIL_ERR = 5;

    interface Host {
        String text();
        String fileName();
        boolean fluidnc();
        boolean connected();
        String machineState();
        boolean streaming();
        Map<String, String> grblSettings();
        float[] wco();
        /** Axes homed since connecting, e.g. "XYZ". */
        String homedAxes();
        long connectedAt();
        SharedPreferences prefs();
        void capture(String cmd, SetupPages.Lines done);
        /** Saves text as Documents/GrblSender/name and offers to open it. */
        void saveCopy(String name, String text, String note);
        /** Text of Documents/GrblSender/name, or null if there is no such file. */
        String readLocal(String name);
    }

    // ---------------------------------------------------------------- analysis

    static final class Report {
        boolean hasMoves;
        float minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        int minZLine = -1;
        /** All moves (rapids too), work coordinates. */
        float allMinX = Float.MAX_VALUE, allMinY = Float.MAX_VALUE, allMaxX = -Float.MAX_VALUE, allMaxY = -Float.MAX_VALUE;
        /** Tool centre of feed moves below Z0 (in the stock). */
        float cutMinX = Float.MAX_VALUE, cutMinY = Float.MAX_VALUE, cutMaxX = -Float.MAX_VALUE, cutMaxY = -Float.MAX_VALUE;
        double maxFeedXY, maxFeedZ;
        int maxFeedXYLine = -1, maxFeedZLine = -1;
        int rapidCount, rapidWithF;
        int rapidIntoStock, rapidIntoStockLine = -1;     // G0 below Z0 deeper than anything cut so far
        int rapidIntoSlot, rapidIntoSlotLine = -1;       // G0 below Z0 back into depth already cut
        float rapidMinZ = Float.MAX_VALUE;
        boolean relative, inches;
        int relativeLine = -1, inchesLine = -1;
        /** First move lowers Z while X/Y are still where the program started (e.g. at home). */
        boolean zFirst;
        int zFirstLine = -1;
        float toolDiameter;           // 0 when the file does not say
        float stockFromFile;          // "Stock: stack 30 mm" in a comment, 0 if absent
        // spindle
        int firstCutLine = -1;
        boolean spindleAtFirstCut, dwellBeforeFirstCut;
        int m3Line = -1, m4Line = -1;
        int cutsSpindleOff, cutsSpindleOffLine = -1;
        // codes the controller may reject
        final List<String> unknownCodes = new ArrayList<>();
        int unknownLine = -1;
        int longLines, longLine = -1, longestLen;
        float[] cutSegs = new float[0];
        int cutSegCount;
        /** Rapids as x0,y0,x1,y1,z (lower Z of the two ends). */
        float[] rapidSegs = new float[0];
        int rapidSegCount;
        boolean hasCut() { return cutMinX <= cutMaxX; }
    }

    private static final Pattern WORD = Pattern.compile("([A-Z])\\s*([-+]?(?:\\d+\\.?\\d*|\\.\\d+))");
    private static final Pattern STOCK = Pattern.compile("(?i)\\bstock\\b[^0-9)]*?(\\d+(?:\\.\\d+)?)\\s*mm");
    private static final int MAX_SEGS = 60000;
    /** G-codes Grbl 1.1 / FluidNC accept (value × 10). */
    private static final int[] G_OK = {0, 10, 20, 30, 40, 100, 170, 180, 190, 200, 210, 280, 281, 300, 301, 382, 383, 384, 385,
            400, 431, 490, 530, 540, 550, 560, 570, 580, 590, 610, 800, 900, 910, 911, 920, 921, 930, 940};
    private static final int[] M_OK = {0, 1, 2, 3, 4, 5, 7, 8, 9, 30, 56, 62, 63, 64, 65};

    static Report analyze(String text, int maxLineLen) {
        Report r = new Report();
        float[] segs = new float[4096], rsegs = new float[4096];
        int nSeg = 0, nR = 0;
        double x = 0, y = 0, z = Double.NaN;          // start Z unknown (wherever the machine is)
        boolean xyMoved = false;
        double cutDepth = 0;                           // deepest Z reached by a feed move so far
        int motion = 0;
        boolean absolute = true;
        double unit = 1, feed = 0;
        int plane = 17;
        boolean spindle = false, dwellSinceM3 = false;
        StringBuilder comments = new StringBuilder();
        String[] rows = text.split("\n", -1);
        for (int ln = 0; ln < rows.length; ln++) {
            String code = stripComments(rows[ln], comments);
            if (comments.length() > 0) {
                String cm = comments.toString();
                if (r.toolDiameter == 0) r.toolDiameter = Math.max(0, GCodeParser.findToolDiameter(cm));
                if (r.stockFromFile == 0) {
                    Matcher sm = STOCK.matcher(cm);
                    if (sm.find()) try { r.stockFromFile = Float.parseFloat(sm.group(1)); } catch (NumberFormatException ignored) { }
                }
            }
            comments.setLength(0);
            String clean = code.replaceAll("\\s+", "").toUpperCase(Locale.US);
            if (clean.isEmpty() || clean.equals("%")) continue;
            if (clean.length() > maxLineLen) {
                if (r.longLines++ == 0) r.longLine = ln + 1;
                r.longestLen = Math.max(r.longestLen, clean.length());
            }
            if (clean.startsWith("$")) continue;
            Double X = null, Y = null, Z = null, I = null, J = null, R = null;
            boolean nonModal = false, hasF = false;
            double P = 0;
            Matcher m = WORD.matcher(clean);
            while (m.find()) {
                char letter = m.group(1).charAt(0);
                double v;
                try { v = Double.parseDouble(m.group(2)); } catch (NumberFormatException e) { continue; }
                switch (letter) {
                    case 'G': {
                        int g10 = (int) Math.round(v * 10);
                        if (java.util.Arrays.binarySearch(G_OK, g10) < 0) unknown(r, "G" + m.group(2), ln);
                        switch (g10) {
                            case 0: motion = 0; break;
                            case 10: motion = 1; break;
                            case 20: motion = 2; break;
                            case 30: motion = 3; break;
                            case 170: case 180: case 190: plane = g10 / 10; break;
                            case 200: unit = 25.4; if (!r.inches) { r.inches = true; r.inchesLine = ln + 1; } break;
                            case 210: unit = 1; break;
                            case 900: absolute = true; break;
                            case 910: absolute = false; if (!r.relative) { r.relative = true; r.relativeLine = ln + 1; } break;
                            case 40:
                                if (spindle) dwellSinceM3 = true;
                                nonModal = true;
                                break;
                            case 100: case 280: case 300: case 530: case 920: nonModal = true; break;
                            default: break;
                        }
                        break;
                    }
                    case 'M': {
                        int mc = (int) Math.round(v);
                        if (java.util.Arrays.binarySearch(M_OK, mc) < 0) unknown(r, "M" + m.group(2), ln);
                        if (mc == 3 || mc == 4) {
                            if (!spindle) dwellSinceM3 = false;
                            spindle = true;
                            if (mc == 3 && r.m3Line < 0) r.m3Line = ln + 1;
                            if (mc == 4 && r.m4Line < 0) r.m4Line = ln + 1;
                        } else if (mc == 5 || mc == 2 || mc == 30) spindle = false;
                        break;
                    }
                    case 'X': X = v; break;
                    case 'Y': Y = v; break;
                    case 'Z': Z = v; break;
                    case 'I': I = v; break;
                    case 'J': J = v; break;
                    case 'R': R = v; break;
                    case 'P': P = v; break;
                    case 'F': feed = v * unit; hasF = true; break;
                    default: break;
                }
            }
            if (nonModal || (X == null && Y == null && Z == null)) continue;
            double nx = X == null ? x : (absolute ? X * unit : x + X * unit);
            double ny = Y == null ? y : (absolute ? Y * unit : y + Y * unit);
            double nz = Z == null ? z : (absolute || Double.isNaN(z) ? Z * unit : z + Z * unit);
            boolean xy = nx != x || ny != y;
            if (!xyMoved && !xy && Z != null && !r.zFirst && (Double.isNaN(z) || nz < z)) {
                r.zFirst = true;
                r.zFirstLine = ln + 1;
            }
            if (xy) xyMoved = true;
            r.hasMoves = true;
            boolean cutting = motion != 0 && !Double.isNaN(nz) && (nz < 0 || (!Double.isNaN(z) && z < 0));

            if (cutting) {
                if (r.firstCutLine < 0) {
                    r.firstCutLine = ln + 1;
                    r.spindleAtFirstCut = spindle;
                    r.dwellBeforeFirstCut = dwellSinceM3;
                }
                if (!spindle && r.cutsSpindleOff++ == 0) r.cutsSpindleOffLine = ln + 1;
            }
            if (motion == 0) {
                r.rapidCount++;
                if (hasF) r.rapidWithF++;
                if (!Double.isNaN(nz)) {
                    r.rapidMinZ = Math.min(r.rapidMinZ, (float) nz);
                    if (nz < 0 && (Double.isNaN(z) || nz < z)) {
                        if (nz < cutDepth - 1e-6) { if (r.rapidIntoStock++ == 0) r.rapidIntoStockLine = ln + 1; }
                        else if (r.rapidIntoSlot++ == 0) r.rapidIntoSlotLine = ln + 1;
                    }
                }
                if (xy && nR < MAX_SEGS && !Double.isNaN(nz)) {
                    if (nR * 5 + 5 > rsegs.length) rsegs = java.util.Arrays.copyOf(rsegs, rsegs.length * 2);
                    rsegs[nR * 5] = (float) x; rsegs[nR * 5 + 1] = (float) y;
                    rsegs[nR * 5 + 2] = (float) nx; rsegs[nR * 5 + 3] = (float) ny;
                    rsegs[nR * 5 + 4] = (float) (Double.isNaN(z) ? nz : Math.min(z, nz));
                    nR++;
                }
            } else if (xy) {
                if (feed > r.maxFeedXY) { r.maxFeedXY = feed; r.maxFeedXYLine = ln + 1; }
            } else if (feed > r.maxFeedZ) { r.maxFeedZ = feed; r.maxFeedZLine = ln + 1; }

            // XY points along the move (arcs sampled), for the extents and the drawing
            boolean arc = (motion == 2 || motion == 3) && plane == 17 && xy;
            double cx = 0, cy = 0, a0 = 0, sweep = 0;
            int n = 1;
            if (arc) {
                if (I != null || J != null) {
                    cx = x + (I == null ? 0 : I * unit);
                    cy = y + (J == null ? 0 : J * unit);
                } else if (R != null) {
                    double rad = R * unit, dx = nx - x, dy = ny - y, d = Math.hypot(dx, dy);
                    double h = Math.sqrt(Math.max(0, rad * rad - d * d / 4)) * ((motion == 2) == (rad > 0) ? -1 : 1);
                    cx = (x + nx) / 2 - h * dy / d;
                    cy = (y + ny) / 2 + h * dx / d;
                } else arc = false;
            }
            if (arc) {
                a0 = Math.atan2(y - cy, x - cx);
                double a1 = Math.atan2(ny - cy, nx - cx);
                sweep = a1 - a0;
                if (motion == 2) { if (sweep >= 0) sweep -= 2 * Math.PI; } else if (sweep <= 0) sweep += 2 * Math.PI;
                n = Math.max(2, (int) Math.ceil(Math.abs(sweep) / (Math.PI / 24)));
            }
            double rad = arc ? Math.hypot(x - cx, y - cy) : 0;
            double px = x, py = y;
            for (int i = 1; i <= n; i++) {
                double qx, qy;
                if (arc && i < n) {
                    double a = a0 + sweep * i / n;
                    qx = cx + rad * Math.cos(a);
                    qy = cy + rad * Math.sin(a);
                } else { qx = nx; qy = ny; }
                grow(r, (float) qx, (float) qy, false);
                if (cutting) {
                    grow(r, (float) qx, (float) qy, true);
                    if (i == 1) grow(r, (float) px, (float) py, true);
                    if (nSeg < MAX_SEGS && xy) {
                        if (nSeg * 4 + 4 > segs.length) segs = java.util.Arrays.copyOf(segs, segs.length * 2);
                        segs[nSeg * 4] = (float) px; segs[nSeg * 4 + 1] = (float) py;
                        segs[nSeg * 4 + 2] = (float) qx; segs[nSeg * 4 + 3] = (float) qy;
                        nSeg++;
                    }
                }
                px = qx; py = qy;
            }
            if (motion != 0 && !Double.isNaN(nz)) cutDepth = Math.min(cutDepth, nz);
            x = nx; y = ny; z = nz;
            if (!Double.isNaN(z)) {
                if (z < r.minZ) { r.minZ = (float) z; r.minZLine = ln + 1; }
                r.maxZ = Math.max(r.maxZ, (float) z);
            }
        }
        r.cutSegs = segs;
        r.cutSegCount = nSeg;
        r.rapidSegs = rsegs;
        r.rapidSegCount = nR;
        return r;
    }

    private static void unknown(Report r, String code, int ln) {
        if (!r.unknownCodes.contains(code)) r.unknownCodes.add(code);
        if (r.unknownLine < 0) r.unknownLine = ln + 1;
    }

    private static void grow(Report r, float x, float y, boolean cut) {
        if (cut) {
            r.cutMinX = Math.min(r.cutMinX, x); r.cutMaxX = Math.max(r.cutMaxX, x);
            r.cutMinY = Math.min(r.cutMinY, y); r.cutMaxY = Math.max(r.cutMaxY, y);
        } else {
            r.allMinX = Math.min(r.allMinX, x); r.allMaxX = Math.max(r.allMaxX, x);
            r.allMinY = Math.min(r.allMinY, y); r.allMaxY = Math.max(r.allMaxY, y);
        }
    }

    private static String stripComments(String raw, StringBuilder comments) {
        StringBuilder out = new StringBuilder(raw.length());
        int depth = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == ';') { comments.append(raw, i + 1, raw.length()); break; }
            if (c == '(') { depth++; continue; }
            if (c == ')') { if (depth > 0) depth--; continue; }
            if (depth > 0) comments.append(c); else out.append(c);
        }
        return out.toString();
    }

    static float segDist(float px, float py, float x0, float y0, float x1, float y1) {
        float dx = x1 - x0, dy = y1 - y0;
        float l2 = dx * dx + dy * dy;
        float t = l2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - x0) * dx + (py - y0) * dy) / l2));
        return (float) Math.hypot(px - (x0 + t * dx), py - (y0 + t * dy));
    }

    // ---------------------------------------------------------------- checklist (per program, cleared when a run starts)

    static final String[] CHECKS = {
            "Bit in the collet matches the file (Ø%s mm) and the collet nut is tight",
            "Stack screwed down; every screw/clamp is marked on the sheet",
            "Dust shoe and hose clear, nothing loose on the bed",
            "MK300 in remote: LOC/REM LED on",
            "E-stop within reach; eye and ear protection on",
    };
    private static final boolean[] ticks = new boolean[CHECKS.length];
    private static int ticksFor;

    static void clearChecklist() { java.util.Arrays.fill(ticks, false); }

    private static void syncTicks(String text) {
        int h = text.hashCode();
        if (h != ticksFor) { ticksFor = h; clearChecklist(); }
    }

    /** Red findings and unticked checklist items for the open program (the Run gate). */
    static List<String> problems(Context c, Host host) {
        SafetyCheck s = new SafetyCheck(c, host);
        s.headless = true;
        s.build();
        return s.errors;
    }

    // ---------------------------------------------------------------- page

    private final Context c;
    private final Host host;
    private LinearLayout root;
    private boolean headless;
    private final List<String> errors = new ArrayList<>();
    private int warnings;
    private int scrollY;
    private ScrollView sv;
    private String g54Note;

    SafetyCheck(Context c, Host host) { this.c = c; this.host = host; }

    /** Builds the page; on screen it reads G54 with $# first when the machine is idle, then fills it in. */
    View build() {
        root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        int p = Ui.dp(c, 14);
        root.setPadding(p, Ui.dp(c, 10), p, Ui.dp(c, 10));
        if (headless) { render(); return root; }
        refresh();
        return root;
    }

    private void refresh() {
        g54Note = null;
        render();
        if (host.connected() && !host.streaming() && host.machineState().equals("Idle")) {
            host.capture("$#", lines -> {
                g54Note = lines == null ? "$# gave no reply – shown from the status report" : "";
                render();
            });
        } else g54Note = "";
    }

    private void render() {
        if (sv != null) scrollY = sv.getScrollY();
        root.removeAllViews();
        errors.clear();
        warnings = 0;
        SharedPreferences pr = host.prefs();
        String text = host.text();
        syncTicks(text);
        Report r = analyze(text, host.fluidnc() ? 255 : 80);
        Map<String, String> s = host.grblSettings();

        LinearLayout top = Ui.row(c);
        String name = host.fileName();
        LinearLayout titleBox = Ui.heading(c, "Safety check", (name == null || name.isEmpty() ? "No file" : name)
                + " · plywood " + f0(SHEET_X) + " × " + f0(SHEET_Y) + " mm, corner at X0 Y0");
        top.addView(titleBox, Ui.weight(1));
        Button clr = Ui.button(c, "Clear clamps", 0);
        clr.setOnClickListener(v -> { pr.edit().remove("safety_clamps").apply(); render(); });
        top.addView(clr);
        Button again = Ui.button(c, "↻ Check again", 1);
        again.setOnClickListener(v -> refresh());
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ap.setMarginStart(Ui.dp(c, 8));
        top.addView(again, ap);
        root.addView(top);
        TextView banner = Ui.text(c, "", 15, Ui.TEXT);
        banner.setTypeface(Typeface.DEFAULT_BOLD);
        banner.setPadding(Ui.dp(c, 10), Ui.dp(c, 6), Ui.dp(c, 10), Ui.dp(c, 6));
        root.addView(banner, Ui.margins(c, 0, 0, 0, 8));

        if (!r.hasMoves) {
            root.removeView(banner);
            root.addView(Ui.text(c, "The editor has no moves to check. Open or load a program first.", 16, Ui.MUTED));
            errors.add("No program to check");
            return;
        }

        LinearLayout body = new LinearLayout(c);
        body.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(body, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        sv = new ScrollView(c);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        sv.addView(col);
        body.addView(sv, new LinearLayout.LayoutParams(Ui.dp(c, 420), LinearLayout.LayoutParams.MATCH_PARENT));
        LinearLayout right = new LinearLayout(c);
        right.setOrientation(LinearLayout.VERTICAL);
        body.addView(right, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        float rad = r.toolDiameter / 2;
        float[] g54 = parseXYZ(s.get("G54"));
        String g54Src = "$# (G54 offset)";
        if ((headless || g54 == null) && host.connected()) { g54 = host.wco().clone(); g54Src = "status report (WCO)"; }
        float mx = num(s.get("$110")), my = num(s.get("$111")), mz = num(s.get("$112"));
        float stock = r.stockFromFile > 0 ? r.stockFromFile : prefF(pr, "safety_stock", 30);
        float clampH = prefF(pr, "safety_clamp_h", 20);
        List<float[]> clamps = loadClamps(pr);

        // 1. Max Z depth – the most important number
        LinearLayout zc = card(col, "Max Z depth", Ui.ERR);
        TextView big = Ui.text(c, "Z " + f3(r.minZ) + " mm", 34, Ui.ERR);
        big.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        zc.addView(big);
        info(zc, f1(-r.minZ) + " mm below work Z0 (top of the stock) · first at line " + r.minZLine);
        float spoil = -r.minZ - stock;
        String st = "Stock " + f1(stock) + " mm" + (r.stockFromFile > 0 ? " (from the file)" : " (setting below)");
        if (spoil < 0) warn(zc, st + ": does NOT cut through – " + f1(-spoil) + " mm left at the bottom");
        else if (spoil > SPOIL_ERR) bad(zc, st + ": cuts " + f1(spoil) + " mm into the spoilboard (more than " + f0(SPOIL_ERR) + " mm)");
        else if (spoil > SPOIL_WARN) warn(zc, st + ": cuts " + f1(spoil) + " mm into the spoilboard");
        else good(zc, st + ": cuts through, " + f1(spoil) + " mm into the spoilboard");
        info(zc, "Highest Z " + f3(r.maxZ) + " mm (safe height)");
        if (!headless) depthLimitRow(zc, pr, r);
        if (g54 != null) {
            float lo = r.minZ + g54[2], hi = r.maxZ + g54[2];
            info(zc, "In machine coordinates: Z " + f3(lo) + " … " + f3(hi));
            float zt = num(s.get("$132"));
            if (host.fluidnc() && zt > 0) {
                if (lo < -zt) bad(zc, "Deepest Z is below the Z travel (MCS −" + f0(zt) + "): soft-limit alarm. Is Z0 on the stock top?");
                if (hi > 0) bad(zc, "Safe height is above Z home (MCS 0): soft-limit alarm");
            }
        }

        // 2. Tool + spindle
        LinearLayout tc = card(col, "Tool and spindle", Ui.ACCENT);
        value(tc, r.toolDiameter > 0 ? "Ø " + f3(r.toolDiameter) + " mm bit" : "Tool size not in the file");
        if (r.toolDiameter > 0) info(tc, "Flute length must exceed " + f1(-r.minZ) + " mm");
        else warn(tc, "Clearances below are to the tool centre – check the bit by hand");
        if (r.firstCutLine < 0) info(tc, "No cutting moves below Z0");
        else if (!r.spindleAtFirstCut) bad(tc, "Spindle is OFF at the first cut (line " + r.firstCutLine + ") – no M3 before it");
        else if (!r.dwellBeforeFirstCut) warn(tc, "No G4 dwell between M3 and the first cut (line " + r.firstCutLine + "): the spindle may not be up to speed");
        else good(tc, "M3 and a G4 spin-up dwell before the first cut (line " + r.firstCutLine + ")");
        if (r.cutsSpindleOff > 0 && r.spindleAtFirstCut)
            bad(tc, r.cutsSpindleOff + " cutting move(s) with the spindle off (after M5) – first at line " + r.cutsSpindleOffLine);
        if (r.m4Line > 0) warn(tc, "M4 (reverse) at line " + r.m4Line + ": the MK300 is wired for on/off only – it runs forward");
        float rpm = prefF(pr, "safety_rpm", 24000), flutes = prefF(pr, "safety_flutes", 2);
        if (r.maxFeedXY > 0 && rpm > 0 && flutes > 0) {
            double fpt = r.maxFeedXY / (rpm * flutes);
            String t = String.format(Locale.US, "Chip load %.3f mm/tooth (F%.0f ÷ %.0f rpm ÷ %.0f flutes)", fpt, r.maxFeedXY, rpm, flutes);
            if (fpt < 0.05) warn(tc, t + " – low: rubbing and burnt edges in plywood");
            else if (fpt > 0.3) warn(tc, t + " – high: risk of breaking the bit");
            else good(tc, t);
        }

        // 3. Feeds
        LinearLayout fc = card(col, "Max G1 feed rate", Ui.ACCENT);
        value(fc, r.maxFeedXY > 0 ? "F" + f0((float) r.maxFeedXY) + " mm/min" : "no X/Y feed moves");
        if (r.maxFeedXYLine > 0) info(fc, "X/Y cutting (G1/G2/G3) · first at line " + r.maxFeedXYLine);
        if (r.maxFeedZ > 0) info(fc, "Plunge (Z only): F" + f0((float) r.maxFeedZ) + " mm/min · line " + r.maxFeedZLine);
        if (mx > 0 && my > 0 && r.maxFeedXY > Math.min(mx, my))
            warn(fc, "Above the machine's max rate (X " + f0(mx) + " / Y " + f0(my) + "): runs at the max rate");
        if (mz > 0 && r.maxFeedZ > mz) warn(fc, "Plunge above the Z max rate " + f0(mz) + ": runs at " + f0(mz));

        LinearLayout gc = card(col, "Max G0 feed rate", Ui.ACCENT);
        if (mx > 0) {
            value(gc, "X " + f0(mx) + " · Y " + f0(my) + " · Z " + f0(mz) + " mm/min");
            info(gc, "G0 ignores F and runs at the machine's max rate ($110–$112)");
        } else value(gc, "unknown – connect to read the max rate");
        info(gc, r.rapidCount + " rapids" + (r.rapidWithF > 0 ? " (" + r.rapidWithF + " carry an F word, ignored)" : ""));
        if (r.rapidIntoStock > 0)
            bad(gc, r.rapidIntoStock + " G0 move(s) go below Z0 deeper than anything cut so far – first at line " + r.rapidIntoStockLine);
        if (r.rapidIntoSlot > 0)
            warn(gc, r.rapidIntoSlot + " G0 move(s) drop below Z0 back into depth already cut (next pass of a slot) – first at line " + r.rapidIntoSlotLine);
        if (r.rapidIntoStock == 0 && r.rapidIntoSlot == 0 && r.rapidMinZ != Float.MAX_VALUE)
            good(gc, "Rapids stay at Z ≥ " + f3(Math.max(0, r.rapidMinZ)) + " (above the stock)");
        if (r.zFirst) bad(gc, "The first move lowers Z before moving X/Y (line " + r.zFirstLine + "): from home the bit can hit the frame");
        else good(gc, "Leaves the start position with X/Y first, then lowers Z");

        // 4. Codes
        LinearLayout cc = card(col, "G-code the controller must accept", Ui.ACCENT);
        if (r.unknownCodes.isEmpty()) good(cc, "All G/M codes are supported");
        else bad(cc, "Unsupported: " + String.join(", ", r.unknownCodes) + " – first at line " + r.unknownLine + " (error:20 stops the job there)");
        int maxLen = host.fluidnc() ? 255 : 80;
        if (r.longLines > 0) bad(cc, r.longLines + " line(s) longer than " + maxLen + " characters (longest " + r.longestLen + ") – first at line " + r.longLine);
        else good(cc, "All lines fit the controller's " + maxLen + "-character buffer");
        if (r.inches) warn(cc, "G20 (inches) at line " + r.inchesLine + " – sizes here are converted to mm");
        if (r.relative) warn(cc, "G91 (relative) at line " + r.relativeLine + " – positions depend on where the program starts");

        // 5. Machine: homing, G54, Z0, travel
        LinearLayout oc = card(col, "Machine, G54 and travel", Ui.ACCENT);
        if (!host.connected()) warn(oc, "Not connected – homing, G54 and travel are checked once the machine is connected");
        else {
            String homed = host.homedAxes();
            if ("0".equals(s.get("$22"))) warn(oc, "Homing is off ($22=0): soft limits are not reliable");
            else if (homed.contains("X") && homed.contains("Y") && homed.contains("Z")) good(oc, "Homed since connecting (X, Y, Z)");
            else bad(oc, "Not homed since connecting" + (homed.isEmpty() ? "" : " (only " + homed + ")") + " – press Home before running");
        }
        if (g54 != null) {
            value(oc, "G54  X " + f3(g54[0]) + " · Y " + f3(g54[1]) + " · Z " + f3(g54[2]));
            info(oc, "From " + g54Src + (g54Note != null && !g54Note.isEmpty() ? " · " + g54Note : ""));
            checkLastJob(oc, pr, g54, r);
            float lx = r.allMinX + g54[0], hx = r.allMaxX + g54[0], ly = r.allMinY + g54[1], hy = r.allMaxY + g54[1];
            info(oc, "Program in MCS: X " + f1(lx) + " … " + f1(hx) + " · Y " + f1(ly) + " … " + f1(hy));
            float tx = num(s.get("$130")), ty = num(s.get("$131"));
            if (host.fluidnc() && tx > 0 && ty > 0) {
                StringBuilder over = new StringBuilder();
                if (lx < 0) over.append("\n   X ").append(f1(lx)).append(" < 0 (").append(f1(-lx)).append(" mm over)");
                if (hx > tx) over.append("\n   X ").append(f1(hx)).append(" > ").append(f0(tx)).append(" (").append(f1(hx - tx)).append(" mm over)");
                if (ly < 0) over.append("\n   Y ").append(f1(ly)).append(" < 0 (").append(f1(-ly)).append(" mm over)");
                if (hy > ty) over.append("\n   Y ").append(f1(hy)).append(" > ").append(f0(ty)).append(" (").append(f1(hy - ty)).append(" mm over)");
                if (over.length() == 0) good(oc, "Inside the travel (X 0…" + f0(tx) + ", Y 0…" + f0(ty) + ")");
                else bad(oc, "Outside the machine travel – soft-limit alarm:" + over + "\n   Move G54 or the sheet so the program fits");
            }
        } else if (host.connected() && g54Note == null) value(oc, "reading G54…");

        // 6. Plywood edges and clamps
        LinearLayout mc = card(col, "Plywood edges and clamps", Ui.ACCENT);
        float[] m = r.hasCut() ? margins(r) : null;
        if (m != null) {
            String[] names = {"Left (X0 edge)", "Right (X" + f0(SHEET_X) + " edge)", "Front (Y0 edge)", "Back (Y" + f0(SHEET_Y) + " edge)"};
            for (int i = 0; i < 4; i++) {
                String t = names[i] + ": " + marginText(m[i]);
                if (m[i] < 0) bad(mc, t); else if (m[i] < MARGIN_WARN) warn(mc, t); else good(mc, t);
            }
            info(mc, "Cut extents (tool centre): X " + f1(r.cutMinX) + " … " + f1(r.cutMaxX) + " · Y " + f1(r.cutMinY) + " … " + f1(r.cutMaxY)
                    + (rad > 0 ? "; clearances are to the cut edge (± " + f3(rad) + " mm)" : "; to the tool centre"));
        } else info(mc, "No cutting moves below Z0");
        boolean[] clampBad = new boolean[clamps.size()];
        if (clamps.isEmpty()) warn(mc, "No clamps/screws marked – tap the sheet where each one is");
        else {
            for (int k = 0; k < clamps.size(); k++) {
                float[] cl = clamps.get(k);
                float dCut = Float.MAX_VALUE, dRap = Float.MAX_VALUE, zRap = 0;
                for (int i = 0; i < r.cutSegCount; i++) {
                    dCut = Math.min(dCut, segDist(cl[0], cl[1], r.cutSegs[i * 4], r.cutSegs[i * 4 + 1], r.cutSegs[i * 4 + 2], r.cutSegs[i * 4 + 3]));
                }
                for (int i = 0; i < r.rapidSegCount; i++) {
                    float zz = r.rapidSegs[i * 5 + 4];
                    if (zz >= clampH + 5) continue;
                    float d = segDist(cl[0], cl[1], r.rapidSegs[i * 5], r.rapidSegs[i * 5 + 1], r.rapidSegs[i * 5 + 2], r.rapidSegs[i * 5 + 3]);
                    if (d < dRap) { dRap = d; zRap = zz; }
                }
                String at = "Clamp " + (k + 1) + " at X" + f0(cl[0]) + " Y" + f0(cl[1]);
                float edge = dCut - rad;
                if (edge < CLAMP_R) { clampBad[k] = true; bad(mc, at + ": the cut comes within " + f1(Math.max(0, edge)) + " mm (keep-out " + f0(CLAMP_R) + " mm)"); }
                else if (dRap - rad < CLAMP_R) { clampBad[k] = true; bad(mc, at + ": a G0 at Z" + f1(zRap) + " passes over it – clamps are " + f0(clampH) + " mm tall"); }
                else good(mc, at + ": clear (cut edge " + (dCut == Float.MAX_VALUE ? "–" : f0(edge) + " mm") + " away)");
            }
        }
        info(mc, "Tap the sheet to mark a screw or clamp, tap it again to remove it. Keep-out " + f0(CLAMP_R)
                + " mm; rapids below Z" + f0(clampH + 5) + " (clamp height + 5) must not cross it.");

        // 7. Checklist
        LinearLayout kc = card(col, "Before you press Run", Ui.ACCENT);
        int ticked = 0;
        for (int i = 0; i < CHECKS.length; i++) {
            final int idx = i;
            CheckBox cb = new CheckBox(c);
            cb.setText(String.format(Locale.US, CHECKS[i], r.toolDiameter > 0 ? f3(r.toolDiameter) : "?"));
            cb.setTextColor(ticks[i] ? Ui.OK : Ui.TEXT);
            cb.setTextSize(14);
            cb.setChecked(ticks[i]);
            cb.setOnCheckedChangeListener((b, on) -> { ticks[idx] = on; render(); });
            kc.addView(cb);
            if (ticks[i]) ticked++;
            else errors.add("Checklist: " + cb.getText());
        }

        // 8. Settings used by the checks
        LinearLayout sc = card(col, "Check settings", Ui.MUTED);
        if (r.stockFromFile == 0) field(sc, "Stock thickness (mm) – the file does not say", "safety_stock", stock);
        field(sc, "Spindle speed (rpm, MK300 fixed)", "safety_rpm", rpm);
        field(sc, "Bit flutes", "safety_flutes", flutes);
        field(sc, "Clamp / screw head height (mm)", "safety_clamp_h", clampH);

        SheetView sheet = new SheetView(c, r, clamps, clampBad, (x, y) -> { toggleClamp(pr, clamps, x, y); render(); });
        right.addView(sheet, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        int nErr = 0;
        for (String e : errors) if (!e.startsWith("Checklist: ")) nErr++;
        banner.setText((nErr > 0 ? "✖ " + nErr + " problem" + (nErr == 1 ? "" : "s") : "✔ No problems")
                + "   ·   ⚠ " + warnings + " warning" + (warnings == 1 ? "" : "s")
                + "   ·   checklist " + ticked + "/" + CHECKS.length
                + (nErr > 0 || ticked < CHECKS.length ? "   –   Run will ask before starting" : "   –   ready to run"));
        banner.setTextColor(nErr > 0 ? Ui.ERR : warnings > 0 || ticked < CHECKS.length ? Ui.WARN : Ui.OK);
        banner.setBackgroundColor(nErr > 0 ? 0x33FF5C6C : warnings > 0 || ticked < CHECKS.length ? 0x26FFC040 : 0x263DDC84);
        final ScrollView view = sv;
        final int y0 = scrollY;
        view.post(() -> view.scrollTo(0, y0));
    }

    /** "Max Z [-25] [Update G-code]": writes a copy of the program with every deeper Z raised to the limit. */
    private void depthLimitRow(LinearLayout card, SharedPreferences pr, Report r) {
        LinearLayout row = Ui.row(c);
        row.addView(Ui.text(c, "Max Z", 15, Ui.TEXT));
        EditText e = new EditText(c);
        float lim = prefF(pr, "safety_zlimit", -25);
        e.setText(lim == Math.rint(lim) ? f0(lim) : f3(lim));
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        e.setImeOptions(EditorInfo.IME_ACTION_DONE);
        e.setSelectAllOnFocus(true);
        e.setTextColor(Ui.TEXT);
        e.setTextSize(16);
        e.setGravity(android.view.Gravity.CENTER);
        e.setBackgroundResource(R.drawable.input_bg);
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(Ui.dp(c, 90), Ui.dp(c, 40));
        ep.setMarginStart(Ui.dp(c, 8));
        ep.setMarginEnd(Ui.dp(c, 8));
        row.addView(e, ep);
        row.addView(Ui.text(c, "mm", 15, Ui.MUTED), Ui.weight(1));
        Button b = Ui.button(c, "Update G-code", 3);
        b.setOnClickListener(v -> {
            float z;
            try { z = Float.parseFloat(e.getText().toString().trim()); } catch (NumberFormatException ex) { z = Float.NaN; }
            if (!(z < 0)) { toast("Max Z must be below 0 (e.g. -25)"); return; }
            pr.edit().putFloat("safety_zlimit", z).apply();
            // a _Zmax copy is limited again from its original, so the limit can also go deeper than the copy
            String srcName = host.fileName(), src = host.text();
            String orig = limitedFrom(src);
            if (orig != null) {
                String t = host.readLocal(orig);
                if (t == null) { toast("This is a depth-limited copy of " + orig + ", which is not in Documents/GrblSender – open the original and update from there"); return; }
                src = t;
                srcName = orig;
            }
            float deepest = analyze(src, Integer.MAX_VALUE).minZ;
            if (deepest >= z) {
                toast((orig != null ? "The original " + orig : "The program") + " only goes to Z" + f3(deepest)
                        + " – a limit of Z" + f3(z) + " changes nothing" + (orig != null ? "" : " (the limit can only make cuts shallower)"));
                return;
            }
            int[] changed = new int[1];
            String out;
            try { out = limitDepth(src, z, srcName, changed); }
            catch (IllegalStateException ex) { toast(ex.getMessage()); return; }
            host.saveCopy(limitedName(srcName, z), out, (orig != null ? "Made from the original " + orig + ". " : "") +
                    changed[0] + " line(s) deeper than Z" + f3(z) + " were set to Z" + f3(z)
                            + ". Passes that were deeper now repeat at Z" + f3(z) + ".");
        });
        row.addView(b);
        card.addView(row, Ui.margins(c, 0, 8, 0, 2));
        String orig = limitedFrom(host.text());
        info(card, orig != null
                ? "This file is a depth-limited copy: Update G-code starts again from the original " + orig
                        + " (it can go deeper than this copy, down to the original's depth)"
                : "Update G-code writes a copy with every Z below the limit raised to it, named …_Zmax" + fmtZ(lim) + ".nc");
    }

    private void toast(String m) { android.widget.Toast.makeText(c, m, android.widget.Toast.LENGTH_LONG).show(); }

    private static final Pattern Z_WORD = Pattern.compile("([Zz]\\s*)([-+]?(?:\\d+\\.?\\d*|\\.\\d+))");
    private static final Pattern G_WORD = Pattern.compile("[Gg]\\s*0*(\\d+(?:\\.\\d+)?)");

    /**
     * Raises every Z word deeper than {@code limit} (work mm) to the limit. Comments are kept as they are, G53
     * (machine coordinate) lines are left alone, G20 is converted; relative (G91) programs are refused.
     */
    static String limitDepth(String text, float limit, String srcName, int[] changed) {
        String[] rows = text.split("\n", -1);
        StringBuilder out = new StringBuilder(text.length() + 200);
        double unit = 1;
        boolean header = false;
        for (int ln = 0; ln < rows.length; ln++) {
            String raw = rows[ln];
            // split into code and comment pieces so Z words inside comments stay untouched
            StringBuilder code = new StringBuilder();
            List<int[]> spans = new ArrayList<>();   // code ranges [start, end)
            int depth = 0, start = 0;
            boolean semi = false;
            for (int i = 0; i < raw.length() && !semi; i++) {
                char ch = raw.charAt(i);
                if (depth == 0 && (ch == '(' || ch == ';')) { spans.add(new int[]{start, i}); if (ch == ';') semi = true; else depth = 1; continue; }
                if (depth > 0) { if (ch == '(') depth++; else if (ch == ')' && --depth == 0) start = i + 1; }
            }
            if (!semi && depth == 0) spans.add(new int[]{start, raw.length()});
            for (int[] sp : spans) code.append(raw, sp[0], sp[1]).append(' ');
            boolean g53 = false;
            Matcher g = G_WORD.matcher(code);
            while (g.find()) {
                double v = Double.parseDouble(g.group(1));
                if (v == 91) throw new IllegalStateException("Line " + (ln + 1) + " uses G91 (relative moves) – depths cannot be limited safely");
                if (v == 20) unit = 25.4;
                if (v == 21) unit = 1;
                if (v == 53) g53 = true;
            }
            StringBuilder line = new StringBuilder(raw.length());
            int pos = 0;
            boolean hit = false;
            for (int[] sp : spans) {
                line.append(raw, pos, sp[0]);
                String part = raw.substring(sp[0], sp[1]);
                if (!g53) {
                    Matcher z = Z_WORD.matcher(part);
                    StringBuffer sb = new StringBuffer();
                    while (z.find()) {
                        double v = Double.parseDouble(z.group(2)) * unit;
                        if (v < limit - 1e-9) {
                            z.appendReplacement(sb, Matcher.quoteReplacement(z.group(1) + trimNum(limit / unit)));
                            hit = true;
                        }
                    }
                    z.appendTail(sb);
                    part = sb.toString();
                }
                line.append(part);
                pos = sp[1];
            }
            line.append(raw, pos, raw.length());
            if (hit) changed[0]++;
            if (!header && !raw.trim().equals("%")) {
                out.append("(Max Z depth limited to Z").append(trimNum(limit)).append(" mm by the Grbl Sender safety check, from ")
                        .append(srcName == null ? "untitled" : srcName.replace('(', '[').replace(')', ']')).append(")\n");
                header = true;
            }
            out.append(line);
            if (ln < rows.length - 1) out.append('\n');
        }
        return out.toString();
    }

    private static final Pattern LIMITED_FROM =
            Pattern.compile("^\\(Max Z depth limited to Z[-0-9.]+ mm by the Grbl Sender safety check, from (.+)\\)\\s*$", Pattern.MULTILINE);

    /** The original file name when text is a copy written by Update G-code (its header comment), else null. */
    static String limitedFrom(String text) {
        Matcher m = LIMITED_FROM.matcher(text.length() > 4000 ? text.substring(0, 4000) : text);
        return m.find() ? m.group(1).trim() : null;
    }

    private static String trimNum(double v) {
        String s = String.format(Locale.US, "%.4f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
        return s.equals("-0") ? "0" : s;
    }

    private static String fmtZ(float z) { return trimNum(z); }

    /** "dome_v4.nc" + -25 → "dome_v4_Zmax-25.nc" (an earlier _Zmax suffix is replaced). */
    static String limitedName(String name, float z) {
        String base = name == null || name.isEmpty() ? "untitled" : name;
        int dot = base.lastIndexOf('.');
        String ext = dot > 0 ? base.substring(dot) : ".nc";
        if (dot > 0) base = base.substring(0, dot);
        base = base.replaceAll("_Zmax-?[0-9.]+$", "");
        return base + "_Zmax" + fmtZ(z) + ext;
    }

    /** Compares G54 with the last job run on the tablet: holes and profile files must share X/Y; a new bit needs a new Z0. */
    private void checkLastJob(LinearLayout card, SharedPreferences pr, float[] g54, Report r) {
        float[] last = parseXYZ(pr.getString("lastRunG54", null));
        long at = pr.getLong("lastRunAt", 0);
        String file = pr.getString("lastRunFile", "");
        float lastTool = pr.getFloat("lastRunTool", 0);
        long z0At = pr.getLong("z0SetAt", 0);
        String when = at > 0 ? new java.text.SimpleDateFormat("MMM d HH:mm", Locale.US).format(new java.util.Date(at)) : "";
        if (z0At > host.connectedAt() && host.connectedAt() > 0)
            good(card, "Z0 set with \"0 Z\" at " + new java.text.SimpleDateFormat("HH:mm", Locale.US).format(new java.util.Date(z0At)) + " (this session)");
        else if (host.connected()) warn(card, "Z0 not set with \"0 Z\" since connecting – make sure it is on top of the stack");
        if (last == null) { info(card, "No earlier job recorded to compare G54 with"); return; }
        float dx = g54[0] - last[0], dy = g54[1] - last[1], dz = g54[2] - last[2];
        info(card, "Last job: " + file + " (" + when + ") with G54 X " + f3(last[0]) + " Y " + f3(last[1]) + " Z " + f3(last[2]));
        if (Math.abs(dx) > 0.05 || Math.abs(dy) > 0.05)
            warn(card, "G54 X/Y moved by " + f3(dx) + ", " + f3(dy) + " mm since that job – if this file continues it (holes → profile), they will not line up");
        else good(card, "Same G54 X/Y as the last job (holes and profile line up)");
        boolean newBit = r.toolDiameter > 0 && lastTool > 0 && Math.abs(r.toolDiameter - lastTool) > 0.01;
        if (newBit && Math.abs(dz) < 0.01)
            bad(card, "Bit changed (Ø" + f3(lastTool) + " → Ø" + f3(r.toolDiameter) + ") but Z0 is the same – set Z0 again on the stock top");
        else if (Math.abs(dz) > 2 && !newBit)
            warn(card, "G54 Z moved by " + f3(dz) + " mm since the last job with the same bit");
    }

    private static List<float[]> loadClamps(SharedPreferences pr) {
        List<float[]> out = new ArrayList<>();
        for (String p : pr.getString("safety_clamps", "").split(";")) {
            String[] xy = p.split(",");
            if (xy.length != 2) continue;
            try { out.add(new float[]{Float.parseFloat(xy[0]), Float.parseFloat(xy[1])}); } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    private static void toggleClamp(SharedPreferences pr, List<float[]> clamps, float x, float y) {
        int hit = -1;
        for (int i = 0; i < clamps.size(); i++) if (Math.hypot(clamps.get(i)[0] - x, clamps.get(i)[1] - y) < CLAMP_R * 1.6f) hit = i;
        if (hit >= 0) clamps.remove(hit); else clamps.add(new float[]{x, y});
        StringBuilder sb = new StringBuilder();
        for (float[] cl : clamps) sb.append(sb.length() == 0 ? "" : ";").append(f1(cl[0])).append(',').append(f1(cl[1]));
        pr.edit().putString("safety_clamps", sb.toString()).apply();
    }

    /** Left, right, front, back distance from the cut edge to the sheet edge (negative = off the sheet). */
    static float[] margins(Report r) {
        float rad = r.toolDiameter / 2;
        return new float[]{r.cutMinX - rad, SHEET_X - (r.cutMaxX + rad), r.cutMinY - rad, SHEET_Y - (r.cutMaxY + rad)};
    }

    static String marginText(float m) {
        return m < 0 ? f1(-m) + " mm OFF the sheet" : f1(m) + " mm" + (m < MARGIN_WARN ? " (tight)" : "");
    }

    static int marginColor(float m) { return m < 0 ? Ui.ERR : m < MARGIN_WARN ? Ui.WARN : Ui.OK; }

    private LinearLayout card(LinearLayout col, String title, int color) {
        LinearLayout k = Ui.card(c);
        TextView t = Ui.label(c, title);
        t.setTextColor(color);
        t.setPadding(0, 0, 0, Ui.dp(c, 4));
        k.addView(t);
        col.addView(k, Ui.margins(c, 0, 0, 8, 8));
        return k;
    }

    private void value(LinearLayout card, String s) {
        TextView t = Ui.text(c, s, 20, Ui.TEXT);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        card.addView(t);
    }

    private void line(LinearLayout card, String s, int color) {
        TextView t = Ui.text(c, s, 13, color);
        t.setPadding(0, Ui.dp(c, 2), 0, 0);
        card.addView(t);
    }

    private void info(LinearLayout card, String s) { line(card, s, Ui.MUTED); }
    private void good(LinearLayout card, String s) { line(card, "✔ " + s, Ui.OK); }
    private void warn(LinearLayout card, String s) { warnings++; line(card, "⚠ " + s, Ui.WARN); }
    private void bad(LinearLayout card, String s) { errors.add(s); line(card, "✖ " + s, Ui.ERR); }

    private void field(LinearLayout card, String label, String key, float value) {
        LinearLayout row = Ui.row(c);
        row.addView(Ui.text(c, label, 13, Ui.TEXT), Ui.weight(1));
        EditText e = new EditText(c);
        e.setText(value == Math.rint(value) ? f0(value) : f1(value));
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setImeOptions(EditorInfo.IME_ACTION_DONE);
        e.setSelectAllOnFocus(true);
        e.setTextColor(Ui.TEXT);
        e.setTextSize(15);
        e.setGravity(android.view.Gravity.CENTER);
        e.setBackgroundResource(R.drawable.input_bg);
        e.setOnEditorActionListener((v, id, ev) -> {
            float f = num(v.getText().toString());
            if (f > 0) host.prefs().edit().putFloat(key, f).apply();
            render();
            return false;
        });
        row.addView(e, new LinearLayout.LayoutParams(Ui.dp(c, 90), Ui.dp(c, 38)));
        card.addView(row, Ui.margins(c, 0, 4, 0, 0));
    }

    private static float prefF(SharedPreferences pr, String key, float def) {
        try { return pr.getFloat(key, def); } catch (ClassCastException e) { return def; }
    }

    static float[] parseXYZ(String v) {
        if (v == null) return null;
        String[] p = v.split(",");
        if (p.length < 3) return null;
        try { return new float[]{Float.parseFloat(p[0].trim()), Float.parseFloat(p[1].trim()), Float.parseFloat(p[2].trim())}; }
        catch (NumberFormatException e) { return null; }
    }

    private static float num(String v) {
        if (v == null) return 0;
        try { return Float.parseFloat(v.trim()); } catch (NumberFormatException e) { return 0; }
    }

    static String f0(float v) { return String.format(Locale.US, "%.0f", v); }
    static String f1(float v) { return String.format(Locale.US, "%.1f", v); }
    static String f3(float v) { return String.format(Locale.US, "%.3f", v); }

    // ---------------------------------------------------------------- drawing

    interface Tap { void at(float x, float y); }

    /** Plywood sheet (X right, Y up) with the cut paths, their bounding box, the clearance to each edge and the clamps. */
    private static final class SheetView extends View {
        private final Report r;
        private final List<float[]> clamps;
        private final boolean[] clampBad;
        private final Tap tap;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG),
                path = new Paint(), box = new Paint(Paint.ANTI_ALIAS_FLAG), txt = new Paint(Paint.ANTI_ALIAS_FLAG),
                clamp = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density;
        private float ox, oy, s;
        private float downX = Float.NaN, downY;

        SheetView(Context c, Report r, List<float[]> clamps, boolean[] clampBad, Tap tap) {
            super(c);
            this.r = r;
            this.clamps = clamps;
            this.clampBad = clampBad;
            this.tap = tap;
            density = c.getResources().getDisplayMetrics().density;
            fill.setColor(0xFF5A4630);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setColor(0xFFC8A06A);
            stroke.setStrokeWidth(2 * density);
            path.setColor(0xFF4EA1FF);
            path.setStrokeWidth(1);
            box.setStyle(Paint.Style.STROKE);
            box.setStrokeWidth(2 * density);
            box.setPathEffect(new DashPathEffect(new float[]{8 * density, 5 * density}, 0));
            txt.setTextSize(13 * density);
            txt.setTypeface(Typeface.DEFAULT_BOLD);
            clamp.setStrokeWidth(2 * density);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            // a tap is a down and an up on this view close together (not the end of a touch that began elsewhere)
            if (e.getAction() == MotionEvent.ACTION_DOWN) { downX = e.getX(); downY = e.getY(); return true; }
            if (e.getAction() == MotionEvent.ACTION_CANCEL) { downX = Float.NaN; return true; }
            if (e.getAction() == MotionEvent.ACTION_UP && s > 0 && !Float.isNaN(downX)
                    && Math.hypot(e.getX() - downX, e.getY() - downY) < 12 * density) {
                downX = Float.NaN;
                float x = (e.getX() - ox) / s, y = (oy - e.getY()) / s;
                if (x >= -50 && y >= -50 && x <= SHEET_X + 50 && y <= SHEET_Y + 50) tap.at(x, y);
                return true;
            }
            return super.onTouchEvent(e);
        }

        @Override protected void onDraw(Canvas cv) {
            float pad = 40 * density;
            float w = getWidth() - 2 * pad, h = getHeight() - 2 * pad;
            if (w <= 0 || h <= 0) return;
            // fit the sheet and anything off it
            float minX = Math.min(0, r.hasCut() ? r.cutMinX : 0), maxX = Math.max(SHEET_X, r.hasCut() ? r.cutMaxX : 0);
            float minY = Math.min(0, r.hasCut() ? r.cutMinY : 0), maxY = Math.max(SHEET_Y, r.hasCut() ? r.cutMaxY : 0);
            s = Math.min(w / (maxX - minX), h / (maxY - minY));
            ox = pad + (w - (maxX - minX) * s) / 2 - minX * s;
            oy = pad + (h - (maxY - minY) * s) / 2 + maxY * s;   // screen y of Y=0

            RectF sheet = new RectF(ox, oy - SHEET_Y * s, ox + SHEET_X * s, oy);
            cv.drawRect(sheet, fill);
            cv.drawRect(sheet, stroke);
            txt.setColor(0xFFC8A06A);
            txt.setTextAlign(Paint.Align.CENTER);
            cv.drawText("Plywood " + f0(SHEET_X) + " × " + f0(SHEET_Y) + " mm", sheet.centerX(), sheet.top - 8 * density, txt);
            txt.setColor(Ui.TEXT);
            txt.setTextAlign(Paint.Align.LEFT);
            cv.drawText("X0 Y0", ox + 4 * density, oy + 16 * density, txt);

            for (int i = 0; i < r.cutSegCount; i++) {
                cv.drawLine(ox + r.cutSegs[i * 4] * s, oy - r.cutSegs[i * 4 + 1] * s,
                        ox + r.cutSegs[i * 4 + 2] * s, oy - r.cutSegs[i * 4 + 3] * s, path);
            }

            for (int k = 0; k < clamps.size(); k++) {
                float cx = ox + clamps.get(k)[0] * s, cy = oy - clamps.get(k)[1] * s;
                int col = clampBad[k] ? Ui.ERR : Ui.WARN;
                clamp.setStyle(Paint.Style.FILL);
                clamp.setColor((col & 0x00FFFFFF) | 0x55000000);
                cv.drawCircle(cx, cy, Math.max(CLAMP_R * s, 6 * density), clamp);
                clamp.setStyle(Paint.Style.STROKE);
                clamp.setColor(col);
                cv.drawCircle(cx, cy, Math.max(CLAMP_R * s, 6 * density), clamp);
                txt.setColor(col);
                cv.drawText(String.valueOf(k + 1), cx + 8 * density, cy - 8 * density, txt);
            }
            if (!r.hasCut()) return;

            float rad = r.toolDiameter / 2;
            float[] m = margins(r);
            float worst = Math.min(Math.min(m[0], m[1]), Math.min(m[2], m[3]));
            box.setColor(marginColor(worst));
            RectF bb = new RectF(ox + (r.cutMinX - rad) * s, oy - (r.cutMaxY + rad) * s, ox + (r.cutMaxX + rad) * s, oy - (r.cutMinY - rad) * s);
            cv.drawRect(bb, box);

            float cy = bb.centerY(), cx = bb.centerX();
            dim(cv, sheet.left, cy, bb.left, cy, m[0], true);
            dim(cv, bb.right, cy, sheet.right, cy, m[1], true);
            dim(cv, cx, sheet.bottom, cx, bb.bottom, m[2], false);
            dim(cv, cx, bb.top, cx, sheet.top, m[3], false);
        }

        private void dim(Canvas cv, float x0, float y0, float x1, float y1, float mm, boolean horiz) {
            int col = marginColor(mm);
            Paint l = new Paint(Paint.ANTI_ALIAS_FLAG);
            l.setColor(col);
            l.setStrokeWidth(2 * density);
            cv.drawLine(x0, y0, x1, y1, l);
            float t = 5 * density;
            if (horiz) { cv.drawLine(x0, y0 - t, x0, y0 + t, l); cv.drawLine(x1, y1 - t, x1, y1 + t, l); }
            else { cv.drawLine(x0 - t, y0, x0 + t, y0, l); cv.drawLine(x1 - t, y1, x1 + t, y1, l); }
            String label = (mm < 0 ? "−" : "") + f1(Math.abs(mm)) + " mm";
            float tw = txt.measureText(label);
            float lx = (x0 + x1) / 2, ly = (y0 + y1) / 2;
            Paint bg = new Paint();
            bg.setColor(0xE00B0F14);
            float th = txt.getTextSize();
            RectF rb = horiz ? new RectF(lx - tw / 2 - 4 * density, ly - th - 6 * density, lx + tw / 2 + 4 * density, ly - 3 * density)
                    : new RectF(lx + 6 * density, ly - th / 2 - 3 * density, lx + tw + 14 * density, ly + th / 2 + 3 * density);
            cv.drawRect(rb, bg);
            txt.setColor(col);
            txt.setTextAlign(Paint.Align.LEFT);
            cv.drawText(label, rb.left + 4 * density, rb.bottom - 4 * density, txt);
        }
    }
}
