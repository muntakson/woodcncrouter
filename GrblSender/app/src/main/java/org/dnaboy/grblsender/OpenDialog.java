package org.dnaboy.grblsender;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The toolbar's Open button: one list of the .nc files in Documents/GrblSender and on the backoffice.
 * A file whose backoffice copy is newer than the tablet's (or that is not on the tablet yet) is downloaded
 * first; the rest open from the tablet. "Browse device…" falls back to the system picker.
 */
final class OpenDialog {

    private static final class Entry {
        final String name;
        FilesSection.LocalFile local;
        long remoteModified = -1, remoteSize;
        Entry(String name) { this.name = name; }
        long newest() { return Math.max(local != null ? local.modified : 0, remoteModified); }
        /** Download when only the backoffice has it, or its copy is newer than the tablet's. */
        boolean fromRemote() { return remoteModified > 0 && (local == null || remoteModified > local.modified + 1000); }
    }

    static boolean isNc(String name) { return name != null && name.toLowerCase(Locale.US).endsWith(".nc"); }

    static void show(Activity a, FilesSection.Host host) {
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(a, 16);
        list.setPadding(pad, Ui.dp(a, 8), pad, Ui.dp(a, 8));
        list.addView(Ui.text(a, "Loading .nc files…", 14, Ui.MUTED));
        ScrollView sv = new ScrollView(a);
        sv.addView(list);
        AlertDialog dlg = new AlertDialog.Builder(a)
                .setTitle("Open G-code (.nc)")
                .setView(sv)
                .setNeutralButton("Browse device…", (d, w) -> host.browse())
                .setNegativeButton("Cancel", null)
                .show();

        // own thread: the app's worker may be busy drawing a big preview
        new Thread(() -> {
            Map<String, Entry> byName = new LinkedHashMap<>();
            for (FilesSection.LocalFile f : FilesSection.listLocal(a)) {
                if (!isNc(f.name) || byName.containsKey(f.name)) continue;
                Entry e = new Entry(f.name);
                e.local = f;
                byName.put(f.name, e);
            }
            String err = null;
            try {
                JSONArray remote = new JSONArray(RemoteLog.get("/api/gcode"));
                for (int i = 0; i < remote.length(); i++) {
                    JSONObject f = remote.optJSONObject(i);
                    String name = f.optString("name");
                    if (!isNc(name)) continue;
                    Entry e = byName.get(name);
                    if (e == null) byName.put(name, e = new Entry(name));
                    e.remoteModified = f.optLong("modified");
                    e.remoteSize = f.optLong("size");
                }
            } catch (Exception ex) {
                err = ex.getMessage();
            }
            List<Entry> entries = new ArrayList<>(byName.values());
            entries.sort((x, y) -> Long.compare(y.newest(), x.newest()));
            final String error = err;
            a.runOnUiThread(() -> {
                if (!dlg.isShowing()) return;
                list.removeAllViews();
                if (error != null) list.addView(Ui.text(a, "Backoffice not reachable (" + error + ") – tablet files only.", 12, Ui.MUTED));
                if (entries.isEmpty()) list.addView(Ui.text(a, "No .nc files on the tablet or the backoffice.", 14, Ui.MUTED));
                for (Entry e : entries) list.addView(row(a, e, () -> {
                    dlg.dismiss();
                    if (host.busy()) {
                        android.widget.Toast.makeText(a, "Stop the running job first", android.widget.Toast.LENGTH_SHORT).show();
                    } else if (e.fromRemote()) host.openRemote(e.name);
                    else host.openLocal(e.local.uri);
                }));
            });
        }, "open-list").start();
    }

    private static LinearLayout row(Activity a, Entry e, Runnable open) {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(0, Ui.dp(a, 10), 0, Ui.dp(a, 10));
        TextView n = Ui.text(a, e.name, 15, Ui.TEXT);
        n.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        r.addView(n);
        String where = e.fromRemote()
                ? (e.local == null ? "backoffice · downloads to the tablet" : "backoffice has a newer copy · downloads")
                : (e.remoteModified > 0 ? "tablet · same as backoffice" : "tablet");
        long size = e.fromRemote() ? e.remoteSize : e.local.size;
        r.addView(Ui.text(a, where + " · " + FilesSection.size(size) + " · " + FilesSection.date(e.newest()), 12, Ui.MUTED));
        r.setOnClickListener(v -> open.run());
        r.setBackgroundResource(android.R.drawable.list_selector_background);
        LinearLayout wrap = new LinearLayout(a);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(r);
        wrap.addView(Ui.divider(a));
        return wrap;
    }
}
