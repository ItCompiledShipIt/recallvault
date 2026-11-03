package com.irwinlabs.recallvault;

import android.content.Context;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Clio's mind (v0.64.0, chris's ask: "give Clio an
 * AGENTS.md in the backend - make her like you, where
 * she will save important info and update her memory
 * files. Self improvement.").
 *
 * Three files in files/brain/, all plain markdown, all
 * on this phone only:
 *   AGENTS.md        - her standing instructions; she
 *                      reads them every time she thinks.
 *                      Seeded with a default written for
 *                      her; the user can edit or reset
 *                      them in Settings.
 *   MEMORY.md        - her curated long-term memory:
 *                      durable facts about the user that
 *                      she saves through her remember
 *                      tool, dated and deduplicated.
 *   memory/<day>.md  - a daily log; every remembered
 *                      fact lands here too, a raw trail
 *                      under the curated file.
 *
 * Her system prompt carries both files (truncated), so
 * what she saves genuinely shapes what she knows next
 * time - and the user can read, edit or clear every
 * word of it from Settings.
 */
public class ClioMind {

    public static final String DEFAULT_AGENTS = String.join("\n",
        "# AGENTS.md - Clio's operating manual",
        "",
        "You are Clio, the muse of history, keeping one person's",
        "private memory vault on their own phone. Everything you",
        "know lives here: their saved memories, their contact",
        "library, and this memory of yours. Nothing leaves the",
        "phone, ever.",
        "",
        "## How you work",
        "",
        "- Answer from the vault, the contacts and your memory",
        "  files first. Say plainly when they do not hold the",
        "  answer. Never invent dates, amounts, or names.",
        "- Plain words, brief unless asked for more. No",
        "  performative warmth, no filler.",
        "- Your tools are read-only except one: remember. Use",
        "  it only for facts that will still matter next month.",
        "",
        "## What to save (with the remember tool)",
        "",
        "- Preferences the user states, and corrections to",
        "  things you had wrong.",
        "- People facts: names, relationships, birthdays,",
        "  how someone matters to the user.",
        "- Decisions and commitments, with their dates.",
        "- Anything the user explicitly asks you to remember.",
        "",
        "Never save: passwords or codes of any kind, one-off",
        "chatter, anything already in the vault as a memory,",
        "or guesses dressed as facts.",
        "",
        "## Self-improvement",
        "",
        "- When the user corrects you, save the correction.",
        "- When a new fact contradicts your memory, trust the",
        "  newer one, save it, and say so.",
        "- Keep facts short and dated. If your memory grows",
        "  stale or duplicated, say what you would tidy and",
        "  let the user confirm in Settings, where both of",
        "  these files can be read and edited.",
        "",
        "## Lines that do not move",
        "",
        "- Private means private: nothing is shared, sent or",
        "  uploaded, and private memories are never quoted.",
        "- You suggest; the user decides. Money, messages and",
        "  deletions are theirs alone.",
        "");

