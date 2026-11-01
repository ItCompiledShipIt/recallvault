package com.irwinlabs.recallvault;

import java.util.Calendar;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Follow-up questions (v0.32.0): Ask remembers the last question's
 * time window, so a short follow-up - "and November?", "what about
 * last month", just "December?" - rewrites itself against the
 * previous question instead of starting from nothing. Only the
 * window moves; the question itself is the one already asked.
 */
public final class FollowUp {

    private FollowUp() {}

    private static final String[] MONTHS = {
        "january", "february", "march", "april", "may", "june",
        "july", "august", "september", "october", "november", "december"};

    private static final Pattern MONTH_TOKEN = Pattern.compile(
        "\\b(january|february|march|april|may|june|july|august|september|october|november|december"
        + "|jan|feb|mar|apr|jun|jul|aug|sep|sept|oct|nov|dec)\\b",
        Pattern.CASE_INSENSITIVE);

    private static int monthIndex(String token) {
        String t = token.toLowerCase(Locale.US);
        for (int i = 0; i < 12; i++) {
            if (MONTHS[i].equals(t) || MONTHS[i].startsWith(t)) return i;
        }
        return -1;
    }

    /**
     * Rewrite newQuery against lastQuery, or return null when the
     * new text is a fresh question of its own. Only short, clearly
     * referential follow-ups resolve.
     */
    public static String resolve(String lastQuery, String newQuery, long nowMs) {
        if (lastQuery == null || lastQuery.isEmpty() || newQuery == null) return null;
        String q = newQuery.trim().replaceAll("[?.!]+$", "").trim();
        String low = q.toLowerCase(Locale.US);
        // Strip the connective tissue: "and ...", "what about ...".
        if (low.startsWith("and ")) { q = q.substring(4); low = low.substring(4); }
        if (low.startsWith("what about ")) { q = q.substring(11); low = low.substring(11); }
        if (low.startsWith("in ")) { q = q.substring(3); low = low.substring(3); }
        String[] words = low.split("\\s+");
        if (words.length > 3) return null;

        // Target window: an explicit month, or a relative phrase.
        int targetMonth = -1;
        if (words.length >= 1) {
            int mi = monthIndex(words[0]);
            if (mi >= 0 && (words.length == 1
                    || (words.length == 2 && words[1].matches("\\d{4}")))) {
                targetMonth = mi;
            }
        }
        if (targetMonth < 0) {
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(nowMs);
            int thisMonth = cal.get(Calendar.MONTH);
            if (low.equals("last month")) targetMonth = (thisMonth + 11) % 12;
            else if (low.equals("this month")) targetMonth = thisMonth;
            else if (low.equals("next month")) targetMonth = (thisMonth + 1) % 12;
            else return null;
        }
        String target = MONTHS[targetMonth];
        target = Character.toUpperCase(target.charAt(0)) + target.substring(1);

        // Swap the month in the last question, or add the window.
        Matcher m = MONTH_TOKEN.matcher(lastQuery);
        if (m.find()) {
            return m.replaceFirst(target);
        }
        String base = lastQuery.replaceAll("[?.!]+$", "").trim();
        return base + " in " + target;
    }
}
