package com.irwinlabs.recallvault;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * RecallVault - a local-first personal memory vault (MemX-style).
 * Save notes, voice memos, photos, scans and documents in one tap;
 * everything is auto-categorized on-device, dates/amounts are pulled
 * out for reminders, and Ask answers in plain words with the source
 * file attached. Everything - semantic search, transcription, OCR -
 * runs on this phone; no server is involved (v0.4.0).
 */
public class MainActivity extends Activity {

    private static final int REQ_PICK_IMAGE = 101;
    private static final int REQ_PICK_DOC = 102;
    private static final int REQ_CAMERA = 103;
    private static final int REQ_PERM_AUDIO = 201;
    private static final int REQ_PERM_CAMERA = 202;
    private static final int REQ_PERM_NOTIF = 203;
    private static final int REQ_EXPORT_DOC = 901;
    private static final int REQ_IMPORT_DOC = 902;
    private static final int REQ_TREE = 903;
    private static final int REQ_EXPORT_PLAIN = 904;
    private static final int REQ_EXPORT_EXPENSES = 907;
    private static final int REQ_CHECK_BACKUP = 905;
    private static final int REQ_IMPORT_FOLDER = 906;
    private static final int REQ_TREE_INGEST = 907;
    private static final int REQ_PERM_MEDIA = 204;

    private Db db;
    private final Handler ui = new Handler(Looper.getMainLooper());

    /** Quick-capture hand-off from the QS tile (v0.35.0). */
    public static final String EXTRA_QUICK_CAPTURE = "quick_capture";
    public static final String EXTRA_ASK_VOICE = "open_ask_voice";
    private boolean pendingQuickVoice = false;
    private boolean pendingAskVoice = false;

