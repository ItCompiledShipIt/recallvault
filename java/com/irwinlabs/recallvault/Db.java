package com.irwinlabs.recallvault;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/** SQLite vault. Everything stays on the phone; no account, no sync. */
public class Db extends SQLiteOpenHelper {

    private static final String NAME = "recallvault.db";
    private static final int VERSION = 11;

    public Db(Context ctx) {
        super(ctx.getApplicationContext(), NAME, null, VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE items ("
            + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + "title TEXT NOT NULL,"
            + "type TEXT NOT NULL,"
            + "category TEXT NOT NULL,"
            + "body TEXT NOT NULL,"
            + "extracted TEXT NOT NULL,"
            + "file_path TEXT NOT NULL,"
            + "mime TEXT NOT NULL,"
            + "created_at INTEGER NOT NULL,"
            + "reminder_at INTEGER NOT NULL,"
            + "amounts TEXT NOT NULL,"
            + "embedding TEXT NOT NULL,"
            + "embedding_local TEXT NOT NULL DEFAULT '',"
            + "embedding_image TEXT NOT NULL DEFAULT '',"
            + "handled_at INTEGER NOT NULL DEFAULT 0,"
            + "file_hash TEXT NOT NULL DEFAULT '',"
            + "pages TEXT NOT NULL DEFAULT '',"
            + "links TEXT NOT NULL DEFAULT '',"
            + "deleted_at INTEGER NOT NULL DEFAULT 0,"
            + "expiry_at INTEGER NOT NULL DEFAULT 0,"
            + "is_private INTEGER NOT NULL DEFAULT 0,"
            + "uuid TEXT NOT NULL DEFAULT '',"
            + "updated_at INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE IF NOT EXISTS tombstones ("
            + "uuid TEXT PRIMARY KEY, deleted_at INTEGER NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        if (oldV < 2) {
            // v2: on-device embedding space, kept apart from the server one.
            db.execSQL("ALTER TABLE items ADD COLUMN embedding_local TEXT NOT NULL DEFAULT ''");
        }
        if (oldV < 3) {
            // v3: vision-tower image vectors for photo search by meaning.
            db.execSQL("ALTER TABLE items ADD COLUMN embedding_image TEXT NOT NULL DEFAULT ''");
        }
        if (oldV < 4) {
            // v4: reminders can be finished (paid/done) - v0.13.0.
            db.execSQL("ALTER TABLE items ADD COLUMN handled_at INTEGER NOT NULL DEFAULT 0");
        }
        if (oldV < 5) {
            // v5: file fingerprints for the duplicate guard - v0.15.0.
            db.execSQL("ALTER TABLE items ADD COLUMN file_hash TEXT NOT NULL DEFAULT ''");
        }
        if (oldV < 6) {
            // v6: extra pages of a multi-page scan - v0.20.0.
            db.execSQL("ALTER TABLE items ADD COLUMN pages TEXT NOT NULL DEFAULT ''");
        }
        if (oldV < 7) {
            // v7: linked memories - v0.23.0.
            db.execSQL("ALTER TABLE items ADD COLUMN links TEXT NOT NULL DEFAULT ''");
        }
        if (oldV < 8) {
            // v8: trash with undo - deleted_at marks a soft delete - v0.25.0.
            db.execSQL("ALTER TABLE items ADD COLUMN deleted_at INTEGER NOT NULL DEFAULT 0");
        }
        if (oldV < 9) {
            // v9: expiry radar - expiry_at tracked apart from due dates - v0.30.0.
            db.execSQL("ALTER TABLE items ADD COLUMN expiry_at INTEGER NOT NULL DEFAULT 0");
        }
        if (oldV < 10) {
            // v10: private memories - per-item display lock - v0.46.0.
            db.execSQL("ALTER TABLE items ADD COLUMN is_private INTEGER NOT NULL DEFAULT 0");
        }
        if (oldV < 11) {
            // v11: live sync with Clio for Desktop (v0.67.0) -
            // a stable uuid per memory, an updated_at clock for
            // last-writer-wins merges, and tombstones so a
            // deletion on one side deletes on the other.
            db.execSQL("ALTER TABLE items ADD COLUMN uuid TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE items ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0");
            db.execSQL("CREATE TABLE IF NOT EXISTS tombstones ("
                + "uuid TEXT PRIMARY KEY, deleted_at INTEGER NOT NULL)");
            Cursor c = db.rawQuery("SELECT id, created_at FROM items WHERE uuid=''", null);
            while (c.moveToNext()) {
                ContentValues v = new ContentValues();
                v.put("uuid", java.util.UUID.randomUUID().toString());
                v.put("updated_at", c.getLong(1));
                db.update("items", v, "id=?", new String[]{String.valueOf(c.getLong(0))});
            }
            c.close();
        }
    }

    private ContentValues values(Item it) {
        ContentValues v = new ContentValues();
        // Text columns are encrypted at rest (v0.16.0, VaultCrypto).
        v.put("title", VaultCrypto.enc(it.title));
        v.put("type", it.type);
        v.put("category", it.category);
        v.put("body", VaultCrypto.enc(it.body));
        v.put("extracted", VaultCrypto.enc(it.extracted));
        v.put("file_path", it.filePath);
        v.put("mime", it.mime);
        v.put("created_at", it.createdAt);
        v.put("reminder_at", it.reminderAt);
        v.put("amounts", VaultCrypto.enc(it.amounts));
        v.put("embedding", it.embedding);
        v.put("embedding_local", it.embeddingLocal);
        v.put("embedding_image", it.embeddingImage);
        v.put("handled_at", it.handledAt);
        v.put("file_hash", it.fileHash == null ? "" : it.fileHash);
        v.put("pages", it.pages == null ? "" : it.pages);
        v.put("links", it.links == null ? "" : it.links);
        // Expiry is re-derived from the words on every save (v0.30.0).
        long exp = Expiry.expiryOf(it);
        it.expiryAt = exp > 0 ? exp : 0;
        v.put("expiry_at", it.expiryAt);
        v.put("is_private", it.isPrivate);
        if (it.uuid == null || it.uuid.isEmpty()) {
            it.uuid = java.util.UUID.randomUUID().toString();
        }
        v.put("uuid", it.uuid);
        v.put("updated_at", it.updatedAt);
        return v;
    }

    private Item fromCursor(Cursor c) {
        Item it = new Item();
        it.id = c.getLong(0);
        it.title = VaultCrypto.dec(c.getString(1));
        it.type = c.getString(2);
        it.category = c.getString(3);
        it.body = VaultCrypto.dec(c.getString(4));
        it.extracted = VaultCrypto.dec(c.getString(5));
        it.filePath = c.getString(6);
        it.mime = c.getString(7);
        it.createdAt = c.getLong(8);
        it.reminderAt = c.getLong(9);
        it.amounts = VaultCrypto.dec(c.getString(10));
        it.embedding = c.getString(11);
        it.embeddingLocal = c.getString(12);
        it.embeddingImage = c.getString(13);
        it.handledAt = c.getLong(14);
        it.fileHash = c.getString(15);
        it.pages = c.getString(16);
        it.links = c.getString(17);
        it.deletedAt = c.getLong(18);
        it.expiryAt = c.getLong(19);
        it.isPrivate = c.getInt(20);
        it.uuid = c.getString(21);
        it.updatedAt = c.getLong(22);
        return it;
    }

    private static final String COLS =
        "id,title,type,category,body,extracted,file_path,mime,created_at,reminder_at,amounts,embedding,embedding_local,embedding_image,handled_at,file_hash,pages,links,deleted_at,expiry_at,is_private,uuid,updated_at";

    /**
     * One-time rewrite of every row through the encrypting values()
     * path (v0.16.0). Safe to call repeatedly: reads decrypt first,
     * so nothing is ever double-encrypted. Records completion in
     * prefs so it runs exactly once.
     */
    public static void migrateEncryption(android.content.Context ctx) {
        android.content.SharedPreferences p =
            ctx.getSharedPreferences(Embed.PREFS, android.content.Context.MODE_PRIVATE);
        if (p.getLong("vault_encrypted_at", 0) > 0) return;
        Db db = new Db(ctx);
        for (Item it : db.all()) db.update(it);
        p.edit().putLong("vault_encrypted_at", System.currentTimeMillis()).apply();
    }

    public long insert(Item it) {
        it.updatedAt = System.currentTimeMillis();
        SQLiteDatabase db = getWritableDatabase();
        long id = db.insert("items", null, values(it));
        it.id = id;
        return id;
    }

    public void update(Item it) {
        it.updatedAt = System.currentTimeMillis();
        SQLiteDatabase db = getWritableDatabase();
        db.update("items", values(it), "id=?", new String[]{String.valueOf(it.id)});
    }

    // ---- sync support (v0.67.0) ----

    /** Every memory changed since a watermark, trash included. */
    public java.util.List<Item> changedSince(long updatedAfter) {
        java.util.List<Item> out = new java.util.ArrayList<Item>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT " + COLS + " FROM items WHERE updated_at>? ORDER BY updated_at",
            new String[]{String.valueOf(updatedAfter)});
        while (c.moveToNext()) out.add(fromCursor(c));
        c.close();
        return out;
    }

