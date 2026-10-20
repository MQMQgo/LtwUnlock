package io.github.mqmqgo.ltwunlock;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Tiny framework-only Material You-ish helpers (no AndroidX). */
final class Ui {
    private Ui() {
    }

    static int c(Context ctx, int res) {
        return ctx.getColor(res);
    }

    static int dp(Context ctx, float v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    static LinearLayout card(Context ctx) {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = round(c(ctx, R.color.ltw_card), dp(ctx, 24));
        g.setStroke(dp(ctx, 1), c(ctx, R.color.ltw_card_stroke));
        l.setBackground(g);
        int p = dp(ctx, 20);
        l.setPadding(p, p, p, p);
        return l;
    }

    static LinearLayout.LayoutParams cardLp(Context ctx) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(ctx, 12);
        return lp;
    }

    static TextView text(Context ctx, CharSequence s, float sp, int colorRes, boolean medium) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(c(ctx, colorRes));
        t.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    static View pill(Context ctx, String s, int bgRes, int fgRes) {
        TextView t = text(ctx, s, 13, fgRes, true);
        t.setBackground(round(c(ctx, bgRes), dp(ctx, 100)));
        t.setPadding(dp(ctx, 14), dp(ctx, 6), dp(ctx, 14), dp(ctx, 6));
        t.setGravity(Gravity.CENTER);
        return t;
    }

    /** Tonal button with ripple. */
    static TextView button(Context ctx, String s, boolean filled, View.OnClickListener l) {
        TextView b = text(ctx, s, 14, filled ? R.color.ltw_on_primary : R.color.ltw_on_primary_container, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(ctx, 16), dp(ctx, 10), dp(ctx, 16), dp(ctx, 10));
        b.setMinHeight(dp(ctx, 40));
        int bg = c(ctx, filled ? R.color.ltw_primary : R.color.ltw_primary_container);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33000000 | 0xFFFFFF & c(ctx, R.color.ltw_on_bg)),
                round(bg, dp(ctx, 100)), round(0xFFFFFFFF, dp(ctx, 100))));
        b.setClickable(true);
        b.setOnClickListener(l);
        return b;
    }

    static RippleDrawable rowRipple(Context ctx) {
        return new RippleDrawable(ColorStateList.valueOf(0x22000000 | 0xFFFFFF & c(ctx, R.color.ltw_primary)),
                null, round(0xFFFFFFFF, dp(ctx, 16)));
    }
}
