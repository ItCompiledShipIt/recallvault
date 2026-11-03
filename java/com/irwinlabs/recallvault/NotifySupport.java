package com.irwinlabs.recallvault;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import java.util.HashSet;
import java.util.Set;

/**
 * NotifySupport - the filing half of notification ingest
 * (v0.70.0). The listener service stays thin; this owns the
 * on/off preference, the system-access check, dedupe and the
 * insert, which mirrors how meaningful texts are filed.
 */
public final class NotifySupport {

    private NotifySupport() {}

    private static final String PREF_ON = "notify_ingest";
    private static final String PREF_SEEN = "notify_seen";

    public static boolean enabled(Context ctx) {
        return prefs(ctx).getBoolean(PREF_ON, false);
    }

    public static void setEnabled(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(PREF_ON, on).apply();
    }

    /** True when Android has granted this app notification access. */
    public static boolean accessGranted(Context ctx) {
        try {
            String flat = Settings.Secure.getString(ctx.getContentResolver(),
                "enabled_notification_listeners");
            return flat != null && flat.contains(ctx.getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(Embed.PREFS, Context.MODE_PRIVATE);
    }

    /**
     * File one judged notification. Returns true when a memory
     * was created. Duplicates - the same app re-posting the
     * same words - are filed once.
     */
    public static boolean file(Context ctx, String pkg, String appLabel,
                               String title, String text, long whenMs) {
        String body = (text == null ? "" : text).trim();
        String head = (title == null ? "" : title).trim();
        String keySource = pkg + "|" + head + "|" + body;
        String key = String.valueOf(keySource.hashCode());
        SharedPreferences p = prefs(ctx);
        Set<String> seen = new HashSet<String>(
            p.getStringSet(PREF_SEEN, new HashSet<String>()));
        if (seen.contains(key)) return false;

        Extract.Facts f = Extract.analyze(head + "\n" + body);
        Item it = new Item();
        it.type = "NOTE";
        String who = (appLabel == null || appLabel.isEmpty()) ? pkg : appLabel;
        it.title = head.isEmpty() ? ("Notification from " + who)
            : (who + ": " + head);
        if (it.title.length() > 90) it.title = it.title.substring(0, 87) + "...";
        it.body = body;
        it.extracted = "";
        it.filePath = "";
        it.mime = "";
        it.fileHash = "";
        it.embedding = "";
        it.createdAt = whenMs > 0 ? whenMs : System.currentTimeMillis();
        it.reminderAt = f.dueDate > 0 ? f.dueDate : -1;
        StringBuilder am = new StringBuilder();
        for (int i = 0; i < f.amounts.size(); i++) {
            if (i > 0) am.append(',');
            am.append(f.amounts.get(i));
        }
        it.amounts = am.toString();
        it.category = f.category;
        Db db = new Db(ctx);
        db.insert(it);

        seen.add(key);
        // Cap the dedupe memory; order is not guaranteed, and
        // losing the oldest entries only risks a rare re-file.
        while (seen.size() > 200) seen.remove(seen.iterator().next());
        p.edit().putStringSet(PREF_SEEN, seen).apply();
        return true;
    }
}
