package com.dsh.sensorkit;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Material Design 3 深色配色与通用控件。 */
public final class Md3 {

    public static final int BG = Color.parseColor("#141218");
    public static final int SURFACE = Color.parseColor("#1D1B20");
    public static final int SURFACE_HIGH = Color.parseColor("#2B2831");
    public static final int PRIMARY = Color.parseColor("#D0BCFF");
    public static final int ON_PRIMARY = Color.parseColor("#381E72");
    public static final int PRIMARY_CONTAINER = Color.parseColor("#4F378B");
    public static final int ON_PRIMARY_CONTAINER = Color.parseColor("#EADDFF");
    public static final int SECONDARY_CONTAINER = Color.parseColor("#4A4458");
    public static final int TERTIARY = Color.parseColor("#EFB8C8");
    public static final int ON_SURFACE = Color.parseColor("#E6E0E9");
    public static final int ON_SURFACE_VARIANT = Color.parseColor("#CAC4D0");
    public static final int OUTLINE = Color.parseColor("#938F99");
    public static final int OUTLINE_VARIANT = Color.parseColor("#49454F");
    public static final int ERROR = Color.parseColor("#F2B8B5");
    public static final int OK = Color.parseColor("#8CE99A");

    public static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }

    public static GradientDrawable rounded(Context c, int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    public static GradientDrawable outlined(Context c, int color, int stroke, float radiusDp) {
        GradientDrawable g = rounded(c, color, radiusDp);
        g.setStroke(dp(c, 1), stroke);
        return g;
    }

    public static TextView text(Context c, String s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    public static TextView title(Context c, String s) {
        TextView t = text(c, s, 16, ON_SURFACE);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    public static TextView label(Context c, String s) {
        return text(c, s, 12, ON_SURFACE_VARIANT);
    }

    public static TextView numeric(Context c, String s, float sp) {
        TextView t = text(c, s, sp, ON_SURFACE);
        t.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        return t;
    }

    /** MD3 卡片：16dp 圆角、surface 底色、内边距 16dp。 */
    public static LinearLayout card(Context c) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(c, SURFACE, 16));
        int p = dp(c, 16);
        card.setPadding(p, dp(c, 14), p, dp(c, 14));
        return card;
    }

    public static LinearLayout.LayoutParams cardLp(Context c) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(c, 12);
        return lp;
    }

    public static View spacer(Context c, float h) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(c, h)));
        return v;
    }

    public static LinearLayout row(Context c) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    /** MD3 填充按钮。 */
    public static TextView button(Context c, String label, int bg, int fg) {
        TextView b = text(c, label, 14, fg);
        b.setGravity(Gravity.CENTER);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setBackground(rounded(c, bg, 20));
        b.setPadding(dp(c, 20), dp(c, 10), dp(c, 20), dp(c, 10));
        return b;
    }

    public static TextView chip(Context c, String label, boolean selected) {
        TextView t = text(c, label, 12, selected ? ON_PRIMARY_CONTAINER : ON_SURFACE_VARIANT);
        t.setGravity(Gravity.CENTER);
        t.setBackground(selected
                ? rounded(c, PRIMARY_CONTAINER, 8)
                : outlined(c, Color.TRANSPARENT, OUTLINE_VARIANT, 8));
        t.setPadding(dp(c, 12), dp(c, 6), dp(c, 12), dp(c, 6));
        return t;
    }

    private Md3() {
    }
}
