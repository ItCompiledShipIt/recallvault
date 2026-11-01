package com.irwinlabs.recallvault;

import android.content.Context;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Embedding plumbing shared by Ask and the embedders.
 *
 * v0.4.0: the bigpc embedding server is GONE from the app - semantic
 * search runs entirely on-device (LocalEmbed, EmbeddingGemma 2 via
 * MediaPipe). What remains here is the vector codec (floats packed
 * into the TEXT column both embedding spaces share), the cosine, and
 * the "keep pending items embedded" entry point the activities call.
 * The server-vector column is still in the schema (harmless, unused)
 * so existing databases upgrade without a migration.
 */
public final class Embed {
    /** App SharedPreferences file name (reminders + dedupe live there). */
    public static final String PREFS = "recallvault";

    private Embed() {}

    /** float[] -> base64 of big-endian IEEE754 bytes. */
    public static String encode(float[] v) {
        byte[] b = new byte[v.length * 4];
        for (int i = 0; i < v.length; i++) {
            int bits = Float.floatToIntBits(v[i]);
            b[i * 4] = (byte) (bits >>> 24);
            b[i * 4 + 1] = (byte) (bits >>> 16);
            b[i * 4 + 2] = (byte) (bits >>> 8);
            b[i * 4 + 3] = (byte) bits;
        }
        return Base64.getEncoder().encodeToString(b);
    }

    /** Inverse of encode; null on garbage. */
    public static float[] decode(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            byte[] b = Base64.getDecoder().decode(s.getBytes(StandardCharsets.US_ASCII));
            if (b.length % 4 != 0 || b.length == 0) return null;
            float[] v = new float[b.length / 4];
            for (int i = 0; i < v.length; i++) {
                int bits = ((b[i * 4] & 0xFF) << 24) | ((b[i * 4 + 1] & 0xFF) << 16)
                         | ((b[i * 4 + 2] & 0xFF) << 8) | (b[i * 4 + 3] & 0xFF);
                v[i] = Float.intBitsToFloat(bits);
            }
            return v;
        } catch (Exception e) {
            return null;
        }
    }

    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) return 0;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) return 0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /**
     * Background pass used by every save path: embed whatever is
     * pending with the on-device model. No-op (no network, no error)
     * when the model is not downloaded.
     */
    public static void embedPending(final Context ctx, final Db db) {
        LocalEmbed.embedPendingLocal(ctx, db);
    }
}
