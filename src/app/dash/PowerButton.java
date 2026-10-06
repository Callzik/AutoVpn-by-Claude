package app.dash;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** "Orbit" connect button: round button inside an orbit ring with a comet flying around it. */
public class PowerButton extends View {
    private int state = AppState.OFF;
    private float burst = -1; // 0..1 after connecting, -1 when idle
    private long burstStart;
    private ValueAnimator anim;
    private float angle = -90f; // comet head angle, accumulated so speed changes never jump
    private float tail = 0f;    // current tail length in degrees, eased toward target
    private long lastFrame;

    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    public PowerButton(Context c) {
        super(c);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        setClickable(true);
        setFocusable(true);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(Ui.dp(c, 2));
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Ui.dp(c, 1.5f));
        icon.setStyle(Paint.Style.STROKE);
        icon.setStrokeCap(Paint.Cap.ROUND);
        icon.setStrokeWidth(Ui.dp(c, 4.5f));
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeCap(Paint.Cap.BUTT);
        dot.setStyle(Paint.Style.FILL);
        setContentDescription("Подключить VPN");
    }

    public void setState(int s) {
        if (s == state) return;
        if (s == AppState.ON && state == AppState.CONNECTING) {
            burst = 0;
            burstStart = System.currentTimeMillis();
        }
        state = s;
        setContentDescription(s == AppState.OFF ? "Подключить VPN" : "Отключить VPN");
        ensureAnim();
        invalidate();
    }

    private float speed() {
        if (state == AppState.CONNECTING) return 320f;
        if (state == AppState.WAITING) return 110f;
        if (state == AppState.ON) return 36f;
        return 0f;
    }

    private float targetTail() {
        if (state == AppState.CONNECTING) return 110f;
        if (state == AppState.ON || state == AppState.WAITING) return 70f;
        return 0f;
    }

    private void ensureAnim() {
        if (anim == null) {
            anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(1000);
            anim.setRepeatCount(ValueAnimator.INFINITE);
            anim.setInterpolator(new LinearInterpolator());
            anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(ValueAnimator a) {
                    long now = android.os.SystemClock.uptimeMillis();
                    long dt = lastFrame == 0 ? 0 : Math.min(100, now - lastFrame);
                    lastFrame = now;
                    angle = (angle + speed() * dt / 1000f) % 360f;
                    float tt = targetTail();
                    tail += (tt - tail) * Math.min(1f, dt / 250f);
                    if (burst >= 0) {
                        burst = (System.currentTimeMillis() - burstStart) / 900f;
                        if (burst > 1) burst = -1;
                    }
                    invalidate();
                    if (state == AppState.OFF && burst < 0 && tail < 0.5f) {
                        tail = 0;
                        a.cancel(); // nothing moves when off
                        lastFrame = 0;
                    }
                }
            });
        }
        if (state == AppState.OFF && burst < 0 && tail < 0.5f) return;
        if (!anim.isRunning()) {
            lastFrame = 0;
            anim.start();
        }
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ensureAnim();
    }

    @Override protected void onDetachedFromWindow() {
        if (anim != null) anim.cancel();
        super.onDetachedFromWindow();
    }

    private static int alpha(int color, float a) {
        int al = Math.max(0, Math.min(255, Math.round(a * 255)));
        return (color & 0x00FFFFFF) | (al << 24);
    }

    @Override protected void onDraw(Canvas c) {
        Context ctx = getContext();
        float w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        float R = Math.min(w, h) / 2f - Ui.dp(ctx, 12);
        float rb = 0.62f * R;

        int accent = state == AppState.WAITING ? Ui.WARN : Ui.ACCENT;
        int btnFill = Ui.SURFACE, btnStroke = Ui.LINE2, iconColor = Ui.MUTED;
        if (state == AppState.CONNECTING) { btnStroke = alpha(accent, 0.6f); iconColor = accent; }
        else if (state == AppState.ON) { btnFill = accent; btnStroke = accent; iconColor = Ui.BG; }
        else if (state == AppState.WAITING) { btnFill = Ui.WARN_TINT; btnStroke = alpha(accent, 0.6f); iconColor = accent; }

        // orbit
        ring.setColor(Ui.LINE);
        ring.setStrokeWidth(Ui.dp(ctx, 2));
        c.drawCircle(cx, cy, R, ring);

        // flash after connecting
        if (burst >= 0) {
            ring.setColor(alpha(accent, 0.8f * (1 - burst)));
            c.drawCircle(cx, cy, rb + (R - rb) * burst, ring);
        }

        // button
        fill.setColor(btnFill);
        if (state == AppState.ON) fill.setShadowLayer(Ui.dp(ctx, 30), 0, 0, alpha(accent, 0.45f));
        else fill.clearShadowLayer();
        c.drawCircle(cx, cy, rb, fill);
        if (state != AppState.ON) {
            stroke.setColor(btnStroke);
            c.drawCircle(cx, cy, rb, stroke);
        }

        // power icon
        icon.setColor(iconColor);
        float ri = 0.30f * rb;
        rect.set(cx - ri, cy - ri, cx + ri, cy + ri);
        c.drawArc(rect, -60, 300, false, icon);
        c.drawLine(cx, cy - ri * 1.2f, cx, cy - ri * 0.2f, icon);

        // comet
        if (tail > 0.5f) {
            float k = Math.min(1f, tail / 70f); // fades in/out with the tail
            rect.set(cx - R, cy - R, cx + R, cy + R);
            int n = 14;
            float seg = tail / n;
            float maxW = Ui.dp(ctx, 6);
            for (int i = 0; i < n; i++) {
                float f = (i + 1) / (float) n; // 0 → tail end, 1 → head
                arc.setStrokeWidth(maxW * (0.25f + 0.75f * f));
                arc.setColor(alpha(accent, k * f * f * 0.95f));
                c.drawArc(rect, angle - tail + i * seg, seg + 0.3f, false, arc);
            }
            double a = Math.toRadians(angle);
            float hx = cx + R * (float) Math.cos(a), hy = cy + R * (float) Math.sin(a);
            float hr = Ui.dp(ctx, 7);
            dot.setColor(alpha(accent, 0.25f * k));
            c.drawCircle(hx, hy, hr * 2f, dot);
            dot.setColor(alpha(accent, k));
            c.drawCircle(hx, hy, hr, dot);
            dot.setColor(alpha(0xFFFFFFFF, 0.8f * k));
            c.drawCircle(hx - hr * 0.3f, hy - hr * 0.3f, hr * 0.35f, dot);
        }
    }
}
