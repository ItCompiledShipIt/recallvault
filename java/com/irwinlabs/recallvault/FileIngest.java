package com.irwinlabs.recallvault;

import android.content.ContentUris;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Automatic ingest (v0.27.0): the vault watches places chris chose
 * and files new material by itself.
 *
 * Two sources, both opt-in:
 *  - Watched folders (SAF tree grants): walked fully; a seen-index
 *    (document uri + last-modified + size) keeps rewalks cheap,
 *    and the SHA-256 duplicate guard is the second line of defense.
 *  - The whole phone (MediaStore): new photos and audio only, and
 *    only items added AFTER the mode was turned on - a watermark,
 *    never a flood of the existing library. Documents are not
 *    covered phone-wide (Android gives media permissions no reach
 *    into documents); folders cover those.
 *
 * Everything filed here goes through the same pipeline as a manual
 * save: copy into the vault, hash, OCR or PDF text, fact
 * extraction, embeddings - so it is findable like anything else.
 * Runs from the daily ReminderWorker pass and opportunistically
 * when the app opens; also on demand from Settings.
 */
public final class FileIngest {

    private FileIngest() {}

    private static final String P_ENABLED = "ingest_enabled";
    private static final String P_PHONE = "ingest_phone";
    private static final String P_FOLDERS = "ingest_folders";
    private static final String P_LAST_AT = "ingest_last_at";
    private static final String P_LAST_COUNT = "ingest_last_count";
    private static final String P_WATERMARK = "ingest_media_watermark"; // seconds
    private static final int MAX_PER_SCAN = 100;

    // ---------- settings ----------

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(Embed.PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context ctx) {
        return prefs(ctx).getBoolean(P_ENABLED, false);
    }

    public static void setEnabled(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(P_ENABLED, on).apply();
    }

    public static boolean phoneMode(Context ctx) {
        return prefs(ctx).getBoolean(P_PHONE, false);
    }

    public static void setPhoneMode(Context ctx, boolean on) {
        SharedPreferences.Editor e = prefs(ctx).edit();
        e.putBoolean(P_PHONE, on);
        if (on) {
            // Only files added from this moment on - never the backlog.
            e.putLong(P_WATERMARK, System.currentTimeMillis() / 1000L);
        }
        e.apply();
    }

    public static List<Uri> folders(Context ctx) {
        List<Uri> out = new ArrayList<Uri>();
        Set<String> raw = prefs(ctx).getStringSet(P_FOLDERS, new HashSet<String>());
        for (String s : raw) {
            try { out.add(Uri.parse(s)); } catch (Exception e) { /* skip junk */ }
        }
        return out;
    }

    public static void addFolder(Context ctx, Uri tree) {
        Set<String> raw = new HashSet<String>(
            prefs(ctx).getStringSet(P_FOLDERS, new HashSet<String>()));
        raw.add(tree.toString());
        prefs(ctx).edit().putStringSet(P_FOLDERS, raw).apply();
    }

    public static void removeFolder(Context ctx, Uri tree) {
        Set<String> raw = new HashSet<String>(
            prefs(ctx).getStringSet(P_FOLDERS, new HashSet<String>()));
        raw.remove(tree.toString());
        prefs(ctx).edit().putStringSet(P_FOLDERS, raw).apply();
    }

    public static String statusText(Context ctx) {
        long at = prefs(ctx).getLong(P_LAST_AT, 0);
        if (at == 0) return "Automatic ingest has not run yet.";
        int n = prefs(ctx).getInt(P_LAST_COUNT, 0);
        return "Last scan " + Extract.formatDate(at) + " - filed " + n
            + (n == 1 ? " new memory." : " new memories.");
    }

    /** Worker/open hook: scan only when chris turned it on. */
    public static void runIfEnabled(Context ctx) {
        if (!isEnabled(ctx)) return;
        if (folders(ctx).isEmpty() && !phoneMode(ctx)) return;
        try { scan(ctx); } catch (Exception e) { /* next pass tries again */ }
    }

    // ---------- the scan ----------

    // One scan at a time, process-wide. The triggers (app open,
    // the daily worker, Settings) can overlap, and two scans
    // racing the hash guard filed the same photo three times -
    // identical hashes on ids 99-101, all copied within 90 ms
    // (device DB, Nov 1, 2026). The guard is check-then-insert,
    // so a second scan must never start while one is running.
    private static final java.util.concurrent.atomic.AtomicBoolean SCANNING =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    /** File everything new from the watched places. Returns the count filed. */
    public static int scan(Context ctx) {
        if (!SCANNING.compareAndSet(false, true)) return 0;
        try {
            return scanInner(ctx);
        } finally {
            SCANNING.set(false);
        }
    }

