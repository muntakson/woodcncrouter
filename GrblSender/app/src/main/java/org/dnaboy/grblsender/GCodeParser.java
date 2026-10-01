package org.dnaboy.grblsender;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns G-code text into (a) the cleaned lines that get streamed to Grbl and
 * (b) a top-view (XY) toolpath for the preview. Also sniffs the tool diameter
 * out of CAM comments.
 */
final class GCodeParser {

    static final float DEFAULT_TOOL_DIAMETER = 3.175f;

    /** One streamable line plus the editor line it came from. */
    static final class Line {
        final String text;
        final int editorLine;
        Line(String text, int editorLine) { this.text = text; this.editorLine = editorLine; }
    }

    static final class Result {
        final List<Line> lines = new ArrayList<>();
        /** Segment endpoints x0,y0,x1,y1 in work mm; feeds and rapids kept separately for drawing. */
        float[] feedPts = new float[0];
        float[] rapidPts = new float[0];
        /** Program-line index (into {@link #lines}) of each segment, non-decreasing. */
        int[] feedLine = new int[0];
        int[] rapidLine = new int[0];
        int feedCount, rapidCount;
        /**
         * The same XY segments with repeats removed (multi-pass programs cut the same
         * outline at several depths). Used for drawing only; line index = first visit.
         */
        float[] drawFeedPts = new float[0], drawRapidPts = new float[0];
        int[] drawFeedLine = new int[0];
        int drawFeedCount, drawRapidCount;
        /** Program uses relative moves (G91), so its drawing depends on the start position. */
        boolean relative;
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        float toolDiameter = DEFAULT_TOOL_DIAMETER;
        boolean toolFromFile = false;
        String toolDescription = "";
        double feedLengthMm, rapidLengthMm;
        /** Estimated run time in minutes (Grbl planner model, see {@link TimeEstimator}). */
        double feedTimeMin;
        /** Estimated seconds elapsed at the end of each program line (index = program line). */
        double[] lineEndSec = new double[0];
        double estSeconds;
        boolean feedCapped;          // feeds in the file exceed the machine's max rate
        double maxFeedRequested;
        boolean limitsFromMachine;   // estimate used the connected machine's $ settings
        double cappedRate;           // the machine's XY max rate (mm/min) used in the estimate
        int tooLong;
        boolean hasBounds() { return minX <= maxX; }

        /** Start/end point of program line {@code lineIdx}'s last segment, or null. */
        float[] endOfLine(int lineIdx) { return lastPointUpTo(lineIdx); }

        float[] lastPointUpTo(int lineIdx) {
            float[] best = null;
            int bestLine = -1;
            int fi = upperBound(feedLine, feedCount, lineIdx) - 1;
            if (fi >= 0) { best = new float[]{feedPts[fi * 4 + 2], feedPts[fi * 4 + 3]}; bestLine = feedLine[fi]; }
            int ri = upperBound(rapidLine, rapidCount, lineIdx) - 1;
            if (ri >= 0 && rapidLine[ri] > bestLine) best = new float[]{rapidPts[ri * 4 + 2], rapidPts[ri * 4 + 3]};
            return best;
        }
    }

