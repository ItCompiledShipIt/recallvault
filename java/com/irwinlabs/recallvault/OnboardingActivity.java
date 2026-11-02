package com.irwinlabs.recallvault;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/**
 * First-run welcome (v0.60.0), in the Sanctuary design:
 * three quiet steps - what Clio is, how she listens,
 * and the on-device models that power her senses.
 * Shown once (pref onboarded_v1), after the app lock
 * clears; replayable any time from Settings.
 * Copy rule: only what the app truly does.
 */
public class OnboardingActivity extends Activity {

    private int step = 0;
    private LinearLayout body;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.applyTheme(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(Ui.dp(this, 22), Ui.dp(this, 16), Ui.dp(this, 22), Ui.dp(this, 4));
        ImageView mark = new ImageView(this);
        mark.setImageResource(R.drawable.clio_capsule);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(Ui.dp(this, 34), Ui.dp(this, 34));
        mlp.setMargins(0, 0, Ui.dp(this, 9), 0);
        mark.setLayoutParams(mlp);
        header.addView(mark);
        TextView wm = Ui.text(this, "Clio", 20, Ui.TEXT, true);
        wm.setTypeface(Ui.faceDisplay(this));
        header.addView(wm);
        root.addView(header);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(Ui.dp(this, 24), Ui.dp(this, 10), Ui.dp(this, 24), Ui.dp(this, 28));
        scroll.addView(body);
        root.addView(scroll);
        setContentView(root);
        showStep();
    }

