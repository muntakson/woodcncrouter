package org.dnaboy.grblsender;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;
import java.util.Map;

/**
 * Settings → Diagnosis: check the X step (pulse) and direction wires with a scope or a logic probe.
 *
 *  - Xpulse sends M150 P10: our Blue Pill firmware (r4) toggles the X step pin from a spare timer,
 *    a 1 kHz / 50 % square wave for 10 s, without moving the machine; M150 P0 stops it early.
 *  - Xdir jogs X by a single step in the direction that leaves the DIR pin high (ON) or low (OFF).
 *    Grbl keeps the DIR pin at the level of the last move until a reset. The DIR level of a move
 *    is "moving towards minus" XOR bit 0 of $3 (direction invert mask).
 */
final class DiagSection {

    interface Host {
        boolean connected();
        boolean busy();
        String machineState();
        Map<String, String> grblSettings();
        void send(String cmd);
        void log(String msg);
    }

    private static final int PULSE_SECONDS = 10;

    private final Activity a;
    private final Host host;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Button pulseBtn, dirBtn;
    private TextView pulseCap, dirCap, info, status;
    private ObjectAnimator blink;
    private boolean pulsing, dirOn;
    private long pulseStartMs, pulseEndMs;

    DiagSection(Activity a, Host host) {
        this.a = a;
        this.host = host;
    }

    View build() {
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(Ui.heading(a, "Diagnosis", "Check the X step (pulse) and direction outputs with a scope or a logic probe"));

        LinearLayout card = Ui.card(a);
        LinearLayout row = Ui.row(a);
        row.setGravity(Gravity.TOP);

        pulseBtn = Ui.button(a, "Xpulse", 0);
        pulseCap = caption();
        row.addView(column(pulseBtn, pulseCap), Ui.weight(1));

        View gap = new View(a);
        row.addView(gap, new LinearLayout.LayoutParams(Ui.dp(a, 16), 1));

        dirBtn = Ui.button(a, "Xdir", 0);
        dirCap = caption();
        row.addView(column(dirBtn, dirCap), Ui.weight(1));
        card.addView(row);

        info = Ui.text(a, "", 13, Ui.MUTED);
        info.setPadding(0, Ui.dp(a, 12), 0, 0);
        card.addView(info);
        status = Ui.text(a, "", 13, Ui.WARN);
        status.setPadding(0, Ui.dp(a, 6), 0, 0);
        card.addView(status);
        root.addView(card, Ui.margins(a, 0, 0, 0, 8));

        pulseBtn.setOnClickListener(v -> { if (pulsing) stopPulse(true); else startPulse(); });
        dirBtn.setOnClickListener(v -> setDir(!dirOn));

        updateInfo();
        return root;
    }

    void detach() {
        if (pulsing) stopPulse(true);
        ui.removeCallbacks(watch);
        pulseBtn = dirBtn = null;
        pulseCap = dirCap = info = status = null;
    }

    // ---------------------------------------------------------------- Xpulse

    private void startPulse() {
        if (settings() == null) return;
        host.send("M150P" + PULSE_SECONDS);
        host.log("Diagnosis: M150 – 1 kHz 50 % square wave on X step for " + PULSE_SECONDS + " s");
        pulsing = true;
        pulseStartMs = System.currentTimeMillis();
        pulseEndMs = pulseStartMs + PULSE_SECONDS * 1000L;
        pulseBtn.setBackgroundResource(R.drawable.btn_stop);
        pulseBtn.setTextColor(Ui.ERR);
        pulseCap.setText("1KHz");
        blink = ObjectAnimator.ofFloat(pulseBtn, "alpha", 1f, 0.35f);
        blink.setDuration(400);
        blink.setRepeatMode(ValueAnimator.REVERSE);
        blink.setRepeatCount(ValueAnimator.INFINITE);
        blink.start();
        say("");
        ui.post(watch);
    }

