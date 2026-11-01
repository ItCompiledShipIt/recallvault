package com.irwinlabs.recallvault;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

/**
 * Create or annotate a memory. Used for plain notes, and as the
 * annotate step after a photo / scan / document import: the user adds
 * a title and a few words, and Extract files it (category, dates,
 * amounts) automatically.
 */
public class EditorActivity extends Activity {

    private Db db;
    private EditText titleInput;
    private EditText bodyInput;
    private TextView liveFacts;
    private TextView machineInfo;
    private long editId = -1;
    private String filePath = "";
    private String mime = "";
    private String type = "TEXT";
    private long createdAt = 0;
    /** Machine-read text (OCR / PDF layer). Kept apart from the user's own words. */
    private String machineText = "";
    private String machineLabel = "";
    /** Shown when a file yielded no machine text (e.g. a scanned PDF). */
    private String machineNote = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        db = new Db(this);

        Intent in = getIntent();
        editId = in.getLongExtra("id", -1);
        filePath = in.getStringExtra("file_path") != null ? in.getStringExtra("file_path") : "";
        mime = in.getStringExtra("mime") != null ? in.getStringExtra("mime") : "";
        type = in.getStringExtra("type") != null ? in.getStringExtra("type") : "TEXT";

        Ui.applyChrome(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16));

        root.addView(Ui.text(this, editId > 0 ? "Edit memory" : "New " + Ui.typeLabel(type).toLowerCase(),
            20, Ui.TEXT, true));

        if (!filePath.isEmpty() && mime.startsWith("image/")) {
            Bitmap bmp = BitmapFactory.decodeFile(filePath);
            if (bmp != null) {
                ImageView iv = new ImageView(this);
                iv.setImageBitmap(bmp);
                iv.setAdjustViewBounds(true);
                iv.setMaxHeight(Ui.dp(this, 220));
                iv.setLayoutParams(Ui.matchWrap(10, this));
                root.addView(iv);
            }
        } else if (!filePath.isEmpty()) {
            TextView f = Ui.text(this, "File: " + new File(filePath).getName(), 12, Ui.DIM, false);
            f.setLayoutParams(Ui.matchWrap(8, this));
            root.addView(f);
        }

        titleInput = Ui.input(this, "Title (blank = first line of your text)");
        titleInput.setLayoutParams(Ui.matchWrap(12, this));
        root.addView(titleInput);

        bodyInput = Ui.input(this, "Say what this is, in your own words. Dates and amounts "
            + "are picked out automatically - try: Electricity bill, $84.12, due November 14, 2026");
        bodyInput.setMinLines(5);
        bodyInput.setGravity(android.view.Gravity.TOP);
        bodyInput.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(bodyInput);
        bodyInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) { updateLiveFacts(); }
        });

        liveFacts = Ui.text(this, "", 12, Ui.ACCENT2, true);
        liveFacts.setBackground(Ui.panelBg((Ui.ACCENT2 & 0x00FFFFFF) | 0x14000000, 0, 10, this));
        liveFacts.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        liveFacts.setLayoutParams(Ui.matchWrap(10, this));
        root.addView(liveFacts);

        machineInfo = Ui.text(this, "", 12, Ui.DIM, false);
        machineInfo.setMaxLines(4);
        machineInfo.setEllipsize(android.text.TextUtils.TruncateAt.END);
        machineInfo.setLayoutParams(Ui.matchWrap(4, this));
        root.addView(machineInfo);

        Button save = Ui.iconButton(this, "Save to vault", R.drawable.ic_check, Ui.ACCENT, Ui.ACCENT_DARK_TEXT);
        save.setLayoutParams(Ui.matchWrap(12, this));
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { save(); }
        });
        root.addView(save);

        Button cancel = Ui.ghostButton(this, "Cancel");
        cancel.setLayoutParams(Ui.matchWrap(8, this));
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        root.addView(cancel);

        setContentView(root);

        String prefill = in.getStringExtra("prefill_body");
        String mt = in.getStringExtra("machine_text");
        if (mt != null) machineText = mt;
        String ml = in.getStringExtra("machine_label");
        if (ml != null) machineLabel = ml;
        String mn = in.getStringExtra("machine_note");
        if (mn != null) machineNote = mn;
        if (editId > 0) {
            Item it = db.get(editId);
            if (it != null) {
                titleInput.setText(it.title);
                bodyInput.setText(it.body.isEmpty() ? it.extracted : it.body);
                filePath = it.filePath;
                mime = it.mime;
                type = it.type;
                createdAt = it.createdAt;
                if (machineText.isEmpty() && it.extracted != null
                        && !it.extracted.equals(it.body) && !it.extracted.isEmpty()) {
                    machineText = it.extracted;
                    machineLabel = "Text read from the file";
                }
            }
        } else if (prefill != null) {
            bodyInput.setText(prefill);
        }
        updateMachineInfo();
        updateLiveFacts();
        maybeRunOcr();
    }

    private void updateMachineInfo() {
        if (machineText == null || machineText.isEmpty()) {
            machineInfo.setText(machineNote == null ? "" : machineNote);
            return;
        }
        String label = machineLabel.isEmpty() ? "Text read from the file" : machineLabel;
        machineInfo.setText(label + " (" + machineText.length() + " chars): "
            + machineText.replaceAll("\\s+", " ").trim());
    }

    /** Photos/scans get OCR on arrival: the words in the image become searchable. */
    private void maybeRunOcr() {
        if (editId > 0) return;
        if (filePath.isEmpty() || mime == null || !mime.startsWith("image/")) return;
        if (machineText != null && !machineText.isEmpty()) return;
        machineInfo.setText("Reading the text in this image...");
        Ocr.recognize(new File(filePath), new Ocr.Callback() {
            @Override public void onText(String text) {
                if (text != null && !text.trim().isEmpty()) {
                    machineText = text.trim();
                    machineLabel = "Text read from the image (OCR)";
                } else {
                    machineInfo.setText("No readable text found in this image - "
                        + "your words below are what it will be filed and found by.");
                }
                updateMachineInfo();
                updateLiveFacts();
            }
            @Override public void onError(String message) {
                machineInfo.setText("Could not read text from this image (" + message + "). "
                    + "Your words below still file it.");
            }
        });
    }

    private void updateLiveFacts() {
        String text = titleInput.getText().toString() + "\n" + bodyInput.getText().toString()
            + "\n" + machineText;
        Extract.Facts f = Extract.analyze(text);
        StringBuilder sb = new StringBuilder();
        sb.append("Will file as: ").append(f.category);
        if (f.dueDate > 0) sb.append("  -  date: ").append(Extract.formatDate(f.dueDate));
        if (!f.amounts.isEmpty()) {
            sb.append("  -  ");
            for (int i = 0; i < f.amounts.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(f.amounts.get(i));
            }
        }
        liveFacts.setText(sb.toString());
    }

    private void save() {
        String body = bodyInput.getText().toString().trim();
        String title = titleInput.getText().toString().trim();
        if (body.isEmpty() && filePath.isEmpty() && machineText.isEmpty()) {
            Toast.makeText(this, "Write something first", Toast.LENGTH_SHORT).show();
            return;
        }
        String allText = title + "\n" + body + "\n" + machineText;
        Extract.Facts f = Extract.analyze(allText);
        if (title.isEmpty()) {
            title = Extract.suggestTitle(body, "");
            if (title.isEmpty()) title = Extract.suggestTitle(machineText, Ui.typeLabel(type));
        }

        Item it;
        if (editId > 0) {
            it = db.get(editId);
            if (it == null) it = new Item();
        } else {
            it = new Item();
            it.createdAt = System.currentTimeMillis();
        }
        if (createdAt > 0) it.createdAt = createdAt;
        it.title = title;
        it.type = type;
        it.category = f.category;
        it.body = body;
        it.extracted = machineText.isEmpty() ? body : machineText;
        it.filePath = filePath;
        // Fingerprint the file so the same bytes are never filed twice (v0.15.0).
        if (!filePath.isEmpty() && new java.io.File(filePath).exists()) {
            try { it.fileHash = Hashes.sha256(new java.io.File(filePath)); }
            catch (Exception e) { it.fileHash = ""; }
        }
        it.mime = mime;
        it.reminderAt = f.dueDate;
        StringBuilder am = new StringBuilder();
        for (int i = 0; i < f.amounts.size(); i++) {
            if (i > 0) am.append(',');
            am.append(f.amounts.get(i));
        }
        it.amounts = am.toString();
        if (editId > 0 && it.id > 0) {
            it.embedding = ""; // text changed: re-embed next round
            db.update(it);
        } else {
            db.insert(it);
        }
        Embed.embedPending(this, db);
        Toast.makeText(this, "Saved under " + it.category, Toast.LENGTH_SHORT).show();
        finish();
    }
}
