package com.irwinlabs.recallvault;

import android.content.Context;

/**
 * JVM-test stub for LocalEmbed (the real one needs MediaPipe native
 * libraries and only compiles in the Android build). Test-only: this
 * file is never part of the app build.
 */
public final class LocalEmbed {
    private LocalEmbed() {}
    public static void embedPendingLocal(Context ctx, Db db) {}
}
