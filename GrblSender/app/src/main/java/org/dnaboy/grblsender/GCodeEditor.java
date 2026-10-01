package org.dnaboy.grblsender;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.Layout;
import android.text.Spannable;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.EditText;

/**
 * Plain-text G-code editor: line-number gutter, syntax colouring and a
 * highlighted "executing" line used while a job runs.
 */
public class GCodeEditor extends EditText {

    public interface OnChange { void onTextChanged(); }

    private static final int C_COMMENT = 0xFF7D858E;
    private static final int C_G = 0xFF6CB6FF;
    private static final int C_M = 0xFFD08CFF;
    private static final int C_X = 0xFFFF8080;
    private static final int C_Y = 0xFF80E080;
    private static final int C_Z = 0xFF80C8FF;
    private static final int C_IJKR = 0xFFFFB86C;
    private static final int C_FS = 0xFFF0D060;
    private static final int C_T = 0xFF50E0D0;
    private static final int C_N = 0xFF6E7681;
    private static final int C_SYS = 0xFFFF79C6;
    private static final int FULL_HIGHLIGHT_LIMIT = 150_000;   // chars; above this only the visible region is coloured

    private final Paint gutterBg = new Paint(), gutterText = new Paint(), gutterTextActive = new Paint();
    private final Paint execLine = new Paint(), errorLine = new Paint();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final float density;
    private int execLineIdx = -1;
    private int gutterWidth;
    private OnChange onChange;
    private boolean suppressWatcher;

    private final Runnable highlighter = this::highlight;

