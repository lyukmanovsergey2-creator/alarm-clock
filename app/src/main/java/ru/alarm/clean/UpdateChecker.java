package ru.alarm.clean;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Необязательная проверка обновлений.
 *
 * По умолчанию выключена. Включается пользователем после явного согласия
 * (диалог «Разрешить доступ к интернету»). Если включена — раз в сутки
 * при открытии приложения делается фоновый запрос к странице релизов на GitHub.
 * Найдена новая версия — интерфейс показывает «Обновить / Позже». Обновления
 * никак не касаются будильников: проверка молчит во время звонка и не трогает
 * расписание. «Позже» — предложение повторится при следующей проверке.
 */
final class UpdateChecker {

    static final String K_ENABLED = "updateCheck";
    private static final String K_LAST = "updateLastCheckAt";
    private static final String K_PENDING_VERSION = "updatePendingVersion";
    private static final String K_PENDING_URL = "updatePendingUrl";
    /* Публичный адрес релизов приложения (без авторизации). */
    private static final String RELEASES_URL =
            "https://api.github.com/repos/lyukmanovsergey2-creator/alarm-clock/releases/latest";
    private static final long CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000; /* раз в сутки */
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean busy = false;
    private static volatile boolean downloading = false;

    private UpdateChecker() {
    }

    static boolean enabled(SharedPreferences prefs) {
        return prefs.getBoolean(K_ENABLED, false);
    }

    /** Ежедневная фоновая проверка: не чаще раза в сутки, молчит во время звонка. */
    static void maybeCheck(Activity activity) {
        try {
            if (AlarmService.running || busy) {
                return;
            }
            SharedPreferences prefs = AlarmScheduler.prefs(activity);
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
     *
     * @param clearLast проверяющий запрос не учитывается в суточном таймере
     *                  (для случая «согласие дано — проверяем немедленно»).
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
                    SharedPreferences prefs = AlarmScheduler.prefs(activity);
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
                    String apkUrl = "";
                    JSONArray assets = release.optJSONArray("assets");
                    for (int i = 0; assets != null && i < assets.length(); i++) {
                        JSONObject asset = assets.getJSONObject(i);
                        if (asset.optString("name", "").toLowerCase().endsWith(".apk")) {
                            apkUrl = asset.optString("browser_download_url", "");
                            break;
                        }
                    }
                    if (version == null || apkUrl.isEmpty()) {
                        throw new Exception("релиз без APK (" + tag + ")");
                    }
                    boolean newer = isNewer(version, current);
                    if (newer) {
                        prefs.edit()
                                .putString(K_PENDING_VERSION, version)
                                .putString(K_PENDING_URL, apkUrl)
                                .apply();
                    } else {
                        clearPending(prefs);
                    }
                    AlarmScheduler.logLine(activity, "update: текущая " + current
                            + ", релиз " + version + ", есть новая=" + newer);
                    JSONObject out = new JSONObject();
                    out.put("available", newer);
                    out.put("version", newer ? version : current);
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

    /** Скачивает APK обновления в кеш приложения, по завершении открывает установку. */
    static void download(final Activity activity) {
        if (downloading) {
            return;
        }
        final SharedPreferences prefs = AlarmScheduler.prefs(activity);
        final String url = prefs.getString(K_PENDING_URL, "");
        if (url == null || url.isEmpty()) {
            Toast.makeText(activity, "Нет данных об обновлении", Toast.LENGTH_SHORT).show();
            return;
        }
        downloading = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                DownloadResult result = new DownloadResult();
                try {
                    File dir = new File(activity.getCacheDir(), "update");
                    dir.mkdirs();
                    File target = new File(dir, "update.apk");
                    if (target.exists()) {
                        target.delete();
                    }
                    HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(30000);
                    conn.setRequestProperty("User-Agent", "CleanAlarm-App");
                    long total = conn.getContentLength();
                    InputStream in = conn.getInputStream();
                    OutputStream out = new FileOutputStream(target);
                    byte[] buffer = new byte[8192];
                    long done = 0;
                    int lastPct = -1;
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        out.write(buffer, 0, n);
                        done += n;
                        if (total > 0) {
                            int pct = (int) (done * 100 / total);
                            if (pct / 10 > lastPct / 10) {
                                lastPct = pct;
                                postJs(activity, "__onUpdateProgress", String.valueOf(pct));
                            }
                        }
                    }
                    out.flush();
                    closeQuietly(out);
                    closeQuietly(in);
                    if (target.length() < 4096) {
                        throw new Exception("файл слишком мал");
                    }
                    result.ok = true;
                } catch (final Exception exception) {
                    result.error = exception.getClass().getSimpleName()
                            + (exception.getMessage() != null ? " " + exception.getMessage() : "");
                }
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        downloading = false;
                        if (result.ok) {
                            AlarmScheduler.logLine(activity, "update: APK скачан — открываю установку");
                            installOnMain(activity);
                        } else {
                            AlarmScheduler.logLine(activity, "update: скачивание НЕ удалось: " + result.error);
                            Toast.makeText(activity, "Скачивание не удалось", Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }).start();
    }

    private static final class DownloadResult {
        boolean ok;
        String error;
    }

    /** Открывает системный установщик с уже скачанным файлом. */
    private static void installOnMain(Activity activity) {
        try {
            File apk = new File(activity.getCacheDir(), "update/update.apk");
            if (!apk.exists() || apk.length() == 0) {
                Toast.makeText(activity, "Файл обновления не найден", Toast.LENGTH_SHORT).show();
                return;
            }
            Uri uri = Uri.parse("content://" + UpdateSourceProvider.AUTHORITY + "/update/update.apk");
            Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, activity.getPackageName());
            activity.startActivity(intent);
        } catch (Exception exception) {
            AlarmScheduler.logLine(activity, "update: установка НЕ началась: " + exception);
            Toast.makeText(activity, "Установщик не открылся", Toast.LENGTH_LONG).show();
        }
    }

    static void clearPending(SharedPreferences prefs) {
        prefs.edit().putString(K_PENDING_VERSION, "").putString(K_PENDING_URL, "").apply();
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

    private static void postJs(Activity activity, String function, String json) {
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
        closeQuietly(in);
        return out.toString("UTF-8");
    }

    private static void closeQuietly(OutputStream out) {
        try {
            if (out != null) out.close();
        } catch (Exception ignored) {
        }
    }

    private static void closeQuietly(InputStream in) {
        try {
            if (in != null) in.close();
        } catch (Exception ignored) {
        }
    }
}
