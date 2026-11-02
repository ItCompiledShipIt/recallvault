package com.irwinlabs.recallvault;

import android.content.Context;

import com.google.ai.edge.litertlm.InternalJsonTool;
import com.google.ai.edge.litertlm.ToolProvider;
import com.google.gson.JsonObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * BrainTools - the vault's hands for the brain. Gemma 4 supports
 * function calling through LiteRT-LM; each tool here is an
 * InternalJsonTool (description JSON + execute) handed to the
 * conversation via one ToolProvider, so the model can reach back
 * into the vault mid-answer instead of only seeing what the app
 * pre-fetched for it.
 *
 * The tools are read-only in v0.53: search, read one memory in
 * full, and list what's due. Private memories are excluded, the
 * same rule Ask already follows. Everything returns plain text -
 * the runtime wraps it as the tool response.
 */
public final class BrainTools {

    private BrainTools() {}

    /** Build the provider bound to a live Db. */
    public static ToolProvider provider(final Context ctx, final Db db) {
        return new ToolProvider() {
            @Override
            public Map<String, InternalJsonTool> provideTools$third_party_odml_litert_lm_kotlin_java_com_google_ai_edge_litertlm_litertlm_android() {
                Map<String, InternalJsonTool> tools = new LinkedHashMap<>();
                tools.put("search_vault", new SearchVault(db));
                tools.put("read_memory", new ReadMemory(db));
                tools.put("vault_due", new VaultDue(db));
                return tools;
            }
        };
    }

    private static JsonObject desc(String name, String description, JsonObject params) {
        JsonObject d = new JsonObject();
        d.addProperty("name", name);
        d.addProperty("description", description);
        if (params != null) d.add("parameters", params);
        return d;
    }

    private static JsonObject params(String[][] props, String... required) {
        JsonObject p = new JsonObject();
        p.addProperty("type", "object");
        JsonObject properties = new JsonObject();
        for (String[] prop : props) {
            JsonObject one = new JsonObject();
            one.addProperty("type", prop[1]);
            one.addProperty("description", prop[2]);
            properties.add(prop[0], one);
        }
        p.add("properties", properties);
        if (required.length > 0) {
            com.google.gson.JsonArray req = new com.google.gson.JsonArray();
            for (String r : required) req.add(r);
            p.add("required", req);
        }
        return p;
    }

    private static String fmtDate(long ms) {
        return new SimpleDateFormat("EEE, MMM d, yyyy", Locale.US).format(new Date(ms));
    }

    static String snippet(Item it, int max) {
        String text = it.body != null && !it.body.isEmpty() ? it.body
            : (it.extracted != null ? it.extracted : "");
        text = text.replace('\n', ' ').trim();
        return text.length() > max ? text.substring(0, max) + "..." : text;
    }

    /** Keyword search over visible items - the deterministic layer
     *  under the model (semantic ranking stays in the Ask pipeline). */
    static List<Item> keywordSearch(Db db, String query, int limit) {
        String[] words = query.toLowerCase(Locale.US).split("[^a-z0-9]+");
        List<Item> all = db.all();
        List<int[]> scored = new ArrayList<>();
        List<Item> pool = new ArrayList<>();
        for (Item it : all) {
            if (it.deletedAt != 0 || it.isPrivate != 0) continue;
            String hay = ((it.title == null ? "" : it.title) + "\n"
                + (it.body == null ? "" : it.body) + "\n"
                + (it.extracted == null ? "" : it.extracted)).toLowerCase(Locale.US);
            int score = 0;
            for (String w : words) {
                if (w.length() < 3) continue;
                if (hay.contains(w)) score++;
            }
            if (score > 0) { pool.add(it); scored.add(new int[]{score}); }
        }
        // Simple stable sort by score desc, then newest first.
        List<Item> out = new ArrayList<>();
        while (!pool.isEmpty() && out.size() < limit) {
            int best = 0;
            for (int i = 1; i < pool.size(); i++) {
                int si = scored.get(i)[0], sb = scored.get(best)[0];
                if (si > sb || (si == sb && pool.get(i).createdAt > pool.get(best).createdAt)) best = i;
            }
            out.add(pool.remove(best));
            scored.remove(best);
        }
        return out;
    }

    private static final class SearchVault implements InternalJsonTool {
        private final Db db;
        SearchVault(Db db) { this.db = db; }

        @Override public JsonObject getToolDescription() {
            return desc("search_vault",
                "Search the user's saved memories by keywords. Returns matching memories with their ids, titles, dates and a short snippet. Use read_memory for the full text of one.",
                params(new String[][]{{"query", "string", "Keywords to search for"}}, "query"));
        }

        @Override public Object execute(JsonObject args) {
            String q = args != null && args.has("query") ? args.get("query").getAsString() : "";
            List<Item> hits = keywordSearch(db, q, 5);
            if (hits.isEmpty()) return "No memories match \"" + q + "\".";
            StringBuilder sb = new StringBuilder();
            for (Item it : hits) {
                sb.append("[").append(it.id).append("] ").append(it.title)
                  .append(" (").append(fmtDate(it.createdAt)).append(")\n")
                  .append(snippet(it, 220)).append("\n\n");
            }
            return sb.toString().trim();
        }
    }

    private static final class ReadMemory implements InternalJsonTool {
        private final Db db;
        ReadMemory(Db db) { this.db = db; }

        @Override public JsonObject getToolDescription() {
            return desc("read_memory",
                "Read one memory in full by its id (ids come from search_vault results).",
                params(new String[][]{{"id", "integer", "The memory id"}}, "id"));
        }

        @Override public Object execute(JsonObject args) {
            long id = args != null && args.has("id") ? args.get("id").getAsLong() : -1;
            for (Item it : db.all()) {
                if (it.id == id) {
                    if (it.isPrivate != 0) return "That memory is private.";
                    String text = it.body != null && !it.body.isEmpty() ? it.body
                        : (it.extracted != null ? it.extracted : "");
                    if (text.length() > 3000) text = text.substring(0, 3000);
                    return it.title + " (" + fmtDate(it.createdAt) + ")\n" + text;
                }
            }
            return "No memory with id " + id + ".";
        }
    }

    private static final class VaultDue implements InternalJsonTool {
        private final Db db;
        VaultDue(Db db) { this.db = db; }

        @Override public JsonObject getToolDescription() {
            return desc("vault_due",
                "List the user's open reminders and bills still to handle, soonest first, with due dates and amounts.",
                params(new String[][]{}));
        }

        @Override public Object execute(JsonObject args) {
            List<Item> due = new ArrayList<>();
            for (Item it : db.all()) {
                if (it.deletedAt == 0 && it.isPrivate == 0 && it.reminderAt > 0 && it.handledAt == 0) {
                    due.add(it);
                }
            }
            due.sort((a, b) -> Long.compare(a.reminderAt, b.reminderAt));
            if (due.isEmpty()) return "Nothing is due or outstanding.";
            StringBuilder sb = new StringBuilder();
            int shown = 0;
            for (Item it : due) {
                if (shown++ >= 8) break;
                sb.append(it.title).append(" - due ").append(fmtDate(it.reminderAt));
                if (it.amounts != null && !it.amounts.isEmpty()) {
                    sb.append(" - ").append(it.amounts);
                }
                sb.append("\n");
            }
            return sb.toString().trim();
        }
    }
}
