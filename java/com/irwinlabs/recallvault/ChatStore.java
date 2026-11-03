package com.irwinlabs.recallvault;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Conversation threads for Ask (v0.76.0): one Main chat plus
 * user-made side chats, mirroring the desktop app exactly.
 * Threads persist as one JSON blob in SharedPreferences.
 *
 * Long threads compress: when the verbatim history past the
 * digest would exceed BUDGET chars, the oldest turns (beyond
 * the most recent KEEP) fold into the chat's digest, which
 * then rides in the prompt instead of the raw turns. The
 * planning math and the no-model fallback digest are pure
 * and JVM-tested; the model-written digest lives in
 * ChatActivity.
 */
public final class ChatStore {

    public static final int BUDGET = 6000;
    public static final int KEEP = 8;
    private static final String PREFS = "chats";
    private static final String KEY = "chats_json";

    public static final class Msg {
        public String role;
        public String text;
        public String prov;
        public long ts;
        /** Vault item this message can act on (Done/Snooze), 0 = none. */
        public long itemId;

        public Msg(String role, String text, long ts, String prov) {
            this.role = role;
            this.text = text == null ? "" : text;
            this.ts = ts;
            this.prov = prov == null ? "" : prov;
        }
    }

    public static final class Chat {
        public String id;
        public String title;
        public long created;
        public String digest = "";
        public int digestUpto = 0;
        public final List<Msg> messages = new ArrayList<Msg>();

        public String lastUserText() {
            for (int i = messages.size() - 1; i >= 0; i--) {
                if ("user".equals(messages.get(i).role)) {
                    return messages.get(i).text;
                }
            }
            return null;
        }
    }

    public final List<Chat> chats = new ArrayList<Chat>();

    public Chat main() {
        for (Chat c : chats) if ("main".equals(c.id)) return c;
        Chat c = new Chat();
        c.id = "main";
        c.title = "Main chat";
        c.created = System.currentTimeMillis();
        chats.add(0, c);
        return c;
    }

    public Chat get(String id) {
        if (id != null) {
            for (Chat c : chats) if (id.equals(c.id)) return c;
        }
        return main();
    }

    public Chat newSide(long now) {
        Chat c = new Chat();
        c.id = "side-" + now;
        c.title = "Side chat";
        c.created = now;
        chats.add(c);
        return c;
    }

    public void delete(String id) {
        if (id == null || "main".equals(id)) return;
        for (int i = chats.size() - 1; i >= 0; i--) {
            if (id.equals(chats.get(i).id)) chats.remove(i);
        }
    }

    // ------------------------------------------------------------
    // Compression planning - pure, JVM-tested.
    // ------------------------------------------------------------

    public static int historyChars(Chat c) {
        int n = 0;
        for (int i = c.digestUpto; i < c.messages.size(); i++) {
            n += c.messages.get(i).text.length() + 8;
        }
        return n;
    }

    public static boolean needsCompression(Chat c) {
        return c.messages.size() - c.digestUpto > KEEP
            && historyChars(c) > BUDGET;
    }

    /** Index the digest would cover up to if compression ran now. */
    public static int compressCut(Chat c) {
        return Math.max(c.digestUpto, c.messages.size() - KEEP);
    }

    /** What the model sees: digest (if any) + verbatim turns after it. */
    public static String historyBlock(Chat c) {
        StringBuilder sb = new StringBuilder();
        if (c.digest != null && !c.digest.isEmpty()) {
            sb.append("[earlier conversation, compressed]\n")
              .append(c.digest);
        }
        for (int i = c.digestUpto; i < c.messages.size(); i++) {
            Msg m = c.messages.get(i);
            if (sb.length() > 0) sb.append("\n");
            sb.append("user".equals(m.role) ? "User: " : "Clio: ")
              .append(m.text);
        }
        return sb.toString();
    }

