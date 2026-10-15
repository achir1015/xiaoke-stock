package com.achir.xiaokestock;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.widget.Toast;

import java.util.Locale;

/** 小柯唸報價：系統 TTS，中文（台灣）。 */
final class Speaker {
    private Speaker() {}

    private static TextToSpeech tts;
    private static boolean ready;
    private static String pending;

    static void say(final Context c, String text) {
        if (tts == null) {
            pending = text;
            final Context app = c.getApplicationContext();
            tts = new TextToSpeech(app, new TextToSpeech.OnInitListener() {
                public void onInit(int status) {
                    if (status != TextToSpeech.SUCCESS) {
                        Toast.makeText(app, "這支手機沒有可用的語音引擎", Toast.LENGTH_SHORT).show();
                        tts = null;
                        return;
                    }
                    tts.setLanguage(Locale.TAIWAN);
                    ready = true;
                    if (pending != null) tts.speak(pending, TextToSpeech.QUEUE_FLUSH, null, "xk");
                    pending = null;
                }
            });
            return;
        }
        if (ready) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "xk");
        else pending = text;
    }

    static void stop() {
        if (tts != null && ready) tts.stop();
    }

    /** 例：「台積電，2440元，下跌50元，跌幅2.01%」 */
    static String describe(Data.Quote q) {
        double ch = q.change();
        String name = q.name == null || q.name.isEmpty() ? q.code : q.name;
        StringBuilder b = new StringBuilder(name).append("，").append(say(q.price));
        if (!"idx".equals(q.market) && !"us".equals(q.market)) b.append("元");
        if (Double.isNaN(ch) || ch == 0) b.append("，平盤");
        else b.append(ch > 0 ? "，上漲" : "，下跌").append(say(Math.abs(ch)))
                .append(ch > 0 ? "，漲幅" : "，跌幅").append(String.format(Locale.US, "%.2f", Math.abs(q.pct()))).append("%");
        return b.append("。").toString();
    }

    private static String say(double v) {
        if (Double.isNaN(v)) return "無資料";
        if (v == Math.rint(v)) return String.valueOf((long) v);
        return String.format(Locale.US, "%.2f", v).replaceAll("0+$", "");
    }
}
