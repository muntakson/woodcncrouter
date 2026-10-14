package org.dnaboy.grblsender;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;
import java.util.Map;

/**
 * Settings → Diagnosis: check the X / Y / Z / A step (pulse) and direction wires with a scope or a logic probe.
 * Two tabs: "Pulse / Dir" (the pin checks) and "Axis test" (real moves with a live position readout).
 *
 *  - Xpulse … Apulse send M150 X|Y|Z|A P10: our Blue Pill firmware (r6) or Pro Mini firmware (1.1h-diag; its
 *    A is the spare A4 pin, as Grbl on the 328P has no A axis) toggles that step pin
 *    from a spare timer, 1 kHz pulses, 100 us wide, for 10 s, without moving the machine; M150 P0 stops
 *    it early. Only one pin pulses at a time.
 *  - Xdir … Adir jog that axis by a single step in the direction that leaves its DIR pin high (ON)
 *    or low (OFF). Grbl keeps a DIR pin at the level of the last move until a reset. The DIR level of a
 *    move is "moving towards minus" XOR that axis's bit of $3 (direction invert mask).
 *  - X / Y / Z / A test sets that axis's work zero here and runs one real cycle, e.g. G1 X100 F500 then
 *    G1 X0 (Z only goes 20 mm up), so step pulses and both DIR levels can be watched on a scope while
 *    the axis moves. The live work position of all axes is shown under the buttons.
 *  - Home sensors: live state of the X / Y / Z home sensor inputs (Grbl and FluidNC call them limit pins) from
 *    the "Pn:" field of the status report. Grbl applies $5 first, so a letter there means "sensor triggered";
 *    it reports the pins whether or not homing ($22) or hard limits ($21) are on.
 */
final class DiagSection {

    interface Host {
        boolean connected();
        boolean busy();
        String machineState();
        /** Work position of axis 0..3 (X, Y, Z, A). */
        float workPos(int axis);
        /** Axes in Grbl's status report: 3, or 4 when the controller has an A axis. */
        int axes();
        /** Active input pins from the last status report ("Pn:" field), e.g. "XZ"; empty when none. */
        String pins();
        /** True for the Arduino Pro Mini board (pins D2… instead of PA0…). */
        boolean proMini();
        /** True for the FluidNC ESP32 board: no M150, its step pins sit behind I2S shift registers. */
        boolean fluidnc();
        /** FluidNC with our M150 (woodcncrouter build, version tag "-wcr"): pulses and DIR levels by M150. */
        boolean fluidM150();
        void realtime(byte b);
        /** Soft reset (Ctrl-X) that also clears the app's send queue. */
        void reset();
        Map<String, String> grblSettings();
        void send(String cmd);
        void log(String msg);
    }

    private static final int PULSE_SECONDS = 10;
    private static final String[] AXES = {"X", "Y", "Z", "A"};
    /** Test travel per axis: Z only goes 20 mm up so it cannot run into the top of its travel. */
    private static final int[] TEST_MM = {100, 100, 20, 100};
    private static final int TEST_FEED = 500;

    private final Activity a;
    private final Host host;
    private final Handler ui = new Handler(Looper.getMainLooper());
    /** Pulse / dir rows: X, Y, Z, A. */
    private final Button[] pulseBtn = new Button[4], dirBtn = new Button[4];
    private final TextView[] pulseCap = new TextView[4], dirCap = new TextView[4];
    private TextView info, status;
    private ObjectAnimator blink;
    private boolean pulsing;
    private int pulseAxis;
    private final boolean[] dirOn = new boolean[4];
    private long pulseStartMs, pulseEndMs;
    private final Button[] testBtn = new Button[4];
    private TextView testPos, testState;
    private boolean testing, testMoved, testStopping;
    private int testAxis;
    private long testStartMs;
    private final TextView[] homeLamp = new TextView[3];
    private TextView homeOther, homeInfo;
    private String homeShown;
    private static final int TABS = 3;
    private final Button[] tabBtn = new Button[TABS];
    private final View[] tabPage = new View[TABS];
    /** Tab shown when the page is opened again. */
    private static int lastTab;

    DiagSection(Activity a, Host host) {
        this.a = a;
        this.host = host;
    }

