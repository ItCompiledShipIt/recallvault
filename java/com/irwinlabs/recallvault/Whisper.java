package com.irwinlabs.recallvault;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * On-device speech-to-text: whisper.cpp (v1.9.5) behind a thin JNI
 * bridge (librecallwhisper.so), running the ggml base.en model
 * (q5_1, ~57 MB, downloaded in-app like the embedding model).
 *
 * Replaces the v0.3 bigpc faster-whisper service - from v0.4.0 the
 * audio never leaves the phone. v1.9.5 + this exact model were
 * host-verified against the same test sentence the server used.
 *
 * Same never-crash discipline as LocalEmbed: the library load and
 * every native call are guarded; any failure surfaces as an ordinary
 * Exception the caller can show, never a dead app.
 */
public final class Whisper {

    /**
     * Model sizes the user can pick in Settings (v0.8.0): the speed /
     * accuracy dial. All are English ggml q5_1 builds from the
     * whisper.cpp Hugging Face repo. Balanced is the v0.4 default.
     */
    public static final String[] VARIANT_KEYS = {"tiny", "base", "small"};
    public static final String[] VARIANT_LABELS = {"Fast", "Balanced", "Sharpest"};
    private static final String[] VARIANT_FILES = {
        "ggml-tiny.en-q5_1.bin", "ggml-base.en-q5_1.bin", "ggml-small.en-q5_1.bin"};
    private static final long[] VARIANT_MIN_BYTES = {25_000_000L, 50_000_000L, 150_000_000L};
    private static final int[] VARIANT_APPROX_MB = {31, 57, 173};
    private static final String HF_BASE = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/";
    private static final String PREF_KEY = "whisper_model";

    private static volatile boolean libLoaded = false;
    private static volatile boolean libBroken = false;
    private static long ctxPtr = 0;

    private Whisper() {}

    // JNI (native/jni.cpp)
    private static native long initContext(String modelPath);
    private static native String transcribe(long ctx, float[] samples, int nThreads);
    private static native void freeContext(long ctx);

    public interface Progress { void onProgress(int percent); }

    public static int variantIndex(Context ctx) {
        String key = ctx.getSharedPreferences(Embed.PREFS, Context.MODE_PRIVATE)
            .getString(PREF_KEY, "base");
        for (int i = 0; i < VARIANT_KEYS.length; i++) {
            if (VARIANT_KEYS[i].equals(key)) return i;
        }
        return 1;
    }

    public static String variantLabel(Context ctx) {
        return VARIANT_LABELS[variantIndex(ctx)];
    }

    public static int variantApproxMb(int index) {
        return VARIANT_APPROX_MB[index];
    }

    /** Switch the active model. The loaded native context is dropped;
     *  downloaded model files are kept (Remove deletes one). */
    public static void setVariant(Context ctx, int index) {
        ctx.getSharedPreferences(Embed.PREFS, Context.MODE_PRIVATE)
            .edit().putString(PREF_KEY, VARIANT_KEYS[index]).apply();
        synchronized (Whisper.class) {
            if (ctxPtr != 0 && libLoaded && !libBroken) {
                try { freeContext(ctxPtr); } catch (Throwable ignored) {}
            }
            ctxPtr = 0;
        }
    }

    private static File fileFor(Context ctx, int index) {
        return new File(ctx.getFilesDir(), VARIANT_FILES[index]);
    }

    private static File modelFile(Context ctx) {
        return fileFor(ctx, variantIndex(ctx));
    }

    public static boolean isModelPresent(Context ctx) {
        int i = variantIndex(ctx);
        File f = fileFor(ctx, i);
        return f.exists() && f.length() >= VARIANT_MIN_BYTES[i];
    }

    public static boolean isVariantPresent(Context ctx, int index) {
        File f = fileFor(ctx, index);
        return f.exists() && f.length() >= VARIANT_MIN_BYTES[index];
    }

    public static long modelBytes(Context ctx) {
        File f = modelFile(ctx);
        return f.exists() ? f.length() : 0;
    }

    public static void deleteModel(Context ctx) {
        synchronized (Whisper.class) {
            if (ctxPtr != 0 && libLoaded && !libBroken) {
                try { freeContext(ctxPtr); } catch (Throwable ignored) {}
            }
            ctxPtr = 0;
        }
        int i = variantIndex(ctx);
        File f = fileFor(ctx, i);
        if (f.exists()) f.delete();
        File tmp = new File(ctx.getFilesDir(), VARIANT_FILES[i] + ".tmp");
        if (tmp.exists()) tmp.delete();
    }

