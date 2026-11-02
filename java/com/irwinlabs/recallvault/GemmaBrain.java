package com.irwinlabs.recallvault;

import android.content.Context;

import com.google.ai.edge.litertlm.Backend;
import com.google.ai.edge.litertlm.Content;
import com.google.ai.edge.litertlm.Contents;
import com.google.ai.edge.litertlm.Conversation;
import com.google.ai.edge.litertlm.ConversationConfig;
import com.google.ai.edge.litertlm.Engine;
import com.google.ai.edge.litertlm.EngineConfig;
import com.google.ai.edge.litertlm.Message;
import com.google.ai.edge.litertlm.SamplerConfig;
import com.google.ai.edge.litertlm.ToolProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * GemmaBrain - Gemma 4 E4B running ON the phone via LiteRT-LM 0.18
 * (the same runtime the embedder already ships). This is the app's
 * second, generative model: where EmbeddingGemma only ranks text,
 * this one writes - summaries, drafted emails, and Ask answers in
 * full sentences, grounded in vault items handed to it as context
 * and through the BrainTools tool providers.
 *
 * Model: litert-community/gemma-4-E4B-it-litert-lm (ungated,
 * Apache 2.0), the generic .litertlm file, 3,659,530,240 bytes,
 * downloaded on demand into app-private storage - never in the APK.
 * Chris picked E4B over E2B on Nov 1, 2026 (smarter over smaller).
 *
 * The Kotlin API is consumed from Java through the generated
 * $default bridges (verified against the 0.18.0 AAR with javap):
 * EngineConfig and ConversationConfig take the mask constructor,
 * Conversation.sendMessage has a blocking overload.
 *
 * All calls are blocking - background threads only. Like
 * LocalEmbed, ANY failure (including linkage Errors from the
 * native stack) marks the model path broken for the process and
 * the feature quietly disables itself: an optional brain must
 * never crash the vault.
 */
public final class GemmaBrain {

    private GemmaBrain() {}

    public static final String MODEL_NAME = "gemma-4-E4B-it.litertlm";
    public static final String MODEL_URL =
        "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/"
        + MODEL_NAME;
    public static final long MODEL_BYTES = 3659530240L;
    public static final long MODEL_MIN_BYTES = 3400000000L;

