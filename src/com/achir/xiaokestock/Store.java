package com.achir.xiaokestock;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 自選群組、到價提醒、設定，存在 SharedPreferences（JSON）。 */
final class Store {
    private Store() {}

    static final int MAX_GROUPS = 10;
    static final int MAX_PER_GROUP = 60;

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("xiaokestock", Context.MODE_PRIVATE);
    }

    // ───────────── 自選群組 ─────────────

    static final class Group {
        String name;
        List<String> codes = new ArrayList<>();
        Group(String n) { name = n; }
    }

    static List<Group> groups(Context c) {
        List<Group> out = new ArrayList<>();
        String s = prefs(c).getString("groups", null);
        if (s != null) {
            try {
                JSONArray a = new JSONArray(s);
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    Group g = new Group(o.getString("name"));
                    JSONArray cs = o.getJSONArray("codes");
                    for (int j = 0; j < cs.length(); j++) g.codes.add(cs.getString(j));
                    out.add(g);
                }
            } catch (Exception ignored) {
            }
        }
        if (out.isEmpty()) {
            Group g = new Group("自選1");
            for (String x : new String[]{"2330", "2317", "2454", "2308", "0050", "0056", "00878", "NVDA"}) g.codes.add(x);
            out.add(g);
            out.add(new Group("自選2"));
            saveGroups(c, out);
        }
        return out;
    }

    static void saveGroups(Context c, List<Group> gs) {
        try {
            JSONArray a = new JSONArray();
            for (Group g : gs) {
                JSONArray cs = new JSONArray();
                for (String x : g.codes) cs.put(x);
                a.put(new JSONObject().put("name", g.name).put("codes", cs));
            }
            prefs(c).edit().putString("groups", a.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    static int currentGroup(Context c) { return prefs(c).getInt("group", 0); }

    static void setCurrentGroup(Context c, int i) { prefs(c).edit().putInt("group", i).apply(); }

    // ───────────── 到價提醒 ─────────────

    static final class Alert {
        String code, name;
        double above = Double.NaN, below = Double.NaN;   // 漲到 ≥ above、跌到 ≤ below
        double pctMove = Double.NaN;                      // 漲跌幅絕對值 ≥ pctMove %
        boolean on = true;
        String firedDay = "";                             // 同一天只提醒一次
    }

    static List<Alert> alerts(Context c) {
        List<Alert> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(c).getString("alerts", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Alert x = new Alert();
                x.code = o.getString("code");
                x.name = o.optString("name", x.code);
                x.above = o.optDouble("above", Double.NaN);
                x.below = o.optDouble("below", Double.NaN);
                x.pctMove = o.optDouble("pct", Double.NaN);
                x.on = o.optBoolean("on", true);
                x.firedDay = o.optString("fired", "");
                out.add(x);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    static void saveAlerts(Context c, List<Alert> l) {
        try {
            JSONArray a = new JSONArray();
            for (Alert x : l) {
                JSONObject o = new JSONObject().put("code", x.code).put("name", x.name).put("on", x.on).put("fired", x.firedDay);
                if (!Double.isNaN(x.above)) o.put("above", x.above);
                if (!Double.isNaN(x.below)) o.put("below", x.below);
                if (!Double.isNaN(x.pctMove)) o.put("pct", x.pctMove);
                a.put(o);
            }
            prefs(c).edit().putString("alerts", a.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    // ───────────── 設定 ─────────────

    static int refreshSec(Context c) { return prefs(c).getInt("refresh", 10); }

    static void setRefreshSec(Context c, int s) { prefs(c).edit().putInt("refresh", s).apply(); }

    static boolean showPct(Context c) { return prefs(c).getBoolean("showPct", true); }

    static void setShowPct(Context c, boolean b) { prefs(c).edit().putBoolean("showPct", b).apply(); }
}
