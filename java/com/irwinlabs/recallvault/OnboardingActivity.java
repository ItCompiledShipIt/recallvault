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
 * First-run welcome (v0.60.0), in the Sanctuary design.
 * Rebuilt v0.79.0 as a guided first run that finishes:
 * welcome, name, pair-by-scan, permissions, a first real
 * capture, and a done screen that reports the state it
 * leaves behind. Every step can be skipped and the flow
 * still lands on Done - nobody gets stuck in setup.
 * Shown once (pref onboarded_v1), after the app lock
 * clears; replayable any time from Settings.
 * Copy rule: only what the app truly does.
 */
public class OnboardingActivity extends Activity {

    private int step = 0;
    private boolean captureSaved = false;
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
        topRow.addView(Ui.text(this, "Step " + (step + 1) + " of 6", 12, Ui.DIM, true));
        View tsp = new View(this);
        tsp.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        topRow.addView(tsp);
        if (step < 5) {
            // Skipping a step moves forward, not out: the
            // run always reaches its Done screen (v0.79.0).
            TextView skip = Ui.text(this, "Skip", 13, Ui.WARN, true);
            skip.setClickable(true);
            skip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { step++; showStep(); }
            });
            topRow.addView(skip);
        }
        body.addView(topRow);
        LinearLayout prog = new LinearLayout(this);
        prog.setOrientation(LinearLayout.HORIZONTAL);
        prog.setLayoutParams(Ui.matchWrap(14, this));
        for (int i = 0; i < 6; i++) {
            View seg = new View(this);
            seg.setBackground(Ui.panelBg(i <= step ? Ui.ACCENT : Ui.PANEL_HI, 0, 999, this));
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, Ui.dp(this, 5), 1f);
            slp.setMargins(i == 0 ? 0 : Ui.dp(this, 4), 0, i == 5 ? 0 : Ui.dp(this, 4), 0);
            seg.setLayoutParams(slp);
            prog.addView(seg);
        }
        body.addView(prog);

        if (step == 0) stepWelcome();
        else if (step == 1) stepName();
        else if (step == 2) stepPair();
        else if (step == 3) stepPerms();
        else if (step == 4) stepCapture();
        else stepDone();
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

    /** Step 3 (v0.79.0): pair with the desktop by scanning
     *  its QR code. Nothing to type; the typed form stays
     *  one tap down for the rare case a scan can't reach. */
    private void stepPair() {
        body.addView(headline("Clio on your computer, too?"));
        body.addView(bodyText(
            "If Clio is open on your computer, its Preferences show a "
            + "QR code. Scan it and the two keep one shared vault - "
            + "over your home network only. No account, and nothing "
            + "leaves your house."));
        boolean paired = SyncClient.configured(this);
        TextView status = Ui.text(this, paired
            ? "Paired with " + SyncClient.addr(this) + " - your vaults keep each other up to date on their own."
            : "Not paired yet. You can do this any time in Settings, too.",
            14, Ui.DIM, false);
        status.setGravity(Gravity.CENTER);
        status.setLayoutParams(Ui.matchWrap(12, this));
        body.addView(status);
        body.addView(primary(paired ? "Continue" : "Scan the code",
            new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (SyncClient.configured(OnboardingActivity.this)) {
                        step = 3; showStep();
                    } else {
                        startActivityForResult(new android.content.Intent(
                            OnboardingActivity.this, ScanActivity.class), 942);
                    }
                }
            }));
        if (!paired) {
            TextView type = Ui.text(this, "Type it instead", 14, Ui.WARN, true);
            type.setGravity(Gravity.CENTER);
            type.setClickable(true);
            type.setLayoutParams(Ui.matchWrap(10, this));
            type.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { showPairTyping(); }
            });
            body.addView(type);
            TextView later = Ui.text(this, "Not now", 14, Ui.DIM, true);
            later.setGravity(Gravity.CENTER);
            later.setClickable(true);
            later.setLayoutParams(Ui.matchWrap(4, this));
            later.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { step = 3; showStep(); }
            });
            body.addView(later);
        }
    }

    private void showPairTyping() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), 0);
        final android.widget.EditText addrIn = Ui.input(this, "Address (like 192.168.1.10:8097)");
        addrIn.setText(SyncClient.addr(this));
        box.addView(addrIn);
        final android.widget.EditText keyIn = Ui.input(this, "Sync key (from the desktop's Preferences)");
        keyIn.setLayoutParams(Ui.matchWrap(10, this));
        box.addView(keyIn);
        new android.app.AlertDialog.Builder(this)
            .setTitle("Pair with desktop")
            .setView(box)
            .setPositiveButton("Pair", new android.content.DialogInterface.OnClickListener() {
                @Override public void onClick(android.content.DialogInterface d, int w) {
                    SyncClient.savePairing(OnboardingActivity.this,
                        addrIn.getText().toString(), keyIn.getText().toString());
                    new Thread(new Runnable() {
                        @Override public void run() {
                            SyncClient.syncNow(getApplicationContext());
                            runOnUiThread(new Runnable() {
                                @Override public void run() { showStep(); }
                            });
                        }
                    }).start();
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode,
                                      android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 942 && resultCode == RESULT_OK && step == 2) showStep();
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
            @Override public void onClick(View v) { step = 4; showStep(); }
        }));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (step == 3) showStep();
    }

    /** Step 5 (v0.79.0): the first memory, saved for real
     *  before the tour ends - the app has proven itself
     *  once before asking for anything else. */
    private void stepCapture() {
        body.addView(headline("Save your first memory."));
        body.addView(bodyText(
            "Anything at all - a plan, a bill, a thought you'd otherwise "
            + "lose. Say it plainly; Clio does the filing, and finds it "
            + "when you ask."));
        final android.widget.EditText in = new android.widget.EditText(this);
        in.setHint("Say it plainly - Clio does the filing");
        in.setMinLines(3);
        in.setGravity(Gravity.TOP | Gravity.START);
        in.setTextColor(Ui.TEXT);
        in.setHintTextColor(Ui.DIM);
        in.setTextSize(16);
        in.setTypeface(Ui.face(this, false));
        in.setBackground(Ui.panelBg(Ui.PANEL, 0, 16, this));
        in.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14));
        in.setLayoutParams(Ui.matchWrap(16, this));
        body.addView(in);
        body.addView(primary("Remember it", new View.OnClickListener() {
            @Override public void onClick(View v) {
                String text = in.getText().toString().trim();
                if (!text.isEmpty()) {
                    Db db = new Db(OnboardingActivity.this);
                    Item it = new Item();
                    it.type = "TEXT";
                    it.body = text;
                    it.createdAt = System.currentTimeMillis();
                    Extract.Facts f = Extract.analyze(text);
                    it.category = f.category;
                    it.title = Extract.suggestTitle(text, "Note");
                    it.reminderAt = f.dueDate;
                    StringBuilder am = new StringBuilder();
                    for (int i = 0; i < f.amounts.size(); i++) {
                        if (i > 0) am.append(',');
                        am.append(f.amounts.get(i));
                    }
                    it.amounts = am.toString();
                    db.insert(it);
                    captureSaved = true;
                }
                step = 5;
                showStep();
            }
        }));
    }

    /** Step 6 (v0.79.0): Done reports the state it leaves
     *  behind - then hands over the app. */
    private void stepDone() {
        body.addView(headline("You're set."));
        SharedPreferences prefs = getSharedPreferences(Embed.PREFS, MODE_PRIVATE);
        String name = prefs.getString("user_name", "").trim();
        if (!name.isEmpty()) {
            body.addView(moveRow(R.drawable.ic_person, Ui.MINT,
                "Hello, " + name, "Clio will greet you by name."));
        }
        boolean paired = SyncClient.configured(this);
        body.addView(moveRow(R.drawable.ic_check, Ui.MINT,
            paired ? "Computer paired" : "Phone-only, for now",
            paired ? "One vault, kept in step on both."
                   : "Pair any time: Settings, Desktop sync, Scan to pair."));
        body.addView(moveRow(R.drawable.ic_note, Ui.SAGE_BG,
            captureSaved ? "First memory filed" : "Capture is always one tap away",
            captureSaved ? "Ask for it in plain words, any time."
                         : "The + on the home screen takes voice, photos and text."));
        TextView note = Ui.text(this,
            "Clio's senses - voice transcription, smart search, her "
            + "answer-writing brain - are free on-device downloads in "
            + "Settings, whenever you want them. Nothing about you "
            + "leaves this phone unless you pair or share it.",
            12, Ui.DIM, false);
        note.setGravity(Gravity.CENTER);
        note.setLayoutParams(Ui.matchWrap(14, this));
        body.addView(note);
        // The lock offer rides the last step (it used to be
        // its own step): one quiet card, off unless wanted.
        LinearLayout lockCard = Ui.card(this);
        lockCard.setLayoutParams(Ui.matchWrap(6, this));
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
        body.addView(primary("Start using Clio", new View.OnClickListener() {
            @Override public void onClick(View v) { finishTour(); }
        }));
    }

    private void finishTour() {
        getSharedPreferences(Embed.PREFS, MODE_PRIVATE)
            .edit().putBoolean("onboarded_v1", true).apply();
        finish();
    }
}
