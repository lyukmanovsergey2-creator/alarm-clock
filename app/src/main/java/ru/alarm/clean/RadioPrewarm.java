package ru.alarm.clean;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;

import java.util.Locale;

/**
 * Подготовка интернет-потока за несколько секунд до звонка.
 *
 * <p>Поток открывается заранее, играет без звука (громкость 0) и к моменту
 * срабатывания уже готов — будильник включает радио без задержки. Если поток
 * не успел или упал, звенит запасной звук: тишины быть не должно.</p>
 */
final class RadioPrewarm {

    /** За сколько до звонка начинаем готовить поток. */
    static final long LEAD_MS = 12000L;
    /** Сколько ждём ответа станции при подготовке. */
    private static final long PREPARE_TIMEOUT_MS = 15000L;

    private static final Handler handler = new Handler(Looper.getMainLooper());

    private static MediaPlayer player;
    private static String url;
    private static Runnable timeout;

    private RadioPrewarm() {
    }

    /** Это ссылка на поток, а не файл или системный звук? */
    static boolean isStream(String soundUri) {
        if (soundUri == null) {
            return false;
        }
        String s = soundUri.trim().toLowerCase(Locale.US);
        return s.startsWith("http://") || s.startsWith("https://");
    }

    /** Короткое имя станции для журнала: хост без схемы и пути. */
    static String host(String streamUrl) {
        if (streamUrl == null) {
            return "";
        }
        String s = streamUrl.trim().replaceFirst("(?i)^https?://", "");
        int slash = s.indexOf('/');
        if (slash >= 0) {
            s = s.substring(0, slash);
        }
        return s;
    }

    static synchronized void begin(final Context context, final String streamUrl) {
        if (!isStream(streamUrl)) {
            return;
        }
        if (player != null && streamUrl.equals(url)) {
            return;
        }
        releaseLocked();
        url = streamUrl;

        MediaPlayer mp = new MediaPlayer();
        player = mp;
        try {
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            mp.setDataSource(streamUrl);
            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer prepared) {
                    synchronized (RadioPrewarm.class) {
                        if (prepared != player) {
                            releaseQuiet(prepared);
                            return;
                        }
                        cancelTimeout();
                        try {
                            /* Тишина, но поток живёт и наполняет буфер. */
                            prepared.setVolume(0f, 0f);
                            prepared.start();
                            AlarmScheduler.logLine(context,
                                    "prewarm: поток готов — " + host(streamUrl));
                        } catch (Exception exception) {
                            releaseLocked();
                        }
                    }
                }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer failed, int what, int extra) {
                    synchronized (RadioPrewarm.class) {
                        if (failed == player) {
                            releaseLocked();
                        } else {
                            releaseQuiet(failed);
                        }
                    }
                    AlarmScheduler.logLine(context, "prewarm: станция не ответила ("
                            + what + "/" + extra + ")");
                    return true;
                }
            });
            mp.prepareAsync();
            scheduleTimeout(context);
        } catch (Exception exception) {
            releaseLocked();
            AlarmScheduler.logLine(context, "prewarm: не удалось начать — "
                    + exception.getClass().getSimpleName());
        }
    }

    /**
     * Забрать готовый плеер для звонка. Возвращает null, если поток ещё не
     * готов или это другая станция — тогда AlarmService играет запасной звук.
     */
    static synchronized MediaPlayer take(String streamUrl) {
        if (player == null || streamUrl == null || !streamUrl.equals(url)) {
            return null;
        }
        boolean playing;
        try {
            playing = player.isPlaying();
        } catch (Exception exception) {
            playing = false;
        }
        if (!playing) {
            return null;
        }
        MediaPlayer result = player;
        player = null;
        url = null;
        cancelTimeout();
        return result;
    }

    /** Освободить подготовленный поток (отмена, сбой, конец звонка). */
    static synchronized void release() {
        releaseLocked();
    }

    private static void releaseLocked() {
        cancelTimeout();
        if (player != null) {
            try {
                if (player.isPlaying()) {
                    player.stop();
                }
            } catch (Exception ignored) {
            }
            releaseQuiet(player);
            player = null;
        }
        url = null;
    }

    static void releaseQuiet(MediaPlayer mp) {
        if (mp == null) {
            return;
        }
        try {
            mp.reset();
        } catch (Exception ignored) {
        }
        try {
            mp.release();
        } catch (Exception ignored) {
        }
    }

    private static void scheduleTimeout(final Context context) {
        cancelTimeout();
        timeout = new Runnable() {
            @Override
            public void run() {
                synchronized (RadioPrewarm.class) {
                    if (player == null) {
                        return;
                    }
                    releaseLocked();
                }
                AlarmScheduler.logLine(context, "prewarm: станция молчит дольше 15 с");
            }
        };
        handler.postDelayed(timeout, PREPARE_TIMEOUT_MS);
    }

    private static void cancelTimeout() {
        if (timeout != null) {
            handler.removeCallbacks(timeout);
            timeout = null;
        }
    }
}