    /** Number of entries in sorted {@code a[0..n)} that are <= key. */
    static int upperBound(int[] a, int n, int key) {
        int lo = 0, hi = n;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (a[mid] <= key) lo = mid + 1; else hi = mid;
        }
        return lo;
    }

    private static final Pattern WORD = Pattern.compile("([A-Z])\\s*([-+]?(?:\\d+\\.?\\d*|\\.\\d+))");
    private static final Pattern[] TOOL_PATTERNS = {
            // Fusion 360 / generic: (T1 D=3.175 CR=0 - ZMIN=-3 - flat end mill)
            Pattern.compile("\\bD\\s*=\\s*(\\d*\\.?\\d+)\\s*(MM|IN|\")?", Pattern.CASE_INSENSITIVE),
            // "tool diameter: 6mm", "Tool dia 1/8 in", "(TOOL DIA. 0.25)"
            Pattern.compile("(?:tool|bit|mill|cutter)[^)\\n]*?\\bdia(?:meter|\\.)?\\s*[:=]?\\s*(\\d+/\\d+|\\d*\\.?\\d+)\\s*(MM|IN|\")?", Pattern.CASE_INSENSITIVE),
            // Carbide Create / APT: TOOL/MILL,3.175,0.0,...
            Pattern.compile("TOOL/MILL\\s*,\\s*(\\d*\\.?\\d+)", Pattern.CASE_INSENSITIVE),
            // "Endmill 3.175mm", "3mm end mill", "1/8in bit"
            Pattern.compile("(\\d+/\\d+|\\d*\\.?\\d+)\\s*(MM|IN|\")\\s*(?:flat|ball|v-?bit|end\\s*mill|endmill|bit|cutter)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?:end\\s*mill|endmill|bit|cutter)\\s*(\\d+/\\d+|\\d*\\.?\\d+)\\s*(MM|IN|\")", Pattern.CASE_INSENSITIVE),
    };

    /**
     * @param startX start X (current work position) – matters for relative (G91) programs
     */
    static Result parse(String text, float startX, float startY, float startZ, TimeEstimator.Limits limits) {
        Result r = new Result();
        TimeEstimator.Limits lim = limits != null ? limits : new TimeEstimator.Limits();
        FloatList feed = new FloatList(), rapid = new FloatList();
        IntList feedL = new IntList(), rapidL = new IntList();

        double x = startX, y = startY, z = startZ;
        int motion = 0;             // 0,1,2,3
        boolean absolute = true;
        double unit = 1.0;          // 25.4 when G20
        int plane = 17;
        double feedRate = 0;
        boolean toolFound = false;
        StringBuilder comments = new StringBuilder();

        String[] rows = text.split("\n", -1);
        TimeEstimator est = new TimeEstimator(lim, rows.length);
        double[] arc = new double[5];
        for (int ln = 0; ln < rows.length; ln++) {
            String raw = rows[ln];
            // Collect comments for tool detection, strip them for parsing
            String code = stripComments(raw, comments);
            if (!toolFound && comments.length() > 0) {
                float d = findToolDiameter(comments.toString());
                if (d > 0) {
                    r.toolDiameter = d;
                    r.toolFromFile = true;
                    r.toolDescription = comments.toString().trim();
                    toolFound = true;
                }
            }
            comments.setLength(0);
            String clean = code.replaceAll("\\s+", "").toUpperCase(Locale.US);
            if (clean.isEmpty() || clean.equals("%")) continue;
            if (clean.length() > 80) r.tooLong++;
            int progIdx = r.lines.size();
            r.lines.add(new Line(clean, ln));
            if (clean.startsWith("$")) { est.stop(); continue; }   // Grbl system command (synchronises)

            Double X = null, Y = null, Z = null, I = null, J = null, R = null;
            boolean nonModal = false;    // G4, G10, G28, G30, G53, G92 ... don't move along the program path
            boolean setOrigin = false;
            boolean dwellCmd = false;
            double P = 0;
            Matcher m = WORD.matcher(clean);
            while (m.find()) {
                char letter = m.group(1).charAt(0);
                double v;
                try { v = Double.parseDouble(m.group(2)); } catch (NumberFormatException e) { continue; }
                switch (letter) {
                    case 'G': {
                        int g10 = (int) Math.round(v * 10);
                        switch (g10) {
                            case 0: motion = 0; break;
                            case 10: motion = 1; break;
                            case 20: motion = 2; break;
                            case 30: motion = 3; break;
                            case 170: plane = 17; break;
                            case 180: plane = 18; break;
                            case 190: plane = 19; break;
                            case 200: unit = 25.4; break;
                            case 210: unit = 1.0; break;
                            case 900: absolute = true; break;
                            case 910: absolute = false; r.relative = true; break;
                            case 920: setOrigin = true; break;
                            case 40: dwellCmd = true; nonModal = true; break;
                            case 100: case 280: case 300: case 530: nonModal = true; est.stop(); break;
                            default:
                                if (g10 >= 382 && g10 <= 385) motion = 1;   // probing moves like a feed
                                break;
                        }
                        break;
                    }
                    case 'X': X = v; break;
                    case 'Y': Y = v; break;
                    case 'Z': Z = v; break;
                    case 'I': I = v; break;
                    case 'J': J = v; break;
                    case 'R': R = v; break;
                    case 'F': feedRate = v * unit; break;
                    case 'P': P = v; break;
                    case 'M': {
                        int mc = (int) Math.round(v);
                        // program pause/end and spindle/coolant changes make Grbl stop and sync
                        if (mc <= 9 || mc == 30) est.stop();
                        break;
                    }
                    default: break;
                }
            }
            if (dwellCmd) est.dwell(progIdx, P);
            if (setOrigin) est.stop();
            if (setOrigin || nonModal) continue;   // coordinate-system changes: path continues where the tool is
            if (X == null && Y == null && Z == null) continue;

            double nx = X == null ? x : (absolute ? X * unit : x + X * unit);
            double ny = Y == null ? y : (absolute ? Y * unit : y + Y * unit);
            double nz = Z == null ? z : (absolute ? Z * unit : z + Z * unit);

            if (motion == 0) {
                addSeg(rapid, rapidL, r, x, y, nx, ny, progIdx);
                r.rapidLengthMm += dist3(x, y, z, nx, ny, nz);
                est.line(progIdx, nx - x, ny - y, nz - z, 0);
            } else if ((motion == 2 || motion == 3) && plane == 17) {
                double len = addArc(feed, feedL, r, x, y, nx, ny, I, J, R, unit, motion == 2, progIdx, arc);
                r.feedLengthMm += Math.hypot(len, nz - z);
                if (Double.isNaN(arc[0])) est.line(progIdx, nx - x, ny - y, nz - z, feedRate);
                else est.arc(progIdx, arc[0], arc[1], arc[2], arc[3], arc[4], nz - z, feedRate);
            } else {
                addSeg(feed, feedL, r, x, y, nx, ny, progIdx);
                r.feedLengthMm += dist3(x, y, z, nx, ny, nz);
                est.line(progIdx, nx - x, ny - y, nz - z, feedRate);
            }
            x = nx; y = ny; z = nz;
            r.minZ = Math.min(r.minZ, (float) z);
            r.maxZ = Math.max(r.maxZ, (float) z);
        }
        r.feedPts = feed.toArray();
        r.rapidPts = rapid.toArray();
        r.feedLine = feedL.toArray();
        r.rapidLine = rapidL.toArray();
        r.feedCount = r.feedPts.length / 4;
        r.rapidCount = r.rapidPts.length / 4;
        dedupe(r);
        r.lineEndSec = est.finish();
        r.estSeconds = r.lineEndSec[r.lineEndSec.length - 1];
        r.feedTimeMin = r.estSeconds / 60.0;
        r.feedCapped = est.feedCapped;
        r.maxFeedRequested = est.maxFeedRequested;
        r.limitsFromMachine = lim.fromMachine;
        r.cappedRate = Math.min(lim.maxRate[0], lim.maxRate[1]);
        return r;
    }

    /** Fills the draw* arrays: segments whose quantised (0.01 mm) endpoints were already seen are skipped. */
    private static void dedupe(Result r) {
        java.util.HashSet<Long> seen = new java.util.HashSet<>(r.feedCount * 2);
        FloatList fp = new FloatList();
        IntList fl = new IntList();
        for (int i = 0; i < r.feedCount; i++) {
            if (seen.add(segKey(r.feedPts, i))) {
                fp.add(r.feedPts[i * 4], r.feedPts[i * 4 + 1], r.feedPts[i * 4 + 2], r.feedPts[i * 4 + 3]);
                fl.add(r.feedLine[i]);
            }
        }
        r.drawFeedPts = fp.toArray();
        r.drawFeedLine = fl.toArray();
        r.drawFeedCount = r.drawFeedPts.length / 4;
        seen.clear();
        FloatList rp = new FloatList();
        for (int i = 0; i < r.rapidCount; i++) {
            if (seen.add(segKey(r.rapidPts, i))) {
                rp.add(r.rapidPts[i * 4], r.rapidPts[i * 4 + 1], r.rapidPts[i * 4 + 2], r.rapidPts[i * 4 + 3]);
            }
        }
        r.drawRapidPts = rp.toArray();
        r.drawRapidCount = r.drawRapidPts.length / 4;
    }

    private static long segKey(float[] p, int i) {
        long h = 1125899906842597L;
        for (int k = 0; k < 4; k++) h = 31 * h + Math.round(p[i * 4 + k] * 100.0);
        return h;
    }

    private static String stripComments(String raw, StringBuilder comments) {
        StringBuilder code = new StringBuilder(raw.length());
        int depth = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (depth == 0 && c == ';') {
                comments.append(raw, i + 1, raw.length()).append(' ');
                break;
            }
            if (c == '(') { depth++; continue; }
            if (c == ')' && depth > 0) { depth--; comments.append(' '); continue; }
            if (depth > 0) comments.append(c); else code.append(c);
        }
        return code.toString();
    }

    static float findToolDiameter(String comment) {
        for (Pattern p : TOOL_PATTERNS) {
            Matcher m = p.matcher(comment);
            if (!m.find()) continue;
            String num = m.group(1);
            double v;
            if (num.contains("/")) {
                String[] f = num.split("/");
                v = Double.parseDouble(f[0]) / Double.parseDouble(f[1]);
            } else {
                v = Double.parseDouble(num);
            }
            String u = m.groupCount() >= 2 && m.group(2) != null ? m.group(2).toUpperCase(Locale.US) : "";
            boolean inch = u.equals("IN") || u.equals("\"") || num.contains("/");
            if (inch) v *= 25.4;
            if (v > 0.05 && v < 60) return (float) v;
        }
        return -1;
    }

    private static void addSeg(FloatList pts, IntList lines, Result r, double x0, double y0, double x1, double y1, int line) {
        pts.add((float) x0, (float) y0, (float) x1, (float) y1);
        lines.add(line);
        r.minX = (float) Math.min(r.minX, Math.min(x0, x1));
        r.maxX = (float) Math.max(r.maxX, Math.max(x0, x1));
        r.minY = (float) Math.min(r.minY, Math.min(y0, y1));
        r.maxY = (float) Math.max(r.maxY, Math.max(y0, y1));
    }

    /** Tessellates an XY arc; returns its length in mm. */
    private static double addArc(FloatList pts, IntList lines, Result r, double x0, double y0, double x1, double y1,
                                 Double I, Double J, Double R, double unit, boolean cw, int line, double[] out) {
        out[0] = Double.NaN;   // stays NaN when the move degenerates to a straight line
        double cx, cy;
        if (I != null || J != null) {
            cx = x0 + (I == null ? 0 : I * unit);
            cy = y0 + (J == null ? 0 : J * unit);
        } else if (R != null) {
            double rad = R * unit;
            double dx = x1 - x0, dy = y1 - y0;
            double d = Math.hypot(dx, dy);
            if (d == 0 || Math.abs(rad) < d / 2 - 1e-6) {
                addSeg(pts, lines, r, x0, y0, x1, y1, line);
                return d;
            }
            double h = Math.sqrt(Math.max(0, rad * rad - d * d / 4));
            // Grbl: positive R = shorter arc; sign flips the side of the centre
            double side = (cw ? -1 : 1) * (rad < 0 ? -1 : 1);
            cx = (x0 + x1) / 2 - side * h * dy / d;
            cy = (y0 + y1) / 2 + side * h * dx / d;
        } else {
            addSeg(pts, lines, r, x0, y0, x1, y1, line);
            return Math.hypot(x1 - x0, y1 - y0);
        }
        double radius = Math.hypot(x0 - cx, y0 - cy);
        double a0 = Math.atan2(y0 - cy, x0 - cx);
        double a1 = Math.atan2(y1 - cy, x1 - cx);
        double sweep = a1 - a0;
        if (cw) { if (sweep >= -1e-9) sweep -= 2 * Math.PI; }
        else { if (sweep <= 1e-9) sweep += 2 * Math.PI; }
        out[0] = cx; out[1] = cy; out[2] = radius; out[3] = a0; out[4] = sweep;
        // Chord tolerance 0.02 mm
        double step = radius > 0.02 ? 2 * Math.acos(Math.max(-1, 1 - 0.02 / radius)) : Math.PI / 8;
        int n = Math.max(4, Math.min(720, (int) Math.ceil(Math.abs(sweep) / Math.max(step, 1e-3))));
        double px = x0, py = y0;
        for (int i = 1; i <= n; i++) {
            double a = a0 + sweep * i / n;
            double qx = i == n ? x1 : cx + radius * Math.cos(a);
            double qy = i == n ? y1 : cy + radius * Math.sin(a);
            addSeg(pts, lines, r, px, py, qx, qy, line);
            px = qx;
            py = qy;
        }
        return Math.abs(sweep) * radius;
    }

    private static double dist3(double x0, double y0, double z0, double x1, double y1, double z1) {
        double dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static final class FloatList {
        float[] a = new float[256];
        int n;
        void add(float p, float q, float s, float t) {
            if (n + 4 > a.length) a = java.util.Arrays.copyOf(a, a.length * 2);
            a[n++] = p; a[n++] = q; a[n++] = s; a[n++] = t;
        }
        float[] toArray() { return java.util.Arrays.copyOf(a, n); }
    }

    private static final class IntList {
        int[] a = new int[64];
        int n;
        void add(int v) {
            if (n == a.length) a = java.util.Arrays.copyOf(a, a.length * 2);
            a[n++] = v;
        }
        int[] toArray() { return java.util.Arrays.copyOf(a, n); }
    }
}
