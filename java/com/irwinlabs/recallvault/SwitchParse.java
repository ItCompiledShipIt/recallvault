package com.irwinlabs.recallvault;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Switcher imports (v0.73.0): parse another app's export into
 * plain notes. Pure - bytes in, notes out; MainActivity files
 * them. Formats (all researched from the real exports):
 * Google Keep via Takeout (a zip of per-note JSON: title,
 * textContent, listContent, labels, createdTimestampUsec,
 * attachments, isTrashed); Limitless (a lifelogs/ tree of
 * Markdown transcripts named YYYY-MM-DD_HHhMMmSSs_Title.md);
 * Mem and everyone else (Markdown / text files, loose or
 * zipped). Attachments a Keep note references are carried
 * over when the export includes them.
 */
public final class SwitchParse {

    private SwitchParse() {}

    public static final class Note {
        public String title = "";
        public String body = "";
        public String type = "NOTE";
        public String category = "";  // "Meetings" for lifelogs
        public String source = "";
        public long createdAt;
        public String attachName = "";
        public String attachMime = "";
        public byte[] attachBytes;
    }

    public static final class Out {
        public final List<Note> notes = new ArrayList<Note>();
        public final Set<String> sources = new LinkedHashSet<String>();
        public int skipped;
    }

    private static final Pattern LIFELOG = Pattern.compile(
        "^(\\d{4})-(\\d{2})-(\\d{2})_(\\d{2})h(\\d{2})m(\\d{2})s_(.+)\\.md$",
        Pattern.CASE_INSENSITIVE);

    public static Out parse(String filename, byte[] blob) {
        Out out = new Out();
        Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
        Map<String, Long> times = new LinkedHashMap<String, Long>();
        if (blob != null && blob.length > 2 && blob[0] == 'P' && blob[1] == 'K') {
            try {
                ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(blob));
                ZipEntry e;
                byte[] buf = new byte[65536];
                while ((e = zin.getNextEntry()) != null) {
                    if (e.isDirectory()) continue;
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    int n;
                    while ((n = zin.read(buf)) > 0) bos.write(buf, 0, n);
                    entries.put(e.getName(), bos.toByteArray());
                    times.put(e.getName(), e.getTime());
                }
                zin.close();
            } catch (Exception ignored) {}
        }
        if (entries.isEmpty() && blob != null) {
            entries.put(filename == null || filename.isEmpty() ? "upload" : filename, blob);
        }
        Map<String, byte[]> byBase = new HashMap<String, byte[]>();
        for (Map.Entry<String, byte[]> en : entries.entrySet()) {
            byBase.put(base(en.getKey()), en.getValue());
        }
        for (Map.Entry<String, byte[]> en : entries.entrySet()) {
            String name = en.getKey();
            byte[] data = en.getValue();
            String low = base(name).toLowerCase(java.util.Locale.US);
            long mt = times.containsKey(name) ? times.get(name) : 0L;
            if (low.endsWith(".json")) {
                keepNote(out, data, byBase);
            } else if (low.endsWith(".md") || low.endsWith(".txt")) {
                textNote(out, name, data, mt);
            }
        }
        return out;
    }

    private static String base(String path) {
        int i = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return i >= 0 ? path.substring(i + 1) : path;
    }

    private static void keepNote(Out out, byte[] data, Map<String, byte[]> byBase) {
        JSONObject o;
        try {
            o = new JSONObject(new String(data, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return;
        }
        if (!o.has("textContent") && !o.has("listContent")
                && !o.has("createdTimestampUsec")) {
            return; // some other app's JSON - not ours to guess at
        }
        out.sources.add("Google Keep");
        if (o.optBoolean("isTrashed", false)) {
            out.skipped++;
            return;
        }
        Note n = new Note();
        n.source = "Google Keep";
        n.title = o.optString("title", "").trim();
        StringBuilder body = new StringBuilder();
        String text = o.optString("textContent", "").trim();
        if (!text.isEmpty()) body.append(text);
        JSONArray list = o.optJSONArray("listContent");
        if (list != null) {
            for (int i = 0; i < list.length(); i++) {
                JSONObject li = list.optJSONObject(i);
                if (li == null) continue;
                if (body.length() > 0) body.append('\n');
                body.append(li.optBoolean("isChecked", false) ? "- [x] " : "- [ ] ")
                    .append(li.optString("text", "").trim());
            }
        }
        JSONArray labels = o.optJSONArray("labels");
        if (labels != null && labels.length() > 0) {
            StringBuilder lb = new StringBuilder();
            for (int i = 0; i < labels.length(); i++) {
                JSONObject l = labels.optJSONObject(i);
                if (l == null) continue;
                String nm = l.optString("name", "").trim();
                if (!nm.isEmpty()) {
                    if (lb.length() > 0) lb.append(", ");
                    lb.append(nm);
                }
            }
            if (lb.length() > 0) {
                if (body.length() > 0) body.append("\n\n");
                body.append("Labels: ").append(lb);
            }
        }
        n.body = body.toString();
        n.createdAt = o.optLong("createdTimestampUsec", 0) / 1000L;
        JSONArray atts = o.optJSONArray("attachments");
        if (atts != null) {
            for (int i = 0; i < atts.length(); i++) {
                JSONObject a = atts.optJSONObject(i);
                if (a == null) continue;
                String bn = base(a.optString("filePath", ""));
                byte[] raw = byBase.get(bn);
                if (raw != null) {
                    n.attachName = bn;
                    n.attachMime = a.optString("mimetype", "");
                    n.attachBytes = raw;
                    break;
                }
            }
        }
        out.notes.add(n);
    }

    private static void textNote(Out out, String path, byte[] data, long entryTime) {
        String text = new String(data, StandardCharsets.UTF_8).trim();
        if (text.isEmpty()) return;
        String name = base(path);
        Matcher m = LIFELOG.matcher(name);
        boolean lifelogPath = path.replace('\\', '/').contains("/lifelogs/")
            || path.replace('\\', '/').startsWith("lifelogs/");
        Note n = new Note();
        if (m.matches() || lifelogPath) {
            n.source = "Limitless";
            out.sources.add("Limitless");
            n.type = "MEETING";
            n.category = "Meetings";
            if (m.matches()) {
                Calendar c = Calendar.getInstance();
                c.set(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)) - 1,
                      Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)),
                      Integer.parseInt(m.group(5)), Integer.parseInt(m.group(6)));
                c.set(Calendar.MILLISECOND, 0);
                n.createdAt = c.getTimeInMillis();
                n.title = m.group(7).replaceAll("[-_]+", " ").trim();
            } else {
                n.createdAt = entryTime;
                n.title = stripExt(name).replaceAll("[-_]+", " ").trim();
            }
            n.body = text;
        } else {
            n.source = "Markdown";
            out.sources.add("Markdown");
            n.createdAt = entryTime;
            String[] lines = text.split("\n", 2);
            if (lines[0].startsWith("# ") && lines[0].length() > 2) {
                n.title = lines[0].substring(2).trim();
                n.body = lines.length > 1 ? lines[1].trim() : "";
            } else {
                n.title = stripExt(name);
                n.body = text;
            }
        }
        out.notes.add(n);
    }

    private static String stripExt(String name) {
        int i = name.lastIndexOf('.');
        return i > 0 ? name.substring(0, i) : name;
    }
}
