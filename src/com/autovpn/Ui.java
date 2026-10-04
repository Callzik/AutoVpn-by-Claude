package com.autovpn;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Colours and small view factories shared by the screens. */
final class Ui {
    static final int BG = 0xFF0E1013;
    static final int SURFACE = 0xFF171A1F;
    static final int SURFACE2 = 0xFF1F232A;
    static final int LINE = 0xFF2A2F37;
    static final int LINE2 = 0xFF3A404A;
    static final int FG = 0xFFECEEF1;
    static final int MUTED = 0xFF9AA1AC;
    static final int ACCENT = 0xFF3DD68C;
    static final int ACCENT_INK = 0xFF06281A;
    static final int ACCENT_TINT = 0xFF12261C;
    static final int WARN = 0xFFF2B544;
    static final int WARN_TINT = 0xFF2A2213;
    static final int WARN_FG = 0xFFF1E3C4;
    static final int BAD = 0xFFF0716B;
    static final int BAD_TINT = 0xFF2C1716;

    static final int TONE_NEUTRAL = 0, TONE_OK = 1, TONE_WARN = 2, TONE_BAD = 3;

    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    static GradientDrawable round(Context c, int fill, float radiusDp, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, radiusDp));
        if (stroke != 0) g.setStroke(dp(c, 1), stroke);
        return g;
    }

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    static LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(round(c, SURFACE, 18, LINE));
        l.setPadding(dp(c, 16), dp(c, 12), dp(c, 16), dp(c, 12));
        return l;
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static LinearLayout.LayoutParams lp(Context c, int w, int h, float topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.topMargin = dp(c, topDp);
        return p;
    }

    static LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    static TextView badge(Context c) {
        TextView t = text(c, "", 13, MUTED, true);
        t.setPadding(dp(c, 10), dp(c, 4), dp(c, 10), dp(c, 4));
        t.setGravity(Gravity.CENTER);
        tone(c, t, TONE_NEUTRAL);
        return t;
    }

    static void tone(Context c, TextView badge, int tone) {
        int bg = SURFACE2, fg = MUTED;
        if (tone == TONE_OK) { bg = ACCENT_TINT; fg = ACCENT; }
        else if (tone == TONE_WARN) { bg = WARN_TINT; fg = WARN; }
        else if (tone == TONE_BAD) { bg = BAD_TINT; fg = BAD; }
        badge.setBackground(round(c, bg, 14, 0));
        badge.setTextColor(fg);
    }

    static TextView button(Context c, String label, boolean primary) {
        TextView b = text(c, label, primary ? 17 : 15, primary ? ACCENT_INK : FG, true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(primary ? round(c, ACCENT, 16, 0) : round(c, SURFACE, 14, LINE));
        b.setMinHeight(dp(c, primary ? 56 : 48));
        b.setPadding(dp(c, 16), 0, dp(c, 16), 0);
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(LINE);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(c, 1));
        p.topMargin = dp(c, 12);
        p.bottomMargin = dp(c, 12);
        v.setLayoutParams(p);
        return v;
    }
}
