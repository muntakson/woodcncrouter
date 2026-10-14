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

    private Dialog page(String title, LinearLayout extraButtons) {
        int pad = px(16);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(pad, pad, pad, pad);
        TextView t = text(title, 22, FG);
        t.setTypeface(null, Typeface.BOLD);
        root.addView(t);

        ScrollView scroll = new ScrollView(a);
        body = new LinearLayout(a);
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
    private void table(String[][] rows) {
        TableLayout tl = new TableLayout(a);
        tl.setStretchAllColumns(true);
        tl.setShrinkAllColumns(true);
        for (int r = 0; r < rows.length; r++) {
            TableRow tr = new TableRow(a);
            for (String cell : rows[r]) {
                TextView t = text(cell, 14, r == 0 ? 0xFFFFFFFF : FG);
                if (r == 0) t.setTypeface(null, Typeface.BOLD);
                t.setBackgroundColor(r == 0 ? HEAD : CELL);
                t.setPadding(px(8), px(6), px(8), px(6));
                TableRow.LayoutParams lp = new TableRow.LayoutParams();
                lp.setMargins(0, 0, px(1), px(1));
                tr.addView(t, lp);
            }
            tl.addView(tr);
        }
        body.addView(tl);
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
        });
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
                + "$/axes/x/steps_per_mm=84.88, $/axes/y/steps_per_mm=84.88, $/axes/z/steps_per_mm=1600, $/axes/z/max_rate_mm_per_min=500, then $CD=config.yaml "
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
                + "Rack pitch: a rack sold as \"4 mm\" is usually module 1.25 = π × 1.25 = 3.927 mm (the default, which gives "
                + "84.88 with the 24T pinion at 1600 pulses/rev). Enter 4.0 only for a true 4 mm circular pitch (83.33). "
                + "Z: motor direct on a 5 mm-lead screw (no belt reduction, measured 2026-10-14) = 320 steps/mm, 5 mm per motor rev.");
        EditText red = field(row(), "X/Y reduction (1:n)", "cnc_red", "5");
        EditText teeth = field(row(), "X/Y pinion teeth", "cnc_teeth", "24");
        EditText pitch = field(row(), "X/Y rack pitch (mm)", "cnc_rack_pitch", "3.927");
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
        h("Work offset G54");
        p("G54 is the work zero that G-code jobs use: the machine position (after homing) where X0 Y0 Z0 of the job lies. "
                + "\"Write G54\" sends G10 L2 P1 with these values; the controller keeps them through power-off. "
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
            g54Now.setText(v != null ? "In the controller now: G54 = " + v.replace(",", " / ") + " (X / Y / Z)"
                    : host.connected() ? "Press \"Read G54\" to see the value in the controller." : "Connect to read G54 from the controller.");
        };
        LinearLayout g54Row = row();
        Button g54Read = button("Read G54"), g54Write = button("Write G54");
        g54Write.setTextColor(GOOD);
        for (Button b : new Button[]{g54Read, g54Write}) {
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
        g54Show.run();
        if (!fluid && host.connected() && !host.busy()) g54Query.run();   // FluidNC: $CD is being read now

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
        p("Feed for an 8 mm 2-flute end mill in plywood: about F2000 at 20000–24000 RPM (chip load ≈ 0.05 mm/tooth). "
                + "ER32 holds 3–20 mm shanks; use the collet that matches the tool shank.");

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
        d.show();
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
