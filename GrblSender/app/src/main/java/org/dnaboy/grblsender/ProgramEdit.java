package org.dnaboy.grblsender;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Edit page: reads the open program's rapid moves, free-move Z heights, contour and hole feeds, peck pattern and
 * spindle speed into a table, and writes an edited copy (…_e1.nc, …_e2.nc) with the new values. Only the words
 * that change are rewritten; when a feed changes, F is carried over to the following lines so modal F stays right.
 */
final class ProgramEdit {

    interface Host {
        String text();
        String fileName();
        Map<String, String> grblSettings();
        /** True when Documents/GrblSender/name exists. */
        boolean exists(String name);
        /** Saves text as Documents/GrblSender/name and offers to open it. */
        void save(String name, String text, String note);
    }

    // ---------------------------------------------------------------- analysis

    /** Feed move classes. */
    static final int C_NONE = 0, C_CUT = 1, C_CPLUNGE = 2, C_HPLUNGE = 3;

    private static final class Line {
        String raw, code;                 // code = without comments, upper case
        int motion = -1;                  // explicit G0..G3 on this line, -1 none
        int modal;                        // motion in effect
        boolean move, xy, g53, skip;      // skip: G4/G10/G28/G30/G92 – not a regular move
        Double x, y, z, f, s;
        double modalF = Double.NaN, endZ;
        int cls = C_NONE;
        int hole = -1;                    // index into holes when inside a hole body
    }

    /** One drilled hole: lines [from, to] are its pecks (G1 Z plunges with the G0 Z retracts between them). */
    private static final class Hole {
        int from, to;
        String key;                       // depth list, e.g. "-16,-33"
        double retract = Double.NaN, gap = Double.NaN;
    }

    static final class Analysis {
        final List<Line> lines = new ArrayList<>();
        final List<Hole> holes = new ArrayList<>();
        String error;
        int rapids, rapidsXY, rapidsZ;
        double cutF = Double.NaN, cPlungeF = Double.NaN, hPlungeF = Double.NaN, spindle = Double.NaN;
        int cutN, cPlungeN, hPlungeN;
        /** Free-move G0 Z heights (≥ 0, outside holes): value → count, and value → role. */
        final TreeMap<Double, Integer> freeZ = new TreeMap<>(java.util.Collections.reverseOrder());
        final Map<Double, String> freeZRole = new HashMap<>();
        String peckKey;                   // most common hole depth list
        int peckHoles, otherHoles;
        double peckRetract = 1, peckGap = 0.5;
    }

    private static final Pattern WORD = Pattern.compile("([A-Z])\\s*([-+]?(?:\\d+\\.?\\d*|\\.\\d+))");

    static String stripComments(String s) {
        return s.replaceAll("\\([^)]*\\)", " ").replaceAll(";.*$", "");
    }

