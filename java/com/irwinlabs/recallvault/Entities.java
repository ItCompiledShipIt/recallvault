package com.irwinlabs.recallvault;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * People &amp; places (v0.31.0): the vault starts noticing names.
 * A name is a run of capitalized words (or an all-caps acronym like
 * CHAS) that shows up in a title or in more than one memory. Ask
 * "everything about the landlord" - or any name - and every memory
 * that mentions it lines up in one place. Nothing is stored: the
 * index is rebuilt from the words whenever it is asked for.
 */
public final class Entities {

    private Entities() {}

    private static final Set<String> STOP = new HashSet<String>(java.util.Arrays.asList(
        "the", "this", "that", "these", "those", "amount", "total", "due", "bill",
        "statement", "payment", "balance", "date", "account", "number", "page",
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
        "january", "february", "march", "april", "may", "june", "july", "august",
        "september", "october", "november", "december",
        "street", "avenue", "road", "drive", "lane", "court", "way",
        "north", "south", "east", "west", "new", "first", "last", "next",
        "dear", "thanks", "thank", "please", "note", "voice", "meeting",
        "photo", "scan", "document", "if", "in", "on", "at", "to", "for",
        "and", "or", "of", "a", "an", "it", "is", "was", "are", "will"));

    public static final class Entity {
        public String name;
        public int itemCount;
    }

    private static boolean isNameWord(String w) {
        if (w.length() < 2) return false;
        if (STOP.contains(w.toLowerCase(Locale.US))) return false;
        if (w.matches("[A-Z]{2,}")) return true;               // CHAS, IRS
        return w.matches("[A-Z][a-z]+") || w.matches("[A-Z][a-z]*'[a-z]+");
    }

    private static String textOf(Item it) {
        return (it.title == null ? "" : it.title) + "\n"
            + (it.body == null ? "" : it.body) + "\n"
            + (it.extracted == null ? "" : it.extracted);
    }

    /** Names worth offering, most-mentioned first. */
    public static List<Entity> top(List<Item> items, int limit) {
        Map<String, Set<Long>> byName = new HashMap<String, Set<Long>>();
        Map<String, Integer> occurrences = new HashMap<String, Integer>();
        Map<String, Boolean> inTitleMid = new HashMap<String, Boolean>();
        for (Item it : items) {
            // Pass 0 is the title alone; pass 1 is body+extracted (the
            // title is NOT rescanned, or every title word would count
            // twice and lone first words would slip in as "repeated").
            String[] texts = { it.title == null ? "" : it.title,
                (it.body == null ? "" : it.body) + "\n" + (it.extracted == null ? "" : it.extracted) };
            for (int pass = 0; pass < 2; pass++) {
                String[] toks = texts[pass].split("[^A-Za-z']+");
                for (int i = 0; i < toks.length; i++) {
                    if (!isNameWord(toks[i])) continue;
                    // Grow a phrase of up to 3 name words.
                    StringBuilder phrase = new StringBuilder(toks[i]);
                    for (int j = i + 1; j < toks.length && j < i + 3; j++) {
                        if (!isNameWord(toks[j])) break;
                        phrase.append(' ').append(toks[j]);
                    }
                    String name = phrase.toString();
                    Set<Long> ids = byName.get(name);
                    if (ids == null) { ids = new HashSet<Long>(); byName.put(name, ids); }
                    ids.add(it.id);
                    Integer occ = occurrences.get(name);
                    occurrences.put(name, occ == null ? 1 : occ + 1);
                    // A name inside a title but not as its first word is
                    // a real mention, not just a capitalized first word.
                    if (pass == 0 && i > 0) inTitleMid.put(name, Boolean.TRUE);
                }
            }
        }
        List<Entity> out = new ArrayList<Entity>();
        for (Map.Entry<String, Set<Long>> e : byName.entrySet()) {
            int n = e.getValue().size();
            int occ = occurrences.get(e.getKey());
            boolean titled = Boolean.TRUE.equals(inTitleMid.get(e.getKey()));
            // Worth offering: spans memories, repeats, or sits inside a
            // title. A lone capitalized first word ("Letter from...")
            // is none of those.
            if (n < 2 && occ < 2 && !titled) continue;
            // A longer phrase that covers the same items beats its parts.
            Entity en = new Entity();
            en.name = e.getKey();
            en.itemCount = n;
            out.add(en);
        }
        // Drop single words fully covered by a longer phrase with the
        // same item set (e.g. "Avista" when "Avista Utilities" exists).
        List<Entity> pruned = new ArrayList<Entity>();
        for (Entity a : out) {
            boolean covered = false;
            for (Entity b : out) {
                if (b != a && b.name.contains(a.name) && b.itemCount == a.itemCount
                        && b.name.length() > a.name.length()) { covered = true; break; }
            }
            if (!covered) pruned.add(a);
        }
        Collections.sort(pruned, new Comparator<Entity>() {
            @Override public int compare(Entity a, Entity b) {
                if (a.itemCount != b.itemCount) return b.itemCount - a.itemCount;
                return a.name.compareTo(b.name);
            }
        });
        return pruned.size() > limit ? pruned.subList(0, limit) : pruned;
    }

    /** Every memory that mentions a name, newest first. */
    public static List<Item> about(List<Item> items, String name) {
        String needle = name.toLowerCase(Locale.US).trim();
        List<Item> out = new ArrayList<Item>();
        if (needle.isEmpty()) return out;
        for (Item it : items) {
            if (textOf(it).toLowerCase(Locale.US).contains(needle)) out.add(it);
        }
        Collections.sort(out, new Comparator<Item>() {
            @Override public int compare(Item a, Item b) {
                return Long.compare(b.createdAt, a.createdAt);
            }
        });
        return out;
    }

