package com.achir.xiaokestock;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 個股頁：即時報價、基本資料、最佳五檔、分時走勢、日/週/月 K。 */
public class StockActivity extends Activity {
    private String code;
    private Data.Quote quote;
    private String symbol;            // Yahoo 代號
    private int chartMode = 0;        // 0 走勢、1 日K、2 週K、3 月K

    private TextView title, price, change, time;
    private LinearLayout info, book;
    private ChartView chart;
    private Button[] chartBtns = new Button[4];
    private Button kdBtn, macdBtn;
    private boolean alive;

    private final Runnable tick = new Runnable() {
        public void run() {
            if (!alive) return;
            loadQuote(false);
            Data.MAIN.postDelayed(this, Store.refreshSec(StockActivity.this) * 1000L);
        }
    };

    static void open(Context c, String code) {
        c.startActivity(new Intent(c, StockActivity.class).putExtra("code", code));
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        code = getIntent().getStringExtra("code");
        if (code == null) { finish(); return; }
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        sv.setFillViewport(true);
        LinearLayout root = Ui.col(this);
        root.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 24));
        sv.addView(root);

        // 標題列
        LinearLayout top = Ui.row(this);
        top.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        Button back = Ui.button(this, "‹ 返回");
        back.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { finish(); } });
        top.addView(back);
        title = Ui.bold(this, code, 18, Ui.TEXT);
        title.setPadding(Ui.dp(this, 12), 0, 0, 0);
        top.addView(title, Ui.weight(1));
        root.addView(top);

        // 價格
        LinearLayout pr = Ui.row(this);
        pr.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), 0);
        price = Ui.bold(this, "--", 34, Ui.TEXT);
        pr.addView(price);
        LinearLayout chCol = Ui.col(this);
        chCol.setPadding(Ui.dp(this, 14), 0, 0, 0);
        change = Ui.bold(this, "", 16, Ui.TEXT);
        time = Ui.text(this, "", 12, Ui.SUB);
        chCol.addView(change);
        chCol.addView(time);
        pr.addView(chCol);
        root.addView(pr);

        // 動作按鈕
        LinearLayout acts = Ui.row(this);
        acts.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), 0);
        Button add = Ui.button(this, "＋ 加入自選");
        add.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { addToGroup(); } });
        Button alert = Ui.button(this, "🔔 到價提醒");
        alert.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { editAlert(); } });
        Button say = Ui.button(this, "🤖 小柯唸");
        say.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (quote != null) Speaker.say(StockActivity.this, Speaker.describe(quote));
            }
        });
        for (Button x : new Button[]{add, alert, say}) {
            LinearLayout.LayoutParams lp = Ui.weight(1);
            lp.setMargins(Ui.dp(this, 3), 0, Ui.dp(this, 3), 0);
            acts.addView(x, lp);
        }
        root.addView(acts);

        // 圖表切換
        LinearLayout tabs = Ui.row(this);
        tabs.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 4));
        String[] names = {"走勢", "日K", "週K", "月K"};
        for (int i = 0; i < 4; i++) {
            final int m = i;
            chartBtns[i] = Ui.button(this, names[i]);
            chartBtns[i].setOnClickListener(new View.OnClickListener() { public void onClick(View v) { setChart(m); } });
            LinearLayout.LayoutParams lp = Ui.weight(1);
            lp.setMargins(Ui.dp(this, 3), 0, Ui.dp(this, 3), 0);
            tabs.addView(chartBtns[i], lp);
        }
        kdBtn = Ui.button(this, "KD");
        macdBtn = Ui.button(this, "MACD");
        kdBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { chart.setSub(ChartView.SUB_KD); Ui.selected(kdBtn, true); Ui.selected(macdBtn, false); }
        });
        macdBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { chart.setSub(ChartView.SUB_MACD); Ui.selected(kdBtn, false); Ui.selected(macdBtn, true); }
        });
        Ui.selected(kdBtn, true);
        for (Button x : new Button[]{kdBtn, macdBtn}) {
            x.setTextSize(12);
            LinearLayout.LayoutParams lp = Ui.weight(1.25f);
            lp.setMargins(Ui.dp(this, 3), 0, Ui.dp(this, 3), 0);
            tabs.addView(x, lp);
        }
        root.addView(tabs);

        chart = new ChartView(this);
        root.addView(chart, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 380)));

        LinearLayout infoCard = Ui.card(this);
        infoCard.addView(Ui.bold(this, "行情資訊", 15, Ui.ACCENT));
        info = Ui.col(this);
        infoCard.addView(info);
        root.addView(infoCard);

        LinearLayout bookCard = Ui.card(this);
        bookCard.addView(Ui.bold(this, "最佳五檔", 15, Ui.ACCENT));
        book = Ui.col(this);
        bookCard.addView(book);
        root.addView(bookCard);

        TextView src = Ui.text(this, "資料來源：證交所 MIS、證交所/櫃買中心 OpenAPI、Yahoo Finance。僅供參考，非投資建議。", 11, Ui.SUB);
        src.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), 0);
        root.addView(src);

        setContentView(sv);
        Ui.selected(chartBtns[0], true);
        loadQuote(true);
    }

    @Override protected void onResume() {
        super.onResume();
        alive = true;
        Data.MAIN.postDelayed(tick, Store.refreshSec(this) * 1000L);
    }

    @Override protected void onPause() {
        super.onPause();
        alive = false;
        Data.MAIN.removeCallbacks(tick);
    }

    private void loadQuote(final boolean first) {
        Data.async(new java.util.concurrent.Callable<Data.Quote>() {
            public Data.Quote call() throws Exception {
                Map<String, Data.Quote> m = Data.quotes(StockActivity.this, Collections.singletonList(code));
                return m.get(code);
            }
        }, new Data.Done<Data.Quote>() {
            public void ok(Data.Quote q) {
                if (q == null) {
                    if (first) {
                        title.setText(code + "（查無報價）");
                        Market.Row r = Market.get(StockActivity.this, code);
                        if (r != null) showDaily(r);
                    }
                    return;
                }
                quote = q;
                showQuote(q);
                if (first) {
                    if ("tse".equals(q.market)) symbol = code + ".TW";
                    else if ("otc".equals(q.market)) symbol = code + ".TWO";
                    else symbol = Data.yahooSymbol(StockActivity.this, code);
                    setChart(chartMode);
                }
            }
        });
    }

    /** 沒有即時報價時（例如 MIS 連不上），用最近一天的收盤資料。 */
    private void showDaily(Market.Row r) {
        title.setText(r.name + "  " + r.code);
        price.setText(Ui.price(r.close));
        price.setTextColor(Ui.color(r.change));
        change.setText(Ui.change(r.change) + "  " + Ui.pct(r.pct()));
        change.setTextColor(Ui.color(r.change));
        time.setText("收盤 " + Market.roc(r.date));
        symbol = code + ("otc".equals(r.market) ? ".TWO" : ".TW");
        setChart(chartMode);
    }

    private void showQuote(Data.Quote q) {
        String mk = "tse".equals(q.market) ? "上市" : "otc".equals(q.market) ? "上櫃" : "idx".equals(q.market) ? "指數" : "海外";
        title.setText(q.name + "  " + (q.code.equals(q.name) ? "" : q.code) + "  " + mk);
        int col = Ui.color(q.change());
        price.setText(Ui.price(q.price));
        price.setTextColor(col);
        change.setText(Ui.change(q.change()) + "  " + Ui.pct(q.pct()));
        change.setTextColor(col);
        time.setText(q.time.isEmpty() ? "" : "更新 " + q.time);

        info.removeAllViews();
        boolean tw = "tse".equals(q.market) || "otc".equals(q.market);
        addPair("開盤", Ui.price(q.open), Ui.color(q.open - q.prev), "昨收", Ui.price(q.prev), Ui.TEXT);
        addPair("最高", Ui.price(q.high), Ui.color(q.high - q.prev), "最低", Ui.price(q.low), Ui.color(q.low - q.prev));
        double amp = q.prev > 0 ? (q.high - q.low) / q.prev * 100 : Double.NaN;
        addPair(tw ? "總量(張)" : "成交量", Ui.vol(q.volume), Ui.TEXT, "振幅",
                Double.isNaN(amp) ? "--" : String.format(Locale.US, "%.2f%%", amp), Ui.TEXT);
        if (tw) {
            addPair("漲停", Ui.price(q.limitUp), Ui.UP, "跌停", Ui.price(q.limitDown), Ui.DOWN);
            Market.Row r = Market.get(this, q.code);
            if (r != null) {
                addPair("本益比", fix(r.pe), Ui.TEXT, "殖利率", Double.isNaN(r.yield) ? "--" : fix(r.yield) + "%", Ui.TEXT);
                addPair("股價淨值比", fix(r.pb), Ui.TEXT, "資料日", Market.roc(r.date), Ui.SUB);
            }
        }

        book.removeAllViews();
        if (q.bid.length == 0 && q.ask.length == 0) {
            book.addView(Ui.text(this, tw ? "目前沒有委買委賣資料" : "海外商品不提供五檔", 13, Ui.SUB));
            return;
        }
        LinearLayout h = Ui.row(this);
        for (String s : new String[]{"委買量", "買價", "賣價", "委賣量"}) {
            TextView t = Ui.text(this, s, 12, Ui.SUB);
            t.setGravity(Gravity.CENTER);
            h.addView(t, Ui.weight(1));
        }
        book.addView(h);
        double maxV = 1;
        for (double v : q.bidVol) maxV = Math.max(maxV, v);
        for (double v : q.askVol) maxV = Math.max(maxV, v);
        for (int i = 0; i < 5; i++) {
            LinearLayout r = Ui.row(this);
            r.setPadding(0, Ui.dp(this, 3), 0, Ui.dp(this, 3));
            r.addView(cell(i < q.bidVol.length ? Ui.vol(q.bidVol[i]) : "", Ui.TEXT), Ui.weight(1));
            r.addView(cell(i < q.bid.length ? Ui.price(q.bid[i]) : "", i < q.bid.length ? Ui.color(q.bid[i] - q.prev) : Ui.TEXT), Ui.weight(1));
            r.addView(cell(i < q.ask.length ? Ui.price(q.ask[i]) : "", i < q.ask.length ? Ui.color(q.ask[i] - q.prev) : Ui.TEXT), Ui.weight(1));
            r.addView(cell(i < q.askVol.length ? Ui.vol(q.askVol[i]) : "", Ui.TEXT), Ui.weight(1));
            book.addView(r);
        }
    }

    private TextView cell(String s, int color) {
        TextView t = Ui.text(this, s, 14, color);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private static String fix(double v) {
        return Double.isNaN(v) ? "--" : String.format(Locale.US, "%.2f", v);
    }

    private void addPair(String k1, String v1, int c1, String k2, String v2, int c2) {
        LinearLayout r = Ui.row(this);
        r.setPadding(0, Ui.dp(this, 5), 0, Ui.dp(this, 5));
        r.addView(Ui.text(this, k1, 13, Ui.SUB), Ui.weight(1));
        TextView a = Ui.bold(this, v1, 14, c1);
        a.setGravity(Gravity.END);
        r.addView(a, Ui.weight(1.2f));
        View gap = new View(this);
        r.addView(gap, new LinearLayout.LayoutParams(Ui.dp(this, 20), 1));
        r.addView(Ui.text(this, k2, 13, Ui.SUB), Ui.weight(1));
        TextView b = Ui.bold(this, v2, 14, c2);
        b.setGravity(Gravity.END);
        r.addView(b, Ui.weight(1.2f));
        info.addView(r);
    }

    private void setChart(final int m) {
        chartMode = m;
        for (int i = 0; i < 4; i++) Ui.selected(chartBtns[i], i == m);
        kdBtn.setVisibility(m == 0 ? View.GONE : View.VISIBLE);
        macdBtn.setVisibility(m == 0 ? View.GONE : View.VISIBLE);
        if (symbol == null) return;
        final String[][] spec = {{"1d", "1m"}, {"2y", "1d"}, {"10y", "1wk"}, {"max", "1mo"}};
        final String sym = symbol;
        Data.async(new java.util.concurrent.Callable<Data.Bars>() {
            public Data.Bars call() throws Exception {
                Data.Bars b = Data.bars(sym, spec[m][0], spec[m][1]);
                if (m == 0 && b.size() == 0) b = Data.bars(sym, "5d", "5m");   // 休市日：顯示最近幾天
                return b;
            }
        }, new Data.Done<Data.Bars>() {
            public void ok(Data.Bars b) {
                if (chartMode != m) return;
                if (b == null) {
                    Toast.makeText(StockActivity.this, "圖表資料載入失敗", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (sym.endsWith(".TW") || sym.endsWith(".TWO")) {   // 台股成交量：股 → 張
                    for (int i = 0; i < b.size(); i++) b.v[i] /= 1000;
                }
                if (m == 0) chart.setIntraday(b, quote != null ? quote.prev : b.prev);
                else chart.setKline(b, m == 3 ? "yyyy/MM" : "yy/MM/dd");
            }
        });
    }

    private void addToGroup() {
        final List<Store.Group> gs = Store.groups(this);
        String[] names = new String[gs.size()];
        final boolean[] checked = new boolean[gs.size()];
        for (int i = 0; i < gs.size(); i++) {
            names[i] = gs.get(i).name + "（" + gs.get(i).codes.size() + "）";
            checked[i] = gs.get(i).codes.contains(code);
        }
        new AlertDialog.Builder(this)
                .setTitle("加入自選群組")
                .setMultiChoiceItems(names, checked, new android.content.DialogInterface.OnMultiChoiceClickListener() {
                    public void onClick(android.content.DialogInterface d, int w, boolean c) { checked[w] = c; }
                })
                .setPositiveButton("確定", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        for (int i = 0; i < gs.size(); i++) {
                            List<String> cs = gs.get(i).codes;
                            if (checked[i] && !cs.contains(code)) {
                                if (cs.size() >= Store.MAX_PER_GROUP) {
                                    Toast.makeText(StockActivity.this, gs.get(i).name + " 已滿 " + Store.MAX_PER_GROUP + " 檔", Toast.LENGTH_SHORT).show();
                                } else cs.add(code);
                            } else if (!checked[i]) cs.remove(code);
                        }
                        Store.saveGroups(StockActivity.this, gs);
                        QuoteWidget.refreshAll(StockActivity.this);
                        Toast.makeText(StockActivity.this, "已更新自選", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void editAlert() {
        final List<Store.Alert> all = Store.alerts(this);
        Store.Alert found = null;
        for (Store.Alert a : all) if (a.code.equals(code)) found = a;
        final Store.Alert al = found != null ? found : new Store.Alert();
        al.code = code;
        al.name = quote != null ? quote.name : code;

        LinearLayout box = Ui.col(this);
        int p = Ui.dp(this, 20);
        box.setPadding(p, Ui.dp(this, 8), p, 0);
        final EditText above = field(box, "漲到（≥ 價格）", al.above);
        final EditText below = field(box, "跌到（≤ 價格）", al.below);
        final EditText pct = field(box, "漲跌幅超過（%）", al.pctMove);
        if (quote != null) box.addView(Ui.text(this, "目前 " + Ui.price(quote.price) + "（" + Ui.pct(quote.pct()) + "）", 13, 0xFF666666));

        AlertDialog.Builder bd = new AlertDialog.Builder(this)
                .setTitle("到價提醒：" + al.name)
                .setView(box)
                .setPositiveButton("儲存", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        al.above = Ui.num(above.getText().toString());
                        al.below = Ui.num(below.getText().toString());
                        al.pctMove = Ui.num(pct.getText().toString());
                        al.on = true;
                        al.firedDay = "";
                        all.remove(al);
                        if (!Double.isNaN(al.above) || !Double.isNaN(al.below) || !Double.isNaN(al.pctMove)) all.add(al);
                        Store.saveAlerts(StockActivity.this, all);
                        AlertJob.schedule(StockActivity.this);
                        askNotifyPermission();
                        Toast.makeText(StockActivity.this, "已設定，盤中約每 15 分鐘檢查一次", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null);
        if (found != null) {
            bd.setNeutralButton("刪除", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) {
                    all.remove(al);
                    Store.saveAlerts(StockActivity.this, all);
                }
            });
        }
        bd.show();
    }

    private EditText field(LinearLayout box, String hint, double v) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        if (!Double.isNaN(v)) e.setText(String.valueOf(v));
        box.addView(e);
        return e;
    }

    private void askNotifyPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
    }
}
