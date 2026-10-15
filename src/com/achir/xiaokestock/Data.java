package com.achir.xiaokestock;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/** 所有公開資料來源：證交所 MIS 即時、證交所/櫃買 OpenAPI 日資料、Yahoo 圖表、Yahoo 新聞 RSS。 */
final class Data {
    private Data() {}

    static final ExecutorService POOL = Executors.newFixedThreadPool(4);
    static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final String UA = "Mozilla/5.0 (Linux; Android 14) XiaoKeStock/1.0";
    private static final Pattern TW = Pattern.compile("^\\d{4,6}[A-Z]?$");

    interface Done<T> { void ok(T v); }

    /** 背景執行 job，完成後回到主執行緒；失敗傳 null。 */
    static <T> void async(final java.util.concurrent.Callable<T> job, final Done<T> done) {
        POOL.execute(new Runnable() {
            public void run() {
                T v = null;
                try {
                    v = job.call();
                } catch (Exception ignored) {
                }
                final T r = v;
                MAIN.post(new Runnable() {
                    public void run() { done.ok(r); }
                });
            }
        });
    }

    static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept-Language", "zh-TW");
        try {
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }

    static boolean isTw(String code) {
        return TW.matcher(code).matches();
    }

    // ───────────── 報價 ─────────────

    static final class Quote {
        String code, name, market;   // market: tse / otc / idx / us
        double price = Double.NaN, prev = Double.NaN, open = Double.NaN, high = Double.NaN, low = Double.NaN;
        double volume = Double.NaN;   // 台股：張；其他：股
        double limitUp = Double.NaN, limitDown = Double.NaN;
        String time = "";
        double[] bid = new double[0], bidVol = new double[0], ask = new double[0], askVol = new double[0];

        double change() { return price - prev; }
        double pct() { return prev > 0 ? (price - prev) / prev * 100 : Double.NaN; }
    }

    /** 指數與國際行情的代號 → 顯示名稱 */
    static final LinkedHashMap<String, String> INDEXES = new LinkedHashMap<>();
    static {
        INDEXES.put("t00", "加權指數");
        INDEXES.put("o00", "櫃買指數");
        INDEXES.put("^DJI", "道瓊工業");
        INDEXES.put("^IXIC", "那斯達克");
        INDEXES.put("^GSPC", "S&P 500");
        INDEXES.put("^SOX", "費城半導體");
        INDEXES.put("^N225", "日經 225");
        INDEXES.put("^HSI", "恆生指數");
        INDEXES.put("^KS11", "韓國綜合");
        INDEXES.put("000001.SS", "上證指數");
        INDEXES.put("TWD=X", "美元/台幣");
        INDEXES.put("JPYTWD=X", "日圓/台幣");
        INDEXES.put("GC=F", "黃金期貨");
        INDEXES.put("CL=F", "原油期貨");
        INDEXES.put("BTC-USD", "比特幣");
    }

