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

/**
 * Планировщик нескольких будильников (до 15).
 *
 * Каждый будильник хранит свои настройки в ключах вида "a&lt;ID&gt;.enabled",
 * "a&lt;ID&gt;.hour" и т.д. Список созданных будильников — в ключе "alarmIds"
 * в виде "ID;ID;ID". Общие для всех будильников настройки (звук, громкость,
 * лимиты откладывания, пример для отключения) — плоские ключи без префикса.
 */
final class AlarmScheduler {

    static final String PREFS = "alarm_prefs";
    static final String ACTION_ALARM = "ru.alarm.clean.ACTION_ALARM";
    static final String ACTION_SNOOZE = "ru.alarm.clean.ACTION_SNOOZE";
    /** Максимальное количество одновременных будильников. */
    static final int MAX_ALARMS = 15;
    /** EXTRA с номером будильника (id) для приёмника, сервиса и экрана звонка. */
    static final String EXTRA_ALARM_ID = "alarmId";

    /* Резервные каналы установки (другой PendingIntent — независимый системный
       будильник): на части устройств (например, Samsung) будильник, поставленный
       через setAlarmClock в заблокированном режиме, не срабатывает, а
       setExactAndAllowWhileIdle — срабатывает. На каждый будильник — своя пара
       requestCode (чтобы PendingIntent-ы не затирали друг друга). */
    private static final int REQ_ALARM_BASE = 1000;   /* канал1: 1000 + id*2 */
    private static final int REQ_SNOOZE_BASE = 1200;  /* канал1: 1200 + id*2 */
    /** Сколько времени после пропущенного срабатывания ещё звеним при разблокировке. */
    static final long MISSED_WINDOW_MS = 15 * 60 * 1000L;

    private AlarmScheduler() {
    }

    /* ————— Хранилище ————— */

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

    /** Ключ настройки отдельного будильника, например "a3.hour". */
    static String key(int id, String field) {
        return "a" + id + "." + field;
    }

    /** Список id созданных будильников (ключ "alarmIds", формат "ID;ID"). */
    static int[] alarmIds(SharedPreferences prefs) {
        String raw = prefs.getString("alarmIds", "");
        if (raw == null || raw.isEmpty()) {
            return new int[0];
        }
        String[] parts = raw.split(";");
        int[] ids = new int[parts.length];
        int count = 0;
        for (int i = 0; i < parts.length && count < MAX_ALARMS; i++) {
            try {
                ids[count] = Integer.parseInt(parts[i].trim());
                count++;
            } catch (NumberFormatException ignored) {
            }
        }
        int[] result = new int[count];
        System.arraycopy(ids, 0, result, 0, count);
        return result;
    }

    static java.util.List<Integer> alarmIdList(SharedPreferences prefs) {
        int[] ids = alarmIds(prefs);
        java.util.List<Integer> out = new java.util.ArrayList<Integer>(ids.length);
        for (int id : ids) {
            out.add(id);
        }
        return out;
    }

