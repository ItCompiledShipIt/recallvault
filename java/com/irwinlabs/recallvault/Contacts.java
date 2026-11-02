package com.irwinlabs.recallvault;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * The contact library (v0.62.0): the people who matter,
 * kept on this phone as a small JSON file in the app's
 * private storage. Not the vault database - contacts
 * are reference cards, not memories, and they never
 * leave the device.
 */
public class Contacts {

    public static class Contact {
        public long id;
        public String name = "";
        public String phone = "";
        public String email = "";
        public String note = "";
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
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Contact c = new Contact();
                c.id = o.optLong("id", 0);
                c.name = o.optString("name", "");
                c.phone = o.optString("phone", "");
                c.email = o.optString("email", "");
                c.note = o.optString("note", "");
                out.add(c);
            }
        } catch (Exception e) {
            // A damaged file reads as an empty library rather
            // than crashing the page.
        }
        return out;
    }

    public static void save(Context ctx, List<Contact> list) {
        try {
            JSONArray arr = new JSONArray();
            for (Contact c : list) {
                JSONObject o = new JSONObject();
                o.put("id", c.id);
                o.put("name", c.name);
                o.put("phone", c.phone);
                o.put("email", c.email);
                o.put("note", c.note);
                arr.put(o);
            }
            Files.write(file(ctx).toPath(), arr.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            // Best effort; the editor reports what it shows.
        }
    }

    /** Insert or update by id; a new contact gets a fresh id. */
    public static void upsert(Context ctx, Contact c) {
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

    public static void delete(Context ctx, long id) {
        List<Contact> list = load(ctx);
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).id == id) list.remove(i);
        }
        save(ctx, list);
    }
}
