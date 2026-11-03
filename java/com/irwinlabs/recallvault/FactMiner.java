package com.irwinlabs.recallvault;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FactMiner (v0.71.0) - pulls durable one-line facts out of a
 * memory's text so ClioMind can keep them even if the memory
 * itself scrolls away. Deliberately narrow patterns: a code or
 * password assignment, a birthday, a named phone number, and
 * plain "X is Y" sentences whose predicate carries a digit, a
 * proper name or a month (the shape of a fact, not an opinion).
 * Pure Java; the JVM suite pins it against realistic texts.
 * Errs toward mining nothing - a missed fact stays in the
 * memory; an invented one would poison her memory file.
 */
public final class FactMiner {

    private FactMiner() {}

    private static final Pattern P_SECRET = Pattern.compile(
        "([^.;:\\n]{0,40}?\\b(?:code|password|passcode|pin)\\b[^.;:\\n]{0,20}?)"
        + "(?:\\bis\\b|:)\\s*([A-Za-z0-9][A-Za-z0-9\\-_@#.]{2,29})",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern P_BIRTHDAY = Pattern.compile(
        "([A-Z][a-z]+(?: [A-Z][a-z]+)?)(?:'s)?\\s+birthday is\\s+"
        + "([A-Z][a-z]+ \\d{1,2}(?:,? \\d{4})?)");
    private static final Pattern P_PHONE_NAMED = Pattern.compile(
        "([A-Z][a-z]+ [A-Z][a-z]+),?\\s+(?:phone|number|cell)\\s*(?:is\\s*)?"
        + "(\\d{3}[- ]\\d{3}[- ]\\d{4})");
    private static final Pattern P_PHONE_BARE = Pattern.compile(
        "([A-Z][a-z]+(?:'s)? [A-Z][a-z]+),?\\s+(\\d{3}[- ]\\d{3}[- ]\\d{4})");
    private static final Pattern P_IS = Pattern.compile(
        "([A-Z][^.;\\n]{2,50}?)\\s+is\\s+([^.;\\n]{3,70})\\.");
    private static final Pattern P_MONTH = Pattern.compile(
        "(January|February|March|April|May|June|July|August|September|"
        + "October|November|December)");

    private static final String[] VALUE_STOP = {
        "the", "on", "a", "an", "same", "in", "at", "to", "under", "over"};

    public static List<String> mine(String title, String text) {
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        if (text == null) return new ArrayList<String>(out);
        // Title and body join behind a sentence break so a
        // pattern's subject can never sprawl across the seam.
        String all = ((title == null ? "" : title) + ". " + text).replace('\n', ' ');

        Matcher m = P_SECRET.matcher(all);
        while (m.find() && out.size() < 4) {
            String subject = cleanSubject(m.group(1));
            String value = m.group(2).replaceAll("[.,;]+$", "");
            if (subject.isEmpty() || isStopValue(value)) continue;
            out.add(capitalize(subject) + " is " + value + ".");
        }
        m = P_BIRTHDAY.matcher(all);
        while (m.find() && out.size() < 4) {
            out.add(m.group(1) + "'s birthday is " + m.group(2) + ".");
        }
        m = P_PHONE_NAMED.matcher(all);
        while (m.find() && out.size() < 4) {
            out.add(m.group(1) + "'s phone is " + m.group(2) + ".");
        }
        m = P_PHONE_BARE.matcher(all);
        while (m.find() && out.size() < 4) {
            out.add("The phone number for " + m.group(1) + " is " + m.group(2) + ".");
        }
        m = P_IS.matcher(all);
        while (m.find() && out.size() < 4) {
            String subj = m.group(1).trim();
            String pred = m.group(2).trim();
            if (subj.split(" ").length > 7) continue;
            if (!hasFactShape(pred)) continue;
            out.add(subj + " is " + pred + ".");
        }
        return new ArrayList<String>(out);
    }

    /** A predicate looks factual when it carries a number, a
     *  capitalized name, or a month - not bare prose. */
    private static boolean hasFactShape(String pred) {
        if (pred.matches(".*\\d.*")) return true;
        if (P_MONTH.matcher(pred).find()) return true;
        String[] words = pred.split(" ");
        for (int i = 1; i < words.length; i++) {
            String w = words[i].replaceAll("[^A-Za-z]", "");
            if (w.length() > 1 && Character.isUpperCase(w.charAt(0))
                && w.substring(1).equals(w.substring(1).toLowerCase(Locale.US))) {
                return true;
            }
        }
        return false;
    }

    private static String cleanSubject(String raw) {
        String s = raw.trim().replaceAll("\\s+", " ");
        // Keep only the last clause: "Network: IrwinHome, password"
        // becomes "password".
        int cut = Math.max(s.lastIndexOf(','), s.lastIndexOf(':'));
        if (cut >= 0 && s.length() - cut > 3) s = s.substring(cut + 1).trim();
        String[] words = s.split(" ");
        if (words.length > 6) {
            StringBuilder sb = new StringBuilder();
            for (int i = words.length - 6; i < words.length; i++) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(words[i]);
            }
            s = sb.toString();
        }
        return s;
    }

    private static boolean isStopValue(String v) {
        String lv = v.toLowerCase(Locale.US);
        for (String s : VALUE_STOP) if (lv.equals(s)) return true;
        return false;
    }

    private static String capitalize(String s) {
        if (s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
