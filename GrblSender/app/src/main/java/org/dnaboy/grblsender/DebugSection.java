package org.dnaboy.grblsender;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Settings → Debug console: live trace of the backoffice link (SRV) and the CNC link (CNC:
 * USB, firmware banner, every line sent and received), with a filter and a verbose switch.
 */
final class DebugSection implements DebugLog.Listener {

    interface Host { String linkSummary(); }

    private static final int SHOW_MAX = 1500;

    private final Activity a;
    private final Host host;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private TextView out, summary;
    private ScrollView scroll;
    private String filter;            // null = all
    private boolean paused;
    private long shownSeq;
    private int shownLines;
    private final LinearLayout[] filterBtns = new LinearLayout[3];

    private final Runnable flush = this::appendNew;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (summary == null) return;
            summary.setText(host.linkSummary());
            ui.postDelayed(this, 1000);
        }
    };

    DebugSection(Activity a, Host host) {
        this.a = a;
        this.host = host;
    }

    View build() {
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(Ui.heading(a, "Debug console", "Backoffice connection (SRV) and CNC firmware link (CNC)"));

        summary = Ui.text(a, host.linkSummary(), 12, Ui.TEXT);
        summary.setTypeface(Typeface.MONOSPACE);
        LinearLayout sc = Ui.card(a);
        sc.addView(summary);
        root.addView(sc, Ui.margins(a, 0, 0, 0, 8));

        LinearLayout bar = Ui.row(a);
        String[] names = {"All", "Backoffice", "CNC"};
        String[] keys = {null, DebugLog.SRV, DebugLog.CNC};
        String[] icons = {"debug", "cloud", "usb"};
        for (int i = 0; i < 3; i++) {
            LinearLayout b = Ui.navItem(a, icons[i], names[i], false);
            b.setPadding(Ui.dp(a, 10), Ui.dp(a, 6), Ui.dp(a, 12), Ui.dp(a, 6));
            final String k = keys[i];
            b.setOnClickListener(v -> { filter = k; markFilter(); reload(); });
            filterBtns[i] = b;
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            p.setMarginEnd(Ui.dp(a, 6));
            bar.addView(b, p);
        }
        View spacer = new View(a);
        bar.addView(spacer, Ui.weight(1));
        Switch verbose = new Switch(a);
        verbose.setText("Verbose ");
        verbose.setTextColor(Ui.MUTED);
        verbose.setChecked(DebugLog.verbose);
        verbose.setOnCheckedChangeListener((s, on) -> DebugLog.verbose = on);
        bar.addView(verbose);
        Button pause = Ui.button(a, "❚❚ Pause", 0);
        Button copy = Ui.button(a, "Copy", 0);
        Button clear = Ui.button(a, "Clear", 4);
        for (Button b : new Button[]{pause, copy, clear}) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            p.setMarginStart(Ui.dp(a, 6));
            bar.addView(b, p);
        }
        root.addView(bar, Ui.margins(a, 0, 0, 0, 8));

        scroll = new ScrollView(a);
        scroll.setBackgroundResource(R.drawable.dro_bg);
        out = Ui.text(a, "", 12, Ui.TEXT);
        out.setTypeface(Typeface.MONOSPACE);
        out.setTextIsSelectable(true);
        int p = Ui.dp(a, 8);
        out.setPadding(p, p, p, p);
        scroll.addView(out);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        pause.setOnClickListener(v -> {
            paused = !paused;
            pause.setText(paused ? "▶ Live" : "❚❚ Pause");
            if (!paused) appendNew();
        });
        copy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) a.getSystemService(Activity.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("Grbl Sender debug", out.getText().toString()));
            android.widget.Toast.makeText(a, "Copied " + shownLines + " lines", android.widget.Toast.LENGTH_SHORT).show();
        });
        clear.setOnClickListener(v -> { DebugLog.clear(); reload(); });

        markFilter();
        reload();
        DebugLog.addListener(this);
        ui.post(tick);
        return root;
    }

    private void markFilter() {
        String[] keys = {null, DebugLog.SRV, DebugLog.CNC};
        for (int i = 0; i < 3; i++) Ui.setNavSelected(filterBtns[i], java.util.Objects.equals(keys[i], filter));
    }

    private void reload() {
        out.setText("");
        shownSeq = 0;
        shownLines = 0;
        appendNew();
    }

    @Override public void onEntry(DebugLog.Entry e) {
        ui.removeCallbacks(flush);
        ui.postDelayed(flush, 150);      // batch bursts (a $$ report is ~35 lines)
    }

    private void appendNew() {
        if (out == null || paused) return;
        List<DebugLog.Entry> list = DebugLog.since(shownSeq, filter, SHOW_MAX);
        if (list.isEmpty()) return;
        boolean atEnd = scroll.getScrollY() + scroll.getHeight() >= out.getHeight() - Ui.dp(a, 40);
        SpannableStringBuilder b = new SpannableStringBuilder();
        for (DebugLog.Entry e : list) {
            if (shownLines > 0 || b.length() > 0) b.append('\n');
            int s = b.length();
            b.append(fmt.format(new Date(e.ts))).append(' ');
            b.setSpan(new ForegroundColorSpan(0xFF4A5866), s, b.length(), 0);
            s = b.length();
            b.append(e.src).append(' ');
            b.setSpan(new ForegroundColorSpan(DebugLog.SRV.equals(e.src) ? 0xFFB48CFF : Ui.ACCENT), s, b.length(), 0);
            s = b.length();
            b.append(e.text);
            int color = e.level == 'E' ? Ui.ERR : e.level == 'W' ? Ui.WARN : e.level == 'D' ? Ui.MUTED
                    : e.text.startsWith(">>") ? 0xFF8FD8FF : e.text.startsWith("<<") ? 0xFFB9E3B9 : Ui.TEXT;
            b.setSpan(new ForegroundColorSpan(color), s, b.length(), 0);
            shownSeq = e.seq;
            shownLines++;
        }
        out.append(b);
        if (shownLines > SHOW_MAX * 2) {           // keep the TextView light: drop the older half
            CharSequence t = out.getText();
            int cut = 0;
            for (int n = 0; n < SHOW_MAX && cut >= 0; n++) cut = t.toString().indexOf('\n', cut + 1);
            if (cut > 0) { out.setText(t.subSequence(cut + 1, t.length())); shownLines -= SHOW_MAX; }
        }
        if (atEnd) scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    void detach() {
        DebugLog.removeListener(this);
        ui.removeCallbacks(flush);
        ui.removeCallbacks(tick);
        out = null;
        summary = null;
    }
}
