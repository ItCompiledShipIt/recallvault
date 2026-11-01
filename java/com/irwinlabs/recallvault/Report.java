package com.irwinlabs.recallvault;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Monthly report (v0.36.0): "Your October in the vault" - what
 * came in, what came due, what got handled, who kept coming up,
 * and what next month already holds. Pure: the Vault card, the
 * dialog and the Ask hook all render from these lines.
 */
public final class Report {

    private Report() {}

    public static long[] monthBounds(int year, int month) {
        Calendar start = Calendar.getInstance();
        start.clear();
        start.set(year, month, 1);
        Calendar end = (Calendar) start.clone();
        end.add(Calendar.MONTH, 1);
        return new long[]{start.getTimeInMillis(), end.getTimeInMillis()};
    }

    public static String monthName(int year, int month) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(year, month, 1);
        return c.getDisplayName(Calendar.MONTH, Calendar.LONG, Locale.US) + " " + year;
    }

    /** The report lines for one month, or null when nothing was filed then. */
    public static List<String> lines(List<Item> items, int year, int month, long nowMs) {
        long[] b = monthBounds(year, month);
        long from = b[0], to = b[1];
        List<String> out = new ArrayList<String>();
        List<Item> filed = new ArrayList<Item>();
        Map<String, Integer> byCat = new HashMap<String, Integer>();
        Map<Long, Integer> byDay = new HashMap<Long, Integer>();
        long dueTotal = 0;
        int dueCount = 0, handled = 0;
        for (Item it : items) {
            if (it.createdAt >= from && it.createdAt < to) {
                filed.add(it);
                Integer n = byCat.get(it.category);
                byCat.put(it.category, n == null ? 1 : n + 1);
                Calendar d = Calendar.getInstance();
                d.setTimeInMillis(it.createdAt);
                d.set(Calendar.HOUR_OF_DAY, 0);
                d.set(Calendar.MINUTE, 0);
                d.set(Calendar.SECOND, 0);
                d.set(Calendar.MILLISECOND, 0);
                Long day = d.getTimeInMillis();
                Integer dn = byDay.get(day);
                byDay.put(day, dn == null ? 1 : dn + 1);
            }
            if (it.reminderAt >= from && it.reminderAt < to) {
                long cents = Ask.principalCents(it);
                if (cents > 0) { dueTotal += cents; dueCount++; }
                if (it.handledAt > 0) handled++;
            }
        }
        if (filed.isEmpty()) return null;
        out.add(filed.size() + (filed.size() == 1 ? " memory" : " memories") + " filed.");
        // Categories, biggest first, top three named.
        List<Map.Entry<String, Integer>> cats =
            new ArrayList<Map.Entry<String, Integer>>(byCat.entrySet());
        java.util.Collections.sort(cats, new java.util.Comparator<Map.Entry<String, Integer>>() {
            @Override public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> c) {
                return c.getValue() - a.getValue();
            }
        });
        StringBuilder catLine = new StringBuilder();
        for (int i = 0; i < cats.size() && i < 3; i++) {
            if (catLine.length() > 0) catLine.append(", ");
            catLine.append(cats.get(i).getValue()).append(' ').append(cats.get(i).getKey().toLowerCase(Locale.US));
        }
        if (catLine.length() > 0) out.add("Mostly " + catLine + ".");
        if (dueCount > 0) {
            out.add("Bills and dates that came due: " + dueCount
                + ", totaling " + Ask.formatMoney(dueTotal) + ".");
        }
        if (handled > 0) {
            out.add(handled + (handled == 1 ? " reminder" : " reminders") + " marked done.");
        }
        long bestDay = 0;
        int bestCount = 0;
        for (Map.Entry<Long, Integer> e : byDay.entrySet()) {
            if (e.getValue() > bestCount) { bestCount = e.getValue(); bestDay = e.getKey(); }
        }
        if (bestCount >= 3) {
            out.add("Busiest day: " + Extract.formatDate(bestDay)
                + " (" + bestCount + " saved).");
        }
        List<Entities.Entity> tops = Entities.top(filed, 1);
        if (!tops.isEmpty()) {
            Entities.Entity t = tops.get(0);
            out.add("Kept coming up: " + t.name + " (" + t.itemCount
                + (t.itemCount == 1 ? " memory" : " memories") + ").");
        }
        // Next month, as far as it is already known.
        Calendar nm = Calendar.getInstance();
        nm.clear();
        nm.set(year, month, 1);
        nm.add(Calendar.MONTH, 1);
        long[] nb = monthBounds(nm.get(Calendar.YEAR), nm.get(Calendar.MONTH));
        long nextTotal = 0;
        int nextCount = 0;
        for (Item it : items) {
            if (it.handledAt > 0) continue;
            if (it.reminderAt >= nb[0] && it.reminderAt < nb[1]) {
                long cents = Ask.principalCents(it);
                if (cents > 0) { nextTotal += cents; nextCount++; }
            }
        }
        long proj = Patterns.projectedMonthlyCents(Patterns.analyze(items, nowMs));
        if (nextCount > 0) {
            out.add("Already on the books for " + monthName(nm.get(Calendar.YEAR), nm.get(Calendar.MONTH))
                + ": " + nextCount + " dated, " + Ask.formatMoney(nextTotal) + "."
                + (proj > 0 ? " Recurring bills usually run about "
                    + Ask.formatMoney(proj) + " a month." : ""));
        } else if (proj > 0) {
            out.add("Recurring bills usually run about " + Ask.formatMoney(proj) + " a month.");
        }
        return out;
    }

    /** Ask hook: "monthly report", "my October report", "month in review". */
    public static Ask.Summary answer(List<Item> items, String query, long nowMs) {
        String q = query.toLowerCase(Locale.US);
        boolean hit = q.contains("report") || q.contains("month in review")
            || q.contains("my month") || q.contains("monthly summary");
        if (!hit) return null;
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(nowMs);
        int year = cal.get(Calendar.YEAR);
        int month = cal.get(Calendar.MONTH);
        long[] win = Ask.monthWindowMs(query, nowMs);
        if (win != null) {
            Calendar wc = Calendar.getInstance();
            wc.setTimeInMillis(win[0]);
            year = wc.get(Calendar.YEAR);
            month = wc.get(Calendar.MONTH);
        } else if (q.contains("last month")) {
            cal.add(Calendar.MONTH, -1);
            year = cal.get(Calendar.YEAR);
            month = cal.get(Calendar.MONTH);
        }
        List<String> ls = lines(items, year, month, nowMs);
        Ask.Summary s = new Ask.Summary();
        if (ls == null) {
            s.text = "Nothing was filed in " + monthName(year, month) + " yet.";
            return s;
        }
        StringBuilder sb = new StringBuilder("Your " + monthName(year, month) + " in the vault:");
        for (String l : ls) sb.append("\n").append(l);
        s.text = sb.toString();
        return s;
    }
}
