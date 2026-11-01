package com.irwinlabs.recallvault;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

/**
 * AskWidget (v0.47.0): a home-screen button that goes straight
 * to a voice question - tap, speak, the vault answers. The tap
 * lands on MainActivity with the ask-voice flag, so the app lock
 * still gates it exactly like every other entry point.
 */
public class AskWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) mgr.updateAppWidget(id, views(ctx));
    }

    static RemoteViews views(Context ctx) {
        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_ask);
        Intent ask = new Intent(ctx, MainActivity.class);
        ask.putExtra(MainActivity.EXTRA_ASK_VOICE, true);
        ask.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        v.setOnClickPendingIntent(R.id.widget_ask_root, PendingIntent.getActivity(ctx, 7003, ask,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        return v;
    }
}
