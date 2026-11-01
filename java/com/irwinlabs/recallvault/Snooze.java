package com.irwinlabs.recallvault;

/**
 * Snooze (v0.41.0): push a reminder out instead of finishing it.
 * Snoozing moves the due date itself, which is why it composes
 * with everything else: the notified keys all carry the due date,
 * so the new date earns its own heads-up and due pings, and the
 * forecast and patterns see the date the user actually chose.
 */
public final class Snooze {

    private Snooze() {}

    public static final long DAY_MS = 24L * 3600 * 1000;
    public static final int[] CHOICES = {1, 7};

    public static String choiceLabel(int days) {
        return days == 1 ? "1 day" : days + " days";
    }

    /** The due date after snoozing by the given number of days. */
    public static long snoozedUntil(long reminderAt, int days) {
        if (reminderAt <= 0 || days <= 0) return reminderAt;
        return reminderAt + days * DAY_MS;
    }
}
