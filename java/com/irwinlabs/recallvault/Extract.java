package com.irwinlabs.recallvault;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extract - local, dependency-free fact extraction from saved text.
 * Pure Java (no Android APIs) so it can be unit-tested on a plain JVM.
 * Pulls out: dates (with a "due / expires" preference), money amounts,
 * and a topical category. This is what makes a saved item answerable:
 * "when is my electricity bill due" -> date + amount + source.
 */
public final class Extract {

    private Extract() {}

    public static final String[] CATEGORIES = {
        "Bills", "Receipts", "Wallet", "People", "Meetings", "Notes"
    };

    public static class Facts {
        /** All dates found, epoch millis at local midnight, in text order. */
        public List<Long> dates = new ArrayList<Long>();
        /** The date most likely to be a deadline (near due/expires/renew), or -1. */
        public long dueDate = -1;
        /** All money amounts found, e.g. "$84.12", in text order. */
        public List<String> amounts = new ArrayList<String>();
        public String category = "Notes";
        public int categoryScore = 0;
    }

    // ---------- dates ----------

    private static final Pattern P_SLASH =
        Pattern.compile("\\b(\\d{1,2})/(\\d{1,2})/(\\d{4})\\b");
    private static final Pattern P_ISO =
        Pattern.compile("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b");
    private static final Pattern P_MONTH_FIRST =
        Pattern.compile("\\b(Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|"
            + "Jul(?:y)?|Aug(?:ust)?|Sep(?:t(?:ember)?)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)"
            + "\\s+(\\d{1,2})(?:st|nd|rd|th)?,?\\s+(\\d{4})\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_DAY_FIRST =
        Pattern.compile("\\b(\\d{1,2})(?:st|nd|rd|th)?\\s+"
            + "(Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|"
            + "Jul(?:y)?|Aug(?:ust)?|Sep(?:t(?:ember)?)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)"
            + "\\s+(\\d{4})\\b", Pattern.CASE_INSENSITIVE);
    // Year-less month dates: speech and transcripts omit the year
    // ("due on November 14th" - exactly what faster-whisper returns).
    // The year is inferred as the next occurrence (see inferYearEpoch).
    // The lookahead keeps these from double-hitting dates that DO
    // carry a year (those are caught by the patterns above).
    private static final Pattern P_MONTH_NOYEAR =
        Pattern.compile("\\b(Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|"
            + "Jul(?:y)?|Aug(?:ust)?|Sep(?:t(?:ember)?)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)"
            + "\\s+(\\d{1,2})(?:st|nd|rd|th)?\\b(?!\\s*,?\\s*\\d{4}\\b)", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_DAY_NOYEAR =
        Pattern.compile("\\b(\\d{1,2})(?:st|nd|rd|th)?\\s+"
            + "(Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|"
            + "Jul(?:y)?|Aug(?:ust)?|Sep(?:t(?:ember)?)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)"
            + "\\b(?!\\s*,?\\s*\\d{4}\\b)", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_DUE_HINT =
        Pattern.compile("(due|expires?|expiry|renew(?:al|s)?|deadline|pay(?:ment)? by|valid (?:until|thru)|through)",
            Pattern.CASE_INSENSITIVE);

    private static final Map<String, Integer> MONTHS = new LinkedHashMap<String, Integer>();
    static {
        String[] names = {"jan","feb","mar","apr","may","jun","jul","aug","sep","oct","nov","dec"};
        for (int i = 0; i < names.length; i++) MONTHS.put(names[i], i);
    }

    private static int monthOf(String s) {
        String k = s.toLowerCase(Locale.US);
        if (k.length() > 3) k = k.substring(0, 3);
        Integer m = MONTHS.get(k);
        return m == null ? -1 : m.intValue();
    }

    private static long toEpoch(int year, int month0, int day) {
        if (year < 1970 || year > 2100 || month0 < 0 || month0 > 11 || day < 1 || day > 31) return -1;
        Calendar c = Calendar.getInstance();
        c.set(Calendar.YEAR, year);
        c.set(Calendar.MONTH, month0);
        c.set(Calendar.DAY_OF_MONTH, day);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        // Reject overflow dates like Feb 31 rolling into March.
        if (c.get(Calendar.DAY_OF_MONTH) != day || c.get(Calendar.MONTH) != month0) return -1;
        return c.getTimeInMillis();
    }

    /** Year-less dates mean the NEXT occurrence: this year, or next
     *  year when that day has already passed. The right reading for
     *  due dates and reminders, which is what these hits feed. */
    private static long inferYearEpoch(int month0, int day) {
        Calendar now = Calendar.getInstance();
        int y = now.get(Calendar.YEAR);
        long e = toEpoch(y, month0, day);
        if (e < 0) return -1;
        Calendar today = Calendar.getInstance();
        today.set(Calendar.HOUR_OF_DAY, 0);
        today.set(Calendar.MINUTE, 0);
        today.set(Calendar.SECOND, 0);
        today.set(Calendar.MILLISECOND, 0);
        if (e < today.getTimeInMillis()) e = toEpoch(y + 1, month0, day);
        return e;
    }

    /** One date hit: epoch + char position in the text. */
    private static class Hit { long epoch; int pos; Hit(long e, int p) { epoch = e; pos = p; } }

    /**
     * OCR engines confuse 1/l/I and 0/O. Normalize those confusions only
     * in date-shaped contexts (digit-adjacent, or right after a month
     * name). Substitutions are 1 char -> 1 char, so positions in the
     * returned hits still index the original text.
     * Found live, Oct 31 2026: ML Kit read a bill's "November 14" as
     * "November l4" and the due date silently vanished.
     */
    private static String normalizeDateConfusions(String text) {
        String t = text;
        t = t.replaceAll("(?<=[0-9/\\-])l(?=[0-9/\\-])", "1");
        t = t.replaceAll("(?<=[0-9/\\-])I(?=[0-9/\\-])", "1");
        t = t.replaceAll("(?<=[0-9/\\-])O(?=[0-9/\\-])", "0");
        t = t.replaceAll("(?i)(\\b(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\s+)[lI](?=[0-9])", "$11");
        return t;
    }

    private static List<Hit> findDates(String rawText) {
        String text = normalizeDateConfusions(rawText);
        List<Hit> hits = new ArrayList<Hit>();
        Matcher m = P_SLASH.matcher(text);
        while (m.find()) {
            long e = toEpoch(Integer.parseInt(m.group(3)),
                             Integer.parseInt(m.group(1)) - 1,
                             Integer.parseInt(m.group(2)));
            if (e > 0) hits.add(new Hit(e, m.start()));
        }
        m = P_ISO.matcher(text);
        while (m.find()) {
            long e = toEpoch(Integer.parseInt(m.group(1)),
                             Integer.parseInt(m.group(2)) - 1,
                             Integer.parseInt(m.group(3)));
            if (e > 0) hits.add(new Hit(e, m.start()));
        }
        m = P_MONTH_FIRST.matcher(text);
        while (m.find()) {
            long e = toEpoch(Integer.parseInt(m.group(3)), monthOf(m.group(1)),
                             Integer.parseInt(m.group(2)));
            if (e > 0) hits.add(new Hit(e, m.start()));
        }
        m = P_DAY_FIRST.matcher(text);
        while (m.find()) {
            long e = toEpoch(Integer.parseInt(m.group(3)), monthOf(m.group(2)),
                             Integer.parseInt(m.group(1)));
            if (e > 0) hits.add(new Hit(e, m.start()));
        }
        m = P_MONTH_NOYEAR.matcher(text);
        while (m.find()) {
            long e = inferYearEpoch(monthOf(m.group(1)), Integer.parseInt(m.group(2)));
            if (e > 0) hits.add(new Hit(e, m.start()));
        }
        m = P_DAY_NOYEAR.matcher(text);
        while (m.find()) {
            long e = inferYearEpoch(monthOf(m.group(2)), Integer.parseInt(m.group(1)));
            if (e > 0) hits.add(new Hit(e, m.start()));
        }
        // Sort by position so output follows reading order; drop exact dupes.
        hits.sort((a, b) -> Integer.compare(a.pos, b.pos));
        List<Hit> out = new ArrayList<Hit>();
        for (Hit h : hits) {
            boolean dupe = false;
            for (Hit o : out) if (o.epoch == h.epoch && Math.abs(o.pos - h.pos) < 12) { dupe = true; break; }
            if (!dupe) out.add(h);
        }
        return out;
    }

    // ---------- amounts ----------

    private static final Pattern P_AMOUNT =
        Pattern.compile("\\$\\s?(\\d{1,3}(?:,\\d{3})*(?:\\.\\d{2})|\\d+(?:\\.\\d{2})?)");

    private static List<String> findAmounts(String text) {
        List<String> out = new ArrayList<String>();
        Matcher m = P_AMOUNT.matcher(text);
        while (m.find()) {
            String a = "$" + m.group(1);
            if (!out.contains(a)) out.add(a);
        }
        return out;
    }

    // ---------- category ----------

    private static final Map<String, String[]> KEYWORDS = new LinkedHashMap<String, String[]>();
    static {
        KEYWORDS.put("Bills", new String[]{
            "bill", "invoice", "amount due", "balance due", "payment due", "due date",
            "electric", "electricity", "utility", "utilities", "water bill", "gas bill",
            "internet", "broadband", "phone bill", "statement", "premium", "subscription",
            "rent", "mortgage", "avangrid", "avista", "energy bill"});
        KEYWORDS.put("Receipts", new String[]{
            "receipt", "subtotal", "total", "tax", "change due", "cashier", "order #",
            "order number", "purchased", "checkout", "refund", "store", "grocery", "groceries"});
        KEYWORDS.put("Wallet", new String[]{
            "passport", "driver license", "drivers license", "license", "insurance",
            "policy", "warranty", "expires", "expiration", "card", "visa", "mastercard",
            "account number", "member id", "id number", "boarding pass", "ticket", "vin"});
        KEYWORDS.put("People", new String[]{
            "birthday", "phone", "email", "address", "contact", "call", "wife", "husband",
            "mom", "dad", "sister", "brother", "friend", "doctor", "dr."});
        KEYWORDS.put("Meetings", new String[]{
            "meeting", "minutes", "agenda", "attendees", "action item", "action items",
            "discussed", "decided", "call notes", "standup", "appointment", "scheduled"});
    }

    private static String classify(String lower) {
        String best = "Notes";
        int bestScore = 0;
        for (Map.Entry<String, String[]> e : KEYWORDS.entrySet()) {
            int score = 0;
            for (String kw : e.getValue()) {
                int idx = 0;
                while ((idx = lower.indexOf(kw, idx)) >= 0) { score++; idx += kw.length(); }
            }
            if (score > bestScore) { bestScore = score; best = e.getKey(); }
        }
        return best;
    }

    // ---------- entry point ----------

    public static Facts analyze(String text) {
        Facts f = new Facts();
        if (text == null || text.trim().isEmpty()) return f;
        List<Hit> hits = findDates(text);
        for (Hit h : hits) f.dates.add(h.epoch);
        // Due date: first date with a due/expires hint in the 48 chars before it.
        for (Hit h : hits) {
            int from = Math.max(0, h.pos - 48);
            String before = text.substring(from, h.pos);
            if (P_DUE_HINT.matcher(before).find()) { f.dueDate = h.epoch; break; }
        }
        if (f.dueDate < 0 && !hits.isEmpty()) {
            // Otherwise the latest date is the best deadline guess.
            long max = -1;
            for (Hit h : hits) if (h.epoch > max) max = h.epoch;
            f.dueDate = max;
        }
        f.amounts = findAmounts(text);
        f.category = classify(text.toLowerCase(Locale.US));
        return f;
    }

    /** Suggest a title from the first meaningful line of text. */
    public static String suggestTitle(String text, String fallback) {
        if (text == null) return fallback;
        for (String line : text.split("\\R")) {
            String t = line.trim();
            if (t.length() >= 3) {
                if (t.length() > 60) t = t.substring(0, 57) + "...";
                return t;
            }
        }
        return fallback;
    }

    public static String formatDate(long epoch) {
        if (epoch <= 0) return "";
        return new SimpleDateFormat("EEE, MMM d, yyyy", Locale.US)
            .format(new java.util.Date(epoch));
    }

    /** "in 5 days" / "3 days ago" / "today" / "tomorrow" relative to now. */
    public static String relativeDay(long epoch, long now) {
        long dayMs = 24L * 60 * 60 * 1000;
        long d = Math.round((double) (startOfDay(epoch) - startOfDay(now)) / dayMs);
        if (d == 0) return "today";
        if (d == 1) return "tomorrow";
        if (d == -1) return "yesterday";
        if (d > 1) return "in " + d + " days";
        return (-d) + " days ago";
    }

    private static long startOfDay(long t) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** Parse a date we formatted ourselves back (used by tests). */
    public static long parseIso(String iso) throws ParseException {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso).getTime();
    }
}
