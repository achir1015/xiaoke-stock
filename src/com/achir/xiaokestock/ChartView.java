package com.achir.xiaokestock;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 分時走勢與 K 線圖。
 * K 線：單指左右拖曳捲動、雙指縮放、長按後滑動顯示十字線；主圖 MA5/20/60，下方成交量與 KD 或 MACD。
 */
public class ChartView extends View {
    static final int INTRADAY = 0, KLINE = 1;
    static final int SUB_KD = 0, SUB_MACD = 1;

    private Data.Bars bars;
    private int mode = KLINE;
    private int sub = SUB_KD;
    private double baseline = Double.NaN;      // 分時：昨收
    private boolean timeAxis;                  // 分時：X 軸依時間（沒成交的分鐘留空），而不是依筆數
    private long t0;
    private int span;
    private String timeFmt = "MM/dd";

    private int visible = 70;                  // K 線一次顯示幾根
    private int endOffset = 0;                 // 最右邊離最新一根幾根
    private int cross = -1;                    // 十字線所在 index
    private float dragAcc;

    private double[] ma5, ma20, ma60, k, d, dif, dea, osc;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float dp;
    private final GestureDetector gd;
    private final ScaleGestureDetector sgd;

    public ChartView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        txt.setTextSize(10 * dp);
        txt.setColor(Ui.SUB);
        gd = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }

            @Override public void onLongPress(MotionEvent e) {
                cross = indexAt(e.getX());
                getParent().requestDisallowInterceptTouchEvent(true);
                invalidate();
            }

            @Override public boolean onSingleTapUp(MotionEvent e) {
                cross = cross >= 0 ? -1 : indexAt(e.getX());
                invalidate();
                return true;
            }

            @Override public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy) {
                if (cross >= 0) return false;
                if (mode != KLINE || bars == null) return false;
                getParent().requestDisallowInterceptTouchEvent(true);
                dragAcc += dx;
                float w = barWidth();
                int step = (int) (dragAcc / w);
                if (step != 0) {
                    dragAcc -= step * w;
                    endOffset = clamp(endOffset - step, 0, Math.max(0, bars.size() - visible));
                    invalidate();
                }
                return true;
            }
        });
        sgd = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector s) {
                if (mode != KLINE || bars == null) return false;
                visible = clamp(Math.round(visible / s.getScaleFactor()), 20, Math.max(20, Math.min(300, bars.size())));
                endOffset = clamp(endOffset, 0, Math.max(0, bars.size() - visible));
                invalidate();
                return true;
            }
        });
    }

    void setIntraday(Data.Bars b, double prevClose) {
        bars = b;
        mode = INTRADAY;
        baseline = prevClose;
        int n = b.size();
        timeAxis = n > 1 && b.t[n - 1] - b.t[0] < 86400;   // 只有單日資料才依時間排
        if (timeAxis) {
            t0 = b.t[0];
            span = (int) Math.max(270, (b.t[n - 1] - t0) / 60 + 1);
        }
        cross = -1;
        timeFmt = "HH:mm";
        invalidate();
    }

    void setKline(Data.Bars b, String fmt) {
        bars = b;
        mode = KLINE;
        cross = -1;
        endOffset = 0;
        timeFmt = fmt;
        int n = b.size();
        ma5 = ma(b.c, 5); ma20 = ma(b.c, 20); ma60 = ma(b.c, 60);
        // KD(9,3,3)
        k = new double[n]; d = new double[n];
        double pk = 50, pd = 50;
        for (int i = 0; i < n; i++) {
            double hi = Double.NEGATIVE_INFINITY, lo = Double.POSITIVE_INFINITY;
            for (int j = Math.max(0, i - 8); j <= i; j++) { hi = Math.max(hi, b.h[j]); lo = Math.min(lo, b.l[j]); }
            double rsv = hi > lo ? (b.c[i] - lo) / (hi - lo) * 100 : 50;
            pk = pk * 2 / 3 + rsv / 3;
            pd = pd * 2 / 3 + pk / 3;
            k[i] = pk; d[i] = pd;
        }
        // MACD(12,26,9)
        dif = new double[n]; dea = new double[n]; osc = new double[n];
        double e12 = n > 0 ? b.c[0] : 0, e26 = e12, s9 = 0;
        for (int i = 0; i < n; i++) {
            e12 = e12 + (b.c[i] - e12) * 2 / 13;
            e26 = e26 + (b.c[i] - e26) * 2 / 27;
            dif[i] = e12 - e26;
            s9 = i == 0 ? dif[i] : s9 + (dif[i] - s9) * 2 / 10;
            dea[i] = s9;
            osc[i] = dif[i] - dea[i];
        }
        invalidate();
    }

    void setSub(int s) { sub = s; invalidate(); }

    private static double[] ma(double[] c, int n) {
        double[] r = new double[c.length];
        double sum = 0;
        for (int i = 0; i < c.length; i++) {
            sum += c[i];
            if (i >= n) sum -= c[i - n];
            r[i] = i >= n - 1 ? sum / n : Double.NaN;
        }
        return r;
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    @Override public boolean onTouchEvent(MotionEvent e) {
        sgd.onTouchEvent(e);
        if (cross >= 0 && e.getAction() == MotionEvent.ACTION_MOVE && e.getPointerCount() == 1) {
            cross = indexAt(e.getX());
            invalidate();
            return true;
        }
        gd.onTouchEvent(e);
        if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) {
            getParent().requestDisallowInterceptTouchEvent(false);
        }
        return true;
    }

    // ───────── 版面 ─────────
    private float left() { return 4 * dp; }
    private float right() { return getWidth() - 52 * dp; }
    private float barWidth() {
        if (mode == INTRADAY && timeAxis) return (right() - left()) / span;
        int n = mode == KLINE ? Math.min(visible, bars.size()) : Math.max(bars.size(), 270);
        return (right() - left()) / Math.max(1, n);
    }
    private int first() { return mode == KLINE ? Math.max(0, bars.size() - endOffset - visible) : 0; }
    private int last() { return mode == KLINE ? bars.size() - endOffset - 1 : bars.size() - 1; }

    private int indexAt(float x) {
        if (bars == null || bars.size() == 0) return -1;
        float slot = (x - left()) / barWidth();
        if (mode == INTRADAY && timeAxis) {
            int best = 0;
            for (int i = 1; i < bars.size(); i++) if (Math.abs(pos(i) - slot) < Math.abs(pos(best) - slot)) best = i;
            return best;
        }
        int i = first() + (int) slot;
        return clamp(i, first(), last());
    }

    /** 第 i 根在畫面上的格位：分時依分鐘，其他依筆數 */
    private float pos(int i) {
        if (mode == INTRADAY && timeAxis) return (bars.t[i] - t0) / 60f;
        return i - first();
    }

    @Override protected void onDraw(Canvas cv) {
        cv.drawColor(Ui.BG);
        if (bars == null || bars.size() == 0) {
            txt.setColor(Ui.SUB);
            txt.setTextSize(13 * dp);
            cv.drawText("載入中或暫無資料…", 16 * dp, 30 * dp, txt);
            txt.setTextSize(10 * dp);
            return;
        }
        float h = getHeight();
        float top = 18 * dp;
        float mainBottom = mode == KLINE ? h * 0.58f : h * 0.74f;
        float volTop = mainBottom + 14 * dp;
        float volBottom = mode == KLINE ? h * 0.76f : h - 14 * dp;
        float subTop = volBottom + 14 * dp;
        float subBottom = h - 14 * dp;

        int a = first(), b = last();
        double hi = Double.NEGATIVE_INFINITY, lo = Double.POSITIVE_INFINITY, vmax = 0;
        for (int i = a; i <= b; i++) {
            hi = Math.max(hi, bars.h[i]);
            lo = Math.min(lo, bars.l[i]);
            vmax = Math.max(vmax, bars.v[i]);
            if (mode == KLINE) for (double[] m : new double[][]{ma5, ma20, ma60}) {
                if (!Double.isNaN(m[i])) { hi = Math.max(hi, m[i]); lo = Math.min(lo, m[i]); }
            }
        }
        if (mode == INTRADAY && !Double.isNaN(baseline)) {   // 以昨收為中線上下對稱
            double r = Math.max(Math.abs(hi - baseline), Math.abs(lo - baseline));
            if (r == 0) r = baseline * 0.01;
            hi = baseline + r;
            lo = baseline - r;
        }
        if (hi <= lo) { hi += 1; lo -= 1; }
        double pad = (hi - lo) * 0.05;
        hi += pad; lo -= pad;

        // 格線與價格座標
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1);
        p.setColor(Ui.LINE);
        for (int g = 0; g <= 4; g++) {
            float y = top + (mainBottom - top) * g / 4;
            cv.drawLine(left(), y, right(), y, p);
            double v = hi - (hi - lo) * g / 4;
            txt.setColor(mode == INTRADAY && !Double.isNaN(baseline) ? Ui.color(v - baseline) : Ui.SUB);
            cv.drawText(Ui.price(v), right() + 4 * dp, y + 4 * dp, txt);
        }
        cv.drawLine(left(), volBottom, right(), volBottom, p);

        float bw = barWidth();
        if (mode == INTRADAY) drawIntraday(cv, a, b, bw, top, mainBottom, hi, lo);
        else drawCandles(cv, a, b, bw, top, mainBottom, hi, lo);

        // 成交量
        for (int i = a; i <= b; i++) {
            float x = left() + pos(i) * bw;
            float y = (float) (volBottom - (volBottom - volTop) * (vmax > 0 ? bars.v[i] / vmax : 0));
            double ref = mode == KLINE ? bars.o[i] : (i > 0 ? bars.c[i - 1] : baseline);
            p.setColor(Ui.color(bars.c[i] - ref));
            p.setStyle(Paint.Style.FILL);
            cv.drawRect(x + bw * 0.15f, y, x + bw * 0.85f, volBottom, p);
        }
        txt.setColor(Ui.SUB);
        if (vmax > 0) cv.drawText("量 " + Ui.vol(vmax), right() + 4 * dp, volTop + 8 * dp, txt);
        else cv.drawText("指數無成交量資料", left(), volTop + 12 * dp, txt);

        if (mode == KLINE) drawSub(cv, a, b, bw, subTop, subBottom);

        // 日期座標
        SimpleDateFormat f = new SimpleDateFormat(timeFmt, Locale.TAIWAN);
        txt.setColor(Ui.SUB);
        cv.drawText(f.format(new Date(bars.t[a] * 1000)), left(), h - 2 * dp, txt);
        String endS = f.format(new Date(bars.t[b] * 1000));
        cv.drawText(endS, right() - txt.measureText(endS), h - 2 * dp, txt);

        // 圖例 / 十字線資訊
        int idx = cross >= 0 ? cross : b;
        drawLegend(cv, idx, f);
        if (cross >= 0) {
            float x = left() + (pos(cross) + 0.5f) * bw;
            float y = (float) (top + (mainBottom - top) * (hi - bars.c[cross]) / (hi - lo));
            p.setColor(Ui.ACCENT);
            p.setStrokeWidth(1);
            p.setStyle(Paint.Style.STROKE);
            cv.drawLine(x, top, x, subBottom, p);
            cv.drawLine(left(), y, right(), y, p);
            p.setStyle(Paint.Style.FILL);
            cv.drawRect(right(), y - 7 * dp, getWidth(), y + 7 * dp, p);
            txt.setColor(Ui.BG);
            cv.drawText(Ui.price(bars.c[cross]), right() + 4 * dp, y + 4 * dp, txt);
        }
    }

    private void drawIntraday(Canvas cv, int a, int b, float bw, float top, float bottom, double hi, double lo) {
        if (!Double.isNaN(baseline)) {
            float y = (float) (top + (bottom - top) * (hi - baseline) / (hi - lo));
            p.setColor(Ui.SUB);
            p.setStyle(Paint.Style.STROKE);
            p.setPathEffect(new android.graphics.DashPathEffect(new float[]{6 * dp, 4 * dp}, 0));
            cv.drawLine(left(), y, right(), y, p);
            p.setPathEffect(null);
        }
        Path path = new Path(), fill = new Path();
        Path avg = new Path();
        double sumPV = 0, sumV = 0;
        for (int i = a; i <= b; i++) {
            float x = left() + (pos(i) + 0.5f) * bw;
            float y = (float) (top + (bottom - top) * (hi - bars.c[i]) / (hi - lo));
            sumPV += bars.c[i] * bars.v[i];
            sumV += bars.v[i];
            double av = sumV > 0 ? sumPV / sumV : bars.c[i];
            float ya = (float) (top + (bottom - top) * (hi - av) / (hi - lo));
            if (i == a) { path.moveTo(x, y); fill.moveTo(x, bottom); avg.moveTo(x, ya); }
            else { path.lineTo(x, y); avg.lineTo(x, ya); }
            fill.lineTo(x, y);
            if (i == b) { fill.lineTo(x, bottom); fill.close(); }
        }
        int col = Ui.color(bars.c[b] - baseline);
        if (sumV <= 0) avg = new Path();   // 指數沒有成交量，均價線無意義
        p.setStyle(Paint.Style.FILL);
        p.setColor((col & 0x00FFFFFF) | 0x33000000);
        cv.drawPath(fill, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.6f * dp);
        p.setColor(col);
        cv.drawPath(path, p);
        p.setStrokeWidth(1.2f * dp);
        p.setColor(Ui.ACCENT);
        cv.drawPath(avg, p);
        p.setStrokeWidth(1);
    }

    private void drawCandles(Canvas cv, int a, int b, float bw, float top, float bottom, double hi, double lo) {
        for (int i = a; i <= b; i++) {
            float x = left() + pos(i) * bw;
            float cx = x + bw / 2;
            float yo = y(bars.o[i], top, bottom, hi, lo), yc = y(bars.c[i], top, bottom, hi, lo);
            float yh = y(bars.h[i], top, bottom, hi, lo), yl = y(bars.l[i], top, bottom, hi, lo);
            int col = bars.c[i] > bars.o[i] ? Ui.UP : bars.c[i] < bars.o[i] ? Ui.DOWN : Ui.FLAT;
            p.setColor(col);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1, dp));
            cv.drawLine(cx, yh, cx, yl, p);
            p.setStyle(Paint.Style.FILL);
            float body = Math.max(1, Math.abs(yc - yo));
            cv.drawRect(x + bw * 0.12f, Math.min(yo, yc), x + bw * 0.88f, Math.min(yo, yc) + body, p);
        }
        line(cv, ma5, a, b, bw, top, bottom, hi, lo, 0xFFFFFFFF);
        line(cv, ma20, a, b, bw, top, bottom, hi, lo, Ui.ACCENT);
        line(cv, ma60, a, b, bw, top, bottom, hi, lo, 0xFF5EA8FF);
    }

    private void drawSub(Canvas cv, int a, int b, float bw, float top, float bottom) {
        p.setColor(Ui.LINE);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1);
        cv.drawLine(left(), top, right(), top, p);
        if (sub == SUB_KD) {
            for (int g : new int[]{20, 50, 80}) {
                float y = y(g, top, bottom, 100, 0);
                cv.drawLine(left(), y, right(), y, p);
                txt.setColor(Ui.SUB);
                cv.drawText(String.valueOf(g), right() + 4 * dp, y + 4 * dp, txt);
            }
            line(cv, k, a, b, bw, top, bottom, 100, 0, Ui.ACCENT);
            line(cv, d, a, b, bw, top, bottom, 100, 0, 0xFF5EA8FF);
        } else {
            double m = 0;
            for (int i = a; i <= b; i++) m = Math.max(m, Math.max(Math.abs(osc[i]), Math.max(Math.abs(dif[i]), Math.abs(dea[i]))));
            if (m == 0) m = 1;
            float zero = y(0, top, bottom, m, -m);
            for (int i = a; i <= b; i++) {
                float x = left() + pos(i) * bw;
                p.setStyle(Paint.Style.FILL);
                p.setColor(osc[i] >= 0 ? Ui.UP : Ui.DOWN);
                float yy = y(osc[i], top, bottom, m, -m);
                cv.drawRect(x + bw * 0.2f, Math.min(zero, yy), x + bw * 0.8f, Math.max(zero, yy), p);
            }
            line(cv, dif, a, b, bw, top, bottom, m, -m, Ui.ACCENT);
            line(cv, dea, a, b, bw, top, bottom, m, -m, 0xFF5EA8FF);
        }
    }

    private float y(double v, float top, float bottom, double hi, double lo) {
        return (float) (top + (bottom - top) * (hi - v) / (hi - lo));
    }

    private void line(Canvas cv, double[] s, int a, int b, float bw, float top, float bottom, double hi, double lo, int color) {
        Path path = new Path();
        boolean started = false;
        for (int i = a; i <= b; i++) {
            if (Double.isNaN(s[i])) continue;
            float x = left() + (pos(i) + 0.5f) * bw;
            float yy = y(s[i], top, bottom, hi, lo);
            if (!started) { path.moveTo(x, yy); started = true; } else path.lineTo(x, yy);
        }
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.2f * dp);
        p.setColor(color);
        cv.drawPath(path, p);
        p.setStrokeWidth(1);
    }

    private void drawLegend(Canvas cv, int i, SimpleDateFormat f) {
        float x = left();
        float y = 12 * dp;
        String s;
        if (mode == KLINE) {
            s = f.format(new Date(bars.t[i] * 1000)) + "  開" + Ui.price(bars.o[i]) + " 高" + Ui.price(bars.h[i])
                    + " 低" + Ui.price(bars.l[i]) + " 收" + Ui.price(bars.c[i]);
            txt.setColor(Ui.TEXT);
            cv.drawText(s, x, y, txt);
            float sy = getHeight() * 0.76f + 11 * dp;
            if (sub == SUB_KD) {
                legend(cv, x, sy, new String[]{"K " + fix(k[i]), "D " + fix(d[i])}, new int[]{Ui.ACCENT, 0xFF5EA8FF});
            } else {
                legend(cv, x, sy, new String[]{"DIF " + fix(dif[i]), "MACD " + fix(dea[i]), "OSC " + fix(osc[i])},
                        new int[]{Ui.ACCENT, 0xFF5EA8FF, Ui.color(osc[i])});
            }
            legend(cv, x, getHeight() * 0.58f + 11 * dp,
                    new String[]{"MA5 " + fix(ma5[i]), "MA20 " + fix(ma20[i]), "MA60 " + fix(ma60[i])},
                    new int[]{0xFFFFFFFF, Ui.ACCENT, 0xFF5EA8FF});
        } else {
            s = f.format(new Date(bars.t[i] * 1000)) + "  成交 " + Ui.price(bars.c[i]) + "  量 " + Ui.vol(bars.v[i]);
            txt.setColor(Double.isNaN(baseline) ? Ui.TEXT : Ui.color(bars.c[i] - baseline));
            cv.drawText(s, x, y, txt);
        }
    }

    private void legend(Canvas cv, float x, float y, String[] s, int[] c) {
        for (int j = 0; j < s.length; j++) {
            txt.setColor(c[j]);
            cv.drawText(s[j], x, y, txt);
            x += txt.measureText(s[j]) + 10 * dp;
        }
    }

    private static String fix(double v) {
        return Double.isNaN(v) ? "--" : String.format(Locale.US, "%.2f", v);
    }
}
