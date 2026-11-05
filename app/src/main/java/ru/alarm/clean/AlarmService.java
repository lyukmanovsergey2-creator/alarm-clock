package ru.alarm.clean;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;

public class AlarmService extends Service {

    static final String ACTION_START = "ru.alarm.clean.START";
    static final String ACTION_SNOOZE = "ru.alarm.clean.SNOOZE";
    static final String ACTION_DISABLE = "ru.alarm.clean.DISABLE";
    static final String ACTION_SILENCE = "ru.alarm.clean.SILENCE";
    static final String EXTRA_SOUND = "sound";
    static final String EXTRA_TEST = "test";

    private static final String CHANNEL_ID = "alarm_channel";
    private static final int NOTIFICATION_ID = 42;

    static volatile boolean running = false;
    /** Пробный звонок из настроек: не трогает реальное расписание. */
    static volatile boolean testing = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int originalVolume = -1;
    private int fadeStep = 0;

    private MediaPlayer player;
    private PowerManager.WakeLock partialWakeLock;
    private PowerManager.WakeLock screenWakeLock;
    private Vibrator vibrator;
    private AudioManager audioManager;
    /* Object, а не AudioFocusRequest: класс появился только в API 26,
       а minSdk приложения — 23. */
    private Object focusRequest;

    static void start(Context context, String soundUri, boolean test) {
        Intent intent = new Intent(context, AlarmService.class);
        intent.setAction(ACTION_START);
        intent.putExtra(EXTRA_SOUND, soundUri == null ? "" : soundUri);
        intent.putExtra(EXTRA_TEST, test);
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    static void stop(Context context) {
        context.stopService(new Intent(context, AlarmService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;
        if (action == null) {
            action = ACTION_START;
        }

        if (ACTION_SNOOZE.equals(action)) {
            SharedPreferences prefs = AlarmScheduler.prefs(this);
            if (!testing) {
                int max = prefs.getInt("maxSnoozes", 0);
                int count = prefs.getInt("snoozeCount", 0);
                if (max > 0 && count >= max) {
                    /* лимит исчерпан — продолжаем звонить */
                    return START_NOT_STICKY;
                }
                int minutes = Math.max(1, Math.min(120, prefs.getInt("snoozeMinutes", 10)));
                AlarmScheduler.scheduleSnooze(this,
                        System.currentTimeMillis() + minutes * 60000L,
                        prefs.getString("soundUri", ""));
                prefs.edit().putInt("snoozeCount", count + 1).apply();
            }
            stopRinging();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_DISABLE.equals(action)) {
            if (!testing) {
                AlarmScheduler.cancelAll(this);
            }
            stopRinging();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_SILENCE.equals(action)) {
            stopRinging();
            stopSelf();
            return START_NOT_STICKY;
        }

        String soundUri = intent != null ? intent.getStringExtra(EXTRA_SOUND) : "";
        testing = intent != null && intent.getBooleanExtra(EXTRA_TEST, false);
        handler.removeCallbacksAndMessages(null);
        Notification notification = buildNotification(soundUri);

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        SharedPreferences cfg = AlarmScheduler.prefs(this);
        applyAlarmVolume(cfg.getInt("volume", 0));
        playSound(soundUri);
        startFadeIn(cfg.getInt("fadeSeconds", 0));
        wakeUp();
        if (cfg.getBoolean("vibrate", true)) {
            startVibrating();
        }
        requestAudioFocus();
        launchRingActivity(soundUri);
        scheduleAutoStop(cfg.getInt("autoStopMinutes", 10));

        return START_NOT_STICKY;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Будильники", NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("Звук и экран будильника");
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        channel.setBypassDnd(true);
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification(String soundUri) {
        Intent ringIntent = new Intent(this, RingActivity.class);
        ringIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        ringIntent.putExtra(EXTRA_SOUND, soundUri);
        PendingIntent fullScreenIntent = PendingIntent.getActivity(this, 10, ringIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent snoozeIntent = new Intent(this, AlarmService.class);
        snoozeIntent.setAction(ACTION_SNOOZE);
        PendingIntent snoozePending = PendingIntent.getService(this, 21, snoozeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent disableIntent = new Intent(this, AlarmService.class);
        disableIntent.setAction(ACTION_DISABLE);
        PendingIntent disablePending = PendingIntent.getService(this, 22, disableIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        SharedPreferences cfg = AlarmScheduler.prefs(this);
        String label = cfg.getString("label", "");
        int max = cfg.getInt("maxSnoozes", 0);
        boolean canSnooze = testing || max <= 0 || cfg.getInt("snoozeCount", 0) < max;
        boolean challenge = cfg.getBoolean("challenge", false) && !testing;
        int minutes = Math.max(1, Math.min(120, cfg.getInt("snoozeMinutes", 10)));

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        builder.setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(label.isEmpty() ? "Будильник" : label)
                .setContentText("Нажмите, чтобы остановить")
                .setColor(Color.parseColor("#D8FF63"))
                .setCategory(Notification.CATEGORY_ALARM)
                .setPriority(Notification.PRIORITY_MAX)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setAutoCancel(false)
                .setWhen(System.currentTimeMillis())
                .setContentIntent(fullScreenIntent)
                .setFullScreenIntent(fullScreenIntent, true);
        if (canSnooze) {
            builder.addAction(android.R.drawable.ic_lock_idle_alarm,
                    "Отложить " + minutes + " мин", snoozePending);
        }
        if (!challenge) {
            /* При включённом «примере для отключения» кнопки отключения в шторке нет. */
            builder.addAction(android.R.drawable.ic_lock_power_off,
                    "Отключить", disablePending);
        }

        return builder.build();
    }

    private void playSound(String soundUri) {
        releasePlayer();

        Uri uri = parseUri(soundUri);
        if (playUri(uri)) {
            return;
        }

        Uri fallback = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (fallback == null) {
            fallback = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
        }
        if (fallback != null && !fallback.equals(uri)) {
            playUri(fallback);
        }
    }

    private Uri parseUri(String soundUri) {
        if (soundUri != null && !soundUri.isEmpty()) {
            try {
                return Uri.parse(soundUri);
            } catch (Exception ignored) {
            }
        }
        return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
    }

    private boolean playUri(Uri uri) {
        if (uri == null) {
            return false;
        }
        MediaPlayer mediaPlayer = null;
        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            mediaPlayer.setDataSource(this, uri);
            mediaPlayer.setLooping(true);
            mediaPlayer.prepare();
            mediaPlayer.start();
            player = mediaPlayer;
            return true;
        } catch (Exception exception) {
            if (mediaPlayer != null) {
                try {
                    mediaPlayer.release();
                } catch (Exception ignored) {
                }
            }
            if (player != null) {
                try {
                    player.release();
                } catch (Exception ignored) {
                }
                player = null;
            }
            return false;
        }
    }

    private void applyAlarmVolume(int percent) {
        if (percent <= 0) {
            return;
        }
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (am == null) {
                return;
            }
            int max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM);
            if (originalVolume < 0) {
                originalVolume = am.getStreamVolume(AudioManager.STREAM_ALARM);
            }
            int target = Math.max(1, Math.round(max * Math.min(100, percent) / 100f));
            am.setStreamVolume(AudioManager.STREAM_ALARM, target, 0);
        } catch (Exception ignored) {
            /* например, режим «Не беспокоить» запрещает менять громкость */
        }
    }

    private void restoreAlarmVolume() {
        if (originalVolume < 0) {
            return;
        }
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (am != null) {
                am.setStreamVolume(AudioManager.STREAM_ALARM, originalVolume, 0);
            }
        } catch (Exception ignored) {
        }
        originalVolume = -1;
    }

    private void startFadeIn(final int seconds) {
        if (seconds <= 0 || player == null) {
            return;
        }
        final int steps = Math.max(1, seconds * 2); /* шаг раз в 0,5 с */
        fadeStep = 0;
        try {
            player.setVolume(0.04f, 0.04f);
        } catch (Exception ignored) {
            return;
        }
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                fadeStep++;
                float v = Math.min(1f, 0.04f + 0.96f * fadeStep / (float) steps);
                try {
                    if (player != null) {
                        player.setVolume(v, v);
                    }
                } catch (Exception ignored) {
                    return;
                }
                if (fadeStep < steps) {
                    handler.postDelayed(this, 500L);
                }
            }
        }, 500L);
    }

