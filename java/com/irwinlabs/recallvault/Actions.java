package com.irwinlabs.recallvault;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Meeting action items (v0.29.0): pull the commitment sentences out
 * of a meeting transcript - "I'll send the lease by Friday",
 * "we need to call the landlord" - and file each as its own memory
 * in the Actions category, linked back to the meeting. Extraction
 * is deliberately generous: a stray non-action is one tap to
 * trash, a missed commitment is gone for good.
 */
public final class Actions {

    private Actions() {}

    public static final class Action {
        public String text;    // the sentence, trimmed, display-ready
        public long dueMs;     // parsed deadline or -1
    }

    private static final String[] CUES = {
        "i'll ", "i will ", "we'll ", "we will ", "need to ", "needs to ",
        "have to ", "has to ", "remember to ", "don't forget", "do not forget",
        "send the", "send a", "send it", "send that", "email the", "email it",
        "call the", "call him", "call her", "call them", "sign the", "sign it",
        "schedule ", "book ", "pay the", "pay it", "pay for", "file the",
        "submit ", "follow up", "pick up", "drop off", "renew ", "bring the",
        "bring it", "order ", "check on", "ask about", "look into",
    };

    /** Find commitment sentences in a transcript. */
    public static List<Action> extract(String text) {
        List<Action> out = new ArrayList<Action>();
        if (text == null) return out;
        Set<String> seen = new HashSet<String>();
        String[] parts = text.split("[.!?\\n]+");
        for (String raw : parts) {
            String s = raw.trim().replaceAll("\\s+", " ");
            if (s.length() < 12 || s.length() > 220) continue;
            String low = s.toLowerCase(Locale.US);
            boolean cue = false;
            for (String c : CUES) {
                if (low.contains(c)) { cue = true; break; }
            }
            if (!cue) continue;
            String norm = low;
            if (!seen.add(norm)) continue;
            Action a = new Action();
            String t = s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
            a.text = Character.toUpperCase(t.charAt(0)) + t.substring(1);
            Extract.Facts f = Extract.analyze(s);
            a.dueMs = f.dueDate;
            out.add(a);
        }
        return out;
    }

    /**
     * File the actions found in a meeting's text. Returns how many
     * new items were created. Duplicates (same words already filed
     * for this meeting) are skipped, so re-running is safe.
     */
    public static int fileActions(Db db, Item meeting, String text) {
        List<Action> found = extract(text);
        if (found.isEmpty()) return 0;
        Set<String> existing = new HashSet<String>();
        for (Item it : db.byCategory("Actions")) {
            if (Links.contains(it.links, meeting.id)) {
                existing.add(it.title.toLowerCase(Locale.US));
            }
        }
        int made = 0;
        long now = System.currentTimeMillis();
        for (Action a : found) {
            String title = a.text.length() > 90 ? a.text.substring(0, 87).trim() + "..." : a.text;
            if (!existing.add(title.toLowerCase(Locale.US))) continue;
            Item it = new Item();
            it.type = "TEXT";
            it.category = "Actions";
            it.title = title;
            it.body = "From the meeting: " + meeting.title + "\n\n" + a.text;
            it.extracted = "";
            it.createdAt = now + made; // stable order within the batch
            it.reminderAt = a.dueMs > 0 ? a.dueMs : 0;
            it.links = Links.with("", meeting.id);
            db.insert(it);
            // Link both ways, like every other link in the vault.
            Item m = db.get(meeting.id);
            if (m != null) {
                m.links = Links.with(m.links, it.id);
                db.update(m);
            }
            made++;
        }
        return made;
    }
}