    private static int scanInner(Context ctx) {
        Db db = new Db(ctx);
        Map<String, long[]> seen = loadSeen(ctx);
        int[] budget = new int[]{MAX_PER_SCAN};
        int filed = 0;
        for (Uri tree : folders(ctx)) {
            if (budget[0] <= 0) break;
            try {
                String rootId = DocumentsContract.getTreeDocumentId(tree);
                filed += walkFolder(ctx, db, tree, rootId, 0, seen, budget);
            } catch (Exception e) {
                // A revoked or moved folder must not sink the scan.
            }
        }
        if (phoneMode(ctx) && budget[0] > 0) {
            filed += scanMedia(ctx, db, seen, budget);
        }
        saveSeen(ctx, seen);
        prefs(ctx).edit()
            .putLong(P_LAST_AT, System.currentTimeMillis())
            .putInt(P_LAST_COUNT, filed)
            .apply();
        if (filed > 0) Embed.embedPending(ctx, db);
        return filed;
    }

    private static int walkFolder(Context ctx, Db db, Uri tree, String docId, int depth,
                                  Map<String, long[]> seen, int[] budget) {
        if (depth > 6 || budget[0] <= 0) return 0;
        int filed = 0;
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(children, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_SIZE}, null, null, null);
            if (c == null) return 0;
            while (c.moveToNext() && budget[0] > 0) {
                String id = c.getString(0);
                String mime = c.getString(1);
                long modified = c.isNull(2) ? 0 : c.getLong(2);
                long size = c.isNull(3) ? -1 : c.getLong(3);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    filed += walkFolder(ctx, db, tree, id, depth + 1, seen, budget);
                } else if (mime != null && supported(mime)) {
                    Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                    String key = doc.toString();
                    long[] prev = seen.get(key);
                    if (prev != null && prev[0] == modified && prev[1] == size) continue;
                    budget[0]--;
                    int outcome = ingestOne(ctx, db, doc, mime);
                    if (outcome == 1) filed++;
                    if (outcome >= 0) {
                        // Filed, or a duplicate (settled business either
                        // way) - do not re-copy it on every walk.
                        seen.put(key, new long[]{modified, size});
                    }
                }
            }
        } catch (Exception e) {
            // Skip unreadable subfolders.
        } finally {
            if (c != null) c.close();
        }
        return filed;
    }

    private static int scanMedia(Context ctx, Db db, Map<String, long[]> seen, int[] budget) {
        long watermark = prefs(ctx).getLong(P_WATERMARK, System.currentTimeMillis() / 1000L);
        long highWater = watermark;
        int filed = 0;
        Uri[] collections = new Uri[]{
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI};
        for (Uri collection : collections) {
            if (budget[0] <= 0) break;
            Cursor c = null;
            try {
                c = ctx.getContentResolver().query(collection,
                    new String[]{MediaStore.MediaColumns._ID,
                        MediaStore.MediaColumns.DATE_ADDED,
                        MediaStore.MediaColumns.MIME_TYPE},
                    MediaStore.MediaColumns.DATE_ADDED + " > ?",
                    new String[]{String.valueOf(watermark)},
                    MediaStore.MediaColumns.DATE_ADDED + " ASC");
                if (c == null) continue;
                while (c.moveToNext() && budget[0] > 0) {
                    long id = c.getLong(0);
                    long added = c.getLong(1);
                    String mime = c.getString(2);
                    if (added > highWater) highWater = added;
                    if (mime == null || !supported(mime)) continue;
                    // The seen-index is the durable truth here, not
                    // the watermark alone: MediaStore ids are stable,
                    // so a file once settled is never re-filed even
                    // if the watermark ever moves backwards (a
                    // reverted pref, a restored backup). The
                    // watermark only bounds the query.
                    String key = "media:" + collection.getLastPathSegment() + ":" + id;
                    if (seen.containsKey(key)) continue;
                    budget[0]--;
                    Uri item = ContentUris.withAppendedId(collection, id);
                    int outcome = ingestOne(ctx, db, item, mime);
                    if (outcome == 1) filed++;
                    if (outcome >= 0) seen.put(key, new long[]{added, 0});
                }
            } catch (Exception e) {
                // Permission missing or provider hiccup: try again next pass.
            } finally {
                if (c != null) c.close();
            }
        }
        prefs(ctx).edit().putLong(P_WATERMARK, highWater).apply();
        return filed;
    }

    static boolean supported(String mime) {
        return IngestSeen.supported(mime);
    }

    // ---------- one file ----------

    /** Copy, hash, read, extract, file. 1 = filed, 0 = duplicate, -1 = failed. */
    private static int ingestOne(Context ctx, Db db, Uri uri, String mime) {
        File dest = null;
        try {
            String name = displayName(ctx, uri);
            dest = copyToVault(ctx, uri, name);
            String hash = "";
            try { hash = Hashes.sha256(dest); } catch (Exception e) { /* best effort */ }
            if (!hash.isEmpty() && db.findByHash(hash) != null) {
                try { dest.delete(); } catch (Exception e) {}
                return 0;
            }
            String fm = mime == null ? "" : mime;
            boolean isPdf = "application/pdf".equals(fm)
                || (name != null && name.toLowerCase().endsWith(".pdf"));
            String machine = "";
            if (isPdf) {
                PdfText.Result r = PdfText.read(dest);
                machine = r.text == null ? "" : r.text;
            } else if (fm.startsWith("image/")) {
                machine = Ocr.recognizeSync(dest);
            } else if (fm.startsWith("text/") || (name != null && name.endsWith(".txt"))) {
                machine = readText(dest);
            }
            Item it = new Item();
            it.type = fm.startsWith("image/") ? "PHOTO" : fm.startsWith("audio/") ? "VOICE" : "DOCUMENT";
            it.filePath = dest.getAbsolutePath();
            it.fileHash = hash;
            it.mime = fm;
            it.createdAt = System.currentTimeMillis();
            it.extracted = machine;
            Extract.Facts f = Extract.analyze(name + "\n" + machine);
            it.category = f.category;
            it.title = Extract.suggestTitle(machine, name);
            it.reminderAt = f.dueDate;
            StringBuilder am = new StringBuilder();
            for (int i = 0; i < f.amounts.size(); i++) {
                if (i > 0) am.append(',');
                am.append(f.amounts.get(i));
            }
            it.amounts = am.toString();
            // Receipt mode (v0.42.0): ingested scans file as expenses too.
            if (fm.startsWith("image/")) Receipts.refine(it, machine, false);
            db.insert(it);
            return 1;
        } catch (Exception e) {
            if (dest != null) {
                try { dest.delete(); } catch (Exception ignored) {}
            }
            return -1;
        }
    }

    private static File copyToVault(Context ctx, Uri uri, String name) throws Exception {
        File dir = new File(ctx.getFilesDir(), "imports");
        dir.mkdirs();
        String safe = (name == null ? "file" : name).replaceAll("[^a-zA-Z0-9._-]", "_");
        File dest = new File(dir, System.currentTimeMillis() + "_" + safe);
        InputStream in = ctx.getContentResolver().openInputStream(uri);
        if (in == null) throw new Exception("cannot open " + uri);
        try {
            FileOutputStream out = new FileOutputStream(dest);
            try {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally { out.close(); }
        } finally { in.close(); }
        return dest;
    }

    private static String displayName(Context ctx, Uri uri) {
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String s = c.getString(idx);
                    if (s != null && !s.isEmpty()) return s;
                }
            }
        } catch (Exception e) {
            // fall through
        } finally {
            if (c != null) c.close();
        }
        return "file_" + System.currentTimeMillis();
    }

    private static String readText(File f) {
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try {
                byte[] bytes = new byte[(int) Math.min(f.length(), 200000)];
                int off = 0, n;
                while (off < bytes.length && (n = in.read(bytes, off, bytes.length - off)) > 0) off += n;
                return new String(bytes, 0, off, "UTF-8");
            } finally { in.close(); }
        } catch (Exception e) {
            return "";
        }
    }

    // ---------- seen index (codec in IngestSeen, JVM-tested) ----------

    private static Map<String, long[]> loadSeen(Context ctx) {
        List<String> lines = new ArrayList<String>();
        File f = new File(ctx.getFilesDir(), "ingest_seen.txt");
        if (f.exists()) {
            try {
                java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f));
                try {
                    String line;
                    while ((line = r.readLine()) != null) lines.add(line);
                } finally { r.close(); }
            } catch (Exception e) { /* start fresh */ }
        }
        return IngestSeen.parseSeen(lines);
    }

    private static void saveSeen(Context ctx, Map<String, long[]> seen) {
        // Write-then-rename: a kill mid-write used to truncate the
        // index to zero bytes (observed Nov 1, 2026), which silently
        // disarmed folder dedupe for the next pass.
        File f = new File(ctx.getFilesDir(), "ingest_seen.txt");
        File tmp = new File(ctx.getFilesDir(), "ingest_seen.txt.tmp");
        try {
            java.io.PrintWriter w = new java.io.PrintWriter(new java.io.FileWriter(tmp));
            try {
                for (Map.Entry<String, long[]> e : seen.entrySet()) {
                    w.println(IngestSeen.seenLine(e.getKey(), e.getValue()[0], e.getValue()[1]));
                }
            } finally { w.close(); }
            if (!tmp.renameTo(f)) {
                try { tmp.delete(); } catch (Exception e) { /* ignore */ }
            }
        } catch (Exception e) { /* the hash guard still protects us */ }
    }
}
