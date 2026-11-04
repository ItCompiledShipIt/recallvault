package com.irwinlabs.recallvault;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The contact library (v0.62.0, grown v0.63.0): list,
 * detail and a full intake form. A contact's page is a
 * person hub - their details, one-tap call/text/email,
 * an Ask shortcut, and every vault memory that mentions
 * them behind category filters. Everything stays on
 * this phone (see Contacts.java).
 */
public class ContactsActivity extends Activity {

    private static final int MODE_LIST = 0, MODE_DETAIL = 1, MODE_EDIT = 2;
    private static final int REQ_PHOTO = 778;
    private int mode = MODE_LIST;
    private long currentId = 0;
    private String vaultFilter = "All";
    private Db db;
    /** The contact being edited - seeded from the library,
     *  a photo suggestion, or blank; form re-renders (photo
     *  picks, relationship chips) round-trip through it. */
    private Contacts.Contact draft;
    private EditText fName, fPhone, fEmail, fAddress, fOrg, fNote;
    private Switch fFav;
    private String pendingNewPhoto, pendingOldPhoto;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        db = new Db(this);
        long want = getIntent() != null ? getIntent().getLongExtra("contact_id", 0) : 0;
        if (want != 0 && Contacts.byId(this, want) != null) {
            currentId = want;
            mode = MODE_DETAIL;
        }
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (AppLock.needsUnlock(this)) {
            startActivity(new Intent(this, LockActivity.class));
        } else {
            AppLock.noteActivity();
        }
    }

    @Override
    public void onBackPressed() {
        if (mode != MODE_LIST) {
            if (mode == MODE_EDIT) abandonDraft();
            mode = (mode == MODE_EDIT && currentId != 0) ? MODE_DETAIL : MODE_LIST;
            render();
        } else {
            super.onBackPressed();
        }
    }

    private void render() {
        Ui.applyTheme(this);
        if (mode == MODE_DETAIL) renderDetail();
        else if (mode == MODE_EDIT) renderEdit();
        else renderList();
    }

    private LinearLayout shell(String title, String sub) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(Ui.dp(this, 20), Ui.dp(this, 16), Ui.dp(this, 20), Ui.dp(this, 16));
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = Ui.text(this, title, 24, Ui.TEXT, true);
        t.setTypeface(Ui.faceDisplay(this));
        head.addView(t);
        View sp = new View(this);
        sp.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        head.addView(sp);
        head.addView(Ui.pill(this, "On-device only • Private"));
        root.addView(head);
        if (sub != null) {
            TextView s = Ui.text(this, sub, 13, Ui.DIM, false);
            s.setLayoutParams(Ui.matchWrap(6, this));
            root.addView(s);
        }
        return root;
    }

    private ScrollView scroller(LinearLayout root) {
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(scroll);
        return scroll;
    }

    private FrameLayout disc(String name, int sizeDp, int bg, int fg, int textSp) {
        FrameLayout d = new FrameLayout(this);
        android.graphics.drawable.GradientDrawable dg = new android.graphics.drawable.GradientDrawable();
        dg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        dg.setColor(bg);
        d.setBackground(dg);
        d.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, sizeDp), Ui.dp(this, sizeDp)));
        TextView ini = Ui.text(this,
            name == null || name.trim().isEmpty() ? "?"
                : name.trim().substring(0, 1).toUpperCase(Locale.US),
            textSp, fg, true);
        ini.setGravity(Gravity.CENTER);
        d.addView(ini, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        return d;
    }

    /** A contact's disc: their photo when they have one,
     *  their initial otherwise (sage in lists, forest on
     *  the detail header - the size picks the register). */
    private FrameLayout discFor(Contacts.Contact c, int sizeDp) {
        android.graphics.Bitmap b = ContactPhotos.contactRound(this, c.photo, Ui.dp(this, sizeDp));
        if (b != null) {
            FrameLayout d = new FrameLayout(this);
            d.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, sizeDp), Ui.dp(this, sizeDp)));
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(b);
            d.addView(iv, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            return d;
        }
        return sizeDp >= 56
            ? disc(c.name, sizeDp, Ui.FOREST, 0xFFFFFFFF, 22)
            : disc(c.name, sizeDp, Ui.SAGE_BG, Ui.SAGE_TEXT, 18);
    }

    // ---- List --------------------------------------------------------

    private void renderList() {
        LinearLayout root = shell("Contacts",
            "The people who matter, kept close. Stored only on this phone.");
        ScrollView scroll = scroller(root);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body);

        List<Contacts.Contact> list = Contacts.load(this);
        Collections.sort(list, new Comparator<Contacts.Contact>() {
            @Override public int compare(Contacts.Contact a, Contacts.Contact b) {
                if (a.fav != b.fav) return a.fav ? -1 : 1;
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        if (list.isEmpty()) {
            body.addView(Ui.emptyState(this, R.drawable.ic_person, "No contacts yet",
                "Add the people who matter - family, doctors, the landlord - "
                + "and Clio keeps their details, their birthdays, and every "
                + "memory that mentions them, all on this phone."));
        } else {
            LocalDate today = LocalDate.now();
            for (final Contacts.Contact c : list) {
                LinearLayout card = Ui.pressCard(this);
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.addView(discFor(c, 44));
                LinearLayout texts = new LinearLayout(this);
                texts.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                tlp.setMargins(Ui.dp(this, 12), 0, 0, 0);
                texts.setLayoutParams(tlp);
                texts.addView(Ui.text(this, c.name + (c.fav ? "  ★" : ""), 16, Ui.TEXT, true));
                StringBuilder meta = new StringBuilder();
                if (!c.rel.trim().isEmpty()) meta.append(c.rel.trim());
                long days = Contacts.daysToNextBirthday(c, today);
                if (days >= 0 && days <= 60) {
                    if (meta.length() > 0) meta.append("  •  ");
                    meta.append("Birthday ").append(Contacts.whenText(days));
                } else if (!c.phone.trim().isEmpty()) {
                    if (meta.length() > 0) meta.append("  •  ");
                    meta.append(c.phone.trim());
                }
                if (meta.length() > 0) texts.addView(Ui.text(this, meta.toString(), 12, Ui.DIM, false));
                row.addView(texts);
                card.addView(row);
                card.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        currentId = c.id;
                        vaultFilter = "All";
                        mode = MODE_DETAIL;
                        render();
                    }
                });
                body.addView(card);
            }
        }

        suggestions(body);

        Button add = Ui.button(this, "Add a contact", Ui.ACCENT, Ui.ACCENT_DARK_TEXT);
        add.setLayoutParams(Ui.matchWrap(12, this));
        add.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                currentId = 0;
                draft = new Contacts.Contact();
                mode = MODE_EDIT;
                render();
            }
        });
        root.addView(add);
        Button back = Ui.ghostButton(this, "Back");
        back.setLayoutParams(Ui.matchWrap(8, this));
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        root.addView(back);
        setContentView(root);
        Ui.installInsets(this);
    }

    // ---- Detail ------------------------------------------------------

    private void renderDetail() {
        final Contacts.Contact c = Contacts.byId(this, currentId);
        if (c == null) { mode = MODE_LIST; renderList(); return; }
        LinearLayout root = shell(c.name, null);
        ScrollView scroll = scroller(root);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body);

        LinearLayout headCard = Ui.card(this);
        LinearLayout hrow = new LinearLayout(this);
        hrow.setOrientation(LinearLayout.HORIZONTAL);
        hrow.setGravity(Gravity.CENTER_VERTICAL);
        hrow.addView(discFor(c, 56));
        LinearLayout ht = new LinearLayout(this);
        ht.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams htlp = new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        htlp.setMargins(Ui.dp(this, 12), 0, 0, 0);
        ht.setLayoutParams(htlp);
        ht.addView(Ui.text(this, c.name + (c.fav ? "  ★" : ""), 19, Ui.TEXT, true));
        if (!c.org.trim().isEmpty()) ht.addView(Ui.text(this, c.org.trim(), 13, Ui.DIM, false));
        hrow.addView(ht);
        if (!c.rel.trim().isEmpty()) hrow.addView(Ui.catPill(this, c.rel, Ui.SAGE_BG, Ui.SAGE_TEXT));
        headCard.addView(hrow);
        body.addView(headCard);

        LinearLayout info = Ui.card(this);
        info.addView(Ui.sectionLabel(this, "Details"));
        boolean any = false;
        any |= fieldRow(info, "Phone", c.phone);
        any |= fieldRow(info, "Email", c.email);
        any |= fieldRow(info, "Address", c.address);
        if (!c.birthday.trim().isEmpty()) {
            LocalDate today = LocalDate.now();
            long days = Contacts.daysToNextBirthday(c, today);
            String b = Contacts.birthdayPretty(c);
            if (days >= 0) {
                b += " - next " + Contacts.whenText(days);
                int age = Contacts.turningAge(c, today);
                if (age > 0) b += ", turns " + age;
            }
            any |= fieldRow(info, "Birthday", b);
        }
        any |= fieldRow(info, "Notes", c.note);
        if (!any) info.addView(Ui.text(this, "Nothing else yet - Edit fills this in.", 13, Ui.DIM, false));
        body.addView(info);

        // One-tap ways to reach them.
        LinearLayout acts = new LinearLayout(this);
        acts.setOrientation(LinearLayout.HORIZONTAL);
        acts.setLayoutParams(Ui.matchWrap(4, this));
        if (!c.phone.trim().isEmpty()) {
            acts.addView(actBtn("Call", new View.OnClickListener() {
                @Override public void onClick(View v) {
                    open(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + c.phone.trim())));
                }
            }));
            acts.addView(actBtn("Text", new View.OnClickListener() {
                @Override public void onClick(View v) {
                    open(new Intent(Intent.ACTION_VIEW, Uri.parse("sms:" + c.phone.trim())));
                }
            }));
        }
        if (!c.email.trim().isEmpty()) {
            acts.addView(actBtn("Email", new View.OnClickListener() {
                @Override public void onClick(View v) {
                    open(new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + c.email.trim())));
                }
            }));
        }
        if (acts.getChildCount() > 0) body.addView(acts);

        Button ask = Ui.button(this, "Ask Clio about " + c.firstName(), Ui.ACCENT, Ui.ACCENT_DARK_TEXT);
        ask.setLayoutParams(Ui.matchWrap(10, this));
        ask.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(ContactsActivity.this, MainActivity.class);
                i.putExtra("ask_query", c.name);
                i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(i);
                finish();
            }
        });
        body.addView(ask);

        vaultSection(body, c);

        Button edit = Ui.ghostButton(this, "Edit contact");
        edit.setLayoutParams(Ui.matchWrap(10, this));
        edit.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                draft = c;
                mode = MODE_EDIT;
                render();
            }
        });
        root.addView(edit);
        Button back = Ui.ghostButton(this, "All contacts");
        back.setLayoutParams(Ui.matchWrap(6, this));
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { mode = MODE_LIST; render(); }
        });
        root.addView(back);
        setContentView(root);
    }

    private boolean fieldRow(LinearLayout into, String label, String value) {
        if (value == null || value.trim().isEmpty()) return false;
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setLayoutParams(Ui.matchWrap(6, this));
        r.addView(Ui.text(this, label.toUpperCase(Locale.US), 10, Ui.DIM, true));
        r.addView(Ui.text(this, value.trim(), 14, Ui.TEXT, false));
        into.addView(r);
        return true;
    }

    private Button actBtn(String label, View.OnClickListener click) {
        Button b = Ui.ghostButton(this, label);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(Ui.dp(this, 3), 0, Ui.dp(this, 3), 0);
        b.setLayoutParams(lp);
        b.setOnClickListener(click);
        return b;
    }

    private void open(Intent i) {
        try {
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "No app for that on this phone", Toast.LENGTH_SHORT).show();
        }
    }

    /** Every vault memory that mentions this person, behind
     *  category filters (chris's ask, v0.63.0). Private
     *  memories stay out, exactly as they do everywhere. */
    private void vaultSection(LinearLayout body, final Contacts.Contact c) {
        List<Item> all = Privacy.visible(db.all());
        final List<Item> hits = new ArrayList<Item>();
        for (Item it : all) {
            if (it.deletedAt != 0) continue;
            String hay = ((it.title == null ? "" : it.title) + "\n"
                + (it.body == null ? "" : it.body) + "\n"
                + (it.extracted == null ? "" : it.extracted)).toLowerCase(Locale.US);
            String hayDigits = hay.replaceAll("[^0-9]", "");
            if (Contacts.matchesText(c, hay, hayDigits)) hits.add(it);
        }
        // A timeline, not a pile (v0.66.0): oldest first, the
        // story of the person in the order it happened.
        Collections.sort(hits, new Comparator<Item>() {
            @Override public int compare(Item a, Item b) {
                return Long.compare(a.createdAt, b.createdAt);
            }
        });
        body.addView(Ui.sectionHead(this, "From your vault",
            hits.size() + (hits.size() == 1 ? " memory" : " memories")));
        if (hits.size() >= 2) {
            TextView span = Ui.text(this,
                "First saved " + Extract.formatDate(hits.get(0).createdAt)
                    + "  •  most recent " + Extract.formatDate(hits.get(hits.size() - 1).createdAt),
                12, Ui.DIM, false);
            span.setLayoutParams(Ui.matchWrap(4, this));
            body.addView(span);
        }
        if (hits.isEmpty()) {
            TextView none = Ui.text(this,
                "Nothing in the vault mentions " + c.firstName() + " yet. "
                + "Save a note, bill or photo with their name and it will gather here.",
                13, Ui.DIM, false);
            none.setLayoutParams(Ui.matchWrap(6, this));
            body.addView(none);
            return;
        }
        List<String> cats = new ArrayList<String>();
        cats.add("All");
        for (Item it : hits) {
            if (!cats.contains(it.category)) cats.add(it.category);
        }
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.setLayoutParams(Ui.matchWrap(8, this));
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        for (final String cat : cats) {
            Button ch = Ui.chip(this, cat, cat.equals(vaultFilter));
            ch.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { vaultFilter = cat; render(); }
            });
            chips.addView(ch);
        }
        hsv.addView(chips);
        body.addView(hsv);

        int shown = 0;
        String lastDay = "";
        for (final Item it : hits) {
            if (!"All".equals(vaultFilter) && !vaultFilter.equals(it.category)) continue;
            shown++;
            String day = new java.text.SimpleDateFormat("MMMM yyyy", Locale.US)
                .format(new java.util.Date(it.createdAt));
            if (!day.equals(lastDay)) {
                lastDay = day;
                TextView dh = Ui.sectionLabel(this, day);
                dh.setLayoutParams(Ui.matchWrap(10, this));
                body.addView(dh);
            }
            LinearLayout card = Ui.pressCard(this);
            LinearLayout top = new LinearLayout(this);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            TextView ttl = Ui.text(this, Privacy.displayTitle(it), 15, Ui.TEXT, true);
            ttl.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            ttl.setMaxLines(1);
            ttl.setEllipsize(android.text.TextUtils.TruncateAt.END);
            top.addView(ttl);
            card.addView(top);
            StringBuilder meta = new StringBuilder(Extract.formatDate(it.createdAt));
            if (it.amounts != null && !it.amounts.isEmpty()) {
                meta.append("  •  ").append(it.amounts.replace(",", ", "));
            }
            if (it.reminderAt > 0) meta.append("  •  due ").append(Extract.formatDate(it.reminderAt));
            TextView mv = Ui.text(this, meta.toString(), 12, Ui.DIM, false);
            mv.setLayoutParams(Ui.matchWrap(4, this));
            card.addView(mv);
            card.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Intent i = new Intent(ContactsActivity.this, DetailActivity.class);
                    i.putExtra("id", it.id);
                    startActivity(i);
                }
            });
            body.addView(card);
        }
        if (shown == 0) {
            TextView none = Ui.text(this, "Nothing under this filter.", 13, Ui.DIM, false);
            none.setLayoutParams(Ui.matchWrap(6, this));
            body.addView(none);
        }
    }

    // ---- Edit (the intake form) --------------------------------------

    /** Pull the form's current values into the draft, so a
     *  re-render (photo pick, relationship chip) loses nothing. */
    private void saveDraft() {
        if (draft == null) draft = new Contacts.Contact();
        if (fName != null) draft.name = fName.getText().toString();
        if (fPhone != null) draft.phone = fPhone.getText().toString();
        if (fEmail != null) draft.email = fEmail.getText().toString();
        if (fAddress != null) draft.address = fAddress.getText().toString();
        if (fOrg != null) draft.org = fOrg.getText().toString();
        if (fNote != null) draft.note = fNote.getText().toString();
        if (fFav != null) draft.fav = fFav.isChecked();
    }

    private void abandonDraft() {
        if (pendingNewPhoto != null) {
            ContactPhotos.delete(this, pendingNewPhoto);
            pendingNewPhoto = null;
        }
        pendingOldPhoto = null;
        draft = null;
    }

    private void renderEdit() {
        if (draft == null) draft = currentId != 0
            ? Contacts.byId(this, currentId) : new Contacts.Contact();
        if (draft == null) draft = new Contacts.Contact();
        final Contacts.Contact seed = draft;
        final boolean isNew = seed.id == 0;
        LinearLayout root = shell(isNew ? "Add a contact" : "Edit contact",
            "Only the name is required. Everything stays on this phone.");
        ScrollView scroll = scroller(root);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body);

        // Photo row: their picture, or a tap to add one.
        LinearLayout prow = new LinearLayout(this);
        prow.setOrientation(LinearLayout.HORIZONTAL);
        prow.setGravity(Gravity.CENTER_VERTICAL);
        prow.setLayoutParams(Ui.matchWrap(4, this));
        prow.addView(discFor(seed, 64));
        LinearLayout pbtns = new LinearLayout(this);
        pbtns.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams pblp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pblp.setMargins(Ui.dp(this, 12), 0, 0, 0);
        pbtns.setLayoutParams(pblp);
        Button pick = Ui.ghostButton(this, seed.photo.isEmpty() ? "Add a photo" : "Change photo");
        pick.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                saveDraft();
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("image/*");
                startActivityForResult(i, REQ_PHOTO);
            }
        });
        pbtns.addView(pick);
        if (!seed.photo.isEmpty()) {
            Button rm = Ui.ghostButton(this, "Remove photo");
            rm.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    saveDraft();
                    if (pendingNewPhoto != null && pendingNewPhoto.equals(draft.photo)) {
                        ContactPhotos.delete(ContactsActivity.this, pendingNewPhoto);
                        pendingNewPhoto = null;
                    } else {
                        pendingOldPhoto = draft.photo;
                    }
                    draft.photo = "";
                    render();
                }
            });
            pbtns.addView(rm);
        }
        prow.addView(pbtns);
        body.addView(prow);

        fName = labeled(body, "Name", seed.name, "Full name");
        body.addView(Ui.sectionLabel(this, "Relationship"));
        LinearLayout relRow = new LinearLayout(this);
        relRow.setOrientation(LinearLayout.HORIZONTAL);
        relRow.setLayoutParams(Ui.matchWrap(6, this));
        for (final String r : Contacts.RELS) {
            Button b = Ui.chip(this, r, r.equals(seed.rel.isEmpty() ? "Family" : seed.rel));
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    saveDraft();
                    draft.rel = r;
                    render();
                }
            });
            relRow.addView(b);
        }
        HorizontalScrollView relScroll = new HorizontalScrollView(this);
        relScroll.setHorizontalScrollBarEnabled(false);
        relScroll.addView(relRow);
        body.addView(relScroll);
        fPhone = labeled(body, "Phone", seed.phone, "Phone number");
        fEmail = labeled(body, "Email", seed.email, "Email address");
        fAddress = labeled(body, "Address", seed.address, "Street, city");
        fOrg = labeled(body, "Workplace / clinic", seed.org, "Where they work, if it matters");

        body.addView(Ui.sectionLabel(this, "Birthday"));
        final Button bdayBtn = Ui.ghostButton(this, bdayLabel(seed.birthday));
        bdayBtn.setLayoutParams(Ui.matchWrap(6, this));
        bdayBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                saveDraft();
                Calendar cal = Calendar.getInstance();
                if (!draft.birthday.isEmpty()) {
                    try {
                        LocalDate d = LocalDate.parse(draft.birthday);
                        cal.set(d.getYear(), d.getMonthValue() - 1, d.getDayOfMonth());
                    } catch (Exception ignored) {}
                }
                new DatePickerDialog(ContactsActivity.this, new DatePickerDialog.OnDateSetListener() {
                    @Override public void onDateSet(DatePicker view, int y, int m, int d) {
                        draft.birthday = String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d);
                        bdayBtn.setText(bdayLabel(draft.birthday));
                    }
                }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show();
            }
        });
        body.addView(bdayBtn);
        TextView bnote = Ui.text(this,
            "Clio reminds you before it - the day before and on the day.",
            12, Ui.DIM, false);
        bnote.setLayoutParams(Ui.matchWrap(2, this));
        body.addView(bnote);

        fNote = labeled(body, "Notes", seed.note,
            "Allergies, gate codes, how they take their coffee - anything");
        fFav = new Switch(this);
        fFav.setText("Favorite (pin to the top)");
        fFav.setTextColor(Ui.TEXT);
        fFav.setTextSize(14);
        fFav.setChecked(seed.fav);
        fFav.setLayoutParams(Ui.matchWrap(8, this));
        body.addView(fFav);

        Button save = Ui.button(this, "Save contact", Ui.ACCENT, Ui.ACCENT_DARK_TEXT);
        save.setLayoutParams(Ui.matchWrap(12, this));
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                saveDraft();
                String n = draft.name.trim();
                if (n.isEmpty()) {
                    Toast.makeText(ContactsActivity.this, "A name is needed", Toast.LENGTH_SHORT).show();
                    return;
                }
                draft.name = n;
                draft.phone = draft.phone.trim();
                draft.email = draft.email.trim();
                draft.address = draft.address.trim();
                draft.org = draft.org.trim();
                draft.note = draft.note.trim();
                Contacts.upsert(ContactsActivity.this, draft);
                if (pendingOldPhoto != null) {
                    ContactPhotos.delete(ContactsActivity.this, pendingOldPhoto);
                    pendingOldPhoto = null;
                }
                pendingNewPhoto = null;
                currentId = draft.id;
                draft = null;
                mode = MODE_DETAIL;
                render();
            }
        });
        root.addView(save);
        if (!isNew) {
            Button del = Ui.ghostButton(this, "Delete contact");
            del.setTextColor(Ui.DANGER);
            del.setLayoutParams(Ui.matchWrap(8, this));
            del.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    new AlertDialog.Builder(ContactsActivity.this)
                        .setTitle("Delete " + seed.name + "?")
                        .setMessage("Their card goes. Memories in the vault stay as they are.")
                        .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) {
                                ContactPhotos.delete(ContactsActivity.this, seed.photo);
                                Contacts.delete(ContactsActivity.this, seed.id);
                                draft = null;
                                mode = MODE_LIST;
                                render();
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
                }
            });
            root.addView(del);
        }
        Button cancel = Ui.ghostButton(this, "Cancel");
        cancel.setLayoutParams(Ui.matchWrap(6, this));
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                abandonDraft();
                mode = currentId != 0 ? MODE_DETAIL : MODE_LIST;
                render();
            }
        });
        root.addView(cancel);
        setContentView(root);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PHOTO && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            String name = ContactPhotos.savePicked(this, data.getData());
            if (name != null) {
                if (draft == null) draft = new Contacts.Contact();
                if (!draft.photo.isEmpty() && !draft.photo.equals(name)) {
                    if (pendingNewPhoto != null && pendingNewPhoto.equals(draft.photo)) {
                        ContactPhotos.delete(this, pendingNewPhoto);
                    } else {
                        pendingOldPhoto = draft.photo;
                    }
                }
                draft.photo = name;
                pendingNewPhoto = name;
            } else {
                Toast.makeText(this, "That photo could not be read", Toast.LENGTH_SHORT).show();
            }
            if (mode == MODE_EDIT) render();
        }
    }

    private String bdayLabel(String iso) {
        if (iso == null || iso.isEmpty()) return "Set birthday";
        Contacts.Contact tmp = new Contacts.Contact();
        tmp.birthday = iso;
        return "Birthday: " + Contacts.birthdayPretty(tmp) + "  (tap to change)";
    }

    private EditText labeled(LinearLayout into, String label, String value, String hint) {
        TextView l = Ui.sectionLabel(this, label);
        l.setLayoutParams(Ui.matchWrap(10, this));
        into.addView(l);
        EditText e = Ui.input(this, hint);
        if (value != null && !value.isEmpty()) e.setText(value);
        into.addView(e);
        return e;
    }

    // ---- Found in your photos ----------------------------------------

    private static final java.util.regex.Pattern P_EMAIL =
        java.util.regex.Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");
    private static final java.util.regex.Pattern P_PHONE =
        java.util.regex.Pattern.compile("(?:\\+?1[\\s.\\-]?)?(?:\\(\\d{3}\\)|\\d{3})[\\s.\\-]\\d{3}[\\s.\\-]\\d{4}");
    private static final java.util.regex.Pattern P_NAME =
        java.util.regex.Pattern.compile("\\b([A-Z][a-z]+ [A-Z][a-z]+)\\b");

    /** People the vault's photos already know about: a phone
     *  number or email in a photo's text, with a name guessed
     *  from the same text. One tap opens the intake form
     *  prefilled - nothing is added without his say-so. */
    private void suggestions(LinearLayout body) {
        List<Contacts.Contact> known = Contacts.load(this);
        List<String> knownDigits = new ArrayList<String>();
        List<String> knownEmails = new ArrayList<String>();
        for (Contacts.Contact k : known) {
            knownDigits.add(k.phone.replaceAll("[^0-9]", ""));
            knownEmails.add(k.email.trim().toLowerCase(Locale.US));
        }
        List<Contacts.Contact> found = new ArrayList<Contacts.Contact>();
        List<String> seenKeys = new ArrayList<String>();
        for (Item it : Privacy.visible(db.all())) {
            if (it.deletedAt != 0 || !"PHOTO".equals(it.type)) continue;
            String text = (it.title == null ? "" : it.title) + "\n"
                + (it.extracted == null ? "" : it.extracted) + "\n"
                + (it.body == null ? "" : it.body);
            String email = "";
            java.util.regex.Matcher em = P_EMAIL.matcher(text);
            if (em.find()) email = em.group();
            String phone = "";
            java.util.regex.Matcher pm = P_PHONE.matcher(text);
            if (pm.find()) phone = pm.group();
            if (email.isEmpty() && phone.isEmpty()) continue;
            String pd = phone.replaceAll("[^0-9]", "");
            if (!pd.isEmpty() && knownDigits.contains(pd)) continue;
            if (!email.isEmpty() && knownEmails.contains(email.toLowerCase(Locale.US))) continue;
            String key = !pd.isEmpty() ? pd : email.toLowerCase(Locale.US);
            if (seenKeys.contains(key)) continue;
            seenKeys.add(key);
            String guess = "";
            java.util.regex.Matcher nm = P_NAME.matcher(text.replace('\n', ' '));
            if (nm.find()) guess = nm.group(1);
            if (guess.isEmpty()) guess = it.title == null ? "New contact" : it.title;
            Contacts.Contact s = new Contacts.Contact();
            s.name = guess;
            s.phone = phone;
            s.email = email;
            s.note = "Found in a photo: " + (it.title == null ? "" : it.title);
            found.add(s);
            if (found.size() >= 6) break;
        }
        if (found.isEmpty()) return;
        body.addView(Ui.sectionHead(this, "Found in your photos",
            found.size() + (found.size() == 1 ? " person" : " people")));
        for (final Contacts.Contact s : found) {
            LinearLayout card = Ui.card(this);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            texts.addView(Ui.text(this, s.name, 15, Ui.TEXT, true));
            StringBuilder ev = new StringBuilder();
            if (!s.phone.isEmpty()) ev.append(s.phone);
            if (!s.email.isEmpty()) {
                if (ev.length() > 0) ev.append("  •  ");
                ev.append(s.email);
            }
            texts.addView(Ui.text(this, ev.toString(), 12, Ui.DIM, false));
            row.addView(texts);
            Button add = Ui.ghostButton(this, "Add");
            add.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    currentId = 0;
                    draft = s;
                    mode = MODE_EDIT;
                    render();
                }
            });
            row.addView(add);
            card.addView(row);
            body.addView(card);
        }
    }
}
