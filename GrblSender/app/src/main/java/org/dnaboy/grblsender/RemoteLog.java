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
                int latest = resp.optInt("latestVersionCode", 0);
                if (latest > BuildConfig.VERSION_CODE && !updateNotified) checkVersion();
                backoffMs = 1000;
                Thread.sleep(500);   // coalesce bursts into fewer requests
            } catch (Exception e) {
                Log.w(TAG, "log upload failed: " + e);
                try { Thread.sleep(backoffMs); } catch (InterruptedException ie) { return; }
                backoffMs = Math.min(backoffMs * 2, 30000);
            }
        }
    }

    private static void checkVersion() {
        try {
            JSONObject v = new JSONObject(get("/api/version"));
            int code = v.optInt("versionCode", 0);
            UpdateListener l = updateListener;
            if (code > BuildConfig.VERSION_CODE && l != null && !updateNotified) {
                updateNotified = true;
                l.onUpdateAvailable(code, v.optString("versionName"));
            }
        } catch (Exception e) {
            Log.w(TAG, "version check failed: " + e);
        }
    }

    public static String downloadUrl() { return BuildConfig.LOG_SERVER + "/apk/latest.apk"; }

    private static String post(String path, String json) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(BuildConfig.LOG_SERVER + path).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(10000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("X-Ingest-Key", BuildConfig.INGEST_KEY);
        try (OutputStream os = c.getOutputStream()) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
        }
        return readResponse(c);
    }

    /** GET {@code path} on the backoffice server (call off the UI thread). */
    static String get(String path) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(BuildConfig.LOG_SERVER + path).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(10000);
        return readResponse(c);
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
