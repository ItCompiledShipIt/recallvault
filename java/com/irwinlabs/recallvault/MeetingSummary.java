package com.irwinlabs.recallvault;

/**
 * Meeting auto-summary (v0.74.0): when a meeting's full
 * transcript lands and the on-device brain is present, Clio
 * writes a short narrative summary and stores it as the
 * meeting's own text - the words a person reads first, above
 * the ledger and the transcript. Pure prompt/shaping logic
 * here; VoiceActivity does the generating. The summary is
 * written once, only into a meeting whose body is still
 * empty (a dictated note in the body is never overwritten).
 */
public final class MeetingSummary {

    private MeetingSummary() {}

    public static boolean shouldSummarize(boolean meeting, String body) {
        return meeting && (body == null || body.trim().isEmpty());
    }

    public static final String SYSTEM =
        "You are Clio, keeper of this person's memories. You summarize meetings they recorded. "
        + "Plain words, the key facts first, dates and amounts kept exact. "
        + "Never add anything that is not in the transcript. No preamble, no sign-off.";

    public static String prompt(String transcript) {
        String t = transcript == null ? "" : transcript.trim();
        if (t.length() > 6000) t = t.substring(0, 6000);
        return "Summarize this meeting in 2-4 short sentences: what it was about, "
            + "what was decided, and what happens next.\n\n" + t;
    }

    /** Keep a generated summary only if it is real prose, not
     *  an error, an empty string, or a runaway generation. */
    public static String clean(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.length() < 40 || s.length() > 1200) return "";
        String low = s.toLowerCase(java.util.Locale.US);
        if (low.startsWith("error") || low.contains("i can't") || low.contains("i cannot")) {
            return "";
        }
        return s;
    }
}
