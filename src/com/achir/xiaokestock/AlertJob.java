package com.achir.xiaokestock;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/** 到價提醒：系統 JobScheduler 每 15 分鐘（Android 下限）檢查一次，有網路才跑。 */
public class AlertJob extends JobService {
    private static final int JOB_ID = 1015;
    private static final String CHANNEL = "price_alert";

    static void schedule(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        if (Store.alerts(c).isEmpty()) {
            js.cancel(JOB_ID);
            return;
        }
        for (JobInfo j : js.getAllPendingJobs()) if (j.getId() == JOB_ID) return;
        js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, AlertJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(15 * 60 * 1000L)
                .setPersisted(true)
                .build());
    }

    @Override public boolean onStartJob(final JobParameters params) {
        new Thread(new Runnable() {
            public void run() {
                try {
                    check(AlertJob.this);
                } catch (Exception ignored) {
                }
                jobFinished(params, false);
            }
        }).start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) { return true; }

    /** 台股：平日 09:00–13:35（台北時間）才檢查；海外商品不限時段。 */
    static boolean twOpen() {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Taipei"));
        int dow = c.get(Calendar.DAY_OF_WEEK);
        if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) return false;
        int m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        return m >= 9 * 60 && m <= 13 * 60 + 35;
    }

    static void check(Context c) throws Exception {
        List<Store.Alert> alerts = Store.alerts(c);
        SimpleDateFormat df = new SimpleDateFormat("yyyyMMdd", Locale.US);
        df.setTimeZone(TimeZone.getTimeZone("Asia/Taipei"));
        String today = df.format(new Date());
        boolean open = twOpen();
        List<String> codes = new ArrayList<>();
        for (Store.Alert a : alerts) {
            if (!a.on || today.equals(a.firedDay)) continue;
            if (Data.isTw(a.code) && !open) continue;
            codes.add(a.code);
        }
        if (codes.isEmpty()) return;
        Map<String, Data.Quote> qs = Data.quotes(c, codes);
        boolean changed = false;
        for (Store.Alert a : alerts) {
            Data.Quote q = qs.get(a.code);
            if (q == null || !codes.contains(a.code) || Double.isNaN(q.price)) continue;
            String why = null;
            if (!Double.isNaN(a.above) && q.price >= a.above) why = "漲到 " + Ui.price(q.price) + "（≥ " + Ui.price(a.above) + "）";
            else if (!Double.isNaN(a.below) && q.price <= a.below) why = "跌到 " + Ui.price(q.price) + "（≤ " + Ui.price(a.below) + "）";
            else if (!Double.isNaN(a.pctMove) && Math.abs(q.pct()) >= a.pctMove) why = "漲跌幅 " + Ui.pct(q.pct());
            if (why != null) {
                notify(c, a, q, why);
                a.firedDay = today;
                changed = true;
            }
        }
        if (changed) Store.saveAlerts(c, alerts);
    }

    private static void notify(Context c, Store.Alert a, Data.Quote q, String why) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "到價提醒", NotificationManager.IMPORTANCE_HIGH));
        }
        Intent i = new Intent(c, StockActivity.class).putExtra("code", a.code).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(c, a.code.hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CHANNEL) : new Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("小柯提醒：" + q.name + " " + a.code)
                .setContentText(why + "，" + Ui.pct(q.pct()))
                .setContentIntent(pi)
                .setAutoCancel(true);
        nm.notify(a.code.hashCode(), b.build());
    }
}
