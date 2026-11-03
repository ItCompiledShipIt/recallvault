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
            // No category in the notification (v0.69.0): the
            // date and the amount are the news.
            String text = when
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

            // Snooze it right from the notification (v0.41.0).
            Intent snooze = new Intent(ctx, SnoozeReceiver.class);
            snooze.setAction(SnoozeReceiver.ACTION);
            snooze.putExtra("id", it.id);
            snooze.putExtra("days", 1);
            PendingIntent snoozePi = PendingIntent.getBroadcast(ctx, (int) it.id, snooze,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, Remind.CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(Privacy.displayTitle(it))
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .addAction(0, "Snooze 1 day", snoozePi)
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
        // Expiry warnings (v0.30.0): one ping per item when its expiry
        // comes within 30 days (or has passed), same exactly-once rule.
        for (Item it : db.all()) {
            if (it.expiryAt <= 0) continue;
            if (it.expiryAt > now + 30L * 24 * 3600 * 1000) continue;
            String key = "exp:" + it.id + ":" + it.expiryAt;
            if (notified.contains(key)) continue;
            boolean past = it.expiryAt < now;
            String text = (past ? "Expired " : "Expires ") + Extract.formatDate(it.expiryAt)
                + " (" + Extract.relativeDay(it.expiryAt, now) + ") - " + it.category;
            Intent open = new Intent(ctx, DetailActivity.class);
            open.putExtra("id", it.id);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(ctx, (int) (40000 + it.id), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, Remind.CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(Privacy.displayTitle(it))
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
            try {
                nm.notify((int) (40000 + it.id), b.build());
            } catch (SecurityException e) {
                continue;
            }
            notified.add(key);
            changed = true;
        }
        // Heads-up pass (v0.40.0): one earlier nudge when a deadline
        // first comes within the lead window (default 3 days).
        int leadDays = prefs.getInt(HeadsUp.PREF_LEAD_DAYS, HeadsUp.DEFAULT_LEAD_DAYS);
        for (Item it : HeadsUp.due(items, now, leadDays, notified)) {
            String text = "Due " + Extract.formatDate(it.reminderAt)
                + " (" + Extract.relativeDay(it.reminderAt, now) + ")"
                + (it.amounts.isEmpty() ? "" : " - " + it.amounts.replace(",", ", "))
                + " - " + it.category;
            Intent open = new Intent(ctx, DetailActivity.class);
            open.putExtra("id", it.id);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(ctx, (int) (50000 + it.id), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, Remind.CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Coming up: " + Privacy.displayTitle(it))
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
            try {
                nm.notify((int) (50000 + it.id), b.build());
            } catch (SecurityException e) {
                continue;
            }
            notified.add(HeadsUp.key(it));
            changed = true;
        }
        // Contact birthdays (v0.63.0): one ping when a birthday
        // is tomorrow, one on the day - per contact, per year,
        // in the same notified set as everything else.
        try {
            java.time.LocalDate todayB = java.time.LocalDate.now();
            int bIdx = 0;
            for (Contacts.Contact c : Contacts.load(ctx)) {
                bIdx++;
                java.time.LocalDate nb = Contacts.nextBirthday(c, todayB);
                if (nb == null) continue;
                long days = java.time.temporal.ChronoUnit.DAYS.between(todayB, nb);
                if (days < 0 || days > 1) continue;
                String key = "bday:" + c.id + ":" + nb.getYear() + ":" + days;
                if (notified.contains(key)) continue;
                String whenB = days == 0 ? "today" : "tomorrow";
                String text = "Birthday " + whenB + " - "
                    + nb.format(java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d", java.util.Locale.US));
                int age = Contacts.turningAge(c, todayB);
                if (age > 0) text += " - turns " + age;
                Intent open = new Intent(ctx, ContactsActivity.class);
                open.putExtra("contact_id", c.id);
                open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                PendingIntent pi = PendingIntent.getActivity(ctx, 61000 + bIdx, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, Remind.CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(c.name)
                    .setContentText(text)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT);
                try {
                    nm.notify(61000 + bIdx, b.build());
                } catch (SecurityException e) {
                    continue;
                }
                notified.add(key);
                changed = true;
            }
        } catch (Throwable ignored) {
            // Contacts never break the reminder pass.
        }
        if (changed) {
            prefs.edit().putStringSet(PREF_NOTIFIED, notified).apply();
        }
        // Clio's nightly self-review of her own memory (v0.66.0).
        try {
            ClioMind.nightlyReview(ctx);
        } catch (Throwable ignored) {}
        // Daily live sync with the desktop, when paired (v0.67.0).
        try {
            if (SyncClient.configured(ctx)) SyncClient.syncNow(ctx);
        } catch (Throwable ignored) {}
        // Weekly self-maintaining backup rides the daily pass (v0.12.0).
        AutoBackup.runIfDue(ctx);
        // Morning digest, once a day (v0.28.0).
        maybePostDigest(ctx);
        // Automatic ingest of watched folders / new phone media (v0.27.0).
        FileIngest.runIfEnabled(ctx);
        // Keep any home-screen widget current (v0.14.0).
        VaultWidget.refresh(ctx);
        return Result.success();
    }

    static final String PREF_DIGEST_ENABLED = "digest_enabled";
    static final String PREF_DIGEST_DAY = "digest_last_day";
    static final int DIGEST_ID = 20001;

    private void maybePostDigest(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(Embed.PREFS, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(PREF_DIGEST_ENABLED, true)) return;
        java.util.Calendar cal = java.util.Calendar.getInstance();
        int today = cal.get(java.util.Calendar.YEAR) * 1000 + cal.get(java.util.Calendar.DAY_OF_YEAR);
        if (prefs.getInt(PREF_DIGEST_DAY, -1) == today) return;
        prefs.edit().putInt(PREF_DIGEST_DAY, today).apply();
        postDigest(ctx);
    }

    /** Compose and post the digest now (worker daily pass + Settings preview). */
    static boolean postDigest(Context ctx) {
        Remind.ensureChannel(ctx);
        Db db = new Db(ctx);
        Digest.Out out = Digest.compose(db.all(), System.currentTimeMillis());
        if (out == null) return false;
        StringBuilder big = new StringBuilder();
        for (String line : out.lines) {
            if (big.length() > 0) big.append('\n');
            big.append(line);
        }
        Intent open = new Intent(ctx, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, DIGEST_ID, open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, Remind.CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Today in your vault - " + out.headline)
            .setContentText(out.lines.get(0))
            .setStyle(new NotificationCompat.BigTextStyle().bigText(big.toString()))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        try {
            NotificationManagerCompat.from(ctx).notify(DIGEST_ID, b.build());
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }
}
