package com.irwinlabs.recallvault;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Heads-up reminders (v0.40.0): the nudge BEFORE the deadline.
 * The due-date worker already pings when something is due within
 * 48 hours or overdue; this adds a single earlier warning when a
 * reminder first comes within the user's lead window (default 3
 * days). Exactly once per (item, due date), same notified-set
 * discipline as everything else, keys prefixed "lead:".
 */
public final class HeadsUp {

    private HeadsUp() {}

    public static final String PREF_LEAD_DAYS = "remind_lead_days";
    public static final int DEFAULT_LEAD_DAYS = 3;
    public static final int[] LEAD_CHOICES = {0, 1, 2, 3, 5, 7, 14};

    public static String key(Item it) {
        return "lead:" + it.id + ":" + it.reminderAt;
    }

    public static String label(int days) {
        if (days <= 0) return "Off";
        if (days == 1) return "1 day before";
        return days + " days before";
    }

    /**
     * Items that should get a heads-up on this pass: unhandled,
     * due in the future but inside the lead window, and outside
     * the due worker's own 48h window (that ping covers the last
     * two days - a heads-up is only a heads-up if it is earlier).
     */
    public static List<Item> due(List<Item> items, long nowMs, int leadDays, Set<String> notified) {
        List<Item> out = new ArrayList<Item>();
        if (leadDays <= 0) return out;
        long day = 24L * 3600 * 1000;
        long windowEnd = nowMs + leadDays * day;
        long dueWindowStart = nowMs + 2 * day;
        for (Item it : items) {
            if (it.reminderAt <= 0 || it.handledAt > 0) continue;
            if (it.reminderAt <= dueWindowStart) continue;
            if (it.reminderAt > windowEnd) continue;
            if (notified != null && notified.contains(key(it))) continue;
            out.add(it);
        }
        return out;
    }

    /** Next choice when the user taps the setting. */
    public static int nextChoice(int current) {
        for (int i = 0; i < LEAD_CHOICES.length; i++) {
            if (LEAD_CHOICES[i] == current) {
                return LEAD_CHOICES[(i + 1) % LEAD_CHOICES.length];
            }
        }
        return DEFAULT_LEAD_DAYS;
    }
}
