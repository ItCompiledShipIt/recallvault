package com.irwinlabs.recallvault;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Patterns - learns the vault's recurring bills (v0.17.0).
 *
 * Dated, amount-carrying memories are grouped by a normalized
 * title stem. A group counts as recurring with 2+ occurrences at
 * least 20 days apart inside the last 200 days. The "usual" amount
 * is the median; monthly cadence (average gap 20-45 days) feeds
 * the next-month projection. Pure logic - JVM-tested.
 */
public final class Patterns {

    private Patterns() {}

    public static final class Recurring {
        public String stem;          // normalized, e.g. "electricity bill"
        public String displayTitle;  // the newest occurrence's real title
        public int count;
        public long usualCents;      // median principal amount
        public long minCents;
        public long maxCents;
        public long latestCents;
        public long latestAt;        // newest occurrence's due date
        public boolean monthly;
    }

    private static final long DAY = 24L * 3600 * 1000;
    private static final long WINDOW_MS = 200 * DAY;
    private static final long MIN_GAP_MS = 20 * DAY;

    /** Lowercase the title and drop digits, punctuation and date words. */
    public static String stemOf(String title) {
        if (title == null) return "";
        String t = title.toLowerCase(java.util.Locale.US)
            .replaceAll("[0-9]", " ")
            .replaceAll("[^a-z ]", " ")
            .replaceAll("\\b(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec|january|february|march|april|june|july|august|september|october|november|december|statement|bill|due|total|payment|amount)\\b", " ")
            .replaceAll("\\s+", " ").trim();
        return t;
    }

    public static List<Recurring> analyze(List<Item> items, long nowMs) {
        Map<String, List<Item>> groups = new HashMap<String, List<Item>>();
        for (Item it : items) {
            if (it.reminderAt <= 0) continue;
            if (it.reminderAt < nowMs - WINDOW_MS) continue;
            if (Ask.principalCents(it) < 0) continue;
            String stem = stemOf(it.title);
            if (stem.isEmpty()) continue;
            List<Item> g = groups.get(stem);
            if (g == null) { g = new ArrayList<Item>(); groups.put(stem, g); }
            g.add(it);
        }
        List<Recurring> out = new ArrayList<Recurring>();
        for (Map.Entry<String, List<Item>> e : groups.entrySet()) {
            List<Item> g = e.getValue();
            Collections.sort(g, new java.util.Comparator<Item>() {
                @Override public int compare(Item a, Item b) {
                    return Long.compare(a.reminderAt, b.reminderAt);
                }
            });
            // Recurring needs two occurrences genuinely apart in time.
            boolean apart = false;
            for (int i = 1; i < g.size(); i++) {
                if (g.get(i).reminderAt - g.get(0).reminderAt >= MIN_GAP_MS) { apart = true; break; }
            }
            if (g.size() < 2 || !apart) continue;
            Recurring r = new Recurring();
            r.stem = e.getKey();
            r.count = g.size();
            List<Long> cents = new ArrayList<Long>();
            for (Item it : g) cents.add(Ask.principalCents(it));
            Collections.sort(cents);
            r.minCents = cents.get(0);
            r.maxCents = cents.get(cents.size() - 1);
            r.usualCents = cents.get(cents.size() / 2);
            Item newest = g.get(g.size() - 1);
            r.displayTitle = newest.title;
            r.latestCents = Ask.principalCents(newest);
            r.latestAt = newest.reminderAt;
            long span = newest.reminderAt - g.get(0).reminderAt;
            long avgGap = span / Math.max(1, g.size() - 1);
            r.monthly = avgGap >= 20 * DAY && avgGap <= 45 * DAY;
            out.add(r);
        }
        Collections.sort(out, new java.util.Comparator<Recurring>() {
            @Override public int compare(Recurring a, Recurring b) {
                return Long.compare(b.usualCents, a.usualCents);
            }
        });
        return out;
    }

    /** Sum of usual amounts across monthly recurring groups. */
    public static long projectedMonthlyCents(List<Recurring> rs) {
        long sum = 0;
        for (Recurring r : rs) if (r.monthly) sum += r.usualCents;
        return sum;
    }

    /** One short line per recurring group, for the Reminders card. */
    public static String line(Recurring r) {
        String s = r.displayTitle + ": usually " + Ask.formatMoney(r.usualCents)
            + " (" + r.count + " saved";
        if (r.minCents != r.maxCents) {
            s += ", " + Ask.formatMoney(r.minCents) + "-" + Ask.formatMoney(r.maxCents);
        }
        return s + ")";
    }

    /**
     * "Is this bill higher than usual?" / "what do I usually pay
     * for X" - returns a Summary-shaped answer, or null when the
     * question is not a pattern question or no group matches.
     */
    public static Ask.Summary answer(List<Item> items, String query, long nowMs) {
        if (query == null) return null;
        String q = query.toLowerCase(java.util.Locale.US);
        boolean patternQ = q.contains("usual") || q.contains("usually") || q.contains("typical")
            || q.contains("normally") || q.contains("higher than") || q.contains("more than usual");
        if (!patternQ) return null;
        List<Recurring> rs = analyze(items, nowMs);
        Recurring best = null;
        int bestHits = 0;
        for (Recurring r : rs) {
            int hits = 0;
            for (String w : r.stem.split(" ")) {
                if (w.length() >= 3 && q.contains(w)) hits++;
            }
            // A bare topic match: every stem word appears in the question.
            if (hits > bestHits) { bestHits = hits; best = r; }
        }
        if (best == null || bestHits == 0) return null;
        Ask.Summary s = new Ask.Summary();
        s.count = best.count;
        s.totalCents = best.usualCents;
        String verdict;
        long diff = best.latestCents - best.usualCents;
        long tol = Math.max(100, best.usualCents / 20); // 5% or $1
        if (Math.abs(diff) <= tol) verdict = "The latest is about usual.";
        else if (diff > 0) verdict = "The latest, " + Ask.formatMoney(best.latestCents)
            + ", is higher than usual by " + Ask.formatMoney(diff) + ".";
        else verdict = "The latest, " + Ask.formatMoney(best.latestCents)
            + ", is lower than usual by " + Ask.formatMoney(-diff) + ".";
        s.text = best.displayTitle + ": usually about " + Ask.formatMoney(best.usualCents)
            + " (" + best.count + " saved, ranging " + Ask.formatMoney(best.minCents)
            + "-" + Ask.formatMoney(best.maxCents) + "). " + verdict;
        return s;
    }
}