    static Analysis analyze(String text) {
        Analysis an = new Analysis();
        String[] raw = text.split("\n", -1);
        double x = 0, y = 0, z = 0, f = Double.NaN;
        int modal = 0;
        for (String r : raw) {
            Line l = new Line();
            l.raw = r;
            l.code = stripComments(r).toUpperCase(Locale.US);
            Matcher m = WORD.matcher(l.code);
            List<Double> gs = new ArrayList<>();
            while (m.find()) {
                double v = Double.parseDouble(m.group(2));
                switch (m.group(1).charAt(0)) {
                    case 'G': gs.add(v); break;
                    case 'X': l.x = v; break;
                    case 'Y': l.y = v; break;
                    case 'Z': l.z = v; break;
                    case 'F': l.f = v; break;
                    case 'S': l.s = v; break;
                    default: break;
                }
            }
            for (double g : gs) {
                if (g == 0 || g == 1 || g == 2 || g == 3) l.motion = (int) g;
                else if (g == 53) l.g53 = true;
                else if (g == 4 || g == 10 || g == 28 || g == 30 || g == 92) l.skip = true;
                else if (g == 91 && an.error == null) an.error = "Relative (G91) programs are not supported";
                else if (g == 20 && an.error == null) an.error = "Inch (G20) programs are not supported";
            }
            if (l.motion >= 0) modal = l.motion;
            l.modal = modal;
            if (l.f != null) f = l.f;
            l.modalF = f;
            if (l.s != null && Double.isNaN(an.spindle)) an.spindle = l.s;
            boolean hasAxis = l.x != null || l.y != null || l.z != null;
            if (hasAxis && !l.skip) {
                if (l.g53) {
                    if (l.z != null) z = 1e6;    // machine coordinates: somewhere above the work
                } else {
                    double nx = l.x != null ? l.x : x, ny = l.y != null ? l.y : y;
                    l.move = true;
                    l.xy = Math.abs(nx - x) > 1e-6 || Math.abs(ny - y) > 1e-6 || modal == 2 || modal == 3;
                    x = nx;
                    y = ny;
                    if (l.z != null) z = l.z;
                }
            }
            l.endZ = z;
            an.lines.add(l);
        }
        // Z-only feeds: contour when the next X/Y move is a feed, hole when it is a rapid (or nothing)
        boolean nextXyFeed = false;
        for (int i = an.lines.size() - 1; i >= 0; i--) {
            Line l = an.lines.get(i);
            if (!l.move) continue;
            if (l.modal != 0) l.cls = l.xy ? C_CUT : nextXyFeed ? C_CPLUNGE : C_HPLUNGE;
            if (l.xy) nextXyFeed = l.modal != 0;
        }
        // holes: a run of Z-only moves starting at a hole plunge, up to its last plunge
        for (int i = 0; i < an.lines.size(); i++) {
            Line l = an.lines.get(i);
            if (l.cls != C_HPLUNGE || l.modal != 1) continue;
            int last = i, j = i + 1;
            for (; j < an.lines.size(); j++) {
                Line k = an.lines.get(j);
                if (k.code.trim().isEmpty()) continue;
                if (!k.move || k.xy || k.motion < 0 && k.modal > 1) break;
                if (k.cls == C_HPLUNGE && k.modal == 1) last = j;
                else if (k.modal != 0) break;
            }
            Hole h = new Hole();
            h.from = i;
            h.to = last;
            StringBuilder key = new StringBuilder();
            double prevDepth = Double.NaN;
            boolean retracted = false;
            for (int k = i; k <= last; k++) {
                Line q = an.lines.get(k);
                q.hole = an.holes.size();
                if (!q.move) continue;
                if (q.modal == 1) {
                    if (key.length() > 0) key.append(", ");
                    key.append(fmt(q.endZ));
                    prevDepth = q.endZ;
                    retracted = false;
                } else if (!retracted) {
                    if (Double.isNaN(h.retract)) h.retract = q.endZ;
                    retracted = true;
                } else if (Double.isNaN(h.gap) && !Double.isNaN(prevDepth)) {
                    h.gap = round3(q.endZ - prevDepth);
                }
            }
            h.key = key.toString();
            an.holes.add(h);
            i = last;
        }
        // most common values
        Map<Double, Integer> cut = new HashMap<>(), cpl = new HashMap<>(), hpl = new HashMap<>();
        Map<String, Integer> keys = new HashMap<>();
        for (Line l : an.lines) {
            if (!l.move) continue;
            if (l.modal == 0) {
                an.rapids++;
                if (l.xy) an.rapidsXY++; else an.rapidsZ++;
                if (l.hole < 0 && l.z != null && l.endZ >= 0) an.freeZ.merge(l.endZ, 1, Integer::sum);
            } else if (!Double.isNaN(l.modalF)) {
                Map<Double, Integer> c = l.cls == C_CUT ? cut : l.cls == C_CPLUNGE ? cpl : hpl;
                c.merge(l.modalF, 1, Integer::sum);
            }
        }
        for (Hole h : an.holes) keys.merge(h.key, 1, Integer::sum);
        an.cutF = mode(cut);
        an.cPlungeF = mode(cpl);
        an.hPlungeF = mode(hpl);
        an.cutN = sum(cut);
        an.cPlungeN = sum(cpl);
        an.hPlungeN = sum(hpl);
        int best = 0;
        for (Map.Entry<String, Integer> e : keys.entrySet()) if (e.getValue() > best) { best = e.getValue(); an.peckKey = e.getKey(); }
        an.peckHoles = best;
        an.otherHoles = an.holes.size() - best;
        for (Hole h : an.holes) if (h.key.equals(an.peckKey)) {
            if (!Double.isNaN(h.retract)) an.peckRetract = h.retract;
            if (!Double.isNaN(h.gap)) an.peckGap = h.gap;
            break;
        }
        // role of each free Z height: approach (a feed follows), lift (a feed came before), else travel
        Map<Double, Map<String, Integer>> roles = new HashMap<>();
        int prevMove = -1;
        for (int i = 0; i < an.lines.size(); i++) {
            Line l = an.lines.get(i);
            if (!l.move) continue;
            if (l.modal == 0 && l.hole < 0 && l.z != null && l.endZ >= 0) {
                int next = -1;
                for (int j = i + 1; j < an.lines.size(); j++) if (an.lines.get(j).move) { next = j; break; }
                String role = next >= 0 && an.lines.get(next).modal != 0 ? "approach, before a plunge"
                        : prevMove >= 0 && an.lines.get(prevMove).modal != 0 ? "lift after a cut / hole" : "travel height";
                roles.computeIfAbsent(l.endZ, k -> new HashMap<>()).merge(role, 1, Integer::sum);
            }
            prevMove = i;
        }
        for (Map.Entry<Double, Map<String, Integer>> e : roles.entrySet()) {
            String r = null;
            int n = 0;
            for (Map.Entry<String, Integer> q : e.getValue().entrySet()) if (q.getValue() > n) { n = q.getValue(); r = q.getKey(); }
            an.freeZRole.put(e.getKey(), r);
        }
        if (an.freeZ.size() > 1) an.freeZRole.put(an.freeZ.firstKey(), "clearance, between parts");
        return an;
    }

