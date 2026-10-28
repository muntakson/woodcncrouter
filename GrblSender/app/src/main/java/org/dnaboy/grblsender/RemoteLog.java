package org.dnaboy.grblsender;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.io.FileInputStream;
import java.util.ArrayDeque;

/**
 * Ships log lines to the backoffice server (BuildConfig.LOG_SERVER) in batches.
 * Lines are buffered in memory while the server is unreachable; crashes are
 * written to a file and sent on the next launch.
 */
public final class RemoteLog {

    public interface UpdateListener { void onUpdateAvailable(int versionCode, String versionName); }

    private static final String TAG = "GrblSender";
    private static final int MAX_QUEUE = 5000;
    private static final int BATCH = 500;
    private static final ArrayDeque<JSONObject> queue = new ArrayDeque<>();
    private static JSONObject device;
    private static File crashFile;
    private static volatile UpdateListener updateListener;
    private static volatile boolean updateNotified;
    private static volatile String server = BuildConfig.LOG_SERVER;
    private static volatile boolean uploadFailing;

    /** Backoffice base URL (Settings → General), e.g. https://cnc.iotok.org. */
    static String server() { return server; }

    /** Accepts "host", "host:port" or a full URL; returns the normalised URL that is now in use. */
    static String setServer(String url) {
        server = normalize(url);
        return server;
    }

    static String normalize(String url) {
        String u = url == null ? "" : url.trim();
        if (u.isEmpty()) u = BuildConfig.LOG_SERVER;
        if (!u.matches("(?i)^https?://.*")) u = "https://" + u;
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    /** This tablet's identity as sent with every upload (id = ANDROID_ID). */
    static JSONObject device() { return device; }

    static String deviceId() { return device != null ? device.optString("id") : ""; }

    private RemoteLog() { }

    public static synchronized void init(Context ctx, UpdateListener listener) {
        updateListener = listener;
        if (device != null) return;
        try {
            device = new JSONObject()
                    .put("id", Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ANDROID_ID))
                    .put("model", Build.MODEL)
                    .put("manufacturer", Build.MANUFACTURER)
                    .put("android", Build.VERSION.RELEASE)
                    .put("sdk", Build.VERSION.SDK_INT)
                    .put("appVersion", BuildConfig.VERSION_NAME)
                    .put("appCode", BuildConfig.VERSION_CODE);
        } catch (Exception ignored) { }

        crashFile = new File(ctx.getFilesDir(), "crash.txt");
        if (crashFile.exists()) {
            try {
                try (InputStream in = new FileInputStream(crashFile)) {
                    e("crash", "Previous run crashed:\n" + readAll(in));
                }
            } catch (Exception ignored) { }
            crashFile.delete();
        }

        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, ex) -> {
            String trace = "Thread " + t.getName() + "\n" + Log.getStackTraceString(ex);
            try (OutputStream os = new FileOutputStream(crashFile)) {
                os.write(trace.getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) { }
            if (prev != null) prev.uncaughtException(t, ex);
        });

        Thread sender = new Thread(RemoteLog::loop, "remote-log");
        sender.setDaemon(true);
        sender.start();

