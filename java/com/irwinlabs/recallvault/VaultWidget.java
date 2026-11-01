package com.irwinlabs.recallvault;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import java.util.List;

/**
 * VaultWidget - home-screen widget (v0.14.0): the due-this-week
 * digest at a glance, a one-tap voice-note button, and an open
 * button. The app refreshes it whenever the vault changes and on
 * the daily reminder pass; no polling of its own.
 */
public class VaultWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) mgr.updateAppWidget(id, views(ctx));
    }

    static RemoteViews views(Context ctx) {
        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget);
        String text = "Nothing due this week.";
        try {
            Db db = new Db(ctx);
            List<Item> items = db.withReminders();
            Ask.Summary d = Ask.dueDigest(items, System.currentTimeMillis());
            if (d != null) text = d.text;
        } catch (Exception e) {
            // A widget must never crash the launcher; fall through.
        }
        v.setTextViewText(R.id.widget_digest, text);

        Intent voice = new Intent(ctx, VoiceActivity.class);
        voice.putExtra("mode", "note");
        voice.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        v.setOnClickPendingIntent(R.id.widget_voice, PendingIntent.getActivity(ctx, 7001, voice,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));

        Intent open = new Intent(ctx, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        v.setOnClickPendingIntent(R.id.widget_open, PendingIntent.getActivity(ctx, 7002, open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        return v;
    }

    /** Repaint every placed widget; safe to call from anywhere. */
    public static void refresh(Context ctx) {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
            int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, VaultWidget.class));
            if (ids.length > 0) mgr.updateAppWidget(ids, views(ctx));
        } catch (Exception e) {
            // No widgets placed, or the manager is unavailable.
        }
    }
}