    private static double mode(Map<Double, Integer> m) {
        double v = Double.NaN;
        int n = 0;
        for (Map.Entry<Double, Integer> e : m.entrySet()) if (e.getValue() > n) { n = e.getValue(); v = e.getKey(); }
        return v;
    }

    private static int sum(Map<Double, Integer> m) {
        int n = 0;
        for (int v : m.values()) n += v;
        return n;
    }

    // ---------------------------------------------------------------- rewrite

    /** New values; NaN / null = unchanged. */
    static final class Changes {
        double rapidXY = Double.NaN, rapidZ = Double.NaN;          // > 0: G0 becomes G1 at this feed
        final Map<Double, Double> freeZ = new HashMap<>();
        double cutF = Double.NaN, cPlungeF = Double.NaN, hPlungeF = Double.NaN, spindle = Double.NaN;
        double[] pecks;                                            // new depth list for the common holes
        double peckRetract = Double.NaN, peckGap = Double.NaN;
        final List<String> notes = new ArrayList<>();
    }

    private static final Pattern G0_WORD = Pattern.compile("G\\s*0*0(?![\\d.])");

    static String rewrite(Analysis an, Changes ch) {
        StringBuilder out = new StringBuilder();
        double emittedF = Double.NaN;
        int emittedMotion = -1;
        boolean regenPecks = ch.pecks != null;
        for (int i = 0; i < an.lines.size(); i++) {
            Line l = an.lines.get(i);
            if (i > 0) out.append('\n');
            if (regenPecks && l.hole >= 0 && an.holes.get(l.hole).from == i && an.holes.get(l.hole).key.equals(an.peckKey)) {
                Hole h = an.holes.get(l.hole);
                double hf = !Double.isNaN(ch.hPlungeF) && l.modalF == an.hPlungeF ? ch.hPlungeF : l.modalF;
                double retract = !Double.isNaN(ch.peckRetract) ? ch.peckRetract : an.peckRetract;
                double gap = !Double.isNaN(ch.peckGap) ? ch.peckGap : an.peckGap;
                // retract / re-entry are rapids: G0, or G1 at the rapid Z limit
                String up = ch.rapidZ > 0 ? "G1 Z" : "G0 Z", upF = ch.rapidZ > 0 ? " F" + fmt(ch.rapidZ) : "";
                for (int k = 0; k < ch.pecks.length; k++) {
                    if (k > 0) {
                        out.append(up).append(fmt(retract)).append(upF).append('\n');
                        out.append(up).append(fmt(ch.pecks[k - 1] + gap)).append('\n');
                    }
                    out.append("G1 Z").append(fmt(ch.pecks[k])).append(" F").append(fmt(hf));
                    if (k < ch.pecks.length - 1) out.append('\n');
                }
                emittedF = hf;
                emittedMotion = 1;
                i = h.to;
                continue;
            }
            String line = l.raw;
            int newMotion = l.modal;
            double wantF = Double.NaN;
            if (l.move) {
                if (l.modal == 0) {
                    double lim = l.xy ? ch.rapidXY : ch.rapidZ;
                    if (lim > 0) { newMotion = 1; wantF = lim; }
                    if (l.hole < 0 && l.z != null && l.endZ >= 0 && ch.freeZ.containsKey(l.endZ))
                        line = replaceWord(line, 'Z', ch.freeZ.get(l.endZ));
                } else {
                    wantF = l.modalF;
                    if (l.cls == C_CUT && !Double.isNaN(ch.cutF) && l.modalF == an.cutF) wantF = ch.cutF;
                    else if (l.cls == C_CPLUNGE && !Double.isNaN(ch.cPlungeF) && l.modalF == an.cPlungeF) wantF = ch.cPlungeF;
                    else if (l.cls == C_HPLUNGE && !Double.isNaN(ch.hPlungeF) && l.modalF == an.hPlungeF) wantF = ch.hPlungeF;
                }
                if (newMotion != l.modal) line = replaceG0(line);
                else if (l.motion < 0 && emittedMotion != newMotion && emittedMotion >= 0) line = "G" + newMotion + " " + line;
                emittedMotion = newMotion;
            } else if (l.motion >= 0) {
                emittedMotion = l.motion;
            }
            if (l.s != null && !Double.isNaN(ch.spindle) && l.s == an.spindle) line = replaceWord(line, 'S', ch.spindle);
            if (l.f != null) {
                if (!Double.isNaN(wantF)) line = replaceWord(line, 'F', wantF);
                emittedF = !Double.isNaN(wantF) ? wantF : l.f;
            } else if (!Double.isNaN(wantF) && wantF != emittedF) {
                line = appendWord(line, " F" + fmt(wantF));
                emittedF = wantF;
            }
            out.append(line);
        }
        return out.toString();
    }

