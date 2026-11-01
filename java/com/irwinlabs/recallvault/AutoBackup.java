package com.irwinlabs.recallvault;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.security.KeyStore;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * AutoBackup - weekly encrypted backups written by themselves into
 * a folder the user picks once (v0.12.0).
 *
 * Format "RVBK2": magic + IV(12) + AES-256-GCM ciphertext of the
 * same ZIP Backup.buildZip makes. The key is a random AES key held
 * in the Android Keystore - it never leaves this phone, so an
 * auto-backup restores on THIS phone only. The password export
 * (RVBK1) remains the one that moves between phones; the UI says so.
 *
 * Cadence: ReminderWorker's daily pass calls runIfDue(); a backup
 * runs when the last one is more than 6 days old. The newest 4
 * auto-backups are kept in the folder.
 */
public final class AutoBackup {

    private AutoBackup() {}

    static final byte[] MAGIC2 = {'R', 'V', 'B', 'K', '2'};
    private static final String KEY_ALIAS = "rv_autobackup_key";
    private static final int IV_LEN = 12;
    private static final int KEEP = 4;
    private static final long DUE_MS = 6L * 24 * 3600 * 1000;
    static final String FILE_PREFIX = "recallvault-auto-";

    // ---------- prefs ----------

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(Embed.PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context ctx) {
        return prefs(ctx).getBoolean("autobackup_enabled", false);
    }

    public static void setEnabled(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean("autobackup_enabled", on).apply();
    }

    public static Uri folder(Context ctx) {
        String s = prefs(ctx).getString("autobackup_tree", null);
        return s == null ? null : Uri.parse(s);
    }

    public static void setFolder(Context ctx, Uri tree) {
        prefs(ctx).edit().putString("autobackup_tree", tree.toString()).apply();
    }

    public static long lastRun(Context ctx) {
        return prefs(ctx).getLong("autobackup_last_at", 0);
    }

    public static String statusText(Context ctx) {
        if (folder(ctx) == null) return "No folder chosen yet.";
        long at = lastRun(ctx);
        if (at == 0) return "Folder chosen - the first backup runs within a day, or tap Back up now.";
        return "Last automatic backup: " + Extract.formatDate(at) + ".";
    }

    // ---------- key + crypto ----------

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) {
            return (SecretKey) ks.getKey(KEY_ALIAS, null);
        }
        KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        gen.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build());
        return gen.generateKey();
    }

    public static boolean isAutoBackup(byte[] blob) {
        if (blob == null || blob.length < MAGIC2.length) return false;
        for (int i = 0; i < MAGIC2.length; i++) if (blob[i] != MAGIC2[i]) return false;
        return true;
    }

    public static byte[] encrypt(byte[] plain) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key());
        byte[] iv = c.getIV();
        byte[] ct = c.doFinal(plain);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(MAGIC2);
        out.write(iv);
        out.write(ct);
        return out.toByteArray();
    }

    public static byte[] decrypt(byte[] blob) throws Exception {
        if (!isAutoBackup(blob) || blob.length < MAGIC2.length + IV_LEN + 16) {
            throw new Exception("Not an automatic RecallVault backup");
        }
        int off = MAGIC2.length;
        byte[] iv = new byte[IV_LEN];
        System.arraycopy(blob, off, iv, 0, IV_LEN);
        off += IV_LEN;
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
        try {
            return c.doFinal(blob, off, blob.length - off);
        } catch (Exception e) {
            throw new Exception("This automatic backup belongs to a different phone or is damaged");
        }
    }

    // ---------- the run ----------

    /** Build, encrypt and file one auto-backup now. Returns the item count. */
    public static int runNow(Context ctx) throws Exception {
        Uri tree = folder(ctx);
        if (tree == null) throw new Exception("No backup folder chosen");
        Db db = new Db(ctx);
        java.util.List<Item> all = db.all();
        byte[] enc = encrypt(Backup.buildZip(all));
        ContentResolver cr = ctx.getContentResolver();
        String parentDoc = DocumentsContract.getTreeDocumentId(tree);
        String name = FILE_PREFIX
            + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date()) + ".rvbackup";
        Uri doc = DocumentsContract.createDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, parentDoc),
            "application/octet-stream", name);
        if (doc == null) throw new Exception("Could not create the backup file in that folder");
        OutputStream out = cr.openOutputStream(doc);
        if (out == null) throw new Exception("Could not open the backup file");
        out.write(enc);
        out.close();
        prune(ctx, tree);
        long now = System.currentTimeMillis();
        prefs(ctx).edit()
            .putLong("autobackup_last_at", now)
            .putLong("backup_last_at", now)
            .putInt("backup_item_count", all.size())
            .apply();
        return all.size();
    }

    private static void prune(Context ctx, Uri tree) {
        try {
            ContentResolver cr = ctx.getContentResolver();
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree,
                DocumentsContract.getTreeDocumentId(tree));
            List<String[]> docs = new ArrayList<String[]>();
            Cursor c = cr.query(children,
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                             DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                null, null, null);
            if (c != null) {
                while (c.moveToNext()) {
                    String name = c.getString(1);
                    if (name != null && name.startsWith(FILE_PREFIX)) {
                        docs.add(new String[]{c.getString(0), name});
                    }
                }
                c.close();
            }
            // Names carry the date; lexicographic order is chronological.
            Collections.sort(docs, new java.util.Comparator<String[]>() {
                @Override public int compare(String[] a, String[] b) { return a[1].compareTo(b[1]); }
            });
            while (docs.size() > KEEP) {
                String[] victim = docs.remove(0);
                DocumentsContract.deleteDocument(cr,
                    DocumentsContract.buildDocumentUriUsingTree(tree, victim[0]));
            }
        } catch (Exception e) {
            // Pruning is housekeeping; never fail a backup over it.
        }
    }

    /** Called from the daily reminder pass; never throws. */
    public static void runIfDue(Context ctx) {
        try {
            if (!isEnabled(ctx) || folder(ctx) == null) return;
            if (System.currentTimeMillis() - lastRun(ctx) < DUE_MS) return;
            runNow(ctx);
        } catch (Exception e) {
            // Silent by design - the Settings line shows the last good run.
        }
    }
}
