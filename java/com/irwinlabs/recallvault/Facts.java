package com.irwinlabs.recallvault;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tap-the-facts (v0.44.0): the words inside a memory stop being
 * dead text. Phone numbers dial, emails compose, links open,
 * street addresses map, tracking numbers track. Extraction is
 * pure text work; the detail page turns each fact into a button
 * (tap to use it, long-press to copy it).
 */
public final class Facts {

    private Facts() {}

    public static final int PHONE = 1;
    public static final int EMAIL = 2;
    public static final int WEB = 3;
    public static final int ADDRESS = 4;
    public static final int TRACKING = 5;

    public static final class Fact {
        public int kind;
        public String label;  // button text
        public String value;  // what gets copied
        public String uri;    // what gets opened
    }

    private static final Pattern P_PHONE =
        Pattern.compile("\\(?\\d{3}\\)?[-.\\s]\\d{3}[-.\\s]\\d{4}");
    private static final Pattern P_EMAIL =
        Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern P_WEB =
        Pattern.compile("(https?://[^\\s]+|www\\.[^\\s]+)");
    private static final Pattern P_ADDRESS =
        Pattern.compile("\\d{1,6} [A-Za-z0-9 .]+?\\b(St|Street|Ave|Avenue|Rd|Road|Blvd|Dr|Drive|Way|Ln|Lane|Ct|Court)\\b");
    private static final Pattern P_UPS =
        Pattern.compile("\\b1Z[0-9A-Z]{16}\\b");
    private static final Pattern P_USPS =
        Pattern.compile("\\b9\\d{19,21}\\b");

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }

    /** Every actionable fact in the text, in the order they appear. */
    public static List<Fact> find(String text) {
        List<Fact> out = new ArrayList<Fact>();
        if (text == null || text.isEmpty()) return out;
        Set<String> seen = new LinkedHashSet<String>();

        Matcher m = P_WEB.matcher(text);
        while (m.find()) {
            String v = m.group().replaceAll("[).,]+$", "");
            if (!seen.add("w:" + v)) continue;
            Fact f = new Fact();
            f.kind = WEB;
            f.value = v;
            f.uri = v.startsWith("http") ? v : "https://" + v;
            f.label = "Open " + v;
            out.add(f);
        }
        m = P_EMAIL.matcher(text);
        while (m.find()) {
            String v = m.group();
            if (!seen.add("e:" + v.toLowerCase(java.util.Locale.US))) continue;
            Fact f = new Fact();
            f.kind = EMAIL;
            f.value = v;
            f.uri = "mailto:" + v;
            f.label = "Email " + v;
            out.add(f);
        }
        m = P_PHONE.matcher(text);
        while (m.find()) {
            String v = m.group().trim();
            String digits = v.replaceAll("[^0-9]", "");
            if (digits.length() < 10) continue;
            if (!seen.add("p:" + digits)) continue;
            Fact f = new Fact();
            f.kind = PHONE;
            f.value = v;
            f.uri = "tel:" + (digits.length() == 10 ? "+1" + digits : "+" + digits);
            f.label = "Call " + v;
            out.add(f);
        }
        m = P_ADDRESS.matcher(text);
        while (m.find()) {
            String v = m.group().trim();
            if (!seen.add("a:" + v.toLowerCase(java.util.Locale.US))) continue;
            Fact f = new Fact();
            f.kind = ADDRESS;
            f.value = v;
            f.uri = "geo:0,0?q=" + enc(v);
            f.label = "Map: " + v;
            out.add(f);
        }
        m = P_UPS.matcher(text);
        while (m.find()) addTracking(out, seen, m.group());
        m = P_USPS.matcher(text);
        while (m.find()) addTracking(out, seen, m.group());
        return out.size() > 10 ? out.subList(0, 10) : out;
    }

    private static void addTracking(List<Fact> out, Set<String> seen, String v) {
        if (!seen.add("t:" + v)) return;
        Fact f = new Fact();
        f.kind = TRACKING;
        f.value = v;
        f.uri = "https://www.google.com/search?q=" + enc(v + " tracking");
        f.label = "Track " + v;
        out.add(f);
    }
}
