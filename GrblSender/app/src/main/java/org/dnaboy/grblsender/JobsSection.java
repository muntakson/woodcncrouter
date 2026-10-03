package org.dnaboy.grblsender;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Settings → Job log: every run with its timeline (started, paused at, resumed at, ended at). */
final class JobsSection {

    private final Activity a;
    private final JobLog log;
    private LinearLayout list;
    private String expanded;

    JobsSection(Activity a, JobLog log) {
        this.a = a;
        this.log = log;
    }

    View build() {
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        LinearLayout top = Ui.row(a);
        top.addView(Ui.heading(a, "Job log", "cnc.job – each run with start, pauses, resumes and end. Also kept on the backoffice ("
                + RemoteLog.server().replaceFirst("^https?://", "") + "/cnc)."), Ui.weight(1));
        Button refresh = Ui.button(a, "⟳ Refresh", 0);
        Button clear = Ui.button(a, "Clear", 4);
        top.addView(refresh);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bp.setMarginStart(Ui.dp(a, 8));
        top.addView(clear, bp);
        root.addView(top);

        ScrollView sv = new ScrollView(a);
        list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        refresh.setOnClickListener(v -> refresh());
        clear.setOnClickListener(v -> new AlertDialog.Builder(a)
                .setTitle("Clear the job log on this tablet?")
                .setMessage("Jobs already sent to the backoffice stay there.")
                .setPositiveButton("Clear", (d, w) -> { log.clear(); refresh(); })
                .setNegativeButton("Cancel", null).show());
        refresh();
        return root;
    }

    void refresh() {
        if (list == null) return;
        list.removeAllViews();
        List<JobLog.Job> jobs = log.jobs();
        if (jobs.isEmpty()) {
            TextView t = Ui.text(a, "No jobs yet. Every Run is logged here.", 14, Ui.MUTED);
            t.setPadding(0, Ui.dp(a, 12), 0, 0);
            list.addView(t);
            return;
        }
        for (JobLog.Job j : jobs) list.addView(jobCard(j), Ui.margins(a, 0, 0, 0, 8));
    }

    private View jobCard(JobLog.Job j) {
        LinearLayout card = Ui.card(a);
        LinearLayout head = Ui.row(a);
        TextView name = Ui.text(a, j.file != null ? j.file : "untitled", 15, Ui.TEXT);
        name.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        head.addView(name, Ui.weight(1));
        String res = j.ended == 0 ? "RUNNING" : j.result == null ? "ENDED" : j.result.toUpperCase(Locale.US);
        int color = j.ended == 0 ? Ui.RUN : "completed".equals(j.result) ? Ui.OK : Ui.ERR;
        TextView chip = Ui.text(a, res, 11, color);
        chip.setBackgroundResource(R.drawable.chip_bg);
        chip.setPadding(Ui.dp(a, 10), Ui.dp(a, 3), Ui.dp(a, 10), Ui.dp(a, 3));
        chip.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(chip);
        card.addView(head);

        long end = j.ended != 0 ? j.ended : System.currentTimeMillis();
        boolean synced = true;
        for (JobLog.Event e : j.events) synced &= e.synced;
        String meta = time(j.started, true) + "  ·  " + hms((end - j.started) / 1000)
                + (j.pausedMs > 0 ? " (paused " + hms(j.pausedMs / 1000) + ")" : "")
                + "  ·  line " + j.lastLine + " / " + j.lines
                + (j.errors > 0 ? "  ·  " + j.errors + " errors" : "")
                + (j.source != null && !j.source.equals("tablet") ? "  ·  from " + j.source : "")
                + "  ·  " + (synced ? "☁ on backoffice" : "☁ sync pending");
        TextView m = Ui.text(a, meta, 12, Ui.MUTED);
        m.setPadding(0, Ui.dp(a, 2), 0, 0);
        card.addView(m);

        if (j.id.equals(expanded)) {
            card.addView(Ui.divider(a), Ui.margins(a, 0, 8, 0, 6));
            for (JobLog.Event e : j.events) {
                LinearLayout r = Ui.row(a);
                TextView t = Ui.text(a, time(e.ts, false), 12, Ui.MUTED);
                t.setTypeface(Typeface.MONOSPACE);
                t.setMinWidth(Ui.dp(a, 76));
                r.addView(t);
                TextView ev = Ui.text(a, e.event, 12, eventColor(e.event));
                ev.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                ev.setMinWidth(Ui.dp(a, 80));
                r.addView(ev);
                String d = (e.line > 0 ? "line " + e.line : "") + (e.detail != null ? (e.line > 0 ? " – " : "") + e.detail : "");
                r.addView(Ui.text(a, d, 12, Ui.TEXT), Ui.weight(1));
                card.addView(r);
            }
        }
        card.setClickable(true);
        card.setOnClickListener(v -> {
            expanded = j.id.equals(expanded) ? null : j.id;
            refresh();
        });
        return card;
    }

    private static int eventColor(String e) {
        switch (e) {
            case "started": return Ui.ACCENT;
            case "paused": return Ui.WARN;
            case "resumed": return Ui.OK;
            case "ended": return Ui.RUN;
            default: return Ui.TEXT;
        }
    }

    private static String time(long ms, boolean withDate) {
        return new SimpleDateFormat(withDate ? "yyyy-MM-dd HH:mm:ss" : "HH:mm:ss", Locale.US).format(new Date(ms));
    }

    private static String hms(long sec) {
        return sec >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", sec / 3600, (sec % 3600) / 60, sec % 60)
                : String.format(Locale.US, "%d:%02d", sec / 60, sec % 60);
    }

    void detach() { list = null; }
}