    private LinearLayout content;
    private String tab = "VAULT"; // VAULT | ASK | REMINDERS
    private String categoryFilter = "All";
    private String vaultQuery = "";
    private boolean vaultByMonth = false;
    private File pendingCapture;
    private String pendingCaptureType = "SCAN";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        db = new Db(this);
        PdfText.init(this);
        handleShareIntent(getIntent());
        if (getIntent() != null
                && "voice".equals(getIntent().getStringExtra(EXTRA_QUICK_CAPTURE))) {
            pendingQuickVoice = true;
        }
        if (getIntent() != null && getIntent().getBooleanExtra(EXTRA_ASK_VOICE, false)) {
            pendingAskVoice = true;
        }
        buildShell();
        render();
        // Warm the on-device semantic index in the background
        // (no-op until the model is downloaded in Settings).
        Embed.embedPending(this, db);
        // Due-date notifications: daily check + one pass now.
        Remind.enqueueAll(this);
        // One-time at-rest encryption rewrite of older rows (v0.16.0).
        new Thread(new Runnable() {
            @Override public void run() { Db.migrateEncryption(MainActivity.this); }
        }, "vault-migrate").start();
        // Expiry backfill (v0.30.0): rows saved before the radar existed
        // get their expiry derived once, through the normal update path.
        new Thread(new Runnable() {
            @Override public void run() {
                android.content.SharedPreferences p =
                    getSharedPreferences(Embed.PREFS, MODE_PRIVATE);
                if (p.getLong("expiry_backfilled_at", 0) == 0) {
                    Db d2 = new Db(MainActivity.this);
                    for (Item it : d2.all()) {
                        if (it.expiryAt <= 0 && Expiry.expiryOf(it) > 0) d2.update(it);
                    }
                    p.edit().putLong("expiry_backfilled_at", System.currentTimeMillis()).apply();
                }
            }
        }, "expiry-backfill").start();
        // Trash purge: anything deleted over 30 days ago goes for good (v0.25.0).
        new Thread(new Runnable() {
            @Override public void run() { purgeOldTrash(); }
        }, "trash-purge").start();
        // Automatic ingest, opportunistically on open (v0.27.0): the
        // daily worker also runs it, but Doze can defer that for days.
        new Thread(new Runnable() {
            @Override public void run() { FileIngest.runIfEnabled(MainActivity.this); }
        }, "ingest-open").start();
        Remind.checkNow(this);
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_PERM_NOTIF);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (AppLock.needsUnlock(this)) {
            startActivity(new Intent(this, LockActivity.class));
        } else {
            AppLock.noteActivity();
            // Quick capture (v0.35.0): the recorder opens only once
            // the lock has cleared - the tile never bypasses it.
            if (pendingQuickVoice) {
                pendingQuickVoice = false;
                startVoice();
            }
            // Ask widget (v0.47.0): land on Ask and open the mic,
            // again only once the lock has cleared.
            if (pendingAskVoice) {
                pendingAskVoice = false;
                tab = "ASK";
                render();
                Intent i = new Intent(this, VoiceActivity.class);
                i.putExtra("mode", "ask");
                startActivityForResult(i, 301);
            }
        }
        render();
        Remind.checkNow(this);
    }

    @Override
    protected void onPause() {
        AppLock.noteActivity();
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleShareIntent(intent);
        if (intent != null && "voice".equals(intent.getStringExtra(EXTRA_QUICK_CAPTURE))) {
            pendingQuickVoice = true;
        }
        if (intent != null && intent.getBooleanExtra(EXTRA_ASK_VOICE, false)) {
            pendingAskVoice = true;
        }
    }

    private void handleShareIntent(Intent intent) {
        if (intent == null) return;
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            // A shared file (photo, PDF) files itself through the same
            // pipeline as a picked file (v0.10.0).
            Uri stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (stream != null) {
                importUri(stream, false);
                return;
            }
            if ("text/plain".equals(intent.getType())) {
                String shared = intent.getStringExtra(Intent.EXTRA_TEXT);
                if (shared != null && !shared.trim().isEmpty()) {
                    Intent e = new Intent(this, EditorActivity.class);
                    e.putExtra("prefill_body", shared);
                    startActivity(e);
                }
            }
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            // Several photos at once: file them all headlessly (OCR +
            // facts + embeddings run; no editor per photo).
            java.util.ArrayList<Uri> streams = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (streams != null && !streams.isEmpty()) {
                importUrisHeadless(streams);
            }
        }
    }

    // ---------- shell ----------

    private LinearLayout tabsBar;
    private TextView headerCount;

    private void buildShell() {
        Ui.applyChrome(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 10));
        header.addView(Ui.brandMark(this));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.setMargins(Ui.dp(this, 12), 0, 0, 0);
        titles.setLayoutParams(tlp);
        titles.addView(Ui.text(this, "RecallVault", 18, Ui.TEXT, true));
        header.addView(titles);
        View hSpacer = new View(this);
        hSpacer.setLayoutParams(new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(hSpacer);
        headerCount = Ui.text(this, "", 12, Ui.DIM, false);
        header.addView(headerCount);
        root.addView(header);

        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 24));
        scroll.addView(content);
        root.addView(scroll);

        tabsBar = new LinearLayout(this);
        tabsBar.setOrientation(LinearLayout.HORIZONTAL);
        tabsBar.setBackgroundColor(Ui.PANEL);
        tabsBar.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 10));
        root.addView(tabsBar);
        renderTabs();

        setContentView(root);
    }

    private void renderTabs() {
        if (tabsBar == null) return;
        tabsBar.removeAllViews();
        tabsBar.addView(Ui.tabItem(this, R.drawable.ic_vault, "Vault", "VAULT".equals(tab),
            new View.OnClickListener() { @Override public void onClick(View v) { tab = "VAULT"; render(); } }));
        tabsBar.addView(Ui.tabItem(this, R.drawable.ic_search, "Ask", "ASK".equals(tab),
            new View.OnClickListener() { @Override public void onClick(View v) { tab = "ASK"; render(); } }));
        // The Reminders tab carries a live badge (v0.48.0): how
        // many deadlines are overdue or land inside 48h.
        int dueSoon = 0;
        try {
            long nowB = System.currentTimeMillis();
            for (Item it : db.withReminders()) {
                if (it.handledAt == 0 && it.reminderAt > 0
                        && it.reminderAt <= nowB + 48L * 3600 * 1000) dueSoon++;
            }
        } catch (Exception ignored) {}
        String remLabel = dueSoon > 0 ? "Reminders - " + dueSoon : "Reminders";
        tabsBar.addView(Ui.tabItem(this, R.drawable.ic_bell, remLabel, "REMINDERS".equals(tab),
            new View.OnClickListener() { @Override public void onClick(View v) { tab = "REMINDERS"; render(); } }));
    }

    private void render() {
        if (content == null) return;
        if (headerCount != null) {
            int n = db.count();
            headerCount.setText(n + (n == 1 ? " memory" : " memories"));
        }
        renderTabs();
        content.removeAllViews();
        if ("ASK".equals(tab)) renderAsk();
        else if ("REMINDERS".equals(tab)) renderReminders();
        else renderVault();
        VaultWidget.refresh(this); // keep the home-screen widget honest (v0.14.0)
    }

    // ---------- VAULT ----------

    private void renderVault() {
        content.addView(captureDock());
        attentionStrip();

        EditText search = Ui.input(this, "Filter your vault...");
        search.setText(vaultQuery);
        search.setLayoutParams(Ui.matchWrap(14, this));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                vaultQuery = s.toString();
                renderListOnly();
            }
        });
        content.addView(search);

        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setLayoutParams(Ui.matchWrap(10, this));
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        String[] cats = {"All", "Bills", "Receipts", "Wallet", "People", "Meetings", "Notes"};
        for (final String cat : cats) {
            Button ch = Ui.chip(this, cat, cat.equals(categoryFilter));
            ch.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { categoryFilter = cat; render(); }
            });
            chips.addView(ch);
        }
        Button byMonth = Ui.chip(this, "By month", vaultByMonth);
        byMonth.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { vaultByMonth = !vaultByMonth; render(); }
        });
        chips.addView(byMonth);
        hsv.addView(chips);
        content.addView(hsv);

        // Monthly report card (v0.36.0): last month's report, offered
        // once when a new month starts.
        if ("All".equals(categoryFilter) && vaultQuery.trim().isEmpty()) {
            java.util.Calendar cal = java.util.Calendar.getInstance();
            final String curKey = cal.get(java.util.Calendar.YEAR) + "-"
                + cal.get(java.util.Calendar.MONTH);
            final android.content.SharedPreferences rp =
                getSharedPreferences(Embed.PREFS, MODE_PRIVATE);
            if (!curKey.equals(rp.getString("report_seen_month", ""))) {
                java.util.Calendar lm = (java.util.Calendar) cal.clone();
                lm.add(java.util.Calendar.MONTH, -1);
                final int ly = lm.get(java.util.Calendar.YEAR);
                final int lmo = lm.get(java.util.Calendar.MONTH);
                final java.util.List<String> rl =
                    Report.lines(db.all(), ly, lmo, System.currentTimeMillis());
                if (rl != null) {
                    LinearLayout rCard = Ui.card(this);
                    rCard.addView(Ui.sectionLabel(this,
                        "Your " + Report.monthName(ly, lmo) + " in the vault"));
                    TextView rt = Ui.text(this, rl.get(0), 13, Ui.TEXT, false);
                    rt.setLayoutParams(Ui.matchWrap(4, this));
                    rCard.addView(rt);
                    Button read = Ui.ghostButton(this, "Read the report");
                    read.setLayoutParams(Ui.matchWrap(6, this));
                    read.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            StringBuilder sb = new StringBuilder();
                            for (String l : rl) {
                                if (sb.length() > 0) sb.append('\n');
                                sb.append(l);
                            }
                            new android.app.AlertDialog.Builder(MainActivity.this)
                                .setTitle("Your " + Report.monthName(ly, lmo) + " in the vault")
                                .setMessage(sb.toString())
                                .setPositiveButton("Close", null)
                                .show();
                        }
                    });
                    rCard.addView(read);
                    Button dismiss = Ui.ghostButton(this, "Dismiss");
                    dismiss.setLayoutParams(Ui.matchWrap(4, this));
                    dismiss.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            rp.edit().putString("report_seen_month", curKey).apply();
                            render();
                        }
                    });
                    rCard.addView(dismiss);
                    content.addView(rCard);
                }
            }
        }

        // On this day (v0.22.0): memories from this date in earlier years.
        if ("All".equals(categoryFilter) && vaultQuery.trim().isEmpty()) {
            java.util.List<Item> otd = Resurface.onThisDay(db.all(), System.currentTimeMillis());
            if (!otd.isEmpty()) {
                LinearLayout oCard = Ui.card(this);
                oCard.addView(Ui.sectionLabel(this, "On this day"));
                for (final Item it : otd) {
                    Button row = Ui.ghostButton(this,
                        Resurface.yearsAgoLabel(it, System.currentTimeMillis()) + " - " + it.title);
                    row.setLayoutParams(Ui.matchWrap(4, this));
                    row.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) { openDetail(it.id); }
                    });
                    oCard.addView(row);
                }
                content.addView(oCard);
            }
        }

        // Photo look-alikes (v0.34.0): near-duplicate photos grouped
        // by their vision vectors, one tap each to review.
        if ("All".equals(categoryFilter) && vaultQuery.trim().isEmpty()) {
            final java.util.List<java.util.List<Item>> groups = NearDupes.groups(db.all());
            if (!groups.isEmpty()) {
                int photos = 0;
                for (java.util.List<Item> g : groups) photos += g.size();
                LinearLayout dCard = Ui.card(this);
                dCard.addView(Ui.sectionLabel(this, "Photo look-alikes"));
                TextView dt = Ui.text(this,
                    groups.size() + (groups.size() == 1 ? " group" : " groups") + ", " + photos
                        + " photos that look like the same shot. Tap one to open it - "
                        + "tidying up is your call, from its Delete button.",
                    12, Ui.DIM, false);
                dt.setLayoutParams(Ui.matchWrap(4, this));
                dCard.addView(dt);
                int gi = 0;
                for (java.util.List<Item> g : groups) {
                    gi++;
                    TextView gl = Ui.text(this, "Group " + gi + " - " + g.size() + " photos",
                        12, Ui.ACCENT2, true);
                    gl.setLayoutParams(Ui.matchWrap(6, this));
                    dCard.addView(gl);
                    for (final Item it : g) {
                        Button row = Ui.ghostButton(this,
                            Extract.formatDate(it.createdAt) + " - " + it.title);
                        row.setLayoutParams(Ui.matchWrap(4, this));
                        row.setOnClickListener(new View.OnClickListener() {
                            @Override public void onClick(View v) { openDetail(it.id); }
                        });
                        dCard.addView(row);
                    }
                }
                content.addView(dCard);
            }
        }

        LinearLayout listHolder = new LinearLayout(this);
        listHolder.setOrientation(LinearLayout.VERTICAL);
        listHolder.setTag("listHolder");
        content.addView(listHolder);
        fillList(listHolder);
    }

    private void renderListOnly() {
        for (int i = 0; i < content.getChildCount(); i++) {
            View v = content.getChildAt(i);
            if ("listHolder".equals(v.getTag())) {
                fillList((LinearLayout) v);
                return;
            }
        }
        render();
    }

    private void fillList(LinearLayout holder) {
        holder.removeAllViews();
        List<Item> items = db.byCategory(categoryFilter);
        String q = vaultQuery.trim().toLowerCase();
        int shown = 0;
        long lastMonthStamp = -1;
        long lastDayStamp = -1;
        long nowMs = System.currentTimeMillis();
        for (Item it : items) {
            if (!q.isEmpty() && !it.searchableText().toLowerCase().contains(q)
                    && !it.title.toLowerCase().contains(q)) continue;
            if (vaultByMonth && (lastMonthStamp == -1 || !Resurface.sameMonth(lastMonthStamp, it.createdAt))) {
                TextView mh = Ui.sectionLabel(this, Resurface.monthLabel(it.createdAt));
                mh.setLayoutParams(Ui.matchWrap(10, this));
                holder.addView(mh);
                lastMonthStamp = it.createdAt;
            }
            // Day-grouped feed (v0.48.0 layout): the vault reads
            // like a journal - Today, Yesterday, then dated days -
            // instead of one undifferentiated stack.
            if (!vaultByMonth) {
                long day = DayGroup.startOfDay(it.createdAt);
                if (day != lastDayStamp) {
                    TextView dh = Ui.sectionLabel(this, DayGroup.label(it.createdAt, nowMs));
                    dh.setLayoutParams(Ui.matchWrap(12, this));
                    holder.addView(dh);
                    lastDayStamp = day;
                }
            }
            holder.addView(itemCard(it));
            shown++;
        }
        if (shown == 0) {
            if (db.count() == 0) {
                holder.addView(Ui.emptyState(this, R.drawable.ic_vault, "Nothing saved yet",
                    "Capture your first note, photo, scan or document above - "
                    + "it will be filed, dated and searchable here."));
            } else {
                holder.addView(Ui.emptyState(this, R.drawable.ic_search, "Nothing matches",
                    "No memory matches this filter. Try different words or another category."));
            }
        } else {
            TextView n = Ui.text(this, shown + (shown == 1 ? " memory" : " memories"), 12, Ui.DIM, false);
            n.setLayoutParams(Ui.matchWrap(10, this));
            holder.addView(n);
        }
    }

    /**
     * The capture dock (v0.48.0 layout): one hero action - voice,
     * the way most memories start - plus a Note shortcut and the
     * full capture sheet behind one tap. The old seven-tile grid
     * card ate the top of the home screen; it lives in the sheet
     * now, unchanged.
     */
    private LinearLayout captureDock() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutParams(Ui.matchWrap(4, this));
        Button voice = Ui.iconButton(this, "Voice note", R.drawable.ic_mic, Ui.ACCENT, Ui.ACCENT_DARK_TEXT);
        voice.setLayoutParams(Ui.weight1(0, this));
        voice.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startVoice(); }
        });
        row.addView(voice);
        Button note = Ui.iconButton(this, "Note", R.drawable.ic_note, Ui.PANEL_HI, Ui.TEXT);
        note.setLayoutParams(Ui.weight1(8, this));
        note.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, EditorActivity.class));
            }
        });
        row.addView(note);
        Button more = Ui.ghostButton(this, "+");
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
            Ui.dp(this, 52), LinearLayout.LayoutParams.MATCH_PARENT);
        mlp.setMargins(Ui.dp(this, 8), 0, 0, 0);
        more.setLayoutParams(mlp);
        more.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showCaptureSheet(); }
        });
        row.addView(more);
        return row;
    }

    private void showCaptureSheet() {
        new android.app.AlertDialog.Builder(this)
            .setTitle("Save something")
            .setView(captureCard())
            .setNegativeButton("Close", null)
            .show();
    }

    /**
     * The attention strip (v0.48.0 layout): when deadlines need
     * the user, the home screen says so up top - one line, one
     * tap to the Reminders tab - instead of the count hiding
     * behind a tab.
     */
    private void attentionStrip() {
        if (!"All".equals(categoryFilter) || !vaultQuery.trim().isEmpty()) return;
        long now = System.currentTimeMillis();
        java.util.List<Item> due = new java.util.ArrayList<Item>();
        for (Item it : db.withReminders()) {
            if (it.handledAt == 0 && it.reminderAt > 0
                    && it.reminderAt <= now + 7L * 24 * 3600 * 1000) due.add(it);
        }
        if (due.isEmpty()) return;
        java.util.Collections.sort(due, new java.util.Comparator<Item>() {
            @Override public int compare(Item a, Item b) {
                return Long.compare(a.reminderAt, b.reminderAt);
            }
        });
        Item first = due.get(0);
        LinearLayout strip = Ui.pressCard(this);
        strip.setBackground(Ui.rippleBg(this, Ui.PANEL, Ui.WARN, 16, 0x2EFFFFFF));
        strip.setLayoutParams(Ui.matchWrap(12, this));
        TextView t = Ui.text(this,
            due.size() + (due.size() == 1 ? " deadline needs" : " deadlines need")
                + " you - first: " + Privacy.displayTitle(first)
                + " (" + Extract.relativeDay(first.reminderAt, now) + ")",
            13, Ui.TEXT, true);
        strip.addView(t);
        strip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { tab = "REMINDERS"; render(); }
        });
        content.addView(strip);
    }

    private LinearLayout captureCard() {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.text(this, "Save something", 16, Ui.TEXT, true));
        card.addView(Ui.text(this, "One tap. It files itself - no folders.", 12, Ui.DIM, false));

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setLayoutParams(Ui.matchWrap(12, this));
        row1.addView(tileInRow(Ui.tile(this, R.drawable.ic_note, "Note", Ui.ACCENT,
            new View.OnClickListener() {
                @Override public void onClick(View v) {
                    startActivity(new Intent(MainActivity.this, EditorActivity.class));
                }
            }), false));
        row1.addView(tileInRow(Ui.tile(this, R.drawable.ic_mic, "Voice", Ui.ACCENT2,
            new View.OnClickListener() {
                @Override public void onClick(View v) { startVoice(); }
            }), true));
        row1.addView(tileInRow(Ui.tile(this, R.drawable.ic_photo, "Photo", Ui.WARN,
            new View.OnClickListener() {
                @Override public void onClick(View v) { pickImage(); }
            }), true));
        row1.addView(tileInRow(Ui.tile(this, R.drawable.ic_scan, "Scan", Ui.ACCENT,
            new View.OnClickListener() {
                @Override public void onClick(View v) { startCamera("SCAN"); }
            }), true));
        card.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setLayoutParams(Ui.matchWrap(8, this));
        row2.addView(tileInRow(Ui.tile(this, R.drawable.ic_doc, "Document", Ui.ACCENT2,
            new View.OnClickListener() {
                @Override public void onClick(View v) { pickDocument(); }
            }), false));
        row2.addView(tileInRow(Ui.tile(this, R.drawable.ic_meeting, "Meeting", Ui.WARN,
            new View.OnClickListener() {
                @Override public void onClick(View v) { startVoiceMode("meeting"); }
            }), true));
        row2.addView(tileInRow(Ui.tile(this, R.drawable.ic_settings, "Settings", Ui.DIM,
            new View.OnClickListener() {
                @Override public void onClick(View v) { showSettings(); }
            }), true));
        View spacer = new View(this);
        spacer.setLayoutParams(Ui.weight1(8, this));
        row2.addView(spacer);
        card.addView(row2);
        return card;
    }

    private View tileInRow(View tile, boolean withLeftMargin) {
        tile.setLayoutParams(Ui.weight1(withLeftMargin ? 8 : 0, this));
        return tile;
    }

    private int typeIcon(String type) {
        if ("VOICE".equals(type)) return R.drawable.ic_mic;
        if ("PHOTO".equals(type)) return R.drawable.ic_photo;
        if ("SCAN".equals(type)) return R.drawable.ic_scan;
        if ("DOCUMENT".equals(type)) return R.drawable.ic_doc;
        return R.drawable.ic_note;
    }

    private int typeTint(String type) {
        if ("VOICE".equals(type)) return Ui.ACCENT2;
        if ("PHOTO".equals(type) || "SCAN".equals(type)) return Ui.WARN;
        return Ui.ACCENT;
    }

    private LinearLayout itemCard(final Item it) {
        LinearLayout card = Ui.pressCard(this);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(Ui.badge(this, typeIcon(it.type), typeTint(it.type)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        clp.setMargins(Ui.dp(this, 12), 0, 0, 0);
        col.setLayoutParams(clp);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        TextView cat = Ui.text(this, it.category.toUpperCase(java.util.Locale.US), 10, Ui.ACCENT, true);
        cat.setLetterSpacing(0.1f);
        top.addView(cat);
        TextView typ = Ui.text(this, "  -  " + Ui.typeLabel(it.type), 11, Ui.DIM, false);
        top.addView(typ);
        col.addView(top);

        TextView t = Ui.text(this, Privacy.displayTitle(it), 15, Ui.TEXT, true);
        t.setLayoutParams(Ui.matchWrap(3, this));
        t.setMaxLines(2);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(t);

        String snip = Privacy.isPrivate(it) ? "" : Ask.snippet(it, "");
        if (!snip.isEmpty()) {
            TextView s = Ui.text(this, snip, 13, Ui.DIM, false);
            s.setLayoutParams(Ui.matchWrap(2, this));
            s.setMaxLines(2);
            s.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(s);
        }
        StringBuilder meta = new StringBuilder();
        meta.append(Ui.dateLine(it.createdAt));
        if (!it.amounts.isEmpty() && !Privacy.isPrivate(it)) meta.append("  -  ").append(it.amounts.replace(",", ", "));
        if (it.reminderAt > 0) {
            meta.append("  -  due ").append(Extract.formatDate(it.reminderAt))
                .append(" (").append(Extract.relativeDay(it.reminderAt, System.currentTimeMillis())).append(")");
        }
        TextView m = Ui.text(this, meta.toString(), 12, Ui.DIM, false);
        m.setLayoutParams(Ui.matchWrap(3, this));
        col.addView(m);

        row.addView(col);
        card.addView(row);
        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openDetail(it.id); }
        });
        return card;
    }

    private void openDetail(long id) {
        Intent i = new Intent(this, DetailActivity.class);
        i.putExtra("id", id);
        startActivity(i);
    }

    // ---------- ASK ----------

    private EditText askInput;
    private LinearLayout askResults;
    /** Earlier questions this session, newest first (v0.48.0). */
    private final java.util.List<String> askEarlier = new java.util.ArrayList<String>();
    private String lastAnsweredQuery = null;

    private void renderAsk() {
        TextView h = Ui.text(this, "Ask your vault", 18, Ui.TEXT, true);
        content.addView(h);
        TextView sub = Ui.text(this,
            "Plain words. Try: when is my electricity bill due?  -  "
            + "Answers cite the memory they came from. Semantic search uses "
            + "EmbeddingGemma 2 on this phone when the model is downloaded "
            + "(Settings). Keyword search always works.",
            12, Ui.DIM, false);
        sub.setLayoutParams(Ui.matchWrap(4, this));
        content.addView(sub);

        askInput = Ui.input(this, "What are you looking for?");
        askInput.setLayoutParams(Ui.matchWrap(12, this));
        content.addView(askInput);

        Button go = Ui.iconButton(this, "Ask", R.drawable.ic_search, Ui.ACCENT, Ui.ACCENT_DARK_TEXT);
        go.setLayoutParams(Ui.matchWrap(10, this));
        go.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { runAsk(); }
        });
        content.addView(go);

        Button speak = Ui.iconButton(this, "Ask out loud", R.drawable.ic_mic, Ui.PANEL_HI, Ui.ACCENT2);
        speak.setLayoutParams(Ui.matchWrap(8, this));
        speak.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(MainActivity.this, VoiceActivity.class);
                i.putExtra("mode", "ask");
                startActivityForResult(i, 301);
            }
        });
        content.addView(speak);

        // People & places (v0.31.0): the names the vault has noticed -
        // one tap lines up everything about that name.
        java.util.List<Entities.Entity> ents = Entities.top(Privacy.visible(db.all()), 8);
        if (!ents.isEmpty()) {
            TextView pl = Ui.sectionLabel(this, "People & places");
            pl.setLayoutParams(Ui.matchWrap(14, this));
            content.addView(pl);
            TextView ph = Ui.text(this, "Everything about a name, in one place:",
                12, Ui.DIM, false);
            content.addView(ph);
            for (final Entities.Entity e : ents) {
                Button chip = Ui.ghostButton(this,
                    e.name + "  (" + e.itemCount + (e.itemCount == 1 ? " memory)" : " memories)"));
                chip.setLayoutParams(Ui.matchWrap(6, this));
                chip.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { showPersonCard(e); }
                });
                content.addView(chip);
            }
        }

        // Earlier questions (v0.48.0): Ask reads as a conversation -
        // what you already asked sits one tap away, above the new
        // answer, instead of vanishing when the next question lands.
        if (!askEarlier.isEmpty()) {
            TextView el = Ui.sectionLabel(this, "Earlier questions");
            el.setLayoutParams(Ui.matchWrap(14, this));
            content.addView(el);
            for (final String eq : askEarlier) {
                Button row = Ui.ghostButton(this, eq);
                row.setLayoutParams(Ui.matchWrap(6, this));
                row.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        askInput.setText(eq);
                        runAsk();
                    }
                });
                content.addView(row);
            }
        }

        askResults = new LinearLayout(this);
        askResults.setOrientation(LinearLayout.VERTICAL);
        content.addView(askResults);
    }

    /** The last question Ask answered, for follow-ups (v0.32.0). */
    private String lastAskQuery = null;

    private void runAsk() {
        String raw = askInput.getText().toString().trim();
        if (raw.isEmpty()) return;
        // Follow-ups (v0.32.0): "and November?" rewrites itself
        // against the last question - and the rewrite is shown in
        // the box, so what was understood is never a guess.
        String resolved = FollowUp.resolve(lastAskQuery, raw, System.currentTimeMillis());
        final String q = resolved != null ? resolved : raw;
        if (resolved != null) askInput.setText(resolved);
        lastAskQuery = q;
        askResults.removeAllViews();
        final TextView status = Ui.text(this, "Searching...", 13, Ui.DIM, false);
        status.setLayoutParams(Ui.matchWrap(12, this));
        askResults.addView(status);

        // Everything is on-device: semantic search via the phone's own
        // EmbeddingGemma 2 when its model is downloaded, keyword search
        // as the floor that always works. No network is involved.
        tryLocalAsk(q);
    }

    private void tryLocalAsk(final String q) {
        if (!LocalEmbed.isModelPresent(this)) {
            showAskResults(q, null, 0);
            return;
        }
        TextView thinking = Ui.text(this, "Thinking on this phone...", 13, Ui.DIM, false);
        thinking.setLayoutParams(Ui.matchWrap(12, this));
        if (askResults != null) { askResults.removeAllViews(); askResults.addView(thinking); }
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    LocalEmbed.embedPendingLocal(MainActivity.this, db);
                    final float[] vec = LocalEmbed.embedSync(MainActivity.this, q);
                    ui.post(new Runnable() { @Override public void run() { showAskResults(q, vec, 2); } });
                } catch (Exception e) {
                    ui.post(new Runnable() { @Override public void run() { showAskResults(q, null, 0); } });
                }
            }
        }, "ask-local").start();
    }

    /** mode: 2 = semantic on this phone, 0 = keyword only. */
    private void showAskResults(String q, float[] vec, int mode) {
        if (askResults == null) return;
        askResults.removeAllViews();
        // Conversation shape (v0.48.0): the question sits in its
        // own bubble above the answer, and the previous question
        // joins the Earlier list for next time.
        if (lastAnsweredQuery != null && !lastAnsweredQuery.equals(q)
                && !askEarlier.contains(lastAnsweredQuery)) {
            askEarlier.add(0, lastAnsweredQuery);
            while (askEarlier.size() > 6) askEarlier.remove(askEarlier.size() - 1);
        }
        lastAnsweredQuery = q;
        TextView bubble = Ui.text(this, q, 14, Ui.TEXT, true);
        bubble.setBackground(Ui.panelBg(Ui.PANEL_HI, Ui.ACCENT, 14, this));
        bubble.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        bubble.setLayoutParams(Ui.matchWrap(12, this));
        askResults.addView(bubble);
        List<Ask.Result> results = Ask.search(Privacy.visible(db.all()), q, vec, true);
        TextView modeView = Ui.text(this,
            mode == 2 ? "Semantic + keyword search (EmbeddingGemma 2 on this phone)"
                : "Keyword search (download the on-device model in Settings for semantic search)",
            11, Ui.DIM, false);
        modeView.setLayoutParams(Ui.matchWrap(12, this));
        askResults.addView(modeView);

        // Answers that add up: for set questions ("how much do I owe
        // this month"), lead with the total across every match.
        // Pattern questions ("is this higher than usual") win first.
        Ask.Summary sum = Patterns.answer(Privacy.visible(db.all()), q, System.currentTimeMillis());
        if (sum == null) sum = Entities.answer(Privacy.visible(db.all()), q);
        if (sum == null) sum = Report.answer(Privacy.visible(db.all()), q, System.currentTimeMillis());
        if (sum == null) sum = Forecast.answer(Privacy.visible(db.all()), q, System.currentTimeMillis());
        if (sum == null) sum = Receipts.answer(Privacy.visible(db.all()), q, System.currentTimeMillis());
        if (sum == null) sum = Expiry.answer(Privacy.visible(db.all()), q, System.currentTimeMillis());
        if (sum == null) sum = Resurface.reviewAnswer(Privacy.visible(db.all()), q, System.currentTimeMillis());
        if (sum == null) sum = Ask.summarize(Privacy.visible(db.all()), q, vec, System.currentTimeMillis());
        if (sum != null) {
            LinearLayout sumCard = Ui.pressCard(this);
            sumCard.setBackground(Ui.rippleBg(this, Ui.PANEL_HI, Ui.ACCENT2, 16, 0x2EFFFFFF));
            TextView st = Ui.text(this, sum.text, 16, Ui.TEXT, true);
            sumCard.addView(st);
            TextView sh = Ui.text(this, "Across your whole vault - the matches are below.",
                11, Ui.DIM, false);
            sh.setLayoutParams(Ui.matchWrap(4, this));
            sumCard.addView(sh);
            askResults.addView(sumCard);
        }

        if (results.isEmpty()) {
            TextView none = Ui.text(this,
                "No match in your vault yet. Save it first - then you can ask for it.",
                14, Ui.DIM, false);
            none.setLayoutParams(Ui.matchWrap(8, this));
            askResults.addView(none);
            return;
        }
        boolean first = true;
        for (final Ask.Result r : results) {
            LinearLayout card = Ui.pressCard(this);
            if (first) {
                card.setBackground(Ui.rippleBg(this, Ui.PANEL_HI, Ui.ACCENT, 16, 0x2EFFFFFF));
                TextView a = Ui.text(this, r.answer, 16, Ui.TEXT, true);
                card.addView(a);
                first = false;
            } else {
                TextView a = Ui.text(this, r.answer, 14, Ui.TEXT, false);
                card.addView(a);
            }
            TextView src = Ui.text(this,
                "Source: " + r.item.title + "  -  " + r.item.category + "  -  " + Ui.dateLine(r.item.createdAt)
                    + (r.viaPhoto ? "  -  matched by what's in the photo" : ""),
                12, Ui.ACCENT2, false);
            src.setLayoutParams(Ui.matchWrap(6, this));
            card.addView(src);
            card.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { openDetail(r.item.id); }
            });
            askResults.addView(card);
        }
    }

    // ---------- REMINDERS ----------

    private void renderReminders() {
        TextView h = Ui.text(this, "Reminders", 18, Ui.TEXT, true);
        content.addView(h);
        TextView sub = Ui.text(this,
            "Dates pulled out of what you saved - bills, renewals, expiries. "
            + "Tap one to open the memory behind it.", 12, Ui.DIM, false);
        sub.setLayoutParams(Ui.matchWrap(4, this));
        content.addView(sub);

        List<Item> items = db.withReminders();
        // At-a-glance counts (v0.48.0 layout): the shape of the
        // week before the detail starts.
        {
            long nowR = System.currentTimeMillis();
            int overdue = 0, today = 0, week = 0;
            for (Item it : items) {
                if (it.handledAt != 0 || it.reminderAt <= 0) continue;
                if (it.reminderAt < DayGroup.startOfDay(nowR)) overdue++;
                else if (it.reminderAt < DayGroup.startOfDay(nowR) + 24L * 3600 * 1000) today++;
                else if (it.reminderAt <= nowR + 7L * 24 * 3600 * 1000) week++;
            }
            if (overdue + today + week > 0) {
                StringBuilder sb = new StringBuilder();
                if (overdue > 0) sb.append(overdue).append(" overdue");
                if (today > 0) { if (sb.length() > 0) sb.append("  -  "); sb.append(today).append(" due today"); }
                if (week > 0) { if (sb.length() > 0) sb.append("  -  "); sb.append(week).append(" more this week"); }
                TextView counts = Ui.text(this, sb.toString(), 13,
                    overdue > 0 ? Ui.WARN : Ui.ACCENT2, true);
                counts.setLayoutParams(Ui.matchWrap(8, this));
                content.addView(counts);
            }
        }
        Ask.Summary digest = Ask.dueDigest(items, System.currentTimeMillis());
        if (digest != null) {
            LinearLayout dCard = Ui.card(this);
            dCard.setBackground(Ui.rippleBg(this, Ui.PANEL_HI, Ui.ACCENT2, 16, 0x2EFFFFFF));
            TextView dl = Ui.sectionLabel(this, "Due this week");
            dCard.addView(dl);
            TextView dt = Ui.text(this, digest.text, 15, Ui.TEXT, true);
            dt.setLayoutParams(Ui.matchWrap(4, this));
            dCard.addView(dt);
            content.addView(dCard);
        }
        // Patterns (v0.17.0): recurring bills and the month ahead.
        java.util.List<Patterns.Recurring> pats =
            Patterns.analyze(db.all(), System.currentTimeMillis());
        if (!pats.isEmpty()) {
            LinearLayout pCard = Ui.card(this);
            pCard.addView(Ui.sectionLabel(this, "Patterns"));
            for (final Patterns.Recurring r : pats) {
                TextView pt = Ui.text(this, Patterns.line(r), 13, Ui.TEXT, false);
                pt.setLayoutParams(Ui.matchWrap(3, this));
                pt.setClickable(true);
                pt.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { showTrendDialog(r); }
                });
                pCard.addView(pt);
            }
            TextView pHint = Ui.text(this, "Tap a bill to see its trend.", 11, Ui.DIM, false);
            pHint.setLayoutParams(Ui.matchWrap(2, this));
            pCard.addView(pHint);
            long proj = Patterns.projectedMonthlyCents(pats);
            if (proj > 0) {
                TextView pp = Ui.text(this,
                    "Recurring bills run about " + Ask.formatMoney(proj) + " a month.",
                    13, Ui.ACCENT2, true);
                pp.setLayoutParams(Ui.matchWrap(6, this));
                pCard.addView(pp);
            }
            content.addView(pCard);
        }
        // Bills forecast (v0.39.0): what's coming over the next three
        // months - real dated bills plus projected recurrences.
        {
            final java.util.List<Forecast.Entry> fc =
                Forecast.forecast(db.all(), System.currentTimeMillis());
            if (!fc.isEmpty()) {
                LinearLayout fCard = Ui.card(this);
                fCard.addView(Ui.sectionLabel(this, "What's coming"));
                java.util.Calendar cal = java.util.Calendar.getInstance();
                for (int m = 0; m < 3; m++) {
                    java.util.Calendar mc = (java.util.Calendar) cal.clone();
                    mc.add(java.util.Calendar.MONTH, m);
                    long total = Forecast.monthTotalCents(fc,
                        mc.get(java.util.Calendar.YEAR), mc.get(java.util.Calendar.MONTH));
                    if (total <= 0) continue;
                    TextView mt = Ui.text(this,
                        Report.monthName(mc.get(java.util.Calendar.YEAR),
                            mc.get(java.util.Calendar.MONTH))
                            + ": about " + Ask.formatMoney(total) + " expected",
                        13, Ui.ACCENT2, true);
                    mt.setLayoutParams(Ui.matchWrap(3, this));
                    fCard.addView(mt);
                }
                int shownF = 0;
                for (Forecast.Entry e : fc) {
                    if (shownF++ >= 5) break;
                    TextView fe = Ui.text(this, Forecast.line(e), 13, Ui.TEXT, false);
                    fe.setLayoutParams(Ui.matchWrap(3, this));
                    fCard.addView(fe);
                }
                if (fc.size() > 5) {
                    Button full = Ui.ghostButton(this, "Full forecast (" + fc.size() + ")");
                    full.setLayoutParams(Ui.matchWrap(6, this));
                    full.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) { showForecastDialog(fc); }
                    });
                    fCard.addView(full);
                }
                content.addView(fCard);
            }
        }
        // Expiry radar (v0.30.0): things that stop being valid, soonest
        // first - expired and next-90-days shown, later ones counted.
        java.util.List<Item> expiring = Expiry.expiring(db.all());
        if (!expiring.isEmpty()) {
            long nowMs = System.currentTimeMillis();
            long horizon = nowMs + 90L * 24 * 3600 * 1000;
            int later = 0;
            TextView eh = Ui.sectionLabel(this, "Expiring");
            eh.setLayoutParams(Ui.matchWrap(14, this));
            boolean headerAdded = false;
            for (final Item e : expiring) {
                if (e.expiryAt > horizon) { later++; continue; }
                if (!headerAdded) { content.addView(eh); headerAdded = true; }
                LinearLayout eCard = Ui.pressCard(this);
                boolean past = e.expiryAt < nowMs;
                TextView eWhen = Ui.text(this,
                    (past ? "Expired " : "Expires ") + Extract.formatDate(e.expiryAt)
                        + "  (" + Extract.relativeDay(e.expiryAt, nowMs) + ")",
                    14, past ? Ui.DANGER : Ui.WARN, true);
                eCard.addView(eWhen);
                TextView eTitle = Ui.text(this, e.title, 15, Ui.TEXT, false);
                eTitle.setLayoutParams(Ui.matchWrap(2, this));
                eCard.addView(eTitle);
                eCard.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { openDetail(e.id); }
                });
                content.addView(eCard);
            }
            if (later > 0) {
                TextView more = Ui.text(this,
                    "Plus " + later + " more expiring later - ask \"what expires\" for the full list.",
                    12, Ui.DIM, false);
                more.setLayoutParams(Ui.matchWrap(4, this));
                content.addView(more);
            }
        }
        // Action items (v0.29.0): commitments pulled out of meetings,
        // shown open or handled regardless of whether they carry a date.
        java.util.List<Item> actionItems = db.byCategory("Actions");
        java.util.List<Item> openActions = new java.util.ArrayList<Item>();
        for (Item a : actionItems) if (a.handledAt <= 0) openActions.add(a);
        if (!actionItems.isEmpty()) {
            TextView ah = Ui.sectionLabel(this, "Action items from meetings");
            ah.setLayoutParams(Ui.matchWrap(14, this));
            content.addView(ah);
            java.util.List<Item> shown = new java.util.ArrayList<Item>(openActions);
            for (Item a : actionItems) if (a.handledAt > 0) shown.add(a);
            for (final Item a : shown) {
                final boolean aHandled = a.handledAt > 0;
                LinearLayout aCard = Ui.pressCard(this);
                if (aHandled) aCard.setAlpha(0.55f);
                TextView aWhen = Ui.text(this,
                    a.reminderAt > 0
                        ? Extract.formatDate(a.reminderAt) + "  (" + Extract.relativeDay(a.reminderAt, System.currentTimeMillis()) + ")"
                        : "No date set",
                    13, aHandled ? Ui.DIM : Ui.ACCENT2, true);
                aCard.addView(aWhen);
                TextView aTitle = Ui.text(this, a.title, 15, aHandled ? Ui.DIM : Ui.TEXT, false);
                aTitle.setLayoutParams(Ui.matchWrap(2, this));
                aCard.addView(aTitle);
                Button ab = Ui.ghostButton(this, aHandled ? "Undo - still open" : "Mark done");
                ab.setLayoutParams(Ui.matchWrap(8, this));
                ab.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        a.handledAt = aHandled ? 0 : System.currentTimeMillis();
                        db.update(a);
                        render();
                    }
                });
                aCard.addView(ab);
                aCard.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { openDetail(a.id); }
                });
                content.addView(aCard);
            }
        }
        if (items.isEmpty()) {
            if (actionItems.isEmpty()) {
                content.addView(Ui.emptyState(this, R.drawable.ic_bell, "No reminders yet",
                    "Save a bill or a document with a due date and it will land here, "
                    + "with a notification before it is due."));
            }
            return;
        }
        long now = System.currentTimeMillis();
        // Open reminders first (due-date order), handled ones after.
        for (int pass = 0; pass < 2; pass++) {
          if (pass == 1) {
            boolean anyHandled = false;
            for (Item x : items) if (x.handledAt > 0) { anyHandled = true; break; }
            if (!anyHandled) break;
            TextView hh = Ui.sectionLabel(this, "Handled");
            hh.setLayoutParams(Ui.matchWrap(14, this));
            content.addView(hh);
          }
          for (final Item it : items) {
            final boolean handled = it.handledAt > 0;
            if ((pass == 1) != handled) continue;
            LinearLayout card = Ui.pressCard(this);
            if (handled) card.setAlpha(0.55f);
            boolean overdue = !handled && it.reminderAt < now - 24L * 60 * 60 * 1000;
            TextView when = Ui.text(this,
                Extract.formatDate(it.reminderAt) + "  (" + Extract.relativeDay(it.reminderAt, now) + ")"
                    + (handled ? "  -  done " + Extract.relativeDay(it.handledAt, now) : ""),
                14, handled ? Ui.DIM : (overdue ? Ui.DANGER : Ui.WARN), true);
            card.addView(when);
            TextView t = Ui.text(this, Privacy.displayTitle(it), 15, handled ? Ui.DIM : Ui.TEXT, false);
            t.setLayoutParams(Ui.matchWrap(2, this));
            card.addView(t);
            if (!it.amounts.isEmpty() && !Privacy.isPrivate(it)) {
                card.addView(Ui.text(this, it.amounts.replace(",", ", "), 12, Ui.DIM, false));
            }
            Button hb = Ui.ghostButton(this,
                handled ? "Undo - still open"
                    : ("Bills".equals(it.category) ? "Mark paid" : "Mark done"));
            hb.setLayoutParams(Ui.matchWrap(8, this));
            hb.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    it.handledAt = handled ? 0 : System.currentTimeMillis();
                    db.update(it);
                    render();
                }
            });
            card.addView(hb);
            if (!handled) {
                Button sb = Ui.ghostButton(this, "Snooze");
                sb.setLayoutParams(Ui.matchWrap(8, this));
                sb.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { showSnoozeChooser(it); }
                });
                card.addView(sb);
            }
            card.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { openDetail(it.id); }
            });
            content.addView(card);
          }
        }
    }

    /** Vault checkup (v0.45.0): the gaps, and one tap that fixes them. */
    private void showCheckup() {
        java.util.List<Item> all = db.all();
        final java.util.List<Checkup.MissedDate> missed = Checkup.missedDates(all);
        final java.util.List<Item> unindexed = Checkup.unindexed(all);
        java.util.List<Item> unread = Checkup.unread(all);
        java.util.Map<Long, Long> sizes = new java.util.HashMap<Long, Long>();
        for (Item it : all) {
            if (it.filePath != null && !it.filePath.isEmpty()) {
                java.io.File f = new java.io.File(it.filePath);
                if (f.exists()) sizes.put(it.id, f.length());
            }
        }
        java.util.List<Item> biggest = Checkup.biggest(all, sizes);
        StringBuilder sb = new StringBuilder();
        if (missed.isEmpty() && unindexed.isEmpty() && unread.isEmpty()) {
            sb.append("Everything checks out - no gaps found.\n");
        }
        if (!missed.isEmpty()) {
            sb.append("\n").append(missed.size())
              .append(missed.size() == 1 ? " memory names" : " memories name")
              .append(" a date that never became a reminder:");
            for (Checkup.MissedDate md : missed) {
                sb.append("\n- ").append(md.item.title).append(" (")
                  .append(Extract.formatDate(md.suggestedMs)).append(")");
                if (sb.length() > 900) { sb.append("\n..."); break; }
            }
            sb.append("\n");
        }
        if (!unindexed.isEmpty()) {
            sb.append("\n").append(unindexed.size())
              .append(unindexed.size() == 1 ? " memory is" : " memories are")
              .append(" not in the meaning index yet, so Ask may miss ")
              .append(unindexed.size() == 1 ? "it" : "them").append(".\n");
        }
        if (!unread.isEmpty()) {
            sb.append("\n").append(unread.size())
              .append(unread.size() == 1 ? " photo's" : " photos'")
              .append(" words were never read - open ")
              .append(unread.size() == 1 ? "it" : "one")
              .append(" and tap Read text.\n");
        }
        if (!biggest.isEmpty()) {
            sb.append("\nBiggest files:");
            for (Item b : biggest) {
                long s = sizes.get(b.id);
                sb.append("\n- ").append(b.title).append(" (")
                  .append(String.format(java.util.Locale.US, "%.1f MB", s / 1048576.0)).append(")");
            }
            sb.append("\n");
        }
        sb.append("\n").append(backupFreshnessText());
        android.app.AlertDialog.Builder b =
            new android.app.AlertDialog.Builder(this)
                .setTitle("Vault checkup")
                .setMessage(sb.toString().trim())
                .setNegativeButton("Close", null);
        if (!missed.isEmpty() || !unindexed.isEmpty()) {
            b.setPositiveButton("Fix what's fixable",
                new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        for (Checkup.MissedDate md : missed) {
                            md.item.reminderAt = md.suggestedMs;
                            db.update(md.item);
                        }
                        if (!unindexed.isEmpty()) Embed.embedPending(MainActivity.this, db);
                        Toast.makeText(MainActivity.this,
                            "Set " + missed.size() + (missed.size() == 1 ? " date" : " dates")
                                + (unindexed.isEmpty() ? "" : ", re-indexing in the background"),
                            Toast.LENGTH_LONG).show();
                        render();
                    }
                });
        }
        b.show();
    }

    /** People card (v0.43.0): the facts at a glance, then the memories. */
    private void showPersonCard(final Entities.Entity e) {
        String card = Entities.cardText(db.all(), e.name, System.currentTimeMillis());
        new android.app.AlertDialog.Builder(this)
            .setTitle(e.name)
            .setMessage(card != null ? card : (e.itemCount + " memories"))
            .setPositiveButton("All memories", new android.content.DialogInterface.OnClickListener() {
                @Override public void onClick(android.content.DialogInterface d, int w) {
                    askInput.setText("everything about " + e.name);
                    runAsk();
                }
            })
            .setNegativeButton("Close", null)
            .show();
    }

    /** Snooze chooser (v0.41.0): push this reminder out a day or a week. */
    private void showSnoozeChooser(final Item it) {
        String[] labels = new String[Snooze.CHOICES.length];
        for (int i = 0; i < labels.length; i++) labels[i] = Snooze.choiceLabel(Snooze.CHOICES[i]);
        new android.app.AlertDialog.Builder(this)
            .setTitle("Snooze \"" + it.title + "\"")
            .setItems(labels, new android.content.DialogInterface.OnClickListener() {
                @Override public void onClick(android.content.DialogInterface d, int which) {
                    it.reminderAt = Snooze.snoozedUntil(it.reminderAt, Snooze.CHOICES[which]);
                    db.update(it);
                    Toast.makeText(MainActivity.this,
                        "Snoozed to " + Extract.formatDate(it.reminderAt), Toast.LENGTH_SHORT).show();
                    render();
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    /** Full forecast dialog (v0.39.0): every expected bill, by month. */
    private void showForecastDialog(java.util.List<Forecast.Entry> fc) {
        StringBuilder sb = new StringBuilder();
        int lastMonth = -1;
        for (Forecast.Entry e : fc) {
            java.util.Calendar c = java.util.Calendar.getInstance();
            c.setTimeInMillis(e.dueMs);
            int key = c.get(java.util.Calendar.YEAR) * 12 + c.get(java.util.Calendar.MONTH);
            if (key != lastMonth) {
                lastMonth = key;
                if (sb.length() > 0) sb.append('\n');
                sb.append(Report.monthName(c.get(java.util.Calendar.YEAR),
                    c.get(java.util.Calendar.MONTH))).append(":\n");
            }
            sb.append(Forecast.line(e)).append('\n');
        }
        new android.app.AlertDialog.Builder(this)
            .setTitle("What's coming - next 3 months")
            .setMessage(sb.toString().trim())
            .setPositiveButton("Close", null)
            .show();
    }

    /** Bill trend (v0.33.0): one bill's whole history, bar by bar. */
    private void showTrendDialog(Patterns.Recurring r) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 18);
        box.setPadding(pad, Ui.dp(this, 6), pad, 0);
        TextView head = Ui.text(this,
            "Usually " + Ask.formatMoney(r.usualCents) + " - " + r.count + " saved - range "
                + Ask.formatMoney(r.minCents) + " to " + Ask.formatMoney(r.maxCents)
                + (r.monthly ? " - about monthly" : ""),
            13, Ui.DIM, false);
        head.setLayoutParams(Ui.matchWrap(8, this));
        box.addView(head);
        long max = Math.max(1L, r.maxCents);
        for (long[] p : r.history) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            TextView d = Ui.text(this, Extract.formatDate(p[0]), 12, Ui.DIM, false);
            d.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 96),
                LinearLayout.LayoutParams.WRAP_CONTENT));
            row.addView(d);
            LinearLayout track = new LinearLayout(this);
            track.setOrientation(LinearLayout.HORIZONTAL);
            track.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            float frac = Math.max(0.04f, (float) p[1] / (float) max);
            View bar = new View(this);
            bar.setBackgroundColor(p[1] > r.usualCents ? Ui.WARN : Ui.ACCENT2);
            bar.setLayoutParams(new LinearLayout.LayoutParams(0, Ui.dp(this, 14), frac));
            track.addView(bar);
            View spacer = new View(this);
            spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f - frac));
            track.addView(spacer);
            row.addView(track);
            TextView amt = Ui.text(this, Ask.formatMoney(p[1]), 12, Ui.TEXT, true);
            amt.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 64),
                LinearLayout.LayoutParams.WRAP_CONTENT));
            amt.setGravity(android.view.Gravity.END);
            row.addView(amt);
            row.setLayoutParams(Ui.matchWrap(5, this));
            box.addView(row);
        }
        if (r.latestCents > r.usualCents && r.usualCents > 0) {
            TextView flag = Ui.text(this,
                "The latest is " + Ask.formatMoney(r.latestCents - r.usualCents)
                    + " above usual.",
                12, Ui.WARN, true);
            flag.setLayoutParams(Ui.matchWrap(8, this));
            box.addView(flag);
        }
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(box);
        new android.app.AlertDialog.Builder(this)
            .setTitle(r.displayTitle)
            .setView(sv)
            .setPositiveButton("Close", null)
            .show();
    }

    // ---------- capture flows ----------

    private String pendingVoiceMode = "note";

    private void startVoice() { startVoiceMode("note"); }

    private void startVoiceMode(String mode) {
        pendingVoiceMode = mode;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_PERM_AUDIO);
            return;
        }
        Intent i = new Intent(this, VoiceActivity.class);
        i.putExtra("mode", mode);
        startActivity(i);
    }

    private void pickImage() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        startActivityForResult(i, REQ_PICK_IMAGE);
    }

    private void pickDocument() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        String[] mimes = {"application/pdf", "text/plain", "image/*", "text/*"};
        i.putExtra(Intent.EXTRA_MIME_TYPES, mimes);
        startActivityForResult(i, REQ_PICK_DOC);
    }

    private void startCamera(String type) {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            pendingCaptureType = type;
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_PERM_CAMERA);
            return;
        }
        pendingCaptureType = type;
        File dir = new File(getFilesDir(), "captures");
        dir.mkdirs();
        pendingCapture = new File(dir, "cap_" + System.currentTimeMillis() + ".jpg");
        Uri uri = ShareProvider.uriFor(pendingCapture);
        Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        i.putExtra(MediaStore.EXTRA_OUTPUT, uri);
        i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_CAMERA);
        } catch (Exception e) {
            Toast.makeText(this, "No camera app found", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] grants) {
        super.onRequestPermissionsResult(req, perms, grants);
        boolean ok = grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED;
        if (req == REQ_PERM_AUDIO && ok) startVoiceMode(pendingVoiceMode);
        if (req == REQ_PERM_CAMERA && ok) startCamera(pendingCaptureType);
        if (req == REQ_PERM_MEDIA) {
            boolean any = false;
            for (int g : grants) if (g == PackageManager.PERMISSION_GRANTED) any = true;
            if (any) {
                FileIngest.setPhoneMode(this, true);
                FileIngest.setEnabled(this, true);
                Toast.makeText(this, "Phone watching on - new photos and audio will file themselves", Toast.LENGTH_LONG).show();
                refreshSettings();
            } else {
                Toast.makeText(this, "Permission needed to watch the phone's photos and audio", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (!ok) Toast.makeText(this, "Permission needed for that capture type", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req == REQ_EXPORT_DOC) {
            if (result == RESULT_OK && data != null && data.getData() != null && pendingExportPassword != null) {
                writeExport(data.getData(), pendingExportPassword);
            }
            pendingExportPassword = null;
            return;
        }
        if (req == REQ_EXPORT_PLAIN) {
            if (result == RESULT_OK && data != null && data.getData() != null) {
                writePlainExport(data.getData());
            }
            return;
        }
        if (req == REQ_EXPORT_EXPENSES) {
            if (result == RESULT_OK && data != null && data.getData() != null) {
                writeExpensesCsv(data.getData());
            }
            return;
        }
        if (req == REQ_IMPORT_DOC) {
            if (result == RESULT_OK && data != null && data.getData() != null) {
                readImportBytes(data.getData());
            }
            return;
        }
        if (req == REQ_CHECK_BACKUP) {
            if (result == RESULT_OK && data != null && data.getData() != null) {
                checkBackup(data.getData());
            }
            return;
        }
        if (req == REQ_IMPORT_FOLDER) {
            if (result == RESULT_OK && data != null && data.getData() != null) {
                importFolder(data.getData());
            }
            return;
        }
        if (req == REQ_TREE_INGEST) {
            if (result == RESULT_OK && data != null && data.getData() != null) {
                final Uri tree = data.getData();
                try {
                    getContentResolver().takePersistableUriPermission(tree,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception e) {
                    Toast.makeText(this, "Android would not grant lasting access to that folder", Toast.LENGTH_LONG).show();
                    return;
                }
                FileIngest.addFolder(this, tree);
                FileIngest.setEnabled(this, true);
                Toast.makeText(this, "Folder watched. Its files will file themselves.", Toast.LENGTH_LONG).show();
                refreshSettings();
            }
            return;
        }
        if (req == REQ_TREE) {
            if (result == RESULT_OK && data != null && data.getData() != null) {
                final Uri tree = data.getData();
                try {
                    getContentResolver().takePersistableUriPermission(tree,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                } catch (Exception e) {
                    Toast.makeText(this, "Android would not grant lasting access to that folder", Toast.LENGTH_LONG).show();
                    return;
                }
                AutoBackup.setFolder(this, tree);
                AutoBackup.setEnabled(this, true);
                Toast.makeText(this, "Automatic backup on. First one runs within a day - or open Settings and tap Back up now.",
                    Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (req == 301 && result == RESULT_OK && data != null) {
            // Spoken Ask question comes back from VoiceActivity.
            String spoken = data.getStringExtra("transcript");
            if (spoken != null && askInput != null) {
                askInput.setText(spoken);
                runAsk();
            }
            return;
        }
        if (result != RESULT_OK) return;
        if (req == REQ_CAMERA && pendingCapture != null) {
            if (pendingCapture.exists() && pendingCapture.length() > 0) {
                annotateFile(pendingCapture.getAbsolutePath(), "image/jpeg", pendingCaptureType);
            } else {
                Toast.makeText(this, "Capture was empty", Toast.LENGTH_SHORT).show();
            }
            pendingCapture = null;
            return;
        }
        if ((req == REQ_PICK_IMAGE || req == REQ_PICK_DOC) && data != null && data.getData() != null) {
            importUri(data.getData(), req == REQ_PICK_IMAGE);
        }
    }

    /**
     * The one file-import pipeline (v0.10.0): picked files and
     * share-sheet files both land here. Copies the Uri into the
     * vault, reads what can be read on-device (PDF text layer / OCR
     * of scanned pages / plain-text layer), then opens the editor so
     * the user can add their own words before it files.
     */
    private void importUri(final Uri uri, final boolean forcePhoto) {
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        String mime = getContentResolver().getType(uri);
                        String name = displayName(uri);
                        File dest = copyUriToVault(uri, name);
                        // Duplicate guard (v0.15.0): the same bytes are
                        // already filed - open that memory instead.
                        try {
                            Item dup = db.findByHash(Hashes.sha256(dest));
                            if (dup != null) {
                                final long dupId = dup.id;
                                try { dest.delete(); } catch (Exception e) {}
                                ui.post(new Runnable() {
                                    @Override public void run() {
                                        Toast.makeText(MainActivity.this,
                                            "Already in your vault - opening it", Toast.LENGTH_LONG).show();
                                        openDetail(dupId);
                                    }
                                });
                                return;
                            }
                        } catch (Exception e) {
                            // Hashing is best-effort; never block an import.
                        }
                        final String path = dest.getAbsolutePath();
                        final String fm = mime == null ? "" : mime;
                        final String type;
                        if (forcePhoto) type = "PHOTO";
                        else if (fm.startsWith("image/")) type = "PHOTO";
                        else type = "DOCUMENT";
                        boolean isPdf = "application/pdf".equals(fm)
                            || (name != null && name.toLowerCase().endsWith(".pdf"));
                        if (isPdf) {
                            // Read the PDF on-device: text layer, or OCR of
                            // its pages when it is a scan (PdfText.read).
                            final PdfText.Result pdfRes = PdfText.read(dest);
                            ui.post(new Runnable() {
                                @Override public void run() {
                                    if (pdfRes.text != null && !pdfRes.text.isEmpty()) {
                                        annotateFileWithMachineText(path, fm, "DOCUMENT",
                                            pdfRes.text,
                                            pdfRes.ocr ? "Text read from the scanned PDF (OCR)"
                                                       : "Text read from the PDF",
                                            "");
                                    } else {
                                        annotateFileWithMachineText(path, fm, "DOCUMENT",
                                            "", "",
                                            "No readable words in this PDF - no text layer, "
                                            + "and OCR found none on its pages. Your words below "
                                            + "are what it will be filed and found by.");
                                    }
                                }
                            });
                            return;
                        }
                        // Read text layer for plain-text files.
                        String textLayer = "";
                        if (fm.startsWith("text/") || (name != null && name.endsWith(".txt"))) {
                            textLayer = readTextFile(dest);
                        }
                        final String tl = textLayer;
                        ui.post(new Runnable() {
                            @Override public void run() { annotateFileWithText(path, fm, type, tl); }
                        });
                    } catch (Exception e) {
                        ui.post(new Runnable() {
                            @Override public void run() {
                                Toast.makeText(MainActivity.this, "Import failed", Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                }
            }, "import").start();
    }

    /**
     * Multi-share (v0.10.0): file every shared item without opening
     * an editor per file. OCR (images), PDF reading, fact extraction
     * and embeddings all run; the toast reports the count.
     */
    private void importUrisHeadless(final java.util.ArrayList<Uri> uris) {
        new Thread(new Runnable() {
            @Override public void run() {
                int saved = 0;
                int dupes = 0;
                for (Uri uri : uris) {
                    try {
                        String mime = getContentResolver().getType(uri);
                        String name = displayName(uri);
                        File dest = copyUriToVault(uri, name);
                        String hash = "";
                        try { hash = Hashes.sha256(dest); } catch (Exception e) { hash = ""; }
                        if (!hash.isEmpty() && db.findByHash(hash) != null) {
                            try { dest.delete(); } catch (Exception e) {}
                            dupes++;
                            continue;
                        }
                        final String fm = mime == null ? "" : mime;
                        boolean isPdf = "application/pdf".equals(fm)
                            || (name != null && name.toLowerCase().endsWith(".pdf"));
                        String machine = "";
                        if (isPdf) {
                            PdfText.Result r = PdfText.read(dest);
                            machine = r.text == null ? "" : r.text;
                        } else if (fm.startsWith("image/")) {
                            machine = Ocr.recognizeSync(dest);
                        } else if (fm.startsWith("text/") || (name != null && name.endsWith(".txt"))) {
                            machine = readTextFile(dest);
                        }
                        Item it = new Item();
                        it.type = fm.startsWith("image/") ? "PHOTO" : "DOCUMENT";
                        it.filePath = dest.getAbsolutePath();
                        it.fileHash = hash;
                        it.mime = fm;
                        it.createdAt = System.currentTimeMillis();
                        it.extracted = machine;
                        Extract.Facts f = Extract.analyze(name + "\n" + machine);
                        it.category = f.category;
                        it.title = Extract.suggestTitle(machine, name);
                        it.reminderAt = f.dueDate;
                        StringBuilder am = new StringBuilder();
                        for (int i = 0; i < f.amounts.size(); i++) {
                            if (i > 0) am.append(',');
                            am.append(f.amounts.get(i));
                        }
                        it.amounts = am.toString();
                        db.insert(it);
                        saved++;
                    } catch (Exception e) {
                        // One bad file must not sink the rest of the batch.
                    }
                }
                if (saved > 0) Embed.embedPending(MainActivity.this, db);
                final int n = saved;
                final int nd = dupes;
                ui.post(new Runnable() {
                    @Override public void run() {
                        String msg;
                        if (n == 0 && nd == 0) msg = "Nothing could be imported";
                        else if (n == 0) msg = "All " + nd + " already in your vault";
                        else msg = "Filed " + n + (n == 1 ? " memory" : " memories") + " from the share"
                            + (nd > 0 ? " (" + nd + " already in your vault)" : "");
                        Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
                        render();
                    }
                });
            }
        }, "import-multi").start();
    }

    private String displayName(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Exception e) {
            // fall through
        } finally {
            if (c != null) c.close();
        }
        return "file_" + System.currentTimeMillis();
    }

    private File copyUriToVault(Uri uri, String name) throws Exception {
        File dir = new File(getFilesDir(), "imports");
        dir.mkdirs();
        String safe = (name == null ? "file" : name).replaceAll("[^a-zA-Z0-9._-]", "_");
        File dest = new File(dir, System.currentTimeMillis() + "_" + safe);
        // Read through a file descriptor, not openInputStream: against
        // some providers (MediaStore on this Samsung) the stream form
        // stalls forever on the first read while the FD form streams
        // immediately - found live in the v0.10.0 share test.
        android.os.ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "r");
        if (pfd == null) throw new Exception("provider gave no file descriptor");
        InputStream in = new java.io.FileInputStream(pfd.getFileDescriptor());
        OutputStream out = new FileOutputStream(dest);
        byte[] buf = new byte[8192];
        int n;
        long total = 0;
        while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); total += n; }
        in.close();
        pfd.close();
        out.flush();
        out.close();
        if (total == 0) throw new Exception("provider gave an empty file");
        return dest;
    }

    private String readTextFile(File f) {
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            byte[] buf = new byte[(int) Math.min(f.length(), 200000)];
            int read = 0, n;
            while (read < buf.length && (n = in.read(buf, read, buf.length - read)) > 0) read += n;
            in.close();
            return new String(buf, 0, read, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private void annotateFile(String path, String mime, String type) {
        annotateFileWithText(path, mime, type, "");
    }

    private void annotateFileWithText(String path, String mime, String type, String textLayer) {
        Intent e = new Intent(this, EditorActivity.class);
        e.putExtra("file_path", path);
        e.putExtra("mime", mime);
        e.putExtra("type", type);
        if (textLayer != null && !textLayer.isEmpty()) e.putExtra("prefill_body", textLayer);
        startActivity(e);
    }

    private void annotateFileWithMachineText(String path, String mime, String type,
                                             String machineText, String machineLabel, String machineNote) {
        Intent e = new Intent(this, EditorActivity.class);
        e.putExtra("file_path", path);
        e.putExtra("mime", mime);
        e.putExtra("type", type);
        if (machineText != null && !machineText.isEmpty()) {
            e.putExtra("machine_text", machineText);
            e.putExtra("machine_label", machineLabel);
        }
        if (machineNote != null && !machineNote.isEmpty()) e.putExtra("machine_note", machineNote);
        startActivity(e);
    }

    private void showSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 12), Ui.dp(this, 20), Ui.dp(this, 4));

        TextView allLocal = Ui.text(this,
            "Everything runs on this phone - search, transcription, reminders. "
            + "No server is involved. The two models below are the only downloads.",
            12, Ui.TEXT, false);
        box.addView(allLocal);

        LinearLayout mCard = Ui.card(this);
        mCard.addView(Ui.sectionLabel(this, "Semantic search"));
        final TextView modelStatus = Ui.text(this, modelStatusText(), 13, Ui.TEXT, false);
        modelStatus.setLayoutParams(Ui.matchWrap(6, this));
        mCard.addView(modelStatus);
        TextView mHelp = Ui.text(this,
            "EmbeddingGemma 2, stored on this phone only. With it, Ask "
            + "understands meaning instead of just matching words.",
            11, Ui.DIM, false);
        mHelp.setLayoutParams(Ui.matchWrap(4, this));
        mCard.addView(mHelp);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutParams(Ui.matchWrap(10, this));
        final boolean embedPresent = LocalEmbed.isModelPresent(this);
        Button dl = Ui.iconButton(this,
            embedPresent ? "Downloaded" : "Download (388 MB)",
            R.drawable.ic_download, Ui.ACCENT, Ui.ACCENT_DARK_TEXT);
        dl.setLayoutParams(Ui.weight1(0, this));
        if (embedPresent) disableButton(dl); // already on the phone - nothing to download
        dl.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { downloadModel(modelStatus); }
        });
        row.addView(dl);
        Button del = Ui.button(this, "Remove", Ui.PANEL_HI, Ui.DANGER);
        del.setLayoutParams(Ui.weight1(8, this));
        if (!embedPresent) disableButton(del);
        del.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                LocalEmbed.deleteModel(MainActivity.this);
                Toast.makeText(MainActivity.this, "On-device model removed", Toast.LENGTH_SHORT).show();
                refreshSettings();
            }
        });
        row.addView(del);
        mCard.addView(row);
        box.addView(mCard);

        LinearLayout wCard = Ui.card(this);
        wCard.addView(Ui.sectionLabel(this, "Transcription"));
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setLayoutParams(Ui.matchWrap(8, this));
        final int selectedVariant = Whisper.variantIndex(this);
        for (int vi = 0; vi < Whisper.VARIANT_KEYS.length; vi++) {
            final int idx = vi;
            boolean sel = vi == selectedVariant;
            String chipLabel = Whisper.VARIANT_LABELS[vi]
                + (Whisper.isVariantPresent(this, vi) ? " (saved)" : "");
            TextView chip = Ui.text(this, chipLabel, 13, sel ? Ui.ACCENT_DARK_TEXT : Ui.DIM, true);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8));
            chip.setBackground(Ui.rippleBg(this, sel ? Ui.ACCENT : Ui.PANEL_HI,
                sel ? Ui.ACCENT : Ui.BORDER, 999, 0x2EFFFFFF));
            LinearLayout.LayoutParams clp = Ui.weight1(vi == 0 ? 0 : 8, this);
            chip.setLayoutParams(clp);
            chip.setClickable(true);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (idx == Whisper.variantIndex(MainActivity.this)) return;
                    Whisper.setVariant(MainActivity.this, idx);
                    if (settingsDialog != null) settingsDialog.dismiss();
                    showSettings();
                    Toast.makeText(MainActivity.this,
                        "Transcription model: " + Whisper.VARIANT_LABELS[idx]
                            + (Whisper.isVariantPresent(MainActivity.this, idx)
                                ? " (already downloaded)" : " - download it below"),
                        Toast.LENGTH_SHORT).show();
                }
            });
            chips.addView(chip);
        }
        wCard.addView(chips);
        final TextView whisperStatus = Ui.text(this, whisperStatusText(), 13, Ui.TEXT, false);
        whisperStatus.setLayoutParams(Ui.matchWrap(6, this));
        wCard.addView(whisperStatus);
        TextView wHelp = Ui.text(this,
            "Whisper (English), stored on this phone only - the audio never "
            + "leaves it. Fast (~31 MB) is a rough draft, Balanced (~57 MB) "
            + "is the everyday pick, Sharpest (~173 MB) hears the most and "
            + "thinks the longest. Switching keeps the other downloads; "
            + "Remove deletes the selected one.",
            11, Ui.DIM, false);
        wHelp.setLayoutParams(Ui.matchWrap(4, this));
        wCard.addView(wHelp);
        LinearLayout wrow = new LinearLayout(this);
        wrow.setOrientation(LinearLayout.HORIZONTAL);
        wrow.setLayoutParams(Ui.matchWrap(10, this));
        final boolean whisperPresent = Whisper.isVariantPresent(this, selectedVariant);
        Button wdl = Ui.iconButton(this,
            whisperPresent ? "Downloaded" : "Download (" + Whisper.variantApproxMb(selectedVariant) + " MB)",
            R.drawable.ic_download, Ui.ACCENT, Ui.ACCENT_DARK_TEXT);
        wdl.setLayoutParams(Ui.weight1(0, this));
        if (whisperPresent) disableButton(wdl);
        wdl.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { downloadWhisper(whisperStatus); }
        });
        wrow.addView(wdl);
        Button wdel = Ui.button(this, "Remove", Ui.PANEL_HI, Ui.DANGER);
        wdel.setLayoutParams(Ui.weight1(8, this));
        if (!whisperPresent) disableButton(wdel);
        wdel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Whisper.deleteModel(MainActivity.this);
                Toast.makeText(MainActivity.this, "Transcription model removed", Toast.LENGTH_SHORT).show();
                refreshSettings();
            }
        });
        wrow.addView(wdel);
        wCard.addView(wrow);
        box.addView(wCard);

        LinearLayout bCard = Ui.card(this);
        bCard.addView(Ui.sectionLabel(this, "Backup & security"));
        TextView bHelp = Ui.text(this,
            "Export one encrypted backup file - every memory, file and index "
            + "inside - and keep it wherever you keep important things. "
            + "Import restores it on this phone or a new one. The password "
            + "is never stored; lose it and the backup cannot be opened.",
            11, Ui.DIM, false);
        bHelp.setLayoutParams(Ui.matchWrap(4, this));
        bCard.addView(bHelp);
        TextView bFresh = Ui.text(this, backupFreshnessText(), 12,
            backupIsStale() ? Ui.WARN : Ui.DIM, false);
        bFresh.setLayoutParams(Ui.matchWrap(6, this));
        bCard.addView(bFresh);
        long encAt = getSharedPreferences(Embed.PREFS, MODE_PRIVATE).getLong("vault_encrypted_at", 0);
        TextView bEnc = Ui.text(this, encAt > 0
                ? "Vault text is encrypted on this phone (since " + Extract.formatDate(encAt)
                    + "). Photos and recordings stay as files in the app's private storage, "
                    + "behind your phone lock."
                : "Vault encryption is being applied - it finishes the next time the app opens.",
            11, Ui.DIM, false);
        bEnc.setLayoutParams(Ui.matchWrap(4, this));
        bCard.addView(bEnc);

        // Automatic backup (v0.12.0): weekly, device-key encrypted,
        // into a folder chosen once. Restores on this phone only.
        TextView abLabel = Ui.sectionLabel(this, "Automatic backup");
        abLabel.setLayoutParams(Ui.matchWrap(12, this));
        bCard.addView(abLabel);
        TextView abHelp = Ui.text(this,
            "Once a week, an encrypted backup writes itself into a folder you pick "
            + "(a dedicated folder is best - Android will not hand over Downloads itself). It opens only on this phone - for a new phone, "
            + "use the password Export above. The newest 4 are kept.",
            11, Ui.DIM, false);
        abHelp.setLayoutParams(Ui.matchWrap(4, this));
        bCard.addView(abHelp);
        final TextView abStatus = Ui.text(this, AutoBackup.statusText(this), 12, Ui.DIM, false);
        abStatus.setLayoutParams(Ui.matchWrap(6, this));
        bCard.addView(abStatus);
        LinearLayout abrow = new LinearLayout(this);
        abrow.setOrientation(LinearLayout.HORIZONTAL);
        abrow.setLayoutParams(Ui.matchWrap(6, this));
        Button abChoose = Ui.ghostButton(this, AutoBackup.folder(this) == null ? "Choose folder" : "Change folder");
        abChoose.setLayoutParams(Ui.weight1(0, this));
        abChoose.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                startActivityForResult(i, REQ_TREE);
            }
        });
        abrow.addView(abChoose);
        Button abNow = Ui.ghostButton(this, "Back up now");
        abNow.setLayoutParams(Ui.weight1(8, this));
        abNow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (AutoBackup.folder(MainActivity.this) == null) {
                    Toast.makeText(MainActivity.this, "Choose a folder first", Toast.LENGTH_SHORT).show();
                    return;
                }
                Toast.makeText(MainActivity.this, "Backing up...", Toast.LENGTH_SHORT).show();
                new Thread(new Runnable() {
                    @Override public void run() {
                        try {
                            final int n = AutoBackup.runNow(MainActivity.this);
                            ui.post(new Runnable() {
                                @Override public void run() {
                                    abStatus.setText(AutoBackup.statusText(MainActivity.this));
                                    Toast.makeText(MainActivity.this,
                                        "Automatic backup written (" + n + " memories)", Toast.LENGTH_LONG).show();
                                }
                            });
                        } catch (final Exception e) {
                            ui.post(new Runnable() {
                                @Override public void run() {
                                    Toast.makeText(MainActivity.this, "Backup failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                                }
                            });
                        }
                    }
                }).start();
            }
        });
        abrow.addView(abNow);
        bCard.addView(abrow);
        final android.widget.Switch abSwitch = new android.widget.Switch(this);
        abSwitch.setText("Automatic backup (weekly)");
        abSwitch.setTextColor(Ui.TEXT);
        abSwitch.setTextSize(13);
        abSwitch.setChecked(AutoBackup.isEnabled(this));
        abSwitch.setLayoutParams(Ui.matchWrap(8, this));
        abSwitch.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                if (on && AutoBackup.folder(MainActivity.this) == null) {
                    b.setChecked(false);
                    Toast.makeText(MainActivity.this, "Choose a folder first", Toast.LENGTH_SHORT).show();
                    return;
                }
                AutoBackup.setEnabled(MainActivity.this, on);
            }
        });
        bCard.addView(abSwitch);
        LinearLayout brow = new LinearLayout(this);
        brow.setOrientation(LinearLayout.HORIZONTAL);
        brow.setLayoutParams(Ui.matchWrap(10, this));
        Button exp = Ui.iconButton(this, "Export", R.drawable.ic_download, Ui.ACCENT, Ui.ACCENT_DARK_TEXT);
        exp.setLayoutParams(Ui.weight1(0, this));
        exp.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { askExportPassword(); }
        });
        brow.addView(exp);
        Button imp = Ui.ghostButton(this, "Import");
        imp.setLayoutParams(Ui.weight1(8, this));
        imp.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("application/octet-stream");
                startActivityForResult(i, REQ_IMPORT_DOC);
            }
        });
        brow.addView(imp);
        bCard.addView(brow);

        // Plain readable export (v0.21.0): one Markdown file a person
        // can open anywhere - a reading copy, not a restore format.
        Button readable = Ui.ghostButton(this, "Export a readable copy (.md)");
        readable.setLayoutParams(Ui.matchWrap(8, this));
        readable.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("text/markdown");
                String stamp = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
                    .format(new java.util.Date());
                i.putExtra(Intent.EXTRA_TITLE, "recallvault-export-" + stamp + ".md");
                startActivityForResult(i, REQ_EXPORT_PLAIN);
            }
        });
        bCard.addView(readable);
        Button expensesBtn = Ui.ghostButton(this, "Export expenses (.csv)");
        expensesBtn.setLayoutParams(Ui.matchWrap(8, this));
        expensesBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("text/csv");
                String stamp = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
                    .format(new java.util.Date());
                i.putExtra(Intent.EXTRA_TITLE, "recallvault-expenses-" + stamp + ".csv");
                startActivityForResult(i, REQ_EXPORT_EXPENSES);
            }
        });
        bCard.addView(expensesBtn);

        // Morning digest (v0.28.0): a once-a-day vault summary notification.
        final android.content.SharedPreferences digestPrefs =
            getSharedPreferences(Embed.PREFS, MODE_PRIVATE);
        final android.widget.Switch digestSwitch = new android.widget.Switch(this);
        digestSwitch.setText("Morning digest (daily vault summary)");
        digestSwitch.setTextColor(Ui.TEXT);
        digestSwitch.setChecked(digestPrefs.getBoolean(ReminderWorker.PREF_DIGEST_ENABLED, true));
        digestSwitch.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                digestPrefs.edit().putBoolean(ReminderWorker.PREF_DIGEST_ENABLED, on).apply();
                Toast.makeText(MainActivity.this, on ? "Morning digest on" : "Morning digest off",
                    Toast.LENGTH_SHORT).show();
            }
        });
        bCard.addView(digestSwitch);
        Button digestBtn = Ui.ghostButton(this, "Preview morning digest");
        digestBtn.setLayoutParams(Ui.matchWrap(6, this));
        digestBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean posted = ReminderWorker.postDigest(MainActivity.this);
                Toast.makeText(MainActivity.this,
                    posted ? "Digest posted - check your notifications" : "Nothing to digest today",
                    Toast.LENGTH_LONG).show();
            }
        });
        bCard.addView(digestBtn);

        // Heads-up reminders (v0.40.0): how early the before-due nudge lands.
        final Button leadBtn = Ui.ghostButton(this,
            "Heads-up reminder: " + HeadsUp.label(
                digestPrefs.getInt(HeadsUp.PREF_LEAD_DAYS, HeadsUp.DEFAULT_LEAD_DAYS)));
        leadBtn.setLayoutParams(Ui.matchWrap(6, this));
        leadBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                int cur = digestPrefs.getInt(HeadsUp.PREF_LEAD_DAYS, HeadsUp.DEFAULT_LEAD_DAYS);
                int next = HeadsUp.nextChoice(cur);
                digestPrefs.edit().putInt(HeadsUp.PREF_LEAD_DAYS, next).apply();
                leadBtn.setText("Heads-up reminder: " + HeadsUp.label(next));
                Toast.makeText(MainActivity.this,
                    next <= 0 ? "Heads-up reminders off"
                              : "You will get a nudge " + HeadsUp.label(next).toLowerCase(java.util.Locale.US),
                    Toast.LENGTH_SHORT).show();
            }
        });
        bCard.addView(leadBtn);

        // Vault checkup (v0.45.0): find and fix the gaps.
        Button checkupBtn = Ui.ghostButton(this, "Vault checkup");
        checkupBtn.setLayoutParams(Ui.matchWrap(6, this));
        checkupBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showCheckup(); }
        });
        bCard.addView(checkupBtn);

        // Staples (v0.26.0): storage meter, check-a-backup, import-a-folder.
        long usedBytes = dirSize(getFilesDir()) + dirSize(getCacheDir());
        java.io.File dbFile = getDatabasePath("recallvault.db");
        if (dbFile != null && dbFile.exists()) usedBytes += dbFile.length();
        TextView storage = Ui.text(this,
            "Storage used by the vault and its models: " + (usedBytes / 1048576.0 >= 100
                ? String.format(java.util.Locale.US, "%.0f MB", usedBytes / 1048576.0)
                : String.format(java.util.Locale.US, "%.1f MB", usedBytes / 1048576.0)),
            12, Ui.DIM, false);
        storage.setLayoutParams(Ui.matchWrap(6, this));
        bCard.addView(storage);

        Button checkBtn = Ui.ghostButton(this, "Check a backup file");
        checkBtn.setLayoutParams(Ui.matchWrap(8, this));
        checkBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                startActivityForResult(i, REQ_CHECK_BACKUP);
            }
        });
        bCard.addView(checkBtn);

        Button folderBtn = Ui.ghostButton(this, "Import a folder");
        folderBtn.setLayoutParams(Ui.matchWrap(8, this));
        folderBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                startActivityForResult(i, REQ_IMPORT_FOLDER);
            }
        });
        bCard.addView(folderBtn);

        final android.widget.Switch lockSwitch = new android.widget.Switch(this);
        lockSwitch.setText("App lock (fingerprint or phone PIN)");
        lockSwitch.setTextColor(Ui.TEXT);
        lockSwitch.setTextSize(13);
        lockSwitch.setChecked(AppLock.isEnabled(this));
        lockSwitch.setLayoutParams(Ui.matchWrap(12, this));
        lockSwitch.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                if (on) {
                    if (!AppLock.canAuthenticate(MainActivity.this)) {
                        b.setChecked(false);
                        Toast.makeText(MainActivity.this,
                            "No fingerprint or secure PIN on this phone yet. Set one in Android Settings, then turn this on.",
                            Toast.LENGTH_LONG).show();
                        return;
                    }
                    AppLock.setEnabled(MainActivity.this, true);
                    AppLock.markUnlocked();
                    Toast.makeText(MainActivity.this, "App lock on. The vault will ask on cold start and after 2 minutes away.",
                        Toast.LENGTH_LONG).show();
                } else {
                    AppLock.setEnabled(MainActivity.this, false);
                    Toast.makeText(MainActivity.this, "App lock off", Toast.LENGTH_SHORT).show();
                }
            }
        });
        bCard.addView(lockSwitch);
        Button trashBtn = Ui.ghostButton(this, "Trash");
        trashBtn.setLayoutParams(Ui.matchWrap(6, this));
        trashBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showTrash(); }
        });
        bCard.addView(trashBtn);
        box.addView(bCard);

        // Automatic ingest (v0.27.0): watched folders and, optionally,
        // the whole phone's new photos and audio file themselves.
        LinearLayout iCard = Ui.card(this);
        iCard.addView(Ui.sectionLabel(this, "Automatic ingest"));
        TextView iHelp = Ui.text(this,
            "Point the vault at folders and it files everything readable "
            + "in them by itself - the daily check and every app open. "
            + "Phone-wide watching covers new photos and audio from the "
            + "moment you turn it on; documents come in through folders. "
            + "Everything filed gets the same reading, facts and search "
            + "as a manual save. Duplicates are always skipped.",
            11, Ui.DIM, false);
        iHelp.setLayoutParams(Ui.matchWrap(4, this));
        iCard.addView(iHelp);
        final android.widget.Switch ingestSwitch = new android.widget.Switch(this);
        ingestSwitch.setText("Automatic ingest");
        ingestSwitch.setTextColor(Ui.TEXT);
        ingestSwitch.setChecked(FileIngest.isEnabled(this));
        ingestSwitch.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                FileIngest.setEnabled(MainActivity.this, on);
                Toast.makeText(MainActivity.this, on ? "Automatic ingest on" : "Automatic ingest off",
                    Toast.LENGTH_SHORT).show();
            }
        });
        iCard.addView(ingestSwitch);
        for (final Uri folder : FileIngest.folders(this)) {
            Button fBtn = Ui.ghostButton(this, "Watched: " + folderLabel(folder) + " (tap to stop watching)");
            fBtn.setLayoutParams(Ui.matchWrap(6, this));
            fBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    FileIngest.removeFolder(MainActivity.this, folder);
                    Toast.makeText(MainActivity.this, "Folder removed", Toast.LENGTH_SHORT).show();
                    refreshSettings();
                }
            });
            iCard.addView(fBtn);
        }
        Button addFolderBtn = Ui.ghostButton(this, "Add a watched folder");
        addFolderBtn.setLayoutParams(Ui.matchWrap(6, this));
        addFolderBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                startActivityForResult(i, REQ_TREE_INGEST);
            }
        });
        iCard.addView(addFolderBtn);
        final android.widget.Switch phoneSwitch = new android.widget.Switch(this);
        phoneSwitch.setText("Watch the whole phone (new photos and audio)");
        phoneSwitch.setTextColor(Ui.TEXT);
        phoneSwitch.setChecked(FileIngest.phoneMode(this));
        phoneSwitch.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                if (on) {
                    if (android.os.Build.VERSION.SDK_INT >= 33) {
                        requestPermissions(new String[]{
                            Manifest.permission.READ_MEDIA_IMAGES,
                            Manifest.permission.READ_MEDIA_AUDIO}, REQ_PERM_MEDIA);
                    } else {
                        FileIngest.setPhoneMode(MainActivity.this, true);
                        FileIngest.setEnabled(MainActivity.this, true);
                    }
                } else {
                    FileIngest.setPhoneMode(MainActivity.this, false);
                }
            }
        });
        iCard.addView(phoneSwitch);
        TextView iStatus = Ui.text(this, FileIngest.statusText(this), 12, Ui.DIM, false);
        iStatus.setLayoutParams(Ui.matchWrap(4, this));
        iCard.addView(iStatus);
        Button nowBtn = Ui.ghostButton(this, "Ingest now");
        nowBtn.setLayoutParams(Ui.matchWrap(6, this));
        nowBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Toast.makeText(MainActivity.this, "Scanning watched places...", Toast.LENGTH_SHORT).show();
                new Thread(new Runnable() {
                    @Override public void run() {
                        final int n = FileIngest.scan(MainActivity.this);
                        ui.post(new Runnable() {
                            @Override public void run() {
                                Toast.makeText(MainActivity.this,
                                    n == 0 ? "Nothing new to file" : "Filed " + n + (n == 1 ? " new memory" : " new memories"),
                                    Toast.LENGTH_LONG).show();
                                render();
                                refreshSettings();
                            }
                        });
                    }
                }, "ingest-now").start();
            }
        });
        iCard.addView(nowBtn);
        box.addView(iCard);

        // The settings stack outgrew one screen (v0.12.0): scroll it,
        // or the bottom cards are clipped and unreachable.
        ScrollView settingsScroll = new ScrollView(this);
        settingsScroll.addView(box);
        settingsDialog = new AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(settingsScroll)
            .setPositiveButton("Close", null)
            .show();
    }

    private AlertDialog settingsDialog;

    /** Short display name for a watched tree Uri (v0.27.0). */
    private String folderLabel(Uri tree) {
        try {
            String id = android.provider.DocumentsContract.getTreeDocumentId(tree);
            int slash = id.lastIndexOf('/');
            String last = slash >= 0 ? id.substring(slash + 1) : id;
            return last.isEmpty() ? id : last;
        } catch (Exception e) {
            return "folder";
        }
    }

    /** Hard-delete trashed items older than 30 days, files included (v0.25.0). */
    private void purgeOldTrash() {
        long cutoff = System.currentTimeMillis() - 30L * 24 * 3600 * 1000;
        for (Item it : db.trashed()) {
            if (it.deletedAt <= 0 || it.deletedAt >= cutoff) continue;
            deleteItemFiles(it);
            db.delete(it.id);
        }
    }

    /** Remove an item's files from disk (page files included). */
    private void deleteItemFiles(Item it) {
        if (it.filePath != null && !it.filePath.isEmpty()) {
            try { new java.io.File(it.filePath).delete(); } catch (Exception e) {}
        }
        if (it.pages != null) {
            for (String pp : it.pages.split("\n")) {
                if (pp.trim().isEmpty()) continue;
                try { new java.io.File(pp.trim()).delete(); } catch (Exception e) {}
            }
        }
    }

    /** Total bytes under a directory, recursively (v0.26.0). */
    private long dirSize(java.io.File f) {
        if (f == null || !f.exists()) return 0;
        if (f.isFile()) return f.length();
        long total = 0;
        java.io.File[] kids = f.listFiles();
        if (kids != null) for (java.io.File k : kids) total += dirSize(k);
        return total;
    }

    /** Check a backup file without restoring it (v0.26.0). */
    private void checkBackup(final Uri uri) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    java.io.InputStream in = getContentResolver().openInputStream(uri);
                    java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                    byte[] chunk = new byte[65536];
                    int n;
                    while ((n = in.read(chunk)) > 0) {
                        buf.write(chunk, 0, n);
                        if (buf.size() > 300 * 1048576) throw new Exception("That file is too large to be a vault backup");
                    }
                    in.close();
                    final byte[] blob = buf.toByteArray();
                    if (AutoBackup.isAutoBackup(blob)) {
                        verifyBackupZip(AutoBackup.decrypt(blob), "Automatic backup");
                    } else if (blob.length > 5 && blob[0] == 'R' && blob[1] == 'V' && blob[2] == 'B' && blob[3] == 'K') {
                        ui.post(new Runnable() {
                            @Override public void run() { promptBackupPassword(blob); }
                        });
                    } else {
                        ui.post(new Runnable() {
                            @Override public void run() {
                                Toast.makeText(MainActivity.this, "That is not a RecallVault backup file", Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override public void run() {
                            Toast.makeText(MainActivity.this, "Could not check that file: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }, "check-backup").start();
    }

    private void promptBackupPassword(final byte[] blob) {
        final EditText pw = new EditText(this);
        pw.setHint("Backup password");
        pw.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        new AlertDialog.Builder(this)
            .setTitle("Password backup - enter its password to check it")
            .setView(pw)
            .setPositiveButton("Check", new android.content.DialogInterface.OnClickListener() {
                @Override public void onClick(android.content.DialogInterface d, int w) {
                    final char[] pass = pw.getText().toString().toCharArray();
                    new Thread(new Runnable() {
                        @Override public void run() {
                            try {
                                verifyBackupZip(Backup.decrypt(blob, pass), "Password backup");
                            } catch (final Exception e) {
                                ui.post(new Runnable() {
                                    @Override public void run() {
                                        Toast.makeText(MainActivity.this, "Check failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                                    }
                                });
                            }
                        }
                    }, "check-backup-pw").start();
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void verifyBackupZip(byte[] zip, final String kind) throws Exception {
        final Backup.Restored r = Backup.parseZip(zip);
        long oldest = Long.MAX_VALUE, newest = 0;
        for (Item it : r.items) {
            if (it.createdAt < oldest) oldest = it.createdAt;
            if (it.createdAt > newest) newest = it.createdAt;
        }
        final String range = r.items.isEmpty() ? ""
            : "\nFrom " + Extract.formatDate(oldest) + " to " + Extract.formatDate(newest);
        ui.post(new Runnable() {
            @Override public void run() {
                new AlertDialog.Builder(MainActivity.this)
                    .setTitle("Backup is good")
                    .setMessage(kind + " - " + r.items.size() + " memories inside." + range)
                    .setPositiveButton("OK", null)
                    .show();
            }
        });
    }

    /** Import every supported file in a chosen folder, subfolders included (v0.26.0). */
    private void importFolder(final Uri tree) {
        new Thread(new Runnable() {
            @Override public void run() {
                final java.util.ArrayList<Uri> found = new java.util.ArrayList<Uri>();
                try {
                    String rootId = android.provider.DocumentsContract.getTreeDocumentId(tree);
                    walkTree(tree, rootId, 0, found);
                } catch (Exception e) {
                    // Fall through with whatever was collected.
                }
                if (found.isEmpty()) {
                    ui.post(new Runnable() {
                        @Override public void run() {
                            Toast.makeText(MainActivity.this, "No importable files in that folder", Toast.LENGTH_LONG).show();
                        }
                    });
                    return;
                }
                ui.post(new Runnable() {
                    @Override public void run() {
                        Toast.makeText(MainActivity.this, "Importing " + found.size() + " files...", Toast.LENGTH_SHORT).show();
                    }
                });
                importUrisHeadless(found);
            }
        }, "import-folder").start();
    }

    private void walkTree(Uri tree, String docId, int depth, java.util.ArrayList<Uri> out) {
        if (depth > 6 || out.size() >= 300) return;
        Uri children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        Cursor c = null;
        try {
            c = getContentResolver().query(children, new String[]{
                android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null);
            if (c == null) return;
            while (c.moveToNext() && out.size() < 300) {
                String id = c.getString(0);
                String mime = c.getString(1);
                if (android.provider.DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    walkTree(tree, id, depth + 1, out);
                } else if (mime != null && (mime.startsWith("image/") || mime.startsWith("text/")
                        || mime.startsWith("audio/") || mime.equals("application/pdf"))) {
                    out.add(android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, id));
                }
            }
        } catch (Exception e) {
            // Skip unreadable subfolders.
        } finally {
            if (c != null) c.close();
        }
    }

    /** Trash screen: restore or delete forever (v0.25.0). */
    private void showTrash() {
        final java.util.List<Item> trashedItems = db.trashed();
        if (trashedItems.isEmpty()) {
            Toast.makeText(this, "Trash is empty", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[trashedItems.size()];
        for (int i = 0; i < trashedItems.size(); i++) {
            Item x = trashedItems.get(i);
            long days = Math.max(0, 30 - (System.currentTimeMillis() - x.deletedAt) / (24L * 3600 * 1000));
            labels[i] = x.title + "  (" + days + " days left)";
        }
        new AlertDialog.Builder(this)
            .setTitle("Trash - tap one to restore or delete")
            .setItems(labels, new android.content.DialogInterface.OnClickListener() {
                @Override public void onClick(android.content.DialogInterface d, int which) {
                    final Item target = trashedItems.get(which);
                    new AlertDialog.Builder(MainActivity.this)
                        .setTitle(target.title)
                        .setPositiveButton("Restore", new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface dd, int w) {
                                db.restore(target.id);
                                Toast.makeText(MainActivity.this, "Restored", Toast.LENGTH_SHORT).show();
                                render();
                            }
                        })
                        .setNegativeButton("Delete forever", new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface dd, int w) {
                                deleteItemFiles(target);
                                db.delete(target.id);
                                Toast.makeText(MainActivity.this, "Deleted for good", Toast.LENGTH_SHORT).show();
                                render();
                            }
                        })
                        .setNeutralButton("Cancel", null)
                        .show();
                }
            })
            .setNegativeButton("Close", null)
            .show();
    }

    // ---------- backup & restore ----------

    private char[] pendingExportPassword;
    private byte[] pendingImportBytes;

    private EditText passwordField(String hint) {
        EditText e = Ui.input(this, hint);
        e.setInputType(android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        e.setLayoutParams(Ui.matchWrap(8, this));
        return e;
    }

    private void askExportPassword() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), Ui.dp(this, 4));
        box.addView(Ui.text(this,
            "Set the backup password. You will need it to restore - it is not saved anywhere.",
            12, Ui.DIM, false));
        final EditText p1 = passwordField("Backup password (6+ characters)");
        final EditText p2 = passwordField("Type it again");
        box.addView(p1);
        box.addView(p2);
        new AlertDialog.Builder(this)
            .setTitle("Export backup")
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Choose where to save", new android.content.DialogInterface.OnClickListener() {
                @Override public void onClick(android.content.DialogInterface d, int w) {
                    String a = p1.getText().toString();
                    String b = p2.getText().toString();
                    if (a.length() < 6) {
                        Toast.makeText(MainActivity.this, "Password needs at least 6 characters", Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (!a.equals(b)) {
                        Toast.makeText(MainActivity.this, "Passwords did not match", Toast.LENGTH_LONG).show();
                        return;
                    }
                    pendingExportPassword = a.toCharArray();
                    Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("application/octet-stream");
                    String stamp = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
                        .format(new java.util.Date());
                    i.putExtra(Intent.EXTRA_TITLE, "recallvault-backup-" + stamp + ".rvbackup");
                    startActivityForResult(i, REQ_EXPORT_DOC);
                }
            })
            .show();
    }

    /** Write the readable Markdown export to the picked document (v0.21.0). */
    /** Expense CSV export (v0.42.0): receipts as a spreadsheet. */
    private void writeExpensesCsv(final Uri uri) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String csv = Receipts.csv(db.all());
                    java.io.OutputStream out = getContentResolver().openOutputStream(uri);
                    if (out == null) throw new Exception("Could not open the destination");
                    out.write(csv.getBytes("UTF-8"));
                    out.close();
                    ui.post(new Runnable() {
                        @Override public void run() {
                            Toast.makeText(MainActivity.this, "Expenses saved", Toast.LENGTH_LONG).show();
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override public void run() {
                            Toast.makeText(MainActivity.this, "Export failed: " + e.getMessage(),
                                Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }, "export-expenses").start();
    }

    private void writePlainExport(final Uri uri) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String md = PlainExport.buildMarkdown(db.all(), System.currentTimeMillis());
                    java.io.OutputStream out = getContentResolver().openOutputStream(uri);
                    if (out == null) throw new Exception("Could not open the destination");
                    out.write(md.getBytes("UTF-8"));
                    out.close();
                    ui.post(new Runnable() {
                        @Override public void run() {
                            Toast.makeText(MainActivity.this, "Readable copy saved", Toast.LENGTH_LONG).show();
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override public void run() {
                            Toast.makeText(MainActivity.this, "Export failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    /** Backup freshness (v0.11.0): a backup only protects what was in it. */
    private String backupFreshnessText() {
        android.content.SharedPreferences p = getSharedPreferences(Embed.PREFS, MODE_PRIVATE);
        long at = p.getLong("backup_last_at", 0);
        if (at == 0) return "No backup yet - your memories exist only on this phone.";
        int covered = p.getInt("backup_item_count", 0);
        int now = db.count();
        String when = Extract.formatDate(at);
        if (now > covered) {
            return "Last backup: " + when + ". " + (now - covered)
                + (now - covered == 1 ? " memory is" : " memories are") + " not in it yet.";
        }
        if (now < covered) return "Last backup: " + when + ". The vault has changed since.";
        return "Last backup: " + when + " - it covers everything in the vault.";
    }

    private boolean backupIsStale() {
        android.content.SharedPreferences p = getSharedPreferences(Embed.PREFS, MODE_PRIVATE);
        long at = p.getLong("backup_last_at", 0);
        if (at == 0) return true;
        return db.count() != p.getInt("backup_item_count", 0);
    }

    private void writeExport(final Uri uri, final char[] password) {
        Toast.makeText(this, "Building encrypted backup...", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    java.util.List<Item> all = db.all();
                    byte[] zip = Backup.buildZip(all);
                    final byte[] enc = Backup.encrypt(zip, password);
                    java.io.OutputStream out = getContentResolver().openOutputStream(uri);
                    if (out == null) throw new Exception("Could not open the destination");
                    out.write(enc);
                    out.close();
                    // Backup-freshness bookkeeping (v0.11.0): when this
                    // backup was made and how many memories it holds,
                    // so Settings can say whether it is still current.
                    getSharedPreferences(Embed.PREFS, MODE_PRIVATE).edit()
                        .putLong("backup_last_at", System.currentTimeMillis())
                        .putInt("backup_item_count", all.size())
                        .apply();
                    ui.post(new Runnable() {
                        @Override public void run() {
                            Toast.makeText(MainActivity.this,
                                "Backup saved (" + (enc.length / 1024) + " KB, encrypted)",
                                Toast.LENGTH_LONG).show();
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override public void run() {
                            Toast.makeText(MainActivity.this, "Export failed: " + e.getMessage(),
                                Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    private void readImportBytes(final Uri uri) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    java.io.InputStream in = getContentResolver().openInputStream(uri);
                    if (in == null) throw new Exception("Could not read that file");
                    pendingImportBytes = Backup.readAll(in);
                    if (AutoBackup.isAutoBackup(pendingImportBytes)) {
                        // Device-key backup (v0.12.0): no password exists
                        // to ask for - it opens with this phone's key.
                        ui.post(new Runnable() {
                            @Override public void run() {
                                new AlertDialog.Builder(MainActivity.this)
                                    .setTitle("Restore automatic backup")
                                    .setMessage("This backup was made automatically on this phone, "
                                        + "so there is no password. Restore its memories here? "
                                        + "Ones already in the vault are skipped.")
                                    .setNegativeButton("Cancel", null)
                                    .setPositiveButton("Restore", new android.content.DialogInterface.OnClickListener() {
                                        @Override public void onClick(android.content.DialogInterface d, int w) {
                                            doImportDeviceKey();
                                        }
                                    })
                                    .show();
                            }
                        });
                    } else {
                        ui.post(new Runnable() {
                            @Override public void run() { askImportPassword(); }
                        });
                    }
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override public void run() {
                            Toast.makeText(MainActivity.this, "Import failed: " + e.getMessage(),
                                Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    private void askImportPassword() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), Ui.dp(this, 4));
        box.addView(Ui.text(this, "Enter the password this backup was made with.", 12, Ui.DIM, false));
        final EditText p1 = passwordField("Backup password");
        box.addView(p1);
        new AlertDialog.Builder(this)
            .setTitle("Import backup")
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Restore", new android.content.DialogInterface.OnClickListener() {
                @Override public void onClick(android.content.DialogInterface d, int w) {
                    doImport(p1.getText().toString().toCharArray());
                }
            })
            .show();
    }

    private void doImport(final char[] password) {
        final byte[] blob = pendingImportBytes;
        pendingImportBytes = null;
        if (blob == null) return;
        Toast.makeText(this, "Restoring...", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    byte[] zip = Backup.decrypt(blob, password);
                    Backup.Restored r = Backup.parseZip(zip);
                    applyImport(r);
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override public void run() {
                            Toast.makeText(MainActivity.this, "Import failed: " + e.getMessage(),
                                Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    /** Device-key (automatic) backup import: same restore, no password (v0.12.0). */
    private void doImportDeviceKey() {
        final byte[] blob = pendingImportBytes;
        pendingImportBytes = null;
        if (blob == null) return;
        Toast.makeText(this, "Restoring...", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Backup.Restored r = Backup.parseZip(AutoBackup.decrypt(blob));
                    applyImport(r);
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override public void run() {
                            Toast.makeText(MainActivity.this, "Import failed: " + e.getMessage(),
                                Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    /** Shared restore body: file entries out, items in, dups skipped. */
    private void applyImport(Backup.Restored r) throws Exception {
                    java.util.Set<String> existing = new java.util.HashSet<String>();
                    for (Item it : db.all()) existing.add(it.title + "|" + it.createdAt);
                    File dir = new File(getFilesDir(), "restored");
                    if (!dir.exists()) dir.mkdirs();
                    int restored = 0, skipped = 0;
                    java.util.List<Item> insertedItems = new java.util.ArrayList<Item>();
                    java.util.List<Integer> insertedIdx = new java.util.ArrayList<Integer>();
                    for (int i = 0; i < r.items.size(); i++) {
                        Item it = r.items.get(i);
                        if (existing.contains(it.title + "|" + it.createdAt)) { skipped++; continue; }
                        String entry = r.itemFiles.get(i);
                        if (entry != null && !entry.isEmpty() && r.files.containsKey(entry)) {
                            String name = new File(entry).getName();
                            File dest = new File(dir, name);
                            int k = 1;
                            while (dest.exists()) {
                                int dot = name.lastIndexOf('.');
                                String base = dot > 0 ? name.substring(0, dot) : name;
                                String ext = dot > 0 ? name.substring(dot) : "";
                                dest = new File(dir, base + "-" + (k++) + ext);
                            }
                            java.io.FileOutputStream fos = new java.io.FileOutputStream(dest);
                            fos.write(r.files.get(entry));
                            fos.close();
                            it.filePath = dest.getAbsolutePath();
                        }
                        // Extra scan pages (v0.20.0): write and re-point.
                        if (i < r.itemPages.size()) {
                            StringBuilder pgs = new StringBuilder();
                            for (String pe : r.itemPages.get(i)) {
                                if (pe == null || pe.isEmpty() || !r.files.containsKey(pe)) continue;
                                String pname = new File(pe).getName();
                                File pdest = new File(dir, pname);
                                int k = 1;
                                while (pdest.exists()) {
                                    int dot = pname.lastIndexOf('.');
                                    String base = dot > 0 ? pname.substring(0, dot) : pname;
                                    String ext = dot > 0 ? pname.substring(dot) : "";
                                    pdest = new File(dir, base + "-" + (k++) + ext);
                                }
                                java.io.FileOutputStream pfos = new java.io.FileOutputStream(pdest);
                                pfos.write(r.files.get(pe));
                                pfos.close();
                                if (pgs.length() > 0) pgs.append('\n');
                                pgs.append(pdest.getAbsolutePath());
                            }
                            it.pages = pgs.toString();
                        }
                        db.insert(it);
                        insertedItems.add(it);
                        insertedIdx.add(i);
                        restored++;
                    }
                    // Re-link restored memories (v0.23.0): link targets
                    // were exported as created_at keys; map them onto
                    // this vault's ids now that everything is inserted.
                    if (!r.itemLinkKeys.isEmpty()) {
                        java.util.Map<Long, Long> idByCreated = new java.util.HashMap<Long, Long>();
                        for (Item x : db.all()) idByCreated.put(x.createdAt, x.id);
                        for (int k = 0; k < insertedItems.size(); k++) {
                            Item src = insertedItems.get(k);
                            int idx = insertedIdx.get(k);
                            if (idx >= r.itemLinkKeys.size()) continue;
                            for (Long key : r.itemLinkKeys.get(idx)) {
                                Long targetId = idByCreated.get(key);
                                if (targetId == null || targetId.longValue() == src.id) continue;
                                Item tgt = db.get(targetId);
                                if (tgt != null) db.setLinked(src, tgt, true);
                                src = db.get(src.id);
                                if (src == null) break;
                            }
                        }
                    }
                    final int fr = restored, fs = skipped;
                    ui.post(new Runnable() {
                        @Override public void run() {
                            Toast.makeText(MainActivity.this,
                                "Restored " + fr + " memories" + (fs > 0 ? ", skipped " + fs + " already here" : ""),
                                Toast.LENGTH_LONG).show();
                            render();
                        }
                    });
    }

    private String modelStatusText() {
        if (LocalEmbed.isModelPresent(this)) {
            long mb = LocalEmbed.modelFile(this).length() / (1024 * 1024);
            return "Search model: ready (" + mb + " MB). Semantic Ask works fully offline.";
        }
        return "Search model: not downloaded. Ask matches keywords only.";
    }

    private String whisperStatusText() {
        if (Whisper.isModelPresent(this)) {
            long mb = Whisper.modelBytes(this) / (1024 * 1024);
            return "Transcription model (" + Whisper.variantLabel(this) + "): ready (" + mb
                + " MB). Recordings and live meeting captions run on this phone.";
        }
        return "Transcription model (" + Whisper.variantLabel(this)
            + "): not downloaded. Voice notes keep their audio only, meetings get no captions.";
    }

    /** Grey out a button whose action makes no sense right now. */
    private void disableButton(Button b) {
        b.setEnabled(false);
        b.setTextColor(Ui.DIM);
        b.setBackground(Ui.rippleBg(this, Ui.PANEL_HI, Ui.BORDER, 16, 0));
        b.setAlpha(0.55f);
    }

    /** Rebuild the Settings dialog so control states match reality. */
    private void refreshSettings() {
        if (settingsDialog != null) settingsDialog.dismiss();
        showSettings();
    }

    private void downloadWhisper(final TextView status) {
        status.setText("Downloading model... 0%");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Whisper.downloadModel(MainActivity.this, new Whisper.Progress() {
                        @Override public void onProgress(final int percent) {
                            ui.post(new Runnable() {
                                @Override public void run() {
                                    status.setText("Downloading model... " + percent + "%");
                                }
                            });
                        }
                    });
                    ui.post(new Runnable() {
                        @Override public void run() {
                            Toast.makeText(MainActivity.this, "Transcription model ready", Toast.LENGTH_SHORT).show();
                            refreshSettings();
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override public void run() {
                            status.setText("Download failed: " + e.getMessage());
                        }
                    });
                }
            }
        }, "whisper-download").start();
    }

    private void downloadModel(final TextView status) {
        status.setText("Downloading model... 0%");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    LocalEmbed.download(MainActivity.this, new LocalEmbed.Progress() {
                        @Override public void onProgress(final int percent) {
                            ui.post(new Runnable() {
                                @Override public void run() {
                                    status.setText("Downloading model... " + percent + "%");
                                }
                            });
                        }
                    });
                    // Index the vault in the new space right away.
                    LocalEmbed.embedPendingLocal(MainActivity.this, db);
                    ui.post(new Runnable() {
                        @Override public void run() {
                            Toast.makeText(MainActivity.this, "On-device model ready", Toast.LENGTH_SHORT).show();
                            refreshSettings();
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override public void run() {
                            status.setText("Download failed: " + e.getMessage());
                        }
                    });
                }
            }
        }, "model-download").start();
    }
}
