package com.irwinlabs.recallvault;

import android.app.NotificationManager;
import android.content.Context;

import androidx.core.app.NotificationCompat;

import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;

import java.nio.charset.StandardCharsets;

/**
 * WatchListenerService - receives captures from the Galaxy Watch
 * companion (v0.18.0). The watch does the dictation; the text
 * arrives here and is filed as a voice memory - facts extracted,
 * embedded, reminder-armed - exactly like a dictated voice note.
 * A small confirmation notification says it landed.
 */
public class WatchListenerService extends WearableListenerService {

    public static final String PATH_NOTE = "/recallvault/note";
    public static final String PATH_ASK = "/recallvault/ask";
    public static final String PATH_ANSWER = "/recallvault/answer";

    @Override
    public void onMessageReceived(MessageEvent event) {
        if (event == null) return;
        if (PATH_ASK.equals(event.getPath())) {
            handleAsk(event);
            return;
        }
        if (!PATH_NOTE.equals(event.getPath())) return;
        String text = new String(event.getData(), StandardCharsets.UTF_8).trim();
        if (text.isEmpty()) return;
        try {
            Db db = new Db(this);
            Item it = new Item();
            it.type = "VOICE";
            it.body = text;
            it.extracted = text;
            Extract.Facts f = Extract.analyze(text);
            it.category = f.category;
            it.title = Extract.suggestTitle(text, "Watch note");
            it.reminderAt = f.dueDate;
            StringBuilder am = new StringBuilder();
            for (int i = 0; i < f.amounts.size(); i++) {
                if (i > 0) am.append(',');
                am.append(f.amounts.get(i));
            }
            it.amounts = am.toString();
            it.createdAt = System.currentTimeMillis();
            db.insert(it);
            Embed.embedPending(this, db);

            Remind.ensureChannel(this);
            NotificationCompat.Builder b = new NotificationCompat.Builder(this, Remind.CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Watch note saved")
                .setContentText(it.title)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                try { nm.notify((int) (30000 + (it.id % 5000)), b.build()); }
                catch (SecurityException e) { /* permission not granted: the note is saved regardless */ }
            }
        } catch (Exception e) {
            // A bad message must never crash the listener.
        }
    }

    /**
     * Ask on the watch (v0.37.0): the watch dictates a question, the
     * phone answers it from the vault - same hook chain as the Ask
     * tab - and the answer travels back for the watch to show (and
     * its screen reader / TTS can speak it). Runs on a thread with a
     * short wake lock: the semantic pass can take a few seconds.
     */
    private void handleAsk(final MessageEvent event) {
        final String q = new String(event.getData(), StandardCharsets.UTF_8).trim();
        final String nodeId = event.getSourceNodeId();
        if (q.isEmpty() || nodeId == null) return;
        final android.os.PowerManager.WakeLock wl;
        android.os.PowerManager pm =
            (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
        wl = pm != null ? pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK,
            "recallvault:watchask") : null;
        if (wl != null) wl.acquire(30000);
        new Thread(new Runnable() {
            @Override public void run() {
                String answer;
                try {
                    answer = answerQuestion(q);
                } catch (Exception e) {
                    answer = "Something went wrong answering that on the phone.";
                }
                try {
                    com.google.android.gms.wearable.Wearable.getMessageClient(WatchListenerService.this)
                        .sendMessage(nodeId, PATH_ANSWER, answer.getBytes(StandardCharsets.UTF_8));
                } catch (Exception e) {
                    // Watch unreachable: the answer simply does not land.
                } finally {
                    if (wl != null && wl.isHeld()) wl.release();
                }
            }
        }, "watch-ask").start();
    }

    /** The Ask-tab pipeline, condensed to one speakable answer. */
    private String answerQuestion(String q) {
        Db db = new Db(this);
        java.util.List<Item> all = db.all();
        long now = System.currentTimeMillis();
        float[] vec = null;
        try {
            vec = LocalEmbed.embedSync(this, q);
        } catch (Exception e) {
            // Keyword floor still answers.
        }
        Ask.Summary sum = Patterns.answer(all, q, now);
        if (sum == null) sum = Entities.answer(all, q);
        if (sum == null) sum = Report.answer(all, q, now);
        if (sum == null) sum = Expiry.answer(all, q, now);
        if (sum == null) sum = Resurface.reviewAnswer(all, q, now);
        if (sum == null) sum = Ask.summarize(all, q, vec, now);
        String out;
        if (sum != null) {
            out = sum.text;
        } else {
            java.util.List<Ask.Result> results = Ask.search(all, q, vec, true);
            if (results.isEmpty()) {
                out = "Nothing in your vault matches that yet.";
            } else {
                Item top = results.get(0).item;
                String body = top.body != null && !top.body.isEmpty() ? top.body
                    : (top.extracted != null ? top.extracted : "");
                body = body.replaceAll("\\s+", " ").trim();
                if (body.length() > 220) body = body.substring(0, 217) + "...";
                out = top.title + (body.isEmpty() ? "" : " - " + body);
            }
        }
        if (out.length() > 600) out = out.substring(0, 597) + "...";
        return out;
    }
}
