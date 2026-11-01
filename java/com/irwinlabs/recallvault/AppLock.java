package com.irwinlabs.recallvault;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.biometrics.BiometricManager;
import android.os.Build;

/**
 * Opt-in app lock (v0.6.0): fingerprint or the phone's own
 * PIN/pattern via the framework BiometricPrompt.
 *
 * Model: one in-memory unlock stamp and one activity stamp. Any
 * gated screen refreshes the activity stamp while unlocked; if the
 * gap since the last activity exceeds LOCK_AFTER_MS (app left in
 * the background), or the process is fresh (cold start), the vault
 * is locked again. Stamps are never persisted, so a killed process
 * always comes back locked, never unlocked.
 */
public final class AppLock {

    private AppLock() {}

    public static final String PREF = "app_lock_enabled";
    private static final long LOCK_AFTER_MS = 2 * 60 * 1000L;

    private static long lastUnlockAt = 0;
    private static long lastActiveAt = 0;
    private static boolean promptOpen = false;

    public static boolean isEnabled(Context ctx) {
        return prefs(ctx).getBoolean(PREF, false);
    }

    public static void setEnabled(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(PREF, on).apply();
        if (!on) {
            lastUnlockAt = 0;
            lastActiveAt = 0;
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(Embed.PREFS, Context.MODE_PRIVATE);
    }

    /** True when a biometric or a device credential can be used. */
    public static boolean canAuthenticate(Context ctx) {
        if (Build.VERSION.SDK_INT < 29) return false;
        try {
            BiometricManager bm = (BiometricManager) ctx.getSystemService(Context.BIOMETRIC_SERVICE);
            if (bm == null) return false;
            int auth = Build.VERSION.SDK_INT >= 30
                ? BiometricManager.Authenticators.BIOMETRIC_STRONG | BiometricManager.Authenticators.DEVICE_CREDENTIAL
                : BiometricManager.Authenticators.BIOMETRIC_WEAK | BiometricManager.Authenticators.DEVICE_CREDENTIAL;
            return bm.canAuthenticate(auth) == BiometricManager.BIOMETRIC_SUCCESS;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean needsUnlock(Context ctx) {
        if (!isEnabled(ctx) || promptOpen) return false;
        if (lastUnlockAt == 0) return true;
        boolean stale = System.currentTimeMillis() - lastActiveAt > LOCK_AFTER_MS;
        if (stale) unlockedItems.clear(); // items re-lock with the app (v0.46.0)
        return stale;
    }

    // Per-item unlocks (v0.46.0): process-lifetime, like the app unlock.
    private static final java.util.Set<Long> unlockedItems = new java.util.HashSet<Long>();

    public static boolean isItemUnlocked(long id) {
        return unlockedItems.contains(id);
    }

    public static void unlockItem(long id) {
        unlockedItems.add(id);
    }

    public static void markUnlocked() {
        lastUnlockAt = System.currentTimeMillis();
        lastActiveAt = lastUnlockAt;
        promptOpen = false;
    }

    /** Gated screens call this on resume/pause once the gate passed. */
    public static void noteActivity() {
        if (lastUnlockAt > 0) lastActiveAt = System.currentTimeMillis();
    }

    public static void setPromptOpen(boolean open) {
        promptOpen = open;
    }
}
