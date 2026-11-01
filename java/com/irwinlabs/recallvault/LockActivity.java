package com.irwinlabs.recallvault;

import android.app.Activity;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.concurrent.Executor;

/**
 * Full-screen lock gate: brand mark, one Unlock button, and the
 * framework biometric / device-credential prompt. Shown by gated
 * activities whenever AppLock says the vault is locked. Back is
 * swallowed - the only way through is a successful unlock.
 */
public class LockActivity extends Activity {

    private CancellationSignal cancelSignal;
    private TextView status;

    private final Executor mainExecutor = new Executor() {
        private final Handler h = new Handler(Looper.getMainLooper());
        @Override public void execute(Runnable r) { h.post(r); }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!AppLock.needsUnlock(this)) {
            finish();
            return;
        }
        Ui.applyChrome(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(Ui.dp(this, 32), Ui.dp(this, 120), Ui.dp(this, 32), Ui.dp(this, 32));

        android.widget.FrameLayout mark = Ui.brandMark(this);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(Ui.dp(this, 72), Ui.dp(this, 72));
        mark.setLayoutParams(mlp);
        root.addView(mark);

        TextView title = Ui.text(this, "RecallVault is locked", 20, Ui.TEXT, true);
        title.setLayoutParams(Ui.matchWrap(20, this));
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        status = Ui.text(this, "Your memories stay private on this phone. Unlock to open the vault.", 13, Ui.DIM, false);
        status.setLayoutParams(Ui.matchWrap(8, this));
        status.setGravity(Gravity.CENTER);
        root.addView(status);

        Button unlock = Ui.iconButton(this, "Unlock", R.drawable.ic_lock, Ui.ACCENT, Ui.ACCENT_DARK_TEXT);
        unlock.setLayoutParams(Ui.matchWrap(24, this));
        unlock.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { prompt(); }
        });
        root.addView(unlock);

        setContentView(root);
        prompt();
    }

    private void prompt() {
        if (Build.VERSION.SDK_INT < 28) {
            status.setText("This Android version cannot show the unlock prompt.");
            return;
        }
        AppLock.setPromptOpen(true);
        try {
            BiometricPrompt.Builder b = new BiometricPrompt.Builder(this)
                .setTitle("Unlock RecallVault")
                .setSubtitle("Your vault, your fingerprint or PIN");
            if (Build.VERSION.SDK_INT >= 30) {
                b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG
                    | BiometricManager.Authenticators.DEVICE_CREDENTIAL);
            } else {
                b.setDeviceCredentialAllowed(true);
            }
            cancelSignal = new CancellationSignal();
            b.build().authenticate(cancelSignal, mainExecutor,
                new BiometricPrompt.AuthenticationCallback() {
                    @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult r) {
                        AppLock.markUnlocked();
                        finish();
                    }
                    @Override public void onAuthenticationError(int code, CharSequence msg) {
                        AppLock.setPromptOpen(false);
                        // Framework error codes (the constants are not on
                        // the framework BiometricPrompt class): 10 = user
                        // canceled, 13 = negative button.
                        if (code != 10 && code != 13) {
                            status.setText("Unlock did not go through (" + msg + "). Tap Unlock to try again.");
                        }
                    }
                    @Override public void onAuthenticationFailed() {
                        status.setText("Not recognized. Try again.");
                    }
                });
        } catch (Throwable t) {
            AppLock.setPromptOpen(false);
            status.setText("Unlock is unavailable on this phone right now.");
        }
    }

    @Override
    public void onBackPressed() {
        // Swallowed on purpose: the gate is the point.
    }

    @Override
    protected void onDestroy() {
        if (cancelSignal != null) cancelSignal.cancel();
        AppLock.setPromptOpen(false);
        super.onDestroy();
    }
}
