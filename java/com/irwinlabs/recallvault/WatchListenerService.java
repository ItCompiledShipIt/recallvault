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

    @Override
    public void onMessageReceived(MessageEvent event) {
        if (event == null || !PATH_NOTE.equals(event.getPath())) return;
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
}
