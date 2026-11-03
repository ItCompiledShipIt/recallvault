package com.irwinlabs.recallvault;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Subscription census (v0.75.0): every recurring charge the
 * vault knows about, treated like a subscription whether it
 * is one or not - cadence, monthly-equivalent cost, the total
 * monthly burn, price creep (the latest charge higher than
 * the one before it), renewals coming, and charges that look
 * finished. Groups by the Patterns stem over a 400-day window
 * (long enough to see a yearly charge twice); an occurrence's
 * date is its due date when it has one, else when it was
 * saved. Pure; the desktop computes the same census.
 */
public final class Census {

    private Census() {}

    private static final long DAY = 24L * 3600 * 1000;

    public static final class Row {
        public String title;
        public int count;
        public long latestCents;
        public long usualCents;
        public long gapDays;
        public String cadence;
        public long monthlyCents;
        public long nextAt;
        public long prevCents;      // previous distinct amount, 0 = none
        public boolean priceUp;
        public boolean priceDown;
        public boolean ended;
        public boolean renewalSoon;
        public final List<long[]> history = new ArrayList<long[]>();
    }

    public static final class Report {
        public final List<Row> rows = new ArrayList<Row>();
        public long monthlyBurnCents;
        public final List<String> warnings = new ArrayList<String>();
    }

    public static Report compute(List<Item> items, long nowMs) {
        Report rep = new Report();
        Map<String, List<Item>> groups = new HashMap<String, List<Item>>();
        for (Item it : items) {
            long when = it.reminderAt > 0 ? it.reminderAt : it.createdAt;
            if (when <= 0 || when < nowMs - 400 * DAY || when > nowMs + 45 * DAY) continue;
            if (Ask.principalCents(it) < 0) continue;
            String stem = Patterns.stemOf(it.title);
            if (stem.isEmpty()) continue;
            List<Item> g = groups.get(stem);
            if (g == null) { g = new ArrayList<Item>(); groups.put(stem, g); }
            g.add(it);
        }
        for (Map.Entry<String, List<Item>> e : groups.entrySet()) {
            final List<Item> g = e.getValue();
            if (g.size() < 2) continue;
            Collections.sort(g, new java.util.Comparator<Item>() {
                @Override public int compare(Item a, Item b) {
                    return Long.compare(dateOf(a), dateOf(b));
                }
            });
            long first = dateOf(g.get(0));
            long last = dateOf(g.get(g.size() - 1));
            if (last - first < 20 * DAY) continue;
            Row r = new Row();
            r.title = g.get(g.size() - 1).title;
            r.count = g.size();
            List<Long> cents = new ArrayList<Long>();
            for (Item it : g) {
                long c = Ask.principalCents(it);
                cents.add(c);
                r.history.add(new long[]{dateOf(it), c});
            }
            List<Long> sorted = new ArrayList<Long>(cents);
            Collections.sort(sorted);
            r.usualCents = sorted.get(sorted.size() / 2);
            r.latestCents = cents.get(cents.size() - 1);
            List<Long> gaps = new ArrayList<Long>();
            for (int i = 1; i < g.size(); i++) {
                long gap = (dateOf(g.get(i)) - dateOf(g.get(i - 1))) / DAY;
                if (gap >= 1) gaps.add(gap);
            }
            if (gaps.isEmpty()) continue;
            Collections.sort(gaps);
            r.gapDays = Math.max(1, gaps.get(gaps.size() / 2));
            r.cadence = r.gapDays <= 10 ? "Weekly"
                : r.gapDays <= 18 ? "Every 2 weeks"
                : r.gapDays <= 45 ? "Monthly"
                : r.gapDays <= 120 ? "Quarterly"
                : r.gapDays <= 400 ? "Yearly" : "Irregular";
            // Monthly-equivalent: a monthly cadence (19-45 day
            // gaps - real months run 28-31 days) counts at face
            // value; everything else scales by the gap.
            r.monthlyCents = (r.gapDays >= 19 && r.gapDays <= 45)
                ? r.latestCents
                : Math.round(r.latestCents * 30.44 / r.gapDays);
            // When the newest occurrence is itself the upcoming
            // charge (a bill saved ahead of its due date), that
            // date is the renewal; otherwise project one gap out.
            // Calendar arithmetic, not raw ms: a projection that
            // crosses a DST change must still land on the right
            // calendar day.
            if (last > nowMs) {
                r.nextAt = last;
            } else {
                java.util.Calendar cal = java.util.Calendar.getInstance();
                cal.setTimeInMillis(last);
                cal.add(java.util.Calendar.DAY_OF_YEAR, (int) r.gapDays);
                r.nextAt = cal.getTimeInMillis();
            }
            // Price creep: the latest amount against the most
            // recent DIFFERENT amount before it.
            long prev = 0;
            for (int i = cents.size() - 2; i >= 0; i--) {
                if (cents.get(i) != r.latestCents) { prev = cents.get(i); break; }
            }
            r.prevCents = prev;
            if (prev > 0) {
                long delta = r.latestCents - prev;
                long tol = Math.max(50, prev * 3 / 100);
                if (delta >= tol) r.priceUp = true;
                else if (-delta >= tol) r.priceDown = true;
            }
            r.ended = nowMs > last + (long) (r.gapDays * DAY * 1.8);
            r.renewalSoon = !r.ended && r.nextAt >= nowMs - DAY
                && r.nextAt - nowMs <= 14 * DAY
                && (r.gapDays >= 80 || r.latestCents >= 5000);
            rep.rows.add(r);
        }
        Collections.sort(rep.rows, new java.util.Comparator<Row>() {
            @Override public int compare(Row a, Row b) {
                return Long.compare(b.monthlyCents, a.monthlyCents);
            }
        });
        for (Row r : rep.rows) {
            if (!r.ended) rep.monthlyBurnCents += r.monthlyCents;
        }
        // Warnings: renewals first (soonest first), then fresh
        // price rises (latest charge within 45 days).
        List<Row> renewals = new ArrayList<Row>();
        for (Row r : rep.rows) if (r.renewalSoon) renewals.add(r);
        Collections.sort(renewals, new java.util.Comparator<Row>() {
            @Override public int compare(Row a, Row b) {
                return Long.compare(a.nextAt, b.nextAt);
            }
        });
        for (Row r : renewals) {
            rep.warnings.add(r.title + " renews " + Extract.formatDate(r.nextAt)
                + " - " + Ask.formatMoney(r.latestCents) + ".");
        }
        for (Row r : rep.rows) {
            long lastAt = r.history.get(r.history.size() - 1)[0];
            if (r.priceUp && nowMs - lastAt <= 45 * DAY) {
                rep.warnings.add(r.title + " went up: " + Ask.formatMoney(r.prevCents)
                    + " -> " + Ask.formatMoney(r.latestCents) + ".");
            }
        }
        return rep;
    }

