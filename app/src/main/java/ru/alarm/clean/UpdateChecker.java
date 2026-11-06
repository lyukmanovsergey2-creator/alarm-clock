package ru.alarm.clean;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Необязательная проверка обновлений.
 *
 * По умолчанию выключена. Включается пользователем после явного согласия
 * (диалог «Разрешить доступ к интернету»). Если включена — раз в сутки
 * при открытии приложения делается фоновый запрос к странице релизов на GitHub.
 * Найдена новая версия — интерфейс показывает «Обновить / Позже».
 *
 * Важно: приложение само не скачивает и не устанавливает APK —
 * кнопка «Обновить» открывает страницу релизов в браузере, установку
 * выполняет пользователь вручную. Это убирает разрешение
 * REQUEST_INSTALL_PACKAGES, на которое ругается Google Play Защита.
 * Обновления никак не касаются будильников: проверка молчит во время
 * звонка и не трогает расписание. «Позже» — предложение повторится
 * при следующей проверке.
 */
final class UpdateChecker {

    static final String K_ENABLED = "updateCheck";
    private static final String K_LAST = "updateLastCheckAt";
    /* Публичный адрес релизов приложения (без авторизации). */
    private static final String RELEASES_URL =
            "https://api.github.com/repos/lyukmanovsergey2-creator/alarm-clock/releases/latest";
    private static final String RELEASES_PAGE =
            "https://github.com/lyukmanovsergey2-creator/alarm-clock/releases/latest";
    private static final long CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000; /* раз в сутки */
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean busy = false;

    private UpdateChecker() {
    }

    static boolean enabled(android.content.SharedPreferences prefs) {
        return prefs.getBoolean(K_ENABLED, false);
    }

    /** Ежедневная фоновая проверка: не чаще раза в сутки, молчит во время звонка. */
    static void maybeCheck(Activity activity) {
        try {
            if (AlarmService.running || busy) {
                return;
            }
            android.content.SharedPreferences prefs = AlarmScheduler.prefs(activity);
            if (!enabled(prefs)) {
                return;
            }
            long now = System.currentTimeMillis();
            long last = prefs.getLong(K_LAST, 0);
            if (last > 0 && now - last < CHECK_INTERVAL_MS) {
                return;
            }
            AlarmScheduler.logLine(activity, "update: плановая проверка");
            checkNow(activity, false);
        } catch (Exception ignored) {
        }
    }

    /**
     * Проверка релизов. manual=true — по кнопке «Проверить сейчас»,
     * результат показывается даже если новой версии нет.
     */
    static void checkNow(final Activity activity, final boolean manual) {
        if (busy) {
            return;
        }
        busy = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                String resultError = "";
                try {
                    android.content.SharedPreferences prefs = AlarmScheduler.prefs(activity);
                    prefs.edit().putLong(K_LAST, System.currentTimeMillis()).apply();

                    String current = currentVersion(activity);
                    HttpURLConnection conn = (HttpURLConnection) new URL(RELEASES_URL).openConnection();
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(10000);
                    conn.setRequestProperty("Accept", "application/vnd.github+json");
                    conn.setRequestProperty("User-Agent", "CleanAlarm-App");
                    int code = conn.getResponseCode();
                    if (code != 200) {
                        throw new Exception("HTTP " + code);
                    }
                    String body = readAll(conn.getInputStream());
                    JSONObject release = new JSONObject(body);
                    String tag = release.optString("tag_name", "");
                    String version = versionFromTag(tag);
                    if (version == null) {
                        throw new Exception("версия не распознана (" + tag + ")");
                    }
                    boolean newer = isNewer(version, current);
                    AlarmScheduler.logLine(activity, "update: текущая " + current
                            + ", релиз " + version + ", есть новая=" + newer);
                    JSONObject out = new JSONObject();
                    out.put("available", newer);
                    out.put("version", version);
                    out.put("manual", manual);
                    postJs(activity, "__onUpdateCheck", out.toString());
                    ok = true;
                } catch (Exception exception) {
                    resultError = exception.getClass().getSimpleName()
                            + (exception.getMessage() != null ? " " + exception.getMessage() : "");
                } finally {
                    busy = false;
                    if (!ok) {
                        final Activity a = activity;
                        final boolean manualFinal = manual;
                        final String err = resultError;
                        MAIN.post(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    AlarmScheduler.logLine(a, "update: ОШИБКА " + err);
                                    JSONObject out = new JSONObject();
                                    out.put("available", false);
                                    out.put("manual", manualFinal);
                                    out.put("error", true);
                                    postJs(a, "__onUpdateCheck", out.toString());
                                } catch (Exception ignored) {
                                }
                            }
                        });
                    }
                }
            }
        }).start();
    }

    /** Кнопка «Обновить»: открывает страницу релизов в браузере. */
    static void openReleasePage(Activity activity) {
        try {
            AlarmScheduler.logLine(activity, "update: открываю страницу релизов");
            Intent browser = new Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_PAGE));
            activity.startActivity(browser);
        } catch (Exception exception) {
            AlarmScheduler.logLine(activity, "update: браузер не открылся: " + exception);
            android.widget.Toast.makeText(activity, "Не удалось открыть браузер",
                    android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private static String currentVersion(Activity activity) {
        try {
            return activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0).versionName;
        } catch (Exception exception) {
            return "0";
        }
    }

    /** "v2.9" → "2.9"; null, если версия не распознана. */
    private static String versionFromTag(String tag) {
        if (tag == null) return null;
        String v = tag.trim().toLowerCase();
        if (v.startsWith("v")) v = v.substring(1);
        if (v.isEmpty() || !Character.isDigit(v.charAt(0))) return null;
        return v;
    }

    /** Части версии в виде чисел ("2.9" → [2, 9]). */
    private static long[] versionParts(String v) {
        String[] chunks = v.split("\\.");
        long[] out = new long[chunks.length];
        for (int i = 0; i < chunks.length; i++) {
            long value = 0;
            StringBuilder digits = new StringBuilder();
            for (int j = 0; j < chunks[i].length(); j++) {
                char c = chunks[i].charAt(j);
                if (Character.isDigit(c)) digits.append(c); else break;
            }
            try {
                value = Long.parseLong(digits.toString());
            } catch (NumberFormatException ignored) {
            }
            out[i] = value;
        }
        return out;
    }

    static boolean isNewer(String remote, String current) {
        try {
            long[] r = versionParts(remote);
            long[] c = versionParts(current);
            int n = Math.max(r.length, c.length);
            for (int i = 0; i < n; i++) {
                long rv = i < r.length ? r[i] : 0;
                long cv = i < c.length ? c[i] : 0;
                if (rv != cv) return rv > cv;
            }
            return false;
        } catch (Exception exception) {
            return false;
        }
    }

    private static void postJs(final Activity activity, final String function, final String json) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                if (activity instanceof MainActivity) {
                    String quoted = JSONObject.quote(json);
                    ((MainActivity) activity).runJs(
                            "window." + function + " && window." + function + "(" + quoted + ");");
                }
            }
        });
    }

    private static String readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int n;
        while ((n = in.read(buffer)) > 0) {
            out.write(buffer, 0, n);
        }
        try {
            in.close();
        } catch (Exception ignored) {
        }
        return out.toString("UTF-8");
    }
}
