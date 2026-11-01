package com.irwinlabs.recallvault;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Bills forecast (v0.39.0): what's coming, before it arrives.
 * Every recurring pattern projects its next occurrences from its
 * own history (average gap, usual amount); bills the vault
 * already holds real dates for are listed as themselves and
 * suppress the projection that would have duplicated them.
 * Three months out, merged and dated.
 */
public final class Forecast {

    private Forecast() {}

    public static final long HORIZON_MS = 92L * 24 * 3600 * 1000;
    private static final long DEDUPE_MS = 10L * 24 * 3600 * 1000;

    public static final class Entry {
        public String title;
        public long dueMs;
        public long cents;
        public boolean predicted;
        public String stem;
    }

    /** The merged timeline: real dated bills + projected recurrences. */
    public static List<Entry> forecast(List<Item> items, long nowMs) {
        List<Entry> out = new ArrayList<Entry>();
        long horizon = nowMs + HORIZON_MS;
        // Real, still-open dated bills in the window.
        List<Entry> real = new ArrayList<Entry>();
        for (Item it : items) {
            if (it.handledAt > 0) continue;
            if (it.reminderAt <= nowMs || it.reminderAt > horizon) continue;
            long cents = Ask.principalCents(it);
            if (cents <= 0) continue;
            Entry e = new Entry();
            e.title = it.title;
            e.dueMs = it.reminderAt;
            e.cents = cents;
            e.predicted = false;
            e.stem = Patterns.stemOf(it.title);
            real.add(e);
        }
        out.addAll(real);
        // Projected recurrences.
        for (Patterns.Recurring r : Patterns.analyze(items, nowMs)) {
            if (r.history.size() < 2) continue;
            long first = r.history.get(0)[0];
            long last = r.history.get(r.history.size() - 1)[0];
            long gap = (last - first) / (r.history.size() - 1);
            if (gap < 5L * 24 * 3600 * 1000) continue; // too erratic to project
            for (long due = last + gap; due <= horizon; due += gap) {
                if (due <= nowMs) continue;
                boolean covered = false;
                for (Entry re : real) {
                    if (re.stem.equals(r.stem) && Math.abs(re.dueMs - due) <= DEDUPE_MS) {
                        covered = true;
                        break;
                    }
                }
                if (covered) continue;
                Entry e = new Entry();
                e.title = r.displayTitle;
                e.dueMs = due;
                e.cents = r.usualCents;
                e.predicted = true;
                e.stem = r.stem;
                out.add(e);
            }
        }
        Collections.sort(out, new Comparator<Entry>() {
            @Override public int compare(Entry a, Entry b) {
                return Long.compare(a.dueMs, b.dueMs);
            }
        });
        return out;
    }

    /** Expected total (cents) for one calendar month of the forecast. */
    public static long monthTotalCents(List<Entry> entries, int year, int month) {
        long[] b = Report.monthBounds(year, month);
        long sum = 0;
        for (Entry e : entries) {
            if (e.dueMs >= b[0] && e.dueMs < b[1]) sum += e.cents;
        }
        return sum;
    }

    public static String line(Entry e) {
        return Extract.formatDate(e.dueMs) + " - " + e.title + " - "
            + (e.predicted ? "about " : "") + Ask.formatMoney(e.cents)
            + (e.predicted ? " (expected)" : "");
    }

    /** Ask hook: "what's coming", "what bills are coming", "forecast". */
    public static Ask.Summary answer(List<Item> items, String query, long nowMs) {
        String q = query.toLowerCase(Locale.US);
        boolean hit = q.contains("forecast")
            || (q.contains("coming") && (q.contains("bill") || q.contains("what")));
        if (!hit) return null;
        List<Entry> fc = forecast(items, nowMs);
        Ask.Summary s = new Ask.Summary();
        if (fc.isEmpty()) {
            s.text = "Nothing is on the books for the next three months yet - "
                + "no dated bills and no recurring patterns to project.";
            return s;
        }
        StringBuilder sb = new StringBuilder("What's coming:");
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(nowMs);
        for (int m = 0; m < 3; m++) {
            Calendar mc = (Calendar) cal.clone();
            mc.add(Calendar.MONTH, m);
            long total = monthTotalCents(fc, mc.get(Calendar.YEAR), mc.get(Calendar.MONTH));
            if (total > 0) {
                sb.append("\n").append(Report.monthName(mc.get(Calendar.YEAR), mc.get(Calendar.MONTH)))
                  .append(": about ").append(Ask.formatMoney(total)).append(" expected.");
            }
        }
        int shown = 0;
        for (Entry e : fc) {
            if (shown++ >= 5) break;
            sb.append("\n").append(line(e));
        }
        if (fc.size() > 5) sb.append("\n...and ").append(fc.size() - 5).append(" more in the forecast.");
        s.text = sb.toString();
        return s;
    }
}
