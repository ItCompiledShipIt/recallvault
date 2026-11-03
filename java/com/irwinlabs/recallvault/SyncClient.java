package com.irwinlabs.recallvault;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Live sync with Clio for Desktop (v0.67.0).
 *
 * One exchange, both directions: this phone pushes every
 * record changed since the last successful sync (memories,
 * contacts, deletions, her memory facts, her instructions)
 * and pulls everything the desktop changed since the seq
 * it last saw. Merges are last-writer-wins per record by
 * updated_at; deletions travel as tombstones; her memory
 * facts union, so neither side can lose a fact. Files ride
 * along by canonical name (created_at + "_" + basename)
 * after the record exchange. First sync adopts records
 * the desktop already holds from a backup import (matched
 * by title + created_at) so nothing doubles.
 *
 * Transport is plain HTTP on the user's own network,
 * authenticated by a shared sync key shown in the desktop
 * app. Over NetBird the same path is WireGuard-encrypted.
 */
public class SyncClient {

    public static final String PREF_ADDR = "sync_addr";
    public static final String PREF_KEY_ENC = "sync_key_enc";
    public static final String PREF_AUTO = "sync_auto";
    public static final String PREF_SEQ = "sync_last_seq";
    public static final String PREF_PUSHED = "sync_pushed_at";
    public static final String PREF_C_PUSHED = "sync_contacts_pushed_at";
    public static final String PREF_LAST_AT = "sync_last_at";
    public static final String PREF_LAST_RESULT = "sync_last_result";
    public static final String DEFAULT_ADDR = "192.168.0.18:8097";

