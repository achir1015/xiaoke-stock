package com.achir.xiaokestock;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.Callable;

/** 檢查更新：比對 GitHub 最新 Release，有新版就用 DownloadManager 下載 APK，再開系統安裝畫面。 */
final class Updater {
    private Updater() {}

    static final String REPO = "achir1015/xiaoke-stock";

    static final class Release { String tag, name, body, apkUrl; }

    static void check(final Activity a) {
        Toast.makeText(a, "檢查更新中…", Toast.LENGTH_SHORT).show();
        Data.async(new Callable<Release>() {
            public Release call() throws Exception {
                JSONObject o = new JSONObject(Data.get("https://api.github.com/repos/" + REPO + "/releases/latest"));
                Release r = new Release();
                r.tag = o.getString("tag_name");
                r.name = o.optString("name", r.tag);
                r.body = o.optString("body", "");
                JSONArray as = o.optJSONArray("assets");
                for (int i = 0; as != null && i < as.length(); i++) {
                    JSONObject x = as.getJSONObject(i);
                    if (x.optString("name").endsWith(".apk")) r.apkUrl = x.getString("browser_download_url");
                }
                return r;
            }
        }, new Data.Done<Release>() {
            public void ok(final Release r) {
                if (a.isFinishing()) return;
                if (r == null) {
                    Toast.makeText(a, "無法連到 GitHub，請稍後再試", Toast.LENGTH_LONG).show();
                    return;
                }
                String cur = version(a);
                if (compare(r.tag, cur) <= 0 || r.apkUrl == null) {
                    new AlertDialog.Builder(a).setTitle("已是最新版")
                            .setMessage("目前版本 v" + cur + "\nGitHub 最新版 " + r.tag)
                            .setPositiveButton("好", null).show();
                    return;
                }
                new AlertDialog.Builder(a).setTitle("有新版 " + r.tag)
                        .setMessage("目前版本 v" + cur + "\n\n" + r.body.replace("**", ""))
                        .setPositiveButton("下載並安裝", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) { download(a, r); }
                        })
                        .setNegativeButton("稍後", null).show();
            }
        });
    }

    static String version(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "0";
        }
    }

    /** "v1.2.0" 與 "1.10" 這類版本號逐段比數字 */
    static int compare(String a, String b) {
        String[] x = a.replaceAll("^[vV]", "").split("[^0-9]+"), y = b.replaceAll("^[vV]", "").split("[^0-9]+");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length && !x[i].isEmpty() ? Integer.parseInt(x[i]) : 0;
            int q = i < y.length && !y[i].isEmpty() ? Integer.parseInt(y[i]) : 0;
            if (p != q) return p < q ? -1 : 1;
        }
        return 0;
    }

    private static void download(final Activity a, Release r) {
        // Android 8+ 要先允許本 App「安裝未知應用程式」
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(a).setTitle("需要安裝權限")
                    .setMessage("請在接下來的畫面開啟「允許來自這個來源的應用程式」，返回後再按一次「檢查更新」。")
                    .setPositiveButton("前往設定", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + a.getPackageName())));
                        }
                    })
                    .setNegativeButton("取消", null).show();
            return;
        }
        final DownloadManager dm = (DownloadManager) a.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) return;
        String file = "XiaoKeStock-" + r.tag + ".apk";
        File old = new File(a.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), file);
        if (old.exists()) old.delete();
        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(r.apkUrl))
                .setTitle("小柯股市助手 " + r.tag)
                .setDescription("下載新版中")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(a, Environment.DIRECTORY_DOWNLOADS, file);
        final long id = dm.enqueue(req);
        Toast.makeText(a, "下載中，完成後會自動開啟安裝畫面", Toast.LENGTH_LONG).show();

        final Context app = a.getApplicationContext();
        BroadcastReceiver done = new BroadcastReceiver() {
            public void onReceive(Context c, Intent i) {
                if (i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return;
                try {
                    app.unregisterReceiver(this);
                } catch (Exception ignored) {
                }
                install(app, dm, id);
            }
        };
        IntentFilter f = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(done, f, Context.RECEIVER_EXPORTED);
        else app.registerReceiver(done, f);
    }

    private static void install(Context c, DownloadManager dm, long id) {
        int status = -1;
        Cursor cur = dm.query(new DownloadManager.Query().setFilterById(id));
        if (cur != null) {
            if (cur.moveToFirst()) status = cur.getInt(cur.getColumnIndex(DownloadManager.COLUMN_STATUS));
            cur.close();
        }
        Uri uri = dm.getUriForDownloadedFile(id);
        if (status != DownloadManager.STATUS_SUCCESSFUL || uri == null) {
            Toast.makeText(c, "下載失敗，請稍後再試", Toast.LENGTH_LONG).show();
            return;
        }
        Intent i = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(i);
        } catch (Exception e) {
            Toast.makeText(c, "無法開啟安裝畫面：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
