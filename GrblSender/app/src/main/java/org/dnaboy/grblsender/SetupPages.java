package org.dnaboy.grblsender;

import android.app.Activity;
import android.app.Dialog;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import java.util.Locale;
import java.util.Map;

/**
 * ☰ → Wiring and ☰ → CNC setup: how both controller boards connect to the router
 * (MA860H drivers, MK300 spindle inverter, home sensors) and the mechanics → $ settings.
 * The pictures are AI illustrations (GrblSender/art/gen_images.py); the tables hold the exact values.
 */
final class SetupPages {

    /** What the pages need from the activity. */
    interface Host {
        boolean connected();
        boolean busy();                       // a job is running
        String board();                       // MainActivity.BOARD_PROMINI / BOARD_BLUEPILL / BOARD_FLUIDNC
        Map<String, String> grblSettings();   // last $$ report, "$100" -> "81.490"
        void send(String cmd);
        void log(String msg);
        SharedPreferences prefs();
        /** Sends cmd and returns its reply lines (FluidNC: $CD), or null when busy / on error. */
        void capture(String cmd, Lines done);
        /** FluidNC: XMODEM upload to its file system. */
        void uploadFluid(String name, byte[] content, Done done);
        /** FluidNC: $Bye, then back runs once it has restarted and its settings are read again. */
        void restartFluid(Runnable back);
        /** Keeps a text file in the app's fluidnc/ folder on the tablet. */
        void saveLocal(String name, String text);
        /** Homing cycle: "" = all axes ($H), "X" / "Y" / "Z" = one axis ($HX …). */
        void home(String axis);
        /** Grbl state ("Idle", "Home", "Alarm:…") and machine position, for the live line under the home buttons. */
        String machineStatus();
        /** Last machine position (MPos) from the status report: X, Y, Z, A. */
        float[] machinePos();
        /** Work coordinate offset (WCO) from the status report: WPos = MPos − WCO. */
        float[] wco();
        /** Soft reset (Ctrl-X): stops all motion at once, also a homing cycle. */
        void estop();
        /** Feed hold (!) / cycle resume (~). */
        void hold();
        void resume();
    }

    interface Lines { void got(java.util.List<String> lines); }

    interface Done { void done(boolean ok, String message); }

    private static final int BG = 0xFF1D2024, FG = 0xFFE3E6EA, DIM = 0xFF8B939C, CELL = 0xFF111316,
            HEAD = 0xFF24476E, WARN = 0xFFF0C050, GOOD = 0xFF4CD07D;
    /** Row marks for {@link #table(String[][], int[])}: must-set (red) and important (yellow) parameters. */
    private static final int RED = 0xFFFF5555, YEL = 0xFFFFE040;

    private final Activity a;
    private final Host host;
    private final float dp;

    SetupPages(Activity a, Host host) {
        this.a = a;
        this.host = host;
        this.dp = a.getResources().getDisplayMetrics().density;
    }

    // ---------------------------------------------------------------- page scaffolding

    private LinearLayout body;
    /** Tabs of the current page: {@link #tab(String)} adds one; its contents go into its own pane. */
    private LinearLayout tabBar, content;
    private ScrollView scroll;
    private final java.util.List<Button> tabButtons = new java.util.ArrayList<>();
    private final java.util.List<LinearLayout> tabPanes = new java.util.ArrayList<>();
    private String tabPref;

    private Dialog page(String title, LinearLayout extraButtons) {
        int pad = px(16);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(pad, pad, pad, pad);
        TextView t = text(title, 22, FG);
        t.setTypeface(null, Typeface.BOLD);
        root.addView(t);

        tabButtons.clear();
        tabPanes.clear();
        tabPref = null;
        tabBar = new LinearLayout(a);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setPadding(0, px(8), 0, px(8));
        tabBar.setVisibility(View.GONE);
        root.addView(tabBar);

        scroll = new ScrollView(a);
        content = body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout buttons = extraButtons != null ? extraButtons : new LinearLayout(a);
        Button close = button("Close");
        buttons.addView(close, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(buttons);

        Dialog d = new Dialog(a, android.R.style.Theme_Material_NoActionBar_Fullscreen);
        d.setContentView(root);
        close.setOnClickListener(v -> d.dismiss());
        return d;
    }

    /** Starts a new tab: what is added after this goes into its pane. */
    private void tab(String label) {
        LinearLayout pane = new LinearLayout(a);
        pane.setOrientation(LinearLayout.VERTICAL);
        pane.setVisibility(tabPanes.isEmpty() ? View.VISIBLE : View.GONE);
        content.addView(pane);
        tabPanes.add(pane);
        body = pane;
        Button b = button(label);
        b.setTextSize(15);
        int i = tabButtons.size();
        b.setOnClickListener(v -> selectTab(i));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, px(52), 1);
        lp.rightMargin = px(4);
        tabBar.addView(b, lp);
        tabButtons.add(b);
        tabBar.setVisibility(View.VISIBLE);
    }

    /** Shows tab i (and remembers it under tabPref so the page reopens on it). */
    private void selectTab(int i) {
        if (i < 0 || i >= tabPanes.size()) i = 0;
        for (int k = 0; k < tabPanes.size(); k++) {
            boolean on = k == i;
            tabPanes.get(k).setVisibility(on ? View.VISIBLE : View.GONE);
            Button b = tabButtons.get(k);
            b.setBackgroundColor(on ? HEAD : CELL);
            b.setTextColor(on ? 0xFFFFFFFF : DIM);
            b.setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
        }
        scroll.scrollTo(0, 0);
        if (tabPref != null) host.prefs().edit().putInt(tabPref, i).apply();
    }

    private int px(float v) { return (int) (v * dp); }

