package com.irwinlabs.recallvault;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;

/**
 * Speech-to-text for voice notes and meeting recordings.
 *
 * v0.4.0: fully on-device via Whisper (whisper.cpp) - the audio never
 * leaves the phone. v0.3 sent recordings to a faster-whisper service
 * on bigpc over Tailscale; Chris asked for everything on the phone,
 * so the server path is gone, along with its endpoint setting.
 */
public final class Transcribe {
    private Transcribe() {}

    public interface Callback {
        void onText(String text);
        void onError(String message);
    }

    /** Blocking transcription - call off the UI thread. */
    public static String transcribeSync(Context ctx, File audio) throws Exception {
        return Whisper.transcribeFile(ctx, audio);
    }

    /** Async wrapper mirroring the rest of the app's style. */
    public static void transcribe(final Context ctx, final File audio, final Callback cb) {
        final Handler ui = new Handler(Looper.getMainLooper());
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                final String text = transcribeSync(app, audio);
                ui.post(() -> cb.onText(text));
            } catch (final Exception e) {
                ui.post(() -> cb.onError(e.getMessage() != null ? e.getMessage() : "Transcription failed"));
            }
        }, "recallvault-transcribe").start();
    }
}