    public static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(Embed.PREFS, Context.MODE_PRIVATE);
    }

    public static String key(Context ctx) {
        String enc = prefs(ctx).getString(PREF_KEY_ENC, "");
        if (enc == null || enc.isEmpty()) return "";
        try {
            String d = VaultCrypto.dec(enc);
            return d == null ? "" : d;
        } catch (Exception e) {
            return "";
        }
    }

    public static boolean configured(Context ctx) {
        return !key(ctx).isEmpty();
    }

    public static String addr(Context ctx) {
        String a = prefs(ctx).getString(PREF_ADDR, DEFAULT_ADDR);
        return (a == null || a.trim().isEmpty()) ? DEFAULT_ADDR : a.trim();
    }

    /** Fire-and-forget auto sync (throttled), for app open
     *  and the daily worker. */
    public static void maybeAuto(final Context ctx) {
        if (!configured(ctx)) return;
        if (!prefs(ctx).getBoolean(PREF_AUTO, true)) return;
        long last = prefs(ctx).getLong(PREF_LAST_AT, 0);
        if (System.currentTimeMillis() - last < 5 * 60 * 1000) return;
        new Thread(new Runnable() {
            @Override public void run() { syncNow(ctx.getApplicationContext()); }
        }).start();
    }

    /** One full exchange. Returns a plain-words result. */
    public static synchronized String syncNow(Context ctx) {
        String k = key(ctx);
        if (k.isEmpty()) return "Add the sync key from the desktop app first.";
        long started = System.currentTimeMillis();
        try {
            Db db = new Db(ctx);
            SharedPreferences p = prefs(ctx);
            long pushed = p.getLong(PREF_PUSHED, 0);
            long cPushed = p.getLong(PREF_C_PUSHED, 0);

            JSONObject req = new JSONObject();
            req.put("last_seq", p.getLong(PREF_SEQ, 0));
            JSONArray items = new JSONArray();
            for (Item it : db.changedSince(pushed)) items.put(itemJson(it));
            req.put("items", items);
            JSONArray tombs = new JSONArray();
            for (String[] t : db.tombstonesSince(pushed)) {
                JSONObject o = new JSONObject();
                o.put("uuid", t[0]);
                o.put("deleted_at", Long.parseLong(t[1]));
                tombs.put(o);
            }
            req.put("tombs", tombs);
            JSONArray contacts = new JSONArray();
            for (Contacts.Contact c : Contacts.load(ctx)) {
                if (c.updatedAt > cPushed) contacts.put(contactJson(c));
            }
            req.put("contacts", contacts);
            JSONArray cTombs = new JSONArray();
            for (String[] t : Contacts.contactTombstones(ctx)) {
                JSONObject o = new JSONObject();
                o.put("uuid", t[0]);
                o.put("deleted_at", Long.parseLong(t[1]));
                cTombs.put(o);
            }
            req.put("contact_tombs", cTombs);
            JSONObject mind = new JSONObject();
            mind.put("agents_ts", ClioMind.agentsUpdatedAt(ctx));
            mind.put("agents", ClioMind.readAgents(ctx));
            JSONArray facts = new JSONArray();
            for (String f : ClioMind.factLines(ctx)) facts.put(f);
            mind.put("facts", facts);
            req.put("mind", mind);

            JSONObject resp = postJson(ctx, "/sync/exchange", req);
            if (resp == null) {
                if (lastError.equals("HTTP 401") || lastError.equals("HTTP 403")) {
                    return note(ctx, "The desktop refused the sync key - open "
                        + "Pairing and check the key matches the one Clio for "
                        + "Desktop shows.");
                }
                if (lastError.startsWith("HTTP")) {
                    return note(ctx, "The desktop answered with an error ("
                        + lastError + ") - sync not done.");
                }
                return note(ctx, "Couldn't reach the desktop at " + addr(ctx)
                    + " - is Clio for Desktop running? (" + lastError + ")");
            }

            // Adoptions first: the desktop may already hold
            // some of these records from a backup import.
            JSONObject adopt = resp.optJSONObject("adopt");
            if (adopt != null) {
                JSONObject ai = adopt.optJSONObject("items");
                if (ai != null) {
                    java.util.Iterator<String> keys = ai.keys();
                    while (keys.hasNext()) {
                        String oldU = keys.next();
                        db.adoptUuid(oldU, ai.optString(oldU, ""));
                    }
                }
                JSONObject ac = adopt.optJSONObject("contacts");
                if (ac != null) {
                    List<Contacts.Contact> all = Contacts.load(ctx);
                    boolean changed = false;
                    java.util.Iterator<String> keys = ac.keys();
                    while (keys.hasNext()) {
                        String oldU = keys.next();
                        String newU = ac.optString(oldU, "");
                        for (Contacts.Contact c : all) {
                            if (oldU.equals(c.uuid)) { c.uuid = newU; changed = true; }
                        }
                    }
                    if (changed) Contacts.save(ctx, all);
                }
            }

            int pulled = 0;
            List<Item> needFiles = new ArrayList<Item>();
            JSONArray rItems = resp.optJSONArray("items");
            if (rItems != null) {
                for (int i = 0; i < rItems.length(); i++) {
                    Item remote = itemFromJson(rItems.getJSONObject(i));
                    Item local = db.byUuid(remote.uuid);
                    if (local != null && local.updatedAt >= remote.updatedAt) continue;
                    if (local != null) {
                        remote.embedding = local.embedding;
                        remote.embeddingLocal = local.embeddingLocal;
                        remote.embeddingImage = local.embeddingImage;
                        remote.fileHash = local.fileHash;
                        remote.pages = local.pages;
                        remote.links = local.links;
                        if (canonical(local).equals(remoteFileName(remote))) {
                            remote.filePath = local.filePath;
                        }
                    }
                    db.applyRemote(remote);
                    pulled++;
                    String fn = remoteFileName(remote);
                    if (!fn.isEmpty()) {
                        File f = new File(ctx.getFilesDir(), fn);
                        if (!f.exists()) {
                            Item fresh = db.byUuid(remote.uuid);
                            if (fresh != null) needFiles.add(fresh);
                        } else {
                            Item fresh = db.byUuid(remote.uuid);
                            if (fresh != null && (fresh.filePath == null || fresh.filePath.isEmpty())) {
                                fresh.filePath = f.getAbsolutePath();
                                db.applyRemote(fresh);
                            }
                        }
                    }
                }
            }
            JSONArray rTombs = resp.optJSONArray("tombs");
            if (rTombs != null) {
                for (int i = 0; i < rTombs.length(); i++) {
                    JSONObject o = rTombs.getJSONObject(i);
                    db.applyTombstone(o.optString("uuid", ""), o.optLong("deleted_at", 0));
                }
            }
            JSONArray rContacts = resp.optJSONArray("contacts");
            if (rContacts != null) {
                for (int i = 0; i < rContacts.length(); i++) {
                    Contacts.Contact remote = contactFromJson(rContacts.getJSONObject(i));
                    Contacts.Contact local = Contacts.byUuid(ctx, remote.uuid);
                    if (local != null && local.updatedAt >= remote.updatedAt) continue;
                    if (local != null) remote.photo = local.photo.isEmpty() ? remote.photo : local.photo;
                    Contacts.applyRemote(ctx, remote);
                    // A photo we don't hold yet rides down too.
                    if (!remote.photo.isEmpty()) {
                        File pf = new File(ContactPhotos.dir(ctx), remote.photo);
                        if (!pf.exists()) downloadFile(ctx, remote.photo, pf);
                    }
                }
            }
            JSONArray rCTombs = resp.optJSONArray("contact_tombs");
            if (rCTombs != null) {
                for (int i = 0; i < rCTombs.length(); i++) {
                    JSONObject o = rCTombs.getJSONObject(i);
                    String u = o.optString("uuid", "");
                    long del = o.optLong("deleted_at", 0);
                    Contacts.Contact local = Contacts.byUuid(ctx, u);
                    if (local != null && local.updatedAt <= del) {
                        Contacts.delete(ctx, local.id);
                    }
                    Contacts.addContactTombstone(ctx, u, del);
                }
            }
            JSONObject rMind = resp.optJSONObject("mind");
            if (rMind != null) {
                long rts = rMind.optLong("agents_ts", 0);
                if (rts > ClioMind.agentsUpdatedAt(ctx)) {
                    ClioMind.writeAgents(ctx, rMind.optString("agents", ""), rts);
                }
                JSONArray rf = rMind.optJSONArray("facts");
                if (rf != null) {
                    List<String> union = new ArrayList<String>();
                    Set<String> seen = new HashSet<String>();
                    List<String> localFacts = ClioMind.factLines(ctx);
                    for (String f : localFacts) {
                        if (seen.add(normFact(f))) union.add(f);
                    }
                    for (int i = 0; i < rf.length(); i++) {
                        String f = rf.optString(i, "");
                        if (!f.isEmpty() && seen.add(normFact(f))) union.add(f);
                    }
                    if (union.size() != localFacts.size()) {
                        java.util.Collections.sort(union);
                        ClioMind.applyFactUnion(ctx, union);
                    }
                }
            }

            // Files the desktop wants from us.
            JSONArray wanted = resp.optJSONArray("files_wanted");
            if (wanted != null) {
                for (int i = 0; i < wanted.length(); i++) {
                    String name = wanted.optString(i, "");
                    File src = findLocalFile(ctx, db, name);
                    if (src != null && src.exists()) uploadFile(ctx, name, src);
                }
            }
            // Files we pulled records for but lack the bytes of.
            for (Item it : needFiles) {
                String name = remoteFileName(it);
                File dest = new File(ctx.getFilesDir(), name);
                if (downloadFile(ctx, name, dest)) {
                    it.filePath = dest.getAbsolutePath();
                    db.applyRemote(it);
                }
            }

            SharedPreferences.Editor e = p.edit();
            e.putLong(PREF_SEQ, resp.optLong("seq", p.getLong(PREF_SEQ, 0)));
            e.putLong(PREF_PUSHED, started);
            e.putLong(PREF_C_PUSHED, started);
            e.apply();
            return note(ctx, "Synced - " + pulled + (pulled == 1 ? " memory" : " memories")
                + " came down, vault is shared.");
        } catch (Exception ex) {
            return note(ctx, "Sync hit a snag (" + ex.getClass().getSimpleName()
                + ") - nothing was half-applied knowingly; try again.");
        }
    }

    private static String note(Context ctx, String msg) {
        SharedPreferences.Editor e = prefs(ctx).edit();
        e.putLong(PREF_LAST_AT, System.currentTimeMillis());
        e.putString(PREF_LAST_RESULT, msg);
        e.apply();
        return msg;
    }

    private static String normFact(String f) {
        return f.toLowerCase().replaceAll("\\s+", " ").trim();
    }

    /** Canonical cross-device file name for an item.
     *  Idempotent: a path that already IS the canonical
     *  name (an in-flight record) maps to itself. */
    public static String canonical(Item it) {
        if (it.filePath == null || it.filePath.isEmpty()) return "";
        String base = new File(it.filePath).getName();
        String prefix = it.createdAt + "_";
        return base.startsWith(prefix) ? base : prefix + base;
    }

    private static String remoteFileName(Item it) {
        return it.filePath == null ? "" : it.filePath;
        // For records in flight, filePath carries the canonical
        // name in the file_name field instead - see itemJson.
    }

    public static JSONObject itemJson(Item it) throws Exception {
        JSONObject o = new JSONObject();
        o.put("uuid", it.uuid);
        o.put("title", it.title);
        o.put("type", it.type);
        o.put("category", it.category);
        o.put("body", it.body);
        o.put("extracted", it.extracted);
        o.put("mime", it.mime);
        o.put("created_at", it.createdAt);
        o.put("reminder_at", it.reminderAt);
        o.put("amounts", it.amounts);
        o.put("handled_at", it.handledAt);
        o.put("deleted_at", it.deletedAt);
        o.put("is_private", it.isPrivate);
        o.put("file_name", canonical(it));
        o.put("updated_at", it.updatedAt);
        return o;
    }

    public static Item itemFromJson(JSONObject o) {
        Item it = new Item();
        it.uuid = o.optString("uuid", "");
        it.title = o.optString("title", "");
        it.type = o.optString("type", "TEXT");
        it.category = o.optString("category", "Notes");
        it.body = o.optString("body", "");
        it.extracted = o.optString("extracted", "");
        it.mime = o.optString("mime", "");
        it.createdAt = o.optLong("created_at", 0);
        it.reminderAt = o.optLong("reminder_at", -1);
        it.amounts = o.optString("amounts", "");
        it.handledAt = o.optLong("handled_at", 0);
        it.deletedAt = o.optLong("deleted_at", 0);
        it.isPrivate = o.optInt("is_private", 0);
        // The canonical file name rides in filePath while in
        // flight; syncNow turns it into a real path after the
        // bytes land.
        it.filePath = o.optString("file_name", "");
        it.updatedAt = o.optLong("updated_at", 0);
        return it;
    }

    public static JSONObject contactJson(Contacts.Contact c) throws Exception {
        JSONObject o = new JSONObject();
        o.put("uuid", c.uuid);
        o.put("name", c.name);
        o.put("rel", c.rel);
        o.put("phone", c.phone);
        o.put("email", c.email);
        o.put("address", c.address);
        o.put("birthday", c.birthday);
        o.put("org", c.org);
        o.put("note", c.note);
        o.put("fav", c.fav);
        o.put("photo", c.photo);
        o.put("updated_at", c.updatedAt);
        return o;
    }

    public static Contacts.Contact contactFromJson(JSONObject o) {
        Contacts.Contact c = new Contacts.Contact();
        c.uuid = o.optString("uuid", "");
        c.name = o.optString("name", "");
        c.rel = o.optString("rel", "");
        c.phone = o.optString("phone", "");
        c.email = o.optString("email", "");
        c.address = o.optString("address", "");
        c.birthday = o.optString("birthday", "");
        c.org = o.optString("org", "");
        c.note = o.optString("note", "");
        c.fav = o.optBoolean("fav", false);
        c.photo = o.optString("photo", "");
        c.updatedAt = o.optLong("updated_at", 0);
        return c;
    }

    private static File findLocalFile(Context ctx, Db db, String canonicalName) {
        for (Item it : db.all()) {
            if (canonical(it).equals(canonicalName)) {
                File f = new File(it.filePath);
                if (f.exists()) return f;
            }
        }
        // Contact photos share the files/ tree only via their
        // own dir; the desktop asks for them by bare name.
        File cp = new File(ContactPhotos.dir(ctx), canonicalName);
        if (cp.exists()) return cp;
        File pf = new File(ctx.getFilesDir(), canonicalName);
        if (pf.exists()) return pf;
        return null;
    }

    // ---- transport ----

    private static HttpURLConnection open(Context ctx, String path, String method)
            throws Exception {
        URL url = new URL("http://" + addr(ctx) + path);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(8000);
        c.setReadTimeout(60000);
        c.setRequestProperty("X-Sync-Key", key(ctx));
        return c;
    }

    private static String lastError = "";

    private static JSONObject postJson(Context ctx, String path, JSONObject body) {
        try {
            lastError = "";
            HttpURLConnection c = open(ctx, path, "POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            OutputStream os = c.getOutputStream();
            os.write(bytes);
            os.close();
            int code = c.getResponseCode();
            if (code != 200) {
                lastError = "HTTP " + code;
                return null;
            }
            return new JSONObject(readAll(c.getInputStream()));
        } catch (Exception e) {
            lastError = e.getClass().getSimpleName();
            return null;
        }
    }

    private static boolean uploadFile(Context ctx, String name, File src) {
        try {
            HttpURLConnection c = open(ctx, "/sync/file/" + name, "POST");
            c.setDoOutput(true);
            FileInputStream in = new FileInputStream(src);
            OutputStream os = c.getOutputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            in.close();
            os.close();
            return c.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean downloadFile(Context ctx, String name, File dest) {
        try {
            HttpURLConnection c = open(ctx, "/sync/file/" + name, "GET");
            if (c.getResponseCode() != 200) return false;
            InputStream in = c.getInputStream();
            FileOutputStream out = new FileOutputStream(dest);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            out.close();
            return dest.length() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
}
