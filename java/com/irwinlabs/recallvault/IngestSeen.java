package com.irwinlabs.recallvault;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure helpers for automatic ingest (v0.27.0): which MIME types the
 * vault files by itself, and the seen-index line codec
 * (key TAB last-modified TAB size) that keeps folder rewalks cheap.
 * Kept Android-free so the JVM suite covers them.
 */
public final class IngestSeen {

    private IngestSeen() {}

    public static boolean supported(String mime) {
        if (mime == null) return false;
        return mime.startsWith("image/") || mime.startsWith("text/")
            || mime.startsWith("audio/") || mime.equals("application/pdf");
    }

    public static String seenLine(String key, long modified, long size) {
        return key.replace('\t', ' ') + "\t" + modified + "\t" + size;
    }

    public static Map<String, long[]> parseSeen(List<String> lines) {
        Map<String, long[]> out = new HashMap<String, long[]>();
        for (String line : lines) {
            if (line == null) continue;
            String[] parts = line.split("\t");
            if (parts.length != 3) continue;
            try {
                out.put(parts[0], new long[]{Long.parseLong(parts[1]), Long.parseLong(parts[2])});
            } catch (Exception e) { /* skip junk lines */ }
        }
        return out;
    }
}
