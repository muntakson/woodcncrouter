package org.dnaboy.grblsender;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Colours, widgets and line icons shared by the pages (the look of the "machine room" theme). */
final class Ui {

    static final int BG = 0xFF0B0F14, PANEL = 0xFF121820, PANEL2 = 0xFF18202A, BORDER = 0xFF263240,
            TEXT = 0xFFE3E9F0, MUTED = 0xFF7F8C99, ACCENT = 0xFF00E5FF, OK = 0xFF3DDC84, WARN = 0xFFFFC040,
            ERR = 0xFFFF5C6C, RUN = 0xFF4EA1FF, AX_X = 0xFFFF6B81, AX_Y = 0xFF3DDC84, AX_Z = 0xFF4EA1FF;

    private Ui() { }

    static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    static TextView text(Context c, CharSequence s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    /** Page heading: bold title with a muted one-line subtitle under it. */
    static LinearLayout heading(Context c, String title, String subtitle) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(c, title, 20, TEXT);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        box.addView(t);
        if (subtitle != null) {
            TextView s = text(c, subtitle, 13, MUTED);
            s.setPadding(0, dp(c, 2), 0, dp(c, 10));
            box.addView(s);
        }
        return box;
    }

    /** Small uppercase section label, e.g. "SERVER". */
    static TextView label(Context c, String s) {
        TextView t = text(c, s.toUpperCase(java.util.Locale.US), 11, MUTED);
        t.setLetterSpacing(0.12f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(c, 12), 0, dp(c, 6));
        return t;
    }

    static LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackgroundResource(R.drawable.card_bg);
        int p = dp(c, 12);
        l.setPadding(p, p, p, p);
        return l;
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    /** kind: 0 normal, 1 accent, 2 run (green), 3 hold (amber), 4 stop (red). */
    static Button button(Context c, String s, int kind) {
        Button b = new Button(c);
        b.setText(s);
        b.setAllCaps(false);
        b.setMinWidth(dp(c, 64));
        int[] bg = {R.drawable.btn_tech, R.drawable.btn_accent, R.drawable.btn_run, R.drawable.btn_hold, R.drawable.btn_stop};
        int[] fg = {TEXT, ACCENT, OK, WARN, ERR};
        b.setBackgroundResource(bg[kind]);
        b.setTextColor(fg[kind]);
        return b;
    }

