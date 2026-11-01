package com.irwinlabs.recallvault;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * HandledReceiver - the "Mark paid / Mark done" action on a due-date
 * notification (v0.13.0). Marks the memory handled and dismisses
 * its notification; no UI involved.
 */
public class HandledReceiver extends BroadcastReceiver {

    public static final String ACTION = "com.irwinlabs.recallvault.MARK_HANDLED";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;
        long id = intent.getLongExtra("id", -1);
        if (id < 0) return;
        Db db = new Db(ctx);
        Item it = db.get(id);
        if (it != null && it.handledAt == 0) {
            it.handledAt = System.currentTimeMillis();
            db.update(it);
        }
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel((int) (10000 + id));
    }
}