    public GCodeEditor(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        density = getResources().getDisplayMetrics().density;
        setTypeface(Typeface.MONOSPACE);
        setTextSize(13);
        setGravity(Gravity.TOP | Gravity.START);
        setHorizontallyScrolling(true);
        setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        setTextColor(0xFFE3E6EA);
        setBackgroundColor(0xFF111316);
        setLineSpacing(0, 1.1f);

        gutterBg.setColor(0xFF1A1D21);
        gutterText.setColor(0xFF5A6370);
        gutterText.setTypeface(Typeface.MONOSPACE);
        gutterText.setTextSize(getTextSize() * 0.85f);
        gutterText.setTextAlign(Paint.Align.RIGHT);
        gutterText.setAntiAlias(true);
        gutterTextActive.set(gutterText);
        gutterTextActive.setColor(0xFFFFB020);
        execLine.setColor(0x40FFB020);
        errorLine.setColor(0x40FF6B6B);
        updateGutter();

        addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) { }
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                if (suppressWatcher) return;
                updateGutter();
                handler.removeCallbacks(highlighter);
                handler.postDelayed(highlighter, 250);
                if (onChange != null) onChange.onTextChanged();
            }
        });
    }

    public void setOnChange(OnChange l) { onChange = l; }

    /** Replace the whole document without firing the change listener. */
    public void setDocument(String text) {
        suppressWatcher = true;
        setText(text);
        suppressWatcher = false;
        execLineIdx = -1;
        updateGutter();
        highlight();
        setSelection(0);
        scrollTo(0, 0);
    }

    public void setReadOnly(boolean ro) {
        setFocusable(!ro);
        setFocusableInTouchMode(!ro);
        setCursorVisible(!ro);
        setLongClickable(!ro);
        if (ro) clearFocus();
    }

    /** Highlight the line being executed (editor line index, -1 for none) and keep it in view. */
    public void setExecLine(int line) {
        if (line == execLineIdx) return;
        execLineIdx = line;
        Layout l = getLayout();
        if (l != null && line >= 0 && line < l.getLineCount()) {
            int top = l.getLineTop(line) + getTotalPaddingTop();
            int bottom = l.getLineBottom(line) + getTotalPaddingTop();
            int h = getHeight();
            if (top < getScrollY() + h / 5 || bottom > getScrollY() + h * 4 / 5) {
                int max = Math.max(0, l.getHeight() + getTotalPaddingTop() + getTotalPaddingBottom() - h);
                scrollTo(0, Math.max(0, Math.min(max, top - h / 3)));
            }
        }
        invalidate();
    }

    private void updateGutter() {
        int lines = Math.max(1, getLineCount());
        int digits = Math.max(3, String.valueOf(lines).length());
        int w = (int) (gutterText.measureText("0") * digits + 14 * density);
        if (w != gutterWidth) {
            gutterWidth = w;
            setPadding(w + (int) (6 * density), (int) (6 * density), (int) (6 * density), (int) (6 * density));
        }
    }

    @Override protected void onScrollChanged(int l, int t, int ol, int ot) {
        super.onScrollChanged(l, t, ol, ot);
        if (length() > FULL_HIGHLIGHT_LIMIT) {
            handler.removeCallbacks(highlighter);
            handler.postDelayed(highlighter, 120);
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        Layout layout = getLayout();
        int sx = getScrollX(), sy = getScrollY();
        if (layout != null && execLineIdx >= 0 && execLineIdx < layout.getLineCount()) {
            float top = layout.getLineTop(execLineIdx) + getTotalPaddingTop();
            float bottom = layout.getLineBottom(execLineIdx) + getTotalPaddingTop();
            canvas.drawRect(sx, top, sx + getWidth(), bottom, execLine);
        }
        super.onDraw(canvas);
        // gutter is drawn on top so horizontally scrolled text slides under it
        canvas.drawRect(sx, sy, sx + gutterWidth, sy + getHeight(), gutterBg);
        if (layout == null) return;
        int first = layout.getLineForVertical(Math.max(0, sy - getTotalPaddingTop()));
        int last = layout.getLineForVertical(sy + getHeight());
        for (int i = first; i <= last && i < layout.getLineCount(); i++) {
            float base = layout.getLineBaseline(i) + getTotalPaddingTop();
            canvas.drawText(String.valueOf(i + 1), sx + gutterWidth - 7 * density, base,
                    i == execLineIdx ? gutterTextActive : gutterText);
        }
    }

    // ---------------------------------------------------------------- syntax colouring

    private void highlight() {
        Editable e = getText();
        if (e == null) return;
        int start = 0, end = e.length();
        if (end > FULL_HIGHLIGHT_LIMIT) {
            // Only colour what is on screen. Without a layout yet, try again once it exists:
            // colouring a whole large document adds ~100k spans, each forcing a re-layout.
            Layout l = getLayout();
            if (l == null) {
                handler.removeCallbacks(highlighter);
                handler.postDelayed(highlighter, 100);
                return;
            }
            int first = Math.max(0, l.getLineForVertical(getScrollY()) - 100);
            int last = Math.min(l.getLineCount() - 1, l.getLineForVertical(getScrollY() + getHeight()) + 100);
            start = l.getLineStart(first);
            end = l.getLineEnd(last);
        }
        suppressWatcher = true;
        applyHighlight(e, start, end);
        suppressWatcher = false;
    }

    /** Colour G-code tokens of {@code e[start, end)}; also used by the large-file viewer. */
    static void applyHighlight(Spannable e, int start, int end) {
        for (ForegroundColorSpan sp : e.getSpans(start, end, ForegroundColorSpan.class)) e.removeSpan(sp);
        for (StyleSpan sp : e.getSpans(start, end, StyleSpan.class)) e.removeSpan(sp);

        int i = start;
        while (i < end) {
            char c = e.charAt(i);
            if (c == '(') {
                int j = i + 1;
                while (j < end && e.charAt(j) != ')' && e.charAt(j) != '\n') j++;
                if (j < end && e.charAt(j) == ')') j++;
                span(e, i, j, C_COMMENT, true);
                i = j;
            } else if (c == ';') {
                int j = i;
                while (j < end && e.charAt(j) != '\n') j++;
                span(e, i, j, C_COMMENT, true);
                i = j;
            } else if (c == '$' && (i == 0 || e.charAt(i - 1) == '\n')) {
                int j = i;
                while (j < end && e.charAt(j) != '\n') j++;
                span(e, i, j, C_SYS, false);
                i = j;
            } else if (Character.isLetter(c)) {
                int j = i + 1;
                while (j < end && (Character.isDigit(e.charAt(j)) || e.charAt(j) == '.'
                        || e.charAt(j) == '-' || e.charAt(j) == '+' || e.charAt(j) == ' ' && j == i + 1)) j++;
                int color = colorFor(Character.toUpperCase(c));
                if (color != 0 && j > i + 1) span(e, i, j, color, false);
                i = j;
            } else {
                i++;
            }
        }
    }

    private static int colorFor(char c) {
        switch (c) {
            case 'G': return C_G;
            case 'M': return C_M;
            case 'X': return C_X;
            case 'Y': return C_Y;
            case 'Z': return C_Z;
            case 'I': case 'J': case 'K': case 'R': return C_IJKR;
            case 'F': case 'S': return C_FS;
            case 'T': return C_T;
            case 'N': return C_N;
            default: return 0;
        }
    }

    private static void span(Spannable e, int s, int t, int color, boolean italic) {
        e.setSpan(new ForegroundColorSpan(color), s, t, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (italic) e.setSpan(new StyleSpan(Typeface.ITALIC), s, t, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
}
