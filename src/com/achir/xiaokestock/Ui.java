package com.achir.xiaokestock;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/** 共用的顏色、格式與小元件。台股慣例：漲紅跌綠。 */
final class Ui {
    static final int BG = Color.parseColor("#101522");
    static final int CARD = Color.parseColor("#1A2133");
    static final int LINE = Color.parseColor("#2A3247");
    static final int TEXT = Color.parseColor("#E8ECF4");
    static final int SUB = Color.parseColor("#8A93A8");
    static final int UP = Color.parseColor("#FF4D4F");
    static final int DOWN = Color.parseColor("#22C55E");
    static final int FLAT = Color.parseColor("#E8ECF4");
    static final int ACCENT = Color.parseColor("#FFB020");
    static final int PINK = Color.parseColor("#FF6F91");

    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static int color(double change) {
        return change > 0 ? UP : change < 0 ? DOWN : FLAT;
    }

    /** 依證交所 212-2 規則：股價 1000 以上不顯示小數，其他兩位。 */
    static String price(double p) {
        if (Double.isNaN(p)) return "--";
        if (Math.abs(p) >= 10000) return String.format(Locale.US, "%,.2f", p);
        if (Math.abs(p) >= 1000) return String.format(Locale.US, "%.1f", p);
        return String.format(Locale.US, "%.2f", p);
    }

    static String change(double c) {
        if (Double.isNaN(c)) return "--";
        String s = price(Math.abs(c));
        return c > 0 ? "▲" + s : c < 0 ? "▼" + s : s;
    }

    static String pct(double p) {
        if (Double.isNaN(p)) return "--";
        return String.format(Locale.US, "%+.2f%%", p);
    }

    static String vol(double v) {
        if (Double.isNaN(v)) return "--";
        if (v >= 1e8) return String.format(Locale.US, "%.2f億", v / 1e8);
        if (v >= 1e4) return String.format(Locale.US, "%.1f萬", v / 1e4);
        return String.format(Locale.US, "%.0f", v);
    }

    static double num(String s) {
        if (s == null) return Double.NaN;
        s = s.replace(",", "").replace("+", "").trim();
        if (s.isEmpty() || s.equals("-") || s.equals("--")) return Double.NaN;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    static TextView text(Context c, String s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    static TextView bold(Context c, String s, float sp, int color) {
        TextView t = text(c, s, sp, color);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    static Button button(Context c, String s) {
        Button b = new Button(c);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(TEXT);
        b.setTextSize(13);
        b.setBackground(round(LINE, dp(c, 8)));
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        int p = dp(c, 8);
        b.setPadding(dp(c, 6), p, dp(c, 6), p);
        b.setSingleLine(true);
        return b;
    }

    /** 選取中的分段按鈕 */
    static void selected(Button b, boolean on) {
        b.setBackground(round(on ? ACCENT : LINE, Ui.dp(b.getContext(), 8)));
        b.setTextColor(on ? BG : TEXT);
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static LinearLayout col(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, w);
    }

    static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(LINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));
        return v;
    }

    static LinearLayout card(Context c) {
        LinearLayout l = col(c);
        l.setBackground(round(CARD, dp(c, 12)));
        int p = dp(c, 12);
        l.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(c, 10), dp(c, 6), dp(c, 10), dp(c, 6));
        l.setLayoutParams(lp);
        return l;
    }
}
