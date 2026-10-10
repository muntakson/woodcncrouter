package org.dnaboy.grblsender;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.view.Surface;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Locale;

/**
 * Shares the tablet's camera with the backoffice so the cutting can be watched remotely.
 *
 * The camera runs only on demand: while a viewer is watching (the backoffice says so in the sync reply
 * and in every frame reply) or while Settings → Camera shows its local preview. Frames come from a
 * Camera2 YUV stream, are compressed to JPEG and POSTed one per request to /api/device/frame at the
 * frame rate the viewers asked for. The backoffice relays them to browsers (Camera page, MJPEG).
 *
 * Android only lets the app use the camera while it is in the foreground; {@link #setForeground}
 * follows the activity's onStart/onStop.
 */
final class CameraStreamer {

    interface Listener {
        /** Something visible changed (live / idle / error); called on the camera thread. */
        void onState();
    }

    interface PreviewSink {
        /** A JPEG frame for the local preview; called on the upload thread. */
        void onFrame(byte[] jpeg, int width, int height);
    }

    interface RotationSource { int displayRotation(); }

    static final String PREF_ENABLED = "camera", PREF_LENS = "cameraLens", PREF_FLIP = "cameraFlip";
    /** Focus point in the picture as sent (0..1, origin top-left) and continuous vs. focus-once-and-hold. */
    static final String PREF_FOCUS_X = "cameraFocusX", PREF_FOCUS_Y = "cameraFocusY", PREF_FOCUS_LOCK = "cameraFocusLock";
    private static final long WISH_TTL_MS = 10000;          // remote wish lapses without a sync / frame reply
    private static final int PREVIEW_W = 640, PREVIEW_H = 360, PREVIEW_FPS = 8;

    private final Context ctx;
    private final SharedPreferences prefs;
    private final RotationSource rotation;
    private final HandlerThread camThread = new HandlerThread("camera");
    private final Handler cam;
    private volatile Listener listener;
    private volatile PreviewSink preview;

    // what is wanted (written from several threads, applied on the camera thread)
    private volatile boolean foreground = true;
    private volatile boolean remoteWant;
    private volatile long remoteWishMs;
    private volatile int remoteFps = 5, remoteW = 960, remoteH = 540, remoteQuality = 70;

    // camera thread only
    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader reader;
    private int openW, openH;
    private String openId;
    private int sensorOrientation;
    private Rect activeArray;
    private int streamW, streamH;
    private int afRegionsMax, aeRegionsMax;

    // autofocus state from the capture results
    private volatile String afState = "off";
    private volatile float focusDiopters = -1;
    private volatile String lastFocusId;
    private boolean frontFacing;
    private boolean opening;
    private long sessionStartMs;
    private static final long WARMUP_MS = 700;         // auto-exposure settles; the first frames are black

    // the newest frame, handed from the camera thread to the upload thread
    private final Object frameLock = new Object();
    private byte[] nv21;
    private int frameW, frameH;
    private boolean frameReady, needFrame;

    // status
    private volatile String error;
    private volatile boolean live;                    // frames are reaching the backoffice
    private volatile int lastBytes, outW, outH;
    private final ArrayDeque<Long> sent = new ArrayDeque<>();
    private volatile Thread uploader;
    private volatile boolean shutdown;

    CameraStreamer(Context ctx, SharedPreferences prefs, RotationSource rotation) {
        this.ctx = ctx.getApplicationContext();
        this.prefs = prefs;
        this.rotation = rotation;
        camThread.start();
        cam = new Handler(camThread.getLooper());
    }

    void setListener(Listener l) { listener = l; }

    boolean enabled() { return prefs.getBoolean(PREF_ENABLED, true); }

