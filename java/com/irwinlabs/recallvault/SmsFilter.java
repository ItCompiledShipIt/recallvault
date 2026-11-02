package com.irwinlabs.recallvault;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * SmsFilter - decides which text messages carry data worth
 * filing (v0.54.0). Chris's rule: not every text, only the
 * ones with meaningful data - a bill, an appointment, a
 * delivery, an amount, a date that matters. Built on
 * Extract's facts (amounts, dates) plus action keywords and
 * link/code shapes. Pure Java so the JVM suite covers it;
 * it errs toward skipping - a borderline text stays in the
 * messaging app, where it can still be shared to the vault
 * by hand.
 */
public final class SmsFilter {

    private SmsFilter() {}

    public static final class Verdict {
        public boolean keep;
        public int score;
        public String why = "";
    }

    // One-time codes are ephemeral and sensitive: never filed
    // unless the message also carries a real fact (an amount
    // or a date) - a bank alert can mention a code and still
    // be a record worth keeping.
    private static final Pattern P_OTP_CTX = Pattern.compile(
        "(verification|security|passcode|one[- ]time|\\botp\\b|your code|code is|access code)",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern P_CODE_NUM = Pattern.compile("\\b\\d{4,8}\\b");
    private static final Pattern P_URL = Pattern.compile("https?://", Pattern.CASE_INSENSITIVE);
    // Confirmation / tracking style tokens: letters AND digits.
    private static final Pattern P_TOKEN = Pattern.compile(
        "\\b(?=[A-Z0-9-]*[A-Z])(?=[A-Z0-9-]*[0-9])[A-Z0-9][A-Z0-9-]{5,}\\b");

    private static final String[] KEYWORDS = {
        "bill", "payment", "paid", "appointment", "delivery", "delivered",
        "shipped", "shipping", "track", "tracking", "order", "booking",
        "booked", "confirmed", "confirmation", "reservation", "prescription",
        "refill", "pickup", "scheduled", "reminder", "expires", "expiry",
        "due", "statement", "invoice", "receipt", "balance", "ticket",
        "boarding", "flight", "departs", "renewal", "policy", "claim",
    };

    // Chris's criteria (Nov 1, 2026): plans, dates, meetings,
    // appointments, TIMES, things to remember. A clock time or a
    // day word next to a plan word is a plan - that files.
    private static final Pattern P_TIME = Pattern.compile(
        "\\b\\d{1,2}(:\\d{2})\\s*(a\\.?m\\.?|p\\.?m\\.)?\\b"
            + "|\\b\\d{1,2}\\s*(a\\.?m\\.?|p\\.?m\\.)\\b"
            + "|\\b(noon|midnight)\\b",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern P_DAY = Pattern.compile(
        "\\b(today|tonight|tomorrow|tmrw|monday|tuesday|wednesday|thursday|"
            + "friday|saturday|sunday|mon|tue|tues|wed|thu|thur|thurs|fri|sat|sun)\\b",
        Pattern.CASE_INSENSITIVE);
    private static final String[] PLAN_WORDS = {
        "meet", "meeting", "see you", "lunch", "dinner", "breakfast",
        "coffee", "come over", "stop by", "pick you up", "headed",
        "leaving", "arrive", "arriving", "starts", "party", "visit",
        "trip", "drive", "ride", "plan", "plans", "get together",
    };
    // "Things to remember" said out loud by the sender, plus
    // keeper codes - a gate code from a person is a memory, not
    // a one-time passcode.
    private static final String[] REMEMBER_PHRASES = {
        "remember", "dont forget", "do not forget", "note to self",
        "gate code", "door code", "lockbox", "wifi", "wi-fi",
        "password is", "address is", "bring the", "we need",
    };

    public static Verdict judge(String body) {
        Verdict v = new Verdict();
        if (body == null) { v.why = "empty"; return v; }
        String text = body.trim();
        if (text.isEmpty()) { v.why = "empty"; return v; }
        Extract.Facts f = Extract.analyze(text);
        boolean hasAmount = !f.amounts.isEmpty();
        boolean hasDue = f.dueDate > 0;
        boolean hasDate = !f.dates.isEmpty();
        boolean hasUrl = P_URL.matcher(text).find();
        boolean hasToken = P_TOKEN.matcher(text).find();
        int kw = 0;
        String lower = " " + text.toLowerCase(Locale.US).replaceAll("[^a-z ]", " ") + " ";
        for (String k : KEYWORDS) {
            if (lower.contains(" " + k + " ") || lower.contains(" " + k + "s ")) kw++;
        }
        if (kw > 3) kw = 3;
        boolean hasTime = P_TIME.matcher(text).find();
        boolean hasDay = P_DAY.matcher(text).find();
        int plan = 0;
        for (String p : PLAN_WORDS) {
            if (lower.contains(" " + p + " ") || lower.contains(" " + p + "s ")) plan++;
        }
        String squashed = text.toLowerCase(Locale.US).replace("'", "");
        boolean remember = false;
        for (String p : REMEMBER_PHRASES) {
            if (squashed.contains(p)) { remember = true; break; }
        }

        if (P_OTP_CTX.matcher(text).find() && P_CODE_NUM.matcher(text).find()
                && !hasAmount && !hasDate && !remember) {
            v.why = "one-time code";
            return v;
        }

        int score = 0;
        if (hasAmount) score += 3;
        if (hasDue) score += 3; else if (hasDate) score += 2;
        if (hasUrl) score += 1;
        if (hasToken) score += 1;
        score += kw;
        v.score = score;

        // Tightened after the first real-corpus run (Nov 2, 2026):
        // his 2023 inbox filed 100 automated alerts through the
        // loose paths (bare amount, score stacking, any two
        // keywords). Chris's criteria are plans and facts with a
        // date/time or an obligation attached - so an amount now
        // needs bill context or a date, and keyword pairs only
        // count from the go/do/get set.
        boolean billCtx = false;
        for (String k : new String[]{"bill", "due", "payment", "invoice", "statement"}) {
            if (lower.contains(" " + k + " ") || lower.contains(" " + k + "s ")) billCtx = true;
        }
        int hardKw = 0;
        for (String k : new String[]{"appointment", "prescription", "refill", "pickup",
                "reservation", "booking", "booked", "flight", "boarding", "ticket",
                "delivery", "delivered", "shipped", "invoice"}) {
            if (lower.contains(" " + k + " ") || lower.contains(" " + k + "s ")) hardKw++;
        }

        v.keep = hasDue
            || remember
            || (hasAmount && (billCtx || hasDate))
            || (hasDate && (kw >= 1 || plan >= 1 || hasTime || hasDay))
            || (hasTime && (plan >= 1 || hasDay || kw >= 1))
            || (hasDay && plan >= 1)
            || hardKw >= 2
            || (hasUrl && kw >= 1);
        v.why = v.keep ? "facts: amount=" + hasAmount + " due=" + hasDue
            + " date=" + hasDate + " time=" + hasTime + " day=" + hasDay
            + " kw=" + kw + " plan=" + plan + " remember=" + remember
            : "no plan or facts (score " + score + ")";
        return v;
    }
}
