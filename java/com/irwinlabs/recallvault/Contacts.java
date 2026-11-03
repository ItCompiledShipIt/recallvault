package com.irwinlabs.recallvault;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The contact library (v0.62.0, grown v0.63.0): the people
 * who matter, kept on this phone as a small JSON file in
 * the app's private storage. Not the vault database -
 * contacts are reference cards, not memories, and they
 * never leave the device. The vault connects back the
 * other way: a contact's page gathers every memory that
 * mentions them, the brain can look a contact up, and
 * birthdays feed the reminder pass.
 */
public class Contacts {

    public static final String[] RELS = {"Family", "Friend", "Medical", "Home", "Work", "Other"};

    public static class Contact {
        public long id;
        public String name = "";
        public String rel = "";
        public String phone = "";
        public String email = "";
        public String address = "";
        /** ISO date "yyyy-MM-dd", or "" when unknown. */
        public String birthday = "";
        public String org = "";
        public String note = "";
        public boolean fav = false;
        /** Photo filename inside files/contact_photos/, or "". */
        public String photo = "";
        /** Sync identity + last-writer-wins clock (v0.67.0). */
        public String uuid = "";
        public long updatedAt = 0;

        public String firstName() {
            String n = name.trim();
            int sp = n.indexOf(' ');
            return sp > 0 ? n.substring(0, sp) : n;
        }
    }

    private static File file(Context ctx) {
        return new File(ctx.getFilesDir(), "contacts.json");
    }