    public Item byUuid(String uuid) {
        if (uuid == null || uuid.isEmpty()) return null;
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT " + COLS + " FROM items WHERE uuid=?",
            new String[]{uuid});
        Item it = null;
        if (c.moveToFirst()) it = fromCursor(c);
        c.close();
        return it;
    }

    /** Write a record that arrived from sync, keeping ITS clock. */
    public void applyRemote(Item it) {
        SQLiteDatabase db = getWritableDatabase();
        Item local = byUuid(it.uuid);
        ContentValues v = values(it);
        if (local == null) {
            long id = db.insert("items", null, v);
            it.id = id;
        } else {
            it.id = local.id;
            db.update("items", v, "id=?", new String[]{String.valueOf(local.id)});
        }
        clearTombstone(it.uuid);
    }

    /** Rename a local record's uuid (first-sync adoption). */
    public void adoptUuid(String oldUuid, String newUuid) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("uuid", newUuid);
        db.update("items", v, "uuid=?", new String[]{oldUuid});
    }

    public java.util.List<String[]> tombstonesSince(long deletedAfter) {
        java.util.List<String[]> out = new java.util.ArrayList<String[]>();
        SQLiteDatabase db = getWritableDatabase();
        Cursor c = db.rawQuery("SELECT uuid, deleted_at FROM tombstones WHERE deleted_at>?",
            new String[]{String.valueOf(deletedAfter)});
        while (c.moveToNext()) out.add(new String[]{c.getString(0), String.valueOf(c.getLong(1))});
        c.close();
        return out;
    }

    public void recordTombstone(String uuid, long deletedAt) {
        if (uuid == null || uuid.isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("uuid", uuid);
        v.put("deleted_at", deletedAt);
        db.insertWithOnConflict("tombstones", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public void clearTombstone(String uuid) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("tombstones", "uuid=?", new String[]{uuid});
    }

    /** A deletion arrived from sync: honor it unless the local
     *  copy was edited after the deletion happened. */
    public void applyTombstone(String uuid, long deletedAt) {
        Item local = byUuid(uuid);
        if (local != null && local.updatedAt <= deletedAt) {
            SQLiteDatabase db = getWritableDatabase();
            db.delete("items", "id=?", new String[]{String.valueOf(local.id)});
        }
        if (local == null || local.updatedAt <= deletedAt) {
            recordTombstone(uuid, deletedAt);
        }
    }

    public void updateEmbedding(long id, String embedding) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("embedding", embedding == null ? "" : embedding);
        db.update("items", v, "id=?", new String[]{String.valueOf(id)});
    }

    public void updateEmbeddingLocal(long id, String embedding) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("embedding_local", embedding == null ? "" : embedding);
        db.update("items", v, "id=?", new String[]{String.valueOf(id)});
    }

    public void updateEmbeddingImage(long id, String embedding) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("embedding_image", embedding == null ? "" : embedding);
        db.update("items", v, "id=?", new String[]{String.valueOf(id)});
    }

    public void delete(long id) {
        Item gone = get(id);
        if (gone != null && gone.uuid != null && !gone.uuid.isEmpty()) {
            recordTombstone(gone.uuid, System.currentTimeMillis());
        }
        SQLiteDatabase db = getWritableDatabase();
        db.delete("items", "id=?", new String[]{String.valueOf(id)});
        // Links are symmetric: scrub the gone id from every other item.
        for (Item it : all()) {
            if (Links.contains(it.links, id)) {
                it.links = Links.without(it.links, id);
                update(it);
            }
        }
    }

    /** Link or unlink two memories, written on both sides (v0.23.0). */
    public void setLinked(Item a, Item b, boolean on) {
        if (a == null || b == null || a.id == b.id) return;
        a.links = on ? Links.with(a.links, b.id) : Links.without(a.links, b.id);
        b.links = on ? Links.with(b.links, a.id) : Links.without(b.links, a.id);
        update(a);
        update(b);
    }

    public Item get(long id) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT " + COLS + " FROM items WHERE id=?",
            new String[]{String.valueOf(id)});
        Item it = null;
        if (c.moveToFirst()) it = fromCursor(c);
        c.close();
        return it;
    }

    public List<Item> all() {
        return query("SELECT " + COLS + " FROM items WHERE deleted_at=0 ORDER BY created_at DESC", null);
    }

    public List<Item> byCategory(String category) {
        if (category == null || category.equals("All")) return all();
        return query("SELECT " + COLS + " FROM items WHERE category=? AND deleted_at=0 ORDER BY created_at DESC",
            new String[]{category});
    }

    /** Trashed memories, most recently deleted first (v0.25.0). */
    public List<Item> trashed() {
        return query("SELECT " + COLS + " FROM items WHERE deleted_at>0 ORDER BY deleted_at DESC", null);
    }

    /** Soft delete: hide the memory and scrub its links; files stay for restore (v0.25.0). */
    public void trash(long id) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("deleted_at", System.currentTimeMillis());
        db.update("items", v, "id=?", new String[]{String.valueOf(id)});
        for (Item it : all()) {
            if (Links.contains(it.links, id)) {
                it.links = Links.without(it.links, id);
                update(it);
            }
        }
    }

    /** Bring a trashed memory back (v0.25.0). */
    public void restore(long id) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("deleted_at", 0L);
        db.update("items", v, "id=?", new String[]{String.valueOf(id)});
    }

    /** First item whose file has this SHA-256, or null (v0.15.0). */
    public Item findByHash(String hash) {
        if (hash == null || hash.isEmpty()) return null;
        List<Item> rows = query("SELECT " + COLS + " FROM items WHERE file_hash=? AND deleted_at=0 LIMIT 1",
            new String[]{hash});
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Item> withReminders() {
        return query("SELECT " + COLS + " FROM items WHERE reminder_at>0 AND deleted_at=0 ORDER BY reminder_at ASC", null);
    }

    public List<Item> needingEmbedding() {
        return query("SELECT " + COLS + " FROM items WHERE embedding='' AND deleted_at=0 ORDER BY created_at DESC", null);
    }

    public List<Item> needingLocalEmbedding() {
        return query("SELECT " + COLS + " FROM items WHERE embedding_local='' AND deleted_at=0 ORDER BY created_at DESC", null);
    }

    public List<Item> needingImageEmbedding() {
        return query("SELECT " + COLS + " FROM items WHERE embedding_image='' AND deleted_at=0 "
            + "AND type IN ('PHOTO','SCAN') AND file_path<>'' ORDER BY created_at DESC", null);
    }

    public int count() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM items WHERE deleted_at=0", null);
        int n = 0;
        if (c.moveToFirst()) n = c.getInt(0);
        c.close();
        return n;
    }

    private List<Item> query(String sql, String[] args) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery(sql, args);
        List<Item> out = new ArrayList<Item>();
        while (c.moveToNext()) out.add(fromCursor(c));
        c.close();
        return out;
    }
}
