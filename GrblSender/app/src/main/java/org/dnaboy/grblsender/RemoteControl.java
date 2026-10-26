package org.dnaboy.grblsender;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Link to the backoffice for remote monitoring and control. A background loop posts
 * POST /api/device/sync with the live machine status, new CNC console lines, results of
 * finished commands and unsynced job-log events; the server answers with commands queued
 * through its HTTP API (/api/cnc/command), holding the request open up to {@link #WAIT_MS}
 * so a command reaches the tablet as soon as it is issued.
 *
 * <p>The status carries the SHA-256 of the loaded program ({@code progHash}). When the server does
 * not have that program yet its reply says {@code program.want}, and the text is uploaded once to
 * /api/device/program, so the dashboard draws exactly what the tablet streams.
 */
final class RemoteControl {

    interface Result { void done(boolean ok, String msg); }

    /** Runs a command on the UI thread and reports back through {@code r} (exactly once). */
    interface Host { void execute(JSONObject cmd, Result r); }

    interface StateListener { void onState(boolean online, String detail); }

    private static final int WAIT_MS = 1500;
    private static final long MAX_COMMAND_AGE_MS = 20000;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Host host;
    private final JobLog jobs;
    private final List<JSONObject> results = new ArrayList<>();
    private volatile JSONObject status = new JSONObject();
    private volatile StateListener stateListener;
    private volatile boolean running;
    private volatile boolean online;
    private volatile String lastError;
    private volatile long lastOkMs;
    private volatile int gen;               // bumped by stop(): an older loop still blocked in HTTP then exits
    private long consoleSeq;
    private volatile CameraStreamer camera;
    private volatile String progHash, progName, progText;   // the loaded program, shared on request
    private volatile String progUploading;                  // hash of an upload in progress
    private volatile long progRetryAtMs;

    RemoteControl(Host host, JobLog jobs) {
        this.host = host;
        this.jobs = jobs;
    }

    void setStatus(JSONObject s) { status = s; }
    void setCamera(CameraStreamer c) { camera = c; }

    /** The program Load produced (null when none); its text is uploaded if the backoffice asks. */
    void setProgram(String hash, String name, String text) {
        progText = text;
        progName = name;
        progHash = hash;
        progRetryAtMs = 0;
    }

    static String sha256(String text) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
    void setStateListener(StateListener l) { stateListener = l; }
    boolean isOnline() { return online; }
    String lastError() { return lastError; }
    long lastOkMs() { return lastOkMs; }

    synchronized void start() {
        if (running) return;
        running = true;
        List<DebugLog.Entry> recent = DebugLog.since(0, DebugLog.CNC, 1);
        consoleSeq = recent.isEmpty() ? 0 : recent.get(recent.size() - 1).seq - 1;
        final int g = ++gen;
        Thread t = new Thread(() -> loop(g), "remote-sync");
        t.setDaemon(true);
        t.start();
    }

    synchronized void stop() {
        running = false;
        gen++;
        setOnline(false, "stopped");
    }

    /** Restart the loop, e.g. after the server URL changed. */
    void restart() {
        stop();
        start();
    }

    private void loop(int g) {
        int backoffMs = 2000;
        DebugLog.srv("Remote link: connecting to " + RemoteLog.server() + " …");
        while (running && g == gen) {
            List<JSONObject> sentResults;
            synchronized (results) {
                sentResults = new ArrayList<>(results);
                results.clear();
            }
            JSONArray events = jobs.unsynced(100);
            List<DebugLog.Entry> lines = DebugLog.since(consoleSeq, DebugLog.CNC, 300);
            long t0 = System.currentTimeMillis();
            try {
                JSONArray console = new JSONArray();
                for (DebugLog.Entry e : lines) {
                    if (e.level == 'D') continue;
                    console.put(new JSONObject().put("ts", e.ts).put("src", e.src).put("text", e.text));
                }
                JSONObject body = new JSONObject()
                        .put("device", RemoteLog.device())
                        .put("status", status)
                        .put("console", console)
                        .put("results", new JSONArray(sentResults))
                        .put("jobEvents", events)
                        .put("camera", camera != null ? camera.statusJson() : null)
                        .put("wait", sentResults.isEmpty() ? WAIT_MS : 0);
                JSONObject resp = new JSONObject(RemoteLog.postJson("/api/device/sync", body.toString(), WAIT_MS + 20000));
                if (!lines.isEmpty()) consoleSeq = lines.get(lines.size() - 1).seq;
                Set<String> uids = new HashSet<>();
                for (int i = 0; i < events.length(); i++) uids.add(events.getJSONObject(i).getString("uid"));
                jobs.markSynced(uids);
                if (uids.size() > 0) DebugLog.srv("Job log: " + uids.size() + " event(s) stored on the backoffice");
                lastOkMs = System.currentTimeMillis();
                setOnline(true, null);
                backoffMs = 2000;
                DebugLog.srvVerbose("sync OK in " + (lastOkMs - t0) + " ms");
                if (g != gen) break;
                CameraStreamer cs = camera;
                if (cs != null) cs.onRemoteWish(resp.optJSONObject("camera"));
                shareProgram(resp.optJSONObject("program"));
                JSONArray cmds = resp.optJSONArray("commands");
                for (int i = 0; cmds != null && i < cmds.length(); i++) dispatch(cmds.getJSONObject(i));
            } catch (Exception e) {
                synchronized (results) { results.addAll(0, sentResults); }   // try again with the next sync
                String msg = e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
                if (!running || g != gen) break;
                if (online || !msg.equals(lastError)) DebugLog.srvErr("Remote link failed: " + msg + " – retrying in " + backoffMs / 1000 + " s");
                lastError = msg;
                setOnline(false, msg);
                try { Thread.sleep(backoffMs); } catch (InterruptedException ie) { break; }
                backoffMs = Math.min(backoffMs * 2, 30000);
            }
        }
    }

    /** Upload the loaded program when the sync reply asks for it (one upload at a time, off the sync loop). */
    private void shareProgram(JSONObject wish) {
        final String want = wish != null ? wish.optString("want", "") : "";
        final String hash = progHash, name = progName, text = progText;
        if (want.isEmpty() || hash == null || text == null || !want.equals(hash)) return;
        if (progUploading != null || System.currentTimeMillis() < progRetryAtMs) return;
        progUploading = hash;
        Thread t = new Thread(() -> {
            try {
                RemoteLog.postProgram(hash, name, text);
                DebugLog.srv("Program shared with the backoffice: " + (name != null ? name : "untitled")
                        + " (" + (text.length() + 1023) / 1024 + " KB)");
            } catch (Exception e) {
                progRetryAtMs = System.currentTimeMillis() + 15000;
                DebugLog.srvErr("Program upload failed: " + e.getClass().getSimpleName()
                        + (e.getMessage() != null ? ": " + e.getMessage() : "") + " – retrying in 15 s");
            } finally {
                progUploading = null;
            }
        }, "program-upload");
        t.setDaemon(true);
        t.start();
    }

    private void dispatch(JSONObject c) {
        final String id = c.optString("id");
        final String cmd = c.optString("cmd");
        String args = c.has("file") ? " file=" + c.optString("file") : c.has("line") ? " line=" + c.optString("line") : "";
        DebugLog.srv("⇩ Remote command: " + cmd + args);
        if (c.optLong("age") > MAX_COMMAND_AGE_MS) {
            addResult(id, false, "too old (" + c.optLong("age") / 1000 + " s) – not executed");
            return;
        }
        ui.post(() -> {
            final boolean[] once = {false};
            try {
                host.execute(c, (ok, msg) -> {
                    if (once[0]) return;
                    once[0] = true;
                    addResult(id, ok, msg);
                });
            } catch (Exception e) {
                if (!once[0]) { once[0] = true; addResult(id, false, "app error: " + e); }
                RemoteLog.e("remote", "command " + cmd + " failed", e);
            }
        });
    }

    private void addResult(String id, boolean ok, String msg) {
        DebugLog.srv((ok ? "⇧ done: " : "⇧ refused: ") + msg);
        try {
            JSONObject r = new JSONObject().put("id", id).put("ok", ok).put("msg", msg);
            synchronized (results) { results.add(r); }
        } catch (Exception ignored) { }
    }

    private void setOnline(boolean on, String detail) {
        boolean changed = on != online;
        online = on;
        if (changed && on) DebugLog.srv("Remote link online (" + RemoteLog.server() + ")");
        if (changed && on) lastError = null;
        StateListener l = stateListener;
        if (changed && l != null) ui.post(() -> l.onState(on, detail));
    }
}
