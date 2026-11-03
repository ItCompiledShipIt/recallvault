package com.irwinlabs.recallvault;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Optional external AI answers (v0.77.0): off by default. When
 * the user enables it and supplies their own API key for an
 * OpenAI-compatible service, Ask answers are written by that
 * service instead of the on-device model - the question plus
 * the matched memories (the same grounding context the local
 * model gets) are sent to the service. Retrieval, learning
 * and chat compression always stay on the phone. The key is
 * stored sealed (VaultCrypto) alongside the mail credentials.
 * Mirrors the desktop app's provider support.
 */
public final class AiProvider {

    static final String P_BASE = "ai_base";
    static final String P_MODEL = "ai_model";
    static final String P_ON = "ai_on";
    static final String P_KEY = "ai_key";

    private AiProvider() { }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(Embed.PREFS, Context.MODE_PRIVATE);
    }

    public static boolean enabled(Context ctx) {
        return prefs(ctx).getBoolean(P_ON, false);
    }

    public static String base(Context ctx) {
        return prefs(ctx).getString(P_BASE, "");
    }

    public static String model(Context ctx) {
        return prefs(ctx).getString(P_MODEL, "");
    }

    public static boolean configured(Context ctx) {
        return !base(ctx).trim().isEmpty() && !model(ctx).trim().isEmpty()
            && !prefs(ctx).getString(P_KEY, "").isEmpty();
    }

    public static void save(Context ctx, String baseUrl, String modelName,
                            String keyOrNull, boolean on) {
        SharedPreferences.Editor e = prefs(ctx).edit()
            .putString(P_BASE, baseUrl == null ? "" : baseUrl.trim())
            .putString(P_MODEL, modelName == null ? "" : modelName.trim())
            .putBoolean(P_ON, on);
        if (keyOrNull != null && !keyOrNull.trim().isEmpty()) {
            e.putString(P_KEY, VaultCrypto.enc(keyOrNull.trim()));
        }
        e.apply();
    }

    /** {baseUrl, model, key} when ready to answer, else null. */
    public static String[] config(Context ctx) {
        if (!enabled(ctx) || !configured(ctx)) return null;
        String key = VaultCrypto.dec(prefs(ctx).getString(P_KEY, ""));
        if (key == null || key.isEmpty()) return null;
        return new String[]{base(ctx).trim(), model(ctx).trim(), key};
    }

    /** Host for the provenance line ("api.openai.com"). */
    public static String host(String baseUrl) {
        try {
            String h = new URI(baseUrl).getHost();
            if (h != null && !h.isEmpty()) return h;
        } catch (Exception ignored) { }
        return baseUrl;
    }

    public static String buildBody(String model, String system, String user)
            throws Exception {
        JSONArray msgs = new JSONArray();
        msgs.put(new JSONObject()
            .put("role", "system").put("content", system));
        msgs.put(new JSONObject()
            .put("role", "user").put("content", user));
        return new JSONObject()
            .put("model", model)
            .put("temperature", 0.1)
            .put("messages", msgs)
            .toString();
    }

    public static String parseAnswer(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        if (root.has("error")) {
            Object err = root.get("error");
            String msg = err instanceof JSONObject
                ? ((JSONObject) err).optString("message", err.toString())
                : String.valueOf(err);
            throw new Exception("provider said: " + msg);
        }
        JSONArray choices = root.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            throw new Exception("provider returned no answer");
        }
        JSONObject msg = choices.getJSONObject(0).optJSONObject("message");
        String text = msg == null ? "" : msg.optString("content", "");
        if (text.trim().isEmpty()) {
            throw new Exception("provider returned an empty answer");
        }
        return text.trim();
    }

    /** One blocking chat completion. Throws on any failure. */
    public static String chat(Context ctx, String system, String user)
            throws Exception {
        String[] cfg = config(ctx);
        if (cfg == null) throw new Exception("provider not configured");
        String url = cfg[0];
        if (!url.endsWith("/")) url += "/";
        url += "chat/completions";
        HttpURLConnection conn =
            (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(120000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + cfg[2]);
        byte[] body = buildBody(cfg[1], system, user)
            .getBytes(StandardCharsets.UTF_8);
        OutputStream os = conn.getOutputStream();
        os.write(body);
        os.close();
        int code = conn.getResponseCode();
        InputStream in = code >= 200 && code < 300
            ? conn.getInputStream() : conn.getErrorStream();
        StringBuilder sb = new StringBuilder();
        if (in != null) {
            BufferedReader br = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
        }
        if (code < 200 || code >= 300) {
            throw new Exception("provider HTTP " + code
                + (sb.length() > 0 ? ": " + sb.substring(
                    0, Math.min(160, sb.length())) : ""));
        }
        return parseAnswer(sb.toString());
    }
}
