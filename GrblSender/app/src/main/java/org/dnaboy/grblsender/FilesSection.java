package org.dnaboy.grblsender;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentUris;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;

/**
 * Settings → Files: the G-code (.nc) files on the backoffice next to the ones on the tablet
 * (Documents/GrblSender). Opening a backoffice file downloads it into that folder first.
 */
final class FilesSection {

    static final String LOCAL_DIR = "GrblSender";

    interface Host {
        void openLocal(Uri uri);          // load into the editor and show the Control page
        void openRemote(String name);     // download, save to the tablet, then open
        void browse();                    // system file picker
        boolean busy();                   // a job is running
    }

    static final class LocalFile {
        final Uri uri;
        final String name;
        final long size, modified;
        LocalFile(Uri uri, String name, long size, long modified) {
            this.uri = uri; this.name = name; this.size = size; this.modified = modified;
        }
    }

    private final Activity a;
    private final Host host;
    private final ExecutorService worker;
    private LinearLayout remoteList, localList;
    private TextView remoteHdr;

    FilesSection(Activity a, Host host, ExecutorService worker) {
        this.a = a;
        this.host = host;
        this.worker = worker;
    }

    View build() {
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        LinearLayout top = Ui.row(a);
        top.addView(Ui.heading(a, "Files", "G-code programs on the backoffice and on this tablet"), Ui.weight(1));
        Button refresh = Ui.button(a, "⟳ Refresh", 0);
        Button browse = Ui.button(a, "Browse device…", 1);
        top.addView(refresh);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bp.setMarginStart(Ui.dp(a, 8));
        top.addView(browse, bp);
        root.addView(top);

        LinearLayout cols = new LinearLayout(a);
        cols.setOrientation(LinearLayout.HORIZONTAL);
        remoteHdr = Ui.label(a, "Backoffice");
        LinearLayout left = column(remoteHdr);
        remoteList = (LinearLayout) ((ScrollView) left.getChildAt(1)).getChildAt(0);
        LinearLayout right = column(Ui.label(a, "Tablet · Documents/" + LOCAL_DIR));
        localList = (LinearLayout) ((ScrollView) right.getChildAt(1)).getChildAt(0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
        lp.setMarginEnd(Ui.dp(a, 10));
        cols.addView(left, lp);
        cols.addView(right, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));
        root.addView(cols, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        refresh.setOnClickListener(v -> refresh());
        browse.setOnClickListener(v -> host.browse());
        refresh();
        return root;
    }

    private LinearLayout column(TextView header) {
        LinearLayout col = Ui.card(a);
        col.addView(header);
        ScrollView sv = new ScrollView(a);
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        col.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        return col;
    }

    void refresh() {
        remoteHdr.setText(("Backoffice · " + RemoteLog.server().replaceFirst("^https?://", "")).toUpperCase(Locale.US));
        message(remoteList, "Loading…");
        message(localList, "Loading…");
        worker.execute(() -> {
            JSONArray remote = null;
            String err = null;
            try {
                remote = new JSONArray(RemoteLog.get("/api/gcode"));
            } catch (Exception e) {
                err = e.getMessage();
            }
            List<LocalFile> local = listLocal(a);
            final JSONArray r = remote;
            final String error = err;
            a.runOnUiThread(() -> {
                if (remoteList == null) return;
                fillRemote(r, error);
                fillLocal(local);
            });
        });
    }

    private void fillRemote(JSONArray files, String error) {
        remoteList.removeAllViews();
        if (files == null) { message(remoteList, "Could not reach the backoffice: " + error); return; }
        if (files.length() == 0) { message(remoteList, "No G-code files on the backoffice. Upload them on its /cnc page."); return; }
        for (int i = 0; i < files.length(); i++) {
            JSONObject f = files.optJSONObject(i);
            String name = f.optString("name");
            String meta = size(f.optLong("size")) + " · " + date(f.optLong("modified"))
                    + (f.optString("title").isEmpty() ? "" : "\n" + f.optString("title"));
            Button open = Ui.button(a, "⇩ Open", 1);
            open.setOnClickListener(v -> {
                if (host.busy()) { toast("Stop the running job first"); return; }
                host.openRemote(name);
            });
            remoteList.addView(fileRow(name, meta, open, null));
        }
    }

    private void fillLocal(List<LocalFile> files) {
        localList.removeAllViews();
        if (files.isEmpty()) {
            message(localList, "No files yet. Files opened from the backoffice are saved here; use \"Browse device…\" for others.");
            return;
        }
        for (LocalFile f : files) {
            Button open = Ui.button(a, "Open", 1);
            open.setOnClickListener(v -> {
                if (host.busy()) { toast("Stop the running job first"); return; }
                host.openLocal(f.uri);
            });
            Button del = Ui.button(a, "✕", 4);
            del.setOnClickListener(v -> new AlertDialog.Builder(a)
                    .setTitle("Delete " + f.name + "?")
                    .setMessage("The file is removed from the tablet. A copy on the backoffice is kept.")
                    .setPositiveButton("Delete", (d, w) -> {
                        try {
                            if ("file".equals(f.uri.getScheme())) new File(f.uri.getPath()).delete();
                            else a.getContentResolver().delete(f.uri, null, null);
                        } catch (Exception e) {
                            toast("Could not delete: " + e.getMessage());
                        }
                        refresh();
                    })
                    .setNegativeButton("Cancel", null).show());
            localList.addView(fileRow(f.name, size(f.size) + " · " + date(f.modified), open, del));
        }
    }

    private View fileRow(String name, String meta, Button primary, Button secondary) {
        LinearLayout row = Ui.row(a);
        row.setPadding(0, Ui.dp(a, 6), 0, Ui.dp(a, 6));
        LinearLayout texts = new LinearLayout(a);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView n = Ui.text(a, name, 14, Ui.TEXT);
        n.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        texts.addView(n);
        texts.addView(Ui.text(a, meta, 12, Ui.MUTED));
        row.addView(texts, Ui.weight(1));
        row.addView(primary);
        if (secondary != null) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            p.setMarginStart(Ui.dp(a, 6));
            secondary.setMinWidth(Ui.dp(a, 44));
            row.addView(secondary, p);
        }
        LinearLayout wrap = new LinearLayout(a);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(row);
        wrap.addView(Ui.divider(a));
        return wrap;
    }

