package ru.alarm.clean;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

final class AlarmScheduler {

    static final String PREFS = "alarm_prefs";
    static final String ACTION_ALARM = "ru.alarm.clean.ACTION_ALARM";
    static final String ACTION_SNOOZE = "ru.alarm.clean.ACTION_SNOOZE";
    static final int REQ_ALARM = 1001;
    static final int REQ_SNOOZE = 1002;
    static final int REQ_SHOW = 1003;
    /* Резервные каналы установки (другой PendingIntent — независимый системный будильник):
       на части устройств (например, Samsung) будильник, поставленный через setAlarmClock
       в заблокированном режиме, не срабатывает, а setExactAndAllowWhileIdle — срабатывает. */
    static final int REQ_ALARM_DB = 1011;
    static final int REQ_SNOOZE_DB = 1012;
    /** Сколько времени после пропущенного срабатывания ещё звеним при разблокировке. */
    static final long MISSED_WINDOW_MS = 15 * 60 * 1000L;

    private AlarmScheduler() {
    }

    /**
     * Настройки храним в device-protected storage (доступно сразу после загрузки,
     * до разблокировки телефона). Это нужно, чтобы будильник восстанавливался
     * по LOCKED_BOOT_COMPLETED, когда пользователь ещё не ввёл пароль.
     */
    static SharedPreferences prefs(Context context) {
        Context storage = context;
        if (Build.VERSION.SDK_INT >= 24) {
            try {
                storage = context.createDeviceProtectedStorageContext();
            } catch (Exception ignored) {
                /* остаёмся в обычном хранилище */
            }
        }
        return storage.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Переносит сохранённые настройки из обычного (ключевого) хранилища
     * в device-protected после обновления приложения. Вызывать при запуске
     * активности, когда устройство разблокировано.
     */
    static void migrateToDeviceProtected(Context context) {
        if (Build.VERSION.SDK_INT < 24) {
            return;
        }
        try {
            Context device = context.createDeviceProtectedStorageContext();
            SharedPreferences devicePrefs = device.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (!devicePrefs.getAll().isEmpty()) {
                return;
            }
            SharedPreferences oldPrefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            Map<String, ?> values = oldPrefs.getAll();
            if (values.isEmpty()) {
                return;
            }
            SharedPreferences.Editor editor = devicePrefs.edit();
            for (Map.Entry<String, ?> entry : values.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                if (value instanceof String) {
                    editor.putString(key, (String) value);
                } else if (value instanceof Integer) {
                    editor.putInt(key, (Integer) value);
                } else if (value instanceof Long) {
                    editor.putLong(key, (Long) value);
                } else if (value instanceof Float) {
                    editor.putFloat(key, (Float) value);
                } else if (value instanceof Boolean) {
                    editor.putBoolean(key, (Boolean) value);
                } else if (value instanceof Set) {
                    //noinspection unchecked
                    editor.putStringSet(key, (Set<String>) value);
                }
            }
            editor.commit();
        } catch (Exception ignored) {
        }
    }

    /** Следующее срабатывание по сохранённым настройкам (учитывает дни недели и пропуск). */
    static long computeTrigger(SharedPreferences prefs, long now) {
        long after = Math.max(now, prefs.getLong("skipBefore", 0));
        return NextAlarm.compute(after,
                prefs.getInt("hour", 7), prefs.getInt("minute", 30),
                prefs.getBoolean("repeat", false),
                prefs.getInt("days", NextAlarm.ALL_DAYS),
                TimeZone.getDefault());
    }

    /** Включает будильник по уже сохранённым настройкам. Возвращает время срабатывания. */
    static long scheduleFromPrefs(Context context) {
        SharedPreferences prefs = prefs(context);
        long triggerAt = computeTrigger(prefs, System.currentTimeMillis());
        prefs.edit()
                .putBoolean("enabled", true)
                .putLong("triggerAt", triggerAt)
                .putLong("snoozeAt", 0)
                .putInt("snoozeCount", 0)
                .apply();
        cancelPending(context, REQ_SNOOZE, ACTION_SNOOZE);
        setAlarmDual(context, triggerAt, REQ_ALARM, ACTION_ALARM, REQ_ALARM_DB);
        return triggerAt;
    }

    static void scheduleSnooze(Context context, long triggerAt, String soundUri) {
        prefs(context).edit()
                .putLong("snoozeAt", triggerAt)
                .putString("snoozeSound", soundUri == null ? "" : soundUri)
                .apply();
        setAlarmDual(context, triggerAt, REQ_SNOOZE, ACTION_SNOOZE, REQ_SNOOZE_DB);
    }

    /** После срабатывания повторяющегося будильника — ставим следующий. */
    static void scheduleNextRepeat(Context context) {
        SharedPreferences prefs = prefs(context);
        long triggerAt = computeTrigger(prefs, System.currentTimeMillis() + 1000);
        prefs.edit().putBoolean("enabled", true).putLong("triggerAt", triggerAt).apply();
        setAlarmDual(context, triggerAt, REQ_ALARM, ACTION_ALARM, REQ_ALARM_DB);
    }

    static void cancelAll(Context context) {
        cancelPending(context, REQ_ALARM, ACTION_ALARM);
        cancelPending(context, REQ_ALARM_DB, ACTION_ALARM);
        cancelPending(context, REQ_SNOOZE, ACTION_SNOOZE);
        cancelPending(context, REQ_SNOOZE_DB, ACTION_SNOOZE);
        prefs(context).edit()
                .putBoolean("enabled", false)
                .putLong("triggerAt", 0)
                .putLong("snoozeAt", 0)
                .putLong("skipBefore", 0)
                .apply();
    }

    static void restore(Context context, String reason) {
        SharedPreferences prefs = prefs(context);
        long now = System.currentTimeMillis();
        long snoozeAt = prefs.getLong("snoozeAt", 0);
        String snoozeSound = prefs.getString("snoozeSound", prefs.getString("soundUri", ""));
        boolean enabled = prefs.getBoolean("enabled", false);
        long triggerAt = prefs.getLong("triggerAt", 0);
        boolean repeat = prefs.getBoolean("repeat", false);
        int hour = prefs.getInt("hour", 7);
        int minute = prefs.getInt("minute", 30);
        int minutes = (int) Math.max(0, (triggerAt - now) / 60000);
        logLine(context, reason + ": включён=" + enabled + ", через " + minutes + " мин, повтор=" + repeat);

        if (enabled) {
            if (repeat) {
                /* Пересчитываем по местному времени: корректно и после смены часового пояса. */
                long next = computeTrigger(prefs, now);
                /* Дозвон: если время сработавшего пропуск — оно недавно. */
                if (triggerAt > 0 && triggerAt <= now && now - triggerAt <= MISSED_WINDOW_MS) {
                    logLine(context, reason + ": пропущен " + (now - triggerAt) / 1000
                            + " с назад (телефон был заблокирован) — звоню сейчас");
                    try {
                        AlarmService.start(context, prefs.getString("soundUri", ""), false);
                    } catch (Exception exception) {
                        logLine(context, reason + ": дозвон не удался: " + exception);
                    }
                }
                prefs.edit().putLong("triggerAt", next).apply();
                setAlarmDual(context, next, REQ_ALARM, ACTION_ALARM, REQ_ALARM_DB);
                logLine(context, reason + ": повтор → следующий через " + (next - now) / 60000 + " мин");
            } else if (triggerAt > now) {
                /* Время ещё не наступило — восстанавливаем будильник. */
                setAlarmDual(context, triggerAt, REQ_ALARM, ACTION_ALARM, REQ_ALARM_DB);
                logLine(context, reason + ": поставлен на " + (triggerAt - now) / 60000 + " мин");
            } else if (triggerAt > 0 && now - triggerAt <= MISSED_WINDOW_MS) {
                /* Время прошло совсем недавно (будильник не успел сработать
                   в заблокированном режиме) — звоним сейчас при разблокировке. */
                logLine(context, reason + ": пропущен " + (now - triggerAt) / 1000
                        + " с назад (телефон был заблокирован) — звоню сейчас");
                try {
                    AlarmService.start(context, prefs.getString("soundUri", ""), false);
                } catch (Exception exception) {
                    logLine(context, reason + ": дозвон не удался: " + exception);
                }
                prefs.edit()
                        .putBoolean("enabled", false)
                        .putLong("triggerAt", 0)
                        .putLong("lastFiredAt", now)
                        .apply();
            } else {
                /* Время давно прошло — будильник должен был прозвенеть до перезагрузки.
                   Выключаем, чтобы не звонить с опозданием. */
                logLine(context, reason + ": время будильника уже давно прошло — выключаю (пропуск)");
                prefs.edit()
                        .putBoolean("enabled", false)
                        .putLong("triggerAt", 0)
                        .putLong("lastFiredAt", now)
                        .apply();
            }
        } else {
            logLine(context, reason + ": будильник выключен — ничего не ставлю");
        }
        if (snoozeAt > now) {
            setAlarmDual(context, snoozeAt, REQ_SNOOZE, ACTION_SNOOZE, REQ_SNOOZE_DB);
            logLine(context, reason + ": восстанавливаю отложенный звонок");
        }
    }

    /** Совместимость с прежними вызовами. */
    static void restore(Context context) {
        restore(context, "restore");
    }

    private static PendingIntent pending(Context context, int requestCode, String action) {
        Intent intent = new Intent(context, AlarmReceiver.class);
        intent.setAction(action);
        return PendingIntent.getBroadcast(context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void cancelPending(Context context, int requestCode, String action) {
        try {
            PendingIntent pendingIntent = pending(context, requestCode, action);
            AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (manager != null) {
                manager.cancel(pendingIntent);
            }
            pendingIntent.cancel();
        } catch (Exception ignored) {
        }
    }

    /* ————— Диагностика: журнал шагов в device-protected storage ————— */

    static void logLine(Context context, String line) {
        try {
            SharedPreferences prefs = prefs(context);
            String old = prefs.getString("diag", "");
            String stamp = new java.text.SimpleDateFormat("dd.MM HH:mm:ss",
                    java.util.Locale.US).format(new java.util.Date());
            StringBuilder buffer = new StringBuilder();
            String[] lines = old.split("\n");
            int from = Math.max(0, lines.length - 60); /* держим ~60 последних строк */
            for (int i = from; i < lines.length; i++) {
                if (!lines[i].isEmpty()) {
                    buffer.append(lines[i]).append('\n');
                }
            }
            buffer.append(stamp).append(" | ").append(line).append('\n');
            /* commit, а не apply: строка должна дожить даже при сбое процесса. */
            prefs.edit().putString("diag", buffer.toString()).commit();
        } catch (Exception ignored) {
        }
    }

    /** Возвращает текст журнала для отображения в интерфейсе. */
    static String diagLog(Context context) {
        try {
            return prefs(context).getString("diag", "");
        } catch (Exception ignored) {
            return "";
        }
    }

    static void clearDiag(Context context) {
        try {
            prefs(context).edit().putString("diag", "").apply();
        } catch (Exception ignored) {
        }
    }

    /**
     * Ставит будильник двумя независимыми каналами: основной через setAlarmClock,
     * резервный через setExactAndAllowWhileIdle. Сработает тот, который система
     * выполнит в текущем режиме; от двойного звонка защищает дедупликация
     * в AlarmReceiver.
     */
    static void setAlarmDual(Context context, long triggerAt, int mainReq, String action, int backupReq) {
        String main = setAlarm(context, triggerAt, pending(context, mainReq, action), true);
        String backup = setAlarm(context, triggerAt, pending(context, backupReq, action), false);
        logLine(context, "двойная постановка: канал1=" + main + ", канал2=" + backup);
    }

    /**
     * Устанавливает будильник с надёжным фолбэком.
     * Возвращает название использованного способа (для журнала).
     */
    private static String setAlarm(Context context, long triggerAt, PendingIntent operation) {
        return setAlarm(context, triggerAt, operation, true);
    }

    private static String setAlarm(Context context, long triggerAt, PendingIntent operation,
                                   boolean withClock) {
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager == null) {
            logLine(context, "setAlarm: AlarmManager недоступен");
            return "none";
        }
        /* 1. setAlarmClock — самый надёжный: значок в статус-баре, не засыпает в Doze. */
        if (withClock) {
            try {
                Intent show = new Intent(context, MainActivity.class);
                PendingIntent showIntent = PendingIntent.getActivity(context, REQ_SHOW, show,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                manager.setAlarmClock(new AlarmManager.AlarmClockInfo(triggerAt, showIntent), operation);
                return "setAlarmClock";
            } catch (Exception exception) {
                logLine(context, "setAlarmClock: " + exception.getClass().getSimpleName()
                        + (exception.getMessage() != null ? " " + exception.getMessage() : ""));
            }
        }
        /* 2. setExactAndAllowWhileIdle — точное время, работает в Doze, но требует
           разрешения на точные будильники на Android 12+. */
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                if (manager.canScheduleExactAlarms()) {
                    manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation);
                    return "setExactAndAllowWhileIdle";
                }
            } else {
                manager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, operation);
                return "setExact";
            }
        } catch (Exception exception) {
            logLine(context, "setExact*: " + exception.getClass().getSimpleName()
                    + (exception.getMessage() != null ? " " + exception.getMessage() : ""));
        }
        /* 3. setAndAllowWhileIdle — не требует разрешений, работает в Doze,
           но время срабатывания может быть отложено системой. */
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation);
                return "setAndAllowWhileIdle";
            }
        } catch (Exception exception) {
            logLine(context, "setAndAllowWhileIdle: " + exception.getClass().getSimpleName()
                    + (exception.getMessage() != null ? " " + exception.getMessage() : ""));
        }
        /* 4. set — последний фолбэк, может быть отложен в режиме экономии энергии. */
        try {
            manager.set(AlarmManager.RTC_WAKEUP, triggerAt, operation);
            return "set";
        } catch (Exception exception) {
            logLine(context, "set: " + exception.getClass().getSimpleName()
                    + (exception.getMessage() != null ? " " + exception.getMessage() : ""));
            return "failed";
        }
    }
}
