package ru.alarm.clean;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        String action = intent.getAction();
        AlarmScheduler.logLine(context, "BootReceiver получил: " + action);
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.LOCKED_BOOT_COMPLETED".equals(action)
                || "android.intent.action.TIME_SET".equals(action)
                || Intent.ACTION_TIMEZONE_CHANGED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            try {
                AlarmScheduler.restore(context,
                        "android.intent.action.LOCKED_BOOT_COMPLETED".equals(action) ? "boot(заблокирован)" : "boot");
            } catch (Exception exception) {
                AlarmScheduler.logLine(context, "boot: ОШИБКА " + exception);
            }
        }
    }
}