    private void message(LinearLayout list, String s) {
        list.removeAllViews();
        TextView t = Ui.text(a, s, 13, Ui.MUTED);
        t.setPadding(0, Ui.dp(a, 8), 0, Ui.dp(a, 8));
        list.addView(t);
    }

    private void toast(String s) { android.widget.Toast.makeText(a, s, android.widget.Toast.LENGTH_SHORT).show(); }

    /** Files in Documents/GrblSender that this app can see (the ones it wrote), newest first. */
    static List<LocalFile> listLocal(Activity a) {
        List<LocalFile> out = new ArrayList<>();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                Uri base = MediaStore.Files.getContentUri("external");
                String[] cols = {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED};
                try (Cursor c = a.getContentResolver().query(base, cols, MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?",
                        new String[]{Environment.DIRECTORY_DOCUMENTS + "/" + LOCAL_DIR + "%"},
                        MediaStore.MediaColumns.DATE_MODIFIED + " DESC")) {
                    while (c != null && c.moveToNext()) {
                        out.add(new LocalFile(ContentUris.withAppendedId(base, c.getLong(0)), c.getString(1),
                                c.getLong(2), c.getLong(3) * 1000));
                    }
                }
            } else {
                File dir = new File(a.getExternalFilesDir(null), LOCAL_DIR);
                File[] fs = dir.listFiles();
                if (fs != null) {
                    java.util.Arrays.sort(fs, (x, y) -> Long.compare(y.lastModified(), x.lastModified()));
                    for (File f : fs) out.add(new LocalFile(Uri.fromFile(f), f.getName(), f.length(), f.lastModified()));
                }
            }
        } catch (Exception e) {
            RemoteLog.e("files", "listing local files failed", e);
        }
        return out;
    }

    static String size(long b) {
        return b >= 1024 * 1024 ? String.format(Locale.US, "%.1f MB", b / 1048576.0)
                : String.format(Locale.US, "%.0f KB", Math.max(1, b / 1024.0));
    }

    static String date(long ms) {
        return ms <= 0 ? "" : new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(ms));
    }

    void detach() {
        remoteList = null;
        localList = null;
    }
}
