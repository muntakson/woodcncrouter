package org.dnaboy.grblsender;

import java.util.Arrays;

/**
 * Run-time estimate that follows Grbl 1.1's motion planner rather than length / feed:
 * <ul>
 *   <li>feed and rapid speeds capped per axis by $110..$112 (max rate)</li>
 *   <li>trapezoidal acceleration per move with $120..$122</li>
 *   <li>corner speeds from the junction-deviation model ($11)</li>
 *   <li>arcs split into chords like Grbl ($12); the chord-to-chord junctions cap the
 *       speed on tight arcs (e.g. small-radius helical hole boring)</li>
 *   <li>full stops at M0/M1/M2/M30, spindle changes and dwells (G4 P adds its time)</li>
 * </ul>
 * Time spent waiting at M0 pauses is not included.
 */
final class TimeEstimator {

    /** Machine motion settings; defaults are Grbl's factory values. */
    static final class Limits {
        final double[] maxRate = {500, 500, 500};   // mm/min, $110..$112
        final double[] accel = {10, 10, 10};         // mm/s^2,  $120..$122
        double junctionDeviation = 0.010;            // mm, $11
        double arcTolerance = 0.002;                 // mm, $12
        boolean fromMachine;

        Limits copy() {
            Limits l = new Limits();
            System.arraycopy(maxRate, 0, l.maxRate, 0, 3);
            System.arraycopy(accel, 0, l.accel, 0, 3);
            l.junctionDeviation = junctionDeviation;
            l.arcTolerance = arcTolerance;
            l.fromMachine = fromMachine;
            return l;
        }

        /** Apply one "$n=value" line from Grbl's $$ report; returns true if it was a motion setting. */
        boolean apply(String line) {
            int eq = line.indexOf('=');
            if (!line.startsWith("$") || eq < 2) return false;
            int n;
            double v;
            try {
                n = Integer.parseInt(line.substring(1, eq));
                v = Double.parseDouble(line.substring(eq + 1).trim());
            } catch (NumberFormatException e) {
                return false;
            }
            if (n == 11) junctionDeviation = v;
            else if (n == 12) arcTolerance = v;
            else if (n >= 110 && n <= 112) maxRate[n - 110] = v;
            else if (n >= 120 && n <= 122) accel[n - 120] = v;
            else return false;
            fromMachine = true;
            return true;
        }
    }

    private static final double MIN_JUNCTION_SPEED = 0.0;

    private final Limits lim;
    private int n;
    private double[] len = new double[256];       // mm
    private double[] cruise2 = new double[256];   // (mm/s)^2
    private double[] acc = new double[256];       // mm/s^2
    private double[] maxEntry2 = new double[256];
    private int[] line = new int[256];
    private boolean stopBeforeNext = true;        // the program starts from rest
    private double[] prevEndTangent;
    private double prevCruise2, prevAcc;
    private final int lineCount;
    private final double[] dwellPerLine;
    boolean feedCapped;                            // some feed exceeded the machine's max rate
    double maxFeedRequested;

    TimeEstimator(Limits lim, int lineCountHint) {
        this.lim = lim;
        this.lineCount = lineCountHint;
        this.dwellPerLine = new double[Math.max(1, lineCountHint)];
    }

    /** Next move starts from rest (M0, spindle change, dwell ...). */
    void stop() { stopBeforeNext = true; }

    void dwell(int programLine, double seconds) {
        stop();
        if (programLine < dwellPerLine.length && seconds > 0) dwellPerLine[programLine] += seconds;
    }

    /** Straight move; {@code feedMmMin <= 0} means rapid (G0). */
    void line(int programLine, double dx, double dy, double dz, double feedMmMin) {
        double L = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (L < 1e-9) return;
        double[] u = {dx / L, dy / L, dz / L};
        double rate = axisLimited(lim.maxRate, u) / 60.0;
        double v = feedMmMin > 0 ? Math.min(feedMmMin / 60.0, rate) : rate;
        noteFeed(feedMmMin, rate);
        add(programLine, L, v * v, axisLimited(lim.accel, u), u, u);
    }

    /**
     * Arc in the XY plane (optionally helical). Angles in radians; {@code sweep} is signed
     * (positive = CCW).
     */
    void arc(int programLine, double cx, double cy, double r, double startAngle, double sweep, double dz,
             double feedMmMin) {
        double arcLen = Math.abs(sweep) * r;
        double L = Math.hypot(arcLen, dz);
        if (L < 1e-9) return;
        double xyFrac = arcLen / L, zFrac = Math.abs(dz) / L;
        double xyRate = Math.min(lim.maxRate[0], lim.maxRate[1]), xyAcc = Math.min(lim.accel[0], lim.accel[1]);
        double rate = Math.min(xyFrac > 1e-9 ? xyRate / xyFrac : Double.MAX_VALUE,
                zFrac > 1e-9 ? lim.maxRate[2] / zFrac : Double.MAX_VALUE) / 60.0;
        double a = Math.min(xyFrac > 1e-9 ? xyAcc / xyFrac : Double.MAX_VALUE,
                zFrac > 1e-9 ? lim.accel[2] / zFrac : Double.MAX_VALUE);
        double v = feedMmMin > 0 ? Math.min(feedMmMin / 60.0, rate) : rate;
        noteFeed(feedMmMin, rate);

        // Grbl splits the arc into chords (mc_arc) and plans each chord junction
        double tol = lim.arcTolerance;
        int segs = (int) Math.floor(Math.abs(0.5 * sweep * r) / Math.sqrt(Math.max(1e-12, tol * (2 * r - tol))));
        if (segs >= 1) {
            double theta = sweep / segs;
            double chord = 2 * r * Math.sin(Math.abs(theta) / 2);
            double h = dz / segs;
            double cosPhi = (chord * chord * Math.cos(theta) + h * h) / (chord * chord + h * h);
            double vj2 = junctionSpeed2(-cosPhi, a);
            v = Math.min(v, Math.sqrt(vj2));
        }
        double dir = Math.signum(sweep);
        double a0 = startAngle, a1 = startAngle + sweep;
        double[] t0 = tangent(a0, dir, arcLen, dz, L);
        double[] t1 = tangent(a1, dir, arcLen, dz, L);
        add(programLine, L, v * v, a, t0, t1);
    }

