package com.achir.xiaokestock;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 全市場日資料（上市＋上櫃）：股票清單、收盤、漲跌、成交量值、本益比、殖利率、淨值比。
 * 來源是證交所與櫃買中心 OpenAPI，每天收盤後更新一次；快取在 files/market.json。
 */
final class Market {
    private Market() {}

    static final class Row {
        String code, name, market;
        double close, change, open, high, low, volume, value, pe = Double.NaN, yield = Double.NaN, pb = Double.NaN;
        String date;

        double pct() {
            double prev = close - change;
            return prev > 0 ? change / prev * 100 : Double.NaN;
        }

        boolean isEtf() { return code.startsWith("00"); }
    }

    /** 只收股票與 ETF：4 碼（含特別股 2881A）或 00 開頭的 ETF；權證、牛熊證不收。 */
    private static final java.util.regex.Pattern STOCK = java.util.regex.Pattern.compile("^(\\d{4}[A-Z]?|00\\d{2,4}[A-Z]?)$");

    private static List<Row> rows;
    private static Map<String, Row> byCode = new HashMap<>();
    private static long loadedAt;

    private static File file(Context c) { return new File(c.getFilesDir(), "market_v2.json"); }

    static synchronized List<Row> rows(Context c) {
        if (rows == null) load(c);
        return rows == null ? Collections.<Row>emptyList() : rows;
    }

    static synchronized Row get(Context c, String code) {
        if (rows == null) load(c);
        return byCode.get(code);
    }

    static String marketOf(Context c, String code) {
        Row r = get(c, code);
        return r == null ? null : r.market;
    }

    /** 資料日期，民國 1151014 → 115/10/14；上市與上櫃日期不同時兩個都列。 */
    static synchronized String date(Context c) {
        String tse = "", otc = "";
        for (Row r : rows(c)) {
            if (tse.isEmpty() && "tse".equals(r.market)) tse = r.date;
            if (otc.isEmpty() && "otc".equals(r.market)) otc = r.date;
            if (!tse.isEmpty() && !otc.isEmpty()) break;
        }
        if (tse.equals(otc) || otc.isEmpty()) return roc(tse);
        if (tse.isEmpty()) return roc(otc);
        return "上市 " + roc(tse) + "、上櫃 " + roc(otc);
    }

    static String roc(String d) {
        if (d == null || d.length() < 7) return d == null ? "" : d;
        int n = d.length();
        return d.substring(0, n - 4) + "/" + d.substring(n - 4, n - 2) + "/" + d.substring(n - 2);
    }

