package ru.alarm.clean;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

/**
 * Короткоживущий foreground-сервис, который поднимает интернет-радио за
 * {@link RadioPrewarm#LEAD_MS} до звонка. Звука не издаёт и экран не будит:
 * только держит процесс живым, пока поток буферизуется.
 */
public class RadioPrepService extends Service {

    private static final String CHANNEL_ID = "radio_prep";
    private static final int NOTIFICATION_ID = 43;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;

    static void start(Context context, String soundUri) {
        try {
            Intent intent = new Intent(context, RadioPrepService.class);
            intent.putExtra("sound", soundUri == null ? "" : soundUri);
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception exception) {
            AlarmScheduler.logLine(context, "prewarm: сервис не запустился — "
                    + exception.getClass().getSimpleName());
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String soundUri = intent != null ? intent.getStringExtra("sound") : "";
        if (!RadioPrewarm.isStream(soundUri)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, buildNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIFICATION_ID, buildNotification());
            }
        } catch (Exception exception) {
            /* Android 12+ может запретить foreground-запуск из фона; тогда
               будильник просто сработает обычным путём с запасным звуком. */
            AlarmScheduler.logLine(this, "prewarm: foreground не разрешён — "
                    + exception.getClass().getSimpleName());
            stopSelf();
            return START_NOT_STICKY;
        }

        acquireWakeLock();
        RadioPrewarm.begin(this, soundUri);

        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                stopSelf();
            }
        }, RadioPrewarm.LEAD_MS + 4000L);

        return START_NOT_STICKY;
    }

    private void acquireWakeLock() {
        if (wakeLock != null) {
            return;
        }
        try {
            PowerManager manager = (PowerManager) getSystemService(POWER_SERVICE);
            if (manager == null) {
                return;
            }
            wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "cleanalarm:radio-prep");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(RadioPrewarm.LEAD_MS + 6000L);
        } catch (Exception ignored) {
        }
    }

    private void releaseWakeLock() {
        if (wakeLock == null) {
            return;
        }
        try {
            if (wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Exception ignored) {
        }
        wakeLock = null;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        releaseWakeLock();
        /* Если звонок уже забрал плеер, здесь ничего нет; иначе освобождаем. */
        RadioPrewarm.release();
        super.onDestroy();
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
                CHANNEL_ID, "Подготовка радио", NotificationManager.IMPORTANCE_MIN);
        channel.setDescription("Тихо готовит интернет-радио к звонку будильника");
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 44, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        return builder
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Будильник скоро сработает")
                .setContentText("Готовим интернет-радио")
                .setColor(Color.parseColor("#D8FF63"))
                .setPriority(Notification.PRIORITY_MIN)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(content)
                .build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
