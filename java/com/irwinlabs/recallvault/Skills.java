package com.irwinlabs.recallvault;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Skills - Agent Skills for the brain, in the same SKILL.md format
 * Google AI Edge Gallery's community uses (the agentskills.io
 * shape): a folder holding a SKILL.md whose YAML frontmatter names
 * and describes the skill, with plain-language instructions below
 * that the model follows when the skill is active.
 *
 * Skills live in two places: bundled in assets/skills/ (ship with
 * the app) and imported into files/skills/ (the user's own, picked
 * through the file picker or dropped in as a folder). An imported
 * skill replaces a bundled one with the same name. Skills are
 * instruction packs - they carry no code - and the tools they can
 * ask the model to use are the app's own BrainTools.
 *
 * Enabled state is a pref per skill (default on). The Ask/brain
 * system prompt carries the catalog (name + description of every
 * enabled skill); the full instructions of the skills the user
 * picked for a run are appended, which is the progressive-
 * disclosure shape the format intends.
 */
public final class Skills {

    private Skills() {}

    public static final String COMMUNITY_URL =
        "https://github.com/google-ai-edge/gallery/discussions/categories/skills";

    public static final class Skill {
        public final String name;
        public final String description;
        public final String instructions;
        public final boolean bundled;
        public final String dirName;

        Skill(String name, String description, String instructions,
              boolean bundled, String dirName) {
            this.name = name;
            this.description = description;
            this.instructions = instructions;
            this.bundled = bundled;
            this.dirName = dirName;
        }
    }

    // ------------------------------------------------------------------
    // Parsing - pure, JVM-tested.
    // ------------------------------------------------------------------

    /**
     * Parse SKILL.md text. Frontmatter is the --- ... --- block at
     * the top; name and description are single-line key: value
     * pairs (quotes tolerated). Returns null without a name.
     */
    public static Skill parse(String md, boolean bundled, String dirName) {
        if (md == null) return null;
        String text = md.replace("\r\n", "\n");
        if (!text.startsWith("---")) return null;
        int end = text.indexOf("\n---", 3);
        if (end < 0) return null;
        String front = text.substring(3, end);
        String body = text.substring(end + 4).trim();
        String name = null, description = "";
        for (String line : front.split("\n")) {
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String key = line.substring(0, colon).trim();
            String val = line.substring(colon + 1).trim();
            if (val.length() >= 2 && val.startsWith("\"") && val.endsWith("\"")) {
                val = val.substring(1, val.length() - 1);
            }
            if (key.equals("name")) name = val;
            else if (key.equals("description")) description = val;
        }
        if (name == null || name.isEmpty()) return null;
        return new Skill(name, description, body, bundled, dirName);
    }

    /** Catalog block for the system prompt: one line per skill. */
    public static String catalogBlock(List<Skill> skills) {
        if (skills == null || skills.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("SKILLS YOU CAN USE (follow a skill's instructions when the request matches it):");
        for (Skill s : skills) {
            sb.append("\n- ").append(s.name).append(": ").append(s.description);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Registry.
    // ------------------------------------------------------------------

    public static File skillsDir(Context ctx) {
        return new File(ctx.getFilesDir(), "skills");
    }

    /** All skills: bundled first, imported overriding by name. */
    public static List<Skill> loadAll(Context ctx) {
        Map<String, Skill> byName = new LinkedHashMap<>();
        // Bundled: assets/skills/<dir>/SKILL.md
        try {
            String[] dirs = ctx.getAssets().list("skills");
            if (dirs != null) {
                for (String d : dirs) {
                    String md = readAll(ctx.getAssets().open("skills/" + d + "/SKILL.md"));
                    Skill s = parse(md, true, d);
                    if (s != null) byName.put(s.name, s);
                }
            }
        } catch (Exception e) { /* no bundled skills is survivable */ }
        // Imported: files/skills/<dir>/SKILL.md
        File root = skillsDir(ctx);
        File[] subs = root.listFiles();
        if (subs != null) {
            for (File sub : subs) {
                if (!sub.isDirectory()) continue;
                File f = new File(sub, "SKILL.md");
                if (!f.isFile()) continue;
                try {
                    Skill s = parse(readAll(new FileInputStream(f)), false, sub.getName());
                    if (s != null) byName.put(s.name, s);
                } catch (Exception e) { /* skip unreadable skill */ }
            }
        }
        return new ArrayList<>(byName.values());
    }

    /** Install an imported SKILL.md. Returns the parsed skill or null. */
    public static Skill install(Context ctx, String mdText) {
        // Parse once against a placeholder dir to learn the name.
        Skill probe = parse(mdText, false, "x");
        if (probe == null) return null;
        String dir = probe.name.toLowerCase(java.util.Locale.US)
            .replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (dir.isEmpty()) return null;
        File sub = new File(skillsDir(ctx), dir);
        if (!sub.exists() && !sub.mkdirs()) return null;
        try {
            java.io.FileOutputStream out = new java.io.FileOutputStream(new File(sub, "SKILL.md"));
            out.write(mdText.getBytes(StandardCharsets.UTF_8));
            out.close();
        } catch (Exception e) {
            return null;
        }
        return parse(mdText, false, dir);
    }

    public static void remove(Context ctx, Skill skill) {
        if (skill.bundled) return;
        File sub = new File(skillsDir(ctx), skill.dirName);
        File f = new File(sub, "SKILL.md");
        if (f.exists()) f.delete();
        sub.delete();
    }

    // ------------------------------------------------------------------
    // Enabled state.
    // ------------------------------------------------------------------

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(Embed.PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context ctx, Skill s) {
        return prefs(ctx).getBoolean("skill_on_" + s.name, true);
    }

    public static void setEnabled(Context ctx, Skill s, boolean on) {
        prefs(ctx).edit().putBoolean("skill_on_" + s.name, on).apply();
    }

    public static List<Skill> enabled(Context ctx, List<Skill> all) {
        List<Skill> out = new ArrayList<>();
        for (Skill s : all) if (isEnabled(ctx, s)) out.add(s);
        return out;
    }

    private static String readAll(InputStream in) throws Exception {
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toString("UTF-8");
        } finally {
            try { in.close(); } catch (Exception e) { /* ignore */ }
        }
    }
}
