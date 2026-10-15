package com.achir.xiaokestock;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 桌面小工具：目前自選群組前 8 檔報價；按 ⟳ 更新、按 ▶ 換群組、按個股開個股頁。 */
public class QuoteWidget extends AppWidgetProvider {
    static final String ACTION_REFRESH = "com.achir.xiaokestock.WIDGET_REFRESH";
    static final String ACTION_NEXT = "com.achir.xiaokestock.WIDGET_NEXT";
    private static final int ROWS = 8;

    static void refreshAll(Context c) {
        c.sendBroadcast(new Intent(c, QuoteWidget.class).setAction(ACTION_REFRESH));
    }

    @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        update(c, goAsync());
    }

    @Override public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        if (ACTION_NEXT.equals(a)) {
            int n = Store.groups(c).size();
            Store.setCurrentGroup(c, (Store.currentGroup(c) + 1) % Math.max(1, n));
            update(c, goAsync());
        } else if (ACTION_REFRESH.equals(a)) {
            update(c, goAsync());
        } else {
            super.onReceive(c, i);
        }
    }

    private static void update(final Context c, final PendingResult pr) {
        final AppWidgetManager m = AppWidgetManager.getInstance(c);
        final int[] ids = m.getAppWidgetIds(new ComponentName(c, QuoteWidget.class));
        if (ids.length == 0) { if (pr != null) pr.finish(); return; }
        new Thread(new Runnable() {
            public void run() {
                try {
                    List<Store.Group> gs = Store.groups(c);
                    int gi = Math.min(Store.currentGroup(c), gs.size() - 1);
                    Store.Group g = gs.get(Math.max(0, gi));
                    List<String> codes = g.codes.subList(0, Math.min(ROWS, g.codes.size()));
                    Map<String, Data.Quote> qs = null;
                    try {
                        qs = Data.quotes(c, codes);
                    } catch (Exception ignored) {
                    }
                    RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget);
                    v.setTextViewText(R.id.w_title, "小柯股市 · " + g.name);
                    v.setTextViewText(R.id.w_time, qs == null ? "連線失敗"
                            : new SimpleDateFormat("HH:mm:ss", Locale.TAIWAN).format(new Date()));
                    v.removeAllViews(R.id.w_list);
                    for (String code : codes) {
                        RemoteViews r = new RemoteViews(c.getPackageName(), R.layout.widget_row);
                        Data.Quote q = qs == null ? null : qs.get(code);
                        r.setTextViewText(R.id.r_name, q == null ? code : q.name);
                        r.setTextViewText(R.id.r_code, code);
                        if (q != null) {
                            int col = Ui.color(q.change());
                            r.setTextViewText(R.id.r_price, Ui.price(q.price));
                            r.setTextColor(R.id.r_price, col);
                            r.setTextViewText(R.id.r_pct, Ui.pct(q.pct()));
                            r.setTextColor(R.id.r_pct, col);
                        } else {
                            r.setTextViewText(R.id.r_price, "--");
                            r.setTextViewText(R.id.r_pct, "");
                        }
                        Intent open = new Intent(c, StockActivity.class).putExtra("code", code)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        r.setOnClickPendingIntent(R.id.r_root, PendingIntent.getActivity(c, code.hashCode(), open,
                                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
                        v.addView(R.id.w_list, r);
                    }
                    if (codes.isEmpty()) {
                        RemoteViews r = new RemoteViews(c.getPackageName(), R.layout.widget_row);
                        r.setTextViewText(R.id.r_name, "這個群組還沒有股票");
                        v.addView(R.id.w_list, r);
                    }
                    v.setOnClickPendingIntent(R.id.w_refresh, PendingIntent.getBroadcast(c, 1,
                            new Intent(c, QuoteWidget.class).setAction(ACTION_REFRESH), PendingIntent.FLAG_IMMUTABLE));
                    v.setOnClickPendingIntent(R.id.w_next, PendingIntent.getBroadcast(c, 2,
                            new Intent(c, QuoteWidget.class).setAction(ACTION_NEXT), PendingIntent.FLAG_IMMUTABLE));
                    v.setOnClickPendingIntent(R.id.w_title, PendingIntent.getActivity(c, 3,
                            new Intent(c, MainActivity.class), PendingIntent.FLAG_IMMUTABLE));
                    m.updateAppWidget(ids, v);
                } finally {
                    if (pr != null) pr.finish();
                }
            }
        }).start();
    }
}
