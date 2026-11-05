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
        RingtoneManager manager = new RingtoneManager(activity);
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
            object.put("enabled", prefs.getBoolean("enabled", false));
            object.put("triggerAt", prefs.getLong("triggerAt", 0));
            object.put("snoozeUntil", prefs.getLong("snoozeAt", 0));
            object.put("lastFiredAt", prefs.getLong("lastFiredAt", 0));
            object.put("hour", prefs.getInt("hour", 7));
            object.put("minute", prefs.getInt("minute", 30));
            object.put("repeat", prefs.getBoolean("repeat", true));
            object.put("soundUri", prefs.getString("soundUri", ""));
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
     * Сохраняет всё и (пере)планирует будильник. Возвращает JSON {"triggerAt": ...}.
     * Если enabled=false — будильник отменяется, настройки остаются.
     */
    @JavascriptInterface
    public String applyConfig(String json) {
        JSONObject result = new JSONObject();
        try {
            JSONObject config = new JSONObject(json);
            SharedPreferences prefs = AlarmScheduler.prefs(activity);
            SharedPreferences.Editor editor = prefs.edit();
            writeSettings(config, editor);
            editor.putInt("hour", clampInt(config.optInt("hour", 7), 0, 23));
            editor.putInt("minute", clampInt(config.optInt("minute", 30), 0, 59));
            editor.putBoolean("repeat", config.optBoolean("repeat", true));
            editor.putInt("days", config.optInt("days", NextAlarm.ALL_DAYS));
            editor.putLong("skipBefore", config.optLong("skipBefore", 0));
            editor.commit();

            if (config.optBoolean("enabled", false)) {
                result.put("triggerAt", AlarmScheduler.scheduleFromPrefs(activity));
            } else {
                AlarmScheduler.cancelAll(activity);
                result.put("triggerAt", 0);
            }
            AlarmScheduler.logLine(activity, "applyConfig: enabled=" + config.optBoolean("enabled", false)
                    + ", время=" + config.optInt("hour", 7) + ":" + config.optInt("minute", 30)
                    + ", triggerAt=" + result.optLong("triggerAt", 0));
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
    }

    private static int clampInt(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    @JavascriptInterface
    public void cancelAlarm() {
        AlarmScheduler.cancelAll(activity);
    }

    @JavascriptInterface
    public void snooze(int minutes, String soundUri) {
        int safeMinutes = Math.max(1, Math.min(120, minutes));
        long triggerAt = System.currentTimeMillis() + safeMinutes * 60000L;
        AlarmScheduler.scheduleSnooze(activity, triggerAt, soundUri);
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
    @JavascriptInterface
    public void testAlarm() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                AlarmService.start(activity,
                        AlarmScheduler.prefs(activity).getString("soundUri", ""), true);
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
