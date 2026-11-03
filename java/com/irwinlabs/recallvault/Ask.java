package com.irwinlabs.recallvault;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
        /** True when a newer near-duplicate makes this one look stale. */
        public boolean superseded;
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

    // ---------- smarter keyword matching (v0.71.0) ----------
    // Three fixes from a measured eval (tools/run-eval.sh):
    //  1. Short words (<=3 chars) match on word boundaries only:
    //     "out" used to score inside "Router" and beat the trash
    //     note for "when do the bins go out".
    //  2. y<->ies variants: "utility" is not a substring of
    //     "utilities", so the utility bill lost to the water bill.
    //  3. Query-time synonym expansion at half weight, plus IDF
    //     weights so a rare word (a name) counts more than a
    //     common one. The desktop mirrors 2 and 3.

    private static final Map<String, String[]> SYNONYMS = new HashMap<String, String[]>();
    static {
        String[][] groups = {
            {"code", "pin", "passcode"},
            {"wifi", "wireless", "network"},
            {"password", "passcode", "login"},
            {"gate", "door", "entry"},
            {"trash", "bins", "garbage", "pickup"},
            {"medicine", "medication", "prescriptions", "pharmacy", "meds"},
            {"utility", "utilities"},
            {"bill", "statement", "invoice"},
            {"appointment", "visit"},
            {"car", "truck", "vehicle"},
            {"phone", "number", "cell"},
        };
        Map<String, Set<String>> acc = new HashMap<String, Set<String>>();
        for (String[] g : groups) {
            for (String w : g) {
                Set<String> s = acc.get(w);
                if (s == null) { s = new HashSet<String>(); acc.put(w, s); }
                for (String o : g) if (!o.equals(w)) s.add(o);
            }
        }
        for (Map.Entry<String, Set<String>> e : acc.entrySet()) {
            SYNONYMS.put(e.getKey(), e.getValue().toArray(new String[0]));
        }
    }

    static String[] synonymsOf(String kw) {
        String[] s = SYNONYMS.get(kw);
        return s == null ? new String[0] : s;
    }

    /** kw plus its y/ies spelling variants. */
    private static List<String> variants(String kw) {
        List<String> out = new ArrayList<String>();
        out.add(kw);
        if (kw.length() > 3 && kw.endsWith("y")) out.add(kw.substring(0, kw.length() - 1) + "ies");
        if (kw.length() > 4 && kw.endsWith("ies")) out.add(kw.substring(0, kw.length() - 3) + "y");
        return out;
    }

    /** Hits for one keyword in one text: best variant wins;
     *  short words need word boundaries. */
    private static int hitsFor(String hayLower, String kw) {
        int best = 0;
        for (String v : variants(kw)) {
            int n = v.length() <= 3 ? countBoundary(hayLower, v) : countHits(hayLower, v);
            if (n > best) best = n;
        }
        return best;
    }

    private static int countBoundary(String hayLower, String needle) {
        int n = 0, idx = 0;
        while ((idx = hayLower.indexOf(needle, idx)) >= 0) {
            int end = idx + needle.length();
            boolean leftOk = idx == 0 || !Character.isLetterOrDigit(hayLower.charAt(idx - 1));
            boolean rightOk = end >= hayLower.length()
                || !Character.isLetterOrDigit(hayLower.charAt(end));
            if (leftOk && rightOk) n++;
            idx++;
        }
        return n;
    }

    public static double keywordScore(Item it, List<String> kws) {
        if (kws.isEmpty()) return 0;
        List<Double> w = new ArrayList<Double>();
        for (int i = 0; i < kws.size(); i++) w.add(1.0);
        return scoreTerms(it, kws, w);
    }

    private static double scoreTerms(Item it, List<String> terms, List<Double> weights) {
        String title = it.title.toLowerCase(Locale.US);
        String cat = it.category.toLowerCase(Locale.US);
        String body = (it.body + "\n" + it.extracted).toLowerCase(Locale.US);
        double score = 0;
        for (int i = 0; i < terms.size(); i++) {
            String kw = terms.get(i);
            double wgt = weights.get(i);
            // Body counts PRESENCE, not frequency (v0.71.0): a
            // library card that says "number" twice is not more
            // about numbers than the landlord note that names
            // Dana once - repetition in short memories is noise.
            score += wgt * (5.0 * hitsFor(title, kw)
                + 4.0 * hitsFor(cat, kw)
                + 2.0 * (hitsFor(body, kw) > 0 ? 1 : 0));
            if (!it.amounts.isEmpty() && it.amounts.contains(kw)) score += wgt * 3.0;
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
        // IDF weights (rare words count more) + synonym expansion
        // at half weight - see the v0.71.0 note at countHits.
        List<String> terms = new ArrayList<String>(kws);
        List<Double> weights = new ArrayList<Double>();
        int nItems = Math.max(1, items.size());
        for (String kw : kws) {
            int df = 0;
            for (Item it : items) {
                String hay = (it.title + "\n" + it.category + "\n"
                    + it.body + "\n" + it.extracted).toLowerCase(Locale.US);
                if (hitsFor(hay, kw) > 0) df++;
            }
            weights.add(Math.min(2.2, 1.0 + Math.log((nItems + 1.0) / (df + 1.0))));
        }
        Set<String> seenTerms = new HashSet<String>(kws);
        for (int i = 0; i < kws.size(); i++) {
            for (String syn : synonymsOf(kws.get(i))) {
                if (seenTerms.add(syn)) {
                    terms.add(syn);
                    weights.add(weights.get(i) * 0.5);
                }
            }
        }
        List<Result> out = new ArrayList<Result>();
        for (Item it : items) {
            double kw = scoreTerms(it, terms, weights);
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
        applySuperseded(out);
        out.sort((a, b) -> Double.compare(b.score, a.score));
        if (out.size() > 5) return new ArrayList<Result>(out.subList(0, 5));
        return out;
    }

    /**
     * Superseded answers sink (v0.70.0): when two matched
     * memories are plainly the same fact re-filed - near-identical
     * titles, different amounts or dates - the older one is
     * probably stale (an old gate code, last month's bill). It
     * stays in the results, marked, at roughly half score, so a
     * fresher version answers first. Nothing is hidden or deleted;
     * the web just leans toward what's current.
     */
    private static void applySuperseded(List<Result> out) {
        for (Result r : out) {
            Set<String> rt = new HashSet<String>(keywords(r.item.title));
            if (rt.isEmpty()) continue;
            for (Result o : out) {
                if (o == r) continue;
                if (o.item.createdAt <= r.item.createdAt + 24L * 3600 * 1000) continue;
                Set<String> ot = new HashSet<String>(keywords(o.item.title));
                if (ot.isEmpty()) continue;
                Set<String> both = new HashSet<String>(rt);
                both.retainAll(ot);
                Set<String> either = new HashSet<String>(rt);
                either.addAll(ot);
                double jaccard = either.isEmpty() ? 0
                    : (double) both.size() / (double) either.size();
                boolean factsDiffer = !String.valueOf(r.item.amounts)
                        .equals(String.valueOf(o.item.amounts))
                    || r.item.reminderAt != o.item.reminderAt;
                if (jaccard >= 0.6 && factsDiffer) {
                    r.score *= 0.45;
                    r.superseded = true;
                    break;
                }
            }
        }
    }

    /**
     * One honest line about where an answer came from (v0.70.0):
     * how many memories matched, and how fresh the freshest is.
     */
    public static String provenance(List<Result> results) {
        if (results == null || results.isEmpty()) return "";
        long newest = 0;
        for (Result r : results) newest = Math.max(newest, r.item.createdAt);
        return "From " + results.size()
            + (results.size() == 1 ? " memory" : " memories")
            + " · newest " + Extract.formatDate(newest);
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
        /**
         * The items that make up the total, when the summary is a
         * literal sum (v0.52.0): the Ask screen renders them as a
         * ledger under the answer. Empty for verdict-style summaries.
         */
        public final List<Line> lines = new ArrayList<Line>();
    }

    /** One ledger row of a Summary: an item and what it contributed. */
    public static final class Line {
        public Item item;
        public long cents;
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

    private static final String[] MONTH_NAMES = {
        "january", "february", "march", "april", "may", "june",
        "july", "august", "september", "october", "november", "december"};

    private static final java.util.regex.Pattern MONTH_TOKEN = java.util.regex.Pattern.compile(
        "\\b(january|february|march|april|may|june|july|august|september|october|november|december"
        + "|jan|feb|mar|apr|jun|jul|aug|sep|sept|oct|nov|dec)(?:\\s+(\\d{4}))?\\b",
        java.util.regex.Pattern.CASE_INSENSITIVE);

    static boolean isMonthWord(String tok) {
        String t = tok.toLowerCase(Locale.US);
        for (String m : MONTH_NAMES) {
            if (m.equals(t) || m.startsWith(t)) return true;
        }
        return false;
    }

    /**
     * The named-month window in a question ("in November", "October
     * 2025"), as {startMs, endMsExclusive}; null when no month is
     * named. A year-less month means its closest instance to now -
     * "in October" asked in November is last month, not next year.
     * (v0.32.0: named months used to leak into the topic keywords,
     * so "how much do I owe in November" never actually scoped.)
     */
    public static long[] monthWindowMs(String q, long nowMs) {
        if (q == null) return null;
        java.util.regex.Matcher m = MONTH_TOKEN.matcher(q);
        if (!m.find()) return null;
        String tok = m.group(1).toLowerCase(Locale.US);
        int month = -1;
        for (int i = 0; i < 12; i++) {
            if (MONTH_NAMES[i].equals(tok) || MONTH_NAMES[i].startsWith(tok)) { month = i; break; }
        }
        if (month < 0) return null;
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(nowMs);
        int year;
        if (m.group(2) != null) {
            year = Integer.parseInt(m.group(2));
        } else {
            year = cal.get(java.util.Calendar.YEAR);
            int cur = cal.get(java.util.Calendar.MONTH);
            if (month - cur > 6) year--;
            else if (cur - month > 6) year++;
        }
        java.util.Calendar start = java.util.Calendar.getInstance();
        start.clear();
        start.set(year, month, 1);
        java.util.Calendar end = (java.util.Calendar) start.clone();
        end.add(java.util.Calendar.MONTH, 1);
        return new long[]{start.getTimeInMillis(), end.getTimeInMillis()};
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

    static long startOfToday(long nowMs) {
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
        // A named month IS the window (v0.32.0).
        long[] monthWin = monthWindowMs(query, nowMs);
        String monthLabel = null;
        if (monthWin != null) {
            from = monthWin[0];
            to = monthWin[1];
            java.util.Calendar mc = java.util.Calendar.getInstance();
            mc.setTimeInMillis(monthWin[0]);
            monthLabel = mc.getDisplayName(java.util.Calendar.MONTH,
                java.util.Calendar.LONG, Locale.US);
        }
        // Topical keywords = question words minus the frame vocabulary
        // (owe/due/bill/month/...). "How much do I owe this month" has
        // NO topical word: the frame itself is the filter, and every
        // dated amount in it counts. "Electric bills due" keeps
        // "electric" as a real topic filter.
        List<String> kws = new ArrayList<String>();
        for (String k : keywords(query)) {
            if (!FRAME_WORDS.contains(k) && !isMonthWord(k) && !k.matches("\\d{4}")) kws.add(k);
        }
        int count = 0;
        long total = 0;
        long earliest = 0;
        List<Line> lines = new ArrayList<Line>();
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
                // A due answer only counts items that carry a due
                // date at all (reminderAt <= 0 means none - a stray
                // 0 must not slip past the from=0 no-window case).
                if (it.reminderAt <= 0 || it.reminderAt < from || it.reminderAt >= to) continue;
            }
            long cents = principalCents(it);
            if (cents < 0) continue;
            count++;
            total += cents;
            Line ln = new Line();
            ln.item = it;
            ln.cents = cents;
            lines.add(ln);
            if (it.reminderAt > 0 && (earliest == 0 || it.reminderAt < earliest)) earliest = it.reminderAt;
        }
        if (count < 2) return null;
        // Ledger order: what is due first (undated last), so the
        // answer reads like a bill list, not a search ranking.
        lines.sort((a, b) -> {
            long da = a.item.reminderAt > 0 ? a.item.reminderAt : Long.MAX_VALUE;
            long db2 = b.item.reminderAt > 0 ? b.item.reminderAt : Long.MAX_VALUE;
            if (da != db2) return Long.compare(da, db2);
            return Long.compare(a.item.createdAt, b.item.createdAt);
        });
        Summary s = new Summary();
        s.count = count;
        s.totalCents = total;
        s.earliestDue = earliest;
        s.lines.addAll(lines);
        StringBuilder sb = new StringBuilder();
        sb.append(count).append(dueFrame ? " due" : " matching");
        if (monthLabel != null) {
            sb.append(" in ").append(monthLabel);
        } else if (to != Long.MAX_VALUE) {
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
