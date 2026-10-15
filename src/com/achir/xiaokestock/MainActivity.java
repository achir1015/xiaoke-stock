package com.achir.xiaokestock;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;

/** 主畫面：自選、大盤、排行、選股、新聞。 */
public class MainActivity extends Activity {
    private static final String[] TABS = {"自選", "大盤", "排行", "選股", "新聞"};
    private static final String[] TAB_ICONS = {"★", "📈", "🏆", "🔎", "📰"};

    private FrameLayout content;
    private final List<TextView> tabViews = new ArrayList<>();
    private int tab = 0;
    private boolean alive;
    private int generation;   // 切換分頁後，舊的背景結果就不要再畫

    // 自選
    private LinearLayout watchList;
    private Map<String, Data.Quote> lastQuotes = new HashMap<>();
    // 大盤
    private LinearLayout indexGrid;
    private ChartView taiexChart;
    // 排行
    private int rankKind = 0, rankMarket = 0;
    // 新聞
    private int newsCat = 0;

    private final Runnable tick = new Runnable() {
        public void run() {
            if (!alive) return;
            if (tab == 0) refreshWatch();
            else if (tab == 1) refreshIndexes();
            Data.MAIN.postDelayed(this, Store.refreshSec(MainActivity.this) * 1000L);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.CARD);

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.BG);

