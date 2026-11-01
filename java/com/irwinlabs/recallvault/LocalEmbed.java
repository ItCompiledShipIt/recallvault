package com.irwinlabs.recallvault;

import android.content.Context;

import com.google.mediapipe.tasks.components.containers.Embedding;
import com.google.mediapipe.tasks.components.containers.EmbeddingResult;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.core.Delegate;
import com.google.mediapipe.tasks.retrieval.universalembedder.UniversalEmbedder;
import com.google.mediapipe.tasks.retrieval.universalembedder.UniversalEmbedderOptions;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * LocalEmbed - EmbeddingGemma 2 running ON the phone, via MediaPipe's
 * Universal Embedder task (tasks-retrieval) over LiteRT-LM.
 *
 * Model: litert-community/embeddinggemma-2-text-vision-440m-litert-lm
 * (ungated Hugging Face repo), the generic .litertlm file, 388 MB,
 * downloaded on demand into app-private storage - it is not in the APK.
 * Output is 768-dim, L2-normalized.
 *
 * Why the 440M text+vision file and not the 165 MB text-only one:
 * the LiteRT-LM EmbeddingEngine behind UniversalEmbedder requires the
 * vision-encoder section at init ("tf_lite_vision_encoder not found in
 * the model" - device-verified Oct 31, 2026). The 440M file carries the
 * same 270M text backbone and the same unified 768-dim space, plus a
 * vision tower this app does not use yet.
 *
 * These vectors live in their own DB column: the on-device build is
 * int4-quantized while bigpc runs the Q8_0 GGUF, so the two spaces are
 * close cousins, not twins - comparisons stay within one space.
 *
 * All calls are blocking - use from background threads only.
 */
public final class LocalEmbed {

    private LocalEmbed() {}

    public static final String MODEL_NAME = "embeddinggemma-2-text-vision-440m.litertlm";
    public static final String MODEL_URL =
        "https://huggingface.co/litert-community/embeddinggemma-2-text-vision-440m-litert-lm/resolve/main/"
        + MODEL_NAME;
    public static final long MODEL_MIN_BYTES = 300L * 1024 * 1024;

    private static UniversalEmbedder embedder;
    private static String embedderPath;

    public static File modelFile(Context ctx) {
        return new File(ctx.getFilesDir(), MODEL_NAME);
    }

    public static boolean isModelPresent(Context ctx) {
        File f = modelFile(ctx);
        return f.isFile() && f.length() >= MODEL_MIN_BYTES;
    }

    public interface Progress {
        void onProgress(int percent);
    }

    /** Download the model into app storage. Blocking; returns the file. */
    public static File download(Context ctx, Progress progress) throws Exception {
        File dest = modelFile(ctx);
        File tmp = new File(ctx.getFilesDir(), MODEL_NAME + ".tmp");
        HttpURLConnection conn = (HttpURLConnection) new URL(MODEL_URL).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        conn.connect();
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
        long total = conn.getContentLengthLong();
        InputStream in = conn.getInputStream();
        FileOutputStream out = new FileOutputStream(tmp);
        byte[] buf = new byte[128 * 1024];
        long done = 0;
        int lastPct = -1;
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            done += n;
            if (total > 0 && progress != null) {
                int pct = (int) (done * 100 / total);
                if (pct != lastPct) { lastPct = pct; progress.onProgress(pct); }
            }
        }
        out.flush();
        out.close();
        in.close();
        if (tmp.length() < MODEL_MIN_BYTES) {
            tmp.delete();
            throw new Exception("download too small (" + tmp.length() + " bytes)");
        }
        if (dest.exists()) dest.delete();
        if (!tmp.renameTo(dest)) throw new Exception("could not move model into place");
        synchronized (LocalEmbed.class) {
            if (embedder != null) { try { embedder.close(); } catch (Exception e) {} embedder = null; }
            brokenPath = null; // fresh model file: retry the embedder
        }
        return dest;
    }

    public static void deleteModel(Context ctx) {
        synchronized (LocalEmbed.class) {
            if (embedder != null) { try { embedder.close(); } catch (Exception e) {} embedder = null; }
            brokenPath = null;
        }
        File f = modelFile(ctx);
        if (f.exists()) f.delete();
    }

    /** Set when embedder creation fails hard (e.g. a linkage Error from
     *  the native/runtime stack). An optional offline feature must
     *  NEVER crash the app: once broken, stay out of the way until the
     *  model file changes. v0.3.0 shipped without this guard and a
     *  NoClassDefFoundError from the LiteRT-LM stack crash-looped the
     *  app on start - found by device verification, fixed in 0.3.1. */
    private static String brokenPath = null;

    private static UniversalEmbedder getEmbedder(Context ctx) throws Exception {
        synchronized (LocalEmbed.class) {
            String path = modelFile(ctx).getAbsolutePath();
            if (embedder != null && path.equals(embedderPath)) return embedder;
            if (path.equals(brokenPath)) {
                throw new Exception("on-device embedder unavailable (runtime failed to load)");
            }
            try {
                UniversalEmbedderOptions options = UniversalEmbedderOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath(path).build())
                    .setTextDelegate(Delegate.CPU)
                    .setVisionDelegate(Delegate.CPU)
                    .setL2Normalize(true)
                    .build();
                embedder = UniversalEmbedder.createFromOptions(ctx.getApplicationContext(), options);
            } catch (Throwable t) {
                android.util.Log.e("RecallVault", "LocalEmbed: embedder failed to start", t);
                brokenPath = path;
                embedder = null;
                throw new Exception("on-device embedder failed to start: " + t);
            }
            embedderPath = path;
            return embedder;
        }
    }

    /** Embed one text on-device. Blocking - background thread only. */
    public static float[] embedSync(Context ctx, String text) throws Exception {
        if (!isModelPresent(ctx)) throw new Exception("on-device model not downloaded");
        String t = text == null ? "" : text;
        if (t.length() > 4000) t = t.substring(0, 4000);
        EmbeddingResult result = getEmbedder(ctx).embedText(t);
        if (result == null || result.embeddings() == null || result.embeddings().isEmpty()) {
            throw new Exception("empty embedding result");
        }
        Embedding e = result.embeddings().get(0);
        float[] v = e.floatEmbedding();
        if (v == null || v.length == 0) throw new Exception("no float embedding in result");
        return v;
    }

    /** Embed every item missing a local vector. Blocking - background thread only. */
    public static void embedPendingLocal(Context ctx, Db db) {
        if (!isModelPresent(ctx)) return;
        for (Item it : db.needingLocalEmbedding()) {
            try {
                float[] v = embedSync(ctx, it.searchableText());
                db.updateEmbeddingLocal(it.id, Embed.encode(v));
            } catch (Throwable t) {
                android.util.Log.e("RecallVault", "LocalEmbed: embed failed for item " + it.id, t);
                return; // ANY failure (incl. linkage Errors): stop, stay silent
            }
        }
        embedPendingImages(ctx, db);
    }

    /**
     * Embed one image through the model's vision tower. Same unified
     * 768-dim space as text: a text question can rank this vector.
     * Blocking - background thread only.
     */
    public static float[] embedImageSync(Context ctx, android.graphics.Bitmap bmp) throws Exception {
        if (!isModelPresent(ctx)) throw new Exception("on-device model not downloaded");
        if (bmp == null) throw new Exception("no image");
        com.google.mediapipe.framework.image.MPImage img =
            new com.google.mediapipe.framework.image.BitmapImageBuilder(bmp).build();
        EmbeddingResult result = getEmbedder(ctx).embedImage(img);
        if (result == null || result.embeddings() == null || result.embeddings().isEmpty()) {
            throw new Exception("empty image embedding result");
        }
        Embedding e = result.embeddings().get(0);
        float[] v = e.floatEmbedding();
        if (v == null || v.length == 0) throw new Exception("no float image embedding in result");
        return v;
    }

    /** Embed every photo/scan missing an image vector. Blocking - background thread only. */
    public static void embedPendingImages(Context ctx, Db db) {
        if (!isModelPresent(ctx)) return;
        for (Item it : db.needingImageEmbedding()) {
            try {
                java.io.File f = new java.io.File(it.filePath);
                if (!f.exists()) continue;
                android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeFile(it.filePath, bounds);
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) continue; // not a decodable image
                int sample = 1;
                while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2;
                android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
                opts.inSampleSize = sample;
                android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeFile(it.filePath, opts);
                if (bmp == null) continue;
                try {
                    float[] v = embedImageSync(ctx, bmp);
                    db.updateEmbeddingImage(it.id, Embed.encode(v));
                } finally {
                    bmp.recycle();
                }
            } catch (Throwable t) {
                android.util.Log.e("RecallVault", "LocalEmbed: image embed failed for item " + it.id, t);
                return; // same rule as text: any failure stops the pass quietly
            }
        }
    }
}
