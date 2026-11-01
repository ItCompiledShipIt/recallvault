package com.irwinlabs.recallvault;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Meeting recaps (v0.38.0): the top of a meeting's page answers
 * "what came out of that?" without re-reading the transcript -
 * what was decided, what is owed by whom (the same extraction
 * that files action items), the dates that matter, and any
 * money that was mentioned. Pure text work over the transcript.
 */
public final class Recap {

    private Recap() {}

    private static final String[] DECISION_CUES = {
        "decided", "agreed", "we'll go with", "we will go with", "going with",
        "approved", "confirmed", "the plan is", "final answer", "locked in",
        "signed off", "we chose", "we picked", "settled on", "agreement is",
    };

    /** Sentences that record an outcome rather than a task. */
    public static List<String> decisions(String text) {
        List<String> out = new ArrayList<String>();
        if (text == null) return out;
        Set<String> seen = new HashSet<String>();
        for (String raw : text.split("[.!?\\n]+")) {
            String s = raw.trim().replaceAll("\\s+", " ");
            if (s.length() < 12 || s.length() > 240) continue;
            String low = s.toLowerCase(Locale.US);
            boolean cue = false;
            for (String c : DECISION_CUES) {
                if (low.contains(c)) { cue = true; break; }
            }
            if (!cue) continue;
            if (!seen.add(low)) continue;
            out.add(Character.toUpperCase(s.charAt(0)) + s.substring(1));
            if (out.size() >= 6) break;
        }
        return out;
    }

    /** The whole recap as display lines; empty when nothing was found. */
    public static List<String> lines(String text) {
        List<String> out = new ArrayList<String>();
        if (text == null || text.trim().isEmpty()) return out;
        List<String> dec = decisions(text);
        if (!dec.isEmpty()) {
            out.add("Decided:");
            for (String d : dec) out.add("- " + d);
        }
        List<Actions.Action> acts = Actions.extract(text);
        // A sentence that already ran as a decision ("We decided to
        // renew...") is not also a to-do - the recap never repeats.
        Set<String> decidedLow = new HashSet<String>();
        for (String d : dec) decidedLow.add(d.toLowerCase(Locale.US));
        List<Actions.Action> todos = new ArrayList<Actions.Action>();
        for (Actions.Action a : acts) {
            if (!decidedLow.contains(a.text.toLowerCase(Locale.US))) todos.add(a);
        }
        if (!todos.isEmpty()) {
            out.add("To do:");
            int shown = 0;
            for (Actions.Action a : todos) {
                if (shown++ >= 6) { out.add("- ...and " + (todos.size() - 6) + " more"); break; }
                out.add("- " + a.text + (a.dueMs > 0 ? " (due " + Extract.formatDate(a.dueMs) + ")" : ""));
            }
        }
        Extract.Facts f = Extract.analyze(text);
        if (!f.dates.isEmpty()) {
            List<Long> dates = new ArrayList<Long>(new HashSet<Long>(f.dates));
            Collections.sort(dates);
            StringBuilder db = new StringBuilder("Key dates: ");
            for (int i = 0; i < dates.size() && i < 5; i++) {
                if (i > 0) db.append(", ");
                db.append(Extract.formatDate(dates.get(i)));
            }
            out.add(db.toString());
        }
        if (!f.amounts.isEmpty()) {
            StringBuilder mb = new StringBuilder("Money mentioned: ");
            for (int i = 0; i < f.amounts.size() && i < 5; i++) {
                if (i > 0) mb.append(", ");
                mb.append(f.amounts.get(i));
            }
            out.add(mb.toString());
        }
        return out;
    }

    /** One text block for cards and dialogs; null when there is no recap. */
    public static String recapText(String text) {
        List<String> ls = lines(text);
        if (ls.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (String l : ls) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(l);
        }
        return sb.toString();
    }
}
