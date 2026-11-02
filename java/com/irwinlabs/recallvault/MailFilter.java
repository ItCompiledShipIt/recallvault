package com.irwinlabs.recallvault;

import java.util.Locale;

/**
 * MailFilter - which email is worth filing (v0.56.0). Same
 * discipline as the text filter, tuned for mail: his criteria
 * are plans, dates, meetings, appointments, times and things
 * to remember, plus the obligations mail mostly carries -
 * bills due, orders, bookings. Bulk mail (anything with a
 * List-Unsubscribe header) only files when it carries a
 * strong fact anyway; newsletters and promos never do.
 * Pure string logic - JVM-tested.
 */
public final class MailFilter {

    private MailFilter() {}

    public static final class Verdict {
        public final boolean keep;
        public final String why;
        Verdict(boolean keep, String why) { this.keep = keep; this.why = why; }
    }

    private static final String[] REMEMBER = {
        "remember", "dont forget", "don't forget", "do not forget",
        "gate code", "door code", "lockbox", "wifi password",
        "password is", "address is", "bring the", "your code is",
        "access code", "entry code"
    };

    private static final String[] BILL = {
        "bill", "due", "payment", "invoice", "statement", "receipt"
    };

    private static final String[] HARD = {
        "appointment", "prescription", "refill", "pickup",
        "reservation", "booking", "booked", "flight", "boarding",
        "ticket", "delivery", "delivered", "shipped", "tracking", "order"
    };

    private static final String[] SOFT = {
        "account", "balance", "alert", "confirm", "confirmed",
        "scheduled", "schedule", "visit", "check-in", "itinerary"
    };

    private static boolean hasWord(String lower, String w) {
        return lower.contains(" " + w + " ") || lower.contains(" " + w + "s ")
            || lower.startsWith(w + " ") || lower.contains("\n" + w + " ");
    }

    public static Verdict judge(String subject, String body, boolean hasUnsub) {
        String text = ((subject == null ? "" : subject) + "\n" + (body == null ? "" : body));
        String lower = text.toLowerCase(Locale.US);
        Extract.Facts f = Extract.analyze(text);

        boolean hasAmount = !f.amounts.isEmpty();
        boolean hasDue = f.dueDate > 0;
        boolean hasDate = !f.dates.isEmpty() || f.dueDate > 0;
        boolean hasUrl = lower.contains("http://") || lower.contains("https://") || lower.contains("www.");

        boolean remember = false;
        for (String r : REMEMBER) if (lower.contains(r)) { remember = true; break; }
        boolean billCtx = false;
        for (String k : BILL) if (hasWord(lower, k)) { billCtx = true; break; }
        int hard = 0;
        for (String k : HARD) if (hasWord(lower, k)) hard++;
        int soft = 0;
        for (String k : SOFT) if (hasWord(lower, k)) soft++;

        boolean strong = hasDue || remember || (hasAmount && billCtx) || (hasDate && (hard + soft) >= 2);
        if (hasUnsub && !strong) return new Verdict(false, "bulk mail without a strong fact");

        if (hasDue) return new Verdict(true, "due date");
        if (remember) return new Verdict(true, "remember-signal");
        if (hasAmount && (billCtx || hasDate)) return new Verdict(true, "amount with context");
        if (hasDate && (hard + soft) >= 1) return new Verdict(true, "dated plan or obligation");
        if (hard >= 1 && (hasUrl || hasDate)) return new Verdict(true, "order/booking with link or date");
        if (hard >= 2) return new Verdict(true, "two obligation keywords");
        return new Verdict(false, "no filing signal");
    }
}
