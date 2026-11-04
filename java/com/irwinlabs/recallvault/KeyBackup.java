package com.irwinlabs.recallvault;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Recovery-code backups, format "RVBK3" (v0.81.0, one-tap
 * backup). The secret is 20 random bytes generated on the
 * device; the AES-256 key is their SHA-256, and the
 * recovery code is their base32 (32 characters, shown in
 * groups of four). No salt, no PBKDF, no password to
 * invent: a backup made this way restores on any device
 * where the code is typed, and the desktop speaks the
 * identical format (one shared test vector pins both
 * implementations - bytes 0..19 must yield the code
 * AAAQ-EAYE-AUDA-OCAJ-BIFQ-YDIO-B4IB-CEQT on each).
 *
 * File layout: magic(5) + IV(12) + AES-256-GCM ciphertext.
 * The password format (RVBK1) and the device-key automatic
 * format (RVBK2) are unchanged; this is the portable,
 * no-password option beside them.
 */
public final class KeyBackup {

    public static final byte[] MAGIC3 = {'R', 'V', 'B', 'K', '3'};
    public static final int ENTROPY_LEN = 20;
    private static final int IV_LEN = 12;
    private static final char[] B32 =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

    private KeyBackup() {}

    public static boolean isKeyBackup(byte[] blob) {
        if (blob == null || blob.length < MAGIC3.length) return false;
        for (int i = 0; i < MAGIC3.length; i++) {
            if (blob[i] != MAGIC3[i]) return false;
        }
        return true;
    }

    public static byte[] newEntropy() {
        byte[] e = new byte[ENTROPY_LEN];
        new SecureRandom().nextBytes(e);
        return e;
    }

    static byte[] key(byte[] entropy) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(entropy);
    }

    public static byte[] encrypt(byte[] plain, byte[] entropy) throws Exception {
        byte[] iv = new byte[IV_LEN];
        new SecureRandom().nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(entropy), "AES"),
               new GCMParameterSpec(128, iv));
        byte[] ct = c.doFinal(plain);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(MAGIC3);
        out.write(iv);
        out.write(ct);
        return out.toByteArray();
    }

    public static byte[] decrypt(byte[] blob, byte[] entropy) throws Exception {
        if (!isKeyBackup(blob) || blob.length < MAGIC3.length + IV_LEN + 16) {
            throw new Exception("Not a recovery-code backup file");
        }
        byte[] iv = new byte[IV_LEN];
        System.arraycopy(blob, MAGIC3.length, iv, 0, IV_LEN);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(entropy), "AES"),
               new GCMParameterSpec(128, iv));
        try {
            return c.doFinal(blob, MAGIC3.length + IV_LEN,
                             blob.length - MAGIC3.length - IV_LEN);
        } catch (Exception e) {
            throw new Exception("That recovery code did not open this backup");
        }
    }

    /** Base32 (RFC 4648, no padding), grouped in fours. */
    public static String codeFromEntropy(byte[] entropy) {
        StringBuilder raw = new StringBuilder(32);
        int buffer = 0, bits = 0;
        for (byte b : entropy) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                raw.append(B32[(buffer >> bits) & 0x1F]);
            }
        }
        if (bits > 0) raw.append(B32[(buffer << (5 - bits)) & 0x1F]);
        StringBuilder grouped = new StringBuilder(39);
        for (int i = 0; i < raw.length(); i++) {
            if (i > 0 && i % 4 == 0) grouped.append('-');
            grouped.append(raw.charAt(i));
        }
        return grouped.toString();
    }

    /** Tolerant decode: ignores case, dashes and spaces.
     *  Returns null for anything that is not a full code. */
    public static byte[] entropyFromCode(String code) {
        if (code == null) return null;
        String s = code.replaceAll("[^A-Za-z0-9]", "")
            .toUpperCase(Locale.US);
        if (s.length() != 32) return null;
        byte[] out = new byte[ENTROPY_LEN];
        int buffer = 0, bits = 0, idx = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            int v;
            if (ch >= 'A' && ch <= 'Z') v = ch - 'A';
            else if (ch >= '2' && ch <= '7') v = ch - '2' + 26;
            else return null;
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                bits -= 8;
                if (idx >= ENTROPY_LEN) return null;
                out[idx++] = (byte) ((buffer >> bits) & 0xFF);
            }
        }
        return idx == ENTROPY_LEN ? out : null;
    }
}
