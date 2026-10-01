package org.dnaboy.grblsender;

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import java.util.Locale;

/**
 * Read-only G-code viewer for large files (tens of thousands of lines).
 * Unlike an EditText it only lays out and colours the rows on screen, so a
 * 15k-line CAM file opens instantly. Highlights the executing line during a run.
 */
public class GCodeViewer extends ListView {

    private String[] lines = new String[0];
    private int execLine = -1;
    private int digits = 3;
    private final float density;

    private final BaseAdapter adapter = new BaseAdapter() {
        @Override public int getCount() { return lines.length; }
        @Override public Object getItem(int i) { return lines[i]; }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int i, View convert, ViewGroup parent) {
            TextView tv = (TextView) convert;
            if (tv == null) {
                tv = new TextView(getContext());
                tv.setTypeface(Typeface.MONOSPACE);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                tv.setTextColor(0xFFE3E6EA);
                tv.setSingleLine(true);
                tv.setHorizontallyScrolling(true);
                int p = (int) (2 * density);
                tv.setPadding(p, p, p, p);
            }
            String num = String.format(Locale.US, "%" + digits + "d  ", i + 1);
            SpannableString sp = new SpannableString(num + lines[i]);
            sp.setSpan(new ForegroundColorSpan(i == execLine ? 0xFFFFB020 : 0xFF5A6370), 0, num.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            GCodeEditor.applyHighlight(sp, num.length(), sp.length());
            tv.setText(sp);
            tv.setBackgroundColor(i == execLine ? 0x40FFB020 : 0x00000000);
            return tv;
        }
    };

    public GCodeViewer(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        density = getResources().getDisplayMetrics().density;
        setBackgroundColor(0xFF111316);
        setDivider(null);
        setFastScrollEnabled(true);
        setAdapter(adapter);
    }

    public void setText(String text) {
        lines = text.split("\n", -1);
        digits = Math.max(3, String.valueOf(lines.length).length());
        execLine = -1;
        adapter.notifyDataSetChanged();
        setSelection(0);
    }

    public int getLineCount() { return lines.length; }

    /** Highlight editor line {@code line} (-1 for none) and keep it roughly a third of the way down. */
    public void setExecLine(int line) {
        if (line == execLine) return;
        execLine = line;
        if (line >= 0) {
            int first = getFirstVisiblePosition(), last = getLastVisiblePosition();
            int span = Math.max(1, last - first);
            if (line < first + span / 5 || line > last - span / 5) setSelectionFromTop(line, getHeight() / 3);
        }
        invalidateViews();
    }
}
