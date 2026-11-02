package com.irwinlabs.recallvault;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/**
 * Remind - schedules the daily due-date check. The worker runs once a
 * day (and once on demand via checkNow) and posts a notification for
 * anything due within 48 hours or overdue that has not been notified
 * for that due date yet - one ping per deadline, never a nag loop.
 */
public final class Remind {

    private Remind() {}

    public static final String CHANNEL = "due_dates";

    public static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(
                    CHANNEL, "Due dates", NotificationManager.IMPORTANCE_DEFAULT);
                ch.setDescription("Bills and deadlines saved in Clio");
                nm.createNotificationChannel(ch);
            }
        }
    }

    public static void enqueueAll(Context ctx) {
        ensureChannel(ctx);
        PeriodicWorkRequest daily = new PeriodicWorkRequest.Builder(
            ReminderWorker.class, 24, TimeUnit.HOURS).build();
        WorkManager.getInstance(ctx.getApplicationContext())
            .enqueueUniquePeriodicWork("rv-remind-daily", ExistingPeriodicWorkPolicy.KEEP, daily);
    }

    /** Run the check once, soon - used at app start and after a save with a due date. */
    public static void checkNow(Context ctx) {
        ensureChannel(ctx);
        WorkManager.getInstance(ctx.getApplicationContext())
            .enqueue(new OneTimeWorkRequest.Builder(ReminderWorker.class).build());
    }
}
