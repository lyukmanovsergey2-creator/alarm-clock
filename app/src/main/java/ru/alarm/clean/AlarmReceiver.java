package ru.alarm.clean;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

public class AlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        SharedPreferences prefs = AlarmScheduler.prefs(context);

        if (AlarmScheduler.ACTION_ALARM.equals(action)) {
            /* Какой будильник сработал: id передаётся extra при постановке. */
            int id = intent.getIntExtra(AlarmScheduler.EXTRA_ALARM_ID, 0);
            String kEnabled = AlarmScheduler.key(id, "enabled");

            /* Фильтр опоздавших доставок: после «Отключить совсем» (или выключения)
               настройки будильника enabled=false, поэтому любая запоздавшая
               доставка каналов игнорируется и заново не звонит. */
            if (!prefs.getBoolean(kEnabled, false)) {
                AlarmScheduler.logLine(context, "ALARM " + id + " при выключенном будильнике — " +
                        "игнорирую (опоздавшая доставка)");
                AlarmScheduler.cancelAlarmPending(context, id);
                return;
            }

            /* Дедупликация: будильник ставится двумя каналами (setAlarmClock +
               setExactAndAllowWhileIdle). Если система выполнила оба — второй
               срабатыванием в течение 90 с игнорируем, чтобы не звонить дважды.
               Счётчик per-alarm: два разных будильника друг другу не мешают. */
            long now = System.currentTimeMillis();
            String kLast = AlarmScheduler.key(id, "lastFiredAt");
            long lastFired = prefs.getLong(kLast, 0);
            if (lastFired > 0 && now - lastFired < 90_000) {
                AlarmScheduler.logLine(context, "ALARM " + id + ": дубликат через "
                        + (now - lastFired) / 1000 + " с — пропускаю");
                return;
            }

            boolean repeat = prefs.getBoolean(AlarmScheduler.key(id, "repeat"), true);
            String sound = prefs.getString("soundUri", "");
            String label = prefs.getString(AlarmScheduler.key(id, "label"), "");
            AlarmScheduler.logLine(context, "ALARM " + id + " СРАБОТАЛ ("
                    + (label.isEmpty() ? "без названия" : label)
                    + "), repeat=" + repeat
                    + ", звук=" + (sound == null || sound.isEmpty() ? "по умолчанию" : "свой"));

            prefs.edit()
                    .putLong(kLast, System.currentTimeMillis())
                    .putInt(AlarmScheduler.key(id, "snoozeCount"), 0)
                    .apply();

            if (repeat) {
                /* Повторяющийся — сразу ставим следующий звонок. */
                AlarmScheduler.scheduleNextRepeat(context, id);
            } else {
                /* Одноразовый — гасим (но отложенный канал оставляем: сноуз ещё может звонить). */
                prefs.edit()
                        .putBoolean(kEnabled, false)
                        .putLong(AlarmScheduler.key(id, "triggerAt"), 0)
                        .apply();
                AlarmScheduler.cancelPendingAlarmOnly(context, id);
            }
            try {
                AlarmService.start(context, sound, false, id);
                AlarmScheduler.logLine(context, "ALARM " + id + ": сервис запущен");
            } catch (Exception exception) {
                AlarmScheduler.logLine(context, "ALARM " + id + ": сервис НЕ запустился: " + exception);
            }
        } else if (AlarmScheduler.ACTION_SNOOZE.equals(action)) {
            int id = intent.getIntExtra(AlarmScheduler.EXTRA_ALARM_ID, 0);
            String sound = prefs.getString(AlarmScheduler.key(id, "snoozeSound"),
                    prefs.getString("soundUri", ""));
            AlarmScheduler.logLine(context, "SNOOZE " + id + " сработал");
            prefs.edit().putLong(AlarmScheduler.key(id, "snoozeAt"), 0).apply();
            try {
                AlarmService.start(context, sound, false, id);
            } catch (Exception exception) {
                AlarmScheduler.logLine(context, "snooze " + id + ": сервис НЕ запустился: " + exception);
            }
        }
    }
}
