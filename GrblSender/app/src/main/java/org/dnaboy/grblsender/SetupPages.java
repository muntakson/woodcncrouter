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
 * (MA860H drivers, MK300 spindle inverter, home switches) and the mechanics → $ settings.
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
    }

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
                + "PB10–PB12 are 5 V tolerant; the other pins are not."
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

        h("3. Home / limit switches");
        table(blue ? new String[][]{
                {"Switch", "Blue Pill", "Other side"},
                {"Home X", "PB10", "G"},
                {"Home Y", "PB11", "G"},
                {"Home Z", "PB12", "G"},
                {"Probe (optional)", "PA15", "G"},
        } : new String[][]{
                {"Switch", "Pro Mini", "Other side"},
                {"Home X", "D9", "GND"},
                {"Home Y", "D10", "GND"},
                {"Home Z", "D12", "GND"},
                {"Probe (optional)", "A5", "GND"},
        });
        p("Grbl uses the internal pull-ups: a pin reads \"triggered\" when it is pulled to GND. "
                + "Normally-open switches: $5=0. Normally-closed switches (safer, a broken wire stops the machine): $5=1.");
        p("Inductive proximity sensors (NPN, 12–24 V) must go through a PC817 opto module. Never wire their 24 V "
                + "output to the " + b + "." + (blue ? " PB10–PB12 tolerate 5 V, not 24 V." : ""), WARN);
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
                + "probe, limit and macro pins on the right header, USB and TF card at the bottom.", 0.6f);
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
                + "$/axes/x/steps_per_mm=81.49, $/axes/y/steps_per_mm=81.49, $/axes/z/steps_per_mm=16.3, then $CD=config.yaml "
                + "to save. If an axis runs the wrong way, add :low to its direction_pin in config.yaml.", WARN);
        p("Measure the STEP terminal voltage before wiring the MA860H: if it is 3.3 V the opto current is low; "
                + "use a 5 V buffer (74HCT245) if a motor misses steps.");

        h("2. Spindle on/off → MK300 inverter");
        p("Spindle on/off: GPIO12 (PWM/enable_pin in config.yaml, set 2026-10-12; the stock file had it as flood coolant). "
                + "M3 / M5 switch it, like D13 / PB13 on the other boards. GPIO27 is the PWM output (PWM-OUT terminal, its "
                + "voltage set with PWM-ADJ), unused while the MK300 sets the speed. Wire GPIO12 through a PC817 optocoupler "
                + "exactly as for the other boards (GPIO12 → 150 Ω → PC817 pin 1, GND → pin 2, pin 4 → X1, pin 3 → COM). "
                + "Never connect the card to X1 directly: X1 sits at about +24 V.", WARN);

        h("3. Home / limit switches and probe");
        table(new String[][]{
                {"Switch", "Card terminal", "ESP32 pin"},
                {"Home X", "X-LIM", "GPIO33 (active low)"},
                {"Home Y", "Y-LIM", "GPIO32 (active low)"},
                {"Home Z", "Z-LIM", "GPIO35 (active low)"},
                {"Probe", "PROBE", "GPIO34 (active low)"},
                {"Feed hold", "", "GPIO36"},
                {"Other side of each switch", "GND", ""},
        });
        p("Normally-open switches to GND. GPIO34–36 are input-only and have no internal pull-up; the card supplies them. "
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

    void showCncSetup() {
        LinearLayout buttons = new LinearLayout(a);
        Button apply = button(MainActivity.BOARD_FLUIDNC.equals(host.board()) ? "Apply steps/mm to FluidNC" : "Apply $100–$102 to Grbl");
        apply.setTextColor(GOOD);
        buttons.addView(apply, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.6f));
        Dialog d = page("CNC setup", buttons);

        image(R.drawable.art_cnc_router, "Illustration (AI-generated): 1325 × 2500 mm gantry router, 4 kW ER32 spindle, MK300 inverter.");

        h("Machine");
        table(new String[][]{
                {"Part", "This router"},
                {"Bed", "1325 × 2500 mm (X = 2500 long side, Y = 1325)"},
                {"X / Y drive", "NEMA34 → 1:5 belt reduction → pinion → rack (4 mm pitch)"},
                {"Z drive", "NEMA34 → pinion → rack, no reduction"},
                {"Stepper drivers", "MA860H, 1600 pulses/rev (SW5 ON, SW6 OFF, SW7 ON, SW8 ON)"},
                {"Spindle", "4 kW, ER32 collet, max 24000 RPM, on/off by M3 / M5 via MK300"},
        });

        image(R.drawable.art_cnc_drivetrain, "Illustration (AI-generated): X/Y with the 1:5 belt, Z direct.");

        h("Steps per mm");
        p("steps/mm = pulses/rev × reduction ÷ (pinion teeth × rack pitch). Count the pinion teeth and enter them.\n"
                + "Rack pitch: a rack sold as \"4 mm\" is usually module 1.25 = π × 1.25 = 3.927 mm (the default, which gives "
                + "the 81.49 / 16.30 in Grbl now). Enter 4.0 only for a true 4 mm circular pitch (80.0 / 16.0).");
        EditText pulses = field(row(), "Driver pulses/rev", "cnc_pulses", "1600");
        EditText red = field(row(), "X/Y reduction (1:n)", "cnc_red", "5");
        EditText teeth = field(row(), "X/Y pinion teeth", "cnc_teeth", "25");
        EditText pitch = field(row(), "X/Y rack pitch (mm)", "cnc_rack_pitch", "3.927");
        EditText zTeeth = field(row(), "Z pinion teeth", "cnc_zteeth", "25");
        EditText zPitch = field(row(), "Z rack pitch (mm)", "cnc_zrack_pitch", "3.927");
        TextView result = p("", GOOD);
        result.setTextSize(18);
        result.setTypeface(null, Typeface.BOLD);
        TextView current = p("", DIM);

        Runnable calc = () -> {
            double xy = stepsPerMm(num(pulses), num(red), num(teeth), num(pitch));
            double z = stepsPerMm(num(pulses), 1, num(zTeeth), num(zPitch));
            double mmRevXY = num(teeth) * num(pitch) / Math.max(1e-9, num(red));
            double mmRevZ = num(zTeeth) * num(zPitch);
            result.setText(String.format(Locale.US, "X / Y: %.1f steps/mm  ($100 = $101 = %.3f)\nZ: %.1f steps/mm  ($102 = %.3f)",
                    xy, xy, z, z, z));
            Map<String, String> s = host.grblSettings();
            current.setText(String.format(Locale.US, "Travel per motor rev: X/Y %.2f mm, Z %.2f mm.\n%s", mmRevXY, mmRevZ,
                    s.containsKey("$100")
                            ? "In Grbl now: $100=" + s.get("$100") + "  $101=" + s.get("$101") + "  $102=" + s.get("$102")
                            : "Connect to see the values in Grbl now."));
            SharedPreferences.Editor ed = host.prefs().edit();
            for (EditText e : new EditText[]{pulses, red, teeth, pitch, zTeeth, zPitch}) ed.putString((String) e.getTag(), e.getText().toString());
            ed.apply();
        };
        TextWatcher w = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int af) { }
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { calc.run(); }
        };
        for (EditText e : new EditText[]{pulses, red, teeth, pitch, zTeeth, zPitch}) e.addTextChangedListener(w);
        calc.run();
        p("Then check it: jog X 500 mm and measure. New $100 = current $100 × 500 ÷ measured distance. Same for Y and Z (use 50 mm on Z).");

        h("Recommended Grbl settings");
        table(new String[][]{
                {"Setting", "Value", "Why"},
                {"$110 / $111 / $112", "1000 / 1000 / 1000", "Max rate mm/min; raise in steps (2000, 3000) once motion is reliable"},
                {"$120 / $121 / $122", "30 / 30 / 10", "Acceleration mm/s²; heavy gantry, raise slowly (50, 80)"},
                {"$130 / $131", "2500 / 1325", "Max travel = bed size (measure the real travel, it is a little shorter)"},
                {"$30 / $31", "24000 / 0", "Spindle max / min RPM (S24000 = full; speed itself is set on the MK300)"},
                {"$32", "0", "Laser mode off"},
                {"$1", "255", "Keep motors energised when idle so Z cannot drop"},
                {"$0", "10", "Step pulse µs, safe for MA860H opto inputs"},
        });
        p("Feed for an 8 mm 2-flute end mill in plywood: about F2000 at 20000–24000 RPM (chip load ≈ 0.05 mm/tooth). "
                + "ER32 holds 3–20 mm shanks; use the collet that matches the tool shank.");

        apply.setOnClickListener(v -> {
            if (!host.connected()) { host.log("Connect to the board first"); return; }
            if (host.busy()) { host.log("Stop the running job before changing settings"); return; }
            double xy = stepsPerMm(num(pulses), num(red), num(teeth), num(pitch));
            double z = stepsPerMm(num(pulses), 1, num(zTeeth), num(zPitch));
            if (xy < 1 || xy > 2000 || z < 1 || z > 2000) { host.log("Steps/mm out of range – check the numbers"); return; }
            if (MainActivity.BOARD_FLUIDNC.equals(host.board())) {
                // FluidNC keeps these in config.yaml; $100… do not exist there. $CD= saves the running config.
                new android.app.AlertDialog.Builder(a)
                        .setTitle("Write steps/mm to FluidNC?")
                        .setMessage(String.format(Locale.US, "$/axes/x/steps_per_mm=%.3f\n$/axes/y/steps_per_mm=%.3f\n"
                                + "$/axes/z/steps_per_mm=%.3f\n\nThis changes the running machine only. To keep it after a "
                                + "restart, send $CD=config.yaml (it rewrites config.yaml on the board).", xy, xy, z))
                        .setPositiveButton("Write", (dlg, which) -> {
                            host.send(String.format(Locale.US, "$/axes/x/steps_per_mm=%.3f", xy));
                            host.send(String.format(Locale.US, "$/axes/y/steps_per_mm=%.3f", xy));
                            host.send(String.format(Locale.US, "$/axes/z/steps_per_mm=%.3f", z));
                            for (char ax : new char[]{'x', 'y', 'z'}) host.send("$/axes/" + ax + "/steps_per_mm");
                            host.log("Steps/mm written to FluidNC (not saved yet: $CD=config.yaml); re-check with a measured jog");
                            current.postDelayed(calc, 1500);
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
                return;
            }
            new android.app.AlertDialog.Builder(a)
                    .setTitle("Write steps/mm to Grbl?")
                    .setMessage(String.format(Locale.US, "$100=%.3f\n$101=%.3f\n$102=%.3f", xy, xy, z))
                    .setPositiveButton("Write", (dlg, which) -> {
                        host.send(String.format(Locale.US, "$100=%.3f", xy));
                        host.send(String.format(Locale.US, "$101=%.3f", xy));
                        host.send(String.format(Locale.US, "$102=%.3f", z));
                        host.send("$$");
                        host.log("Steps/mm written; re-check with a measured jog");
                        current.postDelayed(calc, 1500);
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });
        d.show();
    }
}
