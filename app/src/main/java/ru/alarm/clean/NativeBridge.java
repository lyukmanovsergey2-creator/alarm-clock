package ru.alarm.clean;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.database.Cursor;
import android.os.PowerManager;
import android.provider.OpenableColumns;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class NativeBridge {

    private final Activity activity;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private MediaPlayer previewPlayer;
    private Runnable previewStop;

    NativeBridge(Activity activity, Object webView) {
        this.activity = activity;
    }

    @JavascriptInterface
    public String getAlarmSounds() {
        JSONArray result = new JSONArray();
        try {
            result.put(soundObject("По умолчанию", ""));

            List<Sound> sounds = new ArrayList<>();
            collectSounds(RingtoneManager.TYPE_ALARM, sounds);
            if (sounds.isEmpty()) {
                collectSounds(RingtoneManager.TYPE_RINGTONE, sounds);
            }
            for (int i = 0; i < sounds.size(); i++) {
                Sound sound = sounds.get(i);
                result.put(soundObject(sound.title, sound.uri));
            }
        } catch (Exception ignored) {
            /* список остаётся пустым — веб-интерфейс покажет значение по умолчанию */
        }
        return result.toString();
    }

    private void collectSounds(int type, List<Sound> out) {
        /* Только контекст приложения: если передать Activity, RingtoneManager
           регистрирует курсор как managed, и при перезапуске Activity
           случается StaleDataException (requery по закрытому курсору).
           Фикс сбоя из журнала от 06.11 23:48. */
        RingtoneManager manager = new RingtoneManager(activity.getApplicationContext());
        manager.setType(type);
        android.database.Cursor cursor = null;
        try {
            cursor = manager.getCursor();
            if (cursor == null) {
                return;
            }
            while (cursor.moveToNext()) {
                String title = cursor.getString(RingtoneManager.TITLE_COLUMN_INDEX);
                Uri uri = manager.getRingtoneUri(cursor.getPosition());
                if (uri != null && title != null && !uri.toString().isEmpty()) {
                    out.add(new Sound(title, uri.toString()));
                }
            }
        } catch (Exception ignored) {
            /* игнорируем недоступные системные звуки */
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private JSONObject soundObject(String title, String uri) throws Exception {
        JSONObject object = new JSONObject();
        object.put("title", title);
        object.put("uri", uri);
        return object;
    }

    @JavascriptInterface
    public String getState() {
        SharedPreferences prefs = AlarmScheduler.prefs(activity);
        JSONObject object = new JSONObject();
        try {
            int[] ids = AlarmScheduler.alarmIds(prefs);
            JSONArray alarms = new JSONArray();
            for (int id : ids) {
                JSONObject a = new JSONObject();
                a.put("id", id);
                a.put("enabled", prefs.getBoolean(AlarmScheduler.key(id, "enabled"), false));
                a.put("hour", prefs.getInt(AlarmScheduler.key(id, "hour"), 7));
                a.put("minute", prefs.getInt(AlarmScheduler.key(id, "minute"), 30));
                a.put("repeat", prefs.getBoolean(AlarmScheduler.key(id, "repeat"), true));
                a.put("days", prefs.getInt(AlarmScheduler.key(id, "days"), NextAlarm.ALL_DAYS));
                a.put("label", prefs.getString(AlarmScheduler.key(id, "label"), ""));
                a.put("skipBefore", prefs.getLong(AlarmScheduler.key(id, "skipBefore"), 0));
                a.put("triggerAt", prefs.getLong(AlarmScheduler.key(id, "triggerAt"), 0));
                a.put("snoozeUntil", prefs.getLong(AlarmScheduler.key(id, "snoozeAt"), 0));
                a.put("lastFiredAt", prefs.getLong(AlarmScheduler.key(id, "lastFiredAt"), 0));
                /* Персональные настройки будильника (звук, громкость и т.д.).
                   Фолбэк на общие значения сохранён для будильников,
                   созданных до обновления. */
                a.put("soundUri", AlarmScheduler.alarmSoundUri(prefs, id));
                a.put("volume", AlarmScheduler.alarmVolume(prefs, id));
                a.put("fadeSeconds", AlarmScheduler.alarmFadeSeconds(prefs, id));
                a.put("autoStopMinutes", AlarmScheduler.alarmAutoStopMinutes(prefs, id));
                a.put("snoozeMinutes", AlarmScheduler.alarmSnoozeMinutes(prefs, id));
                a.put("maxSnoozes", AlarmScheduler.alarmMaxSnoozes(prefs, id));
                a.put("vibrate", AlarmScheduler.alarmVibrate(prefs, id));
                a.put("challenge", AlarmScheduler.alarmChallenge(prefs, id));
                a.put("challengeNumbers", AlarmScheduler.alarmChallengeNumbers(prefs, id));
                alarms.put(a);
            }
            object.put("alarms", alarms);
            object.put("ringing", AlarmService.running ? AlarmService.currentAlarmId : -1);
            object.put("soundUri", prefs.getString("soundUri", ""));
            object.put("snoozeMinutes", prefs.getInt("snoozeMinutes", 10));
        } catch (Exception ignored) {
            /* возвращаем пустой объект */
        }
        return object.toString();
    }

    /** Сохраняет настройки, не влияющие на расписание (звук, громкость, лимиты...). */
    @JavascriptInterface
    public void saveSettings(String json) {
        try {
            SharedPreferences.Editor editor = AlarmScheduler.prefs(activity).edit();
            writeSettings(new JSONObject(json), editor);
            editor.apply();
        } catch (Exception ignored) {
        }
    }

    /**
     * Полная синхронизация: список будильников + глобальные настройки.
     * JS присылает весь список при любом изменении; нативная сторона приводит
     * хранилище и системные будильники в соответствие (лишние удаляются).
     * Возвращает JSON {"next": ...} — ближайшее время срабатывания.
     */
    @JavascriptInterface
    public String applyConfig(String json) {
        JSONObject result = new JSONObject();
        try {
            JSONObject config = new JSONObject(json);
            SharedPreferences prefs = AlarmScheduler.prefs(activity);
            SharedPreferences.Editor editor = prefs.edit();
            writeSettings(config, editor);

            /* Читаем присланный список будильников (до 15). */
            java.util.List<Integer> newIds = new java.util.ArrayList<Integer>();
            JSONArray items = config.optJSONArray("alarms");
            if (items != null) {
                for (int i = 0; i < items.length() && newIds.size() < AlarmScheduler.MAX_ALARMS; i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (item == null) continue;
                    int id = item.optInt("id", -1);
                    if (id < 0 || newIds.contains(id)) continue;
                    newIds.add(id);
                    int hour = clampInt(item.optInt("hour", 7), 0, 23);
                    int minute = clampInt(item.optInt("minute", 30), 0, 59);
                    boolean enabled = item.optBoolean("enabled", false);
                    boolean repeat = item.optBoolean("repeat", true);
                    int days = item.optInt("days", NextAlarm.ALL_DAYS) & NextAlarm.ALL_DAYS;
                    String label = item.optString("label", "");
                    long skipBefore = item.optLong("skipBefore", 0);
                    /* Если расписание изменено — время пересчитывается, иначе
                       сохраняем прежнее (важно для одноразовых будильников). */
                    boolean changed = hour != prefs.getInt(AlarmScheduler.key(id, "hour"), -1)
                            || minute != prefs.getInt(AlarmScheduler.key(id, "minute"), -1)
                            || repeat != prefs.getBoolean(AlarmScheduler.key(id, "repeat"), !repeat)
                            || days != prefs.getInt(AlarmScheduler.key(id, "days"), -1)
                            || skipBefore != prefs.getLong(AlarmScheduler.key(id, "skipBefore"), -1);
                    editor.putInt(AlarmScheduler.key(id, "hour"), hour);
                    editor.putInt(AlarmScheduler.key(id, "minute"), minute);
                    editor.putBoolean(AlarmScheduler.key(id, "enabled"), enabled);
                    editor.putBoolean(AlarmScheduler.key(id, "repeat"), repeat);
                    editor.putInt(AlarmScheduler.key(id, "days"), days);
                    editor.putString(AlarmScheduler.key(id, "label"), label);
                    editor.putLong(AlarmScheduler.key(id, "skipBefore"), skipBefore);
                    /* Персональные настройки будильника. На перерасчёт времени
                       не влияют — ключ «changed» их не учитывает. */
                    if (item.has("soundUri")) {
                        editor.putString(AlarmScheduler.key(id, "soundUri"),
                                item.optString("soundUri", ""));
                    }
                    if (item.has("volume")) {
                        editor.putInt(AlarmScheduler.key(id, "volume"),
                                clampInt(item.optInt("volume", 0), 0, 100));
                    }
                    if (item.has("fadeSeconds")) {
                        editor.putInt(AlarmScheduler.key(id, "fadeSeconds"),
                                clampInt(item.optInt("fadeSeconds", 0), 0, 300));
                    }
                    if (item.has("autoStopMinutes")) {
                        editor.putInt(AlarmScheduler.key(id, "autoStopMinutes"),
                                clampInt(item.optInt("autoStopMinutes", 10), 0, 120));
                    }
                    if (item.has("snoozeMinutes")) {
                        editor.putInt(AlarmScheduler.key(id, "snoozeMinutes"),
                                clampInt(item.optInt("snoozeMinutes", 10), 1, 120));
                    }
                    if (item.has("maxSnoozes")) {
                        editor.putInt(AlarmScheduler.key(id, "maxSnoozes"),
                                clampInt(item.optInt("maxSnoozes", 0), 0, 20));
                    }
                    if (item.has("vibrate")) {
                        editor.putBoolean(AlarmScheduler.key(id, "vibrate"),
                                item.optBoolean("vibrate", true));
                    }
                    if (item.has("challenge")) {
                        editor.putBoolean(AlarmScheduler.key(id, "challenge"),
                                item.optBoolean("challenge", false));
                    }
                    if (item.has("challengeNumbers")) {
                        editor.putInt(AlarmScheduler.key(id, "challengeNumbers"),
                                clampInt(item.optInt("challengeNumbers", 2), 2, 3));
                    }
                    if (changed) {
                        editor.putLong(AlarmScheduler.key(id, "triggerAt"), 0);
                    }
                }
            }

            /* Удаляем нативные будильники, которых больше нет в списке. */
            int[] oldIds = AlarmScheduler.alarmIds(prefs);
            for (int oldId : oldIds) {
                if (!newIds.contains(oldId)) {
                    AlarmScheduler.removeAlarm(activity, oldId);
                }
            }
            AlarmScheduler.writeAlarmIds(editor, newIds);
            editor.apply();

            /* Ставим все будильники заново. */
            long next = 0;
            for (int id : newIds) {
                long t = AlarmScheduler.scheduleAlarm(activity, id, true);
                if (t > 0 && (next == 0 || t < next)) next = t;
            }
            result.put("next", next);
            AlarmScheduler.logLine(activity, "applyConfig: будильников " + newIds.size()
                    + ", ближайший через " + (next > 0 ? (next - System.currentTimeMillis()) / 60000 : -1)
                    + " мин");
        } catch (Exception ignored) {
        }
        return result.toString();
    }

    private void writeSettings(JSONObject c, SharedPreferences.Editor editor) {
        if (c.has("soundUri")) editor.putString("soundUri", c.optString("soundUri", ""));
        if (c.has("label")) editor.putString("label", c.optString("label", ""));
        if (c.has("snoozeMinutes"))
            editor.putInt("snoozeMinutes", clampInt(c.optInt("snoozeMinutes", 10), 1, 120));
        if (c.has("fadeSeconds"))
            editor.putInt("fadeSeconds", clampInt(c.optInt("fadeSeconds", 0), 0, 300));
        if (c.has("volume")) editor.putInt("volume", clampInt(c.optInt("volume", 0), 0, 100));
        if (c.has("vibrate")) editor.putBoolean("vibrate", c.optBoolean("vibrate", true));
        if (c.has("autoStopMinutes"))
            editor.putInt("autoStopMinutes", clampInt(c.optInt("autoStopMinutes", 10), 0, 120));
        if (c.has("challenge")) editor.putBoolean("challenge", c.optBoolean("challenge", false));
        if (c.has("challengeNumbers"))
            editor.putInt("challengeNumbers", clampInt(c.optInt("challengeNumbers", 2), 2, 3));
        if (c.has("maxSnoozes"))
            editor.putInt("maxSnoozes", clampInt(c.optInt("maxSnoozes", 0), 0, 20));
        if (c.has("updateCheck")) editor.putBoolean("updateCheck", c.optBoolean("updateCheck", false));
    }

    private static int clampInt(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    /**
     * Останавливает звонящий будильник с экрана-списка.
     * mode "forever" — выключить звонящий будильник совсем;
     * иначе — только заглушить звонок (расписание сохраняется).
     */
    @JavascriptInterface
    public void stopAlarm(String mode) {
        final int id = AlarmService.currentAlarmId;
        if (!AlarmService.running || id < 0) {
            return;
        }
        try {
            if ("forever".equals(mode) && !AlarmService.testing) {
                AlarmScheduler.disableForever(activity, id);
            }
        } catch (Exception ignored) {
        }
        try {
            Intent intent = new Intent(activity, AlarmService.class);
            intent.setAction(AlarmService.ACTION_SILENCE);
            intent.putExtra(AlarmScheduler.EXTRA_ALARM_ID, id);
            if (Build.VERSION.SDK_INT >= 26) {
                activity.startForegroundService(intent);
            } else {
                activity.startService(intent);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Откладывает звонящий будильник (используется с главного экрана во время звонка).
     */
    @JavascriptInterface
    public void snooze(int minutes, String soundUri) {
        int id = AlarmService.currentAlarmId;
        if (!AlarmService.running || id < 0) {
            return;
        }
        if (AlarmService.testing) {
            /* Пробный звонок откладывать нельзя — только остановить. */
            AlarmService.stop(activity);
            return;
        }
        int safeMinutes = Math.max(1, Math.min(120, minutes));
        SharedPreferences prefs = AlarmScheduler.prefs(activity);
        int count = prefs.getInt(AlarmScheduler.key(id, "snoozeCount"), 0);
        int max = prefs.getInt("maxSnoozes", 0);
        if (max > 0 && count >= max) {
            return; /* лимит исчерпан */
        }
        long triggerAt = System.currentTimeMillis() + safeMinutes * 60000L;
        prefs.edit()
                .putInt(AlarmScheduler.key(id, "snoozeCount"), count + 1)
                .putString(AlarmScheduler.key(id, "snoozeSound"), soundUri == null ? "" : soundUri)
                .apply();
        AlarmScheduler.scheduleSnooze(activity, id, triggerAt);
        try {
            AlarmService.stop(activity);
        } catch (Exception ignored) {
        }
    }

    @JavascriptInterface
    public void stopRinging() {
        AlarmService.stop(activity);
    }

    @JavascriptInterface
    public void playPreview(String soundUri) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                startPreview(soundUri);
            }
        });
    }

    private void startPreview(String soundUri) {
        stopPreviewInternal();

        Uri uri = resolveSound(soundUri);
        if (uri == null) {
            return;
        }

        try {
            MediaPlayer player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            player.setDataSource(activity, uri);
            player.setLooping(false);
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    stopPreview();
                }
            });
            player.prepare();
            player.start();
            previewPlayer = player;

            previewStop = new Runnable() {
                @Override
                public void run() {
                    stopPreview();
                }
            };
            mainHandler.postDelayed(previewStop, 10000L);
        } catch (Exception ignored) {
            stopPreviewInternal();
        }
    }

    @JavascriptInterface
    public void stopPreview() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                stopPreviewInternal();
            }
        });
    }

    private void stopPreviewInternal() {
        if (previewStop != null) {
            mainHandler.removeCallbacks(previewStop);
            previewStop = null;
        }
        if (previewPlayer != null) {
            try {
                if (previewPlayer.isPlaying()) {
                    previewPlayer.stop();
                }
            } catch (Exception ignored) {
            }
            try {
                previewPlayer.release();
            } catch (Exception ignored) {
            }
            previewPlayer = null;
        }
    }

    private Uri resolveSound(String soundUri) {
        if (soundUri != null && !soundUri.isEmpty()) {
            try {
                return Uri.parse(soundUri);
            } catch (Exception ignored) {
            }
        }
        Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (uri == null) {
            uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
        }
        return uri;
    }

    /* ————— Свой звуковой файл ————— */
    static final int REQ_PICK_SOUND = 71;

    @JavascriptInterface
    public void pickSoundFile() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("audio/*");
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                    activity.startActivityForResult(intent, REQ_PICK_SOUND);
                } catch (Exception e) {
                    toast("Не удалось открыть выбор файла");
                }
            }
        });
    }

    /** Вызывается из MainActivity после выбора файла. */
    void onSoundPicked(final Uri uri) {
        if (uri == null) {
            return;
        }
        try {
            activity.getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
            /* разрешение может быть не постоянным — звук всё равно доступен сейчас */
        }
        String title = "Свой звук";
        Cursor cursor = null;
        try {
            cursor = activity.getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0 && cursor.getString(index) != null) {
                    title = cursor.getString(index);
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        final String js = "window.__onSoundPicked && window.__onSoundPicked("
                + JSONObject.quote(title) + "," + JSONObject.quote(uri.toString()) + ");";
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).runJs(js);
        }
    }

    /* ————— Проверка и тест ————— */

    /** Пробный звонок НАСТРОЙКАМИ конкретного будильника (звук, громкость и т.д.). */
    @JavascriptInterface
    public void testAlarm(final int alarmId) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    SharedPreferences prefs = AlarmScheduler.prefs(activity);
                    int id = alarmId >= 0 ? alarmId : -1;
                    AlarmService.start(activity,
                            id >= 0 ? AlarmScheduler.alarmSoundUri(prefs, id)
                                    : prefs.getString("soundUri", ""),
                            true, id);
                } catch (Exception ignored) {
                }
            }
        });
    }

    @JavascriptInterface
    public String getHealth() {
        JSONObject o = new JSONObject();
        try {
            boolean notifications = true;
            if (Build.VERSION.SDK_INT >= 24) {
                NotificationManager nm = (NotificationManager)
                        activity.getSystemService(Context.NOTIFICATION_SERVICE);
                notifications = nm == null || nm.areNotificationsEnabled();
            }
            PowerManager pm = (PowerManager) activity.getSystemService(Context.POWER_SERVICE);
            boolean battery = pm == null
                    || pm.isIgnoringBatteryOptimizations(activity.getPackageName());
            boolean fullScreen = true;
            if (Build.VERSION.SDK_INT >= 34) {
                NotificationManager nm = (NotificationManager)
                        activity.getSystemService(Context.NOTIFICATION_SERVICE);
                fullScreen = nm == null || nm.canUseFullScreenIntent();
            }
            o.put("notifications", notifications);
            o.put("battery", battery);
            o.put("fullScreen", fullScreen);
        } catch (Exception ignored) {
        }
        return o.toString();
    }

    @JavascriptInterface
    public void openSettings(final String what) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                String pkg = activity.getPackageName();
                try {
                    Intent intent;
                    if ("battery".equals(what)) {
                        intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                        intent.setData(Uri.parse("package:" + pkg));
                    } else if ("fullscreen".equals(what) && Build.VERSION.SDK_INT >= 34) {
                        intent = new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT);
                        intent.setData(Uri.parse("package:" + pkg));
                    } else if ("notifications".equals(what) && Build.VERSION.SDK_INT >= 26) {
                        intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                        intent.putExtra(Settings.EXTRA_APP_PACKAGE, pkg);
                    } else {
                        intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        intent.setData(Uri.parse("package:" + pkg));
                    }
                    activity.startActivity(intent);
                } catch (Exception e) {
                    try {
                        Intent fallback = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        fallback.setData(Uri.parse("package:" + pkg));
                        activity.startActivity(fallback);
                    } catch (Exception ignored) {
                    }
                }
            }
        });
    }

    /* ————— Тема ————— */
    @JavascriptInterface
    public void applyTheme(final boolean light) {
        AlarmScheduler.prefs(activity).edit().putBoolean("lightTheme", light).apply();
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (activity instanceof MainActivity) {
                    ((MainActivity) activity).applyThemeColors(light);
                }
            }
        });
    }

    /* ————— Необязательная проверка обновлений ————— */

    /** Ручная проверка «Проверить сейчас» (результат — всегда видимый). */
    @JavascriptInterface
    public void checkUpdateNow() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (AlarmService.running) {
                        toast("Будильник звонит — проверка отложена");
                        return;
                    }
                    UpdateChecker.checkNow(activity, true);
                } catch (Exception e) {
                    toast("Не удалось начать проверку");
                }
            }
        });
    }

    /** Кнопка «Обновить»: фоновое скачивание APK, потом системный установщик. */
    @JavascriptInterface
    public void downloadUpdate() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    UpdateChecker.download(activity);
                } catch (Exception e) {
                    toast("Не удалось начать скачивание");
                }
            }
        });
    }

    /** Кнопка «Позже»: предложение появится при следующей проверке. */
    @JavascriptInterface
    public void discardUpdate() {
        try {
            UpdateChecker.clearPending(AlarmScheduler.prefs(activity));
        } catch (Exception ignored) {
        }
    }

    /* ————— Диагностика ————— */
    @JavascriptInterface
    public void clearDiagLog() {
        AlarmScheduler.clearDiag(activity);
    }

    /** Выгружает журнал в мессенджер/почту через системное меню «Поделиться». */
    @JavascriptInterface
    public void exportDiagLog() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    String text = AlarmScheduler.diagLog(activity);
                    if (text.trim().isEmpty()) {
                        Toast.makeText(activity, "Журнал пуст", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("text/plain");
                    send.putExtra(Intent.EXTRA_SUBJECT, "Будильник — журнал диагностики");
                    send.putExtra(Intent.EXTRA_TEXT, text);
                    activity.startActivity(Intent.createChooser(send, "Отправить журнал"));
                } catch (Exception e) {
                    Toast.makeText(activity, "Не удалось открыть отправку", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    @JavascriptInterface
    public void toast(final String message) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    void release() {
        mainHandler.removeCallbacksAndMessages(null);
        stopPreviewInternal();
    }

    private static final class Sound {
        final String title;
        final String uri;

        Sound(String title, String uri) {
            this.title = title;
            this.uri = uri;
        }
    }
}