    private static Engine engine;
    private static String enginePath;
    private static boolean gpuBroken;
    private static String brokenPath;

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
        byte[] buf = new byte[256 * 1024];
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
        synchronized (GemmaBrain.class) {
            closeEngineLocked();
            brokenPath = null;
            gpuBroken = false;
        }
        return dest;
    }

    public static void deleteModel(Context ctx) {
        synchronized (GemmaBrain.class) {
            closeEngineLocked();
            brokenPath = null;
        }
        File f = modelFile(ctx);
        if (f.exists()) f.delete();
    }

    private static void closeEngineLocked() {
        if (engine != null) {
            try { engine.close(); } catch (Throwable t) { /* closing is best-effort */ }
            engine = null;
            enginePath = null;
        }
    }

    private static Engine getEngine(Context ctx) throws Exception {
        synchronized (GemmaBrain.class) {
            String path = modelFile(ctx).getAbsolutePath();
            if (engine != null && path.equals(enginePath)) return engine;
            if (path.equals(brokenPath)) {
                throw new Exception("brain unavailable (runtime failed to load)");
            }
            // The native engine refuses a cache dir that does not
            // exist yet ("Cache directory does not exist or is not
            // writable" - device-verified Nov 1, 2026).
            File cacheFile = new File(ctx.getCacheDir(), "litertlm");
            if (!cacheFile.exists()) cacheFile.mkdirs();
            String cacheDir = cacheFile.getAbsolutePath();
            try {
                engine = buildEngine(path, cacheDir, gpuBroken ? new Backend.CPU() : new Backend.GPU());
            } catch (Throwable t) {
                android.util.Log.e("RecallVault", "GemmaBrain: engine failed (" +
                    (gpuBroken ? "CPU" : "GPU") + ")", t);
                if (!gpuBroken) {
                    gpuBroken = true;
                    try {
                        engine = buildEngine(path, cacheDir, new Backend.CPU());
                    } catch (Throwable t2) {
                        android.util.Log.e("RecallVault", "GemmaBrain: CPU fallback failed", t2);
                        brokenPath = path;
                        engine = null;
                        throw new Exception("brain failed to start: " + t2);
                    }
                } else {
                    brokenPath = path;
                    engine = null;
                    throw new Exception("brain failed to start: " + t);
                }
            }
            enginePath = path;
            return engine;
        }
    }

    private static Engine buildEngine(String path, String cacheDir, Backend backend) {
        // Full constructor (the $default mask bridge is synthetic and
        // invisible to javac - build-verified Nov 1, 2026): vision on
        // the same backend, audio on CPU, 8192-token window, fp16
        // activations, cache in the app cache dir.
        EngineConfig config = new EngineConfig(path, backend, backend,
            new Backend.CPU(), 8192, null, cacheDir,
            com.google.ai.edge.litertlm.ActivationDataType.FLOAT16);
        Engine e = new Engine(config);
        e.initialize();
        return e;
    }

    /** One-shot generation without tools. Blocking. */
    public static String generate(Context ctx, String system, String prompt) throws Exception {
        return generate(ctx, system, prompt, null);
    }

    /**
     * One-shot generation. A fresh conversation per call keeps tasks
     * independent; tools (when given) let the model reach back into
     * the vault mid-answer. Blocking - background thread only.
     */
    public static String generate(Context ctx, String system, String prompt,
                                  List<ToolProvider> tools) throws Exception {
        if (!isModelPresent(ctx)) throw new Exception("brain model not downloaded");
        Engine e = getEngine(ctx.getApplicationContext());
        Conversation conv = null;
        try {
            List<ToolProvider> toolList = tools == null
                ? Collections.<ToolProvider>emptyList() : tools;
            // Full 14-arg constructor (same synthetic-bridge lesson
            // as EngineConfig): empty collections where Kotlin
            // defaults to empty, thinking off, no LoRA/template.
            ConversationConfig config = new ConversationConfig(
                Contents.Companion.of(system == null ? "" : system),
                Collections.<Message>emptyList(),
                toolList,
                new SamplerConfig(40, 0.95, 0.7, 1234),
                !toolList.isEmpty(),
                Collections.<com.google.ai.edge.litertlm.Channel>emptyList(),
                Collections.<String, Object>emptyMap(),
                null,
                false,
                null,
                new com.google.ai.edge.litertlm.ThinkingConfig(false, 0),
                false,
                null,
                null);
            conv = e.createConversation(config);
            Message reply = conv.sendMessage(prompt,
                Collections.<String, Object>emptyMap(),
                null, null, null, null, null, null);
            StringBuilder sb = new StringBuilder();
            if (reply != null && reply.getContents() != null) {
                for (Content c : reply.getContents().getContents()) {
                    if (c instanceof Content.Text) {
                        sb.append(((Content.Text) c).getText());
                    }
                }
            }
            return sb.toString().trim();
        } catch (Throwable t) {
            android.util.Log.e("RecallVault", "GemmaBrain: generate failed", t);
            throw new Exception("brain generation failed: " + t);
        } finally {
            if (conv != null) {
                try { conv.close(); } catch (Throwable t) { /* best-effort */ }
            }
        }
    }

    // ------------------------------------------------------------------
    // Prompt assembly - pure functions, JVM-tested.
    // ------------------------------------------------------------------

    /** Grounding block for Ask: the retrieved memories, numbered. */
    public static String groundingBlock(List<Item> items, int maxCharsEach) {
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (Item it : items) {
            sb.append("[").append(i++).append("] ").append(it.title);
            if (it.createdAt > 0) {
                sb.append(" (").append(new java.text.SimpleDateFormat("EEE, MMM d, yyyy",
                    java.util.Locale.US).format(new java.util.Date(it.createdAt))).append(")");
            }
            sb.append("\n");
            // Due date and amount ride along when present: without
            // them the model mistakes the saved date for the due
            // date (device-verified Nov 1, 2026).
            if (it.reminderAt > 0) {
                sb.append("Due: ").append(new java.text.SimpleDateFormat("EEE, MMM d, yyyy",
                    java.util.Locale.US).format(new java.util.Date(it.reminderAt))).append(". ");
            }
            if (it.amounts != null && !it.amounts.isEmpty()) {
                sb.append("Amount: ").append(it.amounts).append(". ");
            }
            if (it.reminderAt > 0 || (it.amounts != null && !it.amounts.isEmpty())) sb.append("\n");
            String text = it.body != null && !it.body.isEmpty() ? it.body
                : (it.extracted != null ? it.extracted : "");
            if (text.length() > maxCharsEach) text = text.substring(0, maxCharsEach);
            sb.append(text).append("\n\n");
        }
        return sb.toString().trim();
    }
}