        // 標題列
        LinearLayout head = Ui.row(this);
        head.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 6));
        TextView face = Ui.text(this, "◠‿◠", 15, Ui.PINK);
        head.addView(face);
        TextView t = Ui.bold(this, " 小柯股市助手", 17, Ui.TEXT);
        t.setSingleLine(true);
        head.addView(t, Ui.weight(1));
        Button search = Ui.button(this, " 🔍 搜尋 ");
        search.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { showSearch(); } });
        head.addView(search);
        Button gear = Ui.button(this, "  ⚙  ");
        gear.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { showSettings(); } });
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-2, -2);
        glp.setMargins(Ui.dp(this, 6), 0, 0, 0);
        head.addView(gear, glp);
        root.addView(head);

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));

        // 底部分頁
        LinearLayout bar = Ui.row(this);
        bar.setBackgroundColor(Ui.CARD);
        for (int i = 0; i < TABS.length; i++) {
            final int idx = i;
            TextView tv = Ui.text(this, TAB_ICONS[i] + "\n" + TABS[i], 12, Ui.SUB);
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
            tv.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { showTab(idx); } });
            bar.addView(tv, Ui.weight(1));
            tabViews.add(tv);
        }
        root.addView(bar);
        setContentView(root);

        // 背景更新全市場清單（搜尋、上市/上櫃判斷、排行、選股都用得到）
        Data.async(new Callable<Boolean>() {
            public Boolean call() { return Market.refreshIfStale(MainActivity.this, false); }
        }, new Data.Done<Boolean>() {
            public void ok(Boolean v) {
                if ((tab == 2 || tab == 3) && v != null && v) showTab(tab);
            }
        });
        AlertJob.schedule(this);
        showTab(Store.prefs(this).getInt("tab", 0));
    }

    @Override protected void onResume() {
        super.onResume();
        alive = true;
        Data.MAIN.removeCallbacks(tick);
        Data.MAIN.post(tick);
    }

    @Override protected void onPause() {
        super.onPause();
        alive = false;
        Data.MAIN.removeCallbacks(tick);
        Speaker.stop();
    }

    private void showTab(int i) {
        tab = i;
        generation++;
        Store.prefs(this).edit().putInt("tab", i).apply();
        for (int k = 0; k < tabViews.size(); k++) tabViews.get(k).setTextColor(k == i ? Ui.ACCENT : Ui.SUB);
        content.removeAllViews();
        switch (i) {
            case 0: buildWatch(); break;
            case 1: buildMarket(); break;
            case 2: buildRank(); break;
            case 3: buildScreener(); break;
            default: buildNews(); break;
        }
    }

    private ScrollView scroll(View child) {
        ScrollView sv = new ScrollView(this);
        sv.addView(child);
        return sv;
    }

    /** 一列股票：名稱/代號、價格、漲跌、漲跌幅 */
    private LinearLayout stockRow(final String code, String name, double price, double change, double pct, String extra) {
        LinearLayout r = Ui.row(this);
        r.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
        LinearLayout nc = Ui.col(this);
        nc.addView(Ui.bold(this, name, 16, Ui.TEXT));
        nc.addView(Ui.text(this, extra == null ? code : code + "  " + extra, 12, Ui.SUB));
        r.addView(nc, Ui.weight(1.4f));
        int col = Ui.color(change);
        TextView p = Ui.bold(this, Ui.price(price), 17, col);
        p.setGravity(Gravity.END);
        r.addView(p, Ui.weight(1));
        TextView c = Ui.bold(this, Store.showPct(this) ? Ui.pct(pct) : Ui.change(change), 14, Ui.TEXT);
        c.setGravity(Gravity.CENTER);
        c.setBackground(Ui.round(change > 0 ? Ui.UP : change < 0 ? Ui.DOWN : Ui.LINE, Ui.dp(this, 6)));
        c.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));
        LinearLayout.LayoutParams lp = Ui.weight(0.9f);
        lp.setMargins(Ui.dp(this, 10), 0, 0, 0);
        r.addView(c, lp);
        r.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { StockActivity.open(MainActivity.this, code); } });
        return r;
    }

    // ═════════════ 自選 ═════════════

    private void buildWatch() {
        LinearLayout box = Ui.col(this);
        // 群組列
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = Ui.row(this);
        chips.setPadding(Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4));
        final List<Store.Group> gs = Store.groups(this);
        int cur = Math.min(Store.currentGroup(this), gs.size() - 1);
        for (int i = 0; i < gs.size(); i++) {
            final int gi = i;
            Button c = Ui.button(this, gs.get(i).name);
            Ui.selected(c, i == cur);
            c.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { Store.setCurrentGroup(MainActivity.this, gi); showTab(0); QuoteWidget.refreshAll(MainActivity.this); }
            });
            c.setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View v) { groupMenu(gi); return true; }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(Ui.dp(this, 3), 0, Ui.dp(this, 3), 0);
            chips.addView(c, lp);
        }
        Button add = Ui.button(this, "＋");
        add.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { renameGroup(-1); } });
        chips.addView(add);
        hs.addView(chips);
        box.addView(hs);

        LinearLayout tools = Ui.row(this);
        tools.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 4));
        TextView hint = Ui.text(this, "長按股票可排序/刪除，長按群組可改名", 11, Ui.SUB);
        tools.addView(hint, Ui.weight(1));
        Button say = Ui.button(this, "🤖 小柯唸自選");
        say.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { speakWatch(); } });
        tools.addView(say);
        box.addView(tools);
        box.addView(Ui.divider(this));

        watchList = Ui.col(this);
        ScrollView sv = scroll(watchList);
        box.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        content.addView(box);
        renderWatch();
        refreshWatch();
    }

    private Store.Group currentGroup() {
        List<Store.Group> gs = Store.groups(this);
        return gs.get(Math.max(0, Math.min(Store.currentGroup(this), gs.size() - 1)));
    }

    private void renderWatch() {
        if (watchList == null) return;
        watchList.removeAllViews();
        final Store.Group g = currentGroup();
        if (g.codes.isEmpty()) {
            TextView e = Ui.text(this, "\n這個群組還沒有股票。\n按右上角「🔍 搜尋」輸入代號或名稱加入。", 14, Ui.SUB);
            e.setGravity(Gravity.CENTER);
            watchList.addView(e);
            return;
        }
        for (final String code : g.codes) {
            Data.Quote q = lastQuotes.get(code);
            Market.Row mr = Market.get(this, code);
            String name = q != null ? q.name : mr != null ? mr.name : code;
            LinearLayout row = q != null
                    ? stockRow(code, name, q.price, q.change(), q.pct(), "量 " + Ui.vol(q.volume))
                    : mr != null ? stockRow(code, name, mr.close, mr.change, mr.pct(), "收盤")
                    : stockRow(code, name, Double.NaN, 0, Double.NaN, null);
            row.setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View v) { stockMenu(code); return true; }
            });
            watchList.addView(row);
            watchList.addView(Ui.divider(this));
        }
    }

    private void refreshWatch() {
        final List<String> codes = new ArrayList<>(currentGroup().codes);
        if (codes.isEmpty()) return;
        final int gen = generation;
        Data.async(new Callable<Map<String, Data.Quote>>() {
            public Map<String, Data.Quote> call() throws Exception { return Data.quotes(MainActivity.this, codes); }
        }, new Data.Done<Map<String, Data.Quote>>() {
            public void ok(Map<String, Data.Quote> m) {
                if (m == null || gen != generation || tab != 0) return;
                lastQuotes.putAll(m);
                renderWatch();
            }
        });
    }

    private void speakWatch() {
        Store.Group g = currentGroup();
        StringBuilder b = new StringBuilder("小柯報告").append(g.name).append("。");
        int n = 0;
        for (String c : g.codes) {
            Data.Quote q = lastQuotes.get(c);
            if (q == null) continue;
            b.append(Speaker.describe(q));
            if (++n >= 10) break;
        }
        if (n == 0) b.append("目前還沒有報價。");
        Speaker.say(this, b.toString());
    }

    private void stockMenu(final String code) {
        final List<Store.Group> gs = Store.groups(this);
        final int gi = Math.max(0, Math.min(Store.currentGroup(this), gs.size() - 1));
        final List<String> cs = gs.get(gi).codes;
        new AlertDialog.Builder(this)
                .setTitle(code)
                .setItems(new String[]{"置頂", "上移", "下移", "從群組刪除"}, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        int i = cs.indexOf(code);
                        if (i < 0) return;
                        if (w == 0) { cs.remove(i); cs.add(0, code); }
                        else if (w == 1 && i > 0) Collections.swap(cs, i, i - 1);
                        else if (w == 2 && i < cs.size() - 1) Collections.swap(cs, i, i + 1);
                        else if (w == 3) cs.remove(i);
                        Store.saveGroups(MainActivity.this, gs);
                        renderWatch();
                        QuoteWidget.refreshAll(MainActivity.this);
                    }
                }).show();
    }

    private void groupMenu(final int gi) {
        new AlertDialog.Builder(this)
                .setTitle(Store.groups(this).get(gi).name)
                .setItems(new String[]{"改名", "刪除群組"}, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        if (w == 0) { renameGroup(gi); return; }
                        List<Store.Group> gs = Store.groups(MainActivity.this);
                        if (gs.size() <= 1) {
                            Toast.makeText(MainActivity.this, "至少要保留一個群組", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        gs.remove(gi);
                        Store.saveGroups(MainActivity.this, gs);
                        Store.setCurrentGroup(MainActivity.this, 0);
                        showTab(0);
                    }
                }).show();
    }

    /** gi = -1 代表新增 */
    private void renameGroup(final int gi) {
        final List<Store.Group> gs = Store.groups(this);
        if (gi < 0 && gs.size() >= Store.MAX_GROUPS) {
            Toast.makeText(this, "最多 " + Store.MAX_GROUPS + " 個群組", Toast.LENGTH_SHORT).show();
            return;
        }
        final EditText e = new EditText(this);
        e.setText(gi < 0 ? "自選" + (gs.size() + 1) : gs.get(gi).name);
        e.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle(gi < 0 ? "新增群組" : "群組改名")
                .setView(e)
                .setPositiveButton("確定", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String n = e.getText().toString().trim();
                        if (n.isEmpty()) return;
                        if (gi < 0) {
                            gs.add(new Store.Group(n));
                            Store.setCurrentGroup(MainActivity.this, gs.size() - 1);
                        } else gs.get(gi).name = n;
                        Store.saveGroups(MainActivity.this, gs);
                        showTab(0);
                    }
                })
                .setNegativeButton("取消", null).show();
    }

    // ═════════════ 大盤 ═════════════

    private void buildMarket() {
        LinearLayout box = Ui.col(this);
        LinearLayout card = Ui.card(this);
        card.addView(Ui.bold(this, "加權指數 今日走勢", 15, Ui.ACCENT));
        taiexChart = new ChartView(this);
        card.addView(taiexChart, new LinearLayout.LayoutParams(-1, Ui.dp(this, 220)));
        box.addView(card);
        indexGrid = Ui.col(this);
        box.addView(indexGrid);
        TextView src = Ui.text(this, "台股指數來自證交所 MIS；國際指數、匯率、期貨來自 Yahoo Finance，可能延遲。", 11, Ui.SUB);
        src.setPadding(Ui.dp(this, 16), Ui.dp(this, 6), Ui.dp(this, 16), Ui.dp(this, 16));
        box.addView(src);
        content.addView(scroll(box));

        final int gen = generation;
        Data.async(new Callable<Object[]>() {
            public Object[] call() throws Exception {
                Data.Bars b = Data.bars("^TWII", "1d", "1m");
                if (b.size() == 0) b = Data.bars("^TWII", "5d", "5m");
                Map<String, Data.Quote> m = Data.quotes(MainActivity.this, Collections.singletonList("t00"));
                Data.Quote q = m.get("t00");
                return new Object[]{b, q == null ? b.prev : q.prev};
            }
        }, new Data.Done<Object[]>() {
            public void ok(Object[] r) {
                if (r == null || gen != generation) return;
                taiexChart.setIntraday((Data.Bars) r[0], (Double) r[1]);
            }
        });
        renderIndexes(new HashMap<String, Data.Quote>());
        refreshIndexes();
    }

    private void refreshIndexes() {
        final int gen = generation;
        Data.async(new Callable<Map<String, Data.Quote>>() {
            public Map<String, Data.Quote> call() throws Exception {
                return Data.quotes(MainActivity.this, new ArrayList<>(Data.INDEXES.keySet()));
            }
        }, new Data.Done<Map<String, Data.Quote>>() {
            public void ok(Map<String, Data.Quote> m) {
                if (m != null && gen == generation && tab == 1) renderIndexes(m);
            }
        });
    }

    private void renderIndexes(Map<String, Data.Quote> m) {
        indexGrid.removeAllViews();
        List<String> keys = new ArrayList<>(Data.INDEXES.keySet());
        for (int i = 0; i < keys.size(); i += 2) {
            LinearLayout row = Ui.row(this);
            row.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 6), 0);
            for (int j = i; j < Math.min(i + 2, keys.size()); j++) {
                final String k = keys.get(j);
                Data.Quote q = m.get(k);
                LinearLayout c = Ui.card(this);
                LinearLayout.LayoutParams lp = Ui.weight(1);
                lp.setMargins(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));
                c.addView(Ui.text(this, Data.INDEXES.get(k), 13, Ui.SUB));
                int col = q == null ? Ui.TEXT : Ui.color(q.change());
                TextView pv = Ui.bold(this, q == null ? "--" : Ui.price(q.price), 19, col);
                pv.setSingleLine(true);
                c.addView(pv);
                TextView chg = Ui.text(this, q == null ? "" : Ui.change(q.change()) + " " + Ui.pct(q.pct()), 12, col);
                chg.setSingleLine(true);
                c.addView(chg);
                c.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) { StockActivity.open(MainActivity.this, k); }
                });
                row.addView(c, lp);
            }
            indexGrid.addView(row);
        }
    }

    // ═════════════ 排行 ═════════════

    private void buildRank() {
        LinearLayout box = Ui.col(this);
        LinearLayout seg = Ui.row(this);
        seg.setPadding(Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4));
        String[] kinds = {"漲幅", "跌幅", "成交量", "成交值", "殖利率"};
        for (int i = 0; i < kinds.length; i++) {
            final int k = i;
            Button bt = Ui.button(this, kinds[i]);
            Ui.selected(bt, i == rankKind);
            bt.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { rankKind = k; showTab(2); } });
            LinearLayout.LayoutParams lp = Ui.weight(1);
            lp.setMargins(Ui.dp(this, 2), 0, Ui.dp(this, 2), 0);
            seg.addView(bt, lp);
        }
        box.addView(seg);
        LinearLayout seg2 = Ui.row(this);
        seg2.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), Ui.dp(this, 4));
        String[] mk = {"全部", "上市", "上櫃", "ETF"};
        for (int i = 0; i < mk.length; i++) {
            final int k = i;
            Button bt = Ui.button(this, mk[i]);
            Ui.selected(bt, i == rankMarket);
            bt.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { rankMarket = k; showTab(2); } });
            LinearLayout.LayoutParams lp = Ui.weight(1);
            lp.setMargins(Ui.dp(this, 2), 0, Ui.dp(this, 2), 0);
            seg2.addView(bt, lp);
        }
        box.addView(seg2);

        List<Market.Row> all = Market.rows(this);
        TextView info = Ui.text(this, all.isEmpty() ? "正在下載全市場資料…" : "資料日期 " + Market.date(this) + "（收盤後更新）· 前 100 名", 12, Ui.SUB);
        info.setPadding(Ui.dp(this, 14), 0, 0, Ui.dp(this, 4));
        box.addView(info);
        box.addView(Ui.divider(this));

        List<Market.Row> list = new ArrayList<>();
        for (Market.Row r : all) {
            if (rankMarket == 1 && !"tse".equals(r.market)) continue;
            if (rankMarket == 2 && !"otc".equals(r.market)) continue;
            if (rankMarket == 3 && !r.isEtf()) continue;
            if (rankKind <= 1 && (r.volume < 100 || Double.isNaN(r.pct()))) continue;   // 排除冷門股
            if (rankKind == 4 && (Double.isNaN(r.yield) || r.volume < 100)) continue;
            list.add(r);
        }
        Collections.sort(list, new Comparator<Market.Row>() {
            public int compare(Market.Row a, Market.Row b) {
                switch (rankKind) {
                    case 0: return Double.compare(b.pct(), a.pct());
                    case 1: return Double.compare(a.pct(), b.pct());
                    case 2: return Double.compare(b.volume, a.volume);
                    case 3: return Double.compare(b.value, a.value);
                    default: return Double.compare(b.yield, a.yield);
                }
            }
        });
        LinearLayout l = Ui.col(this);
        int n = 0;
        for (Market.Row r : list) {
            String extra = rankKind == 3 ? "值 " + Ui.vol(r.value)
                    : rankKind == 4 ? String.format(Locale.US, "殖利率 %.2f%%", r.yield)
                    : "量 " + Ui.vol(r.volume) + "張";
            l.addView(stockRow(r.code, (++n) + ". " + r.name, r.close, r.change, r.pct(), extra));
            l.addView(Ui.divider(this));
            if (n >= 100) break;
        }
        box.addView(scroll(l), new LinearLayout.LayoutParams(-1, 0, 1));
        content.addView(box);
    }

    // ═════════════ 選股 ═════════════

    private void buildScreener() {
        LinearLayout box = Ui.col(this);
        LinearLayout form = Ui.card(this);
        form.addView(Ui.bold(this, "條件選股（以最近一個交易日收盤資料）", 14, Ui.ACCENT));

        final EditText pe = num("本益比 ≤"), yld = num("殖利率 ≥ %"), pb = num("淨值比 ≤"),
                pmin = num("股價 ≥"), pmax = num("股價 ≤"), vol = num("成交量 ≥ 張"), chg = num("漲跌幅 ≥ %");
        LinearLayout r1 = Ui.row(this);
        r1.addView(pe, Ui.weight(1)); r1.addView(yld, Ui.weight(1)); r1.addView(pb, Ui.weight(1));
        LinearLayout r2 = Ui.row(this);
        r2.addView(pmin, Ui.weight(1)); r2.addView(pmax, Ui.weight(1));
        LinearLayout r3 = Ui.row(this);
        r3.addView(vol, Ui.weight(1)); r3.addView(chg, Ui.weight(1));
        final CheckBox noEtf = new CheckBox(this);
        noEtf.setText("排除 ETF");
        noEtf.setTextColor(Ui.TEXT);
        noEtf.setChecked(true);
        form.addView(r1); form.addView(r2); form.addView(r3); form.addView(noEtf);

        // 快速條件（參考原版「智慧選股」分類）
        LinearLayout presets = Ui.row(this);
        final String[][] P = {
                {"高殖利率", "15", "5", "", "", "", "500", ""},
                {"低本益比", "10", "", "1.5", "", "", "300", ""},
                {"價值股", "", "4", "1", "", "", "200", ""},
                {"強勢股", "", "", "", "", "", "1000", "5"},
                {"銅板股", "", "", "", "", "10", "500", ""},
        };
        final EditText[] fs = {pe, yld, pb, pmin, pmax, vol, chg};
        for (final String[] p : P) {
            Button b = Ui.button(this, p[0]);
            b.setTextSize(12);
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { for (int i = 0; i < fs.length; i++) fs[i].setText(p[i + 1]); }
            });
            LinearLayout.LayoutParams lp = Ui.weight(1);
            lp.setMargins(Ui.dp(this, 2), Ui.dp(this, 4), Ui.dp(this, 2), Ui.dp(this, 4));
            presets.addView(b, lp);
        }
        form.addView(presets);
        Button go = Ui.button(this, "開始選股");
        Ui.selected(go, true);
        form.addView(go);
        box.addView(form);

        final TextView count = Ui.text(this, "", 12, Ui.SUB);
        count.setPadding(Ui.dp(this, 14), 0, 0, Ui.dp(this, 4));
        box.addView(count);
        final LinearLayout results = Ui.col(this);
        box.addView(results);
        go.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                double fpe = Ui.num(pe.getText().toString()), fy = Ui.num(yld.getText().toString()),
                        fpb = Ui.num(pb.getText().toString()), fmin = Ui.num(pmin.getText().toString()),
                        fmax = Ui.num(pmax.getText().toString()), fv = Ui.num(vol.getText().toString()),
                        fc = Ui.num(chg.getText().toString());
                List<Market.Row> out = new ArrayList<>();
                for (Market.Row r : Market.rows(MainActivity.this)) {
                    if (noEtf.isChecked() && r.isEtf()) continue;
                    if (!Double.isNaN(fpe) && (Double.isNaN(r.pe) || r.pe <= 0 || r.pe > fpe)) continue;
                    if (!Double.isNaN(fy) && (Double.isNaN(r.yield) || r.yield < fy)) continue;
                    if (!Double.isNaN(fpb) && (Double.isNaN(r.pb) || r.pb > fpb)) continue;
                    if (!Double.isNaN(fmin) && r.close < fmin) continue;
                    if (!Double.isNaN(fmax) && r.close > fmax) continue;
                    if (!Double.isNaN(fv) && r.volume < fv) continue;
                    if (!Double.isNaN(fc) && (Double.isNaN(r.pct()) || r.pct() < fc)) continue;
                    out.add(r);
                }
                Collections.sort(out, new Comparator<Market.Row>() {
                    public int compare(Market.Row a, Market.Row b) { return Double.compare(b.volume, a.volume); }
                });
                results.removeAllViews();
                count.setText(Market.rows(MainActivity.this).isEmpty() ? "全市場資料還在下載，請稍候再試"
                        : "符合 " + out.size() + " 檔（依成交量排序，最多顯示 200 檔）· 資料日 " + Market.date(MainActivity.this));
                int n = 0;
                for (Market.Row r : out) {
                    String extra = "PE " + f1(r.pe) + " 殖 " + f1(r.yield) + "% PB " + f1(r.pb);
                    results.addView(stockRow(r.code, r.name, r.close, r.change, r.pct(), extra));
                    results.addView(Ui.divider(MainActivity.this));
                    if (++n >= 200) break;
                }
            }
        });
        content.addView(scroll(box));
    }

    private static String f1(double v) { return Double.isNaN(v) ? "-" : String.format(Locale.US, "%.1f", v); }

    private EditText num(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(Ui.SUB);
        e.setTextColor(Ui.TEXT);
        e.setTextSize(14);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        return e;
    }

    // ═════════════ 新聞 ═════════════

    private void buildNews() {
        LinearLayout box = Ui.col(this);
        LinearLayout seg = Ui.row(this);
        seg.setPadding(Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4));
        final String[] cats = {"tw-market", "intl-markets"};
        String[] names = {"台股新聞", "國際財經"};
        for (int i = 0; i < cats.length; i++) {
            final int k = i;
            Button bt = Ui.button(this, names[i]);
            Ui.selected(bt, i == newsCat);
            bt.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { newsCat = k; showTab(4); } });
            LinearLayout.LayoutParams lp = Ui.weight(1);
            lp.setMargins(Ui.dp(this, 2), 0, Ui.dp(this, 2), 0);
            seg.addView(bt, lp);
        }
        box.addView(seg);
        final LinearLayout list = Ui.col(this);
        list.addView(Ui.text(this, "  載入中…", 13, Ui.SUB));
        box.addView(scroll(list), new LinearLayout.LayoutParams(-1, 0, 1));
        content.addView(box);
        final int gen = generation;
        Data.async(new Callable<List<Data.News>>() {
            public List<Data.News> call() throws Exception { return Data.news(cats[newsCat]); }
        }, new Data.Done<List<Data.News>>() {
            public void ok(List<Data.News> ns) {
                if (gen != generation) return;
                list.removeAllViews();
                if (ns == null || ns.isEmpty()) {
                    list.addView(Ui.text(MainActivity.this, "  新聞載入失敗，請檢查網路", 13, Ui.SUB));
                    return;
                }
                for (final Data.News n : ns) {
                    LinearLayout r = Ui.col(MainActivity.this);
                    r.setPadding(Ui.dp(MainActivity.this, 14), Ui.dp(MainActivity.this, 10), Ui.dp(MainActivity.this, 14), Ui.dp(MainActivity.this, 10));
                    r.addView(Ui.text(MainActivity.this, n.title, 15, Ui.TEXT));
                    r.addView(Ui.text(MainActivity.this, shortDate(n.date), 11, Ui.SUB));
                    r.setOnClickListener(new View.OnClickListener() {
                        public void onClick(View v) {
                            try {
                                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(n.link)));
                            } catch (Exception ignored) {
                            }
                        }
                    });
                    list.addView(r);
                    list.addView(Ui.divider(MainActivity.this));
                }
            }
        });
    }

    private static String shortDate(String rfc) {
        if (rfc == null) return "";
        try {
            java.text.SimpleDateFormat in = new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
            java.text.SimpleDateFormat out = new java.text.SimpleDateFormat("MM/dd HH:mm", Locale.TAIWAN);
            return out.format(in.parse(rfc));
        } catch (Exception e) {
            return rfc;
        }
    }

    // ═════════════ 搜尋 ═════════════

    private void showSearch() {
        LinearLayout box = Ui.col(this);
        int p = Ui.dp(this, 16);
        box.setPadding(p, Ui.dp(this, 8), p, 0);
        final EditText e = new EditText(this);
        e.setHint("代號或名稱，例：2330、台積、0050、AAPL");
        e.setSingleLine(true);
        box.addView(e);
        final LinearLayout res = Ui.col(this);
        ScrollView sv = new ScrollView(this);
        sv.addView(res);
        box.addView(sv, new LinearLayout.LayoutParams(-1, Ui.dp(this, 360)));
        final AlertDialog dlg = new AlertDialog.Builder(this).setTitle("搜尋股票").setView(box)
                .setNegativeButton("關閉", null).create();
        e.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) {
                res.removeAllViews();
                String q = s.toString().trim();
                if (q.isEmpty()) return;
                for (final Market.Row r : Market.search(MainActivity.this, q)) {
                    res.addView(searchRow(dlg, r.code, r.name + "  " + ("tse".equals(r.market) ? "上市" : "上櫃")));
                }
                String up = q.toUpperCase(Locale.US);
                if (up.matches("[A-Z0-9.^=\\-]{1,12}")) res.addView(searchRow(dlg, up, "查詢 " + up + "（海外 / Yahoo 代號）"));
                if (Market.rows(MainActivity.this).isEmpty()) res.addView(Ui.text(MainActivity.this, "全市場清單下載中，可直接輸入完整代號", 12, 0xFF888888));
            }
        });
        dlg.show();
    }

    private View searchRow(final AlertDialog dlg, final String code, String label) {
        LinearLayout r = Ui.row(this);
        r.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
        TextView t = new TextView(this);
        t.setText(code + "  " + label);
        t.setTextSize(15);
        r.addView(t, Ui.weight(1));
        Button add = new Button(this);
        add.setText("＋自選");
        add.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                List<Store.Group> gs = Store.groups(MainActivity.this);
                Store.Group g = gs.get(Math.max(0, Math.min(Store.currentGroup(MainActivity.this), gs.size() - 1)));
                if (g.codes.contains(code)) {
                    Toast.makeText(MainActivity.this, "已在「" + g.name + "」", Toast.LENGTH_SHORT).show();
                } else if (g.codes.size() >= Store.MAX_PER_GROUP) {
                    Toast.makeText(MainActivity.this, g.name + " 已滿", Toast.LENGTH_SHORT).show();
                } else {
                    g.codes.add(code);
                    Store.saveGroups(MainActivity.this, gs);
                    Toast.makeText(MainActivity.this, "已加入「" + g.name + "」", Toast.LENGTH_SHORT).show();
                    QuoteWidget.refreshAll(MainActivity.this);
                    if (tab == 0) { renderWatch(); refreshWatch(); }
                }
            }
        });
        r.addView(add);
        r.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { dlg.dismiss(); StockActivity.open(MainActivity.this, code); }
        });
        return r;
    }

    // ═════════════ 設定 ═════════════

    private void showSettings() {
        final int[] secs = {5, 10, 30, 60};
        String[] items = {
                "自動更新：每 " + Store.refreshSec(this) + " 秒",
                "漲跌顯示：" + (Store.showPct(this) ? "漲跌幅 %" : "漲跌點數"),
                "到價提醒清單（" + Store.alerts(this).size() + "）",
                "立即更新全市場資料",
                "關於小柯股市助手",
        };
        new AlertDialog.Builder(this).setTitle("設定").setItems(items, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int w) {
                if (w == 0) {
                    int cur = Store.refreshSec(MainActivity.this), next = secs[0];
                    for (int i = 0; i < secs.length; i++) if (secs[i] == cur) next = secs[(i + 1) % secs.length];
                    Store.setRefreshSec(MainActivity.this, next);
                    showSettings();
                } else if (w == 1) {
                    Store.setShowPct(MainActivity.this, !Store.showPct(MainActivity.this));
                    showTab(tab);
                    showSettings();
                } else if (w == 2) {
                    showAlerts();
                } else if (w == 3) {
                    Toast.makeText(MainActivity.this, "下載中…", Toast.LENGTH_SHORT).show();
                    Data.async(new Callable<Boolean>() {
                        public Boolean call() { return Market.refreshIfStale(MainActivity.this, true); }
                    }, new Data.Done<Boolean>() {
                        public void ok(Boolean v) {
                            Toast.makeText(MainActivity.this, v != null && v ? "已更新：" + Market.rows(MainActivity.this).size() + " 檔，資料日 " + Market.date(MainActivity.this)
                                    : "更新失敗，請檢查網路", Toast.LENGTH_LONG).show();
                            showTab(tab);
                        }
                    });
                } else {
                    new AlertDialog.Builder(MainActivity.this).setTitle("小柯股市助手 v" + version())
                            .setMessage("功能參考三竹股市（以 REA 分析其功能架構），程式全部重新撰寫，不使用三竹的伺服器、程式碼或素材。\n\n"
                                    + "資料來源：\n• 即時報價：臺灣證券交易所 MIS\n• 全市場日資料：證交所、櫃買中心 OpenAPI\n"
                                    + "• 走勢、K 線、國際行情：Yahoo Finance\n• 新聞：Yahoo 股市 RSS\n\n"
                                    + "所有資訊僅供參考，不構成投資建議。")
                            .setPositiveButton("好", null).show();
                }
            }
        }).show();
    }

    private String version() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private void showAlerts() {
        final List<Store.Alert> all = Store.alerts(this);
        if (all.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("到價提醒")
                    .setMessage("還沒有設定。到個股頁按「🔔 到價提醒」新增。\n\n台股在平日 09:00–13:35 檢查，約每 15 分鐘一次；同一檔同一天只提醒一次。")
                    .setPositiveButton("好", null).show();
            return;
        }
        String[] items = new String[all.size()];
        boolean[] on = new boolean[all.size()];
        for (int i = 0; i < all.size(); i++) {
            Store.Alert a = all.get(i);
            StringBuilder s = new StringBuilder(a.name + " " + a.code + "：");
            if (!Double.isNaN(a.above)) s.append(" ≥").append(Ui.price(a.above));
            if (!Double.isNaN(a.below)) s.append(" ≤").append(Ui.price(a.below));
            if (!Double.isNaN(a.pctMove)) s.append(" ±").append(a.pctMove).append("%");
            items[i] = s.toString();
            on[i] = a.on;
        }
        new AlertDialog.Builder(this).setTitle("到價提醒（勾選＝啟用）")
                .setMultiChoiceItems(items, on, new DialogInterface.OnMultiChoiceClickListener() {
                    public void onClick(DialogInterface d, int w, boolean c) { all.get(w).on = c; }
                })
                .setPositiveButton("儲存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        Store.saveAlerts(MainActivity.this, all);
                        AlertJob.schedule(MainActivity.this);
                    }
                })
                .setNeutralButton("立即檢查", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        Store.saveAlerts(MainActivity.this, all);
                        Data.async(new Callable<Boolean>() {
                            public Boolean call() throws Exception { AlertJob.check(MainActivity.this); return true; }
                        }, new Data.Done<Boolean>() {
                            public void ok(Boolean v) {
                                Toast.makeText(MainActivity.this, AlertJob.twOpen() ? "已檢查完畢" : "已檢查（台股目前非交易時段，只檢查海外商品）", Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                })
                .setNegativeButton("取消", null).show();
    }
}