    /** 抓一批代號的報價，台股走 MIS（一次最多 50 檔），其他走 Yahoo。回傳 code → Quote。 */
    static Map<String, Quote> quotes(Context ctx, List<String> codes) throws Exception {
        Map<String, Quote> out = new HashMap<>();
        List<String> keys = new ArrayList<>();
        List<String> others = new ArrayList<>();
        for (String code : codes) {
            if (code.equals("t00") || code.equals("o00")) {
                keys.add((code.equals("t00") ? "tse_" : "otc_") + code + ".tw");
            } else if (isTw(code)) {
                String m = Market.marketOf(ctx, code);
                if (m == null || m.equals("tse")) keys.add("tse_" + code + ".tw");
                if (m == null || m.equals("otc")) keys.add("otc_" + code + ".tw");
            } else {
                others.add(code);
            }
        }
        for (int i = 0; i < keys.size(); i += 50) {
            List<String> part = keys.subList(i, Math.min(keys.size(), i + 50));
            out.putAll(mis(join(part)));
        }
        for (String s : others) {
            try {
                Quote q = yahooQuote(s);
                if (q != null) out.put(s, q);
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private static String join(List<String> l) {
        StringBuilder b = new StringBuilder();
        for (String s : l) {
            if (b.length() > 0) b.append('|');
            b.append(s);
        }
        return b.toString();
    }

    private static Map<String, Quote> mis(String exCh) throws Exception {
        String url = "https://mis.twse.com.tw/stock/api/getStockInfo.jsp?json=1&delay=0&_="
                + System.currentTimeMillis() + "&ex_ch=" + URLEncoder.encode(exCh, "UTF-8");
        JSONObject root = new JSONObject(get(url));
        JSONArray arr = root.optJSONArray("msgArray");
        Map<String, Quote> out = new HashMap<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            Quote q = new Quote();
            q.code = o.optString("c");
            q.name = o.optString("n");
            q.market = q.code.equals("t00") || q.code.equals("o00") ? "idx" : o.optString("ex");
            q.prev = Ui.num(o.optString("y"));
            q.open = Ui.num(o.optString("o"));
            q.high = Ui.num(o.optString("h"));
            q.low = Ui.num(o.optString("l"));
            q.volume = Ui.num(o.optString("v"));
            q.limitUp = Ui.num(o.optString("u"));
            q.limitDown = Ui.num(o.optString("w"));
            q.time = o.optString("t");
            q.bid = split(o.optString("b"));
            q.bidVol = split(o.optString("g"));
            q.ask = split(o.optString("a"));
            q.askVol = split(o.optString("f"));
            double z = Ui.num(o.optString("z"));
            if (Double.isNaN(z)) z = Ui.num(o.optString("pz"));          // 這一筆沒成交：用上一筆
            if (Double.isNaN(z) && q.bid.length > 0) z = q.bid[0];
            if (Double.isNaN(z)) z = q.prev;                               // 開盤前
            q.price = z;
            if (q.code.equals("t00")) q.name = "加權指數";
            if (q.code.equals("o00")) q.name = "櫃買指數";
            if (!q.code.isEmpty()) out.put(q.code, q);
        }
        return out;
    }

    private static double[] split(String s) {
        if (s == null || s.isEmpty() || s.equals("-")) return new double[0];
        String[] p = s.split("_");
        List<Double> l = new ArrayList<>();
        for (String x : p) {
            double d = Ui.num(x);
            if (!Double.isNaN(d)) l.add(d);
        }
        double[] r = new double[l.size()];
        for (int i = 0; i < r.length; i++) r[i] = l.get(i);
        return r;
    }

    /** Yahoo 代號：台股上市 .TW、上櫃 .TWO、指數 ^TWII / ^TWOII，其他原樣。 */
    static String yahooSymbol(Context ctx, String code) {
        if (code.equals("t00")) return "^TWII";
        if (code.equals("o00")) return "^TWOII";
        if (isTw(code)) return code + ("otc".equals(Market.marketOf(ctx, code)) ? ".TWO" : ".TW");
        return code;
    }

    static Quote yahooQuote(String symbol) throws Exception {
        JSONObject meta = chartJson(symbol, "1d", "1d").getJSONObject("meta");
        Quote q = new Quote();
        q.code = symbol;
        q.name = INDEXES.containsKey(symbol) ? INDEXES.get(symbol)
                : meta.optString("shortName", meta.optString("longName", symbol));
        q.market = "us";
        q.price = meta.optDouble("regularMarketPrice", Double.NaN);
        q.prev = meta.optDouble("previousClose", meta.optDouble("chartPreviousClose", Double.NaN));
        q.high = meta.optDouble("regularMarketDayHigh", Double.NaN);
        q.low = meta.optDouble("regularMarketDayLow", Double.NaN);
        q.volume = meta.optDouble("regularMarketVolume", Double.NaN);
        long t = meta.optLong("regularMarketTime", 0);
        if (t > 0) {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("MM/dd HH:mm", Locale.TAIWAN);
            q.time = f.format(new java.util.Date(t * 1000));
        }
        return q;
    }

    // ───────────── K 線 ─────────────

    static final class Bars {
        long[] t;
        double[] o, h, l, c, v;
        double prev = Double.NaN;
        int size() { return t == null ? 0 : t.length; }
    }

    private static JSONObject chartJson(String symbol, String range, String interval) throws Exception {
        String url = "https://query1.finance.yahoo.com/v8/finance/chart/" + URLEncoder.encode(symbol, "UTF-8")
                + "?range=" + range + "&interval=" + interval + "&includePrePost=false";
        return new JSONObject(get(url)).getJSONObject("chart").getJSONArray("result").getJSONObject(0);
    }

    /** range/interval 例：1d/1m 分時、1y/1d 日K、5y/1wk 週K、max/1mo 月K */
    static Bars bars(String symbol, String range, String interval) throws Exception {
        JSONObject r = chartJson(symbol, range, interval);
        Bars b = new Bars();
        b.prev = r.getJSONObject("meta").optDouble("chartPreviousClose", Double.NaN);
        JSONArray ts = r.optJSONArray("timestamp");
        if (ts == null) {
            b.t = new long[0];
            b.o = b.h = b.l = b.c = b.v = new double[0];
            return b;
        }
        JSONObject q = r.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0);
        JSONArray o = q.optJSONArray("open"), h = q.optJSONArray("high"), l = q.optJSONArray("low"),
                c = q.optJSONArray("close"), v = q.optJSONArray("volume");
        List<Integer> keep = new ArrayList<>();
        for (int i = 0; i < ts.length(); i++) if (c != null && !c.isNull(i)) keep.add(i);
        int n = keep.size();
        b.t = new long[n];
        b.o = new double[n]; b.h = new double[n]; b.l = new double[n]; b.c = new double[n]; b.v = new double[n];
        for (int k = 0; k < n; k++) {
            int i = keep.get(k);
            b.t[k] = ts.getLong(i);
            b.c[k] = c.getDouble(i);
            b.o[k] = o == null || o.isNull(i) ? b.c[k] : o.getDouble(i);
            b.h[k] = h == null || h.isNull(i) ? b.c[k] : h.getDouble(i);
            b.l[k] = l == null || l.isNull(i) ? b.c[k] : l.getDouble(i);
            b.v[k] = v == null || v.isNull(i) ? 0 : v.getDouble(i);
        }
        return b;
    }

    // ───────────── 新聞 ─────────────

    static final class News { String title, link, date; }

    static List<News> news(String category) throws Exception {
        String xml = get("https://tw.stock.yahoo.com/rss?category=" + category);
        List<News> out = new ArrayList<>();
        int i = 0;
        while ((i = xml.indexOf("<item>", i)) >= 0) {
            int e = xml.indexOf("</item>", i);
            if (e < 0) break;
            String item = xml.substring(i, e);
            News n = new News();
            n.title = tag(item, "title");
            n.link = tag(item, "link");
            n.date = tag(item, "pubDate");
            if (n.title != null && n.link != null) out.add(n);
            i = e;
        }
        return out;
    }

    private static String tag(String s, String t) {
        int a = s.indexOf("<" + t + ">");
        int b = s.indexOf("</" + t + ">");
        if (a < 0 || b < a) return null;
        String v = s.substring(a + t.length() + 2, b).trim();
        if (v.startsWith("<![CDATA[")) v = v.substring(9, v.length() - 3);
        return v.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&lt;", "<").replace("&gt;", ">").trim();
    }

    // ───────────── 檔案快取 ─────────────

    static String readFile(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    static void writeFile(File f, String s) {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }
}
