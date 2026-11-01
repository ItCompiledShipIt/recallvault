package com.irwinlabs.recallvault;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Expiry radar (v0.30.0): some dates are not bills - they are the
 * day something stops being valid. A licence, an insurance card, a
 * permit, a lease. Those get tracked separately from due dates:
 * extracted whenever an item is saved, listed on the Reminders
 * tab, warned about by the daily worker, and answerable in Ask.
 */
public final class Expiry {

    private Expiry() {}

    private static final String[] KEYWORDS = {
        "expir", "valid until", "valid thru", "valid through",
        "renew by", "renewal date", "good until", "coverage ends",
        "must be renewed", "valid to",
    };

    /**
     * The expiry date in a piece of text, or -1. Finds the first
     * expiry keyword, then takes the first date that follows it -
     * "issued Jan 2024, expires Mar 2027" yields the 2027 date, not
     * the issue date.
     */
    public static long expiryOf(String text) {
        if (text == null || text.isEmpty()) return -1;
        String low = text.toLowerCase(Locale.US);
        int at = -1;
        for (String kw : KEYWORDS) {
            int i = low.indexOf(kw);
            if (i >= 0 && (at < 0 || i < at)) at = i;
        }
        if (at < 0) return -1;
        Extract.Facts f = Extract.analyze(text.substring(at));
        return f.dates.isEmpty() ? -1 : f.dates.get(0);
    }

    /** Convenience over an item's three text fields. */
    public static long expiryOf(Item it) {
        String all = (it.title == null ? "" : it.title) + "\n"
            + (it.body == null ? "" : it.body) + "\n"
            + (it.extracted == null ? "" : it.extracted);
        return expiryOf(all);
    }

    /** Items carrying an expiry, soonest first (expired ones lead). */
    public static List<Item> expiring(List<Item> items) {
        List<Item> out = new ArrayList<Item>();
        for (Item it : items) if (it.expiryAt > 0) out.add(it);
        Collections.sort(out, new Comparator<Item>() {
            @Override public int compare(Item a, Item b) {
                return Long.compare(a.expiryAt, b.expiryAt);
            }
        });
        return out;
    }

    /** Ask hook: "what expires", "what's expiring soon", "when does X expire". */
    public static Ask.Summary answer(List<Item> items, String query, long nowMs) {
        String q = query.toLowerCase(Locale.US);
        if (!q.contains("expir") && !q.contains("renew") && !q.contains("valid until")) return null;
        List<Item> ex = expiring(items);
        if (ex.isEmpty()) {
            Ask.Summary s = new Ask.Summary();
            s.text = "Nothing in your vault has an expiry date on it yet. "
                + "Save a licence, insurance card or permit and it will be tracked here.";
            return s;
        }
        // "when does the insurance expire" - narrow to keyword matches,
        // but the trigger words themselves are not content words:
        // "what expires soon" must list everything, not just the items
        // that happen to contain the word "expires".
        List<String> kws = new ArrayList<String>();
        for (String k : Ask.keywords(query)) {
            if (k.startsWith("expir") || k.startsWith("renew")
                    || k.equals("valid") || k.equals("soon")) continue;
            kws.add(k);
        }
        List<Item> shown = ex;
        if (!kws.isEmpty()) {
            List<Item> narrowed = new ArrayList<Item>();
            for (Item it : ex) {
                if (Ask.keywordScore(it, kws) > 0) narrowed.add(it);
            }
            if (!narrowed.isEmpty()) shown = narrowed;
        }
        StringBuilder sb = new StringBuilder();
        for (Item it : shown) {
            if (sb.length() > 0) sb.append("\n");
            boolean past = it.expiryAt < nowMs;
            sb.append(it.title).append(" - ")
              .append(past ? "expired " : "expires ")
              .append(Extract.formatDate(it.expiryAt))
              .append(" (").append(Extract.relativeDay(it.expiryAt, nowMs)).append(")");
        }
        Ask.Summary s = new Ask.Summary();
        s.count = shown.size();
        s.text = sb.toString();
        return s;
    }
}
