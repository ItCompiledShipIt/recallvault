package com.irwinlabs.recallvault;

import android.app.Notification;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * ClioNotifyListener (v0.70.0) - hears the phone's notifications
 * and files the meaningful ones: bills, deliveries,
 * appointments, money movement. NotifyFilter makes the call;
 * everything else stays in the shade. Requires the system's
 * notification-access grant (Settings switch walks him there)
 * plus the in-app toggle; either one off means silence.
 */
public class ClioNotifyListener extends NotificationListenerService {

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            if (sbn == null) return;
            if (!NotifySupport.enabled(this)) return;
            String pkg = sbn.getPackageName();
            if (pkg == null || pkg.equals(getPackageName())) return;
            if ("android".equals(pkg)) return;
            Notification n = sbn.getNotification();
            if (n == null || n.extras == null) return;
            if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
            if ((n.flags & Notification.FLAG_ONGOING_EVENT) != 0) return;
            Bundle ex = n.extras;
            CharSequence t = ex.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence x = ex.getCharSequence(Notification.EXTRA_BIG_TEXT);
            if (x == null || x.length() == 0) {
                x = ex.getCharSequence(Notification.EXTRA_TEXT);
            }
            String title = t == null ? "" : t.toString();
            String text = x == null ? "" : x.toString();
            NotifyFilter.Verdict v = NotifyFilter.judge(title, text);
            if (!v.keep) return;
            String label = pkg;
            try {
                ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
                CharSequence l = getPackageManager().getApplicationLabel(ai);
                if (l != null && l.length() > 0) label = l.toString();
            } catch (Exception e) { /* package name is a fine label */ }
            NotifySupport.file(this, pkg, label, title, text, sbn.getPostTime());
        } catch (Exception e) {
            // A listener must never crash on a weird notification.
        }
    }
}
