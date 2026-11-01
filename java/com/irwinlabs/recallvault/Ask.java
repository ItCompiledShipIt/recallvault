package com.irwinlabs.recallvault;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Ask - answers a plain-words question from the vault.
 *
 * Hybrid scoring, the same shape as MemX's promise ("the answer comes
 * back with the file it came from, even when the file has none of
 * those exact words"):
 *   1. Keyword score over title / category / body / extracted text.
 *   2. Semantic score from EmbeddingGemma 2 vectors when available,
 *      re-based against the observed EmbeddingGemma cosine floor
 *      (~0.62 for unrelated text on chris's server, verified Oct 31 2026)
 *      so unrelated items score near zero.
 * The final answer is composed from extracted facts (due date, amount)
 * when the question asks for them, plus a snippet, and always names
 * its source item.
 */
public final class Ask {

    private Ask() {}

    public static class Result {
        public Item item;
        public double score;
        public double keywordScore;
        public double semanticScore; // 0 when no vector was used
        /** True when the winning semantic match came from the photo itself. */
        public boolean viaPhoto;
        public String answer;
    }

    private static final Set<String> STOP = new HashSet<String>();
    static {
        String[] w = {"when","is","my","the","what","where","did","i","save","saved","find",
            "show","me","how","much","was","for","a","an","does","do","it","of","to","in","on",
            "and","or","that","this","with","about","please","tell","give","get","which","who",
            "are","were","will","would","can","could","you","have","has","had","there","any"};
        for (String s : w) STOP.add(s);
    }

    public static List<String> keywords(String query) {
        List<String> out = new ArrayList<String>();
        if (query == null) return out;
        for (String tok : query.toLowerCase(Locale.US).split("[^a-z0-9$]+")) {
            if (tok.length() < 2) continue;
            if (STOP.contains(tok)) continue;
            if (!out.contains(tok)) out.add(tok);
        }
        return out;
    }

    private static int countHits(String hayLower, String needle) {
        int n = 0, idx = 0;
        while ((idx = hayLower.indexOf(needle, idx)) >= 0) { n++; idx += needle.length(); }
        return n;
    }

    public static double keywordScore(Item it, List<String> kws) {
        if (kws.isEmpty()) return 0;
        String title = it.title.toLowerCase(Locale.US);
        String cat = it.category.toLowerCase(Locale.US);
        String body = (it.body + "\n" + it.extracted).toLowerCase(Locale.US);
        double score = 0;
        for (String kw : kws) {
            score += 5.0 * countHits(title, kw);
            score += 4.0 * countHits(cat, kw);
            score += 2.0 * Math.min(3, countHits(body, kw));
            if (!it.amounts.isEmpty() && it.amounts.contains(kw)) score += 3.0;
        }
        return score;
    }

    /** Re-base a raw EmbeddingGemma cosine (floor ~0.62) into 0..1 usefulness. */
    public static double semanticUsefulness(double cosine) {
        return rebase(cosine, 0.62);
    }

    /**
     * Same re-base for the on-device int4 LiteRT build's vectors. Its
     * unrelated-text floor is lower than the Q8 server build's; the
     * 0.55 constant was validated behaviorally on the S24 (Oct 31,
     * 2026): with it, a no-shared-words question ranks the right
     * memory first in the on-device space.
     */
    public static double semanticUsefulnessLocal(double cosine) {
        return rebase(cosine, 0.55);
    }

    private static double rebase(double cosine, double floor) {
        double u = (cosine - floor) / (0.95 - floor);
        if (u < 0) return 0;
        if (u > 1) return 1;
        return u;
    }

    public static List<Result> search(List<Item> items, String query, float[] queryVec) {
        return search(items, query, queryVec, false);
    }

    /** useLocal: compare against the on-device embedding space instead of the server one. */
    public static List<Result> search(List<Item> items, String query, float[] queryVec, boolean useLocal) {
        List<String> kws = keywords(query);
        boolean wantsWhen = asksWhen(query);
        boolean wantsAmount = asksAmount(query);
        List<Result> out = new ArrayList<Result>();
        for (Item it : items) {
            double kw = keywordScore(it, kws);
            double sem = 0;
            boolean viaPhoto = false;
            if (queryVec != null) {
                float[] iv = Embed.decode(useLocal ? it.embeddingLocal : it.embedding);
                if (iv != null) {
                    double cos = Embed.cosine(queryVec, iv);
                    sem = useLocal ? semanticUsefulnessLocal(cos) : semanticUsefulness(cos);
                }
                if (useLocal) {
                    // The vision tower shares the text space: a photo can
                    // win on what is IN the picture, not just its words.
                    float[] pv = Embed.decode(it.embeddingImage);
                    if (pv != null) {
                        double imgSem = semanticUsefulnessLocal(Embed.cosine(queryVec, pv));
                        if (imgSem > sem) {
                            sem = imgSem;
                            viaPhoto = true;
                        }
                    }
                }
            }
            double score = kw + sem * 12.0;
            if (score <= 0.5) continue;
            Result r = new Result();
            r.item = it;
            r.score = score;
            r.keywordScore = kw;
            r.semanticScore = sem;
            r.viaPhoto = viaPhoto;
            r.answer = composeAnswer(it, query, wantsWhen, wantsAmount);
            out.add(r);
        }
        out.sort((a, b) -> Double.compare(b.score, a.score));
        if (out.size() > 5) return new ArrayList<Result>(out.subList(0, 5));
        return out;
    }

    // ---------- aggregation: answers that add up (v0.9.0) ----------

    private static final Set<String> FRAME_WORDS = new HashSet<String>();
    static {
        String[] w = {"owe", "owed", "due", "bill", "bills", "month", "monthly",
            "week", "weekly", "total", "amount", "everything", "coming", "all",
            "next", "days", "much", "pay", "payment", "payments"};
        for (String s : w) FRAME_WORDS.add(s);
    }

    public static final class Summary {
        public int count;
        public long totalCents;
        public long earliestDue; // 0 when none of the items carries a date
        public String text;
    }

    private static final java.util.regex.Pattern P_MONEY =
        java.util.regex.Pattern.compile("\\$\\s?(\\d{1,3}(?:,\\d{3})*(?:\\.\\d{2})|\\d+(?:\\.\\d{2})?)");

    /** "$1,234.56" -> 123456. Returns -1 when there is no money in the string. */
    public static long moneyToCents(String s) {
        if (s == null) return -1;
        java.util.regex.Matcher m = P_MONEY.matcher(s);
        if (!m.find()) return -1;
        return groupToCents(m.group(1));
    }

    private static long groupToCents(String g) {
        try {
            String clean = g.replace(",", "");
            int dot = clean.indexOf('.');
            if (dot < 0) return Long.parseLong(clean) * 100;
            long dollars = Long.parseLong(clean.substring(0, dot));
            String cents = (clean.substring(dot + 1) + "00").substring(0, 2);
            return dollars * 100 + Long.parseLong(cents);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * An item's principal amount in cents: the FIRST amount Extract
     * stored (bills lead with their total; later amounts on the same
     * item are usually parts of it). -1 when the item has no amount.
     */
    public static long principalCents(Item it) {
        if (it == null || it.amounts == null || it.amounts.isEmpty()) return -1;
        return moneyToCents(it.amounts);
    }

    public static String formatMoney(long cents) {
        long d = cents / 100;
        long c = Math.abs(cents % 100);
        String ds = String.valueOf(d);
        StringBuilder sb = new StringBuilder("$");
        for (int i = 0; i < ds.length(); i++) {
            if (i > 0 && (ds.length() - i) % 3 == 0) sb.append(',');
            sb.append(ds.charAt(i));
        }
        sb.append('.');
        if (c < 10) sb.append('0');
        sb.append(c);
        return sb.toString();
    }

    /** The question wants a set answer (a total / a list), not one best card. */
    public static boolean asksAggregate(String q) {
        if (q == null) return false;
        String s = q.toLowerCase(Locale.US);
        return asksAmount(q)
            || s.contains("what's due") || s.contains("what is due")
            || s.contains("what do i owe") || s.contains("all my")
            || s.contains("everything due") || s.contains("coming due")
            || s.contains("due this") || s.contains("bills");
    }

    /** Window end (exclusive) implied by the question; Long.MAX_VALUE = no window. */
    public static long windowEndMs(String q, long nowMs) {
        if (q == null) return Long.MAX_VALUE;
        String s = q.toLowerCase(Locale.US);
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(nowMs);
        if (s.contains("this week") || s.contains("next 7 days")) {
            return nowMs + 7L * 24 * 3600 * 1000;
        }
        if (s.contains("this month") || s.contains("next 30 days")) {
            cal.add(java.util.Calendar.MONTH, 1);
            cal.set(java.util.Calendar.DAY_OF_MONTH, 1);
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
            cal.set(java.util.Calendar.MINUTE, 0);
            cal.set(java.util.Calendar.SECOND, 0);
            cal.set(java.util.Calendar.MILLISECOND, 0);
            return cal.getTimeInMillis();
        }
        return Long.MAX_VALUE;
    }

    private static long startOfToday(long nowMs) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(nowMs);
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
        cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    /**
     * The set answer for an aggregate question: every item that both
     * matches the question (keyword or semantic, same bar as search)
     * and fits the frame - dated items inside the window for due
     * questions, amount-carrying items for total questions. Returns
     * null when fewer than 2 items contribute (a single match keeps
     * the ordinary best-card answer).
     */
    public static Summary summarize(List<Item> items, String query, float[] queryVec, long nowMs) {
        if (!asksAggregate(query)) return null;
        boolean dueFrame = asksWhen(query)
            || query.toLowerCase(Locale.US).contains("due")
            || query.toLowerCase(Locale.US).contains("owe");
        long to = windowEndMs(query, nowMs);
        // No explicit window: overdue counts too (it is the most owed).
        // With a window word, the frame starts today.
        long from = (to == Long.MAX_VALUE) ? 0 : startOfToday(nowMs);
        // Topical keywords = question words minus the frame vocabulary
        // (owe/due/bill/month/...). "How much do I owe this month" has
        // NO topical word: the frame itself is the filter, and every
        // dated amount in it counts. "Electric bills due" keeps
        // "electric" as a real topic filter.
        List<String> kws = new ArrayList<String>();
        for (String k : keywords(query)) {
            if (!FRAME_WORDS.contains(k)) kws.add(k);
        }
        int count = 0;
        long total = 0;
        long earliest = 0;
        for (Item it : items) {
            if (it.handledAt > 0) continue; // paid/done no longer counts as owed
            // Relevance: no topic -> in-frame is enough; else keyword
            // hit or a real semantic match in the local space.
            boolean relevant = kws.isEmpty() || keywordScore(it, kws) > 0;
            if (!relevant && queryVec != null) {
                float[] iv = Embed.decode(it.embeddingLocal);
                if (iv == null) iv = Embed.decode(it.embeddingImage);
                if (iv != null && semanticUsefulnessLocal(Embed.cosine(queryVec, iv)) > 0.25) relevant = true;
            }
            if (!relevant) continue;
            if (dueFrame) {
                if (it.reminderAt < from || it.reminderAt >= to) continue;
            }
            long cents = principalCents(it);
            if (cents < 0) continue;
            count++;
            total += cents;
            if (it.reminderAt > 0 && (earliest == 0 || it.reminderAt < earliest)) earliest = it.reminderAt;
        }
        if (count < 2) return null;
        Summary s = new Summary();
        s.count = count;
        s.totalCents = total;
        s.earliestDue = earliest;
        StringBuilder sb = new StringBuilder();
        sb.append(count).append(dueFrame ? " due" : " matching");
        if (to != Long.MAX_VALUE) {
            sb.append(query.toLowerCase(Locale.US).contains("month") ? " this month" : " this week");
        }
        sb.append(", totaling ").append(formatMoney(total)).append('.');
        if (earliest > 0) {
            sb.append(" Next: ").append(Extract.formatDate(earliest))
              .append(" (").append(Extract.relativeDay(earliest, nowMs)).append(").");
        }
        s.text = sb.toString();
        return s;
    }

    /** Reminders digest: what is due in the next 7 days, and for how much. */
    public static Summary dueDigest(List<Item> items, long nowMs) {
        long from = startOfToday(nowMs);
        long to = nowMs + 7L * 24 * 3600 * 1000;
        int count = 0;
        long total = 0;
        long earliest = 0;
        for (Item it : items) {
            if (it.handledAt > 0) continue;
            if (it.reminderAt < from || it.reminderAt >= to) continue;
            count++;
            long cents = principalCents(it);
            if (cents > 0) total += cents;
            if (earliest == 0 || it.reminderAt < earliest) earliest = it.reminderAt;
        }
        if (count == 0) return null;
        Summary s = new Summary();
        s.count = count;
        s.totalCents = total;
        s.earliestDue = earliest;
        s.text = count + (count == 1 ? " memory" : " memories") + " due this week"
            + (total > 0 ? ", " + formatMoney(total) + " in amounts" : "")
            + ". Next: " + Extract.formatDate(earliest)
            + " (" + Extract.relativeDay(earliest, nowMs) + ").";
        return s;
    }

    public static boolean asksWhen(String q) {
        if (q == null) return false;
        String s = q.toLowerCase(Locale.US);
        return s.contains("when") || s.contains("due") || s.contains("expire")
            || s.contains("deadline") || s.contains("renew");
    }

    public static boolean asksAmount(String q) {
        if (q == null) return false;
        String s = q.toLowerCase(Locale.US);
        return s.contains("how much") || s.contains("cost") || s.contains("amount")
            || s.contains("price") || s.contains("owe") || s.contains("total");
    }

    public static String composeAnswer(Item it, String query, boolean wantsWhen, boolean wantsAmount) {
        StringBuilder sb = new StringBuilder();
        if (wantsWhen && it.reminderAt > 0) {
            sb.append(it.title).append(" — due ").append(Extract.formatDate(it.reminderAt))
              .append(" (").append(Extract.relativeDay(it.reminderAt, System.currentTimeMillis())).append(").");
        }
        if (wantsAmount && !it.amounts.isEmpty()) {
            if (sb.length() > 0) sb.append(" ");
            sb.append("Amount: ").append(it.amounts.replace(",", ", ")).append(".");
        }
        if (sb.length() == 0) {
            String snippet = snippet(it, query);
            if (!snippet.isEmpty()) sb.append(snippet);
            else sb.append(it.title);
        }
        return sb.toString();
    }

    /** A ~140-char excerpt around the first keyword hit, or the start of the text. */
    public static String snippet(Item it, String query) {
        String text = (it.body != null && !it.body.trim().isEmpty()) ? it.body : it.extracted;
        if (text == null) return "";
        text = text.replaceAll("\\s+", " ").trim();
        if (text.isEmpty()) return "";
        String lower = text.toLowerCase(Locale.US);
        int pos = -1;
        for (String kw : keywords(query)) {
            int p = lower.indexOf(kw);
            if (p >= 0 && (pos < 0 || p < pos)) pos = p;
        }
        if (pos < 0) pos = 0;
        int start = Math.max(0, pos - 40);
        int end = Math.min(text.length(), start + 140);
        String s = text.substring(start, end);
        if (start > 0) s = "..." + s;
        if (end < text.length()) s = s + "...";
        return s;
    }
}
