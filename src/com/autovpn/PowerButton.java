package com.autovpn;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** The big round connect button with rings, a spinning arc while connecting and a flash on connect. */
public class PowerButton extends View {
    private int state = AppState.OFF;
    private float t;          // 0..1 looping phase
    private float burst = -1; // 0..1 after connecting, -1 when idle
    private long burstStart;
    private ValueAnimator anim;

    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    public PowerButton(Context c) {
        super(c);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        setClickable(true);
        setFocusable(true);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(Ui.dp(c, 1));
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Ui.dp(c, 2));
        icon.setStyle(Paint.Style.STROKE);
        icon.setStrokeCap(Paint.Cap.ROUND);
        icon.setStrokeWidth(Ui.dp(c, 4));
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeCap(Paint.Cap.ROUND);
        arc.setStrokeWidth(Ui.dp(c, 2.5f));
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

    private void ensureAnim() {
        if (anim == null) {
            anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(1600);
            anim.setRepeatCount(ValueAnimator.INFINITE);
            anim.setInterpolator(new LinearInterpolator());
            anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(ValueAnimator a) {
                    t = (Float) a.getAnimatedValue();
                    if (burst >= 0) {
                        burst = (System.currentTimeMillis() - burstStart) / 900f;
                        if (burst > 1) burst = -1;
                    }
                    invalidate();
                }
            });
        }
        if (!anim.isRunning()) anim.start();
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
        float w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        float R = Math.min(w, h) / 2f - Ui.dp(getContext(), 4);
        float rb = 0.70f * R;

        int accent = Ui.ACCENT;
        int ringColor = Ui.LINE, btnFill = Ui.SURFACE, btnStroke = Ui.LINE2, iconColor = Ui.MUTED;
        if (state == AppState.CONNECTING) { ringColor = alpha(accent, 0.32f); btnStroke = alpha(accent, 0.5f); iconColor = accent; }
        else if (state == AppState.ON) { ringColor = alpha(accent, 0.32f); btnFill = Ui.ACCENT_TINT; btnStroke = accent; iconColor = accent; }
        else if (state == AppState.WAITING) { ringColor = alpha(Ui.WARN, 0.28f); btnFill = Ui.WARN_TINT; btnStroke = alpha(Ui.WARN, 0.5f); iconColor = Ui.WARN; }

        // rings
        if (state == AppState.CONNECTING) {
            for (int k = 0; k < 2; k++) {
                float ph = (t + k * 0.35f) % 1f;
                ring.setColor(alpha(accent, 0.45f * (1 - ph)));
                c.drawCircle(cx, cy, R * (0.80f + 0.22f * ph), ring);
            }
        } else if (state == AppState.ON) {
            float breathe = 0.55f + 0.45f * (float) Math.sin(t * Math.PI * 2);
            ring.setColor(alpha(accent, 0.14f + 0.12f * breathe));
            c.drawCircle(cx, cy, R, ring);
            ring.setColor(alpha(accent, 0.32f));
            c.drawCircle(cx, cy, 0.85f * R, ring);
        } else {
            ring.setColor(ringColor);
            c.drawCircle(cx, cy, R, ring);
            c.drawCircle(cx, cy, 0.85f * R, ring);
        }

        // flash after connecting
        if (burst >= 0) {
            ring.setColor(alpha(accent, 0.9f * (1 - burst)));
            ring.setStrokeWidth(Ui.dp(getContext(), 2));
            c.drawCircle(cx, cy, rb * (1 + 0.6f * burst), ring);
            ring.setStrokeWidth(Ui.dp(getContext(), 1));
        }

        // button
        fill.setColor(btnFill);
        if (state == AppState.ON) fill.setShadowLayer(Ui.dp(getContext(), 26), 0, 0, alpha(accent, 0.35f));
        else fill.clearShadowLayer();
        c.drawCircle(cx, cy, rb, fill);
        stroke.setColor(btnStroke);
        c.drawCircle(cx, cy, rb, stroke);

        // spinning arc
        if (state == AppState.CONNECTING || state == AppState.WAITING) {
            float ar = rb + Ui.dp(getContext(), 7);
            rect.set(cx - ar, cy - ar, cx + ar, cy + ar);
            arc.setColor(state == AppState.WAITING ? Ui.WARN : accent);
            float speed = state == AppState.WAITING ? 0.5f : 1.8f;
            c.drawArc(rect, (t * 360f * speed) % 360f, 80, false, arc);
        }

        // power icon
        icon.setColor(iconColor);
        float ri = 0.34f * rb;
        rect.set(cx - ri, cy - ri, cx + ri, cy + ri);
        c.drawArc(rect, -60, 300, false, icon);
        c.drawLine(cx, cy - ri * 1.18f, cx, cy - ri * 0.18f, icon);
    }
}