    private TextView text(CharSequence s, float size, int color) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        return t;
    }

    private Button button(String label) {
        Button b = new Button(a);
        b.setText(label);
        b.setAllCaps(false);
        return b;
    }

    private void h(String s) {
        TextView t = text(s, 18, FG);
        t.setTypeface(null, Typeface.BOLD);
        t.setPadding(0, px(18), 0, px(6));
        body.addView(t);
    }

    private TextView p(CharSequence s) { return p(s, 0xFFC8CDD3); }

    private TextView p(CharSequence s, int color) {
        TextView t = text(s, 15, color);
        t.setPadding(0, px(2), 0, px(6));
        t.setLineSpacing(0, 1.15f);
        body.addView(t);
        return t;
    }

    private void image(int resId, String caption) { image(resId, caption, 1f); }

    /** An illustration at a fraction of the page width (centred), with a caption below. */
    private void image(int resId, String caption, float widthFraction) {
        ImageView iv = new ImageView(a);
        iv.setImageResource(resId);
        iv.setAdjustViewBounds(true);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setPadding(0, px(10), 0, px(2));
        LinearLayout row = new LinearLayout(a);
        row.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        row.setWeightSum(1f);
        row.addView(iv, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, widthFraction));
        body.addView(row, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView c = text(caption, 12, DIM);
        c.setPadding(0, 0, 0, px(6));
        body.addView(c);
    }

    /** First row is the header. */
    private void table(String[][] rows) { table(rows, null); }

    /** As {@link #table(String[][])}; marks[r] (RED / YEL, 0 = none) colours row r's text and tints its cells. */
    private void table(String[][] rows, int[] marks) {
        TableLayout tl = new TableLayout(a);
        tl.setStretchAllColumns(true);
        tl.setShrinkAllColumns(true);
        for (int r = 0; r < rows.length; r++) {
            TableRow tr = new TableRow(a);
            for (String cell : rows[r]) {
                int mark = marks != null && r < marks.length ? marks[r] : 0;
                TextView t = text(cell, 14, r == 0 ? 0xFFFFFFFF : mark != 0 ? mark : FG);
                if (r == 0 || mark != 0) t.setTypeface(null, Typeface.BOLD);
                t.setBackgroundColor(r == 0 ? HEAD : mark == RED ? 0xFF3A1A1C : mark == YEL ? 0xFF33301A : CELL);
                t.setPadding(px(8), px(6), px(8), px(6));
                TableRow.LayoutParams lp = new TableRow.LayoutParams();
                lp.setMargins(0, 0, px(1), px(1));
                tr.addView(t, lp);
            }
            tl.addView(tr);
        }
        body.addView(tl);
    }

    /** MK300 spindle inverter parameters (CNC setup page); same list as mk300_settings.md in the repo. */
    private void mk300Settings() {
        h("Spindle inverter (MK300)");
        p("The board only switches the spindle on and off (M3 / M5 → X1–COM). The speed comes from the MK300 itself: "
                + "Hz × 60 = RPM, so 400 Hz = 24000 RPM. Spindle stopped, PRG → group → ENTER → ▲▼ to the parameter → ENTER, "
                + "change with ▲▼ / ◄, ENTER to save.");
        p("Red = must be set or the spindle will not work / is unsafe. Yellow = important, check it.", DIM);
        table(new String[][]{
                {"Parameter", "Value", "Meaning"},
                {"P1-04", "400.00 Hz", "Rated motor frequency. Left at 50 Hz the drive over-volts the 400 Hz spindle and its current limit holds it at about 17 Hz (slow, humming, hot)"},
                {"P1-01", "4.0 kW", "Rated motor power (nameplate)"},
                {"P1-02", "nameplate V (220)", "Rated motor voltage"},
                {"P1-03", "nameplate A", "Rated motor current (about 13 A for 4 kW / 220 V)"},
                {"P1-05", "24000 rpm", "Rated motor speed"},
                {"P0-10", "400.00 Hz", "Maximum frequency (factory 50 Hz = 3000 RPM). Set before P1-04, P0-12, P0-08"},
                {"P0-12", "400.00 Hz", "Upper limit frequency: caps every speed"},
                {"P0-11", "0", "Upper limit from P0-12 (1 / 2 = unwired AI input → about 0 Hz)"},
                {"P0-08", "400.00 Hz", "Preset frequency = spindle speed on M3 (333.3 = 20000, 300 = 18000 RPM)"},
                {"P0-03", "0 or 1", "Speed from panel / P0-08 (2 = AI1, unwired → no speed)"},
                {"P0-07", "0", "Main frequency source only (no X + Y mixing)"},
                {"P0-02", "1", "Run command from the terminals (0 = panel; made the spindle run at power-on)"},
                {"P8-18", "1", "Ignore a run signal already on at power-up or fault reset"},
                {"P0-17", "5–8 s", "Acceleration time (G-code waits 10 s after M3; too short trips the current limit)"},
                {"P4-38", "00001", "X1 logic. On this unit 00001 gives short X1–COM = run, open = stop (fail-safe). Trust the bare-wire test"},
                {"P0-01", "2", "V/F control"},
                {"P3-00", "0", "Linear V/F"},
                {"P3-01", "0–2 %", "Torque boost, keep small (a large boost over-volts at low Hz)"},
                {"P4-00", "1", "X1 = forward run (FWD)"},
                {"P4-11", "0", "Two-line mode 1"},
                {"P6-10", "0", "Decelerate to stop"},
                {"P7-01", "1", "JOG (MF) key toggles panel ↔ terminal; the LOC/REM LED must be on for X1 to work"},
        }, new int[]{0, RED, YEL, YEL, YEL, YEL, RED, RED, YEL, YEL, YEL, 0, RED, YEL, YEL, YEL, 0, 0, 0, 0, 0, 0, YEL});
        p("Check while running: ►► / SHIFT cycles the display (Hz, V, A). Stuck far below the preset with the current at the "
                + "rated value = current limit → recheck P1-04 and the other P1 values.", WARN);
    }

    // ---------------------------------------------------------------- Wiring

    void showWiring() {
        Dialog d = page("Wiring", null);
        RadioGroup g = new RadioGroup(a);
        g.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton pro = new RadioButton(a), blue = new RadioButton(a), fluid = new RadioButton(a);
        pro.setId(View.generateViewId());
        blue.setId(View.generateViewId());
        fluid.setId(View.generateViewId());
        pro.setText("Arduino Pro Mini");
        blue.setText("STM32F103C8T6 Blue / Black Pill");
        fluid.setText("FluidNC ESP32 6-axis");
        for (RadioButton rb : new RadioButton[]{pro, blue, fluid}) { rb.setTextColor(FG); rb.setTextSize(16); g.addView(rb); }
        LinearLayout holder = (LinearLayout) body.getParent().getParent();   // root
        holder.addView(g, 1);
        String board = host.board();
        g.check(MainActivity.BOARD_BLUEPILL.equals(board) ? blue.getId()
                : MainActivity.BOARD_FLUIDNC.equals(board) ? fluid.getId() : pro.getId());
        if (MainActivity.BOARD_FLUIDNC.equals(board)) fillWiringFluidnc(); else fillWiring(MainActivity.BOARD_BLUEPILL.equals(board));
        g.setOnCheckedChangeListener((gr, id) -> {
            if (id == fluid.getId()) fillWiringFluidnc(); else fillWiring(id == blue.getId());
        });
        d.show();
    }

    private void fillWiring(boolean blue) {
        body.removeAllViews();
        String b = blue ? "Blue Pill" : "Pro Mini";
        image(blue ? R.drawable.art_wiring_bluepill : R.drawable.art_wiring_promini,
                "Illustration (AI-generated). Use the tables below for the exact pins and terminals.");

        p(blue ? "Board: Blue Pill STM32F103C8T6 running Grbl 1.1f (robomechs port). Outputs are 3.3 V. "
                + "The home sensor inputs PB12–PB14 are 5 V tolerant; the step and DIR pins are not."
                : "Board: Arduino Pro Mini 3.3 V / 8 MHz running Grbl 1.1h (D13 spindle-enable build). Outputs are 3.3 V, "
                + "and no input tolerates more than 3.3 V.");

        h("1. Stepper drivers (MA860H ×3)");
        p("Common-cathode wiring (active high): the board drives PUL+ / DIR+, and PUL− / DIR− go to the board's GND. "
                + "This matches Grbl's defaults $2=0 and $3=0.");
        table(blue ? new String[][]{
                {"Signal", "Blue Pill", "MA860H terminal"},
                {"X step", "PA0", "X driver PUL+"},
                {"Y step", "PA1", "Y driver PUL+"},
                {"Z step", "PA2", "Z driver PUL+"},
                {"X dir", "PA3", "X driver DIR+"},
                {"Y dir", "PA4", "Y driver DIR+"},
                {"Z dir", "PA5", "Z driver DIR+"},
                {"Stepper disable (optional)", "PA6", "ENA+ of all three"},
                {"GND", "G", "PUL−, DIR−, ENA− of all three"},
        } : new String[][]{
                {"Signal", "Pro Mini", "MA860H terminal"},
                {"X step", "D2", "X driver PUL+"},
                {"Y step", "D3", "Y driver PUL+"},
                {"Z step", "D4", "Z driver PUL+"},
                {"X dir", "D5", "X driver DIR+"},
                {"Y dir", "D6", "Y driver DIR+"},
                {"Z dir", "D7", "Z driver DIR+"},
                {"Stepper disable (optional)", "D8", "ENA+ of all three"},
                {"GND", "GND", "PUL−, DIR−, ENA− of all three"},
        });
        p("ENA can stay unconnected: the drivers are then always enabled. If you wire it, the pin goes HIGH to switch the "
                + "drivers off (offline), which matches $4=0.");
        p("The MA860H inputs are made for 5 V signals (manual: \"VCC 5 V, R short\"). 3.3 V gives less optocoupler current. "
                + "If a motor misses steps or only works at low speed, put a 5 V buffer (74HCT245) or an opto board between "
                + b + " and the drivers.", WARN);
        p("MA860H DIP switches for 1600 pulses/rev: SW5 ON · SW6 OFF · SW7 ON · SW8 ON.\n"
                + "SW4 OFF = half current after 200 ms without pulses (cooler motors); ON = full holding current, better for Z.\n"
                + "Phase current: SW1–SW3, set to the NEMA34 nameplate current (RMS):");
        table(new String[][]{
                {"SW1", "SW2", "SW3", "Peak A", "RMS A"},
                {"on", "on", "on", "2.8", "2.0"},
                {"off", "on", "on", "3.5", "2.5"},
                {"on", "off", "on", "4.2", "3.0"},
                {"off", "off", "on", "4.9", "3.5"},
                {"on", "on", "off", "5.7", "4.0"},
                {"off", "on", "off", "6.4", "4.6"},
                {"on", "off", "off", "7.0", "5.0"},
                {"off", "off", "off", "7.8", "5.6"},
        });
        p("Driver power: AC 18–80 V or DC 24–110 V. Motor: A+ A− B+ B− to the NEMA34 coils "
                + "(series wiring = rated current, parallel = 1.73× current, faster).");

        h("2. Spindle on/off → MK300 inverter");
        p("Grbl switches the spindle with M3 / M5. Speed is set on the MK300 panel. Never connect the 3.3 V board to X1 "
                + "directly: X1 sits at about +24 V. Use a PC817 optocoupler:");
        table(new String[][]{
                {"From", "Through", "To"},
                {blue ? "PB13 (spindle enable)" : "D13 (spindle enable)", "150 Ω", "PC817 pin 1 (anode)"},
                {blue ? "G" : "GND", "", "PC817 pin 2 (cathode)"},
                {"PC817 pin 4 (collector)", "", "MK300 X1"},
                {"PC817 pin 3 (emitter)", "", "MK300 COM"},
        });
        p("Keep the MK300's factory SEL–+24 link. Keep the board's GND and the MK300 COM separate: the opto is the "
                + "isolation barrier. " + (blue ? "PA8 is the PWM output (unused, for a future 0–10 V speed signal)."
                : "D11 is the PWM output (unused, for a future 0–10 V speed signal). The on-board LED on D13 lights while the spindle is on."));
        p("MK300 parameters:");
        table(new String[][]{
                {"Parameter", "Value", "Meaning"},
                {"P0-02", "1", "Run command from the terminals"},
                {"P4-00", "1", "X1 = forward run (FWD)"},
                {"P4-11", "0", "Two-line mode 1"},
                {"P8-18", "1", "Ignore a run signal already on at power-up"},
                {"P6-10", "0", "Decelerate to stop"},
                {"P0-17", "≤ 8 s", "Acceleration time (G-code waits 10 s after M3)"},
                {"P0-03 / P0-08", "panel / preset", "Speed: preset frequency (see the spindle nameplate, e.g. 400 Hz = 24000 RPM)"},
                {"P1-04", "400 Hz", "Rated motor frequency – full MK300 list on the CNC setup page"},
        }, new int[]{0, RED, 0, 0, YEL, 0, YEL, YEL, RED});
        p("Fail-safe: reset, alarm, USB loss and power-up all drop the enable pin, and the spindle decelerates to a stop.");

        h("3. Home sensors");
        table(blue ? new String[][]{
                {"Home sensor", "Blue Pill", "Other side"},
                {"Home X", "PB12", "G"},
                {"Home Y", "PB13", "G"},
                {"Home Z", "PB14", "G"},
                {"Probe (optional)", "PA15", "G"},
        } : new String[][]{
                {"Home sensor", "Pro Mini", "Other side"},
                {"Home X", "D9", "GND"},
                {"Home Y", "D10", "GND"},
                {"Home Z", "D12", "GND"},
                {"Probe (optional)", "A5", "GND"},
        });
        p("Grbl calls these inputs \"limit pins\"; on this router they are the home sensors. Grbl uses the internal pull-ups: "
                + "a pin reads \"triggered\" when it is pulled to GND. Active-low (normally-open) sensors: $5=0. "
                + "Active-high (normally-closed) sensors: $5=1.");
        p("Inductive proximity sensors (NPN, 12–24 V) must go through a PC817 opto module. Never wire their 24 V "
                + "output to the " + b + "." + (blue ? " PB12–PB14 tolerate 5 V, not 24 V." : ""), WARN);
        p("Homing settings to start with: $22=1 (homing on), $23 = direction mask (bit 0 X, bit 1 Y, bit 2 Z; set a bit if that "
                + "axis homes toward +), $24=25 (feed), $25=500 (seek), $26=250 (debounce), $27=2 (pull-off mm). Grbl homes Z first, then X and Y. "
                + "After homing, $20=1 (soft limits) uses $130–$132 from the CNC setup page.");

        h("4. Power and USB");
        p("Tablet → USB OTG → " + (blue ? "Blue Pill micro-USB (native USB, 0483:5740)" : "Pro Mini CH340 adapter (38400 baud)")
                + ". The board is powered from USB. Join the GND of the board and of the drivers' signal side; keep the "
                + "drivers' motor supply and the MK300 mains wiring away from the signal cables.");
    }

    /** Macrobase "6 axis CNC card" (ESP32 + FluidNC), pins from its stock config.yaml. */
    private void fillWiringFluidnc() {
        body.removeAllViews();
        image(R.drawable.art_wiring_fluidnc,
                "Macrobase 6-axis ESP32 card (vendor photo): driver terminals on top, spindle / mist on the right, "
                + "probe, home sensor (LIM) and macro pins on the right header, USB and TF card at the bottom.", 0.6f);
        p("Board: Macrobase 6-axis ESP32 card running FluidNC. Step, dir and enable go out through I2S shift registers; "
                + "every pin is set in config.yaml on the board, not in $ settings. Supply 12–24 V DC to the board "
                + "(it cuts off above 30 V); USB alone only powers the ESP32.");

        h("1. Stepper drivers (MA860H ×3)");
        p("Common-cathode wiring: the card's X-STEP / X-DIR / X-EAN terminals go to PUL+ / DIR+ / ENA+, and the card's "
                + "GND terminal to PUL−, DIR−, ENA− of all drivers (the Macrobase manual: \"GND – all drivers PUL−, ENA−, DIR−\").");
        table(new String[][]{
                {"Signal", "Card terminal", "MA860H terminal", "config.yaml"},
                {"X step / dir / enable", "X-STEP, X-DIR, X-EAN", "X PUL+, DIR+, ENA+", "I2SO.2 / .1 / .0"},
                {"Y step / dir / enable", "Y-STEP, Y-DIR, Y-EAN", "Y PUL+, DIR+, ENA+", "I2SO.5 / .4 / .7"},
                {"Z step / dir / enable", "Z-STEP, Z-DIR, Z-EAN", "Z PUL+, DIR+, ENA+", "I2SO.10 / .9 / .8"},
                {"A step / dir / enable", "A-STEP, A-DIR, A-EAN", "(4th axis)", "I2SO.13 / .12 / .15"},
                {"B, C", "B-…, C-…", "(5th, 6th axis)", "I2SO.16–23"},
                {"GND", "GND", "PUL−, DIR−, ENA− of all drivers", ""},
        });
        p("The stock config.yaml has steps_per_mm 800 and 5000 mm/min on every axis, made for StepStick modules. "
                + "Set this router's values before the first move (CNC setup page, or the console): "
                + "$/axes/x/steps_per_mm=83.33, $/axes/y/steps_per_mm=83.33, $/axes/z/steps_per_mm=320, $/axes/z/max_rate_mm_per_min=1000, then $CD=config.yaml "
                + "to save. If an axis runs the wrong way, add :low to its direction_pin in config.yaml.", WARN);
        p("Measure the STEP terminal voltage before wiring the MA860H: if it is 3.3 V the opto current is low; "
                + "use a 5 V buffer (74HCT245) if a motor misses steps.");

        h("2. Spindle on/off → MK300 inverter");
        p("Spindle on/off: GPIO12 (PWM/enable_pin in config.yaml, set 2026-10-12; the stock file had it as flood coolant). "
                + "M3 / M5 switch it, like D13 / PB13 on the other boards. GPIO27 is the PWM output (PWM-OUT terminal, its "
                + "voltage set with PWM-ADJ), unused while the MK300 sets the speed. Wire GPIO12 through a PC817 optocoupler "
                + "exactly as for the other boards (GPIO12 → 150 Ω → PC817 pin 1, GND → pin 2, pin 4 → X1, pin 3 → COM). "
                + "Never connect the card to X1 directly: X1 sits at about +24 V.", WARN);

        h("3. Home sensors and probe");
        table(new String[][]{
                {"Home sensor", "Card terminal", "ESP32 pin"},
                {"Home X", "X-LIM", "GPIO33 (active low)"},
                {"Home Y", "Y-LIM", "GPIO32 (active low)"},
                {"Home Z", "Z-LIM", "GPIO35 (active low)"},
                {"Probe", "PROBE", "GPIO34 (active low)"},
                {"Feed hold", "", "GPIO36"},
                {"Sensor ground", "GND", ""},
        });
        p("Active-low home sensors: the input is about 3 V when idle and pulled to GND when the sensor fires. The card labels the "
                + "terminals X-LIM / Y-LIM / Z-LIM and FluidNC calls them limit_all_pin; on this router they are the home sensors. GPIO34–36 are input-only and have no internal pull-up; the card supplies them. "
                + "Homing (cycle, direction, speeds, pull-off) is set per axis under axes/…/homing in config.yaml, not with $22–$27. "
                + "Inductive 12–24 V sensors must go through an opto module.");

        h("4. Power and USB");
        p("Tablet → USB OTG → the card's USB (CH340K, 115200 baud). Join the card's GND and the drivers' signal GND; keep "
                + "motor and mains wiring away from the signal cables. FluidNC also offers Wi-Fi and a WebUI (see Settings → Firmware).");
    }

    // ---------------------------------------------------------------- CNC setup

    /** Grbl steps/mm for a rack & pinion axis: driver pulses/rev × reduction ÷ (pinion teeth × rack pitch). */
    static double stepsPerMm(double pulsesPerRev, double reduction, double teeth, double pitchMm) {
        double mmPerMotorRev = teeth * pitchMm / reduction;
        return mmPerMotorRev > 0 ? pulsesPerRev / mmPerMotorRev : 0;
    }

    private EditText field(LinearLayout row, String label, String key, String def) {
        TextView l = text(label, 15, FG);
        row.addView(l, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f));
        EditText e = new EditText(a);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setTextColor(FG);
        e.setText(host.prefs().getString(key, def));
        e.setTag(key);
        row.addView(e, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return e;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(android.view.Gravity.CENTER_VERTICAL);
        body.addView(r);
        return r;
    }

    private static double num(EditText e) {
        try { return Double.parseDouble(e.getText().toString().trim().replace(',', '.')); } catch (NumberFormatException x) { return 0; }
    }

    // ---------------------------------------------------------------- CNC setup

    /** MA860H DIP switches SW5–SW8 (manual table 1): pulses/rev and the switch positions that give it. */
    static final int[] MA860H_PULSES = {400, 800, 1600, 3200, 6400, 12800, 25600, 51200, 1000, 2000, 5000, 10000, 25000, 50000};
    private static final String[] MA860H_SW = {
            "on on on on", "off on on on", "on off on on", "off off on on", "on on off on", "off on off on", "on off off on",
            "off off off on", "on on on off", "off on on off", "on off on off", "off off on off", "on on off off", "off on off off"};

    static String ma860hSwitches(int pulses) {
        for (int i = 0; i < MA860H_PULSES.length; i++) {
            if (MA860H_PULSES[i] != pulses) continue;
            String[] sw = MA860H_SW[i].split(" ");
            return "SW5 " + sw[0] + ", SW6 " + sw[1] + ", SW7 " + sw[2] + ", SW8 " + sw[3];
        }
        return "not an MA860H setting";
    }

    /** Practical NEMA34 speed on an MA860H: torque falls off steeply above this. */
    private static final int MOTOR_MAX_RPM = 600;
    private static final int DEFAULT_G0 = 1000;
    private static final String[] AX = {"X", "Y", "Z"};

    /** Highest step rate of the controller's firmware (pulses/s per axis). */
    private int controllerHz() {
        String b = host.board();
        if (MainActivity.BOARD_FLUIDNC.equals(b)) return 125000;   // I2S: 1e6 / (2 × 2 frames × 2 µs)
        if (MainActivity.BOARD_BLUEPILL.equals(b)) return 80000;   // grbl-stm32: "less than 80 kHz recommended"
        return 15000;                                              // Grbl on an 8 MHz ATmega328P
    }

    /** Max G0 for an axis: the lower of the motor limit (600 rpm) and the controller's step rate, rounded down to 100. */
    private int maxRate(double stepsPerMm, int pulsesPerRev) {
        if (stepsPerMm <= 0) return 0;
        double motor = MOTOR_MAX_RPM * pulsesPerRev / stepsPerMm;
        double ctrl = controllerHz() * 60.0 / stepsPerMm;
        return (int) (Math.min(motor, ctrl) / 100) * 100;
    }

    /** Suggested G0: DEFAULT_G0, or the axis max when that is lower (Z on its 1 mm/rev screw). */
    private int defaultRate(double stepsPerMm, int pulsesPerRev) {
        int max = maxRate(stepsPerMm, pulsesPerRev);
        return max > 0 ? Math.min(DEFAULT_G0, max) : DEFAULT_G0;
    }

    private String rateTip(double stepsPerMm, int pulsesPerRev) {
        if (stepsPerMm <= 0) return "Default " + DEFAULT_G0 + " mm/min";
        double mmRev = pulsesPerRev / stepsPerMm;
        int motor = (int) (MOTOR_MAX_RPM * mmRev), ctrl = (int) (controllerHz() * 60.0 / stepsPerMm);
        return String.format(Locale.US, "Default %,d · max ≈ %,d mm/min (motor %d rpm × %.1f mm/rev = %,d; controller %d kHz = %,d). "
                        + "Raise in steps (2000, 3000, 5000) and jog the full length after each.",
                defaultRate(stepsPerMm, pulsesPerRev), maxRate(stepsPerMm, pulsesPerRev), MOTOR_MAX_RPM, mmRev, motor, controllerHz() / 1000, ctrl);
    }

    /** Spinner with light text on the page background. */
    private android.widget.Spinner spinner(String[] items) {
        android.widget.Spinner sp = new android.widget.Spinner(a);
        android.widget.ArrayAdapter<String> ad = new android.widget.ArrayAdapter<String>(a, android.R.layout.simple_spinner_item, items) {
            @Override public View getView(int pos, View cv, android.view.ViewGroup parent) {
                TextView t = (TextView) super.getView(pos, cv, parent);
                t.setTextColor(FG);
                t.setTextSize(15);
                return t;
            }
        };
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
        return sp;
    }

    private EditText numField(String value) {
        EditText e = new EditText(a);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setTextColor(FG);
        e.setTextSize(15);
        e.setSelectAllOnFocus(true);
        e.setText(value);
        return e;
    }

    /** One X / Y / Z row of the "Axes & homing" table. */
    private static final class AxisRow {
        android.widget.CheckBox dirInvert;
        RadioButton homeMinus, homePlus;
        EditText rate, seek, travel;
        TextView tip;
    }

    /** Current machine values, from Grbl's $$ or FluidNC's config ($CD). */
    private static final class AxisState {
        boolean[] dirInvert = new boolean[3], homeMinus = new boolean[3];
        double[] rate = new double[3], seek = new double[3], travel = new double[3], steps = new double[3];
        boolean homing;
        int dirMask, homeMask;                // Grbl: whole $3 / $23, so other axes' bits are kept
        FluidConfig fluid;                    // FluidNC: the config it came from
    }

    void showCncSetup() {
        final boolean fluid = MainActivity.BOARD_FLUIDNC.equals(host.board());
        LinearLayout buttons = new LinearLayout(a);
        Button apply = button("Apply to " + (fluid ? "FluidNC" : "Grbl"));
        apply.setTextColor(GOOD);
        buttons.addView(apply, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.6f));
        Dialog d = page("CNC setup", buttons);

        tab("Machine");
        image(R.drawable.art_cnc_router, "Illustration (AI-generated): 1325 × 2500 mm gantry router, 4 kW ER32 spindle, MK300 inverter.");

        int savedPulses;
        try { savedPulses = Integer.parseInt(host.prefs().getString("cnc_pulses", "1600").trim()); } catch (NumberFormatException e) { savedPulses = 1600; }
        h("Machine");
        table(new String[][]{
                {"Part", "This router"},
                {"Bed", "1325 × 2500 mm (X = 2500 long side, Y = 1325)"},
                {"X / Y drive", "NEMA34 → 1:5 belt reduction → pinion → rack (4 mm pitch)"},
                {"Z drive", "NEMA34 → direct (no reduction) → ball screw (5 mm lead)"},
                {"Stepper drivers", "MA860H, microstepping set below (DIP switches SW5–SW8)"},
                {"Spindle", "4 kW, ER32 collet, max 24000 RPM, on/off by M3 / M5 via MK300"},
        });

        image(R.drawable.art_cnc_drivetrain, "Illustration (AI-generated): X/Y with the 1:5 belt. Z (not drawn right): motor direct on a 5 mm-lead screw.");

        tab("Stepper");
        h("Microstepping (MA860H)");
        p("Set the same SW5–SW8 positions on all three drivers (power off), then pick the setting here. Apply scales the "
                + "controller's steps/mm (and so a measured calibration) by new ÷ old pulses/rev.");
        final int appliedPulses = savedPulses;
        String[] msItems = new String[MA860H_PULSES.length];
        int msSel = 2;
        for (int i = 0; i < MA860H_PULSES.length; i++) {
            msItems[i] = String.format(Locale.US, "%,d pulses/rev   (%s)", MA860H_PULSES[i], ma860hSwitches(MA860H_PULSES[i]));
            if (MA860H_PULSES[i] == savedPulses) msSel = i;
        }
        android.widget.Spinner micro = spinner(msItems);
        micro.setSelection(msSel);
        body.addView(micro);
        TextView microNote = p("", DIM);

        h("Steps per mm");
        p("X/Y: steps/mm = pulses/rev × reduction ÷ (pinion teeth × rack pitch). Z: steps/mm = pulses/rev × reduction ÷ screw lead.\n"
                + "Rack: true 4 mm circular pitch with the 24T pinion (the defaults) = 83.33 at 1600 pulses/rev; the X/Y travel "
                + "measured on this machine on 2026-10-26 fits it within 0.4 % (X 83.52, Y 83.66 set on the board). "
                + "A module 1.25 rack would be 3.927 mm (84.88). "
                + "Z: motor direct on a 5 mm-lead screw (no belt reduction, measured 2026-10-14) = 320 steps/mm, 5 mm per motor rev.");
        EditText red = field(row(), "X/Y reduction (1:n)", "cnc_red", "5");
        // new keys: tablets saved 3.927 (module 1.25) and 25T before the rack was measured as 4 mm / 24T
        EditText teeth = field(row(), "X/Y pinion teeth", "cnc_teeth24", "24");
        EditText pitch = field(row(), "X/Y rack pitch (mm)", "cnc_rack_pitch4", "4.0");
        // new key: tablets saved cnc_zred = 5 while Z was thought to have a 1:5 belt
        EditText zRed = field(row(), "Z reduction (1:n)", "cnc_zred_direct", "1");
        EditText zLead = field(row(), "Z screw lead (mm/rev)", "cnc_zlead", "5");
        TextView result = p("", GOOD);
        result.setTextSize(18);
        result.setTypeface(null, Typeface.BOLD);
        TextView current = p("", DIM);
        android.widget.CheckBox writeComputed = new android.widget.CheckBox(a);
        writeComputed.setText("Write these computed steps/mm on Apply (replaces a value calibrated by measuring)");
        writeComputed.setTextColor(FG);
        body.addView(writeComputed);
        p("Then check it: jog X 500 mm and measure. New $100 = current $100 × 500 ÷ measured distance. Same for Y and Z (use 50 mm on Z).");

        // ---- Axes & homing
        tab("Axis & homing");
        h("Axes & homing");
        p("DIR invert: tick an axis that moves the wrong way (its position counts up while it moves towards the home sensor "
                + "at the minus end, for example). Home toward: the end of the axis where its home sensor sits. "
                + (fluid ? "FluidNC homes Z first, then X and Y together (Apply sets homing cycle 1 for Z and 2 for X/Y)."
                         : "Grbl homes Z first, then X and Y together (fixed in the firmware)."));
        LinearLayout head = row();
        String[] cols = {"Axis", "DIR invert", "Home toward", "G0 speed\nmm/min ⓘ", "Home seek\nmm/min", "Travel\nmm"};
        float[] wts = {0.5f, 0.9f, 1.3f, 1.1f, 1f, 1f};
        for (int c = 0; c < cols.length; c++) {
            TextView t = text(cols[c], 13, 0xFFFFFFFF);
            t.setTypeface(null, Typeface.BOLD);
            t.setBackgroundColor(HEAD);
            t.setPadding(px(6), px(4), px(6), px(4));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, wts[c]);
            lp.rightMargin = px(1);
            head.addView(t, lp);
        }
        AxisRow[] rows = new AxisRow[3];
        for (int i = 0; i < 3; i++) {
            AxisRow r = rows[i] = new AxisRow();
            LinearLayout line = row();
            line.setPadding(0, px(4), 0, 0);
            TextView name = text(AX[i], 20, FG);
            name.setTypeface(null, Typeface.BOLD);
            line.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, wts[0]));
            r.dirInvert = new android.widget.CheckBox(a);
            r.dirInvert.setText("invert");
            r.dirInvert.setTextColor(FG);
            line.addView(r.dirInvert, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, wts[1]));
            RadioGroup g = new RadioGroup(a);
            g.setOrientation(RadioGroup.HORIZONTAL);
            r.homeMinus = new RadioButton(a);
            r.homePlus = new RadioButton(a);
            r.homeMinus.setId(View.generateViewId());
            r.homePlus.setId(View.generateViewId());
            r.homeMinus.setText(AX[i] + "−");
            r.homePlus.setText(AX[i] + "+");
            for (RadioButton rb : new RadioButton[]{r.homeMinus, r.homePlus}) { rb.setTextColor(FG); g.addView(rb); }
            line.addView(g, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, wts[2]));
            r.rate = numField("");
            r.seek = numField("");
            r.travel = numField("");
            line.addView(r.rate, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, wts[3]));
            line.addView(r.seek, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, wts[4]));
            line.addView(r.travel, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, wts[5]));
            if (!fluid && i > 0) r.seek.setEnabled(false);          // Grbl has one homing seek rate ($25)
            r.tip = p("", DIM);
            r.tip.setTextSize(12);
        }
        android.widget.CheckBox homingOn = new android.widget.CheckBox(a);
        homingOn.setText("Homing on ($22=1) – Grbl then starts in ALARM until you home or unlock ($X)");
        homingOn.setTextColor(FG);
        if (!fluid) body.addView(homingOn);
        // what is ticked/typed here but not written yet – nothing is saved until Apply
        TextView pendingNote = p("", WARN);
        pendingNote.setTypeface(null, Typeface.BOLD);
        final Runnable[] pending = new Runnable[1];
        final int[] pendingCount = new int[1];
        LinearLayout homeRow = row();
        homeRow.setPadding(0, px(10), 0, 0);
        String[][] homeBtns = {{"Home X", "X"}, {"Home Y", "Y"}, {"Home Z", "Z"}, {"Home all", ""}};
        for (String[] hb : homeBtns) {
            Button b = button(hb[0]);
            b.setTextColor(hb[1].isEmpty() ? GOOD : FG);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, px(56), 1);
            lp.rightMargin = px(6);
            homeRow.addView(b, lp);
            b.setOnClickListener(v -> {
                if (!host.connected()) { toast("Connect to the board first"); return; }
                if (host.busy()) { toast("Homing is disabled while a job runs"); return; }
                if (pendingCount[0] > 0) {
                    new android.app.AlertDialog.Builder(a)
                            .setTitle("Changes not applied yet")
                            .setMessage("Homing uses the settings in the controller now, not the ones changed on this page. "
                                    + "Press \"Apply to " + (fluid ? "FluidNC" : "Grbl") + "\" first.")
                            .setPositiveButton("Home anyway", (dlg, which) -> host.home(hb[1]))
                            .setNegativeButton("Cancel", null)
                            .show();
                    return;
                }
                host.home(hb[1]);
            });
        }
        LinearLayout stopRow = row();
        stopRow.setPadding(0, px(6), 0, 0);
        Button estop = button("E-STOP"), hold = button("Hold"), resume = button("Resume");
        estop.setTextColor(0xFFFFFFFF);
        estop.setTypeface(null, Typeface.BOLD);
        estop.setTextSize(18);
        estop.setBackgroundColor(0xFFC62828);
        hold.setTextColor(WARN);
        resume.setTextColor(GOOD);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(0, px(64), 2);
        elp.rightMargin = px(6);
        stopRow.addView(estop, elp);
        for (Button b : new Button[]{hold, resume}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, px(64), 1);
            lp.rightMargin = px(6);
            stopRow.addView(b, lp);
        }
        estop.setOnClickListener(v -> { if (host.connected()) host.estop(); else toast("Not connected"); });
        hold.setOnClickListener(v -> { if (host.connected()) host.hold(); else toast("Not connected"); });
        resume.setOnClickListener(v -> { if (host.connected()) host.resume(); else toast("Not connected"); });
        TextView homeState = p("", WARN);
        Runnable homeWatch = new Runnable() {
            @Override public void run() {
                if (!d.isShowing()) return;
                homeState.setText(host.connected() ? host.machineStatus() : "Not connected");
                homeState.postDelayed(this, 250);
            }
        };
        d.setOnShowListener(x -> homeState.post(homeWatch));
        p("Home all runs Z first, then X and Y together. Each axis moves towards its home sensor at the home seek speed, "
                + "backs off, touches it again slowly and sets that position as machine zero. Apply your changes before homing.\n"
                + "E-STOP: soft reset – motion stops at once (homing too), Grbl goes to ALARM and the position is lost; "
                + "unlock with $X or home again. It is software only: keep the machine's hardware E-stop within reach. "
                + "Hold: decelerates and pauses (not during homing, which only E-STOP stops); Resume continues.", DIM);
        TextView axisNote = p("", DIM);
        p("Travel is how far each axis can move; homing searches up to 1.1 × (FluidNC) or 1.5 × (Grbl) of it for the home sensor, "
                + "so it must be at least the real travel (X 2500, Y 1325 on this bed). Home seek is the speed towards the sensor; "
                + "it then backs off and touches it again slowly.");

        final AxisState[] state = new AxisState[1];
        final double[] newSteps = new double[3];

        Runnable calc = () -> {
            int pulses = MA860H_PULSES[micro.getSelectedItemPosition()];
            double xy = stepsPerMm(pulses, num(red), num(teeth), num(pitch));
            double z = stepsPerMm(pulses, num(zRed), 1, num(zLead));
            newSteps[0] = newSteps[1] = xy;
            newSteps[2] = z;
            double mmRevXY = num(teeth) * num(pitch) / Math.max(1e-9, num(red));
            double mmRevZ = num(zLead) / Math.max(1e-9, num(zRed));
            microNote.setText(String.format(Locale.US, "%,d pulses/rev: %s on every driver. Resolution X/Y %.4f mm, Z %.4f mm per pulse.",
                    pulses, ma860hSwitches(pulses), xy > 0 ? 1 / xy : 0, z > 0 ? 1 / z : 0));
            result.setText(String.format(Locale.US, "X / Y: %.1f steps/mm  ($100 = $101 = %.3f)\nZ: %.1f steps/mm  ($102 = %.3f)",
                    xy, xy, z, z));
            Map<String, String> s = host.grblSettings();
            String scaled = "";
            if (pulses != appliedPulses && s.containsKey("$100")) {
                double k = (double) pulses / appliedPulses;
                scaled = String.format(Locale.US, "\nApply writes X %.3f  Y %.3f  Z %.3f (now × %s for %,d → %,d pulses/rev).",
                        dnum(s, "$100") * k, dnum(s, "$101") * k, dnum(s, "$102") * k, fmt(round3(k)), appliedPulses, pulses);
            }
            current.setText(String.format(Locale.US, "Travel per motor rev: X/Y %.2f mm, Z %.2f mm.\n%s%s", mmRevXY, mmRevZ,
                    s.containsKey("$100")
                            ? "In the controller now: X " + s.get("$100") + "  Y " + s.get("$101") + "  Z " + s.get("$102") + " steps/mm"
                            : "Connect to see the values in the controller now.", scaled));
            for (int i = 0; i < 3; i++) {
                double k = (double) pulses / appliedPulses;
                double ts = writeComputed.isChecked() || !s.containsKey("$10" + i) ? newSteps[i] : dnum(s, "$10" + i) * k;
                String tip = AX[i] + " G0: " + rateTip(ts, pulses);
                rows[i].tip.setText(tip);
                if (android.os.Build.VERSION.SDK_INT >= 26) rows[i].rate.setTooltipText(tip);
                rows[i].rate.setHint(String.valueOf(defaultRate(ts, pulses)));
            }
            SharedPreferences.Editor ed = host.prefs().edit();
            for (EditText e : new EditText[]{red, teeth, pitch, zRed, zLead}) ed.putString((String) e.getTag(), e.getText().toString());
            ed.apply();
        };
        TextWatcher w = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int af) { }
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { calc.run(); }
        };
        for (EditText e : new EditText[]{red, teeth, pitch, zRed, zLead}) e.addTextChangedListener(w);
        writeComputed.setOnCheckedChangeListener((cb, on) -> calc.run());
        micro.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) { calc.run(); }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) { }
        });
        // Grbl: the Y / Z seek fields mirror X ($25 is one value)
        if (!fluid) rows[0].seek.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int af) { }
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { rows[1].seek.setText(s); rows[2].seek.setText(s); }
        });
        calc.run();

        String applyLabel = "Apply to " + (fluid ? "FluidNC" : "Grbl");
        pending[0] = () -> {
            AxisState st = state[0];
            StringBuilder b = new StringBuilder(), warn = new StringBuilder();
            int n = 0;
            if (st != null) for (int i = 0; i < 3; i++) {
                AxisRow r = rows[i];
                boolean dir = r.dirInvert.isChecked() != st.dirInvert[i], side = r.homeMinus.isChecked() != st.homeMinus[i];
                if (dir) { n++; b.append(AX[i]).append(" DIR ").append(r.dirInvert.isChecked() ? "inverted" : "normal").append(" · "); }
                if (side) { n++; b.append(AX[i]).append(" homes toward ").append(AX[i]).append(r.homeMinus.isChecked() ? "−" : "+").append(" · "); }
                if (dir && side) warn.append("\n⚠ ").append(AX[i]).append(": inverting DIR and switching the home side together leaves homing moving "
                        + "the same physical way as before. If ").append(AX[i]).append(" homes away from its home sensor, change only one of them.");
                if (num(r.rate) != st.rate[i]) { n++; b.append(AX[i]).append(" G0 speed · "); }
                if ((fluid || i == 0) && num(r.seek) != st.seek[i]) { n++; b.append(fluid ? AX[i] : "").append(" home seek · "); }
                if (num(r.travel) != st.travel[i]) { n++; b.append(AX[i]).append(" travel · "); }
            }
            if (st != null && !fluid && homingOn.isChecked() != st.homing) { n++; b.append("Homing on/off · "); }
            pendingCount[0] = n;
            pendingNote.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
            if (n > 0) pendingNote.setText("Not saved yet (" + n + "): " + b.substring(0, b.length() - 3)
                    + "\nPress \"" + applyLabel + "\" at the bottom to write them – ticking a box only changes this page." + warn);
            apply.setText(n > 0 ? applyLabel + "  (" + n + " unsaved)" : applyLabel);
            apply.setTextColor(n > 0 ? WARN : GOOD);
        };
        TextWatcher pw = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int af) { }
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { pending[0].run(); }
        };
        for (AxisRow r : rows) {
            r.dirInvert.setOnCheckedChangeListener((cb, on) -> pending[0].run());
            r.homeMinus.setOnCheckedChangeListener((cb, on) -> pending[0].run());
            for (EditText e : new EditText[]{r.rate, r.seek, r.travel}) e.addTextChangedListener(pw);
        }
        homingOn.setOnCheckedChangeListener((cb, on) -> pending[0].run());
        pending[0].run();

        Runnable[] load = new Runnable[1];
        Runnable show = () -> {
            AxisState st = state[0];
            boolean ok = st != null;
            for (int i = 0; i < 3; i++) {
                AxisRow r = rows[i];
                for (View v : new View[]{r.dirInvert, r.homeMinus, r.homePlus, r.rate, r.travel}) v.setEnabled(ok);
                r.seek.setEnabled(ok && (fluid || i == 0));
                if (!ok) continue;
                r.dirInvert.setChecked(st.dirInvert[i]);
                (st.homeMinus[i] ? r.homeMinus : r.homePlus).setChecked(true);
                r.rate.setText(fmt(st.rate[i]));
                r.seek.setText(fmt(st.seek[i]));
                r.travel.setText(fmt(st.travel[i]));
            }
            homingOn.setEnabled(ok);
            if (ok) homingOn.setChecked(st.homing);
            apply.setEnabled(ok);
            calc.run();
            pending[0].run();
        };
        load[0] = () -> {
            state[0] = null;
            show.run();
            if (!host.connected()) { axisNote.setText("Connect to the board to read and change these values."); return; }
            if (fluid) {
                axisNote.setText("Reading the FluidNC config ($CD)…");
                host.capture("$CD", lines -> {
                    if (lines == null || lines.isEmpty()) {
                        axisNote.setText("Could not read the FluidNC config – the board is busy or did not answer. Close and open CNC setup again.");
                        return;
                    }
                    state[0] = fromFluid(new FluidConfig(lines));
                    if (state[0] == null) { axisNote.setText("The FluidNC config has no axes x / y / z section."); return; }
                    axisNote.setText("Values read from FluidNC ($CD). Apply writes config.yaml and restarts FluidNC.");
                    show.run();
                });
            } else {
                state[0] = fromGrbl(host.grblSettings());
                if (state[0] == null) {
                    axisNote.setText("Reading the Grbl settings ($$)…");
                    host.send("$$");
                    axisNote.postDelayed(() -> {
                        state[0] = fromGrbl(host.grblSettings());
                        axisNote.setText(state[0] != null ? "Values read from Grbl ($$)." : "Grbl did not report its settings – try again.");
                        show.run();
                    }, 2000);
                    return;
                }
                axisNote.setText("Values read from Grbl ($$).");
                show.run();
            }
        };
        load[0].run();

        // ---- Work offset G54
        tab("Work offset");
        h("Work offset G54");
        LinearLayout mcsRow = row();
        mcsRow.addView(text("Current MCS (machine)", 15, FG), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f));
        TextView mcsNow = text("–", 17, GOOD);
        mcsNow.setTypeface(android.graphics.Typeface.MONOSPACE);
        mcsRow.addView(mcsNow, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Runnable mcsWatch = new Runnable() {
            @Override public void run() {
                if (!d.isShowing()) return;
                float[] m = host.machinePos();
                mcsNow.setText(host.connected() ? String.format(java.util.Locale.US, "X %.3f  Y %.3f  Z %.3f", m[0], m[1], m[2]) : "Not connected");
                mcsNow.postDelayed(this, 250);
            }
        };
        mcsNow.post(mcsWatch);
        p("G54 is the work zero that G-code jobs use: the machine position (after homing) where X0 Y0 Z0 of the job lies. "
                + "\"Write G54\" sends G10 L2 P1 with these values; the controller keeps them through power-off. "
                + "\"Set G54\" makes the Current MCS the work zero (WCS 0, 0, 0); the G54 fields then show the saved value. "
                + "The Zero XY / Zero Z buttons on the Control page overwrite G54 with the current position.");
        EditText[] g54 = {
                field(row(), "G54 X (mm, machine)", "cnc_g54_x", "500"),
                field(row(), "G54 Y (mm, machine)", "cnc_g54_y", "0"),
                field(row(), "G54 Z (mm, machine)", "cnc_g54_z", "0")};
        TextWatcher gw = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int af) { }
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                SharedPreferences.Editor ed = host.prefs().edit();
                for (EditText e : g54) ed.putString((String) e.getTag(), e.getText().toString());
                ed.apply();
            }
        };
        for (EditText e : g54) {
            e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
            e.addTextChangedListener(gw);
        }
        TextView g54Now = p("", DIM);
        Runnable g54Show = () -> {
            String v = host.grblSettings().get("G54");
            if (v != null) {                     // the fields follow what the controller saved
                String[] xyz = v.split(",");
                for (int i = 0; i < 3 && i < xyz.length; i++) g54[i].setText(xyz[i].trim());
            }
            g54Now.setText(v != null ? "In the controller now: G54 = " + v.replace(",", " / ") + " (X / Y / Z)"
                    : host.connected() ? "Press \"Read G54\" to see the value in the controller." : "Connect to read G54 from the controller.");
        };
        LinearLayout g54Row = row();
        Button g54Read = button("Read G54"), g54Write = button("Write G54"), g54Set = button("Set G54");
        g54Write.setTextColor(GOOD);
        g54Set.setTextColor(GOOD);
        for (Button b : new Button[]{g54Read, g54Write, g54Set}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, px(56), 1);
            lp.rightMargin = px(6);
            g54Row.addView(b, lp);
        }
        Runnable g54Query = () -> {
            host.send("$#");
            g54Now.postDelayed(g54Show, 1000);
        };
        g54Read.setOnClickListener(v -> {
            if (!host.connected()) { toast("Connect to the board first"); return; }
            g54Query.run();
        });
        g54Write.setOnClickListener(v -> {
            if (!host.connected()) { toast("Connect to the board first"); return; }
            if (host.busy()) { toast("Stop the running job before changing G54"); return; }
            if (host.machineStatus().startsWith("State: Alarm")) { toast("Grbl is in ALARM – unlock ($X) or home first"); return; }
            String cmd = "G10L2P1X" + fmt(round3(num(g54[0]))) + "Y" + fmt(round3(num(g54[1]))) + "Z" + fmt(round3(num(g54[2])));
            new android.app.AlertDialog.Builder(a)
                    .setTitle("Write G54?")
                    .setMessage(cmd + "\n\nJobs then run from this work zero. Check it before the next job: the tool goes to "
                            + "machine X " + g54[0].getText() + " Y " + g54[1].getText() + " Z " + g54[2].getText() + " at G0 X0 Y0 Z0.")
                    .setPositiveButton("Write", (dlg, which) -> host.capture(cmd, lines -> g54Now.post(() -> {
                        // G10 is refused in ALARM (error:9), so only report success on its ok
                        if (lines == null) {
                            host.log("CNC setup: G54 NOT written (" + cmd + ") – the controller refused it or was busy");
                            toast("G54 not written – unlock ($X) or home first, then write again");
                        } else {
                            host.log("CNC setup: G54 written (" + cmd + ")");
                            toast("G54 written");
                        }
                        g54Query.run();
                    })))
                    .setNegativeButton("Cancel", null)
                    .show();
        });
        g54Set.setOnClickListener(v -> {
            if (!host.connected()) { toast("Connect to the board first"); return; }
            if (host.busy()) { toast("Stop the running job before changing G54"); return; }
            if (!host.machineStatus().startsWith("State: Idle")) { toast("Grbl must be Idle – unlock ($X) or home first"); return; }
            float[] m = host.machinePos();
            String x = fmt(round3(m[0])), y = fmt(round3(m[1])), z = fmt(round3(m[2]));
            String cmd = "G10L2P1X" + x + "Y" + y + "Z" + z;
            new android.app.AlertDialog.Builder(a)
                    .setTitle("Set G54 to the current position?")
                    .setMessage("Machine position now: X " + x + "  Y " + y + "  Z " + z + "\n\n" + cmd
                            + "\n\nThis point becomes the work zero: WCS shows 0, 0, 0 here and jobs start from it.")
                    .setPositiveButton("Set G54", (dlg, which) -> host.capture(cmd, lines -> g54Now.post(() -> {
                        if (lines == null) {
                            host.log("CNC setup: G54 NOT set (" + cmd + ") – the controller refused it or was busy");
                            toast("G54 not set – unlock ($X) or home first, then try again");
                        } else {
                            g54[0].setText(x); g54[1].setText(y); g54[2].setText(z);
                            host.log("CNC setup: G54 set to the current machine position (" + cmd + ")");
                            toast("G54 set – WCS is now 0, 0, 0 here");
                        }
                        g54Query.run();
                    })))
                    .setNegativeButton("Cancel", null)
                    .show();
        });
        g54Show.run();
        if (!fluid && host.connected() && !host.busy()) g54Query.run();   // FluidNC: $CD is being read now

        tab("Recommended");
        h("Recommended settings");
        table(new String[][]{
                {"Setting", "Value", "Why"},
                {"G0 speed (max rate)", "X/Y 1000 → 3000…, Z 500", "Start X/Y at 1000 mm/min, raise in steps once motion is reliable; Z (1 mm per motor rev) tops out near 500–600; see the max per axis above"},
                {"Acceleration $120 / $121 / $122", "30 / 30 / 10", "mm/s²; heavy gantry, raise slowly (50, 80)"},
                {"Travel", "2500 / 1325", "Bed size (measure the real travel, it is a little shorter)"},
                {"$30 / $31", "24000 / 0", "Spindle max / min RPM (S24000 = full; speed itself is set on the MK300)"},
                {"$32", "0", "Laser mode off"},
                {"$1", "255", "Keep motors energised when idle so Z cannot drop"},
                {"$0", "10", "Step pulse µs, safe for MA860H opto inputs"},
        });

        tab("Inverter");
        mk300Settings();

        tab("Feedrate");
        feedrate(d);

        apply.setOnClickListener(v -> {
            AxisState st = state[0];
            if (!host.connected()) { toast("Connect to the board first"); return; }
            if (host.busy()) { toast("Stop the running job before changing settings"); return; }
            if (st == null) { toast("The current values are not read yet"); return; }
            int pulses = MA860H_PULSES[micro.getSelectedItemPosition()];
            AxisState want = new AxisState();
            for (int i = 0; i < 3; i++) {
                AxisRow r = rows[i];
                want.steps[i] = writeComputed.isChecked() ? round3(newSteps[i]) : round3(st.steps[i] * pulses / appliedPulses);
                want.dirInvert[i] = r.dirInvert.isChecked();
                want.homeMinus[i] = r.homeMinus.isChecked();
                want.rate[i] = num(r.rate);
                want.seek[i] = num(r.seek);
                want.travel[i] = num(r.travel);
                int max = maxRate(want.steps[i], pulses);
                if (want.steps[i] < 1 || want.steps[i] > 2000) { toast(AX[i] + ": steps/mm " + fmt(want.steps[i]) + " is out of range – check the numbers"); return; }
                if (want.rate[i] < 10 || want.rate[i] > max) { toast(AX[i] + " G0 speed must be 10 – " + max + " mm/min"); return; }
                if (want.seek[i] < 10 || want.seek[i] > want.rate[i]) { toast(AX[i] + " home seek must be 10 – " + fmt(want.rate[i]) + " mm/min (its G0 speed)"); return; }
                if (want.travel[i] < 1 || want.travel[i] > 5000) { toast(AX[i] + " travel must be 1 – 5000 mm"); return; }
            }
            want.homing = fluid || homingOn.isChecked();
            StringBuilder changes = new StringBuilder();
            for (int i = 0; i < 3; i++) {
                if (Math.abs(want.steps[i] - st.steps[i]) > 0.001) changes.append(String.format(Locale.US, "%s steps/mm %s → %.3f\n", AX[i], fmt(st.steps[i]), want.steps[i]));
                if (want.dirInvert[i] != st.dirInvert[i]) changes.append(AX[i]).append(" direction ").append(want.dirInvert[i] ? "inverted" : "normal").append(" (the axis now moves the other way)\n");
                if (want.homeMinus[i] != st.homeMinus[i]) changes.append(AX[i]).append(" homes toward ").append(AX[i]).append(want.homeMinus[i] ? "−\n" : "+\n");
                if (want.dirInvert[i] != st.dirInvert[i] && want.homeMinus[i] != st.homeMinus[i])
                    changes.append("   ⚠ both changed on ").append(AX[i]).append(": homing still moves the same physical way\n");
                if (want.rate[i] != st.rate[i]) changes.append(AX[i]).append(" G0 speed ").append(fmt(st.rate[i])).append(" → ").append(fmt(want.rate[i])).append(" mm/min\n");
                if (want.seek[i] != st.seek[i] && (fluid || i == 0)) changes.append(fluid ? AX[i] : "All axes").append(" home seek ").append(fmt(st.seek[i])).append(" → ").append(fmt(want.seek[i])).append(" mm/min\n");
                if (want.travel[i] != st.travel[i]) changes.append(AX[i]).append(" travel ").append(fmt(st.travel[i])).append(" → ").append(fmt(want.travel[i])).append(" mm\n");
            }
            if (!fluid && want.homing != st.homing) changes.append("Homing ").append(want.homing ? "on ($22=1)" : "off ($22=0)").append('\n');
            String homingOrder = fluid ? homingCycleChanges(st.fluid) : "";
            changes.append(homingOrder);
            if (pulses != appliedPulses) changes.insert(0, String.format(Locale.US, "Microstepping %,d → %,d pulses/rev – set %s on all three MA860H\n",
                    appliedPulses, pulses, ma860hSwitches(pulses)));
            if (changes.length() == 0) { toast("Nothing changed"); return; }
            Runnable applied = () -> host.prefs().edit().putString("cnc_pulses", String.valueOf(pulses)).apply();
            new android.app.AlertDialog.Builder(a)
                    .setTitle("Write to " + (fluid ? "FluidNC" : "Grbl") + "?")
                    .setMessage(changes + (fluid
                            ? "\nThe app saves the edited config as config.yaml on the board (a copy of the old one stays on the tablet) "
                              + "and restarts FluidNC ($Bye). The machine position is lost – home afterwards."
                            : "\nThe settings are stored in Grbl's EEPROM at once."))
                    .setPositiveButton("Write", (dlg, which) -> {
                        if (fluid) applyFluid(want, axisNote, () -> { applied.run(); d.dismiss(); showCncSetup(); });
                        else {
                            applyGrbl(st, want, axisNote);
                            applied.run();
                            axisNote.postDelayed(() -> { d.dismiss(); showCncSetup(); }, 2000);
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });
        tabPref = "cnc_tab";
        selectTab(host.prefs().getInt(tabPref, 0));
        d.show();
    }

    /** Feedrate tab: F from spindle RPM × flutes × chip load, then the X / Y tuning lap. */
    private void feedrate(Dialog d) {
        LinearLayout[] sub = subTabs("cnc_feed_sub", "Feed calculator", "X / Y tuning", "Z tuning");
        LinearLayout outer = body;
        body = sub[0];
        h("Feedrate");
        p("Feed F (mm/min) = spindle RPM × flutes × chip load (mm/tooth). The spindle RPM is the MK300 preset "
                + "(Hz × 60, see the Inverter tab), not the S word. Too slow a feed rubs and burns the plywood; "
                + "too fast breaks the tool or makes the steppers miss steps.");
        EditText rpm = field(row(), "Spindle RPM", "cnc_feed_rpm", "20000");
        EditText flutes = field(row(), "Flutes", "cnc_feed_flutes", "2");
        EditText chip = field(row(), "Chip load (mm/tooth)", "cnc_feed_chip", "0.05");
        TextView out = p("", GOOD);
        out.setTextSize(18);
        out.setTypeface(null, Typeface.BOLD);
        Runnable calc = () -> {
            out.setText(String.format(Locale.US, "F%.0f mm/min", num(rpm) * num(flutes) * num(chip)));
            SharedPreferences.Editor ed = host.prefs().edit();
            for (EditText e : new EditText[]{rpm, flutes, chip}) ed.putString((String) e.getTag(), e.getText().toString());
            ed.apply();
        };
        TextWatcher w = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int af) { }
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { calc.run(); }
        };
        for (EditText e : new EditText[]{rpm, flutes, chip}) e.addTextChangedListener(w);
        calc.run();
        p("Feed for an 8 mm 2-flute end mill in plywood: about F2000 at 20000–24000 RPM (chip load ≈ 0.05 mm/tooth). "
                + "ER32 holds 3–20 mm shanks; use the collet that matches the tool shank.");
        p("The controller caps F at each axis' G0 speed (Axis & homing tab). Plunge into the wood (Z) at about "
                + "a third to a half of the XY feed.", DIM);
        body = sub[1];
        xyTuning(d);
        body = sub[2];
        zTuning(d);
        body = outer;
    }

    /** A row of sub-tab buttons with one pane each (added to body); the choice is kept under pref. */
    private LinearLayout[] subTabs(String pref, String... labels) {
        LinearLayout bar = row();
        bar.setPadding(0, px(4), 0, px(4));
        Button[] bs = new Button[labels.length];
        LinearLayout[] panes = new LinearLayout[labels.length];
        for (int i = 0; i < labels.length; i++) {
            bs[i] = button(labels[i]);
            bs[i].setTextSize(14);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, px(44), 1);
            lp.rightMargin = px(4);
            bar.addView(bs[i], lp);
            panes[i] = new LinearLayout(a);
            panes[i].setOrientation(LinearLayout.VERTICAL);
            body.addView(panes[i]);
        }
        java.util.function.IntConsumer select = sel -> {
            for (int k = 0; k < panes.length; k++) {
                boolean on = k == sel;
                panes[k].setVisibility(on ? View.VISIBLE : View.GONE);
                bs[k].setBackgroundColor(on ? 0xFF2E5A8A : CELL);
                bs[k].setTextColor(on ? 0xFFFFFFFF : DIM);
                bs[k].setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
            }
            scroll.scrollTo(0, 0);
            host.prefs().edit().putInt(pref, sel).apply();
        };
        for (int i = 0; i < labels.length; i++) { int k = i; bs[i].setOnClickListener(v -> select.accept(k)); }
        int saved = host.prefs().getInt(pref, 0);
        select.accept(saved >= 0 && saved < labels.length ? saved : 0);
        return panes;
    }

    /** Two columns added to body: the drawing left (fixed height, or its own height when heightDp is 0), and the returned right column for the controls. */
    private LinearLayout columns(View left, int heightDp) {
        LinearLayout cols = new LinearLayout(a);
        cols.setOrientation(LinearLayout.HORIZONTAL);
        cols.addView(left, new LinearLayout.LayoutParams(0, heightDp > 0 ? px(heightDp) : LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout right = new LinearLayout(a);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(px(12), 0, 0, 0);
        cols.addView(right, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        body.addView(cols);
        return right;
    }

    /** Small explanatory text for the tuning sections. */
    private TextView small(CharSequence s, int color) {
        TextView t = p(s, color);
        t.setTextSize(13);
        t.setPadding(0, px(1), 0, px(3));
        return t;
    }

    /** Go / Pause / Stop row of the tuning sections. */
    private Button[] goPauseStop() {
        LinearLayout btns = row();
        btns.setPadding(0, px(6), 0, 0);
        Button go = button("Go"), pause = button("Pause"), stop = button("Stop");
        go.setTextColor(GOOD);
        pause.setTextColor(WARN);
        stop.setTextColor(0xFFFFFFFF);
        stop.setTypeface(null, Typeface.BOLD);
        stop.setBackgroundColor(0xFFC62828);
        for (Button b : new Button[]{go, pause, stop}) {
            b.setTextSize(16);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, px(56), 1);
            lp.rightMargin = px(6);
            btns.addView(b, lp);
        }
        return new Button[]{go, pause, stop};
    }

    // ---------------------------------------------------------------- X / Y axis tuning

    /** Tuning square: 300 × 300 mm centred at WCS (600, 600); the lap starts and ends at corner 1. */
    private static final double SQ_C = 600, SQ_HALF = 150, TUNE_Z = 50;
    private static final double[][] SQ_CORNERS = {
            {SQ_C - SQ_HALF, SQ_C - SQ_HALF}, {SQ_C + SQ_HALF, SQ_C - SQ_HALF},
            {SQ_C + SQ_HALF, SQ_C + SQ_HALF}, {SQ_C - SQ_HALF, SQ_C + SQ_HALF}};

    /** The tuning square in WCS with the tool's live position. */
    private final class SquareView extends View {
        float wx = Float.NaN, wy = Float.NaN;
        int target = -1;                      // corner being approached, -1 = none
        private final android.graphics.Paint pt = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        SquareView() { super(a); setBackgroundColor(CELL); }

        @Override protected void onDraw(android.graphics.Canvas c) {
            float w = getWidth(), h = getHeight();
            // WCS 400…800 on both axes, Y up
            double lo = SQ_C - SQ_HALF - 50, span = 2 * (SQ_HALF + 50);
            float k = (float) (Math.min(w, h) / span), ox = (w - (float) span * k) / 2, oy = (h - (float) span * k) / 2;
            pt.setStyle(android.graphics.Paint.Style.STROKE);
            pt.setStrokeWidth(px(2));
            pt.setColor(GOOD);
            float x0 = ox + (float) (SQ_CORNERS[0][0] - lo) * k, x1 = ox + (float) (SQ_CORNERS[2][0] - lo) * k;
            float y0 = oy + (float) (lo + span - SQ_CORNERS[0][1]) * k, y1 = oy + (float) (lo + span - SQ_CORNERS[2][1]) * k;
            c.drawRect(x0, y1, x1, y0, pt);
            pt.setStyle(android.graphics.Paint.Style.FILL);
            pt.setTextSize(px(13));
            for (int i = 0; i < 4; i++) {
                float cx = ox + (float) (SQ_CORNERS[i][0] - lo) * k, cy = oy + (float) (lo + span - SQ_CORNERS[i][1]) * k;
                pt.setColor(i == target ? WARN : FG);
                c.drawCircle(cx, cy, px(5), pt);
                String label = (i + 1) + "  (" + (int) SQ_CORNERS[i][0] + ", " + (int) SQ_CORNERS[i][1] + ")";
                float tw = pt.measureText(label);
                c.drawText(label, cx - tw / 2, i < 2 ? cy + px(20) : cy - px(10), pt);
            }
            pt.setColor(DIM);
            String[] centre = {"300 × 300 mm", "centre WCS (600, 600)"};
            for (int i = 0; i < 2; i++) c.drawText(centre[i], (w - pt.measureText(centre[i])) / 2, h / 2 + (i - 0.3f) * px(17), pt);
            if (!Float.isNaN(wx)) {
                // tool dot, held at the edge when it is outside the drawn area
                float tx = ox + (float) Math.max(0, Math.min(span, wx - lo)) * k;
                float ty = oy + (float) Math.max(0, Math.min(span, lo + span - wy)) * k;
                pt.setColor(0xFFFF5555);
                c.drawCircle(tx, ty, px(7), pt);
            }
        }
    }

    /** Number field with ▼ / ▲ buttons that step it by step (kept in prefs under key). */
    private EditText stepField(String label, String key, int def, int step, int min) {
        LinearLayout r = row();
        r.addView(text(label, 14, FG), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f));
        Button down = button("▼"), up = button("▲");
        EditText e = numField(host.prefs().getString(key, String.valueOf(def)));
        e.setTag(key);
        e.setGravity(android.view.Gravity.CENTER);
        r.addView(down, new LinearLayout.LayoutParams(px(64), px(48)));
        r.addView(e, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f));
        r.addView(up, new LinearLayout.LayoutParams(px(64), px(48)));
        down.setOnClickListener(v -> e.setText(String.valueOf(Math.max(min, (int) num(e) - step))));
        up.setOnClickListener(v -> e.setText(String.valueOf((int) num(e) + step)));
        e.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int af) { }
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { host.prefs().edit().putString(key, s.toString()).apply(); }
        });
        return e;
    }

    /** Leadshine MA860H datasheet: pulse input up to 300 kHz. */
    private static final int MA860H_MAX_HZ = 300000;

    /**
     * Practical maximum rapid of an axis: the NEMA34 torque corner (~600 rpm on the MA860H at 80 VAC) limits it,
     * long before the controller's or the driver's pulse rate does.
     */
    private String limitsText(int axis, String expect) {
        Map<String, String> s = host.grblSettings();
        int pulses;
        try { pulses = Integer.parseInt(host.prefs().getString("cnc_pulses", "1600").trim()); } catch (NumberFormatException e) { pulses = 1600; }
        double steps = s.containsKey("$10" + axis) ? dnum(s, "$10" + axis) : axis == 2 ? 320 : 83.5;
        double mmRev = pulses / steps;
        String name = axis == 2 ? "Z" : "X / Y";
        long motor = Math.round(MOTOR_MAX_RPM * mmRev / 100) * 100, ctrl = Math.round(controllerHz() * 60.0 / steps / 100) * 100;
        String ctrlName = MainActivity.BOARD_FLUIDNC.equals(host.board()) ? "FluidNC I2S" : "controller";
        return String.format(Locale.US, "Practical max %s: %,d mm/min = %s. Motor %d rpm × %.2f mm/rev = %,d (NEMA34 torque falls off "
                        + "above ≈600 rpm; 1000 rpm = %,d with little torque); %s %d kHz = %,d; MA860H %d kHz = %,d. "
                        + "Expect clean runs at %s.",
                name, Math.min(motor, ctrl), motor <= ctrl ? "the motor limit, pulse rate is not the limit" : "the " + ctrlName + " step rate limit",
                MOTOR_MAX_RPM, mmRev, motor, Math.round(1000 * mmRev / 100) * 100, ctrlName, controllerHz() / 1000, ctrl,
                MA860H_MAX_HZ / 1000, Math.round(MA860H_MAX_HZ * 60.0 / steps / 100) * 100, expect);
    }

    private LinearLayout vcol() {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    /** {@link #field} with smaller text, for the mass / torque inputs of the tuning sections. */
    private EditText compactField(LinearLayout row, String label, String key, String def) {
        EditText e = field(row, label, key, def);
        ((TextView) row.getChildAt(row.getChildCount() - 2)).setTextSize(13);
        e.setTextSize(14);
        e.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int af) { }
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { host.prefs().edit().putString(key, s.toString()).apply(); }
        });
        return e;
    }

    private double prefNum(String key, double def) {
        try { return Double.parseDouble(host.prefs().getString(key, String.valueOf(def)).trim().replace(',', '.')); }
        catch (NumberFormatException e) { return def; }
    }

    /**
     * Rough NEMA34 pull-out torque on an MA860H at 80 VAC: about 0.7 × holding torque at low speed,
     * half of that near 600 rpm (typical 8.5 Nm curve: 6 / 4.5 / 3 / 2 Nm at 100 / 300 / 600 / 1000 rpm).
     */
    private static double pullOutNm(double holdNm, double rpm) { return holdNm * 0.75 / (1 + rpm / 550); }

    /** Rotor inertia (kg·m²) scaled from a typical 8.5 Nm NEMA34 (2.7 kg·cm²). */
    private static double rotorJ(double holdNm) { return 2.7e-4 * holdNm / 8.5; }

    /**
     * Max accel (mm/s²) of a rack-and-pinion axis at the given rapid: {100 % torque, 50 % torque}.
     * Motor torque at that speed → 1:n belt → pinion, efficiency 0.85, guide friction 3 % of the weight;
     * the rotor's inertia counts as mass J × (n / r)².
     */
    private double[] accelRack(double massKg, int motors, double holdNm, double rapid) {
        double red = prefNum("cnc_red", 5), teeth = prefNum("cnc_teeth24", 24), pitch = prefNum("cnc_rack_pitch4", 4.0);
        double mmRev = teeth * pitch / Math.max(1e-9, red);
        double k = red / (teeth * pitch / (2 * Math.PI) / 1000);        // rack force per motor Nm, 1/m
        double force = motors * pullOutNm(holdNm, rapid / mmRev) * k * 0.85;
        double friction = 0.03 * massKg * 9.81, mass = massKg + motors * rotorJ(holdNm) * k * k;
        return new double[]{Math.max(0, (force - friction) / mass * 1000), Math.max(0, (0.5 * force - friction) / mass * 1000)};
    }

    /** Max accel (mm/s²) of Z going up (against gravity) at the given rapid: {100 % torque, 50 % torque}. */
    private double[] accelZ(double massKg, double holdNm, double rapid) {
        double red = prefNum("cnc_zred_direct", 1), lead = prefNum("cnc_zlead", 5);
        double k = 2 * Math.PI * red / (lead / 1000);                    // screw force per motor Nm, 1/m
        double force = pullOutNm(holdNm, rapid / (lead / Math.max(1e-9, red))) * k * 0.9;
        double load = massKg * 9.81 * 1.03, mass = massKg + rotorJ(holdNm) * k * k;
        return new double[]{Math.max(0, (force - load) / mass * 1000), Math.max(0, (0.5 * force - load) / mass * 1000)};
    }

    /** Time (s) for a straight move of d mm from stop to stop: trapezoid, or triangle when v is never reached. */
    private static double moveTime(double d, double mmPerMin, double accel) {
        double v = mmPerMin / 60;
        if (v <= 0 || accel <= 0) return 0;
        return d >= v * v / accel ? d / v + v / accel : 2 * Math.sqrt(d / accel);
    }

    /**
     * X / Y tuning: writes the accel and max rate (G0 speed) of X and Y, lifts Z to WCS Z50 and runs one G0 lap
     * round the square, timing it from corner 1 back to corner 1.
     */
    private void xyTuning(Dialog d) {
        final boolean fluid = MainActivity.BOARD_FLUIDNC.equals(host.board());
        small("Go writes the accel and rapid rate (G0 = max rate) below, lifts Z to WCS Z50, goes to corner 1 and runs one "
                + "timed G0 lap 1 → 2 → 3 → 4 → 1. Raise them step by step: a motor that misses steps growls or bangs and ends "
                + "the lap away from corner 1 (mark it with a pen). Take the highest clean pair, then back off about 30 % for cutting.", 0xFFC8CDD3);
        SquareView sq = new SquareView();
        LinearLayout outer = body;
        // left: drawing, the masses for the accel estimate, the practical max rapid
        LinearLayout left = vcol();
        left.addView(sq, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(220)));
        body = left;
        EditText gantryKg = compactField(row(), "Gantry kg", "tune_gantry_kg", "300");
        EditText carriageKg = compactField((LinearLayout) gantryKg.getParent(), "X carriage kg", "tune_carriage_kg", "80");
        EditText motorNm = compactField(row(), "Motor Nm", "tune_motor_nm", "8.5");
        EditText gantryMotors = compactField((LinearLayout) motorNm.getParent(), "Gantry motors", "tune_gantry_motors", "1");
        small(limitsText(0, "6000–9000 mm/min with 100–200 mm/s²"), 0xFFC8CDD3);
        body = outer;
        body = columns(left, 0);
        EditText ax = stepField("X accel (mm/s²)", "tune_ax", 100, 10, 10);
        EditText ay = stepField("Y accel (mm/s²)", "tune_ay", 100, 10, 10);
        EditText fx = stepField("X rapid (mm/min)", "tune_fx", 3000, 250, 250);
        EditText fy = stepField("Y rapid (mm/min)", "tune_fy", 3000, 250, 250);
        TextView calcNote = small("", DIM);
        TextView accelNote = small("", WARN);
        Runnable calc = () -> {
            double t = 2 * moveTime(2 * SQ_HALF, num(fx), num(ax)) + 2 * moveTime(2 * SQ_HALF, num(fy), num(ay));
            double vx = num(fx) / 60, vy = num(fy) / 60;
            calcNote.setText(String.format(Locale.US, "Calculated lap: %.1f s · full speed after %.0f mm on X, %.0f mm on Y (v² ÷ 2a).",
                    t, num(ax) > 0 ? vx * vx / (2 * num(ax)) : 0, num(ay) > 0 ? vy * vy / (2 * num(ay)) : 0));
            // the gantry rides the axis with the longer travel ($130 / $131), Y when unknown
            Map<String, String> st = host.grblSettings();
            int g = st.containsKey("$130") && st.containsKey("$131") && dnum(st, "$130") > dnum(st, "$131") ? 0 : 1;
            double[][] est = new double[2][];
            for (int i = 0; i < 2; i++)
                est[i] = accelRack(i == g ? num(gantryKg) : num(carriageKg), i == g ? Math.max(1, (int) num(gantryMotors)) : 1,
                        num(motorNm), num(i == 0 ? fx : fy));
            accelNote.setText(String.format(Locale.US, "Estimated max accel at these rapids (50 %% torque margin): X ≈ %,d · Y ≈ %,d mm/s² "
                            + "(100 %% torque: %,d / %,d). %s = gantry. Practical: 60–70 %% of the highest clean lap; "
                            + "a heavy gantry usually ends at 300–500.",
                    Math.round(est[0][1]), Math.round(est[1][1]), Math.round(est[0][0]), Math.round(est[1][0]), AX[g]));
        };
        TextWatcher cw = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int af) { }
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { calc.run(); }
        };
        for (EditText e : new EditText[]{ax, ay, fx, fy, gantryKg, carriageKg, motorNm, gantryMotors}) e.addTextChangedListener(cw);
        calc.run();

        // write X / Y accel and max rate without running the lap, then read them back
        paramButton("XY params", fluid, new int[]{0, 1}, () -> new int[][]{{(int) num(ax), (int) num(ay)}, {(int) num(fx), (int) num(fy)}},
                v -> xyLimitsError(v[0], v[1]));

        Button[] gps = goPauseStop();
        Button go = gps[0], pause = gps[1], stop = gps[2];
        TextView state = small("", WARN);
        TextView results = small("", FG);
        results.setTypeface(Typeface.MONOSPACE);
        body = outer;
        small(fluid ? "FluidNC keeps these values until it restarts. To keep them, close and reopen CNC setup (it reads them again), "
                        + "then press Apply on that page: it saves the running config as config.yaml."
                : "Grbl stores $110 / $111 / $120 / $121 in its EEPROM at once: the last values tried stay in use.", DIM);

        // 0 = idle, 1 = going to corner 1, 2 = lap running
        final int[] phase = {0};
        final long[] lapStart = {0}, goAt = {0};
        final boolean[] moved = {false}, paused = {false}, pauseSeen = {false};
        final double[] lapCalc = {0};
        final String[] lapLabel = {""};
        StringBuilder history = new StringBuilder();

        Runnable reset = () -> {
            phase[0] = 0;
            paused[0] = false;
            pause.setText("Pause");
            sq.target = -1;
        };
        Runnable watch = new Runnable() {
            @Override public void run() {
                if (!d.isShowing()) return;
                sq.postDelayed(this, 100);
                if (!host.connected()) { sq.wx = Float.NaN; sq.invalidate(); state.setText("Not connected"); return; }
                float[] m = host.machinePos(), o = host.wco();
                sq.wx = m[0] - o[0];
                sq.wy = m[1] - o[1];
                sq.invalidate();
                String st = host.machineStatus();
                boolean idle = st.startsWith("State: Idle");
                if (!idle) moved[0] = true;
                if (st.startsWith("State: Hold") || st.startsWith("State: Door")) pauseSeen[0] = true;
                boolean atStart = Math.abs(sq.wx - SQ_CORNERS[0][0]) < 0.05 && Math.abs(sq.wy - SQ_CORNERS[0][1]) < 0.05;
                if (phase[0] == 1) {
                    state.setText("Going to corner 1…   " + st);
                    // already standing at corner 1 → no motion is seen, so also accept 1.5 s of Idle there
                    if (idle && atStart && (moved[0] || android.os.SystemClock.elapsedRealtime() - goAt[0] > 1500)) {
                        // at corner 1: run the timed lap
                        for (int i = 1; i <= 4; i++) host.send("G0X" + fmt(SQ_CORNERS[i % 4][0]) + "Y" + fmt(SQ_CORNERS[i % 4][1]));
                        phase[0] = 2;
                        moved[0] = false;
                        pauseSeen[0] = false;
                        lapStart[0] = android.os.SystemClock.elapsedRealtime();
                    }
                } else if (phase[0] == 2) {
                    double el = (android.os.SystemClock.elapsedRealtime() - lapStart[0]) / 1000.0;
                    // corner being approached, from the position along the lap
                    int tgt = sq.wy < SQ_C - SQ_HALF + 0.05 && sq.wx > SQ_CORNERS[0][0] + 0.05 ? 1
                            : sq.wx > SQ_C + SQ_HALF - 0.05 && sq.wy < SQ_C + SQ_HALF - 0.05 ? 2
                            : sq.wy > SQ_C + SQ_HALF - 0.05 && sq.wx > SQ_C - SQ_HALF + 0.05 ? 3 : 0;
                    sq.target = tgt;
                    state.setText(String.format(Locale.US, "Lap %.1f s   %s", el, st));
                    if (idle && atStart && moved[0]) {
                        String line = String.format(Locale.US, "%s → %5.1f s (calc %.1f)%s", lapLabel[0], el, lapCalc[0],
                                pauseSeen[0] ? "  paused, time not valid" : "");
                        history.insert(0, line + "\n");
                        results.setText(history);
                        host.log("XY tuning: " + line);
                        state.setText("Lap done. " + st);
                        reset.run();
                    }
                }
            }
        };
        sq.post(watch);                       // runs once the page is shown (the view is attached then)

        go.setOnClickListener(v -> {
            if (!host.connected()) { toast("Connect to the board first"); return; }
            if (host.busy()) { toast("Stop the running job first"); return; }
            if (phase[0] != 0) { toast("The lap is still running – Stop it first"); return; }
            if (!host.machineStatus().startsWith("State: Idle")) { toast("The machine must be Idle – home or unlock ($X) first"); return; }
            int[] acc = {(int) num(ax), (int) num(ay)}, rate = {(int) num(fx), (int) num(fy)};
            Map<String, String> s = host.grblSettings();
            String bad = xyLimitsError(acc, rate);
            if (bad != null) { toast(bad); return; }
            // WCS Z50, but not above the home position (soft limits / the Z top)
            float[] o = host.wco();
            boolean zClamp = o[2] + TUNE_Z > -1;
            String zMove = zClamp ? "G53G0Z-1" : "G0Z" + fmt(TUNE_Z);
            java.util.List<String> cmds = paramCmds(fluid, new int[]{0, 1}, acc, rate);
            StringBuilder was = new StringBuilder();
            for (int i = 0; i < 2; i++) if (s.containsKey("$11" + i))
                was.append(AX[i]).append(" now: accel ").append(s.get("$12" + i)).append(", rapid ").append(s.get("$11" + i)).append('\n');
            String moves = "G21G90G54\n" + zMove + "\nG0X" + fmt(SQ_CORNERS[0][0]) + "Y" + fmt(SQ_CORNERS[0][1]);
            new android.app.AlertDialog.Builder(a)
                    .setTitle("Run the tuning lap?")
                    .setMessage(was + String.join("\n", cmds) + "\n\n" + moves + "\nthen the lap 2 → 3 → 4 → 1 at G0."
                            + (zClamp ? "\n\nWCS Z50 is above the Z home position, so Z goes to machine Z−1 instead." : "")
                            + "\n\nNo tool in the wood: keep the bed clear and a hand near E-STOP.")
                    .setPositiveButton("Go", (dlg, which) -> {
                        for (String c : cmds) host.send(c);
                        if (fluid) for (char c : new char[]{'x', 'y'}) {           // read back so the page shows the new values
                            host.send("$/axes/" + c + "/acceleration_mm_per_sec2");
                            host.send("$/axes/" + c + "/max_rate_mm_per_min");
                        }
                        for (String mv : moves.split("\n")) host.send(mv);
                        lapLabel[0] = String.format(Locale.US, "X a%-4d F%-5d  Y a%-4d F%-5d", acc[0], rate[0], acc[1], rate[1]);
                        lapCalc[0] = 2 * moveTime(2 * SQ_HALF, rate[0], acc[0]) + 2 * moveTime(2 * SQ_HALF, rate[1], acc[1]);
                        host.log("XY tuning: " + String.join(" ", cmds) + ", lap round WCS (450…750, 450…750)");
                        moved[0] = false;
                        goAt[0] = android.os.SystemClock.elapsedRealtime();
                        sq.target = 0;
                        phase[0] = 1;
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });
        pause.setOnClickListener(v -> {
            if (!host.connected()) { toast("Not connected"); return; }
            if (paused[0]) { host.resume(); paused[0] = false; pause.setText("Pause"); }
            else { host.hold(); paused[0] = true; pause.setText("Resume"); }
        });
        stop.setOnClickListener(v -> {
            if (!host.connected()) { toast("Not connected"); return; }
            // hold first so the reset comes after the stop and the position is kept; reset after 2 s at the latest
            host.hold();
            final long t0 = android.os.SystemClock.elapsedRealtime();
            Runnable waitStop = new Runnable() {
                @Override public void run() {
                    String st = host.machineStatus();
                    if (st.startsWith("State: Hold:0") || st.startsWith("State: Idle")
                            || android.os.SystemClock.elapsedRealtime() - t0 > 2000) {
                        host.estop();
                        host.log("XY tuning: stopped");
                        state.setText("Stopped.");
                        reset.run();
                    } else stop.postDelayed(this, 50);
                }
            };
            stop.post(waitStop);
        });
    }

    /** Range check of the X / Y accel and rapid; null when both axes are fine. */
    private String xyLimitsError(int[] acc, int[] rate) {
        Map<String, String> s = host.grblSettings();
        int pulses;
        try { pulses = Integer.parseInt(host.prefs().getString("cnc_pulses", "1600").trim()); } catch (NumberFormatException e) { pulses = 1600; }
        for (int i = 0; i < 2; i++) {
            double steps = s.containsKey("$10" + i) ? dnum(s, "$10" + i) : 0;
            int max = steps > 0 ? maxRate(steps, pulses) : 20000;
            if (acc[i] < 10 || acc[i] > 2000) return AX[i] + " accel must be 10 – 2000 mm/s²";
            if (rate[i] < 250 || rate[i] > max) return AX[i] + " rapid must be 250 – " + max + " mm/min";
        }
        return null;
    }

    /** $12n / $11n (accel / max rate) for each axis n, or the FluidNC $/axes/… equivalents. */
    private static java.util.List<String> paramCmds(boolean fluid, int[] axes, int[] acc, int[] rate) {
        java.util.List<String> cmds = new java.util.ArrayList<>();
        for (int k = 0; k < axes.length; k++) {
            int i = axes[k];
            if (fluid) {
                cmds.add("$/axes/" + "xyz".charAt(i) + "/acceleration_mm_per_sec2=" + acc[k]);
                cmds.add("$/axes/" + "xyz".charAt(i) + "/max_rate_mm_per_min=" + rate[k]);
            } else {
                cmds.add("$12" + i + "=" + acc[k]);
                cmds.add("$11" + i + "=" + rate[k]);
            }
        }
        return cmds;
    }

    interface Values { int[][] get(); }                 // {accel per axis, rate per axis}
    interface Check { String error(int[][] values); }   // null = fine

    /**
     * "Update GRBL params" button with its status text: writes the accel and max rate of axes one command at a time,
     * then reads them back and marks each axis ✓ / ✗. tag prefixes the log lines.
     */
    private void paramButton(String tag, boolean fluid, int[] axes, Values values, Check check) {
        Button update = button("Update GRBL params");
        update.setTextColor(GOOD);
        update.setTextSize(15);
        body.addView(update, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(52)));
        TextView paramState = small("", DIM);
        paramState.setTypeface(Typeface.MONOSPACE);
        update.setOnClickListener(v -> {
            if (!host.connected()) { paramState.setTextColor(RED); paramState.setText("GRBL: not connected – connect to the board first"); return; }
            if (host.busy()) { paramState.setTextColor(WARN); paramState.setText("GRBL: connected, a job is running – stop it first"); return; }
            int[][] val = values.get();
            int[] acc = val[0], rate = val[1];
            String bad = check.error(val);
            if (bad != null) { paramState.setTextColor(RED); paramState.setText("Not sent: " + bad); return; }
            java.util.List<String> cmds = paramCmds(fluid, axes, acc, rate);
            update.setEnabled(false);
            paramState.setTextColor(WARN);
            paramState.setText("GRBL: connected (" + host.machineStatus().replaceFirst("^State: (\\S+).*", "$1") + ")\nWriting 1/" + cmds.size() + "…");
            host.log(tag + ": " + String.join(" ", cmds));
            writeParams(cmds, 0, paramState, err -> {
                if (err != null) {
                    update.setEnabled(true);
                    paramState.setTextColor(RED);
                    paramState.setText(paramState.getText() + "\n✗ " + err);
                    host.log(tag + ": " + err);
                    return;
                }
                paramState.setText(paramState.getText() + "\nParams updated. Reading back…");
                readParams(fluid, readCmds(axes), new java.util.ArrayList<>(), 0, read -> {
                    update.setEnabled(true);
                    if (read == null) {
                        paramState.setTextColor(RED);
                        paramState.setText(paramState.getText() + "\n✗ No reply to the read-back – try again");
                        return;
                    }
                    StringBuilder sb = new StringBuilder(paramState.getText()).append('\n');
                    boolean ok = true;
                    for (int k = 0; k < axes.length; k++) {
                        int i = axes[k];
                        String ra = read.get("$12" + i), rr = read.get("$11" + i);
                        boolean m = ra != null && rr != null && Math.abs(Double.parseDouble(ra) - acc[k]) < 0.01
                                && Math.abs(Double.parseDouble(rr) - rate[k]) < 0.01;
                        ok &= m;
                        sb.append(String.format(Locale.US, "%s  accel $12%d = %-9s rapid $11%d = %-9s %s%n", AX[i],
                                i, ra == null ? "?" : ra, i, rr == null ? "?" : rr, m ? "✓" : "✗ (sent a" + acc[k] + " F" + rate[k] + ")"));
                    }
                    sb.append(ok ? "✓ Confirmed: the board reads back the new values." : "✗ The read-back differs from what was sent.");
                    paramState.setTextColor(ok ? GOOD : RED);
                    paramState.setText(sb);
                    host.log(tag + ": read back " + read + (ok ? " – confirmed" : " – MISMATCH"));
                });
            });
        });
    }

    interface Err { void done(String error); }

    /** Sends cmds one by one, each waiting for its ok; done gets null, or what went wrong. */
    private void writeParams(java.util.List<String> cmds, int i, TextView state, Err done) {
        if (i == cmds.size()) { done.done(null); return; }
        if (!host.connected()) { done.done("GRBL disconnected after " + i + "/" + cmds.size()); return; }
        host.capture(cmds.get(i), lines -> {
            if (lines == null) { done.done(cmds.get(i) + " – error or no ok from the board (busy?)"); return; }
            state.setText(state.getText() + "\n" + cmds.get(i) + "  ok");
            writeParams(cmds, i + 1, state, done);
        });
    }

    interface Read { void got(Map<String, String> settings); }

    /** FluidNC items that read back the accel and max rate of axes. */
    private static String[] readCmds(int[] axes) {
        String[] r = new String[2 * axes.length];
        for (int k = 0; k < axes.length; k++) {
            r[2 * k] = "$/axes/" + "xyz".charAt(axes[k]) + "/acceleration_mm_per_sec2";
            r[2 * k + 1] = "$/axes/" + "xyz".charAt(axes[k]) + "/max_rate_mm_per_min";
        }
        return r;
    }

    /** Reads $11n / $12n back ($$, or the FluidNC items); also refreshes the settings map. */
    private void readParams(boolean fluid, String[] items, java.util.List<String> got, int i, Read done) {
        if (!fluid || i == items.length) {
            if (fluid) { parseParams(got, done); return; }
            host.capture("$$", lines -> { if (lines == null) done.got(null); else parseParams(lines, done); });
            return;
        }
        host.capture(items[i], lines -> {
            if (lines == null) { done.got(null); return; }
            got.addAll(lines);
            readParams(true, items, got, i + 1, done);
        });
    }

    private void parseParams(java.util.List<String> lines, Read done) {
        Map<String, String> out = new java.util.HashMap<>();
        for (String l : lines) {
            String g = MainActivity.fluidToGrbl(l.trim());
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("^\\$(\\d+)=([-0-9.]+)\\s*$").matcher(g != null ? g : l.trim());
            if (!m.matches()) continue;
            host.grblSettings().put("$" + m.group(1), m.group(2));
            out.put("$" + m.group(1), m.group(2));
        }
        done.got(out);
    }

    /** Range check of the Z accel and rapid; null when fine. */
    private String zLimitsError(int acc, int rate) {
        Map<String, String> s = host.grblSettings();
        int pulses;
        try { pulses = Integer.parseInt(host.prefs().getString("cnc_pulses", "1600").trim()); } catch (NumberFormatException e) { pulses = 1600; }
        int max = s.containsKey("$102") ? maxRate(dnum(s, "$102"), pulses) : 3000;
        if (acc < 10 || acc > 1000) return "Z accel must be 10 – 1000 mm/s²";
        if (rate < 25 || rate > max) return "Z rapid must be 25 – " + max + " mm/min";
        return null;
    }

    // ---------------------------------------------------------------- Z axis tuning

    /** Z tuning stroke in WCS: down end, top end. */
    private static final double Z_LO = 50, Z_HI = 150;

    /** The Z stroke as a vertical line with the tool's live Z. */
    private final class ZLineView extends View {
        float wz = Float.NaN;
        int target = -1;                      // 0 = going down to Z50, 1 = going up to Z150
        private final android.graphics.Paint pt = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        ZLineView() { super(a); setBackgroundColor(CELL); }

        @Override protected void onDraw(android.graphics.Canvas c) {
            float w = getWidth(), h = getHeight();
            double lo = Z_LO - 20, span = Z_HI - Z_LO + 40;      // WCS Z30…170, up = up
            float k = (float) (h / span), cx = w / 2;
            float yHi = (float) (lo + span - Z_HI) * k, yLo = (float) (lo + span - Z_LO) * k;
            pt.setStyle(android.graphics.Paint.Style.STROKE);
            pt.setStrokeWidth(px(3));
            pt.setColor(GOOD);
            c.drawLine(cx, yHi, cx, yLo, pt);
            pt.setStyle(android.graphics.Paint.Style.FILL);
            pt.setTextSize(px(13));
            String[] labels = {"Z" + (int) Z_LO + " (down)", "Z" + (int) Z_HI + " (top)"};
            float[] ys = {yLo, yHi};
            for (int i = 0; i < 2; i++) {
                pt.setColor(i == target ? WARN : FG);
                c.drawCircle(cx, ys[i], px(5), pt);
                c.drawText(labels[i], cx + px(14), ys[i] + px(5), pt);
            }
            pt.setColor(DIM);
            String len = (int) (Z_HI - Z_LO) + " mm stroke, WCS";
            c.drawText(len, cx - pt.measureText(len) - px(14), (yHi + yLo) / 2, pt);
            if (!Float.isNaN(wz)) {
                float ty = (float) Math.max(0, Math.min(span, lo + span - wz)) * k;
                pt.setColor(0xFFFF5555);
                c.drawCircle(cx, ty, px(7), pt);
            }
        }
    }

    /**
     * Z tuning: writes Z's accel and max rate (G0 speed), goes to WCS Z50 and runs one G0 stroke
     * Z50 → Z150 → Z50 at the current X / Y, timed.
     */
    private void zTuning(Dialog d) {
        final boolean fluid = MainActivity.BOARD_FLUIDNC.equals(host.board());
        small("Go writes the Z accel and rapid rate below, goes to WCS Z50 and runs one timed G0 stroke Z50 → Z150 → Z50 "
                + "where X / Y stand now. WCS Z150 must lie below the Z home position (G54 Z at least 151 mm below it). "
                + "A Z that misses steps ends away from its mark, and a lost Z can drop the spindle: stay well below the limit.", 0xFFC8CDD3);
        ZLineView zl = new ZLineView();
        LinearLayout outer = body;
        LinearLayout left = vcol();
        left.addView(zl, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(240)));
        body = left;
        EditText zKg = compactField(row(), "Z moving kg", "tune_z_kg", "40");
        EditText motorNm = compactField((LinearLayout) zKg.getParent(), "Motor Nm", "tune_motor_nm", "8.5");
        small(limitsText(2, "1500–2500 mm/min with a lower accel than X / Y (Z lifts the spindle)"), 0xFFC8CDD3);
        body = outer;
        body = columns(left, 0);
        EditText az = stepField("Z accel (mm/s²)", "tune_az", 30, 10, 10);
        EditText fz = stepField("Z rapid (mm/min)", "tune_fz", 500, 25, 25);
        TextView calcNote = small("", DIM);
        TextView accelNote = small("", WARN);
        Runnable calc = () -> {
            double v = num(fz) / 60;
            calcNote.setText(String.format(Locale.US, "Calculated stroke: %.1f s. Full speed is reached after %.1f mm (v² ÷ 2a).",
                    2 * moveTime(Z_HI - Z_LO, num(fz), num(az)), num(az) > 0 ? v * v / (2 * num(az)) : 0));
            double[] est = accelZ(num(zKg), num(motorNm), num(fz));
            accelNote.setText(String.format(Locale.US, "Estimated max accel going up at this rapid (50 %% torque margin): ≈ %,d mm/s² "
                            + "(100 %% torque: %,d). The motor's own rotor inertia dominates on a 5 mm-lead screw. "
                            + "Practical: 60–70 %% of the highest clean stroke, usually 200–400.",
                    Math.round(est[1]), Math.round(est[0])));
        };
        TextWatcher cw = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int af) { }
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { calc.run(); }
        };
        for (EditText e : new EditText[]{az, fz, zKg, motorNm}) e.addTextChangedListener(cw);
        calc.run();

        // write Z accel and max rate without running the stroke, then read them back
        paramButton("Z params", fluid, new int[]{2}, () -> new int[][]{{(int) num(az)}, {(int) num(fz)}},
                v -> zLimitsError(v[0][0], v[1][0]));

        Button[] gps = goPauseStop();
        Button go = gps[0], pause = gps[1], stop = gps[2];
        TextView state = small("", WARN);
        TextView results = small("", FG);
        results.setTypeface(Typeface.MONOSPACE);
        body = outer;
        small(fluid ? "FluidNC keeps these values until it restarts; to keep them, reopen CNC setup and press Apply."
                : "Grbl stores $112 / $122 in its EEPROM at once: the last values tried stay in use.", DIM);

        // 0 = idle, 1 = going to Z50, 2 = stroke running
        final int[] phase = {0};
        final long[] strokeStart = {0}, goAt = {0};
        final boolean[] moved = {false}, paused = {false}, pauseSeen = {false};
        final double[] strokeCalc = {0};
        final String[] strokeLabel = {""};
        StringBuilder history = new StringBuilder();
        Runnable reset = () -> {
            phase[0] = 0;
            paused[0] = false;
            pause.setText("Pause");
            zl.target = -1;
        };
        Runnable watch = new Runnable() {
            @Override public void run() {
                if (!d.isShowing()) return;
                zl.postDelayed(this, 100);
                if (!host.connected()) { zl.wz = Float.NaN; zl.invalidate(); state.setText("Not connected"); return; }
                zl.wz = host.machinePos()[2] - host.wco()[2];
                String st = host.machineStatus();
                boolean idle = st.startsWith("State: Idle");
                if (!idle) moved[0] = true;
                if (st.startsWith("State: Hold") || st.startsWith("State: Door")) pauseSeen[0] = true;
                boolean atLo = Math.abs(zl.wz - Z_LO) < 0.05;
                if (phase[0] == 1) {
                    state.setText("Going to Z" + (int) Z_LO + "…   " + st);
                    // already standing at Z50 → no motion is seen, so also accept 1.5 s of Idle there
                    if (idle && atLo && (moved[0] || android.os.SystemClock.elapsedRealtime() - goAt[0] > 1500)) {
                        host.send("G0Z" + fmt(Z_HI));
                        host.send("G0Z" + fmt(Z_LO));
                        zl.target = 1;
                        phase[0] = 2;
                        moved[0] = false;
                        pauseSeen[0] = false;
                        strokeStart[0] = android.os.SystemClock.elapsedRealtime();
                    }
                } else if (phase[0] == 2) {
                    double el = (android.os.SystemClock.elapsedRealtime() - strokeStart[0]) / 1000.0;
                    if (zl.wz > Z_HI - 0.05) zl.target = 0;
                    else if (zl.target != 0) zl.target = 1;
                    state.setText(String.format(Locale.US, "Stroke %.1f s   %s", el, st));
                    if (idle && atLo && moved[0]) {
                        String line = String.format(Locale.US, "%s → %5.1f s (calc %.1f)%s", strokeLabel[0], el, strokeCalc[0],
                                pauseSeen[0] ? "  paused, time not valid" : "");
                        history.insert(0, line + "\n");
                        results.setText(history);
                        host.log("Z tuning: " + line);
                        state.setText("Stroke done. " + st);
                        reset.run();
                    }
                }
                zl.invalidate();
            }
        };
        zl.post(watch);                       // runs once the page is shown (the view is attached then)

        go.setOnClickListener(v -> {
            if (!host.connected()) { toast("Connect to the board first"); return; }
            if (host.busy()) { toast("Stop the running job first"); return; }
            if (phase[0] != 0) { toast("The stroke is still running – Stop it first"); return; }
            if (!host.machineStatus().startsWith("State: Idle")) { toast("The machine must be Idle – home or unlock ($X) first"); return; }
            int acc = (int) num(az), rate = (int) num(fz);
            Map<String, String> s = host.grblSettings();
            String bad = zLimitsError(acc, rate);
            if (bad != null) { toast(bad); return; }
            float[] o = host.wco();
            if (o[2] + Z_HI > -1) {
                new android.app.AlertDialog.Builder(a)
                        .setTitle("Z150 is out of reach")
                        .setMessage(String.format(Locale.US, "G54 Z is machine Z %.3f, so WCS Z%d is machine Z %.3f – above the Z home "
                                + "position (machine Z 0). Set G54 Z to machine Z %.0f or lower (Work offset tab), then Go again.",
                                o[2], (int) Z_HI, o[2] + Z_HI, -1 - Z_HI))
                        .setPositiveButton("OK", null)
                        .show();
                return;
            }
            java.util.List<String> cmds = paramCmds(fluid, new int[]{2}, new int[]{acc}, new int[]{rate});
            String was = s.containsKey("$112") ? "Z now: accel " + s.get("$122") + ", rapid " + s.get("$112") + "\n" : "";
            new android.app.AlertDialog.Builder(a)
                    .setTitle("Run the Z stroke?")
                    .setMessage(was + String.join("\n", cmds) + "\n\nG21G90G54\nG0Z" + fmt(Z_LO)
                            + "\nthen G0 Z" + fmt(Z_HI) + " → Z" + fmt(Z_LO) + " at the current X / Y."
                            + "\n\nCheck that the tool is clear of the bed and clamps at Z" + fmt(Z_LO) + "; keep a hand near E-STOP.")
                    .setPositiveButton("Go", (dlg, which) -> {
                        for (String c : cmds) host.send(c);
                        if (fluid) {                  // read back so the page shows the new values
                            host.send("$/axes/z/acceleration_mm_per_sec2");
                            host.send("$/axes/z/max_rate_mm_per_min");
                        }
                        host.send("G21G90G54");
                        host.send("G0Z" + fmt(Z_LO));
                        strokeLabel[0] = String.format(Locale.US, "Z a%-4d F%-5d", acc, rate);
                        strokeCalc[0] = 2 * moveTime(Z_HI - Z_LO, rate, acc);
                        host.log("Z tuning: " + String.join(" ", cmds) + ", stroke WCS Z50 → Z150 → Z50");
                        moved[0] = false;
                        goAt[0] = android.os.SystemClock.elapsedRealtime();
                        zl.target = 0;
                        phase[0] = 1;
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });
        pause.setOnClickListener(v -> {
            if (!host.connected()) { toast("Not connected"); return; }
            if (paused[0]) { host.resume(); paused[0] = false; pause.setText("Pause"); }
            else { host.hold(); paused[0] = true; pause.setText("Resume"); }
        });
        stop.setOnClickListener(v -> {
            if (!host.connected()) { toast("Not connected"); return; }
            // hold first so the reset comes after the stop and the position is kept; reset after 2 s at the latest
            host.hold();
            final long t0 = android.os.SystemClock.elapsedRealtime();
            Runnable waitStop = new Runnable() {
                @Override public void run() {
                    String st = host.machineStatus();
                    if (st.startsWith("State: Hold:0") || st.startsWith("State: Idle")
                            || android.os.SystemClock.elapsedRealtime() - t0 > 2000) {
                        host.estop();
                        host.log("Z tuning: stopped");
                        state.setText("Stopped.");
                        reset.run();
                    } else stop.postDelayed(this, 50);
                }
            };
            stop.post(waitStop);
        });
    }

    private static String fmt(double v) {
        if (v == Math.rint(v)) return String.valueOf((long) v);
        return String.format(Locale.US, "%.3f", v).replaceAll("0+$", "");
    }

    private void toast(String s) { android.widget.Toast.makeText(a, s, android.widget.Toast.LENGTH_LONG).show(); }

    /** From Grbl's $$, or null while it is not read. */
    private static AxisState fromGrbl(Map<String, String> s) {
        if (!s.containsKey("$3") || !s.containsKey("$23") || !s.containsKey("$110") || !s.containsKey("$130")) return null;
        AxisState st = new AxisState();
        st.dirMask = (int) dnum(s, "$3");
        st.homeMask = (int) dnum(s, "$23");
        st.homing = dnum(s, "$22") != 0;
        for (int i = 0; i < 3; i++) {
            st.dirInvert[i] = (st.dirMask & (1 << i)) != 0;
            st.homeMinus[i] = (st.homeMask & (1 << i)) != 0;   // Grbl homes towards + unless the $23 bit is set
            st.steps[i] = dnum(s, "$10" + i);
            st.rate[i] = dnum(s, "$11" + i);
            st.travel[i] = dnum(s, "$13" + i);
            st.seek[i] = dnum(s, "$25");
        }
        return st;
    }

    private static double dnum(Map<String, String> s, String k) {
        try { return Double.parseDouble(s.get(k)); } catch (Exception e) { return 0; }
    }

    private static String axisPath(int i) { return "axes/" + "xyz".charAt(i); }

    /** From FluidNC's config, or null when it has no x / y / z axes. */
    private static AxisState fromFluid(FluidConfig c) {
        AxisState st = new AxisState();
        st.fluid = c;
        st.homing = true;
        for (int i = 0; i < 3; i++) {
            String ax = axisPath(i);
            String pin = c.get(ax + "/motor0/*/direction_pin");
            if (pin == null || c.get(ax + "/steps_per_mm") == null) return null;
            st.dirInvert[i] = FluidConfig.pinLow(pin);
            st.homeMinus[i] = !"true".equalsIgnoreCase(c.get(ax + "/homing/positive_direction"));
            st.steps[i] = round3(c.num(ax + "/steps_per_mm", 0));
            st.rate[i] = round3(c.num(ax + "/max_rate_mm_per_min", 0));
            st.seek[i] = round3(c.num(ax + "/homing/seek_mm_per_min", 0));
            st.travel[i] = round3(c.num(ax + "/max_travel_mm", 0));
        }
        return st;
    }

    private static double round3(double v) { return Math.round(v * 1000) / 1000.0; }

    /** FluidNC homing order to match Grbl: Z alone first (cycle 1), then X and Y together (cycle 2). */
    private static final int[] HOMING_CYCLE = {2, 2, 1};

    private static String homingCycleChanges(FluidConfig c) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            String cur = c.get(axisPath(i) + "/homing/cycle");
            if (cur == null || (int) c.num(axisPath(i) + "/homing/cycle", -9) != HOMING_CYCLE[i])
                b.append(AX[i]).append(" homing cycle ").append(cur).append(" → ").append(HOMING_CYCLE[i]).append(i == 2 ? " (Z first)\n" : "\n");
        }
        return b.toString();
    }

    private void applyGrbl(AxisState st, AxisState want, TextView note) {
        int dir = st.dirMask & ~7, home = st.homeMask & ~7;
        for (int i = 0; i < 3; i++) {
            if (want.dirInvert[i]) dir |= 1 << i;
            if (want.homeMinus[i]) home |= 1 << i;
        }
        for (int i = 0; i < 3; i++) {
            if (Math.abs(want.steps[i] - st.steps[i]) > 0.001) host.send(String.format(Locale.US, "$10%d=%.3f", i, want.steps[i]));
            if (want.rate[i] != st.rate[i]) host.send("$11" + i + "=" + fmt(want.rate[i]));
            if (want.travel[i] != st.travel[i]) host.send("$13" + i + "=" + fmt(want.travel[i]));
        }
        if (dir != st.dirMask) host.send("$3=" + dir);
        if (home != st.homeMask) host.send("$23=" + home);
        if (want.seek[0] != st.seek[0]) host.send("$25=" + fmt(want.seek[0]));
        if (want.homing != st.homing) host.send("$22=" + (want.homing ? 1 : 0));
        host.send("$$");
        host.log("CNC setup: axis settings written to Grbl");
        note.setText("Written – reading back…");
    }

    /** @param reload runs once FluidNC is back with the new config */
    private void applyFluid(AxisState want, TextView note, Runnable reload) {
        note.setText("Reading the FluidNC config again ($CD)…");
        // read it fresh so nothing changed since the page opened is lost
        host.capture("$CD", lines -> {
            if (lines == null || lines.isEmpty()) { note.setText("Could not read the FluidNC config – nothing was changed."); return; }
            FluidConfig c = new FluidConfig(lines);
            String before = c.text();
            boolean ok = true;
            for (int i = 0; i < 3; i++) {
                String ax = axisPath(i);
                String pin = c.get(ax + "/motor0/*/direction_pin");
                ok &= pin != null && c.set(ax + "/motor0/*/direction_pin", FluidConfig.pinWithLow(pin, want.dirInvert[i]));
                ok &= c.set(ax + "/homing/positive_direction", want.homeMinus[i] ? "false" : "true");
                ok &= c.set(ax + "/homing/cycle", String.valueOf(HOMING_CYCLE[i]));
                ok &= c.set(ax + "/steps_per_mm", String.format(Locale.US, "%.3f", want.steps[i]));
                ok &= c.set(ax + "/max_rate_mm_per_min", fmt(want.rate[i]));
                ok &= c.set(ax + "/homing/seek_mm_per_min", fmt(want.seek[i]));
                ok &= c.set(ax + "/max_travel_mm", fmt(want.travel[i]));
            }
            if (!ok) { note.setText("The FluidNC config is missing an expected key – nothing was changed."); return; }
            String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new java.util.Date());
            host.saveLocal("config-before-" + stamp + ".yaml", before);
            host.saveLocal("config-" + stamp + ".yaml", c.text());
            note.setText("Uploading config.yaml…");
            host.log("CNC setup: old config kept on the tablet as fluidnc/config-before-" + stamp + ".yaml");
            host.uploadFluid("config.yaml", c.text().getBytes(java.nio.charset.StandardCharsets.UTF_8), (done, msg) -> {
                if (!done) { note.setText("Upload failed (" + msg + ") – config.yaml on the board is unchanged."); return; }
                note.setText("config.yaml written – restarting FluidNC ($Bye)…");
                host.restartFluid(() -> {
                    host.log("CNC setup: FluidNC restarted with the new config – home the machine before running a job");
                    reload.run();
                });
            });
        });
    }
}