    View build() {
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(Ui.heading(a, "Diagnosis", "Check the X / Y / Z / A step (pulse) and direction outputs with a scope or a logic probe"));

        LinearLayout tabs = Ui.row(a);
        String[] tabNames = {"Pulse / Dir", "Axis test", "Home sensors"};
        for (int i = 0; i < TABS; i++) {
            final int t = i;
            tabBtn[i] = Ui.button(a, tabNames[i], 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(a, 48), 1);
            if (i > 0) lp.leftMargin = Ui.dp(a, 8);
            tabs.addView(tabBtn[i], lp);
            tabBtn[i].setOnClickListener(v -> showTab(t));
        }
        root.addView(tabs, Ui.margins(a, 0, 0, 0, 6));
        status = Ui.text(a, "", 13, Ui.WARN);       // shared by both tabs
        status.setPadding(0, 0, 0, Ui.dp(a, 6));
        root.addView(status);
        FrameLayout pages = new FrameLayout(a);
        root.addView(pages, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        LinearLayout pulsePage = page(pages, 0), testPage = page(pages, 1), homePage = page(pages, 2);

        LinearLayout card = Ui.card(a);
        for (int i = 0; i < 4; i++) {
            final int axis = i;
            LinearLayout row = Ui.row(a);
            row.setGravity(Gravity.TOP);

            pulseBtn[i] = Ui.button(a, AXES[i] + "pulse", 0);
            pulseCap[i] = caption();
            row.addView(column(pulseBtn[i], pulseCap[i]), Ui.weight(1));

            View gap = new View(a);
            row.addView(gap, new LinearLayout.LayoutParams(Ui.dp(a, 16), 1));

            dirBtn[i] = Ui.button(a, AXES[i] + "dir", 0);
            dirCap[i] = caption();
            row.addView(column(dirBtn[i], dirCap[i]), Ui.weight(1));
            card.addView(row, Ui.margins(a, 0, i > 0 ? 8 : 0, 0, 0));

            pulseBtn[i].setOnClickListener(v -> {
                if (pulsing && pulseAxis == axis) stopPulse(true);
                else if (testing) say("Wait for the " + AXES[testAxis] + " test to finish");
                else startPulse(axis);                 // also takes over from another axis's pulse
            });
            dirBtn[i].setOnClickListener(v -> setDir(axis, !dirOn[axis]));
            showDir(i);
        }

        info = Ui.text(a, "", 13, Ui.MUTED);
        info.setPadding(0, Ui.dp(a, 12), 0, 0);
        card.addView(info);
        pulsePage.addView(card, Ui.margins(a, 0, 0, 0, 8));

        LinearLayout tcard = Ui.card(a);
        LinearLayout trow = Ui.row(a);
        for (int i = 0; i < 4; i++) {
            final int axis = i;
            testBtn[i] = Ui.button(a, AXES[i] + " test", 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(a, 64), 1);
            if (i > 0) lp.leftMargin = Ui.dp(a, 8);
            trow.addView(testBtn[i], lp);
            testBtn[i].setOnClickListener(v -> {
                if (!testing) startTest(axis);
                else if (axis == testAxis) stopTest();
                else say("Wait for the " + AXES[testAxis] + " test to finish");
            });
        }
        tcard.addView(trow);
        testPos = Ui.text(a, "", 24, Ui.TEXT);
        testPos.setTypeface(android.graphics.Typeface.MONOSPACE);
        testPos.setPadding(0, Ui.dp(a, 12), 0, 0);
        tcard.addView(testPos);
        testState = Ui.text(a, "", 13, Ui.MUTED);
        tcard.addView(testState);
        TextView tinfo = Ui.text(a, "X / Y / A test: sets that axis to 0 where it is now, then G1 to " + TEST_MM[0]
                + " at F" + TEST_FEED + " and back to 0. Z test goes " + TEST_MM[2] + " mm up and back."
                + " A needs a controller with a 4th axis (Blue Pill). Press the button again to stop.", 13, Ui.MUTED);
        tinfo.setPadding(0, Ui.dp(a, 12), 0, 0);
        tcard.addView(tinfo);
        testPage.addView(tcard, Ui.margins(a, 0, 0, 0, 8));

        LinearLayout hcard = Ui.card(a);
        LinearLayout hrow = Ui.row(a);
        for (int i = 0; i < 3; i++) {
            homeLamp[i] = Ui.text(a, "", 18, Ui.MUTED);
            homeLamp[i].setGravity(Gravity.CENTER);
            homeLamp[i].setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(a, 96), 1);
            if (i > 0) lp.leftMargin = Ui.dp(a, 8);
            hrow.addView(homeLamp[i], lp);
        }
        hcard.addView(hrow);
        homeOther = Ui.text(a, "", 13, Ui.MUTED);
        homeOther.setPadding(0, Ui.dp(a, 10), 0, 0);
        hcard.addView(homeOther);
        homeInfo = Ui.text(a, "", 13, Ui.MUTED);
        homeInfo.setPadding(0, Ui.dp(a, 12), 0, 0);
        hcard.addView(homeInfo);
        homePage.addView(hcard, Ui.margins(a, 0, 0, 0, 8));
        homeShown = null;

        ui.post(posWatch);
        showTab(lastTab);

        updateInfo();
        return root;
    }

