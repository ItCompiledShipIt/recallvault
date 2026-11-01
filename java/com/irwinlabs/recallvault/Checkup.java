package com.irwinlabs.recallvault;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Vault checkup (v0.45.0): one pass that finds the fixable gaps -
 * dates mentioned in a memory's words that never became its
 * reminder, text that never made it into the meaning index,
 * photos whose words were never read, and the files eating the
 * most space. Detection is pure; the fixes ride the machinery
 * that already exists (Db.update, the embed pass).
 */
public final class Checkup {

    private Checkup() {}

    public static final class MissedDate {
        public Item item;
        public long suggestedMs;
    }

    /** Memories whose words name a deadline but carry no reminder. */
    public static List<MissedDate> missedDates(List<Item> items) {
        List<MissedDate> out = new ArrayList<MissedDate>();
        for (Item it : items) {
            if (it.reminderAt > 0 || it.handledAt > 0) continue;
            String text = textOf(it);
            if (text.isEmpty()) continue;
            Extract.Facts f = Extract.analyze(text);
            long when = f.dueDate > 0 ? f.dueDate : (f.dates.isEmpty() ? -1 : f.dates.get(0));
            if (when <= 0) continue;
            MissedDate md = new MissedDate();
            md.item = it;
            md.suggestedMs = when;
            out.add(md);
        }
        return out;
    }

    /** Text-bearing memories missing from the meaning index. */
    public static List<Item> unindexed(List<Item> items) {
        List<Item> out = new ArrayList<Item>();
        for (Item it : items) {
            if (textOf(it).isEmpty()) continue;
            if (it.embeddingLocal != null && !it.embeddingLocal.isEmpty()) continue;
            out.add(it);
        }
        return out;
    }

    /** Photos/scans with a file whose words were never read. */
    public static List<Item> unread(List<Item> items) {
        List<Item> out = new ArrayList<Item>();
        for (Item it : items) {
            if (it.filePath == null || it.filePath.isEmpty()) continue;
            if (!("PHOTO".equals(it.type) || "SCAN".equals(it.type))) continue;
            if (it.extracted != null && !it.extracted.trim().isEmpty()) continue;
            out.add(it);
        }
        return out;
    }

    /** The largest files in the vault, biggest first (top 5). */
    public static List<Item> biggest(List<Item> items, final Map<Long, Long> sizeById) {
        List<Item> withFiles = new ArrayList<Item>();
        for (Item it : items) {
            Long s = sizeById.get(it.id);
            if (s != null && s > 0) withFiles.add(it);
        }
        Collections.sort(withFiles, new Comparator<Item>() {
            @Override public int compare(Item a, Item b) {
                return Long.compare(sizeById.get(b.id), sizeById.get(a.id));
            }
        });
        return withFiles.size() > 5 ? withFiles.subList(0, 5) : withFiles;
    }

    private static String textOf(Item it) {
        String b = it.body != null ? it.body : "";
        String e = it.extracted != null ? it.extracted : "";
        return (b + "\n" + e).trim();
    }
}
