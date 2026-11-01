package com.irwinlabs.recallvault;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * VaultCrypto - encrypts the vault's text at rest (v0.16.0).
 *
 * Title, body, extracted text and amounts are stored as
 * "enc1:" + Base64(IV || AES-256-GCM ciphertext) under a key that
 * lives in the Android Keystore and never leaves the phone.
 * Anything without the prefix is legacy plaintext and reads as-is,
 * so the vault works in every mixed state during migration.
 * Media files are NOT encrypted - they sit in Android's app-private
 * storage behind the phone's own lock; the Settings copy says so.
 */
public final class VaultCrypto {

    private VaultCrypto() {}

    static final String PREFIX = "enc1:";
    private static final String ALIAS = "rv_vault_text_key";
    private static SecretKey cached;

    public static boolean isEncrypted(String s) {
        return s != null && s.startsWith(PREFIX);
    }

    private static SecretKey key() throws Exception {
        if (cached != null) return cached;
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(ALIAS)) {
            cached = (SecretKey) ks.getKey(ALIAS, null);
            return cached;
        }
        KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        gen.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build());
        cached = gen.generateKey();
        return cached;
    }

    // Pure core (JVM-tested): the Android entry points delegate here.

    public static String encryptWith(SecretKey k, String plain) throws Exception {
        if (plain == null || plain.isEmpty() || isEncrypted(plain)) return plain == null ? "" : plain;
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, k);
        byte[] iv = c.getIV();
        byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        byte[] blob = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, blob, 0, iv.length);
        System.arraycopy(ct, 0, blob, iv.length, ct.length);
        return PREFIX + Base64.getEncoder().encodeToString(blob);
    }

    public static String decryptWith(SecretKey k, String stored) throws Exception {
        if (!isEncrypted(stored)) return stored == null ? "" : stored;
        byte[] blob = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
        byte[] iv = new byte[12];
        System.arraycopy(blob, 0, iv, 0, 12);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(128, iv));
        return new String(c.doFinal(blob, 12, blob.length - 12), StandardCharsets.UTF_8);
    }

    /** Encrypt for storage; on any failure returns the input (never loses data). */
    public static String enc(String plain) {
        if (plain == null || plain.isEmpty() || isEncrypted(plain)) return plain == null ? "" : plain;
        try {
            return encryptWith(key(), plain);
        } catch (Exception e) {
            return plain;
        }
    }

    /** Decrypt a stored value; unmarked values pass through untouched. */
    public static String dec(String stored) {
        if (!isEncrypted(stored)) return stored == null ? "" : stored;
        try {
            return decryptWith(key(), stored);
        } catch (Exception e) {
            return stored; // show the raw value rather than nothing
        }
    }
}
