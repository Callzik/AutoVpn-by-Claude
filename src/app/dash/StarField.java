package app.dash;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

import java.util.Random;

/** Quiet star background: fixed random stars, a few of them slowly twinkle. */
public class StarField extends View {
    private float[] x, y, r, a, ph;
    private boolean[] tw, tint;
    private int lastW, lastH;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tailP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random crnd = new Random();
    // background comet: flies by every few seconds
    private long cStart = -1, cNext = android.os.SystemClock.uptimeMillis() + 3000;
    private float cx0, cy0, cdx, cdy, cDist;
    private static final long C_DUR = 1400;

    public StarField(Context c) {
        super(c);
        setWillNotDraw(false);
        tailP.setStyle(Paint.Style.STROKE);
        tailP.setStrokeCap(Paint.Cap.ROUND);
        tailP.setStrokeWidth(Ui.dp(c, 3.5f));
    }

    /** Returns true while a comet is in flight (needs smooth frames). */
    private boolean drawComet(Canvas c, int w, int h, long now) {
        if (cStart < 0) {
            if (now < cNext) return false;
            cStart = now;
            cx0 = (-0.1f + crnd.nextFloat() * 0.6f) * w;
            cy0 = (0.45f + crnd.nextFloat() * 0.5f) * h;
            double ang = Math.toRadians(-38 + (crnd.nextFloat() - 0.5f) * 20); // up and to the right, like the logo
            cdx = (float) Math.cos(ang);
            cdy = (float) Math.sin(ang);
            cDist = 0.75f * Math.max(w, h);
        }
        float t = (now - cStart) / (float) C_DUR;
        if (t >= 1f) {
            cStart = -1;
            cNext = now + 7000 + crnd.nextInt(9000);
            return false;
        }
        float e = 1f - (1f - t) * (1f - t); // ease-out
        float fade = t < 0.15f ? t / 0.15f : t > 0.7f ? (1f - t) / 0.3f : 1f;
        float dp = Ui.dp(getContext(), 1);
        float hx = cx0 + cdx * cDist * e, hy = cy0 + cdy * cDist * e;
        float len = dp * 210 * (0.4f + 0.6f * fade);
        float tx = hx - cdx * len, ty = hy - cdy * len;
        int acc = Ui.ACCENT & 0x00FFFFFF;
        tailP.setShader(new android.graphics.LinearGradient(tx, ty, hx, hy,
                acc, (Math.round(0.85f * fade * 255) << 24) | acc, android.graphics.Shader.TileMode.CLAMP));
        c.drawLine(tx, ty, hx, hy, tailP);
        tailP.setShader(null);
        p.setShader(new android.graphics.RadialGradient(hx, hy, dp * 16,
                new int[]{(Math.round(0.5f * fade * 255) << 24) | acc, acc}, null, android.graphics.Shader.TileMode.CLAMP));
        c.drawCircle(hx, hy, dp * 16, p);
        p.setShader(null);
        p.setColor((Math.round(fade * 255) << 24) | 0xE6E1FF);
        c.drawCircle(hx, hy, dp * 3.6f, p);
        return true;
    }

    @Override protected void onWindowVisibilityChanged(int v) {
        super.onWindowVisibilityChanged(v);
        if (v == VISIBLE) invalidate(); // restart the twinkle loop after returning to the screen
    }

    private void build(int w, int h) {
        lastW = w; lastH = h;
        Random rnd = new Random(7);
        float dp = Ui.dp(getContext(), 1);
        int n = Math.max(40, (int) (w * h / (dp * dp) / 1800f));
        x = new float[n]; y = new float[n]; r = new float[n]; a = new float[n]; ph = new float[n];
        tw = new boolean[n]; tint = new boolean[n];
        for (int i = 0; i < n; i++) {
            x[i] = rnd.nextFloat() * w;
            y[i] = rnd.nextFloat() * h;
            float s = rnd.nextFloat();
            r[i] = dp * (0.5f + s * s * 1.1f);
            a[i] = 0.12f + rnd.nextFloat() * 0.45f;
            ph[i] = rnd.nextFloat() * 6.283f;
            tw[i] = rnd.nextFloat() < 0.25f;
            tint[i] = rnd.nextFloat() < 0.2f;
        }
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        if (x == null || w != lastW || h != lastH) build(w, h);
        double t = android.os.SystemClock.uptimeMillis() / 1000.0;
        for (int i = 0; i < x.length; i++) {
            float al = a[i];
            if (tw[i]) {
                al *= 0.45f + 0.55f * (float) (0.5 + 0.5 * Math.sin(t * 0.9 + ph[i]));
            }
            int base = tint[i] ? 0xC4BCFF : 0xFFFFFF;
            p.setColor((Math.round(al * 255) << 24) | base);
            c.drawCircle(x[i], y[i], r[i], p);
        }
        boolean flying = drawComet(c, w, h, android.os.SystemClock.uptimeMillis());
        // ~15 fps is plenty for the slow twinkle; smooth frames only while a comet flies
        if (isShown()) postInvalidateDelayed(flying ? 16 : 66);
    }
}
