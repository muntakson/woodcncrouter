package org.dnaboy.grblsender;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Top (XY) view of the toolpath in work coordinates:
 * planned feeds/rapids, the cut width at tool diameter, a tool icon at the live
 * machine position and, while running, the traced path the machine actually took.
 */
public class ToolpathView extends View {

    private GCodeParser.Result path;
    private Runnable onSingleTap;
    private final Path rapidPath = new Path();
    private float toolX, toolY, toolZ;
    private boolean toolKnown;
    private int doneLine = -1;              // program lines <= this are drawn as completed
    private boolean running;

    private static final float TRACE_MIN_STEP = 0.5f;      // mm
    private static final int TRACE_MAX_POINTS = 20000;
    private float[] trace = new float[1024];
    private int traceLen;

    // view transform: screen = (model - center) * scale, Y flipped
    private float scale = 4f, centerX, centerY;
    private boolean userMoved;

    private final Paint gridMinor = new Paint(), gridMajor = new Paint(), axisX = new Paint(), axisY = new Paint();
    private final Paint rapidPaint = new Paint(), feedPaint = new Paint(), cutPaint = new Paint(), donePaint = new Paint();
    private final Paint tracePaint = new Paint(), toolFill = new Paint(), toolEdge = new Paint(), cross = new Paint();
    private final Paint text = new Paint(), textMuted = new Paint(), iconBody = new Paint(), iconFlute = new Paint(), panel = new Paint();
    private final Matrix matrix = new Matrix();
    private final float density;

    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;

    public ToolpathView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        density = getResources().getDisplayMetrics().density;
        gridMinor.setColor(0xFF22262B);
        gridMajor.setColor(0xFF2E343B);
        axisX.setColor(0xFF8A3B3B); axisX.setStrokeWidth(2 * density);
        axisY.setColor(0xFF3B8A4A); axisY.setStrokeWidth(2 * density);

        rapidPaint.setColor(0xFF6E7681); rapidPaint.setStyle(Paint.Style.STROKE); rapidPaint.setAntiAlias(true);
        feedPaint.setColor(0xFF4EA1FF); feedPaint.setStyle(Paint.Style.STROKE); feedPaint.setAntiAlias(true);
        cutPaint.setColor(0x334EA1FF); cutPaint.setStyle(Paint.Style.STROKE); cutPaint.setStrokeCap(Paint.Cap.ROUND);
        cutPaint.setAntiAlias(true);
        donePaint.setColor(0xFF4CD07D); donePaint.setStyle(Paint.Style.STROKE); donePaint.setAntiAlias(true);
        tracePaint.setColor(0xFFFFB020); tracePaint.setStyle(Paint.Style.STROKE); tracePaint.setAntiAlias(true);
        tracePaint.setStrokeJoin(Paint.Join.ROUND); tracePaint.setStrokeCap(Paint.Cap.ROUND);

        toolFill.setColor(0x55FFB020); toolFill.setAntiAlias(true);
        toolEdge.setColor(0xFFFFB020); toolEdge.setStyle(Paint.Style.STROKE); toolEdge.setAntiAlias(true);
        toolEdge.setStrokeWidth(1.5f * density);
        cross.setColor(0xFFFFFFFF); cross.setStrokeWidth(1.2f * density); cross.setAntiAlias(true);

        text.setColor(0xFFE3E6EA); text.setTextSize(13 * density); text.setAntiAlias(true);
        textMuted.setColor(0xFF8B939C); textMuted.setTextSize(11 * density); textMuted.setAntiAlias(true);
        iconBody.setColor(0xFFB8C0C8); iconBody.setAntiAlias(true);
        iconFlute.setColor(0xFF5A6370); iconFlute.setStrokeWidth(1.5f * density); iconFlute.setAntiAlias(true);
        panel.setColor(0xCC15171A);