    /** A scrolling tab page inside pages; returns its content column. */
    private LinearLayout page(FrameLayout pages, int i) {
        ScrollView sv = new ScrollView(a);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        sv.addView(col);
        pages.addView(sv, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        tabPage[i] = sv;
        return col;
    }

    private void showTab(int t) {
        lastTab = t;
        for (int i = 0; i < TABS; i++) {
            tabPage[i].setVisibility(i == t ? View.VISIBLE : View.GONE);
            tabBtn[i].setBackgroundResource(i == t ? R.drawable.btn_accent : R.drawable.btn_tech);
            tabBtn[i].setTextColor(i == t ? Ui.ACCENT : Ui.TEXT);
        }
    }

    void detach() {
        if (pulsing) stopPulse(true);
        ui.removeCallbacks(watch);
        ui.removeCallbacks(testWatch);        // a running axis test finishes on its own
        ui.removeCallbacks(posWatch);
        testing = false;
        java.util.Arrays.fill(pulseBtn, null);
        java.util.Arrays.fill(dirBtn, null);
        java.util.Arrays.fill(pulseCap, null);
        java.util.Arrays.fill(dirCap, null);
        java.util.Arrays.fill(testBtn, null);
        java.util.Arrays.fill(tabBtn, null);
        java.util.Arrays.fill(tabPage, null);
        java.util.Arrays.fill(homeLamp, null);
        testPos = testState = homeOther = homeInfo = null;
        info = status = null;
    }

    // ---------------------------------------------------------------- X / Y / Z / A pulse

    private void startPulse(int axis) {
        if (host.fluidnc() && !host.fluidM150()) { say("This FluidNC has no M150 – flash the woodcncrouter build (Settings → Firmware)"); return; }
        if ((axis < 3 || !host.proMini()) && !hasAxis(axis)) return;   // the Pro Mini pulses A on its spare A4 pin
        if (settings(Math.min(axis, 2)) == null) return;
        if (pulsing) stopPulse(false);                 // the firmware switches pins by itself
        host.send("M150" + AXES[axis] + "P" + PULSE_SECONDS);
        host.log("Diagnosis: M150 – 1 kHz, 100 µs pulses on " + AXES[axis] + " step for " + PULSE_SECONDS + " s");
        pulsing = true;
        pulseAxis = axis;
        pulseStartMs = System.currentTimeMillis();
        pulseEndMs = pulseStartMs + PULSE_SECONDS * 1000L;
        Button b = pulseBtn[axis];
        b.setBackgroundResource(R.drawable.btn_stop);
        b.setTextColor(Ui.ERR);
        pulseCap[axis].setText("1KHz");
        blink = ObjectAnimator.ofFloat(b, "alpha", 1f, 0.35f);
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

    /** @param early stop the pulses in the firmware too (button pressed again, page left) */
    private void stopPulse(boolean early) {
        if (early && host.connected()) host.send("M150P0");
        pulsing = false;
        ui.removeCallbacks(watch);
        if (blink != null) { blink.cancel(); blink = null; }
        Button b = pulseBtn[pulseAxis];
        if (b == null) return;
        b.setAlpha(1f);
        b.setBackgroundResource(R.drawable.btn_tech);
        b.setTextColor(Ui.TEXT);
        pulseCap[pulseAxis].setText("");
    }

    // ---------------------------------------------------------------- X / Y / Z / A test

    private void startTest(int axis) {
        if (pulsing) { say("Wait for the pulse to finish"); return; }
        if (settings(Math.min(axis, 2)) == null) return;
        if (!hasAxis(axis)) return;
        String n = AXES[axis];
        if (axis < 3 && host.pins().contains(n))
            host.log("⚠ " + n + " home sensor reads TRIGGERED. If it stays like that after an interrupted homing, FluidNC "
                    + "older than v4.1.1-wcr2 sends no step pulses to " + n + " (the move reports ok but the motor stands still) "
                    + "– restart the board, then check Diagnosis → Home sensors.");
        host.send("G10L20P0" + n + "0");               // work zero of this axis at the current position
        host.send("G90G21G1" + n + TEST_MM[axis] + "F" + TEST_FEED);
        host.send("G1" + n + "0");
        host.log("Diagnosis: " + n + " test, " + n + "0 → " + n + TEST_MM[axis] + " → " + n + "0 at F" + TEST_FEED);
        testing = true;
        testAxis = axis;
        testMoved = testStopping = false;
        testStartMs = System.currentTimeMillis();
        for (int i = 0; i < 4; i++) testBtn[i].setAlpha(i == axis ? 1f : 0.4f);
        testBtn[axis].setText("Stop");
        testBtn[axis].setBackgroundResource(R.drawable.btn_stop);
        testBtn[axis].setTextColor(Ui.ERR);
        say("");
        ui.post(testWatch);
    }

    /** Feed hold, then a soft reset once stopped: drops the rest of the cycle without an alarm. */
    private void stopTest() {
        if (testStopping) return;
        testStopping = true;
        host.realtime((byte) '!');
        testState.setText("Stopping…");
    }

    /** Live work position of every axis and the home sensor states while the page is open. */
    private final Runnable posWatch = new Runnable() {
        @Override public void run() {
            if (testPos == null) return;
            showHome();
            if (!host.connected()) testPos.setText("X —  Y —  Z —");
            else {
                StringBuilder b = new StringBuilder();
                for (int i = 0; i < Math.min(4, host.axes()); i++)
                    b.append(i > 0 ? "  " : "").append(String.format(Locale.US, "%s %.3f", AXES[i], host.workPos(i)));
                testPos.setText(b);
            }
            ui.postDelayed(this, 100);
        }
    };

    private final Runnable testWatch = new Runnable() {
        @Override public void run() {
            if (!testing || testState == null) return;
            String st = host.machineState();
            String n = AXES[testAxis];
            testState.setText(n + " test: " + (testStopping ? "stopping… (" + st + ")" : st));
            if (!host.connected()) { endTest("Disconnected"); return; }
            if (st.startsWith("Alarm")) { endTest("Grbl went into ALARM – see the console"); return; }
            if (st.startsWith("Run")) testMoved = true;
            if (testStopping && st.equals("Hold:0")) {
                host.reset();
                endTest(String.format(Locale.US, "%s test stopped at %s %.3f", n, n, host.workPos(testAxis)));
                return;
            }
            if (!testStopping && st.equals("Idle") && (testMoved || System.currentTimeMillis() - testStartMs > 3000)) {
                if (testMoved) {
                    // the last move (back to 0) went towards minus, so the DIR pin is at that level now
                    dirOn[testAxis] = (intSetting("$3") & (1 << testAxis)) == 0;
                    showDir(testAxis);
                    endTest(n + " test done – back at " + n + " 0");
                } else {
                    endTest("Grbl did not move – see the console");
                }
                return;
            }
            ui.postDelayed(this, 100);
        }
    };

    private void endTest(String msg) {
        testing = false;
        ui.removeCallbacks(testWatch);
        if (testState == null) return;
        for (int i = 0; i < 4; i++) {
            testBtn[i].setAlpha(1f);
            testBtn[i].setText(AXES[i] + " test");
            testBtn[i].setBackgroundResource(R.drawable.btn_tech);
            testBtn[i].setTextColor(Ui.TEXT);
        }
        testState.setText(msg);
    }

    // ---------------------------------------------------------------- X / Y / Z home sensors

    private void showHome() {
        if (homeLamp[0] == null) return;
        boolean on = host.connected();
        String pins = on ? host.pins() : "";
        String key = (on ? "c" : "d") + host.machineState() + "|" + pins;
        if (key.equals(homeShown)) return;
        homeShown = key;
        for (int i = 0; i < 3; i++) {
            boolean hit = on && pins.indexOf(AXES[i].charAt(0)) >= 0;
            int color = !on ? Ui.MUTED : hit ? Ui.ERR : Ui.OK;
            homeLamp[i].setText(AXES[i] + " home\n" + (!on ? "—" : hit ? "TRIGGERED" : "open"));
            homeLamp[i].setTextColor(color);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setCornerRadius(Ui.dp(a, 8));
            bg.setColor(hit ? 0x33FF5C6C : Ui.PANEL2);
            bg.setStroke(Ui.dp(a, 2), color);
            homeLamp[i].setBackground(bg);
        }
        if (!on) homeOther.setText("Not connected to the controller");
        else {
            StringBuilder o = new StringBuilder();
            String[][] names = {{"A", "A home sensor"}, {"P", "Probe"}, {"D", "Door"}, {"H", "Feed hold"}, {"R", "Reset"}, {"S", "Cycle start"}};
            for (String[] n : names) if (pins.contains(n[0])) o.append(o.length() > 0 ? ", " : "").append(n[1]);
            homeOther.setText("Other active inputs: " + (o.length() > 0 ? o : "none")
                    + (host.machineState().startsWith("Alarm") ? "   (Grbl is in ALARM – $X unlocks)" : ""));
        }
        homeInfo.setText(homeHelp());
    }

    private String homeHelp() {
        String pinsTxt;
        if (host.fluidnc()) pinsTxt = "Inputs: X gpio.33, Y gpio.32, Z gpio.35, active low (card terminals X-LIM / Y-LIM / Z-LIM, "
                + "limit_all_pin in config.yaml – FluidNC calls the home sensors limit switches).";
        else if (host.proMini()) pinsTxt = "Pins: X D9, Y D10, Z D12 – internal pull-up, pulled to GND = triggered (unless $5 inverts).";
        else pinsTxt = "Pins: X PB12, Y PB13, Z PB14 – internal pull-up, pulled to GND = triggered (unless $5 inverts).";
        String hom = host.grblSettings().get("$22"), hard = host.grblSettings().get("$21");
        String state = "$22 homing = " + (hom != null ? hom : "?") + ", $21 hard limits (stop when a home sensor fires outside homing) = "
                + (hard != null ? hard : "?");
        String how = "Bring metal to each home sensor (or trigger it by hand): its box turns red (TRIGGERED) and back to green (open) "
                + "when you take it away.\n" + pinsTxt + "\n";
        if (host.fluidnc())
            return how
                    + "If a box is red with nothing at the sensor: measure the input. About 3 V means the sensor is idle – restart "
                    + "the board (after an interrupted homing, FluidNC older than v4.1.1-wcr2 keeps the sensor \"triggered\" and that "
                    + "axis will not move). Near 0 V means the sensor or its wiring pulls the input low. If the boxes are green when "
                    + "triggered and red when idle, remove or add \":low\" on that limit_all_pin in config.yaml (FluidNC has no $5).\n"
                    + state;
        String inv = host.grblSettings().get("$5");
        return how
                + "If a box is red with nothing at the sensor, the input is floating or the polarity is reversed – flip $5 "
                + "(home sensor input invert) or check the wiring.\n"
                + "$5 = " + (inv != null ? inv : "?") + ", " + state
                + (inv == null && host.connected() ? " (send $$ to read them)" : "");
    }

    // ---------------------------------------------------------------- X / Y / Z / A dir

    private void setDir(int axis, boolean on) {
        if (pulsing) { say("Wait for the pulse to finish"); return; }
        if (testing) { say("Wait for the " + AXES[testAxis] + " test to finish"); return; }
        if (!hasAxis(axis)) return;
        if (host.fluidM150()) {
            // FluidNC build with M150: set the DIR output directly, no step
            dirOn[axis] = on;
            host.send("M150" + AXES[axis] + "D" + (on ? 1 : 0));
            host.log("Diagnosis: " + AXES[axis] + " DIR " + (on ? "high" : "low") + " (M150 D)");
            showDir(axis);
            say("");
            return;
        }
        double[] s = settings(axis);
        if (s == null) return;
        dirOn[axis] = on;
        // one step: the move sets the DIR pin and Grbl leaves it there
        host.send(String.format(Locale.US, "$J=G91G21%s%.4fF100", AXES[axis], sign(axis) / s[0]));
        host.log("Diagnosis: " + AXES[axis] + " DIR " + (on ? "high" : "low") + " (one-step jog)");
        showDir(axis);
        say("");
    }

    private void showDir(int axis) {
        if (dirBtn[axis] == null) return;
        boolean on = dirOn[axis];
        dirBtn[axis].setBackgroundResource(on ? R.drawable.btn_accent : R.drawable.btn_tech);
        dirBtn[axis].setTextColor(on ? Ui.ACCENT : Ui.TEXT);
        dirCap[axis].setText(on ? "ON" : "");
    }

    /** Jog sign that leaves the axis's DIR pin at the level its dir button is set to. */
    private int sign(int axis) {
        boolean invert = (intSetting("$3") & (1 << axis)) != 0;
        boolean minus = dirOn[axis] != invert;            // Grbl sets DIR when moving towards minus
        return minus ? -1 : 1;
    }

    // ---------------------------------------------------------------- helpers

    /** {steps/mm, max rate, accel} for axis 0..3, or null (with a message) when not ready. */
    private double[] settings(int axis) {
        if (!host.connected()) { say("Not connected to the controller"); return null; }
        if (host.busy()) { say("A program is running"); return null; }
        String st = host.machineState();
        if (st.startsWith("Alarm")) { say("Grbl is in ALARM – unlock ($X) first"); return null; }
        if (!st.isEmpty() && !st.equals("Idle")) { say("Grbl is " + st + " – wait until it is Idle"); return null; }
        double spm = numSetting("$10" + axis);
        if (spm <= 0) {
            host.send("$$");
            say("Reading the Grbl settings – try again in a moment");
            return null;
        }
        return new double[]{spm, numSetting("$11" + axis), numSetting("$12" + axis)};
    }

    private void updateInfo() {
        if (info == null) return;
        if (host.fluidM150()) info.setText("X/Y/Z/A pulse: 1 kHz, 100 µs wide pulses on that step output (I2S shift register:"
                + " X I2SO.2, Y I2SO.5, Z I2SO.10, A I2SO.13 – the card's X/Y/Z/A-STEP terminals) for 10 s; nothing moves in"
                + " FluidNC's planner, but a connected, enabled driver turns its motor.\n"
                + "X/Y/Z/A dir: ON = DIR terminal high, OFF = low (M150 <axis> D1 / D0; X I2SO.1, Y I2SO.4, Z I2SO.9, A I2SO.12).");
        else if (host.fluidnc()) info.setText("FluidNC (ESP32): the X/Y/Z/A pulse buttons need M150, which only the woodcncrouter FluidNC "
                + "build has (Settings → Firmware → (C) FluidNC). Until then use the Axis test tab or jog.\n"
                + "X/Y/Z/A dir: ON = DIR high, OFF = DIR low, set with a one-step jog (only meaningful if the dir pin is not inverted).");
        else if (host.proMini()) info.setText("X/Y/Z/A pulse: 1 kHz, 100 µs wide pulses on that step pin (X D2, Y D3, Z D4,"
                + " A = spare pin A4) for 10 s – the machine does not move. Needs Pro Mini firmware 1.1h-diag;"
                + " older firmware answers error:20.\n"
                + "X/Y/Z dir: ON = DIR pin high, OFF = DIR pin low (X D5, Y D6, Z D7; set with a one-step jog).\n"
                + "A dir: the Pro Mini has no A axis and no free pin for an A DIR.");
        else info.setText("X/Y/Z/A pulse: 1 kHz, 100 µs wide pulses on that step pin (X PA0, Y PA1, Z PA2, A PA3) for 10 s"
                + " – the machine does not move. Needs Blue Pill firmware r6 (M150 with an axis);"
                + " older firmware answers error:20.\n"
                + "X/Y/Z/A dir: ON = DIR pin high, OFF = DIR pin low (X PA4, Y PA5, Z PA6, A PA7; set with a one-step jog).\n"
                + "A needs a controller with a 4th axis (Blue Pill).");
    }

    /** False (with a message) for the A axis on a 3-axis controller. */
    private boolean hasAxis(int axis) {
        if (host.connected() && axis >= host.axes()) { say("This controller has no " + AXES[axis] + " axis"); return false; }
        return true;
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
