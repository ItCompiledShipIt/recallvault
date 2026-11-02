package com.irwinlabs.recallvault;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * The contact library page (v0.62.0): the people who
 * matter, as calm Sanctuary cards. Add, edit, delete -
 * everything stays on this phone (see Contacts.java).
 */
public class ContactsActivity extends Activity {

    private LinearLayout body;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
    }

    private void render() {
        Ui.applyTheme(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(Ui.dp(this, 20), Ui.dp(this, 16), Ui.dp(this, 20), Ui.dp(this, 16));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = Ui.text(this, "Contacts", 24, Ui.TEXT, true);
        t.setTypeface(Ui.faceDisplay(this));
        head.addView(t);
        View sp = new View(this);
        sp.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        head.addView(sp);
        head.addView(Ui.pill(this, "On-device only • Private"));
        root.addView(head);
        TextView sub = Ui.text(this,
            "The people who matter, kept close. Stored only on this phone.",
            13, Ui.DIM, false);
        sub.setLayoutParams(Ui.matchWrap(6, this));
        root.addView(sub);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body);
        root.addView(scroll);

        List<Contacts.Contact> list = Contacts.load(this);
        if (list.isEmpty()) {
            body.addView(Ui.emptyState(this, R.drawable.ic_person, "No contacts yet",
                "Add the people who matter - family, doctors, the landlord - "
                + "and their details stay here, on this phone, when you need them."));
        } else {
            for (final Contacts.Contact c : list) {
                body.addView(contactCard(c));
            }
        }

        Button add = Ui.button(this, "Add a contact", Ui.ACCENT, Ui.ACCENT_DARK_TEXT);
        add.setLayoutParams(Ui.matchWrap(12, this));
        add.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { editContact(null); }
        });
        root.addView(add);
        Button back = Ui.ghostButton(this, "Back");
        back.setLayoutParams(Ui.matchWrap(8, this));
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        root.addView(back);
        setContentView(root);
    }

    private LinearLayout contactCard(final Contacts.Contact c) {
        LinearLayout card = Ui.pressCard(this);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout disc = new FrameLayout(this);
        android.graphics.drawable.GradientDrawable dg = new android.graphics.drawable.GradientDrawable();
        dg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        dg.setColor(Ui.SAGE_BG);
        disc.setBackground(dg);
        disc.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
        TextView ini = Ui.text(this,
            c.name.trim().isEmpty() ? "?" : c.name.trim().substring(0, 1).toUpperCase(java.util.Locale.US),
            18, Ui.SAGE_TEXT, true);
        ini.setGravity(Gravity.CENTER);
        disc.addView(ini, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        row.addView(disc);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tlp.setMargins(Ui.dp(this, 12), 0, 0, 0);
        texts.setLayoutParams(tlp);
        texts.addView(Ui.text(this, c.name, 16, Ui.TEXT, true));
        StringBuilder meta = new StringBuilder();
        if (!c.phone.trim().isEmpty()) meta.append(c.phone.trim());
        if (!c.email.trim().isEmpty()) {
            if (meta.length() > 0) meta.append("  •  ");
            meta.append(c.email.trim());
        }
        if (meta.length() > 0) texts.addView(Ui.text(this, meta.toString(), 12, Ui.DIM, false));
        if (!c.note.trim().isEmpty()) {
            TextView n = Ui.text(this, c.note.trim(), 12, Ui.DIM, false);
            n.setMaxLines(2);
            texts.addView(n);
        }
        row.addView(texts);
        card.addView(row);
        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { editContact(c); }
        });
        return card;
    }

    private void editContact(final Contacts.Contact existing) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 10), Ui.dp(this, 20), Ui.dp(this, 4));
        final EditText name = Ui.input(this, "Name");
        final EditText phone = Ui.input(this, "Phone");
        final EditText email = Ui.input(this, "Email");
        final EditText note = Ui.input(this, "Note (birthday, address, anything)");
        if (existing != null) {
            name.setText(existing.name);
            phone.setText(existing.phone);
            email.setText(existing.email);
            note.setText(existing.note);
        }
        box.addView(name);
        box.addView(phone);
        box.addView(email);
        box.addView(note);
        AlertDialog.Builder b = new AlertDialog.Builder(this)
            .setTitle(existing == null ? "Add a contact" : "Edit contact")
            .setView(box)
            .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    String n = name.getText().toString().trim();
                    if (n.isEmpty()) {
                        Toast.makeText(ContactsActivity.this, "A name is needed", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Contacts.Contact c = existing != null ? existing : new Contacts.Contact();
                    c.name = n;
                    c.phone = phone.getText().toString().trim();
                    c.email = email.getText().toString().trim();
                    c.note = note.getText().toString().trim();
                    Contacts.upsert(ContactsActivity.this, c);
                    render();
                }
            })
            .setNegativeButton("Cancel", null);
        if (existing != null) {
            b.setNeutralButton("Delete", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    Contacts.delete(ContactsActivity.this, existing.id);
                    render();
                }
            });
        }
        b.show();
    }
}
