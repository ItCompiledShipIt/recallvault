package com.irwinlabs.recallvault;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * Day grouping for the vault feed (v0.48.0): the feed reads like
 * a journal - Today, Yesterday, then dated days. Pure calendar
 * math, kept apart from the Activity so it is testable.
 */
public final class DayGroup {

    private DayGroup() {}

    public static long startOfDay(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    public static String label(long ms, long nowMs) {
        long day = startOfDay(ms);
        if (day == startOfDay(nowMs)) return "Today";
        if (day == startOfDay(nowMs - 24L * 3600 * 1000)) return "Yesterday";
        return new SimpleDateFormat("EEE, MMM d", Locale.US).format(new Date(ms));
    }
}
