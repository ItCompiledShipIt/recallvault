package com.irwinlabs.recallvault;

import java.util.ArrayList;
import java.util.List;

/**
 * Morning digest (v0.28.0): one composed summary of the day -
 * overdue and due first, then the week, an on-this-day memory,
 * and any recurring bill running hot. Pure: the worker and the
 * Settings preview both render from this.
 */
public final class Digest {

    private Digest() {}

    public static final class Out {
        public String headline;
        public final List<String> lines = new ArrayList<String>();
    }

    /** Compose the digest, or return null when there is nothing worth saying. */
    public static Out compose(List<Item> items, long nowMs) {
        Out out = new Out();
        long startToday = Ask.startOfToday(nowMs);
        long endToday = startToday + 24L * 3600 * 1000;

        Item firstOverdue = null;
        int overdue = 0;
        Item firstToday = null;
        int dueToday = 0;
        for (Item it : items) {
            if (it.reminderAt <= 0 || it.handledAt > 0) continue;
            if (it.reminderAt < startToday) {
                overdue++;
                if (firstOverdue == null || it.reminderAt < firstOverdue.reminderAt) firstOverdue = it;
            } else if (it.reminderAt < endToday) {
                dueToday++;
                if (firstToday == null || it.reminderAt < firstToday.reminderAt) firstToday = it;
            }
        }
        if (overdue > 0) {
            out.lines.add(overdue + (overdue == 1 ? " overdue: " : " overdue - first: ")
                + Privacy.displayTitle(firstOverdue) + " (was due " + Extract.formatDate(firstOverdue.reminderAt) + ")");
        }
        if (dueToday > 0) {
            out.lines.add(dueToday + (dueToday == 1 ? " due today: " : " due today - first: ")
                + Privacy.displayTitle(firstToday));
        }
        Ask.Summary week = Ask.dueDigest(items, nowMs);
        if (week != null && dueToday == 0 && overdue == 0) {
            out.lines.add(week.text);
        } else if (week != null) {
            out.lines.add("This week: " + week.count + (week.count == 1 ? " memory" : " memories")
                + (week.totalCents > 0 ? ", " + Ask.formatMoney(week.totalCents) : "") + " coming due.");
        }

        // A bill running hot is worth one line.
        for (Patterns.Recurring r : Patterns.analyze(items, nowMs)) {
            if (r.count >= 3 && r.usualCents > 0 && r.latestCents > r.usualCents
                    && (r.latestCents - r.usualCents) * 100 >= r.usualCents * 15) {
                out.lines.add(r.displayTitle + " is running high: latest "
                    + Ask.formatMoney(r.latestCents) + " vs usual " + Ask.formatMoney(r.usualCents) + ".");
                break;
            }
        }

        // Subscription census (v0.75.0): a renewal coming or a
        // fresh price rise is worth one line.
        Census.Report crep = Census.compute(items, nowMs);
        if (!crep.warnings.isEmpty()) {
            out.lines.add(crep.warnings.get(0));
        }

        List<Item> otd = Resurface.onThisDay(items, nowMs);
        if (!otd.isEmpty()) {
            Item it = otd.get(0);
            out.lines.add("On this day: " + Privacy.displayTitle(it) + " (" + Resurface.yearsAgoLabel(it, nowMs) + ")");
        }

        if (out.lines.isEmpty()) return null;
        out.headline = overdue > 0
            ? overdue + (overdue == 1 ? " overdue item" : " overdue items")
            : dueToday > 0
                ? dueToday + (dueToday == 1 ? " due today" : " due today")
                : "Your vault today";
        return out;
    }
}