    /** Ends the pulse display when its time is up (the firmware stops by itself), or on alarm / disconnect. */
    private final Runnable watch = new Runnable() {
        @Override public void run() {
            if (!pulsing) return;
            long now = System.currentTimeMillis();
            if (!host.connected()) { stopPulse(false); say("Disconnected"); return; }
            if (host.machineState().startsWith("Alarm")) { stopPulse(false); say("Grbl went into ALARM – see the console"); return; }
            if (now >= pulseEndMs) { stopPulse(false); return; }
            ui.postDelayed(this, 100);
        }
    };

    /** @param early stop the square wave in the firmware too (button pressed again, page left) */
    private void stopPulse(boolean early) {
        if (early && host.connected()) host.send("M150P0");
        pulsing = false;
        ui.removeCallbacks(watch);
        if (blink != null) { blink.cancel(); blink = null; }
        if (pulseBtn == null) return;
        pulseBtn.setAlpha(1f);
        pulseBtn.setBackgroundResource(R.drawable.btn_tech);
        pulseBtn.setTextColor(Ui.TEXT);
        pulseCap.setText("");
    }

    // ---------------------------------------------------------------- Xdir

    private void setDir(boolean on) {
        if (pulsing) { say("Wait for the pulse to finish"); return; }
        double[] s = settings();
        if (s == null) return;
        dirOn = on;
        // one step: the move sets the DIR pin and Grbl leaves it there
        host.send(String.format(Locale.US, "$J=G91G21X%.4fF100", sign() / s[0]));
        host.log("Diagnosis: X DIR " + (on ? "high" : "low") + " (one-step jog)");
        dirBtn.setBackgroundResource(on ? R.drawable.btn_accent : R.drawable.btn_tech);
        dirBtn.setTextColor(on ? Ui.ACCENT : Ui.TEXT);
        dirCap.setText(on ? "ON" : "");
        say("");
        updateInfo();
    }

    /** Jog sign that leaves the X DIR pin at the level Xdir is set to. */
    private int sign() {
        boolean invert = (intSetting("$3") & 1) != 0;
        boolean minus = dirOn != invert;                  // Grbl sets DIR when moving towards minus
        return minus ? -1 : 1;
    }

    // ---------------------------------------------------------------- helpers

    /** {steps/mm, max rate, accel} for X, or null (with a message) when not ready. */
    private double[] settings() {
        if (!host.connected()) { say("Not connected to the controller"); return null; }
        if (host.busy()) { say("A program is running"); return null; }
        String st = host.machineState();
        if (st.startsWith("Alarm")) { say("Grbl is in ALARM – unlock ($X) first"); return null; }
        if (!st.isEmpty() && !st.equals("Idle")) { say("Grbl is " + st + " – wait until it is Idle"); return null; }
        double spm = numSetting("$100");
        if (spm <= 0) {
            host.send("$$");
            say("Reading the Grbl settings – try again in a moment");
            return null;
        }
        return new double[]{spm, numSetting("$110"), numSetting("$120")};
    }

    private void updateInfo() {
        if (info == null) return;
        info.setText("Xpulse: 1 kHz, 50 % square wave on the X step pin (PA0) for 10 s – the machine does not move."
                + " Needs Blue Pill firmware r4 (M150); older firmware answers error:20.\n"
                + "Xdir: ON = DIR pin high, OFF = DIR pin low (set with a one-step jog).");
    }

    private void say(String s) { if (status != null) status.setText(s); }

    private double numSetting(String key) {
        try { return Double.parseDouble(host.grblSettings().get(key)); } catch (Exception e) { return 0; }
    }

    private int intSetting(String key) { return (int) numSetting(key); }

    private TextView caption() {
        TextView t = Ui.text(a, "", 15, Ui.TEXT);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, Ui.dp(a, 6), 0, 0);
        return t;
    }

    private LinearLayout column(Button b, TextView cap) {
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        c.addView(b, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(a, 64)));
        c.addView(cap);
        return c;
    }
}