    /** Applies fn to the code parts of a line, leaving (comments) and ;comments alone. */
    private static String onCode(String line, java.util.function.UnaryOperator<String> fn) {
        StringBuilder b = new StringBuilder();
        int i = 0;
        while (i < line.length()) {
            int p = line.indexOf('(', i), s = line.indexOf(';', i);
            int c = p < 0 ? s : s < 0 ? p : Math.min(p, s);
            if (c < 0) { b.append(fn.apply(line.substring(i))); break; }
            b.append(fn.apply(line.substring(i, c)));
            int end = line.charAt(c) == ';' ? line.length() : line.indexOf(')', c);
            end = end < 0 ? line.length() : line.charAt(c) == ';' ? end : end + 1;
            b.append(line, c, end);
            i = end;
        }
        return b.toString();
    }

    private static String replaceWord(String line, char w, double v) {
        Pattern p = Pattern.compile("(?i)(" + w + "\\s*)[-+]?(?:\\d+\\.?\\d*|\\.\\d+)");
        return onCode(line, s -> p.matcher(s).replaceAll("$1" + Matcher.quoteReplacement(fmt(v))));
    }

    private static String replaceG0(String line) {
        boolean[] done = {false};
        return onCode(line, s -> {
            if (done[0]) return s;
            Matcher m = G0_WORD.matcher(s.toUpperCase(Locale.US));
            if (!m.find()) return s;
            done[0] = true;
            return s.substring(0, m.start()) + "G1" + s.substring(m.end());
        });
    }

    /** Adds a word at the end of the code, before a trailing comment. */
    private static String appendWord(String line, String word) {
        int p = line.indexOf('('), s = line.indexOf(';');
        int c = p < 0 ? s : s < 0 ? p : Math.min(p, s);
        if (c < 0) return line.replaceAll("\\s+$", "") + word;
        String code = line.substring(0, c).replaceAll("\\s+$", "");
        return code + word + " " + line.substring(c);
    }

    static String fmt(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-9) return String.valueOf((long) Math.rint(v));
        return String.format(Locale.US, "%.3f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static double round3(double v) { return Math.round(v * 1000) / 1000.0; }

    /** name.nc → name_e1.nc, name_e1.nc → name_e2.nc …, the first that is not in Documents/GrblSender yet. */
    static String editedName(String name, Host host) {
        String base = name == null || name.isEmpty() ? "untitled" : name.replaceAll("\\s*\\(\\d+\\)$", "");   // Android's "x.nc (1)"
        int dot = base.lastIndexOf('.');
        String ext = dot > 0 ? base.substring(dot) : ".nc";
        if (dot > 0) base = base.substring(0, dot);
        int n = 1;
        Matcher m = Pattern.compile("^(.*)_e(\\d+)$").matcher(base);
        if (m.matches()) { base = m.group(1); n = Integer.parseInt(m.group(2)) + 1; }
        while (host.exists(base + "_e" + n + ext)) n++;
        return base + "_e" + n + ext;
    }

    // ---------------------------------------------------------------- start from panel N

    private static final Pattern PART = Pattern.compile("\\(\\s*Part\\s+(\\d+)\\s*/\\s*\\d+", Pattern.CASE_INSENSITIVE);

    /** Panels from "(Part n/N …)" comments; the numbering starts again in each section (holes, then profiles). */
    static final class Panels {
        final List<String> titles = new ArrayList<>();
        final List<Map<Integer, Integer>> parts = new ArrayList<>();   // per section: panel → its comment line
        final List<Integer> header = new ArrayList<>();                // per section: "(== SECTION …)" line or -1
        int preambleEnd = -1;                                          // lines before it are the setup
    }

    static Panels panels(String[] lines) {
        Panels p = new Panels();
        int prev = Integer.MAX_VALUE, pendingHeader = -1;
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            if (stripComments(l).trim().isEmpty() && l.toUpperCase(Locale.US).contains("SECTION")) { pendingHeader = i; continue; }
            Matcher m = PART.matcher(l);
            if (!m.find()) continue;
            int n = Integer.parseInt(m.group(1));
            if (p.parts.isEmpty() || n <= prev) {
                p.parts.add(new java.util.TreeMap<>());
                p.header.add(pendingHeader);
                String t = pendingHeader >= 0 ? lines[pendingHeader].replaceAll("[()=]", "").replaceAll("\\s+", " ").trim() : "";
                p.titles.add(t.isEmpty() ? "Section " + p.parts.size() : t);
                if (p.preambleEnd < 0) p.preambleEnd = pendingHeader >= 0 ? pendingHeader : i;
            }
            pendingHeader = -1;
            p.parts.get(p.parts.size() - 1).put(n, i);
            prev = n;
        }
        return p;
    }

