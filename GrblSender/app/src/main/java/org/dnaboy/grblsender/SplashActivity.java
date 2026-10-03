package org.dnaboy.grblsender;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * Launch screen: a spindle traces a hexagonal dome panel on a blueprint grid, then the
 * title fades in and the app opens the main page (landscape, like the rest of the app).
 */
public class SplashActivity extends Activity {

    private static final long DURATION_MS = 1400;
    private boolean launched;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!isTaskRoot() && getIntent().hasCategory(Intent.CATEGORY_LAUNCHER)) {
            finish();          // the app is already running: the launcher just brings it forward
            return;
        }
        SplashView v = new SplashView(this);
        setContentView(v);
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(DURATION_MS);
        a.setInterpolator(new DecelerateInterpolator(1.2f));
        a.addUpdateListener(an -> v.setT((float) an.getAnimatedValue()));
        a.start();
        v.postDelayed(this::launch, DURATION_MS + 250);
        v.setOnClickListener(x -> launch());
    }

    private void launch() {
        if (launched || isFinishing()) return;
        launched = true;
        startActivity(new Intent(this, MainActivity.class));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    private static final class SplashView extends View {
        private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint hex = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint trail = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint title = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint sub = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path outline = new Path(), inner = new Path(), seg = new Path();
        private final float dp;
        private float t;

        SplashView(Context c) {
            super(c);
            dp = c.getResources().getDisplayMetrics().density;
            setBackgroundColor(Ui.BG);
            grid.setColor(0xFF132030);
            grid.setStrokeWidth(dp);
            hex.setStyle(Paint.Style.STROKE);
            hex.setStrokeWidth(1.5f * dp);
            hex.setColor(0x3300E5FF);
            trail.setStyle(Paint.Style.STROKE);
            trail.setStrokeWidth(3f * dp);
            trail.setStrokeCap(Paint.Cap.ROUND);
            trail.setStrokeJoin(Paint.Join.ROUND);
            trail.setColor(Ui.ACCENT);
            glow.set(trail);
            glow.setStrokeWidth(9f * dp);
            glow.setAlpha(70);
            glow.setMaskFilter(new BlurMaskFilter(8 * dp, BlurMaskFilter.Blur.NORMAL));
            dot.setColor(0xFFFFFFFF);
            title.setColor(Ui.TEXT);
            title.setTextAlign(Paint.Align.CENTER);
            title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            title.setLetterSpacing(0.35f);
            sub.setColor(Ui.MUTED);
            sub.setTextAlign(Paint.Align.CENTER);
            sub.setTypeface(Typeface.MONOSPACE);
            sub.setLetterSpacing(0.2f);
            setLayerType(LAYER_TYPE_SOFTWARE, null);   // BlurMaskFilter
        }

        void setT(float v) { t = v; invalidate(); }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            float step = 28 * dp;
            float shift = t * step;
            for (float x = -step + shift % step; x < w; x += step) c.drawLine(x, 0, x, h, grid);
            for (float y = 0; y < h; y += step) c.drawLine(0, y, w, y, grid);

            float cx = w / 2f, cy = h * 0.40f, r = Math.min(w, h) * 0.20f;
            outline.reset();
            inner.reset();
            for (int i = 0; i <= 6; i++) {
                double a = Math.PI / 2 + i * Math.PI / 3;
                float x = (float) (cx + r * Math.cos(a)), y = (float) (cy - r * Math.sin(a));
                float xi = (float) (cx + 0.62f * r * Math.cos(a)), yi = (float) (cy - 0.62f * r * Math.sin(a));
                if (i == 0) { outline.moveTo(x, y); inner.moveTo(xi, yi); } else { outline.lineTo(x, y); inner.lineTo(xi, yi); }
            }
            c.drawPath(outline, hex);

            // the spindle cuts the inner contour, then the outer one
            float cut = Math.min(1f, t / 0.8f);
            float[] pos = new float[2];
            PathMeasure pmIn = new PathMeasure(inner, false), pmOut = new PathMeasure(outline, false);
            float lin = pmIn.getLength(), lout = pmOut.getLength(), total = lin + lout, d = cut * total;
            seg.reset();
            pmIn.getSegment(0, Math.min(d, lin), seg, true);
            if (d > lin) pmOut.getSegment(0, d - lin, seg, true);
            c.drawPath(seg, glow);
            c.drawPath(seg, trail);
            if (d <= lin) pmIn.getPosTan(d, pos, null); else pmOut.getPosTan(d - lin, pos, null);
            if (cut < 1f) {
                dot.setAlpha(255);
                c.drawCircle(pos[0], pos[1], 5 * dp, dot);
                glow.setStyle(Paint.Style.FILL);
                c.drawCircle(pos[0], pos[1], 12 * dp, glow);
                glow.setStyle(Paint.Style.STROKE);
            }

            float a = Math.max(0f, Math.min(1f, (t - 0.45f) / 0.4f));
            title.setTextSize(34 * dp);
            title.setAlpha((int) (255 * a));
            float ty = cy + r + 70 * dp;
            c.drawText("GRBL SENDER", cx, ty, title);
            sub.setTextSize(12 * dp);
            sub.setAlpha((int) (255 * a));
            c.drawText("CNC CONTROL  ·  v" + BuildConfig.VERSION_NAME, cx, ty + 28 * dp, sub);

            float bw = Math.min(w * 0.4f, 320 * dp), by = ty + 54 * dp;
            bar.setShader(null);
            bar.setColor(0xFF1A2633);
            c.drawRoundRect(cx - bw / 2, by, cx + bw / 2, by + 3 * dp, 2 * dp, 2 * dp, bar);
            bar.setShader(new LinearGradient(cx - bw / 2, 0, cx + bw / 2, 0, Ui.ACCENT, Ui.OK, Shader.TileMode.CLAMP));
            c.drawRoundRect(cx - bw / 2, by, cx - bw / 2 + bw * t, by + 3 * dp, 2 * dp, 2 * dp, bar);
        }
    }
}