    public static List<Contact> load(Context ctx) {
        List<Contact> out = new ArrayList<Contact>();
        try {
            File f = file(ctx);
            if (!f.exists()) return out;
            String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            JSONArray arr = new JSONArray(s);
            boolean healed = false;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Contact c = new Contact();
                c.id = o.optLong("id", 0);
                c.name = o.optString("name", "");
                c.rel = o.optString("rel", "");
                c.phone = o.optString("phone", "");
                c.email = o.optString("email", "");
                c.address = o.optString("address", "");
                c.birthday = o.optString("birthday", "");
                c.org = o.optString("org", "");
                c.note = o.optString("note", "");
                c.fav = o.optBoolean("fav", false);
                c.photo = o.optString("photo", "");
                c.uuid = o.optString("uuid", "");
                c.updatedAt = o.optLong("updated_at", 0);
                if (c.uuid.isEmpty()) {
                    c.uuid = java.util.UUID.randomUUID().toString();
                    healed = true;
                }
                if (c.updatedAt == 0) {
                    c.updatedAt = c.id > 0 ? c.id : System.currentTimeMillis();
                    healed = true;
                }
                out.add(c);
            }
            if (healed) save(ctx, out);
        } catch (Exception e) {
            // A damaged file reads as an empty library rather
            // than crashing the page.
        }
        return out;
    }

    public static Contact byId(Context ctx, long id) {
        for (Contact c : load(ctx)) if (c.id == id) return c;
        return null;
    }

    public static void save(Context ctx, List<Contact> list) {
        try {
            JSONArray arr = new JSONArray();
            for (Contact c : list) {
                JSONObject o = new JSONObject();
                o.put("id", c.id);
                o.put("name", c.name);
                o.put("rel", c.rel);
                o.put("phone", c.phone);
                o.put("email", c.email);
                o.put("address", c.address);
                o.put("birthday", c.birthday);
                o.put("org", c.org);
                o.put("note", c.note);
                o.put("fav", c.fav);
                o.put("photo", c.photo);
                o.put("uuid", c.uuid == null ? "" : c.uuid);
                o.put("updated_at", c.updatedAt);
                arr.put(o);
            }
            Files.write(file(ctx).toPath(), arr.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            // Best effort; the editor reports what it shows.
        }
    }

    /** Insert or update by id; a new contact gets a fresh id. */
    public static void upsert(Context ctx, Contact c) {
        if (c.uuid == null || c.uuid.isEmpty()) {
            c.uuid = java.util.UUID.randomUUID().toString();
        }
        c.updatedAt = System.currentTimeMillis();
        List<Contact> list = load(ctx);
        if (c.id == 0) {
            c.id = System.currentTimeMillis();
            list.add(c);
        } else {
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).id == c.id) { list.set(i, c); break; }
            }
        }
        save(ctx, list);
    }

    /** Write a contact that arrived from sync, keeping its clock. */
    public static void applyRemote(Context ctx, Contact c) {
        List<Contact> list = load(ctx);
        boolean found = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).uuid.equals(c.uuid)) {
                c.id = list.get(i).id;
                list.set(i, c);
                found = true;
                break;
            }
        }
        if (!found) {
            c.id = System.currentTimeMillis();
            // Millisecond ids can collide under fast adds.
            boolean clash = true;
            while (clash) {
                clash = false;
                for (Contact x : list) if (x.id == c.id) { c.id++; clash = true; }
            }
            list.add(c);
        }
        save(ctx, list);
        clearContactTombstone(ctx, c.uuid);
    }

    public static Contact byUuid(Context ctx, String uuid) {
        if (uuid == null || uuid.isEmpty()) return null;
        for (Contact c : load(ctx)) if (uuid.equals(c.uuid)) return c;
        return null;
    }

    public static void delete(Context ctx, long id) {
        List<Contact> list = load(ctx);
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).id == id) {
                if (!list.get(i).uuid.isEmpty()) {
                    addContactTombstone(ctx, list.get(i).uuid, System.currentTimeMillis());
                }
                list.remove(i);
            }
        }
        save(ctx, list);
    }

    // ---- contact tombstones (sync, v0.67.0) ----

    private static File tombFile(Context ctx) {
        return new File(ctx.getFilesDir(), "contact_tombstones.json");
    }

    /** [uuid, deletedAtMillis] pairs. */
    public static List<String[]> contactTombstones(Context ctx) {
        List<String[]> out = new ArrayList<String[]>();
        try {
            File f = tombFile(ctx);
            if (!f.exists()) return out;
            JSONArray arr = new JSONArray(new String(
                Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new String[]{o.optString("uuid", ""),
                    String.valueOf(o.optLong("deleted_at", 0))});
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static void addContactTombstone(Context ctx, String uuid, long deletedAt) {
        List<String[]> cur = contactTombstones(ctx);
        cur.removeIf(t -> t[0].equals(uuid));
        cur.add(new String[]{uuid, String.valueOf(deletedAt)});
        writeContactTombstones(ctx, cur);
    }

    public static void clearContactTombstone(Context ctx, String uuid) {
        List<String[]> cur = contactTombstones(ctx);
        if (cur.removeIf(t -> t[0].equals(uuid))) writeContactTombstones(ctx, cur);
    }

    private static void writeContactTombstones(Context ctx, List<String[]> tombs) {
        try {
            JSONArray arr = new JSONArray();
            for (String[] t : tombs) {
                JSONObject o = new JSONObject();
                o.put("uuid", t[0]);
                o.put("deleted_at", Long.parseLong(t[1]));
                arr.put(o);
            }
            Files.write(tombFile(ctx).toPath(), arr.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }

    // ---- Birthdays -------------------------------------------------

    /** The next occurrence of the contact's birthday on or
     *  after today, or null when no (valid) birthday is set. */
    public static LocalDate nextBirthday(Contact c, LocalDate today) {
        if (c.birthday == null || c.birthday.trim().isEmpty()) return null;
        try {
            LocalDate b = LocalDate.parse(c.birthday.trim());
            LocalDate cand = LocalDate.of(today.getYear(), b.getMonth(), b.getDayOfMonth());
            if (cand.isBefore(today)) cand = cand.plusYears(1);
            return cand;
        } catch (Exception e) {
            return null;
        }
    }

    public static long daysToNextBirthday(Contact c, LocalDate today) {
        LocalDate n = nextBirthday(c, today);
        return n == null ? -1 : ChronoUnit.DAYS.between(today, n);
    }

    /** Age they turn on the next birthday, or -1 when the
     *  birth year is unknown or implausible. */
    public static int turningAge(Contact c, LocalDate today) {
        LocalDate n = nextBirthday(c, today);
        if (n == null) return -1;
        try {
            int by = LocalDate.parse(c.birthday.trim()).getYear();
            int age = n.getYear() - by;
            return (by > 1900 && age > 0 && age < 130) ? age : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    public static String birthdayPretty(Contact c) {
        if (c.birthday == null || c.birthday.trim().isEmpty()) return "";
        try {
            LocalDate b = LocalDate.parse(c.birthday.trim());
            String s = b.format(DateTimeFormatter.ofPattern("MMMM d", Locale.US));
            if (b.getYear() > 1900) s += ", " + b.getYear();
            return s;
        } catch (Exception e) {
            return c.birthday;
        }
    }

    /** "today", "tomorrow", or "in N days" for a day count. */
    public static String whenText(long days) {
        if (days == 0) return "today";
        if (days == 1) return "tomorrow";
        return "in " + days + " days";
    }

    // ---- Matching --------------------------------------------------

    private static String digits(String s) {
        return s == null ? "" : s.replaceAll("[^0-9]", "");
    }

    /** True when a memory's text mentions this contact - by
     *  name (full, first or last token), by phone number, or
     *  by email. hayLower must already be lowercase. */
    public static boolean matchesText(Contact c, String hayLower, String hayDigits) {
        String name = c.name.trim().toLowerCase(Locale.US);
        if (name.length() >= 3 && hayLower.contains(name)) return true;
        String[] parts = name.split("\\s+");
        for (String p : parts) {
            if (p.length() >= 3 && hayLower.contains(p)) return true;
        }
        String pd = digits(c.phone);
        if (pd.length() >= 7 && hayDigits.contains(pd.substring(pd.length() - 7))) return true;
        String em = c.email.trim().toLowerCase(Locale.US);
        if (em.length() >= 5 && hayLower.contains(em)) return true;
        return false;
    }

    /** Contacts a question is about: a name token of theirs
     *  (3+ letters) appears in the query. */
    public static List<Contact> matchQuery(List<Contact> all, String q) {
        List<Contact> out = new ArrayList<Contact>();
        String ql = " " + (q == null ? "" : q.toLowerCase(Locale.US)) + " ";
        for (Contact c : all) {
            String[] parts = c.name.trim().toLowerCase(Locale.US).split("\\s+");
            for (String p : parts) {
                if (p.length() >= 3 && ql.contains(p)) { out.add(c); break; }
            }
        }
        return out;
    }

    /** A plain-words card of everything known - the shape the
     *  brain tool and the Ask contact card both speak. */
    public static String describe(Contact c, LocalDate today) {
        StringBuilder sb = new StringBuilder();
        sb.append(c.name);
        if (!c.rel.trim().isEmpty()) sb.append(" (").append(c.rel.trim()).append(")");
        if (!c.org.trim().isEmpty()) sb.append(" - ").append(c.org.trim());
        sb.append(".");
        if (!c.phone.trim().isEmpty()) sb.append(" Phone: ").append(c.phone.trim()).append(".");
        if (!c.email.trim().isEmpty()) sb.append(" Email: ").append(c.email.trim()).append(".");
        if (!c.address.trim().isEmpty()) sb.append(" Address: ").append(c.address.trim()).append(".");
        LocalDate nb = nextBirthday(c, today);
        if (nb != null) {
            sb.append(" Birthday: ").append(birthdayPretty(c))
              .append(" - next ").append(whenText(daysToNextBirthday(c, today)));
            int age = turningAge(c, today);
            if (age > 0) sb.append(", turns ").append(age);
            sb.append(".");
        }
        if (!c.note.trim().isEmpty()) sb.append(" Notes: ").append(c.note.trim());
        return sb.toString();
    }
}
