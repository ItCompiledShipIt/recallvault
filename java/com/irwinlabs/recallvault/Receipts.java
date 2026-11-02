package com.irwinlabs.recallvault;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Receipt mode (v0.42.0): a scanned receipt files as a structured
 * expense instead of a photo with words. The parser pulls the
 * store (first real line), the total (the TOTAL line - never the
 * cash tendered or the subtotal), the tax, and the date out of
 * the OCR text. Expenses then answer Ask ("how much did I spend
 * in October") and export as a spreadsheet-ready CSV.
 */
public final class Receipts {

    private Receipts() {}

    public static final String CATEGORY = "Receipts";

    public static final class Parsed {
        public String store;
        public long totalCents;
        public long taxCents;   // -1 when no tax line
        public long dateMs;     // -1 when no date found
    }

    private static final Pattern AMOUNT =
        Pattern.compile("\\$?\\s*(\\d{1,3}(?:,\\d{3})*\\.\\d{2})\\b");

    private static long centsOf(String s) {
        try {
            double d = Double.parseDouble(s.replace(",", ""));
            return Math.round(d * 100);
        } catch (Exception e) {
            return -1;
        }
    }

    private static Long amountOn(String line) {
        Matcher m = AMOUNT.matcher(line);
        Long last = null;
        while (m.find()) {
            long c = centsOf(m.group(1));
            if (c >= 0) last = c;
        }
        return last;
    }

    /** Parse receipt OCR text; null when no total can be found. */
    public static Parsed parse(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        String[] lines = text.split("\\n");
        Long grandTotal = null, total = null, tax = null, largest = null;
        String store = null;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            String low = line.toLowerCase(Locale.US);
            Long amt = amountOn(line);
            if (amt != null && (largest == null || amt > largest)) largest = amt;
            if (amt != null) {
                if (low.contains("grand total") || low.contains("amount due")
                    || low.contains("balance due")) {
                    grandTotal = amt;
                } else if (low.contains("total") && !low.contains("subtotal")
                    && !low.contains("sub total")) {
                    total = amt;
                } else if (low.contains("tax")) {
                    tax = amt;
                }
            }
            if (store == null && line.length() >= 3 && line.matches(".*[A-Za-z]{3,}.*")
                && !low.contains("total") && amt == null) {
                store = line.length() > 40 ? line.substring(0, 40).trim() : line;
            }
        }
        Long chosen = grandTotal != null ? grandTotal : (total != null ? total : largest);
        if (chosen == null || chosen <= 0) return null;
        Parsed p = new Parsed();
        p.store = store != null ? store : "Store";
        p.totalCents = chosen;
        p.taxCents = tax != null ? tax : -1;
        List<Long> dates = Extract.analyze(text).dates;
        p.dateMs = dates.isEmpty() ? -1 : dates.get(0);
        return p;
    }

    /** "Walmart receipt" -> "Walmart". */
    public static String storeOf(Item it) {
        String t = it.title == null ? "" : it.title;
        if (t.toLowerCase(Locale.US).endsWith(" receipt")) {
            return t.substring(0, t.length() - " receipt".length());
        }
        return t;
    }

    /** Refine a freshly analyzed item into a receipt, when it is one. */
    public static boolean refine(Item it, String machineText, boolean userTitled) {
        Parsed p = parse(machineText);
        if (p == null) return false;
        it.category = CATEGORY;
        if (!userTitled) it.title = p.store + " receipt";
        it.amounts = Ask.formatMoney(p.totalCents);
        it.reminderAt = -1; // a purchase record, not a deadline
        return true;
    }

    /** Ask hook: spending questions over the Receipts category. */
    public static Ask.Summary answer(List<Item> items, String query, long nowMs) {
        String q = query.toLowerCase(Locale.US);
        if (!q.contains("spend") && !q.contains("spent") && !q.contains("spending")) return null;
        List<Item> receipts = new ArrayList<Item>();
        for (Item it : items) {
            if (CATEGORY.equals(it.category)) receipts.add(it);
        }
        Ask.Summary s = new Ask.Summary();
        if (receipts.isEmpty()) {
            s.text = "No receipts saved yet - scan one and it files itself as an expense.";
            return s;
        }
        // Optional store scope: a saved store's name inside the question.
        String storeScope = null, scopeDisplay = null;
        for (Item it : receipts) {
            String st = storeOf(it).toLowerCase(Locale.US);
            if (st.length() >= 3 && q.contains(st)) { storeScope = st; scopeDisplay = storeOf(it); break; }
        }
        long[] win = Ask.monthWindowMs(q, nowMs);
        long total = 0;
        int count = 0;
        Item biggest = null;
        Map<String, Long> byStore = new LinkedHashMap<String, Long>();
        for (Item it : receipts) {
            if (win != null && (it.createdAt < win[0] || it.createdAt >= win[1])) continue;
            if (storeScope != null && !storeOf(it).toLowerCase(Locale.US).equals(storeScope)) continue;
            long c = Ask.principalCents(it);
            if (c <= 0) continue;
            total += c;
            count++;
            Ask.Line ln = new Ask.Line();
            ln.item = it;
            ln.cents = c;
            s.lines.add(ln);
            if (biggest == null || c > Ask.principalCents(biggest)) biggest = it;
            String st = storeOf(it);
            Long cur = byStore.get(st);
            byStore.put(st, (cur == null ? 0 : cur) + c);
        }
        String scope = storeScope != null ? " at " + scopeDisplay : "";
        String when = "";
        if (win != null) {
            java.util.Calendar c = java.util.Calendar.getInstance();
            c.setTimeInMillis(win[0]);
            when = " in " + Report.monthName(c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH));
        }
        if (count == 0) {
            s.text = "No receipts" + scope + when + " yet.";
            return s;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Spending").append(scope).append(when).append(": ")
          .append(Ask.formatMoney(total)).append(" across ").append(count)
          .append(count == 1 ? " receipt." : " receipts.");
        if (biggest != null) {
            sb.append("\nBiggest: ").append(biggest.title).append(" - ")
              .append(Ask.formatMoney(Ask.principalCents(biggest))).append(".");
        }
        if (storeScope == null && byStore.size() > 1) {
            List<Map.Entry<String, Long>> stores =
                new ArrayList<Map.Entry<String, Long>>(byStore.entrySet());
            java.util.Collections.sort(stores, new java.util.Comparator<Map.Entry<String, Long>>() {
                @Override public int compare(Map.Entry<String, Long> a, Map.Entry<String, Long> b) {
                    return Long.compare(b.getValue(), a.getValue());
                }
            });
            sb.append("\nBy store:");
            for (int i = 0; i < stores.size() && i < 3; i++) {
                sb.append("\n").append(stores.get(i).getKey()).append(": ")
                  .append(Ask.formatMoney(stores.get(i).getValue()));
            }
        }
        s.text = sb.toString();
        // Ledger order (v0.52.0): most recent spending first.
        java.util.Collections.sort(s.lines, new java.util.Comparator<Ask.Line>() {
            @Override public int compare(Ask.Line a, Ask.Line b) {
                return Long.compare(b.item.createdAt, a.item.createdAt);
            }
        });
        return s;
    }

    /** Spreadsheet export: one row per receipt. */
    public static String csv(List<Item> items) {
        StringBuilder sb = new StringBuilder("date,store,total,tax\n");
        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        List<Item> sorted = new ArrayList<Item>(items);
        java.util.Collections.sort(sorted, new java.util.Comparator<Item>() {
            @Override public int compare(Item a, Item b) {
                return Long.compare(a.createdAt, b.createdAt);
            }
        });
        for (Item it : sorted) {
            if (!CATEGORY.equals(it.category)) continue;
            Parsed p = parse(it.extracted != null && !it.extracted.isEmpty() ? it.extracted : it.body);
            long total = Ask.principalCents(it);
            String tax = "";
            if (p != null && p.taxCents >= 0) tax = Ask.formatMoney(p.taxCents).replace("$", "");
            sb.append(df.format(new Date(it.createdAt))).append(',')
              .append('"').append(storeOf(it).replace("\"", "'")).append('"').append(',')
              .append(Ask.formatMoney(total).replace("$", "")).append(',')
              .append(tax).append('\n');
        }
        return sb.toString();
    }
}
