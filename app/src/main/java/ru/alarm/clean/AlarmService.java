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
    static final String EXTRA_ALARM_ID = AlarmScheduler.EXTRA_ALARM_ID;

    private static final String CHANNEL_ID = "alarm_channel";
    private static final int NOTIFICATION_ID = 42;

    static volatile boolean running = false;
    /** Пробный звонок из настроек: не трогает реальное расписание. */
    static volatile boolean testing = false;
    /** Какой будильник сейчас звонит (id) — -1, если звонка нет. */
    static volatile int currentAlarmId = -1;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int originalVolume = -1;
    private int fadeStep = 0;

    private MediaPlayer player;
    /* Поток радио: сначала prepareAsync, потом подмена запасного звука. */
    private MediaPlayer streamPlayer;
    private String streamUrl;
    private Runnable streamTimeout;
    private int fadeSteps;
    private static final long STREAM_TIMEOUT_MS = 8000L;
    private PowerManager.WakeLock partialWakeLock;
    private PowerManager.WakeLock screenWakeLock;
    private Vibrator vibrator;
    private AudioManager audioManager;
    /* Object, а не AudioFocusRequest: класс появился только в API 26,
       а minSdk приложения — 23. */
    private Object focusRequest;

    static void start(Context context, String soundUri, boolean test, int alarmId) {
        Intent intent = new Intent(context, AlarmService.class);
        intent.setAction(ACTION_START);
        intent.putExtra(EXTRA_SOUND, soundUri == null ? "" : soundUri);
        intent.putExtra(EXTRA_TEST, test);
        intent.putExtra(EXTRA_ALARM_ID, alarmId);
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
        SharedPreferences prefs = AlarmScheduler.prefs(this);
        /* id звонящего будильника: из extra, иначе — текущий (для кнопок шторки,
           которые не передают id). */
        int id = intent != null ? intent.getIntExtra(EXTRA_ALARM_ID, currentAlarmId) : currentAlarmId;
        if (ACTION_START.equals(action) && id >= 0) {
            currentAlarmId = id;
        }

        if (ACTION_SNOOZE.equals(action)) {
            if (!testing && id >= 0) {
                int max = AlarmScheduler.alarmMaxSnoozes(prefs, id);
                int count = prefs.getInt(AlarmScheduler.key(id, "snoozeCount"), 0);
                if (max > 0 && count >= max) {
                    /* лимит исчерпан — продолжаем звонить */
                    return START_NOT_STICKY;
                }
                int minutes = Math.max(1, Math.min(120,
                        AlarmScheduler.alarmSnoozeMinutes(prefs, id)));
                long snoozeAt = System.currentTimeMillis() + minutes * 60000L;
                /* Сначала фиксируем звук сноуза, затем ставим каналы и подготовку
                   радио: подготовка читает snoozeSound. */
                prefs.edit()
                        .putInt(AlarmScheduler.key(id, "snoozeCount"), count + 1)
                        .putString(AlarmScheduler.key(id, "snoozeSound"),
                                AlarmScheduler.alarmSoundUri(prefs, id))
                        .apply();
                AlarmScheduler.scheduleSnooze(this, id, snoozeAt);
            }
            stopRinging();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_DISABLE.equals(action)) {
            if (!testing && id >= 0) {
                /* «Совсем» из шторки: тот же путь, что и кнопка на экране звонка. */
                AlarmScheduler.disableForever(this, id);
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
        Notification notification = buildNotification(soundUri, id);

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        /* Громкость, нарастание, вибрация и автоостановка — настройки
           звонящего будильника (с фолбэком на общие, если их ещё нет). */
        applyAlarmVolume(AlarmScheduler.alarmVolume(prefs, id));
        playSound(soundUri);
        startFadeIn(AlarmScheduler.alarmFadeSeconds(prefs, id));
        wakeUp();
        if (AlarmScheduler.alarmVibrate(prefs, id)) {
            startVibrating();
        }
        requestAudioFocus();
        launchRingActivity(soundUri, id);
        scheduleAutoStop(AlarmScheduler.alarmAutoStopMinutes(prefs, id));

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

    private Notification buildNotification(String soundUri, int id) {
        Intent ringIntent = new Intent(this, RingActivity.class);
        ringIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        ringIntent.putExtra(EXTRA_SOUND, soundUri);
        ringIntent.putExtra(EXTRA_ALARM_ID, id);
        PendingIntent fullScreenIntent = PendingIntent.getActivity(this, 10, ringIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent snoozeIntent = new Intent(this, AlarmService.class);
        snoozeIntent.setAction(ACTION_SNOOZE);
        snoozeIntent.putExtra(EXTRA_ALARM_ID, id);
        PendingIntent snoozePending = PendingIntent.getService(this, 21, snoozeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent disableIntent = new Intent(this, AlarmService.class);
        disableIntent.setAction(ACTION_DISABLE);
        disableIntent.putExtra(EXTRA_ALARM_ID, id);
        PendingIntent disablePending = PendingIntent.getService(this, 22, disableIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        SharedPreferences cfg = AlarmScheduler.prefs(this);
        String label = id >= 0 ? cfg.getString(AlarmScheduler.key(id, "label"), "") : "";
        int max = AlarmScheduler.alarmMaxSnoozes(cfg, id);
        boolean canSnooze = testing || max <= 0
                || (id >= 0 && cfg.getInt(AlarmScheduler.key(id, "snoozeCount"), 0) < max);
        boolean challenge = id >= 0
                && AlarmScheduler.alarmChallenge(cfg, id) && !testing;
        int minutes = Math.max(1, Math.min(120, AlarmScheduler.alarmSnoozeMinutes(cfg, id)));

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
        releaseStreamRequest();

        String primary = soundUri == null ? "" : soundUri.trim();

        /* Интернет-радио: если поток подготовлен заранее — играем сразу. */
        if (RadioPrewarm.isStream(primary)) {
            MediaPlayer ready = RadioPrewarm.take(primary);
            if (ready != null) {
                adoptStream(ready, primary);
                return;
            }
            RadioPrewarm.release();
            /* Пока грузится поток, будильник не молчит: запасной/системный звук. */
            playFallbackNow(primary);
            prepareStream(primary);
            return;
        }

        Uri uri = parseUri(primary);
        if (playUri(uri)) {
            return;
        }
        playFallbackNow(primary);
        recoverStreamFailure(primary);
    }

    /**
     * Основной звук не загрузился. Если запасной звук — радио, сначала играет
     * системная мелодия (мгновенно), а поток подключается, когда откроется.
     */
    private void recoverStreamFailure(String failedUrl) {
        if (!running) {
            return;
        }
        Uri userFallback = userFallbackUri(failedUrl);
        String fallbackUrl = userFallback == null ? null : userFallback.toString();
        if (!RadioPrewarm.isStream(fallbackUrl)) {
            return;
        }
        AlarmScheduler.logLine(this, "radio: основной поток недоступен — готовлю запасную станцию");
        prepareStream(fallbackUrl);
    }

    /** Немедленный звук на время загрузки потока и при его сбое. */
    private void playFallbackNow(String primary) {
        /* Запасной звук из настроек: срабатывает, если основной недоступен
           (нет сети, файл удалён, битая ссылка). Поток запасным быть не может:
           его нельзя включить мгновенно. */
        Uri userFallback = userFallbackUri(primary);
        if (userFallback != null && !RadioPrewarm.isStream(userFallback.toString())
                && playUri(userFallback)) {
            return;
        }

        Uri fallback = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (fallback == null) {
            fallback = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
        }
        if (fallback != null) {
            playUri(fallback);
        }
    }

    /** Готовый плеер из prewarm: просто снимаем mute, запасной не нужен. */
    private void adoptStream(MediaPlayer ready, String url) {
        try {
            ready.setOnErrorListener(streamLostListener());
            ready.setOnCompletionListener(streamCompletionListener());
            ready.setVolume(1f, 1f);
            player = ready;
            streamUrl = url;
            if (!ready.isPlaying()) {
                ready.start();
            }
            AlarmScheduler.logLine(this, "radio: поток был готов заранее — "
                    + RadioPrewarm.host(url));
        } catch (Exception exception) {
            if (player == ready) {
                player = null;
            }
            RadioPrewarm.releaseQuiet(ready);
            playFallbackNow(url);
        }
    }

    private void prepareStream(final String url) {
        MediaPlayer created = null;
        try {
            final MediaPlayer mp = new MediaPlayer();
            created = mp;
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            mp.setDataSource(url);
            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer prepared) {
                    if (prepared != streamPlayer) {
                        RadioPrewarm.releaseQuiet(prepared);
                        return;
                    }
                    streamPlayer = null;
                    cancelStreamTimeout();
                    switchToStream(prepared, url);
                }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer failed, int what, int extra) {
                    if (failed == streamPlayer) {
                        streamPlayer = null;
                    }
                    cancelStreamTimeout();
                    RadioPrewarm.releaseQuiet(failed);
                    AlarmScheduler.logLine(AlarmService.this, "radio: станция не открылась ("
                            + what + "/" + extra + ") — играет запасной");
                    recoverStreamFailure(url);
                    return true;
                }
            });
            streamPlayer = mp;
            streamUrl = url;
            mp.prepareAsync();
            streamTimeout = new Runnable() {
                @Override
                public void run() {
                    if (streamPlayer != null) {
                        RadioPrewarm.releaseQuiet(streamPlayer);
                        streamPlayer = null;
                    }
                    AlarmScheduler.logLine(AlarmService.this,
                            "radio: поток не ответил за 8 с — остаётся запасной");
                    recoverStreamFailure(url);
                }
            };
            handler.postDelayed(streamTimeout, STREAM_TIMEOUT_MS);
            AlarmScheduler.logLine(this, "radio: готовлю поток " + RadioPrewarm.host(url));
        } catch (Exception exception) {
            if (created != null) {
                if (created == streamPlayer) {
                    streamPlayer = null;
                }
                RadioPrewarm.releaseQuiet(created);
            }
            AlarmScheduler.logLine(this, "radio: не удалось начать — "
                    + exception.getClass().getSimpleName());
        }
    }

    private void switchToStream(MediaPlayer mp, String url) {
        if (!running) {
            RadioPrewarm.releaseQuiet(mp);
            return;
        }
        float volume = currentFadeVolume();
        try {
            mp.setOnErrorListener(streamLostListener());
            mp.setOnCompletionListener(streamCompletionListener());
            mp.setVolume(volume, volume);
            mp.start();
        } catch (Exception exception) {
            RadioPrewarm.releaseQuiet(mp);
            return;
        }
        /* Запасной больше не нужен. */
        releasePlayer();
        player = mp;
        streamUrl = url;
        AlarmScheduler.logLine(this, "radio: играю поток " + RadioPrewarm.host(url));
    }

    private MediaPlayer.OnErrorListener streamLostListener() {
        return new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer failed, int what, int extra) {
                if (failed != player) {
                    /* Событие от уже заменённого плеера — текущий не трогаем. */
                    RadioPrewarm.releaseQuiet(failed);
                    return true;
                }
                onStreamLost("ошибка " + what + "/" + extra);
                return true;
            }
        };
    }

    private MediaPlayer.OnCompletionListener streamCompletionListener() {
        return new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer completed) {
                if (completed != player) {
                    RadioPrewarm.releaseQuiet(completed);
                    return;
                }
                onStreamLost("поток завершился");
            }
        };
    }

    /** Радио оборвалось уже во время звонка — возвращаемся к запасному. */
    private void onStreamLost(String reason) {
        String url = streamUrl;
        streamUrl = null;
        if (player != null) {
            RadioPrewarm.releaseQuiet(player);
            player = null;
        }
        AlarmScheduler.logLine(this, "radio: " + reason + " — включаю запасной");
        playFallbackNow(url);
        recoverStreamFailure(url);
        if (player != null) {
            float volume = currentFadeVolume();
            try {
                player.setVolume(volume, volume);
            } catch (Exception ignored) {
            }
        }
    }

    private float currentFadeVolume() {
        if (fadeSteps <= 0) {
            return 1f;
        }
        return Math.min(1f, 0.04f + 0.96f * fadeStep / (float) fadeSteps);
    }

    private void cancelStreamTimeout() {
        if (streamTimeout != null) {
            handler.removeCallbacks(streamTimeout);
            streamTimeout = null;
        }
    }

    private void releaseStreamRequest() {
        cancelStreamTimeout();
        if (streamPlayer != null) {
            RadioPrewarm.releaseQuiet(streamPlayer);
            streamPlayer = null;
        }
        streamUrl = null;
    }

    private Uri userFallbackUri(String primary) {
        try {
            String raw = AlarmScheduler.prefs(this).getString("fallbackSoundUri", "");
            if (raw == null || raw.isEmpty() || raw.equals(primary)) {
                return null;
            }
            return Uri.parse(raw);
        } catch (Exception ignored) {
            return null;
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
        fadeSteps = seconds > 0 ? Math.max(1, seconds * 2) : 0;
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

    private void launchRingActivity(String soundUri, int id) {
        try {
            Intent intent = new Intent(this, RingActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            intent.putExtra(EXTRA_SOUND, soundUri);
            intent.putExtra(EXTRA_ALARM_ID, id);
            startActivity(intent);
        } catch (Exception ignored) {
            /* На некоторых версиях Android запуск из фона запрещён —
               сработает full-screen intent в уведомлении. */
        }
    }

    private void stopRinging() {
        handler.removeCallbacksAndMessages(null);
        releasePlayer();
        releaseStreamRequest();
        RadioPrewarm.release();
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
        currentAlarmId = -1;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