        i("app", "Start " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ") on "
                + Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE
                + " (SDK " + Build.VERSION.SDK_INT + ")");
    }

    public static void d(String tag, String msg) { add('D', tag, msg); }
    public static void i(String tag, String msg) { add('I', tag, msg); }
    public static void w(String tag, String msg) { add('W', tag, msg); }
    public static void e(String tag, String msg) { add('E', tag, msg); }
    public static void e(String tag, String msg, Throwable t) { add('E', tag, msg + "\n" + Log.getStackTraceString(t)); }

    private static void add(char level, String tag, String msg) {
        Log.println(level == 'E' ? Log.ERROR : level == 'W' ? Log.WARN : level == 'D' ? Log.DEBUG : Log.INFO,
                TAG, tag + ": " + msg);
        try {
            JSONObject o = new JSONObject()
                    .put("ts", System.currentTimeMillis())
                    .put("level", String.valueOf(level))
                    .put("tag", tag)
                    .put("msg", msg);
            synchronized (queue) {
                if (queue.size() >= MAX_QUEUE) queue.poll();
                queue.add(o);
                queue.notifyAll();
            }
        } catch (Exception ignored) { }
    }

    private static void loop() {
        int backoffMs = 1000;
        checkVersion();
        while (true) {
            JSONArray batch = new JSONArray();
            synchronized (queue) {
                try {
                    if (queue.isEmpty()) queue.wait(10000);
                } catch (InterruptedException e) {
                    return;
                }
                int n = 0;
                for (JSONObject o : queue) {
                    if (n++ >= BATCH) break;
                    batch.put(o);
                }
            }
            if (batch.length() == 0) continue;
            try {
                JSONObject body = new JSONObject().put("device", device).put("logs", batch);
                JSONObject resp = new JSONObject(post("/api/ingest", body.toString()));
                synchronized (queue) {
                    for (int i = 0; i < batch.length() && !queue.isEmpty(); i++) queue.poll();
                }
                if (uploadFailing) DebugLog.srv("Log upload works again (" + server + ")");
                uploadFailing = false;
                DebugLog.srvVerbose("uploaded " + batch.length() + " log line(s)");
                int latest = resp.optInt("latestVersionCode", 0);
                if (latest > BuildConfig.VERSION_CODE && !updateNotified) checkVersion();
                backoffMs = 1000;
                Thread.sleep(500);   // coalesce bursts into fewer requests
            } catch (Exception e) {
                Log.w(TAG, "log upload failed: " + e);
                if (!uploadFailing) DebugLog.srvErr("Log upload to " + server + " failed: " + e.getMessage() + " – keeping lines and retrying");
                uploadFailing = true;
                try { Thread.sleep(backoffMs); } catch (InterruptedException ie) { return; }
                backoffMs = Math.min(backoffMs * 2, 30000);
            }
        }
    }

    private static void checkVersion() {
        try {
            JSONObject v = new JSONObject(get("/api/version"));
            int code = v.optInt("versionCode", 0);
            DebugLog.srv("Version check: newest on the backoffice is build " + code + " (installed " + BuildConfig.VERSION_CODE + ")");
            UpdateListener l = updateListener;
            if (code > BuildConfig.VERSION_CODE && l != null && !updateNotified) {
                updateNotified = true;
                l.onUpdateAvailable(code, v.optString("versionName"));
            }
        } catch (Exception e) {
            Log.w(TAG, "version check failed: " + e);
            DebugLog.srvErr("Version check failed: " + e.getMessage());
        }
    }

    public static String downloadUrl() { return server + "/apk/latest.apk"; }

    private static String post(String path, String json) throws Exception { return postJson(path, json, 10000); }

    /** POST JSON with the ingest key (call off the UI thread). */
    static String postJson(String path, String json, int readTimeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(server + path).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(readTimeoutMs);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("X-Ingest-Key", BuildConfig.INGEST_KEY);
        try (OutputStream os = c.getOutputStream()) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
        }
        return readResponse(c);
    }

    /** POST the loaded program's text with the ingest key; the server checks it against {@code hash}. */
    static void postProgram(String hash, String name, String text) throws Exception {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        HttpURLConnection c = (HttpURLConnection) new URL(server + "/api/device/program").openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(60000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setFixedLengthStreamingMode(body.length);
        c.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
        c.setRequestProperty("X-Ingest-Key", BuildConfig.INGEST_KEY);
        c.setRequestProperty("X-Device-Id", deviceId());
        c.setRequestProperty("X-Program-Hash", hash);
        c.setRequestProperty("X-Program-Name", java.net.URLEncoder.encode(name != null ? name : "", "UTF-8"));
        try (OutputStream os = c.getOutputStream()) {
            os.write(body);
        }
        readResponse(c);
    }

    /** Delete an NC file from the backoffice's gcode folder with the ingest key (call off the UI thread). */
    static void deleteGcode(String name) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(server + "/api/gcode/"
                + java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(10000);
        c.setRequestMethod("DELETE");
        c.setRequestProperty("X-Ingest-Key", BuildConfig.INGEST_KEY);
        readResponse(c);
    }

    /** GET {@code path} on the backoffice server (call off the UI thread). */
    static String get(String path) throws Exception { return getAbs(server + path); }

    /** GET a full URL (used to test another server before switching to it). */
    static String getAbs(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(10000);
        return readResponse(c);
    }

    /** GET {@code path} as raw bytes (binary firmware images). Call off the UI thread. */
    static byte[] getBytes(String path) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(server + path).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(20000);
        try {
            int code = c.getResponseCode();
            if (code / 100 != 2) throw new Exception("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                return bo.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    private static String readResponse(HttpURLConnection c) throws Exception {
        try {
            int code = c.getResponseCode();
            if (code / 100 != 2) throw new Exception("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                return readAll(in);
            }
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toString("UTF-8");
    }
}