    private void showStep() {
        body.removeAllViews();
        // Step line + progress segments.
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        topRow.setLayoutParams(Ui.matchWrap(4, this));
        topRow.addView(Ui.text(this, "Step " + (step + 1) + " of 5", 12, Ui.DIM, true));
        View tsp = new View(this);
        tsp.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        topRow.addView(tsp);
        if (step < 4) {
            TextView skip = Ui.text(this, "Skip", 13, Ui.WARN, true);
            skip.setClickable(true);
            skip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { finishTour(); }
            });
            topRow.addView(skip);
        }
        body.addView(topRow);
        LinearLayout prog = new LinearLayout(this);
        prog.setOrientation(LinearLayout.HORIZONTAL);
        prog.setLayoutParams(Ui.matchWrap(14, this));
        for (int i = 0; i < 5; i++) {
            View seg = new View(this);
            seg.setBackground(Ui.panelBg(i <= step ? Ui.ACCENT : Ui.PANEL_HI, 0, 999, this));
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, Ui.dp(this, 5), 1f);
            slp.setMargins(i == 0 ? 0 : Ui.dp(this, 4), 0, i == 4 ? 0 : Ui.dp(this, 4), 0);
            seg.setLayoutParams(slp);
            prog.addView(seg);
        }
        body.addView(prog);

        if (step == 0) stepWelcome();
        else if (step == 1) stepName();
        else if (step == 2) stepPerms();
        else if (step == 3) stepListen();
        else stepModels();
    }

    private TextView headline(String s) {
        TextView h = Ui.text(this, s, 29, Ui.TEXT, true);
        h.setTypeface(Ui.faceDisplay(this));
        h.setGravity(Gravity.CENTER);
        h.setLayoutParams(Ui.matchWrap(10, this));
        return h;
    }

    private TextView bodyText(String s) {
        TextView b = Ui.text(this, s, 15, Ui.DIM, false);
        b.setGravity(Gravity.CENTER);
        b.setLayoutParams(Ui.matchWrap(8, this));
        return b;
    }

    private android.widget.Button primary(String label, View.OnClickListener click) {
        android.widget.Button b = Ui.button(this, label, Ui.ACCENT, Ui.ACCENT_DARK_TEXT);
        LinearLayout.LayoutParams lp = Ui.matchWrap(18, this);
        lp.height = Ui.dp(this, 52);
        b.setLayoutParams(lp);
        b.setOnClickListener(click);
        return b;
    }

    private void stepWelcome() {
        ImageView hero = new ImageView(this);
        hero.setImageResource(R.drawable.clio_capsule);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(Ui.dp(this, 128), Ui.dp(this, 128));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        hlp.setMargins(0, Ui.dp(this, 16), 0, Ui.dp(this, 6));
        hero.setLayoutParams(hlp);
        body.addView(hero);
        body.addView(headline("Your Phone. Your Memories. Your Sanctuary."));
        body.addView(bodyText(
            "Clio is a private vault for the things you'd otherwise lose - "
            + "voice notes, photos, bills and reminders. Everything is kept, "
            + "and understood, right here on this phone."));
        body.addView(moveRow(R.drawable.ic_mic, Ui.MINT, "Speak it",
            "Say it out loud - Clio writes it down."));
        body.addView(moveRow(R.drawable.ic_scan, Ui.TERRA_BG, "Scan it",
            "Receipts, letters, handwritten notes."));
        body.addView(moveRow(R.drawable.ic_search, Ui.SAGE_BG, "Ask for it back",
            "Plain words, months later - she finds it."));
        TextView foot = Ui.text(this,
            "No account. No cloud. Your data never leaves this device unless you choose to share it.",
            12, Ui.DIM, false);
        foot.setGravity(Gravity.CENTER);
        foot.setLayoutParams(Ui.matchWrap(16, this));
        body.addView(foot);
        body.addView(primary("Set Up My Sanctuary", new View.OnClickListener() {
            @Override public void onClick(View v) { step = 1; showStep(); }
        }));
    }

    private LinearLayout moveRow(int iconRes, int discBg, String label, String sub) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setLayoutParams(Ui.matchWrap(10, this));
        FrameLayout disc = new FrameLayout(this);
        android.graphics.drawable.GradientDrawable dg = new android.graphics.drawable.GradientDrawable();
        dg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        dg.setColor(discBg);
        disc.setBackground(dg);
        disc.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42)));
        ImageView iv = Ui.icon(this, iconRes, 20, Ui.FOREST);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(Ui.dp(this, 20), Ui.dp(this, 20));
        ilp.gravity = Gravity.CENTER;
        iv.setLayoutParams(ilp);
        disc.addView(iv);
        r.addView(disc);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tlp.setMargins(Ui.dp(this, 12), 0, 0, 0);
        texts.setLayoutParams(tlp);
        texts.addView(Ui.text(this, label, 15, Ui.TEXT, true));
        texts.addView(Ui.text(this, sub, 13, Ui.DIM, false));
        r.addView(texts);
        return r;
    }

    private void stepListen() {
        body.addView(headline("Just talk - Clio remembers."));
        body.addView(bodyText(
            "Record a thought and it is transcribed on this phone, word for word. "
            + "Ask in plain words later and Clio finds it. With her brain downloaded, "
            + "she can also write answers up from your vault - still without the cloud."));
        final SharedPreferences prefs = getSharedPreferences(Embed.PREFS, MODE_PRIVATE);
        LinearLayout lockCard = Ui.card(this);
        lockCard.setLayoutParams(Ui.matchWrap(16, this));
        LinearLayout lr = new LinearLayout(this);
        lr.setOrientation(LinearLayout.HORIZONTAL);
        lr.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout ltexts = new LinearLayout(this);
        ltexts.setOrientation(LinearLayout.VERTICAL);
        ltexts.setLayoutParams(new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        ltexts.addView(Ui.text(this, "Lock Clio", 15, Ui.TEXT, true));
        ltexts.addView(Ui.text(this,
            "Opens only with your fingerprint or phone PIN.",
            13, Ui.DIM, false));
        lr.addView(ltexts);
        final Switch lockSw = new Switch(this);
        lockSw.setChecked(prefs.getBoolean("app_lock_enabled", false));
        lockSw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                prefs.edit().putBoolean("app_lock_enabled", on).apply();
            }
        });
        lr.addView(lockSw);
        lockCard.addView(lr);
        body.addView(lockCard);
        body.addView(primary("Continue", new View.OnClickListener() {
            @Override public void onClick(View v) { step = 4; showStep(); }
        }));
    }

    /** Step 2 (v0.62.0): the name Clio greets you by. */
    private void stepName() {
        body.addView(headline("What should Clio call you?"));
        body.addView(bodyText(
            "Just your first name. She will greet you with it - "
            + "and like everything else here, it stays on this phone."));
        final android.widget.EditText nameIn = new android.widget.EditText(this);
        nameIn.setHint("Your name");
        nameIn.setSingleLine(true);
        nameIn.setTextColor(Ui.TEXT);
        nameIn.setHintTextColor(Ui.DIM);
        nameIn.setTextSize(17);
        nameIn.setTypeface(Ui.face(this, false));
        nameIn.setBackground(Ui.panelBg(Ui.PANEL, 0, 16, this));
        nameIn.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14));
        String cur = getSharedPreferences(Embed.PREFS, MODE_PRIVATE).getString("user_name", "");
        if (!cur.isEmpty()) nameIn.setText(cur);
        nameIn.setLayoutParams(Ui.matchWrap(16, this));
        body.addView(nameIn);
        body.addView(primary("Continue", new View.OnClickListener() {
            @Override public void onClick(View v) {
                getSharedPreferences(Embed.PREFS, MODE_PRIVATE).edit()
                    .putString("user_name", nameIn.getText().toString().trim()).apply();
                step = 2;
                showStep();
            }
        }));
    }

    /** Step 3 (v0.62.0): the device permissions, each with
     *  its plain reason and the real system prompt. */
    private void stepPerms() {
        body.addView(headline("A few permissions, only for what they do."));
        body.addView(bodyText(
            "Clio asks only for what a feature needs. Tap Allow and the "
            + "phone itself will ask - nothing is taken quietly, and you "
            + "can change your mind in the phone's settings any time."));
        String[][] rows = {
            {android.Manifest.permission.RECORD_AUDIO, "Microphone",
                "For voice notes and speaking your questions"},
            {android.Manifest.permission.CAMERA, "Camera",
                "For scanning receipts, letters and documents"},
            {android.Manifest.permission.POST_NOTIFICATIONS, "Notifications",
                "So a reminder can reach you before something is due"},
            {android.Manifest.permission.READ_MEDIA_IMAGES, "Photos",
                "For keeping photos you ask Clio to save"},
            {android.Manifest.permission.READ_SMS, "Text messages",
                "Only if you turn on text keeping in Settings, so plans "
                + "in your texts can be filed for you"},
        };
        for (int i = 0; i < rows.length; i++) {
            final String perm = rows[i][0];
            if (perm.equals(android.Manifest.permission.POST_NOTIFICATIONS)
                    && android.os.Build.VERSION.SDK_INT < 33) continue;
            boolean granted = checkSelfPermission(perm)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setBackground(Ui.panelBg(Ui.PANEL, 0, 16, this));
            r.setElevation(Ui.dp(this, 1));
            r.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
            r.setLayoutParams(Ui.matchWrap(10, this));
            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            texts.addView(Ui.text(this, rows[i][1], 15, Ui.TEXT, true));
            texts.addView(Ui.text(this, rows[i][2], 12, Ui.DIM, false));
            r.addView(texts);
            if (granted) {
                r.addView(Ui.catPill(this, "Allowed", Ui.MINT, Ui.MINT_TEXT));
            } else {
                final int code = 900 + i;
                android.widget.Button allow = Ui.ghostButton(this, "Allow");
                allow.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        requestPermissions(new String[]{perm}, code);
                    }
                });
                r.addView(allow);
            }
            body.addView(r);
        }
        body.addView(primary("Continue", new View.OnClickListener() {
            @Override public void onClick(View v) { step = 3; showStep(); }
        }));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (step == 2) showStep();
    }

    private void stepModels() {
        body.addView(headline("Bring Clio's senses home."));
        body.addView(bodyText(
            "Three on-device models power listening, finding and thinking. "
            + "They are free, they live on this phone, and you can download "
            + "any of them whenever you like from Settings."));
        body.addView(modelRow("Voice transcription",
            "Hears your voice notes (Whisper)", Whisper.isModelPresent(this)));
        body.addView(modelRow("Smart search",
            "Understands meaning, not just words", LocalEmbed.isModelPresent(this)));
        body.addView(modelRow("Clio's brain",
            "Writes answers up from your vault (Gemma)", GemmaBrain.isModelPresent(this)));
        TextView note = Ui.text(this,
            "Downloads live in Settings, under On-device models. Restoring a backup from "
            + "an old phone? Settings has Import, too.",
            12, Ui.DIM, false);
        note.setGravity(Gravity.CENTER);
        note.setLayoutParams(Ui.matchWrap(14, this));
        body.addView(note);
        body.addView(primary("Start using Clio", new View.OnClickListener() {
            @Override public void onClick(View v) { finishTour(); }
        }));
    }

    private LinearLayout modelRow(String label, String sub, boolean present) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBackground(Ui.panelBg(Ui.PANEL, 0, 16, this));
        r.setElevation(Ui.dp(this, 1));
        r.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        r.setLayoutParams(Ui.matchWrap(10, this));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        texts.addView(Ui.text(this, label, 15, Ui.TEXT, true));
        texts.addView(Ui.text(this, sub, 12, Ui.DIM, false));
        r.addView(texts);
        r.addView(Ui.catPill(this, present ? "On this phone" : "In Settings",
            present ? Ui.MINT : Ui.PANEL_HI, present ? Ui.MINT_TEXT : Ui.DIM));
        return r;
    }

    private void finishTour() {
        getSharedPreferences(Embed.PREFS, MODE_PRIVATE)
            .edit().putBoolean("onboarded_v1", true).apply();
        finish();
    }
}