        scaleDetector = new ScaleGestureDetector(ctx, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                float fx = d.getFocusX(), fy = d.getFocusY();
                float mx = toModelX(fx), my = toModelY(fy);
                scale = Math.max(0.05f, Math.min(scale * d.getScaleFactor(), 400f));
                centerX = mx - (fx - getWidth() / 2f) / scale;
                centerY = my + (fy - getHeight() / 2f) / scale;
                userMoved = true;
                invalidate();
                return true;
            }
        });
        gestureDetector = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                centerX += dx / scale;
                centerY -= dy / scale;
                userMoved = true;
                invalidate();
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                fit();
                return true;
            }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                if (onSingleTap == null) return false;
                onSingleTap.run();
                return true;
            }
        });
    }

    // ---------------------------------------------------------------- API

    public void setPath(GCodeParser.Result r) {
        path = r;
        doneLine = -1;
        rapidPath.reset();
        if (r != null) {
            float lx = Float.NaN, ly = Float.NaN;
            for (int i = 0; i < r.drawRapidCount; i++) {
                float x0 = r.drawRapidPts[i * 4], y0 = r.drawRapidPts[i * 4 + 1];
                if (x0 != lx || y0 != ly) rapidPath.moveTo(x0, y0);
                lx = r.drawRapidPts[i * 4 + 2]; ly = r.drawRapidPts[i * 4 + 3];
                rapidPath.lineTo(lx, ly);
            }
        }
        if (!userMoved) fit(); else invalidate();
    }

    public GCodeParser.Result getPath() { return path; }

    /** Called on a single tap (not on drag, pinch or double-tap); null to ignore taps. */
    public void setOnSingleTap(Runnable r) { onSingleTap = r; }

    public void setTool(float x, float y, float z) {
        toolX = x; toolY = y; toolZ = z;
        toolKnown = true;
        if (running) {
            int n = traceLen;
            // keep the trace bounded for long jobs: skip points < 0.5 mm apart and thin it out past the cap
            if (n < 2 || Math.hypot(trace[n - 2] - x, trace[n - 1] - y) >= TRACE_MIN_STEP) {
                if (n / 2 >= TRACE_MAX_POINTS) {
                    int k = 0;
                    for (int i = 0; i < n; i += 4) { trace[k++] = trace[i]; trace[k++] = trace[i + 1]; }
                    traceLen = k;
                }
                if (traceLen + 2 > trace.length) trace = Arrays.copyOf(trace, trace.length * 2);
                trace[traceLen++] = x;
                trace[traceLen++] = y;
            }
        }
        invalidate();
    }

    public void setRunning(boolean run) {
        if (run && !running) traceLen = 0;
        running = run;
        invalidate();
    }

    public void clearTrace() { traceLen = 0; invalidate(); }

    public void setDoneLine(int line) {
        if (line != doneLine) { doneLine = line; invalidate(); }
    }

    public void fit() {
        userMoved = false;
        float w = Math.max(getWidth(), 1), h = Math.max(getHeight(), 1);
        RectF b = new RectF();
        if (path != null && path.hasBounds()) {
            float pad = path.toolDiameter / 2;
            b.set(path.minX - pad, path.minY - pad, path.maxX + pad, path.maxY + pad);
        } else {
            b.set(-50, -50, 50, 50);
        }
        if (toolKnown) b.union(toolX, toolY);
        b.union(0, 0);
        float bw = Math.max(b.width(), 1), bh = Math.max(b.height(), 1);
        scale = Math.min((w - 48 * density) / bw, (h - 110 * density) / bh);
        scale = Math.max(0.05f, Math.min(scale, 400f));
        centerX = b.centerX();
        centerY = b.centerY() - 20 * density / scale;   // leave room for the info panel at the top
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) { if (!userMoved) fit(); }

    @Override public boolean onTouchEvent(MotionEvent e) {
        scaleDetector.onTouchEvent(e);
        if (!scaleDetector.isInProgress()) gestureDetector.onTouchEvent(e);
        if (e.getAction() == MotionEvent.ACTION_DOWN) getParent().requestDisallowInterceptTouchEvent(true);
        return true;
    }

    private float toModelX(float sx) { return centerX + (sx - getWidth() / 2f) / scale; }
    private float toModelY(float sy) { return centerY - (sy - getHeight() / 2f) / scale; }
    private float toScreenX(float mx) { return (mx - centerX) * scale + getWidth() / 2f; }
    private float toScreenY(float my) { return -(my - centerY) * scale + getHeight() / 2f; }

    // ---------------------------------------------------------------- drawing

    @Override protected void onDraw(Canvas c) {
        c.drawColor(0xFF111316);
        drawGrid(c);

        c.save();
        matrix.reset();
        matrix.postTranslate(-centerX, -centerY);
        matrix.postScale(scale, -scale);
        matrix.postTranslate(getWidth() / 2f, getHeight() / 2f);
        c.concat(matrix);

        float px = 1f / scale;   // one screen pixel in model units
        if (path != null) {
            // cut width (true tool diameter)
            cutPaint.setStrokeWidth(path.toolDiameter);
            if (path.toolDiameter * scale >= 3) c.drawLines(path.drawFeedPts, 0, path.drawFeedCount * 4, cutPaint);

            rapidPaint.setStrokeWidth(1.2f * density * px);
            rapidPaint.setPathEffect(new DashPathEffect(new float[]{6 * density * px, 5 * density * px}, 0));
            c.drawPath(rapidPath, rapidPaint);

            feedPaint.setStrokeWidth(1.6f * density * px);
            c.drawLines(path.drawFeedPts, 0, path.drawFeedCount * 4, feedPaint);

            if (doneLine >= 0) {
                // drawFeedLine is non-decreasing, so "done" is a prefix of the de-duplicated list
                int n = GCodeParser.upperBound(path.drawFeedLine, path.drawFeedCount, doneLine);
                donePaint.setStrokeWidth(2.2f * density * px);
                c.drawLines(path.drawFeedPts, 0, n * 4, donePaint);
            }
        }
        if (traceLen >= 4) {
            tracePaint.setStrokeWidth(2.5f * density * px);
            Path tp = new Path();
            tp.moveTo(trace[0], trace[1]);
            for (int i = 2; i < traceLen; i += 2) tp.lineTo(trace[i], trace[i + 1]);
            c.drawPath(tp, tracePaint);
        }
        c.restore();

        if (toolKnown) drawTool(c);
        drawInfo(c);
    }

    private void drawGrid(Canvas c) {
        float w = getWidth(), h = getHeight();
        // pick a grid step that is ~30-80 px on screen: 1, 5, 10, 50, 100 ... mm
        float[] steps = {0.1f, 0.5f, 1, 5, 10, 50, 100, 500, 1000};
        float step = steps[steps.length - 1];
        for (float s : steps) if (s * scale >= 18 * density) { step = s; break; }
        // 0.1->0.5, 0.5->1, 1->5, 5->10, 10->50, 50->100 ...
        float major = String.valueOf(step).replace("0.", "").replace("0", "").startsWith("5") ? step * 2 : step * 5;
        float x0 = (float) Math.floor(toModelX(0) / step) * step;
        for (float x = x0; toScreenX(x) <= w; x += step) {
            float sx = toScreenX(x);
            c.drawLine(sx, 0, sx, h, isMultiple(x, major) ? gridMajor : gridMinor);
        }
        float y0 = (float) Math.floor(toModelY(h) / step) * step;
        for (float y = y0; toScreenY(y) >= 0; y += step) {
            float sy = toScreenY(y);
            c.drawLine(0, sy, w, sy, isMultiple(y, major) ? gridMajor : gridMinor);
        }
        // work origin axes
        float ox = toScreenX(0), oy = toScreenY(0);
        c.drawLine(ox, oy, ox + 40 * density, oy, axisX);
        c.drawLine(ox, oy, ox, oy - 40 * density, axisY);
        c.drawText("X", ox + 42 * density, oy + 4 * density, textMuted);
        c.drawText("Y", ox - 4 * density, oy - 44 * density, textMuted);
        // scale bar
        String label = (major >= 1 ? String.format(Locale.US, "%.0f mm", major) : String.format(Locale.US, "%.1f mm", major));
        float bar = major * scale;
        if (bar < w / 2) {
            // above the legend row so the two never overlap on a narrow panel
            float bx = w - bar - 16 * density, by = h - 32 * density;
            c.drawLine(bx, by, bx + bar, by, cross);
            c.drawText(label, bx, by - 6 * density, textMuted);
        }
    }

    private static boolean isMultiple(float v, float m) {
        float r = Math.abs(v / m - Math.round(v / m));
        return r < 1e-3;
    }

    private void drawTool(Canvas c) {
        float sx = toScreenX(toolX), sy = toScreenY(toolY);
        float d = path != null ? path.toolDiameter : GCodeParser.DEFAULT_TOOL_DIAMETER;
        float r = Math.max(d / 2 * scale, 5 * density);
        c.drawCircle(sx, sy, r, toolFill);
        c.drawCircle(sx, sy, r, toolEdge);
        float k = 9 * density;
        c.drawLine(sx - r - k, sy, sx - r + k * 0.4f, sy, cross);
        c.drawLine(sx + r - k * 0.4f, sy, sx + r + k, sy, cross);
        c.drawLine(sx, sy - r - k, sx, sy - r + k * 0.4f, cross);
        c.drawLine(sx, sy + r - k * 0.4f, sx, sy + r + k, cross);
    }

    private void drawInfo(Canvas c) {
        float pad = 10 * density;
        float iconW = 22 * density, iconH = 56 * density;
        // keep clear of the Fit / full-screen buttons in the top-right corner
        float boxW = Math.max(150 * density, Math.min(getWidth() - 2 * pad - 118 * density, 360 * density));
        float tx = pad * 2 + iconW + 12 * density;
        float maxW = pad + boxW - tx - 8 * density;

        // fields flow onto as many lines as the box width needs
        List<String> fields = new ArrayList<>();
        List<Paint> paints = new ArrayList<>();
        float d = path != null ? path.toolDiameter : GCodeParser.DEFAULT_TOOL_DIAMETER;
        boolean fromFile = path != null && path.toolFromFile;
        fields.add(String.format(Locale.US, "Tool Ø %.3f mm%s", d, fromFile ? "" : " (default)"));
        paints.add(text);
        if (path != null && path.hasBounds()) {
            long sec = Math.round(path.estSeconds);
            String est = sec >= 3600 ? String.format(Locale.US, "%dh %02dm", sec / 3600, (sec % 3600) / 60)
                    : String.format(Locale.US, "%d:%02d", sec / 60, sec % 60);
            fields.add(String.format(Locale.US, "Est. %s%s", est,
                    path.feedCapped ? String.format(Locale.US, " (F capped %.0f)", path.cappedRate) : ""));
            paints.add(text);
            fields.add(String.format(Locale.US, "%.1f × %.1f mm", path.maxX - path.minX, path.maxY - path.minY));
            paints.add(textMuted);
            fields.add(String.format(Locale.US, "Z %.2f…%.2f",
                    path.minZ == Float.MAX_VALUE ? 0 : path.minZ, path.maxZ == -Float.MAX_VALUE ? 0 : path.maxZ));
            paints.add(textMuted);
            fields.add(String.format(Locale.US, "cut %.1f m", path.feedLengthMm / 1000));
            paints.add(textMuted);
            fields.add(String.format(Locale.US, "%d lines", path.lines.size()));
            paints.add(textMuted);
        } else {
            fields.add(path == null ? "No program" : "No XY motion");
            paints.add(textMuted);
        }
        if (toolKnown) {
            fields.add(String.format(Locale.US, "Tool at X %.2f Y %.2f Z %.2f", toolX, toolY, toolZ));
            paints.add(textMuted);
        }
        float gap = 12 * density, lineH = 16 * density;
        float[] fx = new float[fields.size()];
        int[] fl = new int[fields.size()];
        float x = 0;
        int line = 0;
        for (int i = 0; i < fields.size(); i++) {
            float fw = paints.get(i).measureText(fields.get(i));
            // the tool line and the "tool at" line always start a new line
            boolean forceBreak = i == 1 || (toolKnown && i == fields.size() - 1);
            if (x > 0 && (forceBreak || x + fw > maxW)) { line++; x = 0; }
            fx[i] = x;
            fl[i] = line;
            x += fw + gap;
        }
        float boxH = Math.max(iconH, (line + 1) * lineH + 4 * density) + 2 * pad;
        c.drawRoundRect(new RectF(pad, pad, pad + boxW, pad + boxH), 8 * density, 8 * density, panel);
        drawEndMill(c, pad * 2, pad * 2, iconW, iconH);
        c.save();
        c.clipRect(tx, pad, pad + boxW - 6 * density, pad + boxH);
        float ty0 = pad * 2 + 12 * density;
        for (int i = 0; i < fields.size(); i++) c.drawText(fields.get(i), tx + fx[i], ty0 + fl[i] * lineH, paints.get(i));
        c.restore();

        // legend
        float ly = getHeight() - 14 * density, lx = pad;
        lx = legend(c, lx, ly, feedPaint.getColor(), "feed");
        lx = legend(c, lx, ly, rapidPaint.getColor(), "rapid");
        lx = legend(c, lx, ly, donePaint.getColor(), "done");
        legend(c, lx, ly, tracePaint.getColor(), "actual");
    }

    private float legend(Canvas c, float x, float y, int color, String label) {
        Paint p = new Paint(cross);
        p.setColor(color);
        p.setStrokeWidth(3 * density);
        c.drawLine(x, y - 4 * density, x + 16 * density, y - 4 * density, p);
        c.drawText(label, x + 20 * density, y, textMuted);
        return x + 26 * density + textMuted.measureText(label) + 8 * density;
    }

    /** Simple flat end-mill pictogram: shank on top, fluted cutting part below. */
    private void drawEndMill(Canvas c, float x, float y, float w, float h) {
        float shankH = h * 0.45f;
        c.drawRoundRect(new RectF(x + w * 0.1f, y, x + w * 0.9f, y + shankH), 2 * density, 2 * density, iconBody);
        float fy = y + shankH + 2 * density;
        RectF body = new RectF(x, fy, x + w, y + h);
        c.drawRect(body, iconBody);
        for (float k = -1; k < 3; k += 0.5f) {
            float yy = fy + k * w * 0.6f;
            c.save();
            c.clipRect(body);
            c.drawLine(x, yy, x + w, yy + w * 0.6f, iconFlute);
            c.restore();
        }
        Paint tip = new Paint(toolEdge);
        tip.setStrokeWidth(2 * density);
        c.drawLine(x, y + h, x + w, y + h, tip);
    }
}
