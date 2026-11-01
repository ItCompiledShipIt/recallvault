package com.irwinlabs.recallvault;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Links - the stored form of memory-to-memory links (v0.23.0):
 * a comma-separated id list in one text column. Links are stored
 * on BOTH items (symmetric) and scrubbed when an item is deleted.
 * Pure logic - JVM-tested.
 */
public final class Links {

    private Links() {}

    public static Set<Long> parse(String stored) {
        Set<Long> out = new LinkedHashSet<Long>();
        if (stored == null || stored.isEmpty()) return out;
        for (String part : stored.split(",")) {
            try {
                long v = Long.parseLong(part.trim());
                if (v > 0) out.add(v);
            } catch (Exception e) { /* skip junk */ }
        }
        return out;
    }

    public static String format(Set<Long> ids) {
        StringBuilder sb = new StringBuilder();
        for (Long id : ids) {
            if (sb.length() > 0) sb.append(',');
            sb.append(id.longValue());
        }
        return sb.toString();
    }

    /** stored list with id added (order-stable, no duplicates). */
    public static String with(String stored, long id) {
        Set<Long> s = parse(stored);
        s.add(id);
        return format(s);
    }

    /** stored list with id removed. */
    public static String without(String stored, long id) {
        Set<Long> s = parse(stored);
        s.remove(id);
        return format(s);
    }

    public static boolean contains(String stored, long id) {
        return parse(stored).contains(id);
    }
}