    /**
     * The setup lines (up to the first section), a lift to the homed top, then the program from panel n of
     * section sec to the end. Feed and spindle in effect at that panel are set again.
     */
    static String startFrom(String text, int sec, int n, String srcName) {
        String[] lines = text.split("\n", -1);
        Panels p = panels(lines);
        if (sec >= p.parts.size()) throw new IllegalArgumentException("No such section");
        Integer at = p.parts.get(sec).get(n);
        if (at == null) throw new IllegalArgumentException("Panel " + n + " is not in " + p.titles.get(sec)
                + " (panels " + ((java.util.TreeMap<Integer, Integer>) p.parts.get(sec)).firstKey() + " – "
                + ((java.util.TreeMap<Integer, Integer>) p.parts.get(sec)).lastKey() + ")");
        // feed and spindle in effect at the panel, and spindle state at the end of the setup lines
        double f = Double.NaN, sAt = Double.NaN;
        boolean onAt = false, onPre = false;
        for (int i = 0; i < at; i++) {
            Matcher m = WORD.matcher(stripComments(lines[i]).toUpperCase(Locale.US));
            while (m.find()) {
                char w = m.group(1).charAt(0);
                double v = Double.parseDouble(m.group(2));
                if (w == 'F') f = v;
                else if (w == 'S') sAt = v;
                else if (w == 'M' && (v == 3 || v == 4)) onAt = true;
                else if (w == 'M' && (v == 5 || v == 2 || v == 30)) onAt = false;
            }
            if (i == p.preambleEnd - 1) onPre = onAt;
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < p.preambleEnd; i++) {
            String l = lines[i];
            if (i > 0 && l.trim().equals("%")) continue;
            b.append(l).append('\n');
        }
        b.append("(Restart: from panel ").append(n).append(" of ").append(p.titles.get(sec).replace('(', '[').replace(')', ']'))
                .append(", line ").append(at + 1).append(" of ").append(srcName.replace('(', '[').replace(')', ']')).append(")\n");
        if (onAt && !onPre) {
            b.append("M3").append(Double.isNaN(sAt) ? "" : " S" + fmt(sAt)).append(" (spindle ON)\n");
            b.append("G4 P10 (wait for the spindle to reach speed)\n");
        }
        b.append("G53 G0 Z0 (lift to the homed top before any X/Y move)\n");
        if (!Double.isNaN(f)) b.append("F").append(fmt(f)).append(" (feed in effect at this panel)\n");
        int h = p.header.get(sec);
        if (h >= 0 && h < at) b.append(lines[h]).append('\n');
        for (int i = at; i < lines.length; i++) {
            b.append(lines[i]);
            if (i < lines.length - 1) b.append('\n');
        }
        return b.toString();
    }

    // ---------------------------------------------------------------- page

    private final Context c;
    private final Host host;
    private LinearLayout body;

    private ProgramEdit(Context c, Host host) { this.c = c; this.host = host; }

    static void show(Context c, Host host) {
        String text = host.text();
        if (text == null || text.trim().isEmpty()) { toast(c, "Open a .nc program first"); return; }
        Analysis an = analyze(text);
        if (an.error != null) { toast(c, an.error); return; }
        if (an.rapids == 0 && an.cutN + an.cPlungeN + an.hPlungeN == 0) { toast(c, "No moves found in this program"); return; }
        new ProgramEdit(c, host).build(an);
    }

    private static void toast(Context c, String s) { android.widget.Toast.makeText(c, s, android.widget.Toast.LENGTH_LONG).show(); }