    private static long dateOf(Item it) {
        return it.reminderAt > 0 ? it.reminderAt : it.createdAt;
    }

    /** One display line for the Reminders card and the dialog. */
    public static String line(Row r, long nowMs) {
        StringBuilder s = new StringBuilder();
        s.append(r.title).append(": ").append(Ask.formatMoney(r.latestCents))
         .append(' ').append(r.cadence.toLowerCase(Locale.US));
        if (r.priceUp) {
            s.append(" · up from ").append(Ask.formatMoney(r.prevCents));
        } else if (r.priceDown) {
            s.append(" · down from ").append(Ask.formatMoney(r.prevCents));
        }
        if (r.ended) s.append(" · looks finished");
        else s.append(" · next ").append(Extract.formatDate(r.nextAt));
        return s.toString();
    }

    /** "What subscriptions do I have?" / "how much do my
     *  recurring charges run?" - the census as an Ask answer. */
    public static Ask.Summary answer(List<Item> items, String query, long nowMs) {
        if (query == null) return null;
        String q = query.toLowerCase(Locale.US);
        if (!q.contains("subscription") && !q.contains("recurring")) return null;
        Report rep = compute(items, nowMs);
        if (rep.rows.isEmpty()) return null;
        Ask.Summary s = new Ask.Summary();
        s.count = rep.rows.size();
        s.totalCents = rep.monthlyBurnCents;
        StringBuilder t = new StringBuilder();
        t.append(rep.rows.size()).append(rep.rows.size() == 1
            ? " recurring charge, running about " : " recurring charges, running about ")
         .append(Ask.formatMoney(rep.monthlyBurnCents)).append(" a month (")
         .append(Ask.formatMoney(rep.monthlyBurnCents * 12)).append(" a year). ");
        int shown = 0;
        for (Row r : rep.rows) {
            if (r.ended) continue;
            if (shown++ >= 3) break;
            if (shown > 1) t.append(", ");
            t.append(r.title).append(' ').append(Ask.formatMoney(r.latestCents))
             .append(' ').append(r.cadence.toLowerCase(Locale.US));
        }
        if (shown > 0) t.append('.');
        if (!rep.warnings.isEmpty()) t.append(' ').append(rep.warnings.get(0));
        s.text = t.toString();
        return s;
    }
}