    private void scheduleAutoStop(int minutes) {
        if (minutes <= 0) {
            return;
        }
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                stopRinging();
                stopSelf();
            }
        }, minutes * 60000L);
    }

    private void releasePlayer() {
        if (player == null) {
            return;
        }
        try {
            if (player.isPlaying()) {
                player.stop();
            }
        } catch (Exception ignored) {
        }
        try {
            player.release();
        } catch (Exception ignored) {
        }
        player = null;
    }

    private void wakeUp() {
        PowerManager manager = (PowerManager) getSystemService(POWER_SERVICE);
        if (manager == null) {
            return;
        }
        try {
            partialWakeLock = manager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, "CleanAlarm:ring");
            partialWakeLock.acquire(10 * 60 * 1000L);
        } catch (Exception ignored) {
        }
        try {
            screenWakeLock = manager.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                            | PowerManager.ACQUIRE_CAUSES_WAKEUP
                            | PowerManager.ON_AFTER_RELEASE,
                    "CleanAlarm:screen");
            screenWakeLock.acquire(60 * 1000L);
        } catch (Exception ignored) {
        }
    }

    private void startVibrating() {
        try {
            vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vibrator == null || !vibrator.hasVibrator()) {
                return;
            }
            long[] pattern = {0, 700, 600, 700, 900};
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
            } else {
                vibrator.vibrate(pattern, 0);
            }
        } catch (Exception ignored) {
        }
    }

    private void requestAudioFocus() {
        try {
            audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (audioManager == null) {
                return;
            }
            if (Build.VERSION.SDK_INT >= 26) {
                AudioAttributes attributes = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build();
                focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                        .setAudioAttributes(attributes)
                        .build();
                audioManager.requestAudioFocus((AudioFocusRequest) focusRequest);
            } else {
                audioManager.requestAudioFocus(null, AudioManager.STREAM_ALARM,
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
            }
        } catch (Exception ignored) {
        }
    }

    private void launchRingActivity(String soundUri) {
        try {
            Intent intent = new Intent(this, RingActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            intent.putExtra(EXTRA_SOUND, soundUri);
            startActivity(intent);
        } catch (Exception ignored) {
            /* На некоторых версиях Android запуск из фона запрещён —
               сработает full-screen intent в уведомлении. */
        }
    }

    private void stopRinging() {
        handler.removeCallbacksAndMessages(null);
        releasePlayer();
        restoreAlarmVolume();

        if (vibrator != null) {
            try {
                vibrator.cancel();
            } catch (Exception ignored) {
            }
            vibrator = null;
        }

        if (audioManager != null) {
            try {
                if (Build.VERSION.SDK_INT >= 26 && focusRequest != null) {
                    audioManager.abandonAudioFocusRequest((AudioFocusRequest) focusRequest);
                } else {
                    audioManager.abandonAudioFocus(null);
                }
            } catch (Exception ignored) {
            }
            focusRequest = null;
        }

        if (partialWakeLock != null) {
            try {
                if (partialWakeLock.isHeld()) {
                    partialWakeLock.release();
                }
            } catch (Exception ignored) {
            }
            partialWakeLock = null;
        }

        if (screenWakeLock != null) {
            try {
                if (screenWakeLock.isHeld()) {
                    screenWakeLock.release();
                }
            } catch (Exception ignored) {
            }
            screenWakeLock = null;
        }

        try {
            stopForeground(true);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onDestroy() {
        stopRinging();
        running = false;
        testing = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
