package com.irwinlabs.recallvault;

import java.util.Locale;

/**
 * NotifyFilter - decides which notifications carry data worth
 * filing (v0.70.0). Same judgment as texts and mail, tuned for
 * the noisiest stream on the phone: a notification earns a
 * memory only when it carries a fact - an amount, a due or
 * appointment date, a money/delivery event - never for chatter,
 * reactions, media controls or one-time codes. Pure Java so
 * the JVM suite covers it; it errs toward skipping, like
 * SmsFilter: a borderline notification stays in the shade,
 * where it can still be shared to the vault by hand.
 */
public final class NotifyFilter {

    private NotifyFilter() {}

    public static final class Verdict {
        public boolean keep;
        public String why = "";
    }

    /** Events that are records on their own, fact or not. */
    private static final String[] EVENT_WORDS = {
        "delivered", "shipped", "out for delivery", "charged",
        "payment received", "deposit received", "refund issued",
        "amount due", "bill is ready", "payment due", "past due"};

    /** Meaningful only in combination (two, or one plus a date). */
    private static final String[] STRONG_WORDS = {
        "bill", "payment", "appointment", "delivery", "expires",
        "expiry", "renewal", "due", "statement", "invoice",
        "prescription", "refill", "order", "tracking", "booking",
        "reservation", "scheduled", "reminder", "balance"};

    private static final String[] CODE_WORDS = {
        "verification code", "security code", "one-time",
        "your code is", "passcode", "otp"};

    public static Verdict judge(String title, String text) {
        Verdict v = new Verdict();
        String combined = ((title == null ? "" : title) + " "
            + (text == null ? "" : text)).trim();
        if (combined.isEmpty()) { v.why = "empty"; return v; }
        String lower = combined.toLowerCase(Locale.US);
        Extract.Facts f = Extract.analyze(combined);
        boolean hasAmount = !f.amounts.isEmpty();
        boolean hasDue = f.dueDate > 0;
        boolean hasDate = !f.dates.isEmpty();

        // One-time codes are ephemeral and sensitive: never
        // filed unless a real fact rides along.
        boolean codeCtx = false;
        for (String cw : CODE_WORDS) {
            if (Extract.hasPhrase(lower, cw)) { codeCtx = true; break; }
        }
        if (codeCtx && !hasAmount && !hasDue) { v.why = "code only"; return v; }

        for (String ew : EVENT_WORDS) {
            if (Extract.hasPhrase(lower, ew)) {
                v.keep = true; v.why = "event: " + ew; return v;
            }
        }
        if (hasAmount) { v.keep = true; v.why = "amount"; return v; }
        if (hasDue) { v.keep = true; v.why = "due date"; return v; }
        int strong = 0;
        String firstStrong = "";
        for (String sw : STRONG_WORDS) {
            if (Extract.hasPhrase(lower, sw)) {
                strong++;
                if (firstStrong.isEmpty()) firstStrong = sw;
            }
        }
        if (strong >= 2) { v.keep = true; v.why = "facts: " + strong; return v; }
        if (strong >= 1 && hasDate) {
            v.keep = true; v.why = "dated: " + firstStrong; return v;
        }
        v.why = "no fact";
        return v;
    }
}