    static void writeAlarmIds(SharedPreferences.Editor editor, java.util.List<Integer> ids) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) s.append(';');
            s.append(ids.get(i));
        }
        editor.putString("alarmIds", s.toString());
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
                migrateSingleAlarm(devicePrefs);
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
            migrateSingleAlarm(devicePrefs);
        } catch (Exception ignored) {
        }
    }

    /**
     * Однократная миграция с версии 2.6 (один будильник в плоских ключах
     * enabled/hour/minute/...) в многобудильниковое хранилище: старый будильник
     * становится будильником с id 1.
     */
    private static void migrateSingleAlarm(SharedPreferences prefs) {
        if (!prefs.getString("alarmIds", "").isEmpty()) {
            return; /* уже мигрировано */
        }
        Map<String, ?> all = prefs.getAll();
        if (!all.containsKey("hour") && !all.containsKey("enabled")) {
            return; /* старых данных нет */
        }
        SharedPreferences.Editor editor = prefs.edit();
        int id = 1;
        editor.putInt(key(id, "hour"), prefs.getInt("hour", 7));
        editor.putInt(key(id, "minute"), prefs.getInt("minute", 30));
        editor.putBoolean(key(id, "enabled"), prefs.getBoolean("enabled", false));
        editor.putBoolean(key(id, "repeat"), prefs.getBoolean("repeat", true));
        editor.putInt(key(id, "days"), prefs.getInt("days", NextAlarm.ALL_DAYS));
        editor.putString(key(id, "label"), prefs.getString("label", ""));
        editor.putLong(key(id, "triggerAt"), prefs.getLong("triggerAt", 0));
        editor.putLong(key(id, "skipBefore"), prefs.getLong("skipBefore", 0));
        editor.putLong(key(id, "snoozeAt"), prefs.getLong("snoozeAt", 0));
        editor.putString(key(id, "snoozeSound"), prefs.getString("snoozeSound", ""));
        editor.putInt(key(id, "snoozeCount"), prefs.getInt("snoozeCount", 0));
        editor.putLong(key(id, "lastFiredAt"), prefs.getLong("lastFiredAt", 0));
        java.util.List<Integer> ids = new java.util.ArrayList<Integer>();
        ids.add(id);
        writeAlarmIds(editor, ids);
        editor.commit();
        logLineStatic(prefs, "миграция: старый будильник → id " + id);
    }

    /* ————— Расчёт времени ————— */

    /* ————— Настройки отдельных будильников (с фолбэком на общие) ————— */
    /* Звук, громкость, нарастание, автоостановка, откладывание, вибрация
       и «пример для отключения» теперь у каждого будильника свои. Если их
       ещё нет (обновление со старой версии) — используются прежние общие
       значения, поэтому настройки существующих будильников поменялись. */

    static String alarmSoundUri(SharedPreferences prefs, int id) {
        return prefs.getString(key(id, "soundUri"), prefs.getString("soundUri", ""));
    }

    static int alarmVolume(SharedPreferences prefs, int id) {
        return prefs.getInt(key(id, "volume"), prefs.getInt("volume", 0));
    }

    static int alarmFadeSeconds(SharedPreferences prefs, int id) {
        return prefs.getInt(key(id, "fadeSeconds"), prefs.getInt("fadeSeconds", 0));
    }

    static int alarmAutoStopMinutes(SharedPreferences prefs, int id) {
        return prefs.getInt(key(id, "autoStopMinutes"), prefs.getInt("autoStopMinutes", 10));
    }

    static int alarmSnoozeMinutes(SharedPreferences prefs, int id) {
        return prefs.getInt(key(id, "snoozeMinutes"), prefs.getInt("snoozeMinutes", 10));
    }

    static int alarmMaxSnoozes(SharedPreferences prefs, int id) {
        return prefs.getInt(key(id, "maxSnoozes"), prefs.getInt("maxSnoozes", 0));
    }

    static boolean alarmVibrate(SharedPreferences prefs, int id) {
        return prefs.getBoolean(key(id, "vibrate"), prefs.getBoolean("vibrate", true));
    }

    static boolean alarmChallenge(SharedPreferences prefs, int id) {
        return prefs.getBoolean(key(id, "challenge"), prefs.getBoolean("challenge", false));
    }

    static int alarmChallengeNumbers(SharedPreferences prefs, int id) {
        return prefs.getInt(key(id, "challengeNumbers"), prefs.getInt("challengeNumbers", 2));
    }

    private static boolean alarmRepeat(SharedPreferences prefs, int id) {
        return prefs.getBoolean(key(id, "repeat"), true);
    }

    private static long alarmSkipBefore(SharedPreferences prefs, int id) {
        return prefs.getLong(key(id, "skipBefore"), 0);
    }

    /** Следующее срабатывание будильника id (учитывает дни недели и пропуск). */
    static long computeTrigger(SharedPreferences prefs, int id, long now) {
        long after = Math.max(now, alarmSkipBefore(prefs, id));
        return NextAlarm.compute(after,
                prefs.getInt(key(id, "hour"), 7), prefs.getInt(key(id, "minute"), 30),
                alarmRepeat(prefs, id),
                prefs.getInt(key(id, "days"), NextAlarm.ALL_DAYS),
                TimeZone.getDefault());
    }

    /* ————— Постановка ————— */

    private static void log(Context context, String line) {
        logLine(context, line);
    }

    private static void logLineStatic(SharedPreferences prefs, String line) {
        try {
            String old = prefs.getString("diag", "");
            String stamp = new java.text.SimpleDateFormat("dd.MM HH:mm:ss",
                    java.util.Locale.US).format(new java.util.Date());
            StringBuilder buffer = new StringBuilder();
            String[] lines = old.split("\n");
            int from = Math.max(0, lines.length - 150);
            for (int i = from; i < lines.length; i++) {
                if (!lines[i].isEmpty()) buffer.append(lines[i]).append('\n');
            }
            buffer.append(stamp).append(" | ").append(line).append('\n');
            prefs.edit().putString("diag", buffer.toString()).commit();
        } catch (Exception ignored) {
        }
    }

    /**
     * Ставит один будильник.
     *
     * @param fresh true — время пересчитывается заново (после изменения настроек
     *              пользователем); false — для одноразового будильника сохраняется
     *              уже записанное triggerAt (важно при восстановлении после
     *              перезагрузки, иначе одноразовый будильник уехал бы на завтра).
     * @return время срабатывания или 0, если будильник выключен.
     */
    static long scheduleAlarm(Context context, int id, boolean fresh) {
        SharedPreferences prefs = prefs(context);
        if (!prefs.getBoolean(key(id, "enabled"), false)) {
            cancelPending(context, REQ_ALARM_BASE + id * 2, ACTION_ALARM, id);
            cancelPending(context, REQ_ALARM_BASE + id * 2 + 1, ACTION_ALARM, id);
            return 0L;
        }
        long now = System.currentTimeMillis();
        long stored = prefs.getLong(key(id, "triggerAt"), 0);
        long triggerAt;
        if (!fresh && !alarmRepeat(prefs, id) && stored > now) {
            triggerAt = stored;
        } else {
            triggerAt = computeTrigger(prefs, id, now);
        }
        prefs.edit().putLong(key(id, "triggerAt"), triggerAt).apply();
        /* Старое откладывание больше не нужно: будильник заново поставлен. */
        prefs.edit().putLong(key(id, "snoozeAt"), 0).apply();
        cancelPending(context, REQ_SNOOZE_BASE + id * 2, ACTION_SNOOZE, id);
        cancelPending(context, REQ_SNOOZE_BASE + id * 2 + 1, ACTION_SNOOZE, id);
        setAlarmDual(context, triggerAt, REQ_ALARM_BASE + id * 2, REQ_ALARM_BASE + id * 2 + 1,
                ACTION_ALARM, id);
        return triggerAt;
    }

    /** Ставит все будильники из списка. Возвращает ближайшее время срабатывания. */
    static long scheduleAll(Context context) {
        SharedPreferences prefs = prefs(context);
        int[] ids = alarmIds(prefs);
        long earliest = 0L;
        for (int id : ids) {
            long t = scheduleAlarm(context, id, false);
            if (t > 0 && (earliest == 0 || t < earliest)) {
                earliest = t;
            }
        }
        return earliest;
    }

    /** Откладывание одного будильника: ставит отдельный канал сноуза. */
    static void scheduleSnooze(Context context, int id, long triggerAt) {
        SharedPreferences prefs = prefs(context);
        prefs.edit().putLong(key(id, "snoozeAt"), triggerAt).apply();
        setAlarmDual(context, triggerAt, REQ_SNOOZE_BASE + id * 2,
                REQ_SNOOZE_BASE + id * 2 + 1, ACTION_SNOOZE, id);
    }

    /** После срабатывания повторяющегося будильника — ставим следующий. */
    static void scheduleNextRepeat(Context context, int id) {
        SharedPreferences prefs = prefs(context);
        long triggerAt = computeTrigger(prefs, id, System.currentTimeMillis() + 1000);
        prefs.edit().putBoolean(key(id, "enabled"), true)
                .putLong(key(id, "triggerAt"), triggerAt)
                .putLong(key(id, "skipBefore"), 0L)
                .apply();
        setAlarmDual(context, triggerAt, REQ_ALARM_BASE + id * 2,
                REQ_ALARM_BASE + id * 2 + 1, ACTION_ALARM, id);
    }

    /** Снимает PendingIntent-ы конкретного будильника. */
    static void cancelAlarmPending(Context context, int id) {
        cancelPending(context, REQ_ALARM_BASE + id * 2, ACTION_ALARM, id);
        cancelPending(context, REQ_ALARM_BASE + id * 2 + 1, ACTION_ALARM, id);
        cancelPending(context, REQ_SNOOZE_BASE + id * 2, ACTION_SNOOZE, id);
        cancelPending(context, REQ_SNOOZE_BASE + id * 2 + 1, ACTION_SNOOZE, id);
    }

    /** Полностью удаляет будильник: снимает каналы, чистит настройки, убирает из списка. */
    static void removeAlarm(Context context, int id) {
        cancelAlarmPending(context, id);
        SharedPreferences prefs = prefs(context);
        SharedPreferences.Editor editor = prefs.edit();
        editor.remove(key(id, "enabled")).remove(key(id, "hour")).remove(key(id, "minute"))
                .remove(key(id, "repeat")).remove(key(id, "days")).remove(key(id, "label"))
                .remove(key(id, "triggerAt")).remove(key(id, "skipBefore"))
                .remove(key(id, "snoozeAt")).remove(key(id, "snoozeSound"))
                .remove(key(id, "snoozeCount")).remove(key(id, "lastFiredAt"))
                .remove(key(id, "soundUri")).remove(key(id, "volume"))
                .remove(key(id, "fadeSeconds")).remove(key(id, "autoStopMinutes"))
                .remove(key(id, "snoozeMinutes")).remove(key(id, "maxSnoozes"))
                .remove(key(id, "vibrate")).remove(key(id, "challenge"))
                .remove(key(id, "challengeNumbers"));
        java.util.List<Integer> ids = alarmIdList(prefs);
        ids.remove(Integer.valueOf(id));
        writeAlarmIds(editor, ids);
        editor.apply();
    }

    /** «Совсем» для одного будильника: выключает и снимает все его каналы. */
    static void disableForever(Context context, int id) {
        SharedPreferences prefs = prefs(context);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean(key(id, "enabled"), false)
                .putLong(key(id, "triggerAt"), 0)
                .putLong(key(id, "snoozeAt"), 0)
                .putLong(key(id, "lastFiredAt"), System.currentTimeMillis())
                .putLong(key(id, "skipBefore"), 0);
        editor.apply();
        cancelAlarmPending(context, id);
        log(context, "будильник " + id + " отключён совсем");
    }

    /**
     * Снимает только каналы звонка конкретного будильника (canal1/canal2),
     * отложенный (сноуз) канал не трогает.
     */
    static void cancelPendingAlarmOnly(Context context, int id) {
        cancelPending(context, REQ_ALARM_BASE + id * 2, ACTION_ALARM, id);
        cancelPending(context, REQ_ALARM_BASE + id * 2 + 1, ACTION_ALARM, id);
    }

    /** «На сутки» для повторяющегося будильника: пропустить ближайший звонок. */
    static void skipNext(Context context, int id) {
        SharedPreferences prefs = prefs(context);
        long next = computeTrigger(prefs, id, System.currentTimeMillis());
        if (next <= 0) return;
        prefs.edit().putLong(key(id, "skipBefore"), next).apply();
        scheduleAlarm(context, id, true);
        log(context, "будильник " + id + ": ближайший " + (next - System.currentTimeMillis()) / 60000
                + " мин пропущен");
    }

    /** Снимает все будильники (без удаления настроек, например для «теста»). */
    static void cancelAllRinging(Context context) {
        SharedPreferences prefs = prefs(context);
        int[] ids = alarmIds(prefs);
        for (int id : ids) {
            cancelAlarmPending(context, id);
        }
    }

    /* ————— Восстановление после перезагрузки ————— */

    static void restore(Context context, String reason) {
        SharedPreferences prefs = prefs(context);
        long now = System.currentTimeMillis();
        int[] ids = alarmIds(prefs);
        StringBuilder summary = new StringBuilder();
        for (int id : ids) {
            boolean enabled = prefs.getBoolean(key(id, "enabled"), false);
            long triggerAt = prefs.getLong(key(id, "triggerAt"), 0);
            boolean repeat = alarmRepeat(prefs, id);
            long next = 0;
            if (enabled) {
                if (repeat) {
                    /* Пересчитываем по местному времени: корректно и после смены часового пояса. */
                    next = computeTrigger(prefs, id, now);
                    /* Дозвон: если время сработавшего пропуск — оно недавно. */
                    if (triggerAt > 0 && triggerAt <= now && now - triggerAt <= MISSED_WINDOW_MS
                            && !AlarmService.running) {
                        log(context, reason + ": будильник " + id + " пропущен "
                                + (now - triggerAt) / 1000 + " с назад — звоню сейчас");
                        fireMissed(context, id, prefs);
                    }
                    prefs.edit().putLong(key(id, "triggerAt"), next).apply();
                    setAlarmDual(context, next, REQ_ALARM_BASE + id * 2,
                            REQ_ALARM_BASE + id * 2 + 1, ACTION_ALARM, id);
                    if (next > 0) {
                        summary.append(id).append("→").append((next - now) / 60000).append("м ");
                    }
                } else if (triggerAt > now) {
                    setAlarmDual(context, triggerAt, REQ_ALARM_BASE + id * 2,
                            REQ_ALARM_BASE + id * 2 + 1, ACTION_ALARM, id);
                    summary.append(id).append("→").append((triggerAt - now) / 60000).append("м ");
                } else if (triggerAt > 0 && now - triggerAt <= MISSED_WINDOW_MS) {
                    /* Время прошло совсем недавно (будильник не успел сработать
                       в заблокированном режиме) — звоним сейчас при разблокировке. */
                    summary.append(id).append("→дозвон ");
                    fireMissed(context, id, prefs);
                    prefs.edit()
                            .putBoolean(key(id, "enabled"), false)
                            .putLong(key(id, "triggerAt"), 0)
                            .apply();
                } else {
                    /* Время давно прошло — будильник должен был прозвенеть до перезагрузки. */
                    prefs.edit()
                            .putBoolean(key(id, "enabled"), false)
                            .putLong(key(id, "triggerAt"), 0)
                            .apply();
                    summary.append(id).append("→выкл ");
                }
            }
            /* Отложенный звонок тоже восстанавливаем. */
            long snoozeAt = prefs.getLong(key(id, "snoozeAt"), 0);
            if (snoozeAt > now) {
                setAlarmDual(context, snoozeAt, REQ_SNOOZE_BASE + id * 2,
                        REQ_SNOOZE_BASE + id * 2 + 1, ACTION_SNOOZE, id);
                summary.append(id).append("→сноуз ");
            }
        }
        log(context, reason + ": будильников " + ids.length + (summary.length() > 0
                ? " — " + summary.toString().trim() : " (все выключены)"));
    }

    private static void fireMissed(Context context, int id, SharedPreferences prefs) {
        try {
            AlarmService.start(context, alarmSoundUri(prefs, id), false, id);
        } catch (Exception exception) {
            log(context, "дозвон не удался: " + exception);
        }
    }

    /** Совместимость с прежними вызовами. */
    static void restore(Context context) {
        restore(context, "restore");
    }

    private static PendingIntent pending(Context context, int requestCode, String action, int id) {
        Intent intent = new Intent(context, AlarmReceiver.class);
        intent.setAction(action);
        intent.putExtra(EXTRA_ALARM_ID, id);
        return PendingIntent.getBroadcast(context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void cancelPending(Context context, int requestCode, String action, int id) {
        try {
            PendingIntent pendingIntent = pending(context, requestCode, action, id);
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
            logLineStatic(prefs, line);
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
     * выполнит в текущем состоянии; от двойного звонка защищает дедупликация
     * в AlarmReceiver.
     */
    private static void setAlarmDual(Context context, long triggerAt, int mainReq, int backupReq,
                                     String action, int id) {
        log(context, "будильник " + id + " на " + (triggerAt - System.currentTimeMillis()) / 60000
                + " мин: канал1=" + setAlarm(context, triggerAt, pending(context, mainReq, action, id), true)
                + ", канал2=" + setAlarm(context, triggerAt, pending(context, backupReq, action, id), false));
    }

    /**
     * Устанавливает будильник с надёжным фолбэком.
     * Возвращает название использованного способа (для журнала).
     */
    private static String setAlarm(Context context, long triggerAt, PendingIntent operation,
                                   boolean withClock) {
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager == null) {
            log(context, "setAlarm: AlarmManager недоступен");
            return "none";
        }
        /* 1. setAlarmClock — самый надёжный: значок в статус-баре, не засыпает в Doze. */
        if (withClock) {
            try {
                Intent show = new Intent(context, MainActivity.class);
                PendingIntent showIntent = PendingIntent.getActivity(context, 10, show,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                manager.setAlarmClock(new AlarmManager.AlarmClockInfo(triggerAt, showIntent), operation);
                return "setAlarmClock";
            } catch (Exception exception) {
                log(context, "setAlarmClock: " + exception.getClass().getSimpleName()
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
            log(context, "setExact*: " + exception.getClass().getSimpleName()
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
            log(context, "setAndAllowWhileIdle: " + exception.getClass().getSimpleName()
                    + (exception.getMessage() != null ? " " + exception.getMessage() : ""));
        }
        /* 4. set — последний фолбэк, может быть отложен в режиме экономии энергии. */
        try {
            manager.set(AlarmManager.RTC_WAKEUP, triggerAt, operation);
            return "set";
        } catch (Exception exception) {
            log(context, "set: " + exception.getClass().getSimpleName()
                    + (exception.getMessage() != null ? " " + exception.getMessage() : ""));
            return "failed";
        }
    }
}