    static LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, w);
    }

    static LinearLayout.LayoutParams margins(Context c, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(dp(c, l), dp(c, t), dp(c, r), dp(c, b));
        return p;
    }

    /** A vertical icon + label item for the navigation rail / settings section list. */
    static LinearLayout navItem(Context c, String icon, String label, boolean vertical) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(vertical ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        l.setGravity(vertical ? Gravity.CENTER : Gravity.CENTER_VERTICAL);
        l.setBackgroundResource(R.drawable.nav_item_bg);
        int p = dp(c, vertical ? 6 : 10);
        l.setPadding(p, dp(c, vertical ? 8 : 10), p, dp(c, vertical ? 8 : 10));
        l.setClickable(true);
        l.setFocusable(true);
        ImageView iv = new ImageView(c);
        iv.setImageDrawable(new Icon(icon, MUTED));
        int s = dp(c, vertical ? 24 : 20);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(s, s);
        if (!vertical) ip.setMarginEnd(dp(c, 12));
        l.addView(iv, ip);
        TextView t = text(c, label, vertical ? 11 : 15, MUTED);
        t.setGravity(Gravity.CENTER);
        if (vertical) t.setPadding(0, dp(c, 3), 0, 0);
        l.addView(t);
        l.setContentDescription(label);
        return l;
    }

    static void setNavSelected(LinearLayout item, boolean on) {
        item.setSelected(on);
        int color = on ? ACCENT : MUTED;
        ImageView iv = (ImageView) item.getChildAt(0);
        ((Icon) iv.getDrawable()).setColor(color);
        iv.invalidate();
        ((TextView) item.getChildAt(1)).setTextColor(on ? TEXT : MUTED);
    }

    /** Status chip in the title bar: coloured dot + text. */
    static void setChip(TextView chip, String s, int color) {
        chip.setText("● " + s);
        android.text.SpannableString sp = new android.text.SpannableString(chip.getText());
        sp.setSpan(new android.text.style.ForegroundColorSpan(color), 0, 1, 0);
        chip.setText(sp);
    }

    /**
     * Line icons drawn on a 24×24 grid with a stroke – no image assets, crisp at any size.
     * Kinds: control, settings, wiring, setup, about, files, jobs, debug, firmware, update, general, cloud, usb.
     */
    static final class Icon extends Drawable {
        private final String kind;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        Icon(String kind, int color) {
            this.kind = kind;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setColor(color);
        }

        void setColor(int color) { p.setColor(color); invalidateSelf(); }

        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            float s = Math.min(b.width(), b.height()) / 24f;
            c.save();
            c.translate(b.left + (b.width() - 24 * s) / 2, b.top + (b.height() - 24 * s) / 2);
            c.scale(s, s);
            p.setStrokeWidth(1.8f);
            p.setStyle(Paint.Style.STROKE);
            path.reset();
            switch (kind) {
                case "control":   // crosshair over a work area
                    c.drawCircle(12, 12, 7, p);
                    line(c, 12, 1.5f, 12, 6); line(c, 12, 18, 12, 22.5f);
                    line(c, 1.5f, 12, 6, 12); line(c, 18, 12, 22.5f, 12);
                    fillCircle(c, 12, 12, 2);
                    break;
                case "settings": {   // gear
                    c.drawCircle(12, 12, 3, p);
                    c.drawCircle(12, 12, 7, p);
                    p.setStrokeWidth(2.6f);
                    for (int i = 0; i < 8; i++) {
                        double a = Math.PI * i / 4;
                        line(c, (float) (12 + 7.5 * Math.cos(a)), (float) (12 + 7.5 * Math.sin(a)),
                                (float) (12 + 10 * Math.cos(a)), (float) (12 + 10 * Math.sin(a)));
                    }
                    break;
                }
                case "wiring":    // lightning bolt
                    path.moveTo(13.5f, 2); path.lineTo(5, 13.5f); path.lineTo(11.5f, 13.5f);
                    path.lineTo(10.5f, 22); path.lineTo(19, 10.5f); path.lineTo(12.5f, 10.5f); path.close();
                    c.drawPath(path, p);
                    break;
                case "setup":     // router: gantry, spindle and bit
                    line(c, 3, 5, 21, 5);
                    c.drawRoundRect(new RectF(8.5f, 7, 15.5f, 15), 1.5f, 1.5f, p);
                    path.moveTo(10.5f, 15); path.lineTo(12, 20); path.lineTo(13.5f, 15);
                    c.drawPath(path, p);
                    line(c, 3, 22, 21, 22);
                    break;
                case "about":
                    c.drawCircle(12, 12, 9, p);
                    line(c, 12, 11, 12, 16.5f);
                    fillCircle(c, 12, 7.6f, 1.2f);
                    break;
                case "files":     // folder
                    path.moveTo(3, 6.5f); path.lineTo(9, 6.5f); path.lineTo(11, 8.5f); path.lineTo(21, 8.5f);
                    path.lineTo(21, 19); path.lineTo(3, 19); path.close();
                    c.drawPath(path, p);
                    break;
                case "jobs":      // clock + list
                    c.drawCircle(8, 12, 5.5f, p);
                    line(c, 8, 9, 8, 12); line(c, 8, 12, 10, 13.5f);
                    line(c, 16, 7, 21, 7); line(c, 16, 12, 21, 12); line(c, 16, 17, 21, 17);
                    break;
                case "debug":     // terminal prompt
                    c.drawRoundRect(new RectF(2.5f, 4, 21.5f, 20), 2, 2, p);
                    path.moveTo(6.5f, 9); path.lineTo(10, 12); path.lineTo(6.5f, 15);
                    c.drawPath(path, p);
                    line(c, 12, 15.5f, 17, 15.5f);
                    break;
                case "firmware":  // chip with pins
                    c.drawRoundRect(new RectF(6, 6, 18, 18), 1.5f, 1.5f, p);
                    c.drawRect(new RectF(9.5f, 9.5f, 14.5f, 14.5f), p);
                    for (int i = 0; i < 3; i++) {
                        float k = 9 + i * 3;
                        line(c, k, 2.5f, k, 6); line(c, k, 18, k, 21.5f);
                        line(c, 2.5f, k, 6, k); line(c, 18, k, 21.5f, k);
                    }
                    break;
                case "update":    // arrow into tray
                    line(c, 12, 3, 12, 15);
                    path.moveTo(7, 10.5f); path.lineTo(12, 15.5f); path.lineTo(17, 10.5f);
                    c.drawPath(path, p);
                    path.reset();
                    path.moveTo(4, 15); path.lineTo(4, 20.5f); path.lineTo(20, 20.5f); path.lineTo(20, 15);
                    c.drawPath(path, p);
                    break;
                case "diag":      // square pulse train
                    path.moveTo(2.5f, 17); path.lineTo(6, 17); path.lineTo(6, 7); path.lineTo(10, 7); path.lineTo(10, 17);
                    path.lineTo(14, 17); path.lineTo(14, 7); path.lineTo(18, 7); path.lineTo(18, 17); path.lineTo(21.5f, 17);
                    c.drawPath(path, p);
                    break;
                case "general":   // sliders
                    line(c, 4, 7, 20, 7); line(c, 4, 17, 20, 17);
                    fillCircle(c, 9, 7, 2.6f); fillCircle(c, 15, 17, 2.6f);
                    break;
                case "camera":    // camera body, lens, viewfinder bump
                    c.drawRoundRect(new RectF(2.5f, 7, 21.5f, 19.5f), 2.5f, 2.5f, p);
                    path.moveTo(8, 7); path.lineTo(9.5f, 4.5f); path.lineTo(14.5f, 4.5f); path.lineTo(16, 7);
                    c.drawPath(path, p);
                    c.drawCircle(12, 13, 3.6f, p);
                    break;
                case "cloud":
                    path.moveTo(7, 18.5f);
                    path.cubicTo(3.5f, 18.5f, 2.5f, 14, 6, 12.8f);
                    path.cubicTo(6, 8, 12.5f, 6, 14.5f, 10);
                    path.cubicTo(18.5f, 8.5f, 21.5f, 12, 20, 15);
                    path.cubicTo(21.5f, 17.5f, 19.5f, 18.5f, 18, 18.5f);
                    path.close();
                    c.drawPath(path, p);
                    break;
                case "usb":
                    line(c, 12, 3, 12, 19);
                    path.moveTo(9.5f, 5.5f); path.lineTo(12, 2.5f); path.lineTo(14.5f, 5.5f);
                    c.drawPath(path, p);
                    path.reset();
                    path.moveTo(12, 14); path.lineTo(7, 11); path.lineTo(7, 8.5f);
                    path.moveTo(12, 12); path.lineTo(17, 9.5f); path.lineTo(17, 7.5f);
                    c.drawPath(path, p);
                    fillCircle(c, 12, 20, 1.8f);
                    break;
                default:
                    c.drawCircle(12, 12, 8, p);
            }
            c.restore();
        }

        private void line(Canvas c, float x0, float y0, float x1, float y1) { c.drawLine(x0, y0, x1, y1, p); }

        private void fillCircle(Canvas c, float x, float y, float r) {
            p.setStyle(Paint.Style.FILL);
            c.drawCircle(x, y, r, p);
            p.setStyle(Paint.Style.STROKE);
        }

        @Override public void setAlpha(int a) { p.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter f) { p.setColorFilter(f); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        @Override public int getIntrinsicWidth() { return 48; }
        @Override public int getIntrinsicHeight() { return 48; }
    }

    /** Thin divider line. */
    static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(BORDER);
        v.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));
        return v;
    }
}