    /** Blocking download of the SELECTED variant - call off the UI thread. */
    public static void downloadModel(Context ctx, Progress progress) throws Exception {
        final int i = variantIndex(ctx);
        File tmp = new File(ctx.getFilesDir(), VARIANT_FILES[i] + ".tmp");
        HttpURLConnection c = (HttpURLConnection) new URL(HF_BASE + VARIANT_FILES[i]).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(true);
        try {
            int code = c.getResponseCode();
            if (code / 100 != 2) throw new Exception("Download failed (HTTP " + code + ")");
            long total = c.getContentLengthLong();
            try (InputStream in = c.getInputStream();
                 FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                long done = 0;
                int n;
                while ((n = in.read(buf)) >= 0) {
                    out.write(buf, 0, n);
                    done += n;
                    if (progress != null && total > 0) {
                        progress.onProgress((int) (done * 100 / total));
                    }
                }
            }
        } finally {
            c.disconnect();
        }
        if (tmp.length() < VARIANT_MIN_BYTES[i]) {
            tmp.delete();
            throw new Exception("Download incomplete (" + tmp.length() + " bytes)");
        }
        synchronized (Whisper.class) {
            if (ctxPtr != 0 && libLoaded && !libBroken) {
                try { freeContext(ctxPtr); } catch (Throwable ignored) {}
            }
            ctxPtr = 0;
        }
        File dest = modelFile(ctx);
        if (dest.exists()) dest.delete();
        if (!tmp.renameTo(dest)) throw new Exception("Could not install the model file");
    }

    private static void ensureLib() throws Exception {
        if (libBroken) throw new Exception("On-device transcription unavailable on this phone");
        if (!libLoaded) {
            try {
                System.loadLibrary("recallwhisper");
                libLoaded = true;
            } catch (Throwable t) {
                libBroken = true;
                Log.e("RecallVault", "librecallwhisper load failed", t);
                throw new Exception("On-device transcription unavailable on this phone");
            }
        }
    }

    private static long ensureContext(Context ctx) throws Exception {
        ensureLib();
        synchronized (Whisper.class) {
            if (ctxPtr == 0) {
                long p;
                try {
                    p = initContext(modelFile(ctx).getAbsolutePath());
                } catch (Throwable t) {
                    libBroken = true;
                    Log.e("RecallVault", "whisper init failed", t);
                    throw new Exception("Transcription model failed to load");
                }
                if (p == 0) throw new Exception("Transcription model failed to load");
                ctxPtr = p;
            }
            return ctxPtr;
        }
    }

    /** Blocking: decode + transcribe a recording. Call off the UI thread. */
    public static String transcribeFile(Context ctx, File audio) throws Exception {
        if (!isModelPresent(ctx)) {
            throw new Exception("Transcription model not downloaded yet - get it in Settings");
        }
        float[] samples = AudioDecode.decodeTo16kMono(audio);
        if (samples.length < AudioDecode.TARGET_RATE / 4) {
            throw new Exception("Recording too short to transcribe");
        }
        long p = ensureContext(ctx);
        int threads = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors() - 1));
        String text;
        try {
            synchronized (Whisper.class) {
                text = transcribe(p, samples, threads);
            }
        } catch (Throwable t) {
            Log.e("RecallVault", "whisper transcribe failed", t);
            throw new Exception("Transcription failed on this phone");
        }
        if (text == null) throw new Exception("Transcription failed on this phone");
        text = text.trim();
        if (text.isEmpty()) throw new Exception("No speech found in the recording");
        return text;
    }

    /**
     * Transcribe one in-memory segment (16 kHz mono floats) for live
     * meeting captions. Silence is a normal outcome here, not an
     * error: returns "" when nothing was said. Still throws when the
     * model is missing or the native stack is broken, so the caller
     * can turn captions off with a plain note.
     */
    public static String transcribeSegment(Context ctx, float[] samples) throws Exception {
        if (!isModelPresent(ctx)) {
            throw new Exception("Transcription model not downloaded yet - get it in Settings");
        }
        if (samples == null || samples.length < AudioDecode.TARGET_RATE) return "";
        long p = ensureContext(ctx);
        int threads = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors() - 1));
        String text;
        try {
            synchronized (Whisper.class) {
                text = transcribe(p, samples, threads);
            }
        } catch (Throwable t) {
            Log.e("RecallVault", "whisper segment failed", t);
            throw new Exception("Transcription failed on this phone");
        }
        return text == null ? "" : text.trim();
    }
}