    private static double[] tangent(double angle, double dir, double arcLen, double dz, double L) {
        double k = arcLen / L;
        return new double[]{-Math.sin(angle) * dir * k, Math.cos(angle) * dir * k, dz / L};
    }

    private void noteFeed(double feed, double rateMmS) {
        if (feed > 0) {
            maxFeedRequested = Math.max(maxFeedRequested, feed);
            if (feed / 60.0 > rateMmS + 1e-6) feedCapped = true;
        }
    }

    private static double axisLimited(double[] perAxis, double[] u) {
        double v = Double.MAX_VALUE;
        for (int i = 0; i < 3; i++) if (Math.abs(u[i]) > 1e-9) v = Math.min(v, perAxis[i] / Math.abs(u[i]));
        return v;
    }

    /** Grbl junction-deviation corner speed^2; {@code cosTheta} = -dot(prevUnit, unit). */
    private double junctionSpeed2(double cosTheta, double a) {
        if (cosTheta > 0.999999) return MIN_JUNCTION_SPEED * MIN_JUNCTION_SPEED;   // reversal
        if (cosTheta < -0.999999) return Double.MAX_VALUE;                          // straight on
        double s = Math.sqrt(0.5 * (1.0 - cosTheta));
        return Math.max(MIN_JUNCTION_SPEED * MIN_JUNCTION_SPEED, a * lim.junctionDeviation * s / (1.0 - s));
    }

    private void add(int programLine, double L, double v2, double a, double[] tStart, double[] tEnd) {
        if (n == len.length) {
            int c = n * 2;
            len = Arrays.copyOf(len, c);
            cruise2 = Arrays.copyOf(cruise2, c);
            acc = Arrays.copyOf(acc, c);
            maxEntry2 = Arrays.copyOf(maxEntry2, c);
            line = Arrays.copyOf(line, c);
        }
        double e2;
        if (stopBeforeNext || prevEndTangent == null) {
            e2 = 0;
        } else {
            double dot = prevEndTangent[0] * tStart[0] + prevEndTangent[1] * tStart[1] + prevEndTangent[2] * tStart[2];
            e2 = Math.min(junctionSpeed2(-dot, Math.min(a, prevAcc)), Math.min(v2, prevCruise2));
        }
        len[n] = L;
        cruise2[n] = v2;
        acc[n] = a;
        maxEntry2[n] = e2;
        line[n] = programLine;
        n++;
        stopBeforeNext = false;
        prevEndTangent = tEnd;
        prevCruise2 = v2;
        prevAcc = a;
    }

    /**
     * Plans all moves and returns cumulative seconds at the end of each program line
     * (index = program line), plus the total in the last+1 element.
     */
    double[] finish() {
        double[] entry2 = Arrays.copyOf(maxEntry2, n);
        // backward pass: be able to stop at the end / reach the next entry speed
        double next2 = 0;
        for (int k = n - 1; k >= 0; k--) {
            entry2[k] = Math.min(entry2[k], next2 + 2 * acc[k] * len[k]);
            next2 = entry2[k];
        }
        // forward pass: cannot accelerate more than the move allows
        for (int k = 0; k + 1 < n; k++) {
            if (entry2[k + 1] > 0) entry2[k + 1] = Math.min(entry2[k + 1], entry2[k] + 2 * acc[k] * len[k]);
        }
        double[] perLine = new double[lineCount + 1];
        for (int k = 0; k < n; k++) {
            double v0 = Math.sqrt(entry2[k]);
            double v1 = k + 1 < n ? Math.sqrt(entry2[k + 1]) : 0;
            double t = trapezoidTime(len[k], v0, v1, Math.sqrt(cruise2[k]), acc[k]);
            if (line[k] < lineCount) perLine[line[k]] += t;
        }
        double sum = 0;
        for (int i = 0; i < lineCount; i++) {
            sum += perLine[i] + (i < dwellPerLine.length ? dwellPerLine[i] : 0);
            perLine[i] = sum;
        }
        perLine[lineCount] = sum;
        return perLine;
    }

    static double trapezoidTime(double L, double v0, double v1, double vc, double a) {
        vc = Math.max(vc, Math.max(v0, v1));
        if (a <= 0 || vc <= 0) return vc > 0 ? L / vc : 0;
        double da = (vc * vc - v0 * v0) / (2 * a);
        double dd = (vc * vc - v1 * v1) / (2 * a);
        if (da + dd <= L) return (vc - v0) / a + (vc - v1) / a + (L - da - dd) / vc;
        // never reaches cruise speed: triangular profile
        double vp = Math.sqrt(Math.max(0, (2 * a * L + v0 * v0 + v1 * v1) / 2));
        return Math.max(0, (vp - v0) / a) + Math.max(0, (vp - v1) / a);
    }
}
