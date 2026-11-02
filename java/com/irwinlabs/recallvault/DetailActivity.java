package com.irwinlabs.recallvault;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

/** One memory, in full: image/audio/file, extracted facts, actions. */
public class DetailActivity extends Activity {

    private Db db;
    private Item item;
    private MediaPlayer player;
    private Button playBtn;
    private TextToSpeech tts;
    private boolean ttsReady;
    private boolean speaking;
    private Button listenBtn;
    private int pageIndex = 0;
    private File pendingPage;
    private static final int REQ_PAGE = 77;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        db = new Db(this);
        long id = getIntent().getLongExtra("id", -1);
        item = db.get(id);
        if (item == null) {
            Toast.makeText(this, "That memory is gone", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (AppLock.needsUnlock(this)) {
            startActivity(new android.content.Intent(this, LockActivity.class));
        } else {
            AppLock.noteActivity();
        }
        if (item != null) {
            Item fresh = db.get(item.id);
            if (fresh != null) { item = fresh; render(); }
        }
    }

    /** The locked face of a private memory (v0.46.0). */
    private void renderLocked() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(Ui.dp(this, 24), Ui.dp(this, 32), Ui.dp(this, 24), Ui.dp(this, 24));
        TextView h = Ui.text(this, "This memory is private", 20, Ui.TEXT, true);
        root.addView(h);
        TextView sub = Ui.text(this,
            "Its words stay hidden - in lists, in Ask, everywhere - until you unlock this one item.",
            13, Ui.DIM, false);
        sub.setLayoutParams(Ui.matchWrap(10, this));
        root.addView(sub);
        Button unlock = Ui.button(this, "Unlock", Ui.ACCENT, Ui.ACCENT_DARK_TEXT);
        unlock.setLayoutParams(Ui.matchWrap(14, this));
        unlock.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { promptItemUnlock(); }
        });
        root.addView(unlock);
        Button back = Ui.ghostButton(this, "Back");
        back.setLayoutParams(Ui.matchWrap(10, this));
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        root.addView(back);
        setContentView(root);
    }

    private android.os.CancellationSignal itemCancelSignal;

    /** Biometric/device-credential prompt for this one item. */
    private void promptItemUnlock() {
        if (android.os.Build.VERSION.SDK_INT < 28) {
            Toast.makeText(this, "This Android version cannot show the unlock prompt",
                Toast.LENGTH_LONG).show();
            return;
        }
        try {
            android.hardware.biometrics.BiometricPrompt.Builder b =
                new android.hardware.biometrics.BiometricPrompt.Builder(this)
                    .setTitle("Unlock this memory")
                    .setSubtitle("Your fingerprint or PIN");
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                b.setAllowedAuthenticators(
                    android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
                    | android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL);
            } else {
                b.setDeviceCredentialAllowed(true);
            }
            itemCancelSignal = new android.os.CancellationSignal();
            b.build().authenticate(itemCancelSignal, getMainExecutor(),
                new android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                    @Override public void onAuthenticationSucceeded(
                            android.hardware.biometrics.BiometricPrompt.AuthenticationResult r) {
                        AppLock.unlockItem(item.id);
                        render();
                    }
                    @Override public void onAuthenticationError(int code, CharSequence msg) {
                        if (code != 10 && code != 13) {
                            Toast.makeText(DetailActivity.this,
                                "Unlock did not go through", Toast.LENGTH_SHORT).show();
                        }
                    }
                });
        } catch (Exception e) {
            Toast.makeText(this, "Unlock is not available on this phone", Toast.LENGTH_LONG).show();
        }
    }

    private void render() {
        Ui.applyChrome(this);
        // Private memories (v0.46.0): the words stay hidden until
        // this one item is unlocked.
        if (Privacy.isPrivate(item) && !AppLock.isItemUnlocked(item.id)) {
            renderLocked();
            return;
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16));

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        LinearLayout bodyCol = new LinearLayout(this);
        bodyCol.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(bodyCol);
        root.addView(scroll, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView meta = Ui.text(this, item.category + "  -  " + Ui.typeLabel(item.type)
            + "  -  " + Ui.dateLine(item.createdAt), 12, Ui.ACCENT, true);
        bodyCol.addView(meta);

        TextView title = Ui.text(this, item.title, 22, Ui.TEXT, true);
        title.setLayoutParams(Ui.matchWrap(6, this));
        bodyCol.addView(title);

        // Facts pulled out of the text, in their own card.
        StringBuilder facts = new StringBuilder();
        if (item.reminderAt > 0) {
            facts.append("Date: ").append(Extract.formatDate(item.reminderAt))
                .append(" (").append(Extract.relativeDay(item.reminderAt, System.currentTimeMillis())).append(")\n");
        }
        if (!item.amounts.isEmpty()) facts.append("Amounts: ").append(item.amounts.replace(",", ", ")).append("\n");
        if (item.embeddingLocal != null && !item.embeddingLocal.isEmpty()) facts.append("Semantic index: embedded on this phone (EmbeddingGemma 2)\n");
        else if (LocalEmbed.isModelPresent(this)) facts.append("Semantic index: indexing in the background\n");
        else facts.append("Semantic index: not embedded yet (needs the on-device model - Settings)\n");
        if (facts.length() > 0) {
            LinearLayout factsCard = Ui.card(this);
            TextView fv = Ui.text(this, facts.toString().trim(), 13, Ui.ACCENT2, false);
            factsCard.addView(fv);
            bodyCol.addView(factsCard);
        }

        // Media / file preview.
        if (!item.filePath.isEmpty()) {
            File f = new File(item.filePath);
            if (item.mime != null && item.mime.startsWith("image/") && f.exists()) {
                // Multi-page scans (v0.20.0): page through every page,
                // and add more pages with the camera.
                final java.util.List<String> allPages = item.pagePaths();
                if (pageIndex >= allPages.size()) pageIndex = 0;
                Bitmap bmp = BitmapFactory.decodeFile(allPages.get(pageIndex));
                if (bmp != null) {
                    ImageView iv = new ImageView(this);
                    iv.setImageBitmap(bmp);
                    iv.setAdjustViewBounds(true);
                    iv.setLayoutParams(Ui.matchWrap(10, this));
                    bodyCol.addView(iv);
                }
                if (allPages.size() > 1) {
                    LinearLayout pager = new LinearLayout(this);
                    pager.setOrientation(LinearLayout.HORIZONTAL);
                    pager.setLayoutParams(Ui.matchWrap(6, this));
                    Button prev = Ui.ghostButton(this, "Previous");
                    prev.setLayoutParams(Ui.weight1(0, this));
                    prev.setEnabled(pageIndex > 0);
                    prev.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) { pageIndex--; render(); }
                    });
                    pager.addView(prev);
                    TextView pl = Ui.text(this, "Page " + (pageIndex + 1) + " of " + allPages.size(),
                        13, Ui.DIM, false);
                    pl.setGravity(android.view.Gravity.CENTER);
                    pl.setLayoutParams(Ui.weight1(8, this));
                    pager.addView(pl);
                    Button next = Ui.ghostButton(this, "Next");
                    next.setLayoutParams(Ui.weight1(8, this));
                    next.setEnabled(pageIndex < allPages.size() - 1);
                    next.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) { pageIndex++; render(); }
                    });
                    pager.addView(next);
                    bodyCol.addView(pager);
                }
                Button addPage = Ui.ghostButton(this, "Add a page (camera)");
                addPage.setLayoutParams(Ui.matchWrap(6, this));
                addPage.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { startAddPage(); }
                });
                bodyCol.addView(addPage);
            } else if (item.mime != null && item.mime.startsWith("audio/")) {
                playBtn = Ui.iconButton(this, "Play voice note", R.drawable.ic_play, Ui.PANEL_HI, Ui.ACCENT2);
                playBtn.setLayoutParams(Ui.matchWrap(10, this));
                playBtn.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { togglePlay(); }
                });
                bodyCol.addView(playBtn);
            } else {
                TextView fv = Ui.text(this, "File: " + f.getName(), 12, Ui.DIM, false);
                fv.setLayoutParams(Ui.matchWrap(8, this));
                bodyCol.addView(fv);
                Button open = Ui.button(this, "Open file", Ui.PANEL_HI, Ui.TEXT);
                open.setLayoutParams(Ui.matchWrap(6, this));
                open.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { openFile(); }
                });
                bodyCol.addView(open);
            }
        }

        String text = !item.body.isEmpty() ? item.body : item.extracted;
        if (text != null && !text.trim().isEmpty()) {
            TextView tv = Ui.text(this, text, 15, Ui.TEXT, false);
            tv.setLayoutParams(Ui.matchWrap(12, this));
            bodyCol.addView(tv);
        } else if (item.filePath.isEmpty()) {
            TextView tv = Ui.text(this, "(no text)", 14, Ui.DIM, false);
            tv.setLayoutParams(Ui.matchWrap(12, this));
            bodyCol.addView(tv);
        }

        // Machine-read text (OCR / PDF layer), when it adds words of its own.
        final boolean hasMachine = item.extracted != null && !item.extracted.isEmpty()
            && !item.extracted.equals(item.body);
        if (hasMachine) {
            TextView mh = Ui.sectionLabel(this, "Text read from the file");
            mh.setLayoutParams(Ui.matchWrap(16, this));
            bodyCol.addView(mh);
            TextView mv = Ui.text(this, item.extracted, 13, Ui.DIM, false);
            mv.setLayoutParams(Ui.matchWrap(4, this));
            bodyCol.addView(mv);
        }

        // Tap-the-facts (v0.44.0): numbers, emails, links, addresses
        // and tracking codes inside the words become actions.
        {
            String allWords = (item.body != null ? item.body : "") + "\n"
                + (item.extracted != null ? item.extracted : "");
            java.util.List<Facts.Fact> foundFacts = Facts.find(allWords);
            if (!foundFacts.isEmpty()) {
                TextView fh = Ui.sectionLabel(this, "Facts in this memory");
                fh.setLayoutParams(Ui.matchWrap(16, this));
                bodyCol.addView(fh);

                for (final Facts.Fact fact : foundFacts) {
                    Button fb = Ui.ghostButton(this, fact.label);
                    fb.setLayoutParams(Ui.matchWrap(6, this));
                    fb.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            try {
                                Intent i = new Intent(Intent.ACTION_VIEW,
                                    android.net.Uri.parse(fact.uri));
                                if (fact.kind == Facts.PHONE) i.setAction(Intent.ACTION_DIAL);
                                startActivity(i);
                            } catch (Exception e) {
                                Toast.makeText(DetailActivity.this,
                                    "No app can open that", Toast.LENGTH_SHORT).show();
                            }
                        }
                    });
                    fb.setOnLongClickListener(new View.OnLongClickListener() {
                        @Override public boolean onLongClick(View v) {
                            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                                getSystemService(CLIPBOARD_SERVICE);
                            if (cm != null) {
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("fact", fact.value));
                                Toast.makeText(DetailActivity.this, "Copied: " + fact.value,
                                    Toast.LENGTH_SHORT).show();
                            }
                            return true;
                        }
                    });
                    bodyCol.addView(fb);
                }
            }
        }

        // (Re)read machine text on demand.
        if (!item.filePath.isEmpty() && item.mime != null && item.mime.startsWith("image/")) {
            Button ocr = Ui.iconButton(this, hasMachine ? "Re-read text in this image (OCR)" : "Read text in this image (OCR)",
                R.drawable.ic_scan, Ui.PANEL_HI, Ui.ACCENT2);
            ocr.setLayoutParams(Ui.matchWrap(12, this));
            ocr.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { runOcr(); }
            });
            bodyCol.addView(ocr);
        } else if (!item.filePath.isEmpty()
                && ("application/pdf".equals(item.mime) || item.filePath.endsWith(".pdf"))) {
            Button pdf = Ui.iconButton(this, hasMachine ? "Re-read the PDF text" : "Read the PDF text",
                R.drawable.ic_doc, Ui.PANEL_HI, Ui.ACCENT2);
            pdf.setLayoutParams(Ui.matchWrap(12, this));
            pdf.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { runPdfExtract(); }
            });
            bodyCol.addView(pdf);
        }

        // Transcribe a recording on this phone (Whisper, on-device).
        if (!item.filePath.isEmpty() && item.mime != null && item.mime.startsWith("audio/")
                && new File(item.filePath).exists()) {
            Button tr = Ui.iconButton(this, "Transcribe recording",
                R.drawable.ic_transcribe, Ui.ACCENT, Ui.ACCENT_DARK_TEXT);
            tr.setLayoutParams(Ui.matchWrap(12, this));
            tr.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { runTranscribe(); }
            });
            bodyCol.addView(tr);
            TextView th = Ui.text(this,
                "Runs Whisper on this phone; the words become part of the memory and searchable. The audio never leaves the phone.",
                11, Ui.DIM, false);
            th.setLayoutParams(Ui.matchWrap(2, this));
            bodyCol.addView(th);
        }

        // Category correction chips.
        TextView catLabel = Ui.sectionLabel(this, "Filed under");
        catLabel.setLayoutParams(Ui.matchWrap(18, this));
        bodyCol.addView(catLabel);
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        for (final String cat : Extract.CATEGORIES) {
            Button ch = Ui.chip(this, cat, cat.equals(item.category));
            ch.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    item.category = cat;
                    db.update(item);
                    render();
                }
            });
            chips.addView(ch);
        }
        hsv.addView(chips);
        bodyCol.addView(hsv);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setLayoutParams(Ui.matchWrap(14, this));
        Button edit = Ui.ghostButton(this, "Edit");
        edit.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        edit.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent e = new Intent(DetailActivity.this, EditorActivity.class);
                e.putExtra("id", item.id);
                startActivity(e);
            }
        });
        actions.addView(edit);
        Button share = Ui.ghostButton(this, "Share");
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        slp.setMargins(Ui.dp(this, 8), 0, 0, 0);
        share.setLayoutParams(slp);
        share.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { shareOut(); }
        });
        actions.addView(share);
        Button del = Ui.iconButton(this, "Delete", R.drawable.ic_delete, Ui.PANEL_HI, Ui.DANGER);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        dlp.setMargins(Ui.dp(this, 8), 0, 0, 0);
        del.setLayoutParams(dlp);
        del.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirmDelete(); }
        });
        actions.addView(del);
        root.addView(actions);

        // Brain summary (v0.53.0): Gemma 4 writes the plain-words
        // version of this memory, on the phone.
        final String detailText = (item.body != null && !item.body.isEmpty())
            ? item.body : (item.extracted != null ? item.extracted : "");
        if (GemmaBrain.isModelPresent(this) && !detailText.trim().isEmpty()) {
            Button sumBtn = Ui.ghostButton(this, "Summarize with Clio");
            sumBtn.setLayoutParams(Ui.matchWrap(8, this));
            sumBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { summarizeWithBrain(detailText); }
            });
            root.addView(sumBtn);
        }

        // Listen (v0.19.0): the phone reads the memory aloud -
        // built-in text-to-speech, nothing leaves the device.
        listenBtn = Ui.ghostButton(this, "Listen - hear this memory");
        listenBtn.setLayoutParams(Ui.matchWrap(8, this));
        listenBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleListen(); }
        });
        root.addView(listenBtn);

        // Linked memories (v0.23.0): both-way links, tap to jump.
        java.util.Set<Long> linked = Links.parse(item.links);
        if (!linked.isEmpty()) {
            LinearLayout lCard = Ui.card(this);
            lCard.setLayoutParams(Ui.matchWrap(10, this));
            lCard.addView(Ui.sectionLabel(this, "Linked memories"));
            for (Long lid : linked) {
                final Item other = db.get(lid);
                if (other == null) continue;
                Button row = Ui.ghostButton(this, other.title);
                row.setLayoutParams(Ui.matchWrap(4, this));
                row.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        Intent i = new Intent(DetailActivity.this, DetailActivity.class);
                        i.putExtra("id", other.id);
                        startActivity(i);
                    }
                });
                lCard.addView(row);
            }
            root.addView(lCard);
        }
        Button linkBtn = Ui.ghostButton(this, "Link to another memory");
        linkBtn.setLayoutParams(Ui.matchWrap(8, this));
        linkBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickLinkTarget(); }
        });
        root.addView(linkBtn);

        // Meeting recap (v0.38.0; checkable to-dos v0.52.0):
        // decided / to do / key dates / money, at the top of
        // the meeting's page. To-do checks persist per meeting
        // in prefs - the transcript itself is never rewritten.
        if ("Meetings".equals(item.category)) {
            String rtext = (item.extracted != null && !item.extracted.isEmpty())
                ? item.extracted : (item.body != null ? item.body : "");
            final Recap.Parts rp = Recap.parts(rtext);
            if (!rp.isEmpty()) {
                LinearLayout rCard = Ui.card(this);
                rCard.addView(Ui.sectionLabel(this, "// Meeting ledger"));
                if (!rp.decisions.isEmpty()) {
                    TextView dh = Ui.text(this, "DECIDED", 11, Ui.ACCENT2, true);
                    dh.setLayoutParams(Ui.matchWrap(6, this));
                    rCard.addView(dh);
                    for (String d : rp.decisions) {
                        TextView dt = Ui.text(this, "- " + d, 13, Ui.TEXT, false);
                        dt.setLayoutParams(Ui.matchWrap(2, this));
                        rCard.addView(dt);
                    }
                }
                if (!rp.todos.isEmpty()) {
                    final android.content.SharedPreferences rprefs =
                        getSharedPreferences(Embed.PREFS, MODE_PRIVATE);
                    LinearLayout headRow = new LinearLayout(this);
                    headRow.setOrientation(LinearLayout.HORIZONTAL);
                    headRow.setLayoutParams(Ui.matchWrap(8, this));
                    TextView th = Ui.text(this, "TO DO", 11, Ui.ACCENT2, true);
                    th.setLayoutParams(new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                    headRow.addView(th);
                    final TextView tcount = Ui.text(this, "", 11, Ui.DIM, true);
                    headRow.addView(tcount);
                    rCard.addView(headRow);
                    final int[] doneCount = {0};
                    for (final Actions.Action a : rp.todos) {
                        final String key = "recap_done_" + item.id + "_" + a.text.hashCode();
                        boolean done = rprefs.getBoolean(key, false);
                        if (done) doneCount[0]++;
                        final android.widget.CheckBox cb = new android.widget.CheckBox(this);
                        cb.setText(a.text + (a.dueMs > 0
                            ? " (due " + Extract.formatDate(a.dueMs) + ")" : ""));
                        cb.setTextColor(done ? Ui.DIM : Ui.TEXT);
                        cb.setTextSize(13);
                        cb.setChecked(done);
                        cb.setLayoutParams(Ui.matchWrap(2, this));
                        cb.setOnCheckedChangeListener(
                            new android.widget.CompoundButton.OnCheckedChangeListener() {
                                @Override public void onCheckedChanged(
                                        android.widget.CompoundButton b, boolean on) {
                                    rprefs.edit().putBoolean(key, on).apply();
                                    cb.setTextColor(on ? Ui.DIM : Ui.TEXT);
                                    doneCount[0] += on ? 1 : -1;
                                    tcount.setText(doneCount[0] + "/" + rp.todos.size() + " DONE");
                                }
                            });
                        rCard.addView(cb);
                    }
                    tcount.setText(doneCount[0] + "/" + rp.todos.size() + " DONE");
                }
                if (rp.datesLine != null) {
                    TextView dl = Ui.text(this, rp.datesLine, 12, Ui.DIM, false);
                    dl.setLayoutParams(Ui.matchWrap(6, this));
                    rCard.addView(dl);
                }
                if (rp.moneyLine != null) {
                    TextView ml = Ui.text(this, rp.moneyLine, 12, Ui.DIM, false);
                    ml.setLayoutParams(Ui.matchWrap(2, this));
                    rCard.addView(ml);
                }
                root.addView(rCard);
            }
        }

        // Find action items (v0.29.0): pull the commitments out of a
        // meeting's words and file them as their own memories.
        if ("Meetings".equals(item.category)) {
            Button actBtn = Ui.ghostButton(this, "Find action items");
            actBtn.setLayoutParams(Ui.matchWrap(8, this));
            actBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    String text = (item.extracted != null && !item.extracted.isEmpty())
                        ? item.extracted : (item.body != null ? item.body : "");
                    int made = Actions.fileActions(db, item, text);
                    Toast.makeText(DetailActivity.this,
                        made > 0 ? "Filed " + made + " action item" + (made == 1 ? "" : "s")
                                 + " - see the Reminders tab"
                                 : "No new action items found",
                        Toast.LENGTH_LONG).show();
                    if (made > 0) render();
                }
            });
            root.addView(actBtn);
        }

        // Add to calendar (v0.24.0): dated memories can become real
        // calendar events - the calendar app does the saving.
        if (item.reminderAt > 0) {
            Button calBtn = Ui.ghostButton(this, "Add to calendar");
            calBtn.setLayoutParams(Ui.matchWrap(8, this));
            calBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { addToCalendar(); }
            });
            root.addView(calBtn);
        }

        // Private toggle (v0.46.0): hide this memory's words until
        // the item itself is unlocked.
        {
            Button privBtn = Ui.ghostButton(this,
                Privacy.isPrivate(item) ? "Make public" : "Make private");
            privBtn.setLayoutParams(Ui.matchWrap(8, this));
            privBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    item.isPrivate = Privacy.isPrivate(item) ? 0 : 1;
                    db.update(item);
                    if (item.isPrivate == 1) AppLock.unlockItem(item.id); // keep viewing this session
                    Toast.makeText(DetailActivity.this,
                        item.isPrivate == 1
                            ? "Private - hidden until you unlock this item"
                            : "Public again",
                        Toast.LENGTH_LONG).show();
                    render();
                }
            });
            root.addView(privBtn);
        }

        // Snooze (v0.41.0): push the due date out a day or a week.
        if (item.reminderAt > 0 && item.handledAt == 0) {
            Button snzBtn = Ui.ghostButton(this, "Snooze reminder");
            snzBtn.setLayoutParams(Ui.matchWrap(8, this));
            snzBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    String[] labels = new String[Snooze.CHOICES.length];
                    for (int i = 0; i < labels.length; i++) {
                        labels[i] = Snooze.choiceLabel(Snooze.CHOICES[i]);
                    }
                    new android.app.AlertDialog.Builder(DetailActivity.this)
                        .setTitle("Snooze this reminder")
                        .setItems(labels, new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface d, int which) {
                                item.reminderAt = Snooze.snoozedUntil(item.reminderAt, Snooze.CHOICES[which]);
                                db.update(item);
                                Toast.makeText(DetailActivity.this,
                                    "Snoozed to " + Extract.formatDate(item.reminderAt),
                                    Toast.LENGTH_SHORT).show();
                                render();
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
                }
            });
            root.addView(snzBtn);
        }

        Button back = Ui.iconButton(this, "Back", R.drawable.ic_back, Ui.PANEL_HI, Ui.DIM);
        back.setLayoutParams(Ui.matchWrap(8, this));
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        root.addView(back);

        setContentView(root);
    }

    private void runOcr() {
        Toast.makeText(this, "Reading text...", Toast.LENGTH_SHORT).show();
        Ocr.recognize(new java.io.File(item.filePath), new Ocr.Callback() {
            @Override public void onText(String text) {
                if (text == null || text.trim().isEmpty()) {
                    Toast.makeText(DetailActivity.this, "No readable text found in this image",
                        Toast.LENGTH_LONG).show();
                    return;
                }
                applyMachineText(text.trim());
            }
            @Override public void onError(String message) {
                Toast.makeText(DetailActivity.this, "OCR failed: " + message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void runPdfExtract() {
        Toast.makeText(this, "Reading PDF...", Toast.LENGTH_SHORT).show();
        final java.io.File f = new java.io.File(item.filePath);
        new Thread(new Runnable() {
            @Override public void run() {
                final PdfText.Result res = PdfText.read(f);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (res.text == null || res.text.isEmpty()) {
                            Toast.makeText(DetailActivity.this,
                                "No readable words in this PDF - no text layer, and OCR found none on its pages",
                                Toast.LENGTH_LONG).show();
                            return;
                        }
                        if (res.ocr) {
                            Toast.makeText(DetailActivity.this,
                                "Scanned PDF - text came from OCR of its pages", Toast.LENGTH_SHORT).show();
                        }
                        applyMachineText(res.text);
                    }
                });
            }
        }, "pdf-read").start();
    }

    private void runTranscribe() {
        final File f = new File(item.filePath);
        Toast.makeText(this, "Transcribing on this phone - long recordings take a while...",
            Toast.LENGTH_LONG).show();
        Transcribe.transcribe(this, f, new Transcribe.Callback() {
            @Override public void onText(final String text) {
                runOnUiThread(new Runnable() {
                    @Override public void run() { applyMachineText(text); }
                });
            }
            @Override public void onError(final String message) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        Toast.makeText(DetailActivity.this,
                            "Transcription failed: " + message, Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    /** Store machine text, and let it contribute dates/amounts it carries. */
    private void applyMachineText(String text) {
        item.extracted = text;
        item.embedding = ""; // words changed: re-embed next round
        item.embeddingLocal = "";
        Extract.Facts f = Extract.analyze(item.title + "\n" + item.body + "\n" + text);
        if (f.dueDate > 0 && item.reminderAt <= 0) item.reminderAt = f.dueDate;
        if (!f.amounts.isEmpty() && item.amounts.isEmpty()) {
            StringBuilder am = new StringBuilder();
            for (int i = 0; i < f.amounts.size(); i++) {
                if (i > 0) am.append(',');
                am.append(f.amounts.get(i));
            }
            item.amounts = am.toString();
        }
        if ("Notes".equals(item.category) && !"Notes".equals(f.category)) item.category = f.category;
        db.update(item);
        Embed.embedPending(this, db);
        Toast.makeText(this, "Read " + text.length() + " chars - now searchable", Toast.LENGTH_SHORT).show();
        render();
    }

    private void togglePlay() {
        try {
            if (player != null && player.isPlaying()) {
                player.pause();
                playBtn.setText("Play voice note");
                return;
            }
            if (player == null) {
                player = new MediaPlayer();
                player.setDataSource(item.filePath);
                player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                    @Override public void onCompletion(MediaPlayer mp) {
                        if (playBtn != null) playBtn.setText("Play voice note");
                    }
                });
                player.prepare();
            }
            player.start();
            playBtn.setText("Pause");
        } catch (Exception e) {
            Toast.makeText(this, "Could not play that recording", Toast.LENGTH_SHORT).show();
        }
    }

    private void openFile() {
        try {
            Uri uri = ShareProvider.uriFor(new File(item.filePath));
            // Files outside captures/ are served by path through a generic view:
            // use the provider only for captures; otherwise hand the system a file uri is
            // blocked on modern Android, so fall back to telling the user where it lives.
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, item.mime == null || item.mime.isEmpty() ? "*/*" : item.mime);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "No app can open this file type", Toast.LENGTH_SHORT).show();
        }
    }

    /** Send this memory to another app - text, facts, and its file (v0.15.0). */
    /** Brain summary dialog (v0.53.0): generation on a worker
     *  thread, the dialog fills in when the words land. */
    private void summarizeWithBrain(final String text) {
        final TextView out = Ui.text(this, "Clio is thinking...", 14, Ui.TEXT, false);
        out.setPadding(Ui.dp(this, 20), Ui.dp(this, 16), Ui.dp(this, 20), Ui.dp(this, 8));
        final android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
            .setTitle("Summary")
            .setView(out)
            .setPositiveButton("Close", null)
            .create();
        dlg.show();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String clipped = text.length() > 6000 ? text.substring(0, 6000) : text;
                    final String answer = GemmaBrain.generate(DetailActivity.this,
                        "You are Clio, keeper of this person's memories. You summarize their saved memories. Plain words, the key facts first, "
                        + "dates and amounts kept exact. Never add anything that is not in the text.",
                        "Summarize this memory in 2-4 short sentences, then a line starting with "
                        + "\"Key points:\" listing the facts that matter.\n\n" + clipped);
                    runOnUiThread(new Runnable() {
                        @Override public void run() { if (dlg.isShowing()) out.setText(answer); }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (dlg.isShowing()) out.setText("Clio unavailable: " + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private void shareOut() {
        StringBuilder text = new StringBuilder(item.title);
        if (!item.body.isEmpty()) text.append("\n\n").append(item.body);
        String machine = item.extracted == null ? "" : item.extracted.trim();
        if (!machine.isEmpty() && !machine.equals(item.body.trim())) {
            text.append("\n\n").append(machine);
        }
        Intent s = new Intent(Intent.ACTION_SEND);
        File f = item.filePath == null || item.filePath.isEmpty() ? null : new File(item.filePath);
        if (f != null && f.exists()) {
            try {
                Uri u = ShareProvider.uriFor(f);
                s.setType(item.mime == null || item.mime.isEmpty() ? "*/*" : item.mime);
                s.putExtra(Intent.EXTRA_STREAM, u);
                s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception e) {
                s.setType("text/plain");
            }
        } else {
            s.setType("text/plain");
        }
        s.putExtra(Intent.EXTRA_SUBJECT, item.title);
        s.putExtra(Intent.EXTRA_TEXT, text.toString());
        startActivity(Intent.createChooser(s, "Share memory"));
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
            .setTitle("Move this memory to trash?")
            .setMessage(item.title + "\n\nYou can restore it within 30 days from Settings - Trash.")
            .setPositiveButton("Move to trash", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    db.trash(item.id);
                    Toast.makeText(DetailActivity.this, "Moved to trash", Toast.LENGTH_SHORT).show();
                    finish();
                }
            })
            .setNegativeButton("Keep", null)
            .show();
    }

    /** Add-a-page: camera into captures/, then filed under pages/ (v0.20.0). */
    private void startAddPage() {
        // Samsung's camera refuses the capture intent when the caller
        // lacks the CAMERA runtime permission - ask first, like the
        // main Scan flow does.
        if (checkSelfPermission(android.Manifest.permission.CAMERA)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.CAMERA}, REQ_PERM_PAGE);
            return;
        }
        File dir = new File(getFilesDir(), "captures");
        if (!dir.exists()) dir.mkdirs();
        pendingPage = new File(dir, "cap_" + System.currentTimeMillis() + ".jpg");
        Intent i = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
        i.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, ShareProvider.uriFor(pendingPage));
        i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_PAGE);
        } catch (Exception e) {
            Toast.makeText(this, "No camera app found", Toast.LENGTH_SHORT).show();
        }
    }

    private static final int REQ_PERM_PAGE = 78;

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] grants) {
        super.onRequestPermissionsResult(req, perms, grants);
        if (req == REQ_PERM_PAGE) {
            boolean ok = grants.length > 0
                && grants[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
            if (ok) startAddPage();
            else Toast.makeText(this, "Camera permission is needed to add a page",
                Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req != REQ_PAGE || result != RESULT_OK || pendingPage == null) return;
        final File shot = pendingPage;
        pendingPage = null;
        if (!shot.exists() || shot.length() == 0) {
            Toast.makeText(this, "That page did not come through", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "Reading the new page...", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    File dir = new File(getFilesDir(), "pages");
                    if (!dir.exists()) dir.mkdirs();
                    File dest = new File(dir, "page_" + System.currentTimeMillis() + ".jpg");
                    java.io.InputStream in = new java.io.FileInputStream(shot);
                    java.io.OutputStream out = new java.io.FileOutputStream(dest);
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    in.close();
                    out.close();
                    try { shot.delete(); } catch (Exception e) {}
                    String ocr = Ocr.recognizeSync(dest);
                    Item fresh = db.get(item.id);
                    if (fresh == null) return;
                    fresh.pages = (fresh.pages == null || fresh.pages.isEmpty())
                        ? dest.getAbsolutePath()
                        : fresh.pages + "\n" + dest.getAbsolutePath();
                    if (ocr != null && !ocr.trim().isEmpty()) {
                        fresh.extracted = (fresh.extracted == null || fresh.extracted.isEmpty())
                            ? ocr : fresh.extracted + "\n" + ocr;
                    }
                    fresh.embedding = "";
                    fresh.embeddingLocal = "";
                    db.update(fresh);
                    Embed.embedPending(DetailActivity.this, db);
                    item = fresh;
                    pageIndex = item.pagePaths().size() - 1;
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            Toast.makeText(DetailActivity.this, "Page added", Toast.LENGTH_SHORT).show();
                            render();
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            Toast.makeText(DetailActivity.this,
                                "Could not add that page: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }, "add-page").start();
    }

    /** Hand this memory's due date to the calendar app as a new event (v0.24.0). */
    private void addToCalendar() {
        Intent i = new Intent(Intent.ACTION_INSERT);
        i.setData(android.provider.CalendarContract.Events.CONTENT_URI);
        i.putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, item.reminderAt);
        i.putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, item.reminderAt + 3600000L);
        i.putExtra(android.provider.CalendarContract.Events.TITLE, item.title);
        String desc = item.body != null && !item.body.trim().isEmpty() ? item.body : item.extracted;
        if (desc != null && !desc.trim().isEmpty()) {
            i.putExtra(android.provider.CalendarContract.Events.DESCRIPTION, desc.trim());
        }
        try {
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "No calendar app found", Toast.LENGTH_SHORT).show();
        }
    }

    /** Link picker: every other memory; linked ones unlink on tap (v0.23.0). */
    private void pickLinkTarget() {
        java.util.List<Item> others = new java.util.ArrayList<Item>();
        for (Item x : db.all()) if (x.id != item.id) others.add(x);
        if (others.isEmpty()) {
            Toast.makeText(this, "Nothing else in the vault to link yet", Toast.LENGTH_LONG).show();
            return;
        }
        final java.util.List<Item> choices = others;
        String[] labels = new String[choices.size()];
        for (int i = 0; i < choices.size(); i++) {
            Item x = choices.get(i);
            labels[i] = (Links.contains(item.links, x.id) ? "Linked - " : "") + x.title;
        }
        new AlertDialog.Builder(this)
            .setTitle("Link to another memory")
            .setItems(labels, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int which) {
                    Item target = choices.get(which);
                    boolean on = !Links.contains(item.links, target.id);
                    db.setLinked(item, target, on);
                    Item fresh = db.get(item.id);
                    if (fresh != null) item = fresh;
                    Toast.makeText(DetailActivity.this,
                        on ? "Linked" : "Link removed", Toast.LENGTH_SHORT).show();
                    render();
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    /** Listen button: speak the memory, or stop mid-sentence (v0.19.0). */
    private void toggleListen() {
        if (speaking && tts != null) {
            tts.stop();
            setSpeaking(false);
            return;
        }
        if (tts == null) {
            tts = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
                @Override public void onInit(int status) {
                    ttsReady = status == TextToSpeech.SUCCESS;
                    if (ttsReady) {
                        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                            @Override public void onStart(String id) {}
                            @Override public void onDone(String id) {
                                runOnUiThread(new Runnable() {
                                    @Override public void run() { setSpeaking(false); }
                                });
                            }
                            @Override public void onError(String id) {
                                runOnUiThread(new Runnable() {
                                    @Override public void run() { setSpeaking(false); }
                                });
                            }
                        });
                        speakNow();
                    } else {
                        Toast.makeText(DetailActivity.this,
                            "Text-to-speech is not available on this phone", Toast.LENGTH_LONG).show();
                    }
                }
            });
            return;
        }
        if (ttsReady) speakNow();
    }

    private void setSpeaking(boolean on) {
        speaking = on;
        if (listenBtn != null) {
            listenBtn.setText(on ? "Stop" : "Listen - hear this memory");
        }
    }

    private void speakNow() {
        StringBuilder sb = new StringBuilder(item.title);
        String words = item.body != null && !item.body.trim().isEmpty() ? item.body : item.extracted;
        if (words != null && !words.trim().isEmpty() && !words.trim().equals(item.title.trim())) {
            sb.append(". ").append(words.trim());
        }
        if (item.reminderAt > 0) {
            sb.append(". Due ").append(Extract.formatDate(item.reminderAt)).append(".");
        }
        if (item.amounts != null && !item.amounts.isEmpty()) {
            sb.append(" Amount: ").append(item.amounts.replace(",", ", ")).append(".");
        }
        setSpeaking(true);
        tts.speak(sb.toString(), TextToSpeech.QUEUE_FLUSH, null, "rv-listen");
    }

    @Override
    protected void onDestroy() {
        if (player != null) {
            try { player.release(); } catch (Exception e) {}
            player = null;
        }
        if (tts != null) {
            try { tts.stop(); tts.shutdown(); } catch (Exception e) {}
            tts = null;
        }
        super.onDestroy();
    }
}
