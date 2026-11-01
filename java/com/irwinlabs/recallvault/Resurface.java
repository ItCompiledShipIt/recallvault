package com.irwinlabs.recallvault;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Resurface - memory lane features (v0.22.0): On this day,
 * a by-month timeline, and a plain-words year in review.
 * Pure logic - JVM-tested.
 */
public final class Resurface {

    private Resurface() {}

    private static Calendar cal(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        return c;
    }

    /**
     * Memories filed on this same month/day in an earlier year
     * (at least 300 days old, so nothing from this year sneaks in).
     */
    public static List<Item> onThisDay(List<Item> items, long nowMs) {
        Calendar now = cal(nowMs);
        List<Item> out = new ArrayList<Item>();
        for (Item it : items) {
            if (nowMs - it.createdAt < 300L * 24 * 3600 * 1000) continue;
            Calendar c = cal(it.createdAt);
            if (c.get(Calendar.MONTH) == now.get(Calendar.MONTH)
                    && c.get(Calendar.DAY_OF_MONTH) == now.get(Calendar.DAY_OF_MONTH)) {
                out.add(it);
            }
        }
        out.sort(new java.util.Comparator<Item>() {
            @Override public int compare(Item a, Item b) {
                return Long.compare(a.createdAt, b.createdAt);
            }
        });
        return out;
    }

    /** "1 year ago today" / "3 years ago today" for an on-this-day item. */
    public static String yearsAgoLabel(Item it, long nowMs) {
        int years = cal(nowMs).get(Calendar.YEAR) - cal(it.createdAt).get(Calendar.YEAR);
        return years <= 1 ? "1 year ago today" : years + " years ago today";
    }

    /** "November 2026" - the timeline's month headers. */
    public static String monthLabel(long ms) {
        return cal(ms).getDisplayName(Calendar.MONTH, Calendar.LONG, Locale.US)
            + " " + cal(ms).get(Calendar.YEAR);
    }

    /** Whether two timestamps fall in the same calendar month. */
    public static boolean sameMonth(long a, long b) {
        Calendar x = cal(a), y = cal(b);
        return x.get(Calendar.YEAR) == y.get(Calendar.YEAR)
            && x.get(Calendar.MONTH) == y.get(Calendar.MONTH);
    }

    /**
     * Year in review for a calendar year: counts, money recorded,
     * busiest month. Returns 2-4 plain lines.
     */
    public static List<String> yearInReview(List<Item> items, int year) {
        int total = 0, photos = 0, voice = 0, handled = 0;
        long money = 0;
        int[] perMonth = new int[12];
        java.util.Map<String, Integer> cats = new java.util.HashMap<String, Integer>();
        for (Item it : items) {
            Calendar c = cal(it.createdAt);
            if (c.get(Calendar.YEAR) != year) continue;
            total++;
            perMonth[c.get(Calendar.MONTH)]++;
            if ("PHOTO".equals(it.type) || "SCAN".equals(it.type)) photos++;
            if ("VOICE".equals(it.type)) voice++;
            if (it.handledAt > 0) handled++;
            long cents = Ask.principalCents(it);
            if (cents > 0) money += cents;
            Integer n = cats.get(it.category);
            cats.put(it.category, n == null ? 1 : n + 1);
        }
        List<String> lines = new ArrayList<String>();
        if (total == 0) {
            lines.add("Nothing filed in " + year + " yet.");
            return lines;
        }
        lines.add("In " + year + " you filed " + total + (total == 1 ? " memory" : " memories")
            + (photos + voice > 0
                ? " - " + photos + (photos == 1 ? " photo or scan" : " photos and scans")
                    + ", " + voice + (voice == 1 ? " voice note" : " voice notes")
                : "") + ".");
        String topCat = null;
        int topN = 0;
        for (java.util.Map.Entry<String, Integer> e : cats.entrySet()) {
            if (e.getValue() > topN) { topN = e.getValue(); topCat = e.getKey(); }
        }
        if (topCat != null) {
            lines.add("Most went to " + topCat + " (" + topN + ").");
        }
        int busiest = 0;
        for (int i = 1; i < 12; i++) if (perMonth[i] > perMonth[busiest]) busiest = i;
        if (perMonth[busiest] > 0) {
            Calendar probe = Calendar.getInstance();
            probe.set(Calendar.MONTH, busiest);
            lines.add("Busiest month: "
                + probe.getDisplayName(Calendar.MONTH, Calendar.LONG, Locale.US)
                + " (" + perMonth[busiest] + ").");
        }
        if (money > 0) {
            lines.add("Amounts recorded: " + Ask.formatMoney(money) + " across the year.");
        }
        if (handled > 0) {
            lines.add("You marked " + handled + (handled == 1 ? " reminder" : " reminders") + " handled.");
        }
        return lines;
    }

    /** Ask hook: "my year in review" - current year, as a summary card. */
    public static Ask.Summary reviewAnswer(List<Item> items, String query, long nowMs) {
        if (query == null) return null;
        String q = query.toLowerCase(Locale.US);
        if (!q.contains("year in review") && !(q.contains("review") && q.contains("year"))) return null;
        int year = cal(nowMs).get(Calendar.YEAR);
        List<String> lines = yearInReview(items, year);
        Ask.Summary s = new Ask.Summary();
        s.count = lines.size();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(lines.get(i));
        }
        s.text = sb.toString();
        return s;
    }
}
