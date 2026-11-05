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
            /* Фильтр опоздавших доставок: после «Отключить совсем» (или выключения)
               пользовательские настройки enabled=false, поэтому любая запоздавшая
               доставка каналов игнорируется и заново не звонит. */
            if (!prefs.getBoolean("enabled", false)) {
                AlarmScheduler.logLine(context, "ALARM при выключенном будильнике — игнорирую (опоздавшая доставка)");
                AlarmScheduler.cancelAll(context);
                return;
            }
            /* Дедупликация: будильник ставится двумя каналами (setAlarmClock +
               setExactAndAllowWhileIdle). Если система выполнила оба — второй
               срабатыванием в течение 90 с игнорируем, чтобы не звонить дважды. */
            long now = System.currentTimeMillis();
            long lastFired = prefs.getLong("lastFiredAt", 0);
            if (lastFired > 0 && now - lastFired < 90_000) {
                AlarmScheduler.logLine(context, "ALARM: дубликат через " + (now - lastFired) / 1000
                        + " с — пропускаю");
                return;
            }
            boolean repeat = prefs.getBoolean("repeat", false);
            String sound = prefs.getString("soundUri", "");
            AlarmScheduler.logLine(context, "ALARM СРАБОТАЛ, repeat=" + repeat
                    + ", звук=" + (sound == null || sound.isEmpty() ? "по умолчанию" : "свой"));

            prefs.edit()
                    .putLong("lastFiredAt", System.currentTimeMillis())
                    .putInt("snoozeCount", 0)
                    .apply();

            if (repeat) {
                AlarmScheduler.scheduleNextRepeat(context);
            } else {
                prefs.edit()
                        .putBoolean("enabled", false)
                        .putLong("triggerAt", 0)
                        .putLong("snoozeAt", 0)
                        .apply();
            }
            try {
                AlarmService.start(context, sound, false);
                AlarmScheduler.logLine(context, "ALARM: сервис запущен");
            } catch (Exception exception) {
                AlarmScheduler.logLine(context, "ALARM: сервис НЕ запустился: " + exception);
            }
        } else if (AlarmScheduler.ACTION_SNOOZE.equals(action)) {
            String sound = prefs.getString("snoozeSound", prefs.getString("soundUri", ""));
            AlarmScheduler.logLine(context, "SNOOZE сработал");
            prefs.edit().putLong("snoozeAt", 0).apply();
            try {
                AlarmService.start(context, sound, false);
            } catch (Exception exception) {
                AlarmScheduler.logLine(context, "snooze: сервис НЕ запустился: " + exception);
            }
        }
    }
}
