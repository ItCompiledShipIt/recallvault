package com.irwinlabs.recallvault;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Ask as a conversation (v0.76.0): a messaging-style thread with
 * Clio - one Main chat plus side chats, thinking/status clues
 * while she works, and compression for long threads (the oldest
 * turns fold into a digest that rides in the prompt; a divider
 * in the thread marks where). Mirrors the desktop app.
 *
 * The answer chain is the same one the old Ask card used:
 * contacts, then the deterministic vault answers (census,
 * patterns, ...), then the on-device brain over the retrieved
 * memories, then the plain hit list when no brain is present.
 */
public class ChatActivity extends Activity {

    private Db db;
    private ChatStore store;
    private ChatStore.Chat chat;
    private LinearLayout threadCol;
    private ScrollView scroll;
    private EditText input;
    private Button sendBtn;
    private TextView titleView;
    private LinearLayout typingRow;
    private TextView typingDots;
    private TextView typingStatus;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean busy;
    private int dotFrame;

    private final Runnable dotTick = new Runnable() {
        @Override public void run() {
            if (typingRow.getVisibility() != View.VISIBLE) return;
            String[] frames = {"\u25CF\u25CB\u25CB", "\u25CB\u25CF\u25CB",
                               "\u25CB\u25CB\u25CF"};
            typingDots.setText(frames[dotFrame++ % frames.length]);
            ui.postDelayed(this, 350);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (AppLock.isEnabled(this) && AppLock.needsUnlock(this)) {
            startActivity(new Intent(this, LockActivity.class));
            finish();
            return;
        }
        Ui.applyChrome(this);
        db = new Db(this);
        store = ChatStore.load(this);
        chat = store.get(getIntent() != null
            ? getIntent().getStringExtra("chat_id") : null);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(Ui.dp(this, 12), Ui.dp(this, 10),
                        Ui.dp(this, 12), Ui.dp(this, 10));
        setContentView(root);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        Button back = Ui.ghostButton(this, "BACK");
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        head.addView(back);
        titleView = Ui.text(this, chat.title, 16, Ui.TEXT, true);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tlp.setMargins(Ui.dp(this, 10), 0, 0, 0);
        titleView.setLayoutParams(tlp);
        head.addView(titleView);
        Button chatsBtn = Ui.ghostButton(this, "CHATS");
        chatsBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showChats(); }
        });
        head.addView(chatsBtn);
        root.addView(head);

        scroll = new ScrollView(this);
        threadCol = new LinearLayout(this);
        threadCol.setOrientation(LinearLayout.VERTICAL);
        threadCol.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        scroll.addView(threadCol);
        root.addView(scroll, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        typingRow = new LinearLayout(this);
        typingRow.setOrientation(LinearLayout.HORIZONTAL);
        typingRow.setGravity(Gravity.CENTER_VERTICAL);
        typingRow.setVisibility(View.GONE);
        typingRow.setPadding(Ui.dp(this, 4), Ui.dp(this, 2), 0, Ui.dp(this, 6));
        typingDots = Ui.text(this, "\u25CF\u25CB\u25CB", 12, Ui.ACCENT, true);
        typingRow.addView(typingDots);
        typingStatus = Ui.text(this, "Thinking", 12, Ui.DIM, false);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.setMargins(Ui.dp(this, 8), 0, 0, 0);
        typingStatus.setLayoutParams(slp);
        typingRow.addView(typingStatus);
        root.addView(typingRow);

        LinearLayout composer = new LinearLayout(this);
        composer.setOrientation(LinearLayout.HORIZONTAL);
        composer.setGravity(Gravity.CENTER_VERTICAL);
        input = Ui.input(this, "Message Clio...");
        input.setLayoutParams(new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        composer.addView(input);
        sendBtn = Ui.primaryButton(this, "SEND");
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
            Ui.dp(this, 76), LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.setMargins(Ui.dp(this, 8), 0, 0, 0);
        sendBtn.setLayoutParams(blp);
        sendBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                send(input.getText().toString());
            }
        });
        composer.addView(sendBtn);
        root.addView(composer);

        renderThread();

        String q0 = getIntent() != null
            ? getIntent().getStringExtra("q") : null;
        if (q0 != null && !q0.trim().isEmpty()) {
            final String first = q0;
            ui.post(new Runnable() {
                @Override public void run() { send(first); }
            });
        }
    }

    // ------------------------------------------------------------
    // Thread rendering.
    // ------------------------------------------------------------

    private void renderThread() {
        threadCol.removeAllViews();
        titleView.setText(chat.title);
        if (chat.messages.isEmpty()) {
            TextView hint = Ui.text(this,
                "Ask about your memories, people and dates. Clio answers "
                + "from your vault, on this phone.", 13, Ui.DIM, false);
            hint.setGravity(Gravity.CENTER);
            hint.setLayoutParams(Ui.matchWrap(24, this));
            threadCol.addView(hint);
        }
        for (int i = 0; i < chat.messages.size(); i++) {
            if (chat.digestUpto > 0 && i == chat.digestUpto) {
                TextView div = Ui.text(this,
                    "Earlier conversation compressed", 11, Ui.DIM, false);
                div.setGravity(Gravity.CENTER);
                div.setLayoutParams(Ui.matchWrap(8, this));
                threadCol.addView(div);
            }
            threadCol.addView(bubble(chat.messages.get(i)));
        }
        scroll.post(new Runnable() {
            @Override public void run() {
                scroll.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    private View bubble(final ChatStore.Msg m) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutParams(Ui.matchWrap(5, this));
        boolean user = "user".equals(m.role);
        row.setGravity(user ? Gravity.END : Gravity.START);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        col.setLayoutParams(clp);

        TextView tv = Ui.text(this, m.text, 15,
            user ? Ui.ACCENT_DARK_TEXT : Ui.TEXT, false);
        tv.setBackground(Ui.panelBg(user ? Ui.ACCENT : Ui.PANEL, 0, 16, this));
        tv.setPadding(Ui.dp(this, 12), Ui.dp(this, 9),
                      Ui.dp(this, 12), Ui.dp(this, 9));
        tv.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.82));
        col.addView(tv);
        if (!user && m.prov != null && !m.prov.isEmpty()) {
            TextView pv = Ui.text(this, m.prov, 11, Ui.DIM, false);
            pv.setLayoutParams(Ui.matchWrap(3, this));
            col.addView(pv);
        }
        if (!user && m.itemId > 0) {
            final Item it = db.get(m.itemId);
            if (it != null && it.reminderAt > 0 && it.handledAt == 0) {
                col.addView(actionRow(m, it));
            }
        }
        row.addView(col);
        return row;
    }

    /** Done / Snooze for the bill a vault answer points at. */
    private View actionRow(final ChatStore.Msg m, final Item it) {
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setLayoutParams(Ui.matchWrap(6, this));
        Button done = new Button(this);
        done.setText("Bills".equals(it.category) ? "DONE - PAID" : "DONE");
        done.setTextSize(12);
        done.setAllCaps(true);
        done.setTypeface(android.graphics.Typeface.DEFAULT,
                         android.graphics.Typeface.BOLD);
        done.setTextColor(Ui.ACCENT_DARK_TEXT);
        done.setBackground(Ui.rippleBg(this, Ui.ACCENT2, 0, 8, 0x330B0E13));
        done.setLayoutParams(Ui.weight1(0, this));
        done.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                it.handledAt = System.currentTimeMillis();
                db.update(it);
                m.itemId = 0;
                store.save(ChatActivity.this);
                Toast.makeText(ChatActivity.this,
                    "Done - " + it.title, Toast.LENGTH_SHORT).show();
                renderThread();
            }
        });
        actions.addView(done);
        Button snz = Ui.ghostButton(this, "SNOOZE +24H");
        snz.setLayoutParams(Ui.weight1(8, this));
        snz.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                it.reminderAt = Snooze.snoozedUntil(it.reminderAt, 1);
                db.update(it);
                m.itemId = 0;
                store.save(ChatActivity.this);
                Toast.makeText(ChatActivity.this,
                    "Snoozed to " + Extract.formatDate(it.reminderAt),
                    Toast.LENGTH_SHORT).show();
                renderThread();
            }
        });
        actions.addView(snz);
        return actions;
    }

    // ------------------------------------------------------------
    // Chat switching.
    // ------------------------------------------------------------

    private void showChats() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(this, 16), Ui.dp(this, 8),
                       Ui.dp(this, 16), Ui.dp(this, 8));
        final AlertDialog[] dlg = new AlertDialog[1];
        for (final ChatStore.Chat c : store.chats) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setLayoutParams(Ui.matchWrap(4, this));
            TextView name = Ui.text(this,
                (c.id.equals(chat.id) ? "\u25CF " : "\u25CB ")
                    + c.title + "  \u00B7  " + c.messages.size(),
                15, Ui.TEXT, c.id.equals(chat.id));
            name.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            name.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    chat = c;
                    if (dlg[0] != null) dlg[0].dismiss();
                    renderThread();
                }
            });
            row.addView(name);
            if (!"main".equals(c.id)) {
                TextView del = Ui.text(this, "Delete", 13,
                    Ui.ACCENT, false);
                del.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        store.delete(c.id);
                        if (chat.id.equals(c.id)) chat = store.main();
                        store.save(ChatActivity.this);
                        if (dlg[0] != null) dlg[0].dismiss();
                        renderThread();
                        showChats();
                    }
                });
                row.addView(del);
            }
            col.addView(row);
        }
        Button newBtn = Ui.primaryButton(this, "NEW SIDE CHAT");
        newBtn.setLayoutParams(Ui.matchWrap(10, this));
        newBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                chat = store.newSide(System.currentTimeMillis());
                store.save(ChatActivity.this);
                if (dlg[0] != null) dlg[0].dismiss();
                renderThread();
            }
        });
        col.addView(newBtn);
        dlg[0] = new AlertDialog.Builder(this)
            .setTitle("Chats")
            .setView(col)
            .setNegativeButton("Close", null)
            .show();
    }

    // ------------------------------------------------------------
    // Sending + the answer chain.
    // ------------------------------------------------------------

    private void setTyping(boolean on, String status) {
        if (on) {
            typingStatus.setText(status);
            typingRow.setVisibility(View.VISIBLE);
            dotFrame = 0;
            ui.removeCallbacks(dotTick);
            ui.post(dotTick);
            scroll.post(new Runnable() {
                @Override public void run() {
                    scroll.fullScroll(View.FOCUS_DOWN);
                }
            });
        } else {
            typingRow.setVisibility(View.GONE);
            ui.removeCallbacks(dotTick);
        }
    }

    private void setStatus(final String status) {
        ui.post(new Runnable() {
            @Override public void run() { typingStatus.setText(status); }
        });
    }

    private void send(String raw0) {
        if (busy) return;
        String raw = raw0 == null ? "" : raw0.trim();
        if (raw.isEmpty()) return;
        long now = System.currentTimeMillis();
        String resolved = FollowUp.resolve(chat.lastUserText(), raw, now);
        final String q = resolved != null ? resolved : raw;
        chat.messages.add(new ChatStore.Msg("user", q, now, ""));
        if (!"main".equals(chat.id) && "Side chat".equals(chat.title)) {
            chat.title = q.length() > 42 ? q.substring(0, 42) : q;
        }
        store.save(this);
        input.setText("");
        renderThread();
        busy = true;
        sendBtn.setEnabled(false);
        setTyping(true, "Searching your memories");
        new Thread(new Runnable() {
            @Override public void run() { answer(q); }
        }, "chat-ask").start();
    }

    private void appendClio(String text, String prov, long itemId) {
        ChatStore.Msg m = new ChatStore.Msg(
            "clio", text, System.currentTimeMillis(), prov);
        m.itemId = itemId;
        chat.messages.add(m);
    }

    private void answer(final String q) {
        try {
            maybeCompress();
            final List<Item> items = Privacy.visible(db.all());
            final long now = System.currentTimeMillis();

            final List<Contacts.Contact> contactHits =
                Contacts.matchQuery(Contacts.load(this), q);

            float[] vec = null;
            if (LocalEmbed.isModelPresent(this)) {
                try {
                    LocalEmbed.embedPendingLocal(this, db);
                    vec = LocalEmbed.embedSync(this, q);
                } catch (Exception e) {
                    vec = null;
                }
            }

            Ask.Summary sum = Census.answer(items, q, now);
            if (sum == null) sum = Patterns.answer(items, q, now);
            if (sum == null) sum = Entities.answer(items, q);
            if (sum == null) sum = Report.answer(items, q, now);
            if (sum == null) sum = Forecast.answer(items, q, now);
            if (sum == null) sum = Receipts.answer(items, q, now);
            if (sum == null) sum = Expiry.answer(items, q, now);
            if (sum == null) sum = Resurface.reviewAnswer(items, q, now);
            if (sum == null) sum = Ask.summarize(items, q, vec, now);

            String answerText = null;
            String prov = "";
            long actionItem = 0;
            if (sum != null) {
                StringBuilder sb = new StringBuilder(sum.text);
                int shown = 0;
                for (Ask.Line ln : sum.lines) {
                    if (shown++ >= 8) break;
                    sb.append("\n\u2022 ")
                      .append(Ui.dateLine(ln.item.reminderAt > 0
                          ? ln.item.reminderAt : ln.item.createdAt))
                      .append("  ").append(ln.item.title)
                      .append("  ").append(Ask.formatMoney(ln.cents));
                }
                answerText = sb.toString();
                prov = "From your vault";
                for (Ask.Line ln : sum.lines) {
                    if (ln.item.reminderAt > 0 && ln.item.handledAt == 0) {
                        actionItem = ln.item.id;
                        break;
                    }
                }
            } else {
                List<Ask.Result> results = Ask.search(items, q, vec, true);
                List<Item> hitItems = new ArrayList<Item>();
                for (Ask.Result r : results) hitItems.add(r.item);
                String[] ai = AiProvider.config(this);
                boolean aiFailed = false;
                if (ai != null || GemmaBrain.isModelPresent(this)) {
                    setStatus("Thinking");
                    List<Skills.Skill> all = Skills.loadAll(this);
                    List<Skills.Skill> on = Skills.enabled(this, all);
                    String sysA = "You are Clio, the muse who keeps this person's private memory vault - everything you know lives on this phone, in these memories. "
                        + "Answer using ONLY the memories provided. "
                        + "Say plainly when the vault does not hold the answer. Be direct and brief. "
                        + "Never invent dates, amounts or names. ";
                    String systemBase = sysA
                        + ClioMind.promptBlock(this)
                        + "\n\n" + Skills.catalogBlock(on);
                    String hist = historyExcludingCurrent();
                    String prompt = "Question: " + q
                        + (hist.isEmpty() ? ""
                           : "\n\nConversation so far:\n" + hist)
                        + "\n\nMemories already found:\n"
                        + GemmaBrain.groundingBlock(hitItems, 900)
                        + "\n\nWrite the answer in full sentences. Cite the memory number like [1] where you use it.";
                    if (ai != null) {
                        try {
                            answerText = AiProvider.chat(this, systemBase, prompt);
                            prov = "Answered by " + AiProvider.host(ai[0])
                                + " \u00B7 " + Ask.provenance(results);
                        } catch (Exception e) {
                            answerText = null;
                            aiFailed = true;
                        }
                    }
                    if (answerText == null && GemmaBrain.isModelPresent(this)) {
                        try {
                            String system = sysA
                                + "When a question needs facts that are not in the vault, you may use search_web; say clearly when an answer comes from the web instead of the memories."
                                + ClioMind.promptBlock(this)
                                + "\n\n" + Skills.catalogBlock(on);
                            List<com.google.ai.edge.litertlm.ToolProvider> tools =
                                new ArrayList<com.google.ai.edge.litertlm.ToolProvider>();
                            tools.add(BrainTools.provider(this, db));
                            answerText = GemmaBrain.generate(this, system, prompt, tools);
                            prov = (aiFailed
                                ? "Answered locally - the external provider did not respond \u00B7 "
                                : "") + Ask.provenance(results);
                        } catch (Exception e) {
                            answerText = null;
                        }
                    }
                }
                if (answerText == null) {
                    if (!results.isEmpty()) {
                        StringBuilder sb = new StringBuilder("Here's what I found:");
                        int n = 1;
                        for (Ask.Result r : results) {
                            if (n > 6) break;
                            sb.append("\n").append(n++).append(". ")
                              .append(r.item.title).append(" - ")
                              .append(Ui.dateLine(r.item.createdAt));
                        }
                        answerText = sb.toString();
                        prov = Ask.provenance(results);
                    } else {
                        answerText = "No match in your vault yet.";
                    }
                }
            }

            final String fText = answerText;
            final String fProv = prov;
            final long fItem = actionItem;
            ui.post(new Runnable() {
                @Override public void run() {
                    for (Contacts.Contact c : contactHits) {
                        appendClio(contactText(c), "From your contacts", 0);
                    }
                    appendClio(fText, fProv, fItem);
                    store.save(ChatActivity.this);
                    setTyping(false, "");
                    busy = false;
                    sendBtn.setEnabled(true);
                    renderThread();
                }
            });
        } catch (final Exception e) {
            ui.post(new Runnable() {
                @Override public void run() {
                    appendClio("Something went wrong answering that: "
                        + e.getMessage(), "", 0);
                    store.save(ChatActivity.this);
                    setTyping(false, "");
                    busy = false;
                    sendBtn.setEnabled(true);
                    renderThread();
                }
            });
        }
    }

    private String contactText(Contacts.Contact c) {
        StringBuilder sb = new StringBuilder(c.name);
        if (!c.rel.trim().isEmpty()) sb.append(" \u00B7 ").append(c.rel.trim());
        String bp = Contacts.birthdayPretty(c);
        if (!bp.isEmpty()) sb.append("\nBirthday: ").append(bp);
        if (!c.phone.trim().isEmpty()) sb.append("\nPhone: ").append(c.phone.trim());
        if (!c.email.trim().isEmpty()) sb.append("\nEmail: ").append(c.email.trim());
        if (!c.note.trim().isEmpty()) sb.append("\n").append(c.note.trim());
        return sb.toString();
    }

    /** History for the prompt, minus the question just asked. */
    private String historyExcludingCurrent() {
        ChatStore.Chat tmp = new ChatStore.Chat();
        tmp.id = chat.id;
        tmp.digest = chat.digest;
        tmp.digestUpto = chat.digestUpto;
        int end = chat.messages.size();
        if (end > 0 && "user".equals(chat.messages.get(end - 1).role)) end--;
        for (int i = 0; i < end; i++) tmp.messages.add(chat.messages.get(i));
        if (tmp.digestUpto > tmp.messages.size()) {
            tmp.digestUpto = tmp.messages.size();
        }
        return ChatStore.historyBlock(tmp);
    }

    private void maybeCompress() {
        if (!ChatStore.needsCompression(chat)) return;
        setStatus("Tidying the conversation");
        int cut = ChatStore.compressCut(chat);
        List<ChatStore.Msg> turns = new ArrayList<ChatStore.Msg>(
            chat.messages.subList(chat.digestUpto, cut));
        String digest = null;
        if (GemmaBrain.isModelPresent(this)) {
            try {
                StringBuilder ex = new StringBuilder();
                if (chat.digest != null && !chat.digest.isEmpty()) {
                    ex.append("Summary of the conversation before this excerpt:\n")
                      .append(chat.digest).append("\n\n");
                }
                ex.append("Conversation excerpt:\n");
                for (ChatStore.Msg m : turns) {
                    ex.append("user".equals(m.role) ? "User: " : "Clio: ")
                      .append(m.text).append("\n");
                }
                ex.append("\nWrite the updated summary now.");
                digest = GemmaBrain.generate(this,
                    "Compress this conversation into a compact summary for "
                    + "future context: keep decisions, facts, names, dates, "
                    + "amounts, and open questions. Drop small talk. Plain "
                    + "sentences, no preamble, at most 180 words.",
                    ex.toString());
                if (digest != null) {
                    digest = digest.trim().replaceFirst(
                        "(?i)^(here'?s|this is) [^:\\n]*:\\s*", "").trim();
                }
            } catch (Exception e) {
                digest = null;
            }
        }
        if (digest == null || digest.isEmpty()) {
            digest = ChatStore.fallbackDigest(turns, chat.digest);
        }
        chat.digest = digest;
        chat.digestUpto = cut;
        store.save(this);
        ui.post(new Runnable() {
            @Override public void run() { renderThread(); }
        });
    }
}