    /**
     * The no-model digest: the questions asked, plus the
     * money/date-bearing sentences from Clio's answers. Honest
     * extraction - nothing is paraphrased or invented.
     */
    public static String fallbackDigest(List<Msg> turns, String prior) {
        StringBuilder sb = new StringBuilder();
        if (prior != null && !prior.isEmpty()) {
            sb.append(prior.trim()).append(" ");
        }
        StringBuilder qs = new StringBuilder();
        for (Msg m : turns) {
            if ("user".equals(m.role)) {
                if (qs.length() > 0) qs.append("; ");
                qs.append(m.text.replaceAll("\\s+", " ").trim());
            }
        }
        if (qs.length() > 0) {
            sb.append("Earlier, the user asked: ").append(qs).append(". ");
        }
        int facts = 0;
        for (Msg m : turns) {
            if (!"clio".equals(m.role)) continue;
            for (String sent : m.text.split("(?<=[.!?])\\s+")) {
                String s = sent.replaceAll("\\s+", " ").trim();
                if (s.isEmpty() || s.length() > 170) continue;
                if (s.contains("$") || s.matches(".*\\b(19|20)\\d\\d\\b.*")
                        || s.toLowerCase().contains("due")) {
                    sb.append(s).append(" ");
                    if (++facts >= 10) break;
                }
            }
            if (facts >= 10) break;
        }
        String out = sb.toString().trim();
        return out.length() > 1600 ? out.substring(0, 1600) : out;
    }

    // ------------------------------------------------------------
    // JSON + persistence.
    // ------------------------------------------------------------

    public JSONObject toJson() {
        try {
            JSONObject root = new JSONObject();
            JSONArray arr = new JSONArray();
            for (Chat c : chats) {
                JSONObject co = new JSONObject();
                co.put("id", c.id);
                co.put("title", c.title);
                co.put("created", c.created);
                co.put("digest", c.digest == null ? "" : c.digest);
                co.put("digest_upto", c.digestUpto);
                JSONArray ms = new JSONArray();
                for (Msg m : c.messages) {
                    JSONObject mo = new JSONObject();
                    mo.put("role", m.role);
                    mo.put("text", m.text);
                    mo.put("ts", m.ts);
                    mo.put("prov", m.prov == null ? "" : m.prov);
                    mo.put("item", m.itemId);
                    ms.put(mo);
                }
                co.put("messages", ms);
                arr.put(co);
            }
            root.put("chats", arr);
            return root;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public static ChatStore fromJson(JSONObject root) {
        ChatStore s = new ChatStore();
        try {
            JSONArray arr = root == null ? null : root.optJSONArray("chats");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject co = arr.optJSONObject(i);
                    if (co == null) continue;
                    Chat c = new Chat();
                    c.id = co.optString("id", "");
                    if (c.id.isEmpty()) continue;
                    c.title = co.optString("title", "Chat");
                    c.created = co.optLong("created", 0);
                    c.digest = co.optString("digest", "");
                    c.digestUpto = co.optInt("digest_upto", 0);
                    JSONArray ms = co.optJSONArray("messages");
                    if (ms != null) {
                        for (int k = 0; k < ms.length(); k++) {
                            JSONObject mo = ms.optJSONObject(k);
                            if (mo == null) continue;
                            Msg m = new Msg(mo.optString("role", "user"),
                                mo.optString("text", ""),
                                mo.optLong("ts", 0),
                                mo.optString("prov", ""));
                            m.itemId = mo.optLong("item", 0);
                            c.messages.add(m);
                        }
                    }
                    if (c.digestUpto > c.messages.size()) {
                        c.digestUpto = c.messages.size();
                    }
                    s.chats.add(c);
                }
            }
        } catch (Exception e) {
            // A corrupt store starts fresh rather than crashing Ask.
        }
        s.main();
        return s;
    }

    public static ChatStore load(Context ctx) {
        try {
            String raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, "");
            if (raw != null && !raw.isEmpty()) {
                return fromJson(new JSONObject(raw));
            }
        } catch (Exception e) {
            // fall through to a fresh store
        }
        ChatStore s = new ChatStore();
        s.main();
        return s;
    }

    public void save(Context ctx) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, toJson().toString()).apply();
        } catch (Exception e) {
            // Persistence is best-effort; the thread still works.
        }
    }
}
