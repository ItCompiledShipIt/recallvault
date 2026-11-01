package com.irwinlabs.recallvault;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PlainExport - the whole vault as one readable Markdown document
 * (v0.21.0). For reading, printing and sharing - NOT a restore
 * format (the password .rvbackup is). Files are named, not
 * embedded. Pure logic - JVM-tested.
 */
public final class PlainExport {

    private PlainExport() {}

    public static String buildMarkdown(List<Item> items, long nowMs) {
        StringBuilder sb = new StringBuilder();
        sb.append("# RecallVault export\n\n");
        sb.append("Exported ").append(Extract.formatDate(nowMs)).append(" - ")
          .append(items.size()).append(items.size() == 1 ? " memory" : " memories")
          .append(".\n\n");
        sb.append("This is a reading copy. Your restorable backup is the ")
          .append("password-protected .rvbackup file (Settings, in the app).\n");

        Map<String, List<Item>> byCat = new LinkedHashMap<String, List<Item>>();
        for (Item it : items) {
            String cat = it.category == null || it.category.isEmpty() ? "Notes" : it.category;
            List<Item> g = byCat.get(cat);
            if (g == null) { g = new ArrayList<Item>(); byCat.put(cat, g); }
            g.add(it);
        }
        List<String> cats = new ArrayList<String>(byCat.keySet());
        Collections.sort(cats);
        for (String cat : cats) {
            List<Item> g = byCat.get(cat);
            Collections.sort(g, new Comparator<Item>() {
                @Override public int compare(Item a, Item b) {
                    return Long.compare(b.createdAt, a.createdAt);
                }
            });
            sb.append("\n## ").append(cat).append("\n");
            for (Item it : g) {
                sb.append("\n### ").append(it.title == null || it.title.isEmpty() ? "(untitled)" : it.title).append("\n\n");
                sb.append("- Filed: ").append(Extract.formatDate(it.createdAt));
                if (it.type != null && !it.type.isEmpty()) sb.append(" - ").append(it.type);
                sb.append("\n");
                if (it.reminderAt > 0) {
                    sb.append("- Due: ").append(Extract.formatDate(it.reminderAt));
                    if (it.handledAt > 0) sb.append(" (handled ").append(Extract.formatDate(it.handledAt)).append(")");
                    sb.append("\n");
                }
                if (it.amounts != null && !it.amounts.isEmpty()) {
                    sb.append("- Amounts: ").append(it.amounts.replace(",", ", ")).append("\n");
                }
                if (it.filePath != null && !it.filePath.isEmpty()) {
                    sb.append("- File: ").append(new File(it.filePath).getName());
                    int pages = it.pagePaths().size();
                    if (pages > 1) sb.append(" (").append(pages).append(" pages)");
                    sb.append("\n");
                }
                String words = it.body != null && !it.body.trim().isEmpty() ? it.body : it.extracted;
                if (words != null && !words.trim().isEmpty()) {
                    sb.append("\n").append(words.trim()).append("\n");
                }
                if (it.extracted != null && !it.extracted.trim().isEmpty()
                        && !it.extracted.trim().equals(words == null ? "" : words.trim())) {
                    sb.append("\nText read from the file:\n\n").append(it.extracted.trim()).append("\n");
                }
            }
        }
        return sb.toString();
    }
}