    /** 快取超過 6 小時就重抓（在背景執行緒呼叫）。 */
    static boolean refreshIfStale(Context c, boolean force) {
        File f = file(c);
        if (!force && f.exists() && System.currentTimeMillis() - f.lastModified() < 6 * 3600_000L) {
            rows(c);
            return true;
        }
        try {
            List<Row> list = new ArrayList<>();
            Map<String, Row> map = new HashMap<>();
            // 上市
            JSONArray a = new JSONArray(Data.get("https://openapi.twse.com.tw/v1/exchangeReport/STOCK_DAY_ALL"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Row r = new Row();
                r.market = "tse";
                r.code = o.optString("Code").trim();
                r.name = o.optString("Name").trim();
                r.date = o.optString("Date");
                r.close = Ui.num(o.optString("ClosingPrice"));
                r.change = Ui.num(o.optString("Change"));
                r.open = Ui.num(o.optString("OpeningPrice"));
                r.high = Ui.num(o.optString("HighestPrice"));
                r.low = Ui.num(o.optString("LowestPrice"));
                r.volume = Ui.num(o.optString("TradeVolume")) / 1000;   // 股 → 張
                r.value = Ui.num(o.optString("TradeValue"));
                if (!STOCK.matcher(r.code).matches() || Double.isNaN(r.close)) continue;
                if (Double.isNaN(r.change)) r.change = 0;
                list.add(r);
                map.put(r.code, r);
            }
            // 上櫃
            JSONArray b = new JSONArray(Data.get("https://www.tpex.org.tw/openapi/v1/tpex_mainboard_daily_close_quotes"));
            for (int i = 0; i < b.length(); i++) {
                JSONObject o = b.getJSONObject(i);
                Row r = new Row();
                r.market = "otc";
                r.code = o.optString("SecuritiesCompanyCode").trim();
                r.name = o.optString("CompanyName").trim();
                r.date = o.optString("Date");
                r.close = Ui.num(o.optString("Close"));
                r.change = Ui.num(o.optString("Change"));
                r.open = Ui.num(o.optString("Open"));
                r.high = Ui.num(o.optString("High"));
                r.low = Ui.num(o.optString("Low"));
                r.volume = Ui.num(o.optString("TradingShares")) / 1000;
                r.value = Ui.num(o.optString("TransactionAmount"));
                if (!STOCK.matcher(r.code).matches() || Double.isNaN(r.close)) continue;
                if (Double.isNaN(r.change)) r.change = 0;
                list.add(r);
                map.put(r.code, r);
            }
            // 本益比、殖利率、淨值比（抓不到不影響行情）
            try {
                JSONArray p = new JSONArray(Data.get("https://openapi.twse.com.tw/v1/exchangeReport/BWIBBU_ALL"));
                for (int i = 0; i < p.length(); i++) {
                    JSONObject o = p.getJSONObject(i);
                    Row r = map.get(o.optString("Code").trim());
                    if (r == null) continue;
                    r.pe = Ui.num(o.optString("PEratio"));
                    r.yield = Ui.num(o.optString("DividendYield"));
                    r.pb = Ui.num(o.optString("PBratio"));
                }
            } catch (Exception ignored) {
            }
            try {
                JSONArray p = new JSONArray(Data.get("https://www.tpex.org.tw/openapi/v1/tpex_mainboard_peratio_analysis"));
                for (int i = 0; i < p.length(); i++) {
                    JSONObject o = p.getJSONObject(i);
                    Row r = map.get(o.optString("SecuritiesCompanyCode").trim());
                    if (r == null) continue;
                    r.pe = Ui.num(o.optString("PriceEarningRatio"));
                    r.yield = Ui.num(o.optString("YieldRatio"));
                    r.pb = Ui.num(o.optString("PriceBookRatio"));
                }
            } catch (Exception ignored) {
            }
            if (list.isEmpty()) return false;
            save(c, list);
            synchronized (Market.class) {
                rows = list;
                byCode = map;
                loadedAt = System.currentTimeMillis();
            }
            return true;
        } catch (Exception e) {
            rows(c);
            return false;
        }
    }

    private static void save(Context c, List<Row> list) {
        try {
            JSONArray a = new JSONArray();
            for (Row r : list) {
                JSONArray x = new JSONArray();
                x.put(r.code).put(r.name).put(r.market).put(r.date)
                        .put(n(r.close)).put(n(r.change)).put(n(r.open)).put(n(r.high)).put(n(r.low))
                        .put(n(r.volume)).put(n(r.value)).put(n(r.pe)).put(n(r.yield)).put(n(r.pb));
                a.put(x);
            }
            Data.writeFile(file(c), a.toString());
        } catch (Exception ignored) {
        }
    }

    private static Object n(double d) { return Double.isNaN(d) || Double.isInfinite(d) ? "" : (Object) d; }

    private static double d(JSONArray x, int i) {
        Object o = x.opt(i);
        return o instanceof Number ? ((Number) o).doubleValue() : Double.NaN;
    }

    private static void load(Context c) {
        List<Row> list = new ArrayList<>();
        Map<String, Row> map = new HashMap<>();
        String s = Data.readFile(file(c));
        if (s != null) {
            try {
                JSONArray a = new JSONArray(s);
                for (int i = 0; i < a.length(); i++) {
                    JSONArray x = a.getJSONArray(i);
                    Row r = new Row();
                    r.code = x.getString(0); r.name = x.getString(1); r.market = x.getString(2); r.date = x.getString(3);
                    r.close = d(x, 4); r.change = d(x, 5); r.open = d(x, 6); r.high = d(x, 7); r.low = d(x, 8);
                    r.volume = d(x, 9); r.value = d(x, 10); r.pe = d(x, 11); r.yield = d(x, 12); r.pb = d(x, 13);
                    list.add(r);
                    map.put(r.code, r);
                }
            } catch (Exception ignored) {
            }
        }
        rows = list;
        byCode = map;
        loadedAt = System.currentTimeMillis();
    }

    /** 代號或名稱搜尋 */
    static List<Row> search(Context c, String q) {
        q = q.trim().toUpperCase();
        List<Row> exact = new ArrayList<>(), starts = new ArrayList<>(), contains = new ArrayList<>();
        if (q.isEmpty()) return exact;
        for (Row r : rows(c)) {
            if (r.code.equals(q) || r.name.equals(q)) exact.add(r);
            else if (r.code.startsWith(q) || r.name.startsWith(q)) starts.add(r);
            else if (r.name.toUpperCase().contains(q)) contains.add(r);
        }
        exact.addAll(starts);
        exact.addAll(contains);
        return exact.size() > 60 ? exact.subList(0, 60) : exact;
    }
}
