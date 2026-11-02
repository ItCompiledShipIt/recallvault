package com.irwinlabs.recallvault;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypted vault backup (v0.6.0).
 *
 * One .rvbackup file = magic || salt || iv || AES-256-GCM ciphertext,
 * where the plaintext is a ZIP holding vault.json (every item,
 * embeddings included) plus each item's file under files/. The key
 * comes from the user's backup password via PBKDF2-HmacSHA256
 * (120k rounds). The password is never stored anywhere; a wrong
 * password fails the GCM tag, which is how import knows.
 *
 * Pure JCA + java.util.zip + org.json: the whole round trip is
 * covered by the JVM test suite.
 */
public final class Backup {

    private Backup() {}

    private static final byte[] MAGIC = {'R', 'V', 'B', 'K', '1'};
    private static final int SALT_LEN = 16;
    private static final int IV_LEN = 12;
    private static final int ITERATIONS = 120_000;

    // ---------- ZIP build / parse ----------

    /** Claim a zip entry name that is not already used in this archive. */
    private static String uniqueEntry(java.util.Set<String> used, String name) {
        if (used.add(name)) return name;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        int k = 2;
        String candidate;
        do { candidate = base + "-" + (k++) + ext; } while (!used.add(candidate));
        return candidate;
    }

    public static byte[] buildZip(List<Item> items) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ZipOutputStream zos = new ZipOutputStream(bos);
        java.util.Set<String> usedEntries = new java.util.HashSet<String>();
        JSONObject root = new JSONObject();
        root.put("format", "recallvault-backup");
        root.put("version", 1);
        root.put("created", System.currentTimeMillis());
        JSONArray arr = new JSONArray();
        for (Item it : items) {
            JSONObject o = new JSONObject();
            o.put("title", it.title);
            o.put("type", it.type);
            o.put("category", it.category);
            o.put("body", it.body);
            o.put("extracted", it.extracted);
            o.put("mime", it.mime);
            o.put("created_at", it.createdAt);
            o.put("reminder_at", it.reminderAt);
            o.put("amounts", it.amounts);
            o.put("embedding", it.embedding == null ? "" : it.embedding);
            o.put("embedding_local", it.embeddingLocal == null ? "" : it.embeddingLocal);
            o.put("embedding_image", it.embeddingImage == null ? "" : it.embeddingImage);
            if (it.handledAt > 0) o.put("handled_at", it.handledAt);
            if (it.fileHash != null && !it.fileHash.isEmpty()) o.put("file_hash", it.fileHash);
            // Links travel as the targets' created_at stamps (v0.23.0):
            // ids are device-local, created_at is the portable key.
            java.util.Set<Long> linkIds = Links.parse(it.links);
            if (!linkIds.isEmpty()) {
                java.util.Map<Long, Long> createdById = new java.util.HashMap<Long, Long>();
                for (Item x : items) createdById.put(x.id, x.createdAt);
                JSONArray lk = new JSONArray();
                for (Long lid : linkIds) {
                    Long ts = createdById.get(lid);
                    if (ts != null) lk.put(ts.longValue());
                }
                if (lk.length() > 0) o.put("link_keys", lk);
            }
            String entryName = "";
            if (it.filePath != null && !it.filePath.isEmpty()) {
                File f = new File(it.filePath);
                if (f.exists()) {
                    entryName = uniqueEntry(usedEntries, "files/" + f.getName());
                    zos.putNextEntry(new ZipEntry(entryName));
                    FileInputStream in = new FileInputStream(f);
                    try {
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buf)) > 0) zos.write(buf, 0, n);
                    } finally {
                        in.close();
                    }
                    zos.closeEntry();
                }
            }
            o.put("file", entryName);
            // Extra scan pages ride along as their own entries (v0.20.0);
            // the raw paths are meaningless off this phone, so the
            // entry names are what gets restored.
            if (it.pages != null && !it.pages.isEmpty()) {
                JSONArray pf = new JSONArray();
                for (String pp : it.pages.split("\n")) {
                    if (pp.isEmpty()) continue;
                    File pf2 = new File(pp);
                    if (!pf2.exists()) continue;
                    String pe = uniqueEntry(usedEntries, "files/" + pf2.getName());
                    zos.putNextEntry(new ZipEntry(pe));
                    FileInputStream pin = new FileInputStream(pf2);
                    try {
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = pin.read(buf)) > 0) zos.write(buf, 0, n);
                    } finally {
                        pin.close();
                    }
                    zos.closeEntry();
                    pf.put(pe);
                }
                if (pf.length() > 0) o.put("pages_files", pf);
            }
            arr.put(o);
        }
        root.put("items", arr);
        zos.putNextEntry(new ZipEntry("vault.json"));
        zos.write(root.toString().getBytes("UTF-8"));
        zos.closeEntry();
        zos.close();
        return bos.toByteArray();
    }

    public static final class Restored {
        public final List<Item> items = new ArrayList<Item>();
        /** zip entry name -> bytes, for the items' files. */
        public final Map<String, byte[]> files = new LinkedHashMap<String, byte[]>();
        /** per-item file entry name ("" when none), parallel to items. */
        public final List<String> itemFiles = new ArrayList<String>();
        /** per-item extra page entry names, parallel to items (v0.20.0). */
        public final List<java.util.List<String>> itemPages = new ArrayList<java.util.List<String>>();
        /** per-item link targets as created_at keys, parallel to items (v0.23.0). */
        public final List<java.util.List<Long>> itemLinkKeys = new ArrayList<java.util.List<Long>>();
    }

    public static Restored parseZip(byte[] zip) throws Exception {
        Restored r = new Restored();
        String json = null;
        ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zip));
        ZipEntry e;
        byte[] buf = new byte[64 * 1024];
        while ((e = zin.getNextEntry()) != null) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int n;
            while ((n = zin.read(buf)) > 0) out.write(buf, 0, n);
            if ("vault.json".equals(e.getName())) json = out.toString("UTF-8");
            else r.files.put(e.getName(), out.toByteArray());
        }
        zin.close();
        if (json == null) throw new Exception("Not a Clio backup (vault.json missing)");
        JSONObject root = new JSONObject(json);
        if (!"recallvault-backup".equals(root.optString("format"))) {
            throw new Exception("Not a Clio backup");
        }
        JSONArray arr = root.getJSONArray("items");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            Item it = new Item();
            it.title = o.optString("title", "");
            it.type = o.optString("type", "TEXT");
            it.category = o.optString("category", "Notes");
            it.body = o.optString("body", "");
            it.extracted = o.optString("extracted", "");
            it.mime = o.optString("mime", "");
            it.createdAt = o.optLong("created_at", 0);
            it.reminderAt = o.optLong("reminder_at", -1);
            it.amounts = o.optString("amounts", "");
            it.embedding = o.optString("embedding", "");
            it.embeddingLocal = o.optString("embedding_local", "");
            it.embeddingImage = o.optString("embedding_image", "");
            it.handledAt = o.optLong("handled_at", 0);
            it.fileHash = o.optString("file_hash", "");
            it.filePath = "";
            r.items.add(it);
            r.itemFiles.add(o.optString("file", ""));
            java.util.List<String> pgs = new ArrayList<String>();
            JSONArray pf = o.optJSONArray("pages_files");
            if (pf != null) {
                for (int j = 0; j < pf.length(); j++) pgs.add(pf.optString(j, ""));
            }
            r.itemPages.add(pgs);
            java.util.List<Long> lks = new ArrayList<Long>();
            JSONArray lkArr = o.optJSONArray("link_keys");
            if (lkArr != null) {
                for (int j = 0; j < lkArr.length(); j++) lks.add(lkArr.optLong(j, 0));
            }
            r.itemLinkKeys.add(lks);
        }
        return r;
    }

    // ---------- encryption ----------

    private static SecretKeySpec key(char[] password, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, 256);
        byte[] raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec).getEncoded();
        return new SecretKeySpec(raw, "AES");
    }

    public static byte[] encrypt(byte[] plain, char[] password) throws Exception {
        SecureRandom rnd = new SecureRandom();
        byte[] salt = new byte[SALT_LEN];
        byte[] iv = new byte[IV_LEN];
        rnd.nextBytes(salt);
        rnd.nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key(password, salt), new GCMParameterSpec(128, iv));
        byte[] ct = c.doFinal(plain);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(MAGIC);
        out.write(salt);
        out.write(iv);
        out.write(ct);
        return out.toByteArray();
    }

    public static byte[] decrypt(byte[] blob, char[] password) throws Exception {
        if (blob == null || blob.length < MAGIC.length + SALT_LEN + IV_LEN + 16) {
            throw new Exception("Not a Clio backup file");
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (blob[i] != MAGIC[i]) throw new Exception("Not a RecallVault backup file");
        }
        int off = MAGIC.length;
        byte[] salt = new byte[SALT_LEN];
        System.arraycopy(blob, off, salt, 0, SALT_LEN);
        off += SALT_LEN;
        byte[] iv = new byte[IV_LEN];
        System.arraycopy(blob, off, iv, 0, IV_LEN);
        off += IV_LEN;
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key(password, salt), new GCMParameterSpec(128, iv));
        try {
            return c.doFinal(blob, off, blob.length - off);
        } catch (Exception e) {
            throw new Exception("Wrong password, or the file is damaged");
        }
    }

    public static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }
}