    // ---------- people cards (v0.43.0) ----------

    private static final java.util.regex.Pattern PHONE =
        java.util.regex.Pattern.compile("\\(?\\d{3}\\)?[-.\\s]\\d{3}[-.\\s]\\d{4}");
    private static final java.util.regex.Pattern EMAIL =
        java.util.regex.Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final java.util.regex.Pattern ADDRESS =
        java.util.regex.Pattern.compile("\\d{1,6} [A-Za-z0-9 .]+?\\b(St|Street|Ave|Avenue|Rd|Road|Blvd|Dr|Drive|Way|Ln|Lane|Ct|Court)\\b");

    /**
     * The at-a-glance card for one name: contact facts the vault
     * has seen in the memories that mention them, the dates on
     * those memories, and any money that runs through them.
     */
    public static List<String> cardLines(List<Item> items, String name, long nowMs) {
        List<Item> hits = about(items, name);
        List<String> out = new ArrayList<String>();
        if (hits.isEmpty()) return out;
        out.add(hits.size() + (hits.size() == 1 ? " memory" : " memories")
            + " - latest " + Extract.formatDate(hits.get(0).createdAt));
        Set<String> phones = new LinkedHashSet<String>();
        Set<String> emails = new LinkedHashSet<String>();
        Set<String> addrs = new LinkedHashSet<String>();
        Set<Long> dates = new TreeSet<Long>();
        long money = 0;
        for (Item it : hits) {
            String text = textOf(it);
            java.util.regex.Matcher m = PHONE.matcher(text);
            while (m.find()) phones.add(m.group().trim());
            m = EMAIL.matcher(text);
            while (m.find()) emails.add(m.group().trim());
            m = ADDRESS.matcher(text);
            while (m.find()) addrs.add(m.group().trim());
            Extract.Facts f = Extract.analyze(text);
            for (Long d : f.dates) dates.add(d);
            long c = Ask.principalCents(it);
            if (c > 0) money += c;
        }
        for (String p : phones) { out.add("Phone: " + p); if (out.size() > 8) break; }
        for (String e : emails) out.add("Email: " + e);
        for (String a : addrs) out.add("Address: " + a);
        if (!dates.isEmpty()) {
            StringBuilder db = new StringBuilder("Dates: ");
            int i = 0;
            for (Long d : dates) {
                if (i++ >= 4) break;
                if (i > 1) db.append(", ");
                db.append(Extract.formatDate(d));
            }
            out.add(db.toString());
        }
        if (money > 0) out.add("Money across these memories: " + Ask.formatMoney(money));
        return out;
    }

    /** The card as one block; null when the name is unknown. */
    public static String cardText(List<Item> items, String name, long nowMs) {
        List<String> ls = cardLines(items, name, nowMs);
        if (ls.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (String l : ls) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(l);
        }
        return sb.toString();
    }

    /** Ask hook: "everything about X", "all about X", "about X". */
    public static Ask.Summary answer(List<Item> items, String query) {
        String q = query.trim();
        String low = q.toLowerCase(Locale.US);
        // Contact questions ("Gina's phone number", "CHAS address")
        // get the card, not the memory list (v0.43.0).
        if (low.contains("phone") || low.contains("number") || low.contains("address")
            || low.contains("email") || low.contains("contact")) {
            for (Entity e : top(items, 40)) {
                if (low.contains(e.name.toLowerCase(Locale.US))) {
                    String card = cardText(items, e.name, System.currentTimeMillis());
                    if (card != null) {
                        Ask.Summary cs = new Ask.Summary();
                        cs.text = e.name + " - at a glance:\n" + card;
                        return cs;
                    }
                }
            }
        }
        String name = null;
        int idx = low.indexOf("everything about ");
        if (idx >= 0) name = q.substring(idx + "everything about ".length());
        if (name == null) {
            idx = low.indexOf("all about ");
            if (idx >= 0) name = q.substring(idx + "all about ".length());
        }
        if (name == null && low.startsWith("about ")) name = q.substring("about ".length());
        if (name == null) return null;
        name = name.replaceAll("[?.!]+$", "").trim();
        if (name.isEmpty()) return null;
        // Prefer a known entity whose name matches (either direction).
        String display = name;
        for (Entity e : top(items, 40)) {
            if (e.name.equalsIgnoreCase(name)
                    || e.name.toLowerCase(Locale.US).contains(name.toLowerCase(Locale.US))
                    || name.toLowerCase(Locale.US).contains(e.name.toLowerCase(Locale.US))) {
                display = e.name;
                break;
            }
        }
        List<Item> hits = about(items, display);
        Ask.Summary s = new Ask.Summary();
        if (hits.isEmpty()) {
            s.text = "Nothing in your vault mentions " + display + " yet.";
            return s;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Everything about ").append(display)
          .append(" - ").append(hits.size())
          .append(hits.size() == 1 ? " memory:" : " memories:");
        int shown = 0;
        for (Item it : hits) {
            if (shown++ >= 8) { sb.append("\n...and ").append(hits.size() - 8).append(" more"); break; }
            sb.append("\n").append(Extract.formatDate(it.createdAt)).append(" - ").append(it.title);
        }
        s.count = hits.size();
        s.text = sb.toString();
        return s;
    }
}