    private static File dir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "brain");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static File agentsFile(Context ctx) {
        return new File(dir(ctx), "AGENTS.md");
    }

    public static File memoryFile(Context ctx) {
        return new File(dir(ctx), "MEMORY.md");
    }

    private static File dailyFile(Context ctx, Date when) {
        File d = new File(dir(ctx), "memory");
        if (!d.exists()) d.mkdirs();
        return new File(d, new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(when) + ".md");
    }

    /** Seed the files on first use; never overwrite. */
    public static void ensureSeeded(Context ctx) {
        try {
            File a = agentsFile(ctx);
            if (!a.exists()) write(a, DEFAULT_AGENTS);
            File m = memoryFile(ctx);
            if (!m.exists()) {
                write(m, "# MEMORY.md - what Clio remembers\n\n"
                    + "Durable facts Clio has saved about the user, newest last.\n"
                    + "She reads this every time she thinks. It can be edited\n"
                    + "or cleared in Settings at any time.\n");
            }
        } catch (Exception ignored) {}
    }

    private static String read(File f) {
        try {
            if (!f.exists()) return "";
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static void write(File f, String s) throws Exception {
        Files.write(f.toPath(), s.getBytes(StandardCharsets.UTF_8));
    }

    public static String readAgents(Context ctx) {
        ensureSeeded(ctx);
        return read(agentsFile(ctx));
    }

    public static String readMemory(Context ctx) {
        ensureSeeded(ctx);
        return read(memoryFile(ctx));
    }

    public static boolean writeAgents(Context ctx, String text) {
        try {
            write(agentsFile(ctx), text);
            // A local edit stamps the sync clock (v0.67.0).
            ctx.getSharedPreferences(Embed.PREFS, Context.MODE_PRIVATE).edit()
                .putLong(PREF_AGENTS_TS, System.currentTimeMillis()).apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean writeMemory(Context ctx, String text) {
        try {
            write(memoryFile(ctx), text);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean resetAgents(Context ctx) {
        return writeAgents(ctx, DEFAULT_AGENTS);
    }

    /** Count of remembered facts (the dated bullets). */
    public static int factCount(Context ctx) {
        String m = readMemory(ctx);
        int n = 0;
        for (String line : m.split("\n")) if (line.startsWith("- (")) n++;
        return n;
    }

    /**
     * Save one durable fact: a dated bullet in MEMORY.md
     * (exact duplicates skipped) and a line in today's
     * daily note. Returns a plain-words result for the
     * brain to speak.
     */
    public static synchronized String remember(Context ctx, String fact) {
        String f = fact == null ? "" : fact.trim().replace('\n', ' ');
        if (f.isEmpty()) return "Nothing to save.";
        if (f.length() > 400) f = f.substring(0, 400);
        ensureSeeded(ctx);
        try {
            String mem = readMemory(ctx);
            for (String line : mem.split("\n")) {
                if (line.toLowerCase(Locale.US).contains(f.toLowerCase(Locale.US))) {
                    return "Already in your memory: " + f;
                }
            }
            Date now = new Date();
            String day = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now);
            String bullet = "- (" + day + ") " + f + "\n";
            write(memoryFile(ctx), mem + (mem.endsWith("\n") || mem.isEmpty() ? "" : "\n") + bullet);
            File daily = dailyFile(ctx, now);
            String d = read(daily);
            if (d.isEmpty()) d = "# " + day + "\n\n";
            String hhmm = new SimpleDateFormat("HH:mm", Locale.US).format(now);
            write(daily, d + "- " + hhmm + " remembered: " + f + "\n");
            return "Saved to your memory: " + f;
        } catch (Exception e) {
            return "Could not save that just now.";
        }
    }

    /**
     * The companion files a full backup carries (v0.65.0,
     * photos added v0.66.0): the contact library, the mind
     * files, the profile photo and every contact photo -
     * entry name to file, ready for Backup.buildZip.
     */
    public static java.util.Map<String, File> backupExtras(Context ctx) {
        java.util.Map<String, File> extras = new java.util.LinkedHashMap<String, File>();
        extras.put("contacts.json", new File(ctx.getFilesDir(), "contacts.json"));
        extras.put("brain/AGENTS.md", agentsFile(ctx));
        extras.put("brain/MEMORY.md", memoryFile(ctx));
        extras.put("profile.jpg", ContactPhotos.profileFile(ctx));
        File[] photos = ContactPhotos.dir(ctx).listFiles();
        if (photos != null) {
            for (File p : photos) {
                extras.put("contact_photos/" + p.getName(), p);
            }
        }
        return extras;
    }

    // ---- sync support (v0.67.0) ----

    public static final String PREF_AGENTS_TS = "mind_agents_ts";

    public static long agentsUpdatedAt(Context ctx) {
        return ctx.getSharedPreferences(Embed.PREFS, Context.MODE_PRIVATE)
            .getLong(PREF_AGENTS_TS, 0);
    }

    public static void writeAgents(Context ctx, String text, long updatedAt) {
        ensureSeeded(ctx);
        try {
            write(agentsFile(ctx), text);
        } catch (Exception ignored) {}
        android.content.SharedPreferences.Editor e =
            ctx.getSharedPreferences(Embed.PREFS, Context.MODE_PRIVATE).edit();
        e.putLong(PREF_AGENTS_TS, updatedAt).apply();
    }

    /** The fact lines of MEMORY.md - the part sync unions. */
    public static java.util.List<String> factLines(Context ctx) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        for (String ln : readMemory(ctx).split("\n")) {
            if (ln.startsWith("- (")) out.add(ln.trim());
        }
        return out;
    }

    /** Replace MEMORY.md's facts with a unioned set, keeping
     *  this device's header and any non-fact lines. */
    public static void applyFactUnion(Context ctx, java.util.List<String> facts) {
        String cur = readMemory(ctx);
        StringBuilder head = new StringBuilder();
        for (String ln : cur.split("\n", -1)) {
            if (!ln.startsWith("- (")) head.append(ln).append("\n");
        }
        String h = head.toString().replaceAll("\n+$", "\n");
        StringBuilder out = new StringBuilder(h);
        if (!h.endsWith("\n")) out.append("\n");
        for (String f : facts) out.append(f).append("\n");
        try {
            write(memoryFile(ctx), out.toString());
        } catch (Exception ignored) {}
    }

    public static final String PREF_REVIEW_DAY = "mind_review_day";
    public static final String PREF_REVIEW_NOTE = "mind_review_note";

    /**
     * The nightly self-review (v0.66.0), run from the daily
     * reminder pass: fold duplicate facts (the newest wording
     * wins), keep every header and every distinct fact, log
     * the review in today's daily note, and leave a one-line
     * summary the Settings card can show. She never deletes
     * a distinct fact on her own - tidying is folding, not
     * forgetting. Runs at most once a day.
     */
    public static void nightlyReview(Context ctx) {
        android.content.SharedPreferences prefs =
            ctx.getSharedPreferences(Embed.PREFS, Context.MODE_PRIVATE);
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        if (today.equals(prefs.getString(PREF_REVIEW_DAY, ""))) return;
        prefs.edit().putString(PREF_REVIEW_DAY, today).apply();
        ensureSeeded(ctx);
        try {
            String mem = readMemory(ctx);
            String[] lines = mem.split("\n", -1);
            java.util.Map<String, Integer> lastSeen = new java.util.HashMap<String, Integer>();
            java.util.List<String> facts = new java.util.ArrayList<String>();
            for (String ln : lines) {
                if (ln.startsWith("- (")) facts.add(ln);
            }
            for (int i = 0; i < facts.size(); i++) {
                lastSeen.put(normalize(facts.get(i)), i);
            }
            java.util.Set<Integer> keepIdx = new java.util.HashSet<Integer>(lastSeen.values());
            StringBuilder out = new StringBuilder();
            int factIdx = -1, folded = 0, kept = 0;
            for (String ln : lines) {
                if (ln.startsWith("- (")) {
                    factIdx++;
                    if (keepIdx.contains(factIdx)) {
                        out.append(ln).append("\n");
                        kept++;
                    } else {
                        folded++;
                    }
                } else {
                    out.append(ln).append("\n");
                }
            }
            String rebuilt = out.toString();
            if (!rebuilt.equals(mem) && !rebuilt.equals(mem + "\n")) {
                write(memoryFile(ctx), rebuilt);
            }
            String note = today + ": " + kept + " facts"
                + (folded > 0 ? ", " + folded + " duplicate" + (folded == 1 ? "" : "s") + " folded" : ", no duplicates")
                + (kept > 300 ? " - memory is getting long" : "");
            prefs.edit().putString(PREF_REVIEW_NOTE, note).apply();
            File daily = dailyFile(ctx, new Date());
            String d = read(daily);
            if (d.isEmpty()) d = "# " + today + "\n\n";
            String hhmm = new SimpleDateFormat("HH:mm", Locale.US).format(new Date());
            write(daily, d + "- " + hhmm + " self-review: " + note + "\n");
        } catch (Exception ignored) {
            // A failed review never breaks the reminder pass.
        }
    }

    private static String normalize(String factLine) {
        String s = factLine.toLowerCase(Locale.US).replaceAll("\\s+", " ").trim();
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        s = s.trim();
        return s.length() > max ? s.substring(0, max) + "\n[...truncated]" : s;
    }

    /** The block her system prompt carries: her standing
     *  instructions and her memory, so both genuinely
     *  shape every answer. Empty when both are blank. */
    public static String promptBlock(Context ctx) {
        String a = truncate(readAgents(ctx), 4000);
        String m = truncate(readMemory(ctx), 6000);
        if (a.isEmpty() && m.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        if (!a.isEmpty()) {
            sb.append("\n\nYOUR STANDING INSTRUCTIONS (AGENTS.md - the user owns this file and can edit it; follow it):\n").append(a);
        }
        if (!m.isEmpty()) {
            sb.append("\n\nWHAT YOU REMEMBER (MEMORY.md - facts you saved about the user; trust these, and save new durable facts with the remember tool):\n").append(m);
        }
        return sb.toString();
    }
}
