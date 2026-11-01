package com.irwinlabs.recallvault;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * ReminderWorker - posts notifications for deadlines due within
 * 48 hours (or overdue). Each (item, due date) pair notifies exactly
 * once; the notified set lives in SharedPreferences. If Android 13+
 * notification permission was never granted, posts simply no-op.
 */
public class ReminderWorker extends Worker {

    private static final String PREF_NOTIFIED = "notified_reminders";
    private static final long WINDOW_MS = 48L * 3600 * 1000;

    public ReminderWorker(@NonNull Context ctx, @NonNull WorkerParameters params) {
        super(ctx, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        Remind.ensureChannel(ctx);
        Db db = new Db(ctx);
        long now = System.currentTimeMillis();
        SharedPreferences prefs = ctx.getSharedPreferences(Embed.PREFS, Context.MODE_PRIVATE);
        Set<String> notified = new HashSet<String>(
            prefs.getStringSet(PREF_NOTIFIED, new HashSet<String>()));
        boolean changed = false;

        List<Item> items = db.withReminders();
        NotificationManagerCompat nm = NotificationManagerCompat.from(ctx);
        for (Item it : items) {
            if (it.reminderAt <= 0) continue;
            if (it.handledAt > 0) continue; // finished reminders stay quiet
            if (it.reminderAt > now + WINDOW_MS) continue;
            String key = it.id + ":" + it.reminderAt;
            if (notified.contains(key)) continue;

            boolean overdue = it.reminderAt < now - 24L * 3600 * 1000;
            String when = overdue
                ? "overdue - was due " + Extract.formatDate(it.reminderAt)
                : "due " + Extract.formatDate(it.reminderAt)
                    + " (" + Extract.relativeDay(it.reminderAt, now) + ")";
            String text = it.category + " - " + when
                + (it.amounts.isEmpty() ? "" : " - " + it.amounts.replace(",", ", "));

            Intent open = new Intent(ctx, DetailActivity.class);
            open.putExtra("id", it.id);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(ctx, (int) it.id, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            // Finish it right from the notification (v0.13.0).
            Intent done = new Intent(ctx, HandledReceiver.class);
            done.setAction(HandledReceiver.ACTION);
            done.putExtra("id", it.id);
            PendingIntent donePi = PendingIntent.getBroadcast(ctx, (int) it.id, done,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, Remind.CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(it.title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .addAction(0, "Bills".equals(it.category) ? "Mark paid" : "Mark done", donePi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
            try {
                nm.notify((int) (10000 + it.id), b.build());
            } catch (SecurityException e) {
                // Permission not granted: stay quiet, do not mark notified.
                continue;
            }
            notified.add(key);
            changed = true;
        }
        if (changed) {
            prefs.edit().putStringSet(PREF_NOTIFIED, notified).apply();
        }
        // Weekly self-maintaining backup rides the daily pass (v0.12.0).
        AutoBackup.runIfDue(ctx);
        // Keep any home-screen widget current (v0.14.0).
        VaultWidget.refresh(ctx);
        return Result.success();
    }
}
