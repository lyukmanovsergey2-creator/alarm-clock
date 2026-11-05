package ru.alarm.clean;

import java.util.Calendar;
import java.util.TimeZone;

/** Чистая логика расчёта времени срабатывания (без зависимостей от Android). */
final class NextAlarm {

    /** Маска дней: бит 0 = понедельник ... бит 6 = воскресенье. */
    static final int ALL_DAYS = 0x7F;

    private NextAlarm() {
    }

    static int bitForCalendarDay(int calendarDayOfWeek) {
        /* Calendar: SUNDAY=1, MONDAY=2 ... SATURDAY=7 */
        int index = (calendarDayOfWeek + 5) % 7; /* пн=0 ... вс=6 */
        return 1 << index;
    }

    /**
     * Ближайший момент строго после {@code after}.
     * Одноразовый будильник игнорирует маску дней.
     */
    static long compute(long after, int hour, int minute, boolean repeat, int daysMask,
                        TimeZone zone) {
        int mask = (daysMask & ALL_DAYS) == 0 ? ALL_DAYS : (daysMask & ALL_DAYS);
        Calendar base = Calendar.getInstance(zone);
        base.setTimeInMillis(after);
        for (int offset = 0; offset <= 8; offset++) {
            Calendar candidate = (Calendar) base.clone();
            candidate.set(Calendar.HOUR_OF_DAY, hour);
            candidate.set(Calendar.MINUTE, minute);
            candidate.set(Calendar.SECOND, 0);
            candidate.set(Calendar.MILLISECOND, 0);
            candidate.add(Calendar.DAY_OF_YEAR, offset);
            if (candidate.getTimeInMillis() <= after) {
                continue;
            }
            if (!repeat || (mask & bitForCalendarDay(candidate.get(Calendar.DAY_OF_WEEK))) != 0) {
                return candidate.getTimeInMillis();
            }
        }
        return after + 24L * 60 * 60 * 1000;
    }
}
