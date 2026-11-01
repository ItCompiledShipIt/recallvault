package com.irwinlabs.recallvault;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * SnoozeReceiver - the "Snooze 1 day" action on a due-date
 * notification (v0.41.0). Pushes the due date out and dismisses
 * the notification; the new date re-notifies on its own schedule.
 */
public class SnoozeReceiver extends BroadcastReceiver {

    public static final String ACTION = "com.irwinlabs.recallvault.SNOOZE";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;
        long id = intent.getLongExtra("id", -1);
        int days = intent.getIntExtra("days", 1);
        if (id < 0) return;
        Db db = new Db(ctx);
        Item it = db.get(id);
        if (it != null && it.handledAt == 0 && it.reminderAt > 0) {
            it.reminderAt = Snooze.snoozedUntil(it.reminderAt, days);
            db.update(it);
        }
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.cancel((int) (10000 + id));
            nm.cancel((int) (50000 + id));
        }
    }
}
