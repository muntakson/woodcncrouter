package org.dnaboy.grblsender;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory debug trace for Settings → Debug console: the backoffice link (SRV: log upload,
 * remote-control sync, update checks) and the CNC link (CNC: USB, firmware banner, every line
 * sent and received). Thread-safe; keeps the last {@link #MAX} entries.
 */
final class DebugLog {

    static final String SRV = "SRV", CNC = "CNC";
    static final int MAX = 3000;

    static final class Entry {
        final long seq, ts;
        final String src;
        final char level;      // I, W, E, D (verbose)
        final String text;
        Entry(long seq, long ts, String src, char level, String text) {
            this.seq = seq; this.ts = ts; this.src = src; this.level = level; this.text = text;
        }
    }

    interface Listener { void onEntry(Entry e); }

    /** Also record every sync round-trip and every Grbl status report (very chatty). */
    static volatile boolean verbose;

    private static final ArrayList<Entry> entries = new ArrayList<>();
    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private static long seq;

    private DebugLog() { }

    static void srv(String text) { add(SRV, 'I', text); }
    static void srvWarn(String text) { add(SRV, 'W', text); }
    static void srvErr(String text) { add(SRV, 'E', text); }
    static void srvVerbose(String text) { if (verbose) add(SRV, 'D', text); }
    static void cnc(String text) { add(CNC, 'I', text); }
    static void cncErr(String text) { add(CNC, 'E', text); }
    static void cncVerbose(String text) { if (verbose) add(CNC, 'D', text); }

    static void add(String src, char level, String text) {
        Entry e;
        synchronized (entries) {
            e = new Entry(++seq, System.currentTimeMillis(), src, level, text);
            entries.add(e);
            if (entries.size() > MAX) entries.subList(0, entries.size() - MAX + 500).clear();
        }
        for (Listener l : listeners) l.onEntry(e);
    }

    /** Entries after {@code afterSeq} (optionally only one source), oldest first. */
    static List<Entry> since(long afterSeq, String src, int max) {
        ArrayList<Entry> out = new ArrayList<>();
        synchronized (entries) {
            for (int i = entries.size() - 1; i >= 0 && out.size() < max; i--) {
                Entry e = entries.get(i);
                if (e.seq <= afterSeq) break;
                if (src == null || src.equals(e.src)) out.add(e);
            }
        }
        java.util.Collections.reverse(out);
        return out;
    }

    static void clear() { synchronized (entries) { entries.clear(); } }

    static void addListener(Listener l) { listeners.add(l); }
    static void removeListener(Listener l) { listeners.remove(l); }
}
