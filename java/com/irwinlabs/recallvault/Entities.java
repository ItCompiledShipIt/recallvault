package com.irwinlabs.recallvault;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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

    /** Ask hook: "everything about X", "all about X", "about X". */
    public static Ask.Summary answer(List<Item> items, String query) {
        String q = query.trim();
        String low = q.toLowerCase(Locale.US);
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