    private void build(Analysis an) {
        ScrollView sv = new ScrollView(c);
        body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(c, 16);
        body.setPadding(pad, Ui.dp(c, 4), pad, pad);
        sv.addView(body);
        String name = host.fileName() == null ? "untitled" : host.fileName();
        body.addView(Ui.text(c, name + " – " + (an.lines.size()) + " lines. Change a value in the New column (empty = keep), "
                + "then Update: an edited copy " + editedName(name, host) + " is saved next to the programs.", 14, Ui.MUTED));
        header();

        Map<String, String> st = host.grblSettings();
        String maxXY = st.containsKey("$110") ? "X " + num(st.get("$110")) + " / Y " + num(st.get("$111")) : "board max rate";
        String maxZ = st.containsKey("$112") ? num(st.get("$112")) : "board max rate";
        section("Rapid moves (G0)");
        EditText rXY = row("Rapid X / Y (" + an.rapidsXY + " moves)", "G0 = " + maxXY, "", "mm/min – G0 → G1 at this F (empty = keep G0)");
        EditText rZ = row("Rapid Z (" + an.rapidsZ + " moves)", "G0 = " + maxZ, "", "mm/min – G0 → G1 at this F (empty = keep G0)");

        section("Free move Z heights (G0 Z, work mm)");
        Map<Double, EditText> zEdits = new LinkedHashMap<>();
        if (an.freeZ.isEmpty()) note("No G0 Z heights above Z0 outside the holes.");
        for (Map.Entry<Double, Integer> e : an.freeZ.entrySet())
            zEdits.put(e.getKey(), row("Z" + fmt(e.getKey()) + " – " + an.freeZRole.get(e.getKey()), "Z" + fmt(e.getKey()),
                    fmt(e.getKey()), e.getValue() + " × G0"));

        section("Contour cut");
        EditText cutF = row("Cut feed (X / Y, arcs)", feedNow(an.cutF, an.cutN), feedVal(an.cutF), "mm/min");
        EditText cplF = row("Plunge feed (pass step down)", feedNow(an.cPlungeF, an.cPlungeN), feedVal(an.cPlungeF), "mm/min");

        section("Hole cut");
        EditText hplF = row("Plunge feed", feedNow(an.hPlungeF, an.hPlungeN), feedVal(an.hPlungeF), "mm/min");

        section("Peck drill");
        EditText pecks = null, retract = null, gap = null;
        if (an.holes.isEmpty()) note("No drilled holes found.");
        else {
            String depth = an.peckKey.substring(an.peckKey.lastIndexOf(' ') + 1);
            pecks = row("Peck depths (Z, last = hole depth)", an.peckKey, an.peckKey,
                    an.peckHoles + " holes" + (an.otherHoles > 0 ? " (" + an.otherHoles + " other holes kept)" : "") + " – depth Z" + depth);
            retract = row("Retract between pecks (Z)", "Z" + fmt(an.peckRetract), fmt(an.peckRetract), "G0 up to clear chips");
            gap = row("Re-entry gap above the last peck", fmt(an.peckGap), fmt(an.peckGap), "mm – G0 back down to depth + gap");
        }

        section("Spindle");
        EditText sp = row("Spindle speed S", Double.isNaN(an.spindle) ? "–" : "S" + fmt(an.spindle),
                Double.isNaN(an.spindle) ? "" : fmt(an.spindle), "rpm");
        if (Double.isNaN(an.spindle)) sp.setEnabled(false);

        section("Start from panel (restart a job)");
        Panels pn = panels(host.text().split("\n", -1));
        android.widget.Spinner secPick = null;
        EditText from = null;
        if (pn.parts.isEmpty()) note("No \"(Part n/N)\" comments in this program, so it has no panel numbers.");
        else {
            if (pn.parts.size() > 1) {
                LinearLayout r = Ui.row(c);
                r.addView(Ui.text(c, "Section", 15, Ui.TEXT), Ui.weight(COLS[0]));
                String[] names = new String[pn.parts.size()];
                for (int i = 0; i < names.length; i++) names[i] = pn.titles.get(i) + "  (" + pn.parts.get(i).size() + " panels)";
                secPick = spinner(names);
                secPick.setSelection(names.length - 1);
                r.addView(secPick, Ui.weight(COLS[1] + COLS[2] + COLS[3]));
                body.addView(r, Ui.margins(c, 0, 3, 0, 3));
            }
            from = row("Start at panel", "whole program", "", "empty = from the start. Numbers are on the toolpath view");
            from.setInputType(InputType.TYPE_CLASS_NUMBER);
            note("Saves the setup lines (pause, spindle on, wait), a G53 Z0 lift, then the program from that panel to "
                    + "the end. Earlier panels and sections are left out. Home and check G54 before you run it.");
        }

        TextView result = Ui.text(c, "", 14, Ui.WARN);
        result.setPadding(0, Ui.dp(c, 10), 0, 0);
        body.addView(result);

        AlertDialog d = new AlertDialog.Builder(c)
                .setTitle("Edit program parameters")
                .setView(sv)
                .setPositiveButton("Update .nc", null)
                .setNegativeButton("Close", null)
                .create();
        final EditText fPecks = pecks, fRetract = retract, fGap = gap, fFrom = from;
        final android.widget.Spinner fSec = secPick;
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            Changes ch = new Changes();
            try {
                ch.rapidXY = optFeed(rXY, Double.NaN, "Rapid X / Y");
                ch.rapidZ = optFeed(rZ, Double.NaN, "Rapid Z");
                if (ch.rapidXY > 0) ch.notes.add("rapid X/Y G0 → G1 F" + fmt(ch.rapidXY));
                if (ch.rapidZ > 0) ch.notes.add("rapid Z G0 → G1 F" + fmt(ch.rapidZ));
                for (Map.Entry<Double, EditText> e : zEdits.entrySet()) {
                    double z = value(e.getValue(), e.getKey(), "Z" + fmt(e.getKey()));
                    if (z < 0.5 || z > 300) throw new IllegalArgumentException("Free move Z" + fmt(e.getKey()) + " must be 0.5 – 300 mm above Z0");
                    if (z != e.getKey()) { ch.freeZ.put(e.getKey(), z); ch.notes.add("G0 Z" + fmt(e.getKey()) + " → Z" + fmt(z)); }
                }
                ch.cutF = feedChange(cutF, an.cutF, "contour cut feed", ch);
                ch.cPlungeF = feedChange(cplF, an.cPlungeF, "contour plunge feed", ch);
                ch.hPlungeF = feedChange(hplF, an.hPlungeF, "hole plunge feed", ch);
                if (fPecks != null) {
                    double[] p = parseList(fPecks.getText().toString());
                    double r = value(fRetract, an.peckRetract, "Retract"), g = value(fGap, an.peckGap, "Re-entry gap");
                    for (int i = 0; i < p.length; i++)
                        if (p[i] >= 0 || i > 0 && p[i] >= p[i - 1]) throw new IllegalArgumentException("Peck depths must be below Z0 and each deeper than the one before, e.g. -11, -22, -33");
                    if (r < 0 || r > 50) throw new IllegalArgumentException("Retract must be Z0 – Z50");
                    if (g < 0 || g > 5) throw new IllegalArgumentException("Re-entry gap must be 0 – 5 mm");
                    boolean same = joinList(p).equals(an.peckKey);
                    if (!same || r != an.peckRetract || g != an.peckGap) {
                        ch.pecks = p;
                        ch.peckRetract = r;
                        ch.peckGap = g;
                        ch.notes.add("pecks " + an.peckKey + " → " + joinList(p) + " (retract Z" + fmt(r) + ", gap " + fmt(g) + ")");
                    }
                }
                double s = Double.isNaN(an.spindle) ? Double.NaN : value(sp, an.spindle, "Spindle");
                if (!Double.isNaN(s) && s != an.spindle) {
                    if (s < 0 || s > 30000) throw new IllegalArgumentException("Spindle S must be 0 – 30000");
                    ch.spindle = s;
                    ch.notes.add("S" + fmt(an.spindle) + " → S" + fmt(s));
                }
            } catch (IllegalArgumentException ex) {
                result.setTextColor(Ui.ERR);
                result.setText(ex.getMessage());
                return;
            }
            int startSec = fSec != null ? fSec.getSelectedItemPosition() : 0, startPanel = -1;
            if (fFrom != null && !fFrom.getText().toString().trim().isEmpty()) {
                try { startPanel = Integer.parseInt(fFrom.getText().toString().trim()); }
                catch (NumberFormatException ex) { result.setTextColor(Ui.ERR); result.setText("Start at panel: give a whole number"); return; }
            }
            boolean edited = !ch.notes.isEmpty();
            if (!edited && startPanel < 0) { result.setTextColor(Ui.WARN); result.setText("Nothing changed – edit a value in the New column first."); return; }
            String out = edited ? rewrite(an, ch) : host.text();
            String src = host.fileName() == null ? "untitled" : host.fileName().replaceAll("\\s*\\(\\d+\\)$", "");
            if (startPanel >= 0) {
                try { out = startFrom(out, startSec, startPanel, src); }
                catch (IllegalArgumentException ex) { result.setTextColor(Ui.ERR); result.setText(ex.getMessage()); return; }
                ch.notes.add("starts at panel " + startPanel + " of " + pn.titles.get(startSec));
            }
            String note = String.join("; ", ch.notes);
            if (edited) out = addHeader(out, "(Edited in Grbl Sender from " + src.replace('(', '[').replace(')', ']') + ": "
                    + note.replace('(', '[').replace(')', ']') + ")");
            String newName = startPanel >= 0 ? fromName(src, startPanel, host) : editedName(src, host);
            String finalOut = out;
            Runnable save = () -> {
                d.dismiss();
                host.save(newName, finalOut, "Changes: " + note + ".");
            };
            // a deeper hole than anything the program cut so far needs a second look (flute length, spoilboard)
            double deepest = 0;
            for (Line l : an.lines) if (l.move && l.modal != 0) deepest = Math.min(deepest, l.endZ);
            if (ch.pecks != null && ch.pecks[ch.pecks.length - 1] < deepest - 1e-6) {
                new AlertDialog.Builder(c)
                        .setTitle("Deeper than the program")
                        .setMessage("The new hole depth Z" + fmt(ch.pecks[ch.pecks.length - 1]) + " is deeper than the deepest cut in "
                                + src + " (Z" + fmt(deepest) + "). Check the endmill's flute length and the spoilboard.\n\nSave anyway?")
                        .setPositiveButton("Save", (x2, w2) -> save.run())
                        .setNegativeButton("Cancel", null)
                        .show();
                return;
            }
            save.run();
        }));
        d.show();
        android.view.Window w = d.getWindow();
        if (w != null) w.setLayout((int) (c.getResources().getDisplayMetrics().widthPixels * 0.9), android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /** Puts a comment after the leading "%" line (if any). */
    private static String addHeader(String text, String comment) {
        if (text.startsWith("%")) {
            int nl = text.indexOf('\n');
            return nl < 0 ? text + "\n" + comment : text.substring(0, nl + 1) + comment + "\n" + text.substring(nl + 1);
        }
        return comment + "\n" + text;
    }

    private static String num(String s) {
        try { return fmt(Double.parseDouble(s)); } catch (NumberFormatException e) { return s; }
    }

    private static String feedNow(double f, int n) { return Double.isNaN(f) ? "–" : "F" + fmt(f) + "  (" + n + " moves)"; }

    private static String feedVal(double f) { return Double.isNaN(f) ? "" : fmt(f); }

    private static double value(EditText e, double def, String what) {
        String s = e.getText().toString().trim().replace(',', '.');
        if (s.isEmpty()) return def;
        try { return Double.parseDouble(s); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException(what + ": \"" + s + "\" is not a number"); }
    }

    private static double optFeed(EditText e, double def, String what) {
        double v = value(e, def, what);
        if (!Double.isNaN(v) && (v < 10 || v > 20000)) throw new IllegalArgumentException(what + " must be 10 – 20000 mm/min");
        return v;
    }

    private static double feedChange(EditText e, double old, String what, Changes ch) {
        if (Double.isNaN(old)) return Double.NaN;
        double v = optFeed(e, old, what);
        if (v == old) return Double.NaN;
        ch.notes.add(what + " F" + fmt(old) + " → F" + fmt(v));
        return v;
    }

    private static double[] parseList(String s) {
        String[] parts = s.trim().split("[\\s,;]+");
        List<Double> v = new ArrayList<>();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            try { v.add(Double.parseDouble(p)); }
            catch (NumberFormatException ex) { throw new IllegalArgumentException("Peck depths: \"" + p + "\" is not a number"); }
        }
        if (v.isEmpty()) throw new IllegalArgumentException("Peck depths: give at least the hole depth, e.g. -33");
        double[] a = new double[v.size()];
        for (int i = 0; i < a.length; i++) a[i] = v.get(i);
        return a;
    }

    private static String joinList(double[] p) {
        StringBuilder b = new StringBuilder();
        for (double v : p) { if (b.length() > 0) b.append(", "); b.append(fmt(v)); }
        return b.toString();
    }

    /** name.nc → name_from12.nc (then …_from12_e1.nc if that exists). */
    static String fromName(String name, int panel, Host host) {
        String base = name == null || name.isEmpty() ? "untitled" : name.replaceAll("\\s*\\(\\d+\\)$", "");
        int dot = base.lastIndexOf('.');
        String ext = dot > 0 ? base.substring(dot) : ".nc";
        if (dot > 0) base = base.substring(0, dot);
        base = base.replaceAll("(_from\\d+)?(_e\\d+)?$", "");
        String cand = base + "_from" + panel + ext;
        return host.exists(cand) ? editedName(cand, host) : cand;
    }

    // ---------------------------------------------------------------- table widgets

    private android.widget.Spinner spinner(String[] items) {
        android.widget.Spinner sp = new android.widget.Spinner(c);
        android.widget.ArrayAdapter<String> ad = new android.widget.ArrayAdapter<String>(c, android.R.layout.simple_spinner_item, items) {
            @Override public android.view.View getView(int pos, android.view.View cv, android.view.ViewGroup parent) {
                TextView t = (TextView) super.getView(pos, cv, parent);
                t.setTextColor(Ui.TEXT);
                t.setTextSize(15);
                return t;
            }
        };
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
        return sp;
    }

    private static final float[] COLS = {2.2f, 1.4f, 1f, 2f};

    private void header() {
        LinearLayout r = Ui.row(c);
        String[] h = {"Parameter", "Now", "New", ""};
        for (int i = 0; i < 4; i++) {
            TextView t = Ui.text(c, h[i], 12, Ui.MUTED);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            r.addView(t, Ui.weight(COLS[i]));
        }
        body.addView(r, Ui.margins(c, 0, 10, 0, 0));
    }

    private void section(String s) { body.addView(Ui.label(c, s)); }

    private void note(String s) { body.addView(Ui.text(c, s, 13, Ui.MUTED)); }

    private EditText row(String label, String now, String value, String unit) {
        LinearLayout r = Ui.row(c);
        r.addView(Ui.text(c, label, 15, Ui.TEXT), Ui.weight(COLS[0]));
        TextView n = Ui.text(c, now, 14, Ui.MUTED);
        n.setTypeface(Typeface.MONOSPACE);
        r.addView(n, Ui.weight(COLS[1]));
        EditText e = new EditText(c);
        e.setText(value);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT);
        e.setSelectAllOnFocus(true);
        e.setTextColor(Ui.TEXT);
        e.setTextSize(15);
        e.setGravity(android.view.Gravity.CENTER);
        e.setBackgroundResource(R.drawable.input_bg);
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(0, Ui.dp(c, 40), COLS[2]);
        ep.setMarginEnd(Ui.dp(c, 8));
        r.addView(e, ep);
        r.addView(Ui.text(c, unit, 13, Ui.MUTED), Ui.weight(COLS[3]));
        body.addView(r, Ui.margins(c, 0, 3, 0, 3));
        return e;
    }
}
