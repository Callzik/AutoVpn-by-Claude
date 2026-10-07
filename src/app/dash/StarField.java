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

    public StarField(Context c) {
        super(c);
        setWillNotDraw(false);
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
        boolean anyTwinkle = false;
        for (int i = 0; i < x.length; i++) {
            float al = a[i];
            if (tw[i]) {
                al *= 0.45f + 0.55f * (float) (0.5 + 0.5 * Math.sin(t * 0.9 + ph[i]));
                anyTwinkle = true;
            }
            int base = tint[i] ? 0xC4BCFF : 0xFFFFFF;
            p.setColor((Math.round(al * 255) << 24) | base);
            c.drawCircle(x[i], y[i], r[i], p);
        }
        if (anyTwinkle && isShown()) postInvalidateDelayed(66); // ~15 fps is plenty for a slow twinkle
    }
}
