package org.dnaboy.grblsender;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * cnc.job log: one record per run with its timeline (started, paused, resumed, ended), kept in
 * the app's files (jobs.json, newest {@link #KEEP} jobs) and sent to the backoffice by the
 * remote-control sync loop. Events stay marked unsynced until the server has stored them.
 */
final class JobLog {

    static final int KEEP = 150;

    static final class Event {
        String uid, event, detail;
        long ts;
        int line;
        boolean synced;
    }

    static final class Job {
        String id, file, source, result;
        int lines, lastLine, errors;
        long estSec, started, ended, pausedMs;
        final List<Event> events = new ArrayList<>();
    }

    private static JobLog instance;
    private final File store;
    private final List<Job> jobs = new ArrayList<>();      // newest first

    static synchronized JobLog get(Context ctx) {
        if (instance == null) instance = new JobLog(new File(ctx.getFilesDir(), "jobs.json"));
        return instance;
    }

    private JobLog(File store) {
        this.store = store;
        load();
    }

    synchronized Job start(String file, int lines, long estSec, String source) {
        Job j = new Job();
        j.id = UUID.randomUUID().toString();
        j.file = file;
        j.lines = lines;
        j.estSec = estSec;
        j.source = source;
        j.started = System.currentTimeMillis();
        jobs.add(0, j);
        while (jobs.size() > KEEP) jobs.remove(jobs.size() - 1);
        addEvent(j, "started", 0, source.equals("tablet") ? null : "started from the " + source);
        return j;
    }

    synchronized void event(Job j, String event, int line, String detail) {
        if (j == null) return;
        j.lastLine = line;
        addEvent(j, event, line, detail);
    }

    synchronized void end(Job j, String result, int line, int errors, long pausedMs, String detail) {
        if (j == null || j.ended != 0) return;
        j.ended = System.currentTimeMillis();
        j.result = result;
        j.lastLine = line;
        j.errors = errors;
        j.pausedMs = pausedMs;
        addEvent(j, "ended", line, result + (detail != null ? " – " + detail : ""));
    }

    private void addEvent(Job j, String event, int line, String detail) {
        Event e = new Event();
        e.uid = UUID.randomUUID().toString();
        e.event = event;
        e.ts = System.currentTimeMillis();
        e.line = line;
        e.detail = detail;
        j.events.add(e);
        save();
    }

    synchronized List<Job> jobs() { return new ArrayList<>(jobs); }

    synchronized void clear() {
        jobs.clear();
        save();
    }

    /** Unsynced events in the shape POST /api/device/sync expects, oldest first. */
    synchronized JSONArray unsynced(int max) {
        JSONArray out = new JSONArray();
        try {
            for (int i = jobs.size() - 1; i >= 0; i--) {
                Job j = jobs.get(i);
                for (Event e : j.events) {
                    if (e.synced) continue;
                    if (out.length() >= max) return out;
                    JSONObject job = new JSONObject().put("id", j.id).put("file", j.file).put("lines", j.lines)
                            .put("estSec", j.estSec).put("source", j.source);
                    JSONObject o = new JSONObject().put("uid", e.uid).put("job", job).put("event", e.event)
                            .put("ts", e.ts).put("line", e.line).put("detail", e.detail);
                    if (e.event.equals("ended")) o.put("result", j.result).put("errors", j.errors).put("pausedMs", j.pausedMs);
                    out.put(o);
                }
            }
        } catch (Exception ignored) { }
        return out;
    }

    synchronized void markSynced(Set<String> uids) {
        if (uids.isEmpty()) return;
        for (Job j : jobs) for (Event e : j.events) if (uids.contains(e.uid)) e.synced = true;
        save();
    }

    // ---------------------------------------------------------------- storage

    private void save() {
        try {
            JSONArray a = new JSONArray();
            for (Job j : jobs) {
                JSONArray ev = new JSONArray();
                for (Event e : j.events) {
                    ev.put(new JSONObject().put("uid", e.uid).put("event", e.event).put("ts", e.ts)
                            .put("line", e.line).put("detail", e.detail).put("synced", e.synced));
                }
                a.put(new JSONObject().put("id", j.id).put("file", j.file).put("source", j.source).put("result", j.result)
                        .put("lines", j.lines).put("lastLine", j.lastLine).put("errors", j.errors).put("estSec", j.estSec)
                        .put("started", j.started).put("ended", j.ended).put("pausedMs", j.pausedMs).put("events", ev));
            }
            File tmp = new File(store.getPath() + ".tmp");
            try (FileOutputStream os = new FileOutputStream(tmp)) {
                os.write(a.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(store)) RemoteLog.w("jobs", "could not save jobs.json");
        } catch (Exception e) {
            RemoteLog.e("jobs", "save failed", e);
        }
    }

    private void load() {
        if (!store.exists()) return;
        try (FileInputStream in = new FileInputStream(store)) {
            byte[] buf = new byte[(int) store.length()];
            int n = 0;
            while (n < buf.length) {
                int r = in.read(buf, n, buf.length - n);
                if (r < 0) break;
                n += r;
            }
            JSONArray a = new JSONArray(new String(buf, 0, n, StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Job j = new Job();
                j.id = o.getString("id");
                j.file = o.optString("file", null);
                j.source = o.optString("source", "tablet");
                j.result = o.isNull("result") ? null : o.optString("result", null);
                j.lines = o.optInt("lines");
                j.lastLine = o.optInt("lastLine");
                j.errors = o.optInt("errors");
                j.estSec = o.optLong("estSec");
                j.started = o.optLong("started");
                j.ended = o.optLong("ended");
                j.pausedMs = o.optLong("pausedMs");
                JSONArray ev = o.optJSONArray("events");
                for (int k = 0; ev != null && k < ev.length(); k++) {
                    JSONObject x = ev.getJSONObject(k);
                    Event e = new Event();
                    e.uid = x.getString("uid");
                    e.event = x.getString("event");
                    e.ts = x.optLong("ts");
                    e.line = x.optInt("line");
                    e.detail = x.isNull("detail") ? null : x.optString("detail", null);
                    e.synced = x.optBoolean("synced");
                    j.events.add(e);
                }
                jobs.add(j);
            }
        } catch (Exception e) {
            RemoteLog.e("jobs", "jobs.json unreadable – starting a new log", e);
        }
    }
}