    boolean hasPermission() {
        return Build.VERSION.SDK_INT < 23 || ctx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    boolean isLive() { return live; }

    boolean remoteWanted() { return remoteWant && System.currentTimeMillis() - remoteWishMs < WISH_TTL_MS; }

    String lastError() { return error; }

    /** Settings changed (enabled, lens, flip) or the permission was granted. */
    void settingsChanged() { reconcileSoon(true); }

    void setForeground(boolean fg) {
        foreground = fg;
        reconcileSoon(false);
    }

    /** Local preview for Settings → Camera ({@code null} stops it). */
    void setPreview(PreviewSink sink) {
        preview = sink;
        reconcileSoon(false);
    }

    float focusX() { return prefs.getFloat(PREF_FOCUS_X, 0.5f); }
    float focusY() { return prefs.getFloat(PREF_FOCUS_Y, 0.5f); }
    boolean focusLocked() { return prefs.getBoolean(PREF_FOCUS_LOCK, false); }
    String afState() { return afState; }

    /**
     * Focus (and meter exposure) on a point of the picture, e.g. the router bit: x, y in 0..1 from the
     * top-left of the picture as viewers see it. {@code lock} null keeps the mode. Then runs a focus pass.
     */
    void setFocus(float x, float y, Boolean lock) {
        SharedPreferences.Editor e = prefs.edit()
                .putFloat(PREF_FOCUS_X, Math.max(0f, Math.min(1f, x)))
                .putFloat(PREF_FOCUS_Y, Math.max(0f, Math.min(1f, y)));
        if (lock != null) e.putBoolean(PREF_FOCUS_LOCK, lock);
        e.apply();
        DebugLog.srv(String.format(Locale.US, "Camera: focus at %.0f%% / %.0f%%%s", x * 100, y * 100,
                focusLocked() ? " (locked)" : " (continuous)"));
        cam.post(() -> applyFocus(true));
    }

    /** Run a new focus pass at the current point. */
    void refocus() { cam.post(() -> applyFocus(true)); }

    /** The backoffice's wish from a sync or frame reply: {want, fps, width, height, quality, focus?}. */
    void onRemoteWish(JSONObject w) {
        JSONObject f = w != null ? w.optJSONObject("focus") : null;
        if (f != null && !f.optString("id").equals(lastFocusId)) {      // a viewer clicked the picture / Refocus
            lastFocusId = f.optString("id");
            if (f.has("x") && f.has("y")) setFocus((float) f.optDouble("x"), (float) f.optDouble("y"), f.has("lock") ? f.optBoolean("lock") : null);
            else if (f.has("lock")) setFocus(focusX(), focusY(), f.optBoolean("lock"));
            else refocus();
        }
        boolean want = w != null && w.optBoolean("want");
        if (want) {
            remoteFps = Math.max(1, Math.min(15, w.optInt("fps", 5)));
            remoteW = w.optInt("width", 960);
            remoteH = w.optInt("height", 540);
            remoteQuality = Math.max(30, Math.min(90, w.optInt("quality", 70)));
        }
        remoteWishMs = System.currentTimeMillis();
        if (want != remoteWant) {
            remoteWant = want;
            if (want) DebugLog.srv("Camera: a viewer is watching – streaming " + remoteW + "×" + remoteH + " @ " + remoteFps + " fps");
            else DebugLog.srv("Camera: nobody is watching – camera off");
            reconcileSoon(false);
        }
    }

    /** Camera state for the sync body: shows on the backoffice's Camera page. */
    JSONObject statusJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("enabled", enabled()).put("permission", hasPermission()).put("foreground", foreground)
                    .put("lens", prefs.getString(PREF_LENS, "back")).put("live", live).put("fps", fps())
                    .put("width", outW).put("height", outH).put("bytes", lastBytes)
                    .put("focus", new JSONObject().put("x", focusX()).put("y", focusY()).put("lock", focusLocked())
                            .put("state", afState).put("diopters", focusDiopters).put("id", lastFocusId));
            String why = blockedReason();
            if (why != null) o.put("error", why);
            else if (error != null) o.put("error", error);
        } catch (Exception ignored) { }
        return o;
    }

    /** One line for the Debug console / remote "status". */
    String summary() {
        String why = blockedReason();
        if (why != null) return why;
        if (live) return String.format(Locale.US, "LIVE %d×%d · %.1f fps · %d KB/frame", outW, outH, fps(), lastBytes / 1024);
        if (device != null) return "on (preview)";
        return error != null ? "idle · last error: " + error : "idle – nobody watching";
    }

    double fps() {
        long now = System.currentTimeMillis();
        synchronized (sent) {
            while (!sent.isEmpty() && now - sent.peekFirst() > 5000) sent.pollFirst();
            return sent.size() / 5.0;
        }
    }

    /** Why the camera cannot run at all, or null. */
    String blockedReason() {
        if (!enabled()) return "camera sharing is switched off on the tablet (Settings → Camera)";
        if (!hasPermission()) return "camera permission not granted on the tablet";
        if (!foreground) return "Grbl Sender is in the background – Android blocks the camera";
        return null;
    }

    void shutdown() {
        shutdown = true;
        preview = null;
        remoteWant = false;
        cam.post(() -> { closeCamera(); camThread.quitSafely(); });
        Thread t = uploader;
        if (t != null) t.interrupt();
    }

    // ------------------------------------------------------------------ camera thread

    private void reconcileSoon(boolean reopen) {
        cam.post(() -> {
            if (reopen) closeCamera();
            reconcile();
        });
    }

    private boolean active() {
        return !shutdown && blockedReason() == null && (remoteWanted() || preview != null);
    }

    private final Runnable recheck = this::reconcile;

    private void reconcile() {
        boolean remote = remoteWanted();
        if (!active()) {
            if (device != null || opening) closeCamera();
            setLive(false);
            notifyState();
            return;
        }
        int w = remote ? remoteW : PREVIEW_W, h = remote ? remoteH : PREVIEW_H;
        startUploader();                                     // idempotent: it may have just quit
        cam.removeCallbacks(recheck);
        cam.postDelayed(recheck, WISH_TTL_MS);               // re-check when a silent remote wish lapses
        if ((device != null || opening) && w == openW && h == openH) return;
        closeCamera();
        open(w, h);
    }

    @SuppressWarnings("MissingPermission")
    private void open(int wantW, int wantH) {
        CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        try {
            boolean front = "front".equals(prefs.getString(PREF_LENS, "back"));
            String id = null;
            for (String c : cm.getCameraIdList()) {
                Integer f = cm.getCameraCharacteristics(c).get(CameraCharacteristics.LENS_FACING);
                boolean isFront = f != null && f == CameraCharacteristics.LENS_FACING_FRONT;
                if (id == null || isFront == front) { id = c; if (isFront == front) break; }
            }
            if (id == null) { fail("this tablet has no camera"); return; }
            CameraCharacteristics ch = cm.getCameraCharacteristics(id);
            Integer so = ch.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorOrientation = so != null ? so : 0;
            Integer f = ch.get(CameraCharacteristics.LENS_FACING);
            frontFacing = f != null && f == CameraCharacteristics.LENS_FACING_FRONT;
            Size size = pickSize(ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP), wantW, wantH);
            activeArray = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            Integer afMax = ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF), aeMax = ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE);
            afRegionsMax = afMax != null ? afMax : 0;
            aeRegionsMax = aeMax != null ? aeMax : 0;
            streamW = size.getWidth();
            streamH = size.getHeight();
            openW = wantW;
            openH = wantH;
            openId = id;
            reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888, 3);
            reader.setOnImageAvailableListener(this::onImage, cam);
            opening = true;
            DebugLog.srv("Camera: opening " + (frontFacing ? "front" : "back") + " camera " + size.getWidth() + "×" + size.getHeight());
            cm.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice d) {
                    opening = false;
                    if (!active() || reader == null) { d.close(); return; }
                    device = d;
                    startSession();
                }
                @Override public void onDisconnected(CameraDevice d) {
                    d.close();
                    if (device == d) { device = null; fail("camera disconnected (another app took it?)"); }
                }
                @Override public void onError(CameraDevice d, int code) {
                    d.close();
                    opening = false;
                    if (device == d || device == null) { device = null; fail("camera error " + code); }
                }
            }, cam);
        } catch (SecurityException e) {
            fail("camera permission not granted");
        } catch (Exception e) {
            fail("cannot open the camera: " + e.getMessage());
        }
    }

    private static Size pickSize(StreamConfigurationMap map, int w, int h) {
        Size best = null;
        if (map != null) {
            Size[] sizes = map.getOutputSizes(ImageFormat.YUV_420_888);
            // exact match, else the smallest size that covers the request with the same aspect, else the closest area
            for (Size s : sizes) if (s.getWidth() == w && s.getHeight() == h) return s;
            for (Size s : sizes) {
                if (s.getWidth() >= w && s.getHeight() >= h && s.getWidth() * h == s.getHeight() * w
                        && (best == null || s.getWidth() < best.getWidth())) best = s;
            }
            if (best == null) {
                for (Size s : sizes) {
                    if (best == null || Math.abs(s.getWidth() * s.getHeight() - w * h)
                            < Math.abs(best.getWidth() * best.getHeight() - w * h)) best = s;
                }
            }
        }
        return best != null ? best : new Size(640, 480);
    }

    private void startSession() {
        try {
            device.createCaptureSession(Collections.singletonList(reader.getSurface()), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession s) {
                    if (device == null) { s.close(); return; }
                    session = s;
                    try {
                        s.setRepeatingRequest(buildRequest(null).build(), afCallback, cam);
                        sessionStartMs = System.currentTimeMillis();
                        cam.postDelayed(() -> applyFocus(true), WARMUP_MS);   // exposure first, then a focus pass
                        error = null;
                        notifyState();
                    } catch (Exception e) {
                        fail("camera session failed: " + e.getMessage());
                    }
                }
                @Override public void onConfigureFailed(CameraCaptureSession s) { fail("camera session could not be configured"); }
            }, cam);
        } catch (CameraAccessException e) {
            fail("camera session failed: " + e.getMessage());
        }
    }

    /**
     * The streaming request: autofocus and exposure metered on the focus point, sharpening at high quality,
     * light noise reduction (strong noise reduction smears the fine detail of the cut).
     */
    private CaptureRequest.Builder buildRequest(Integer afTrigger) throws CameraAccessException {
        CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        b.addTarget(reader.getSurface());
        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
        b.set(CaptureRequest.CONTROL_AF_MODE, focusLocked() ? CaptureRequest.CONTROL_AF_MODE_AUTO
                : CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        b.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY);
        b.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST);
        MeteringRectangle region = focusRegion();
        if (region != null) {
            if (afRegionsMax > 0) b.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[]{region});
            if (aeRegionsMax > 0) b.set(CaptureRequest.CONTROL_AE_REGIONS, new MeteringRectangle[]{region});
        }
        if (afTrigger != null) b.set(CaptureRequest.CONTROL_AF_TRIGGER, afTrigger);
        return b;
    }

    /** Re-issue the repeating request (new point / mode); with {@code pass}, restart the focus search. */
    private void applyFocus(boolean pass) {
        if (session == null || device == null || reader == null) return;
        try {
            session.setRepeatingRequest(buildRequest(null).build(), afCallback, cam);
            if (pass) {
                session.capture(buildRequest(CaptureRequest.CONTROL_AF_TRIGGER_CANCEL).build(), afCallback, cam);
                // continuous: the cancel restarts the passive scan; auto (locked): focus once and hold
                if (focusLocked()) session.capture(buildRequest(CaptureRequest.CONTROL_AF_TRIGGER_START).build(), afCallback, cam);
            }
        } catch (Exception e) {
            DebugLog.srvErr("Camera: focus request failed: " + e.getMessage());
        }
    }

    /**
     * The focus point (picture coordinates) as a sensor metering rectangle: undo the picture's rotation,
     * then account for the centre crop from the 4:3 sensor to the 16:9 stream. About 16 % of the height.
     */
    private MeteringRectangle focusRegion() {
        if (activeArray == null || streamW == 0) return null;
        float x = focusX(), y = focusY(), sx, sy;
        switch (jpegRotation()) {
            case 90: sx = y; sy = 1 - x; break;
            case 180: sx = 1 - x; sy = 1 - y; break;
            case 270: sx = 1 - y; sy = x; break;
            default: sx = x; sy = y;
        }
        float aw = activeArray.width(), ah = activeArray.height(), aspect = (float) streamW / streamH;
        float cw = aw, ch = ah;
        if (aw / ah > aspect) cw = ah * aspect; else ch = aw / aspect;
        float cx = activeArray.left + (aw - cw) / 2 + sx * cw, cy = activeArray.top + (ah - ch) / 2 + sy * ch;
        int half = Math.round(Math.min(cw, ch) * 0.08f);
        int l = Math.max(activeArray.left, Math.round(cx) - half), t = Math.max(activeArray.top, Math.round(cy) - half);
        int r = Math.min(activeArray.right - 1, Math.round(cx) + half), b = Math.min(activeArray.bottom - 1, Math.round(cy) + half);
        if (r <= l || b <= t) return null;
        return new MeteringRectangle(new Rect(l, t, r, b), MeteringRectangle.METERING_WEIGHT_MAX - 1);
    }

    private final CameraCaptureSession.CaptureCallback afCallback = new CameraCaptureSession.CaptureCallback() {
        @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest req, TotalCaptureResult res) {
            Integer st = res.get(CaptureResult.CONTROL_AF_STATE);
            Float d = res.get(CaptureResult.LENS_FOCUS_DISTANCE);
            if (d != null) focusDiopters = d;
            String v;
            if (st == null) v = "off";
            else switch (st) {
                case CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN:
                case CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN: v = "searching"; break;
                case CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED: v = "focused"; break;
                case CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED: v = "locked"; break;
                case CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED: v = "failed"; break;
                case CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED: v = "unfocused"; break;
                default: v = "idle";
            }
            if (!v.equals(afState)) {
                afState = v;
                DebugLog.srvVerbose("Camera: autofocus " + v + (d != null ? String.format(Locale.US, " (%.1f dpt)", d) : ""));
            }
        }
    };

    private void onImage(ImageReader r) {
        Image img = r.acquireLatestImage();
        if (img == null) return;
        try {
            if (System.currentTimeMillis() - sessionStartMs < WARMUP_MS) return;
            synchronized (frameLock) {
                if (!needFrame) return;                   // the encoder is busy: skip this frame
                int w = img.getWidth(), h = img.getHeight();
                if (nv21 == null || nv21.length != w * h * 3 / 2) nv21 = new byte[w * h * 3 / 2];
                toNv21(img, nv21);
                frameW = w;
                frameH = h;
                frameReady = true;
                needFrame = false;
                frameLock.notifyAll();
            }
        } finally {
            img.close();
        }
    }

    /** YUV_420_888 (any row / pixel stride) → NV21 (Y plane, then interleaved V U). */
    private static void toNv21(Image img, byte[] out) {
        int w = img.getWidth(), h = img.getHeight();
        Image.Plane[] p = img.getPlanes();
        ByteBuffer y = p[0].getBuffer();
        int yRow = p[0].getRowStride(), pos = 0;
        if (yRow == w) {
            y.position(0);
            y.get(out, 0, w * h);
            pos = w * h;
        } else {
            for (int row = 0; row < h; row++) {
                y.position(row * yRow);
                y.get(out, pos, w);
                pos += w;
            }
        }
        ByteBuffer u = p[1].getBuffer(), v = p[2].getBuffer();
        int uRow = p[1].getRowStride(), uPix = p[1].getPixelStride();
        int vRow = p[2].getRowStride(), vPix = p[2].getPixelStride();
        int cw = w / 2, ch = h / 2;
        for (int row = 0; row < ch; row++) {
            int ub = row * uRow, vb = row * vRow;
            for (int col = 0; col < cw; col++) {
                out[pos++] = v.get(vb + col * vPix);
                out[pos++] = u.get(ub + col * uPix);
            }
        }
    }

    private void closeCamera() {
        opening = false;
        try { if (session != null) session.close(); } catch (Exception ignored) { }
        session = null;
        try { if (device != null) device.close(); } catch (Exception ignored) { }
        if (device != null) DebugLog.srv("Camera: closed");
        device = null;
        try { if (reader != null) reader.close(); } catch (Exception ignored) { }
        reader = null;
        openW = openH = 0;
        sessionStartMs = Long.MAX_VALUE / 2;
        afState = "off";
        synchronized (frameLock) { frameReady = false; frameLock.notifyAll(); }
    }

    private void fail(String msg) {
        if (!msg.equals(error)) DebugLog.srvErr("Camera: " + msg);
        error = msg;
        closeCamera();
        setLive(false);
        notifyState();
        if (active()) cam.postDelayed(this::reconcile, 5000);   // try again while still wanted
    }

    private void setLive(boolean on) {
        if (live != on) {
            live = on;
            notifyState();
        }
    }

    private void notifyState() {
        Listener l = listener;
        if (l != null) l.onState();
    }

    // ------------------------------------------------------------------ upload thread

    private synchronized void startUploader() {
        if (uploader != null && uploader.isAlive()) return;
        uploader = new Thread(this::uploadLoop, "camera-upload");
        uploader.setDaemon(true);
        uploader.start();
    }

    private void uploadLoop() {
        byte[] work = null;
        while (!shutdown) {
            boolean remote = remoteWanted();
            PreviewSink sink = preview;
            if (!remote && sink == null) {
                setLive(false);
                cam.post(this::reconcile);
                break;                                           // the next wish starts a new uploader
            }
            long t0 = System.currentTimeMillis();
            int w, h;
            synchronized (frameLock) {
                frameReady = false;
                needFrame = true;
                try {
                    long until = t0 + 1500;
                    while (!frameReady && !shutdown) {
                        long left = until - System.currentTimeMillis();
                        if (left <= 0) break;
                        frameLock.wait(left);
                    }
                } catch (InterruptedException e) { break; }
                if (!frameReady) { needFrame = false; continue; }
                if (work == null || work.length != nv21.length) work = new byte[nv21.length];
                System.arraycopy(nv21, 0, work, 0, nv21.length);
                w = frameW;
                h = frameH;
            }
            int rot = jpegRotation();
            if (rot == 180) rotate180(work, w, h);
            int quality = remote ? remoteQuality : 75;
            ByteArrayOutputStream bo = new ByteArrayOutputStream(64 * 1024);
            new YuvImage(work, ImageFormat.NV21, w, h, null).compressToJpeg(new Rect(0, 0, w, h), quality, bo);
            byte[] jpeg = bo.toByteArray();
            if (rot == 90 || rot == 270) {                       // rare (tablet held in portrait): slower bitmap path
                Bitmap b = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
                Matrix m = new Matrix();
                m.postRotate(rot);
                Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
                bo.reset();
                r.compress(Bitmap.CompressFormat.JPEG, quality, bo);
                jpeg = bo.toByteArray();
                int t = w; w = h; h = t;
                b.recycle();
                r.recycle();
            }
            outW = w;
            outH = h;
            lastBytes = jpeg.length;
            if (sink != null) sink.onFrame(jpeg, w, h);
            long interval = 1000L / (remote ? remoteFps : PREVIEW_FPS);
            if (remote) {
                synchronized (slotLock) {               // newest frame wins: a slow upload drops frames, not fps
                    slot = jpeg; slotW = w; slotH = h;
                    slotLock.notifyAll();
                }
                startSender();
            }
            long sleep = interval - (System.currentTimeMillis() - t0);
            if (sleep > 0) {
                try { Thread.sleep(sleep); } catch (InterruptedException e) { break; }
            }
        }
        synchronized (this) { if (uploader == Thread.currentThread()) uploader = null; }
        setLive(false);
    }

    private final Object slotLock = new Object();
    private byte[] slot;
    private int slotW, slotH;
    private volatile Thread sender;

    private synchronized void startSender() {
        if (sender != null && sender.isAlive()) return;
        sender = new Thread(this::sendLoop, "camera-send");
        sender.setDaemon(true);
        sender.start();
    }

    /** Uploads the newest encoded frame, one request at a time. */
    private void sendLoop() {
        int backoff = 1000;
        while (!shutdown) {
            byte[] jpeg;
            int w, h;
            synchronized (slotLock) {
                try {
                    if (slot == null) slotLock.wait(3000);
                } catch (InterruptedException e) { break; }
                if (slot == null) {
                    if (!remoteWanted()) break;           // nobody watching any more
                    continue;
                }
                jpeg = slot; w = slotW; h = slotH;
                slot = null;
            }
            try {
                JSONObject wish = postFrame(jpeg, w, h);
                synchronized (sent) { sent.add(System.currentTimeMillis()); }
                setLive(true);
                backoff = 1000;
                onRemoteWish(wish);
            } catch (Exception e) {
                setLive(false);
                String msg = "frame upload failed: " + e.getMessage();
                if (!msg.equals(error)) DebugLog.srvErr("Camera: " + msg);
                error = msg;
                try { Thread.sleep(backoff); } catch (InterruptedException ie) { break; }
                backoff = Math.min(backoff * 2, 10000);
            }
        }
        synchronized (this) { if (sender == Thread.currentThread()) sender = null; }
        setLive(false);
    }

    /** Degrees to rotate the sensor image so it is upright on screen (plus the user's 180° flip). */
    private int jpegRotation() {
        int deg = 0;
        switch (rotation.displayRotation()) {
            case Surface.ROTATION_90: deg = 90; break;
            case Surface.ROTATION_180: deg = 180; break;
            case Surface.ROTATION_270: deg = 270; break;
            default:
        }
        int r = frontFacing ? (sensorOrientation + deg) % 360 : (sensorOrientation - deg + 360) % 360;
        if (prefs.getBoolean(PREF_FLIP, false)) r = (r + 180) % 360;
        return r;
    }

    /** NV21 rotated by 180°: reverse the Y plane and the V/U pairs. */
    private static void rotate180(byte[] d, int w, int h) {
        int n = w * h;
        for (int i = 0, j = n - 1; i < j; i++, j--) { byte t = d[i]; d[i] = d[j]; d[j] = t; }
        for (int i = n, j = d.length - 2; i < j; i += 2, j -= 2) {
            byte v = d[i], u = d[i + 1];
            d[i] = d[j]; d[i + 1] = d[j + 1];
            d[j] = v; d[j + 1] = u;
        }
    }

    /** POST one JPEG; keeps the HTTP(S) connection alive between frames. Returns the backoffice's wish. */
    private JSONObject postFrame(byte[] jpeg, int w, int h) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(RemoteLog.server() + "/api/device/frame").openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(10000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setFixedLengthStreamingMode(jpeg.length);
        c.setRequestProperty("Content-Type", "image/jpeg");
        c.setRequestProperty("X-Ingest-Key", BuildConfig.INGEST_KEY);
        c.setRequestProperty("X-Device-Id", RemoteLog.deviceId());
        c.setRequestProperty("X-Frame-Width", String.valueOf(w));
        c.setRequestProperty("X-Frame-Height", String.valueOf(h));
        try (OutputStream os = c.getOutputStream()) { os.write(jpeg); }
        int code = c.getResponseCode();
        InputStream in = code / 100 == 2 ? c.getInputStream() : c.getErrorStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        if (in != null) {
            try (InputStream s = in) {
                byte[] buf = new byte[1024];
                int n;
                while ((n = s.read(buf)) > 0) bo.write(buf, 0, n);
            }
        }
        if (code / 100 != 2) throw new Exception("HTTP " + code);
        return new JSONObject(bo.toString("UTF-8"));
    }
}
