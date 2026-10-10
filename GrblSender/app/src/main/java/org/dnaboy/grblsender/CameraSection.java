package org.dnaboy.grblsender;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Settings → Camera: a live preview for aiming the tablet at the machine, the switch that lets the
 * backoffice watch, the lens and a 180° flip. The preview runs only while this section is open.
 */
final class CameraSection {

    private final Activity a;
    private final CameraStreamer cam;
    private final SharedPreferences prefs;
    private final Runnable askPermission;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private ImageView img;
    private TextView placeholder, status, previewInfo, focusInfo;
    private Reticle reticle;
    private RadioButton fCont, fLock;
    private Button grant;
    private Switch enable;
    private boolean attached;
    private Bitmap shown;

    CameraSection(Activity a, CameraStreamer cam, SharedPreferences prefs, Runnable askPermission) {
        this.a = a;
        this.cam = cam;
        this.prefs = prefs;
        this.askPermission = askPermission;
    }

    View build() {
        attached = true;
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(Ui.heading(a, "Camera", "Watch the cutting from the backoffice (Camera page). The camera runs only while "
                + "someone watches, or while this page shows the preview."));

        LinearLayout cols = new LinearLayout(a);
        cols.setOrientation(LinearLayout.HORIZONTAL);

        // ---- left: preview
        LinearLayout prevCard = Ui.card(a);
        prevCard.addView(Ui.label(a, "Preview"));
        FrameLayout frame = new FrameLayout(a);
        frame.setBackgroundResource(R.drawable.dro_bg);
        img = new ImageView(a);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        img.setAdjustViewBounds(true);
        frame.addView(img, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, Ui.dp(a, 250)));
        placeholder = Ui.text(a, "Starting the camera…", 14, Ui.MUTED);
        placeholder.setGravity(Gravity.CENTER);
        frame.addView(placeholder, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, Ui.dp(a, 250)));
        reticle = new Reticle();
        frame.addView(reticle, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, Ui.dp(a, 250)));
        reticle.setOnTouchListener((v, ev) -> {
            if (ev.getAction() != MotionEvent.ACTION_UP) return true;
            RectF r = imageRect();
            if (r == null || !r.contains(ev.getX(), ev.getY())) return true;
            cam.setFocus((ev.getX() - r.left) / r.width(), (ev.getY() - r.top) / r.height(), null);
            reticle.invalidate();
            return true;
        });
        prevCard.addView(frame);
        previewInfo = Ui.text(a, "", 12, Ui.MUTED);
        previewInfo.setPadding(0, Ui.dp(a, 6), 0, 0);
        prevCard.addView(previewInfo);
        cols.addView(prevCard, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.15f));

        // ---- right: sharing + camera
        LinearLayout right = new LinearLayout(a);
        right.setOrientation(LinearLayout.VERTICAL);

        LinearLayout share = Ui.card(a);
        share.addView(Ui.label(a, "Sharing"));
        enable = new Switch(a);
        enable.setText("Let the backoffice watch the camera");
        enable.setTextColor(Ui.TEXT);
        enable.setTextSize(15);
        enable.setChecked(cam.enabled());
        share.addView(enable);
        status = Ui.text(a, "", 13, Ui.TEXT);
        status.setPadding(0, Ui.dp(a, 8), 0, 0);
        share.addView(status);
        grant = Ui.button(a, "Allow camera access", 1);
        share.addView(grant, Ui.margins(a, 0, 8, 0, 0));
        TextView note = Ui.text(a, "Viewers: " + RemoteLog.server() + "/app#camera\nMJPEG for VLC / OBS: "
                + RemoteLog.server() + "/api/cnc/camera.mjpeg (dashboard login or Bearer CNC_API_KEY)\n"
                + "A red “Camera LIVE” chip shows in the title bar while someone watches. Keep Grbl Sender on screen: "
                + "Android blocks the camera for apps in the background.", 12, Ui.MUTED);
        note.setPadding(0, Ui.dp(a, 8), 0, 0);
        note.setTextIsSelectable(true);
        share.addView(note);
        right.addView(share, Ui.margins(a, 0, 0, 0, 10));

        LinearLayout lensCard = Ui.card(a);
        lensCard.addView(Ui.label(a, "Camera"));
        RadioGroup lens = new RadioGroup(a);
        lens.setOrientation(RadioGroup.VERTICAL);
        RadioButton back = radio("Back (main) camera"), front = radio("Front camera");
        lens.addView(back);
        lens.addView(front);
        (("front".equals(prefs.getString(CameraStreamer.PREF_LENS, "back"))) ? front : back).setChecked(true);
        lensCard.addView(lens);
        Switch flip = new Switch(a);
        flip.setText("Rotate the picture 180° (tablet mounted upside down)");
        flip.setTextColor(Ui.TEXT);
        flip.setTextSize(15);
        flip.setChecked(prefs.getBoolean(CameraStreamer.PREF_FLIP, false));
        lensCard.addView(flip, Ui.margins(a, 0, 8, 0, 0));
        right.addView(lensCard, Ui.margins(a, 0, 0, 0, 10));

        LinearLayout focusCard = Ui.card(a);
        focusCard.addView(Ui.label(a, "Focus"));
        RadioGroup fMode = new RadioGroup(a);
        fMode.setOrientation(RadioGroup.VERTICAL);
        fCont = radio("Continuous – keeps refocusing on the point");
        fLock = radio("Locked – focus once on the point, then hold");
        fMode.addView(fCont);
        fMode.addView(fLock);
        (cam.focusLocked() ? fLock : fCont).setChecked(true);
        focusCard.addView(fMode);
        LinearLayout fRow = Ui.row(a);
        focusInfo = Ui.text(a, "", 13, Ui.MUTED);
        fRow.addView(focusInfo, Ui.weight(1));
        Button refocus = Ui.button(a, "Refocus", 0);
        fRow.addView(refocus);
        focusCard.addView(fRow, Ui.margins(a, 0, 6, 0, 0));
        TextView fNote = Ui.text(a, "Tap the preview on the router bit to focus and set the exposure there. "
                + "Viewers can click the picture on the backoffice too. Locked avoids hunting while the spindle moves.", 12, Ui.MUTED);
        fNote.setPadding(0, Ui.dp(a, 6), 0, 0);
        focusCard.addView(fNote);
        right.addView(focusCard);
        fMode.setOnCheckedChangeListener((g, id) -> cam.setFocus(cam.focusX(), cam.focusY(), id == fLock.getId()));
        refocus.setOnClickListener(v -> cam.refocus());

        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        rp.setMarginStart(Ui.dp(a, 12));
        cols.addView(right, rp);

        ScrollView sv = new ScrollView(a);
        sv.addView(cols);
        root.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        enable.setOnCheckedChangeListener((b, on) -> {
            prefs.edit().putBoolean(CameraStreamer.PREF_ENABLED, on).apply();
            if (on && !cam.hasPermission()) askPermission.run();
            cam.settingsChanged();
            refresh();
        });
        lens.setOnCheckedChangeListener((g, id) -> {
            prefs.edit().putString(CameraStreamer.PREF_LENS, id == front.getId() ? "front" : "back").apply();
            cam.settingsChanged();
        });
        flip.setOnCheckedChangeListener((b, on) -> {
            prefs.edit().putBoolean(CameraStreamer.PREF_FLIP, on).apply();
            cam.refocus();                       // the focus point moves on the sensor
        });
        grant.setOnClickListener(v -> askPermission.run());

        cam.setPreview(this::onFrame);
        refresh();
        ui.postDelayed(ticker, 1000);
        return root;
    }

    private RadioButton radio(String s) {
        RadioButton r = new RadioButton(a);
        r.setText(s);
        r.setTextColor(Ui.TEXT);
        r.setTextSize(15);
        r.setPadding(0, 0, Ui.dp(a, 16), 0);
        return r;
    }

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!attached) return;
            refresh();
            ui.postDelayed(this, 1000);
        }
    };

    void refresh() {
        if (!attached) return;
        String st = cam.afState();
        focusInfo.setText(String.format(java.util.Locale.US, "Point %.0f%% · %.0f%% · %s", cam.focusX() * 100, cam.focusY() * 100,
                "off".equals(st) ? "camera off" : st));
        focusInfo.setTextColor(st.equals("focused") || st.equals("locked") ? Ui.OK : st.equals("failed") ? Ui.ERR
                : st.equals("searching") ? Ui.WARN : Ui.MUTED);
        reticle.invalidate();
        String why = cam.blockedReason();
        boolean noPerm = !cam.hasPermission();
        grant.setVisibility(noPerm && cam.enabled() ? View.VISIBLE : View.GONE);
        enable.setChecked(cam.enabled());
        if (why != null) {
            status.setText(why);
            status.setTextColor(Ui.WARN);
            placeholder.setVisibility(View.VISIBLE);
            placeholder.setText(noPerm ? "Camera access is not allowed yet" : "Camera off");
            img.setImageBitmap(null);
        } else if (cam.isLive()) {
            status.setText("● LIVE on the backoffice – " + cam.summary().replaceFirst("^LIVE ", ""));
            status.setTextColor(Ui.ERR);
        } else {
            status.setText(cam.remoteWanted() ? "A viewer is waiting – starting…"
                    : "Idle – nobody is watching" + (cam.lastError() != null ? "\nLast error: " + cam.lastError() : ""));
            status.setTextColor(cam.remoteWanted() ? Ui.WARN : Ui.MUTED);
        }
    }

    /** Upload thread → decode → UI. */
    private void onFrame(byte[] jpeg, int w, int h) {
        Bitmap b = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        ui.post(() -> {
            if (!attached || b == null) return;
            img.setImageBitmap(b);
            if (shown != null) shown.recycle();
            shown = b;
            placeholder.setVisibility(View.GONE);
            previewInfo.setText(w + "×" + h + " · " + jpeg.length / 1024 + " KB per frame");
        });
    }

    /** Where the picture is drawn inside the preview (FIT_CENTER), or null before the first frame. */
    private RectF imageRect() {
        if (img.getDrawable() == null) return null;
        RectF r = new RectF(0, 0, img.getDrawable().getIntrinsicWidth(), img.getDrawable().getIntrinsicHeight());
        img.getImageMatrix().mapRect(r);
        r.offset(img.getPaddingLeft(), img.getPaddingTop());
        return r;
    }

    /** Focus marker over the preview: a square at the focus point, coloured by the autofocus state. */
    private final class Reticle extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Reticle() {
            super(a);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Ui.dp(a, 2));
        }

        @Override protected void onDraw(Canvas c) {
            RectF r = imageRect();
            if (r == null) return;
            float x = r.left + cam.focusX() * r.width(), y = r.top + cam.focusY() * r.height(), h = r.height() * 0.08f;
            String st = cam.afState();
            p.setColor(st.equals("focused") || st.equals("locked") ? Ui.OK : st.equals("failed") ? Ui.ERR : Ui.WARN);
            c.drawRect(x - h, y - h, x + h, y + h, p);
            c.drawLine(x - h * 0.35f, y, x + h * 0.35f, y, p);
            c.drawLine(x, y - h * 0.35f, x, y + h * 0.35f, p);
        }
    }

    void detach() {
        attached = false;
        ui.removeCallbacks(ticker);
        cam.setPreview(null);
    }
}
