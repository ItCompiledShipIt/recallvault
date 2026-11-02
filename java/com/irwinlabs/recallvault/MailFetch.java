package com.irwinlabs.recallvault;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Date;
import java.util.Map;
import java.util.Properties;

import javax.mail.Address;
import javax.mail.BodyPart;
import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.Multipart;
import javax.mail.Part;
import javax.mail.Session;
import javax.mail.Store;
import javax.mail.UIDFolder;
import javax.mail.internet.InternetAddress;

/**
 * MailFetch - Gmail over IMAP (v0.56.0). The address lives in
 * a plain pref; the app password is stored VaultCrypto-
 * encrypted and only ever decrypted in memory to log in.
 * Filing mirrors the text pipeline: a UID watermark (new
 * mail only, stamped lazily on first run), a seen-index so
 * each message is judged once, MailFilter decides, keepers
 * land as NOTE items with Extract facts attached.
 *
 * Network code - called only from scan threads, never the UI.
 */
public final class MailFetch {

    private MailFetch() {}

    static final String P_ADDR = "mail_address";
    static final String P_PASS = "mail_pass_enc";
    static final String P_WATERMARK = "ingest_mail_watermark"; // IMAP UID

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(Embed.PREFS, Context.MODE_PRIVATE);
    }

    public static boolean configured(Context ctx) {
        return !prefs(ctx).getString(P_ADDR, "").isEmpty()
            && !prefs(ctx).getString(P_PASS, "").isEmpty();
    }

    public static String address(Context ctx) {
        return prefs(ctx).getString(P_ADDR, "");
    }

    public static void saveCreds(Context ctx, String address, String appPassword) {
        prefs(ctx).edit()
            .putString(P_ADDR, address.trim())
            .putString(P_PASS, VaultCrypto.enc(appPassword.trim()))
            .apply();
    }

    public static void clearCreds(Context ctx) {
        prefs(ctx).edit().remove(P_ADDR).remove(P_PASS).apply();
    }

    private static Session session() {
        Properties p = new Properties();
        p.put("mail.store.protocol", "imaps");
        p.put("mail.imaps.host", "imap.gmail.com");
        p.put("mail.imaps.port", "993");
        p.put("mail.imaps.ssl.enable", "true");
        p.put("mail.imaps.connectiontimeout", "15000");
        p.put("mail.imaps.timeout", "20000");
        return Session.getInstance(p);
    }

    /** "" when the login works, else a plain-words error. */
    public static String testLogin(Context ctx) {
        Store store = null;
        try {
            store = session().getStore("imaps");
            store.connect("imap.gmail.com",
                prefs(ctx).getString(P_ADDR, ""),
                VaultCrypto.dec(prefs(ctx).getString(P_PASS, "")));
            return "";
        } catch (Exception e) {
            String m = e.getMessage();
            if (e instanceof javax.mail.AuthenticationFailedException) {
                return "Gmail refused the sign-in. Check the address and that the app password is current (it needs 2-step verification on the Google account).";
            }
            return "Could not reach Gmail (" + (m == null ? e.getClass().getSimpleName() : m) + ").";
        } finally {
            try { if (store != null) store.close(); } catch (Exception ignored) {}
        }
    }

    /** File new mail. Mirrors FileIngest.scanSms's contract:
     *  seen settled for every judged message, watermark only
     *  advances over messages actually seen. */
    static int fetchNew(Context ctx, Db db, Map<String, long[]> seen, int[] budget) {
        if (!configured(ctx)) return 0;
        Store store = null;
        Folder inbox = null;
        int filed = 0;
        try {
            store = session().getStore("imaps");
            store.connect("imap.gmail.com",
                prefs(ctx).getString(P_ADDR, ""),
                VaultCrypto.dec(prefs(ctx).getString(P_PASS, "")));
            inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);
            UIDFolder uf = (UIDFolder) inbox;
            long watermark = prefs(ctx).getLong(P_WATERMARK, -1);
            if (watermark < 0) {
                // First run: stamp at the current top - new mail only.
                int count = inbox.getMessageCount();
                long top = count > 0 ? uf.getUID(inbox.getMessage(count)) : 0;
                prefs(ctx).edit().putLong(P_WATERMARK, top).apply();
                return 0;
            }
            Message[] msgs = uf.getMessagesByUID(watermark + 1, UIDFolder.LASTUID);
            long high = watermark;
            for (Message msg : msgs) {
                if (budget[0] <= 0) break;
                long uid = uf.getUID(msg);
                if (uid > high) high = uid;
                String key = "mail:" + uid;
                if (seen.containsKey(key)) continue;
                seen.put(key, new long[]{msg.getSentDate() != null ? msg.getSentDate().getTime() : 0, 0});
                String subject = "";
                try { subject = msg.getSubject() == null ? "" : msg.getSubject(); } catch (Exception ignored) {}
                String body = extractText(msg);
                boolean unsub = false;
                try {
                    String[] h = msg.getHeader("List-Unsubscribe");
                    unsub = h != null && h.length > 0;
                } catch (Exception ignored) {}
                if (!MailFilter.judge(subject, body, unsub).keep) continue;
                budget[0]--;
                String from = fromText(msg);
                Date sent = msg.getSentDate();
                String full = "From: " + from + "\n\n" + (body == null ? "" : body);
                if (full.length() > 6000) full = full.substring(0, 6000);
                Extract.Facts f = Extract.analyze(subject + "\n" + (body == null ? "" : body));
                Item it = new Item();
                it.type = "NOTE";
                it.title = "Email: " + (subject.isEmpty() ? "(no subject)" : subject);
                it.body = full;
                it.extracted = "";
                it.filePath = "";
                it.mime = "";
                it.fileHash = "";
                it.embedding = "";
                it.createdAt = sent != null ? sent.getTime() : System.currentTimeMillis();
                it.reminderAt = f.dueDate > 0 ? f.dueDate : -1;
                StringBuilder am = new StringBuilder();
                for (int i = 0; i < f.amounts.size(); i++) {
                    if (i > 0) am.append(',');
                    am.append(f.amounts.get(i));
                }
                it.amounts = am.toString();
                it.category = f.category;
                db.insert(it);
                filed++;
            }
            prefs(ctx).edit().putLong(P_WATERMARK, high).apply();
        } catch (Exception e) {
            // Offline, refused, or a folder hiccup: the next
            // scan retries from the same watermark.
            return filed;
        } finally {
            try { if (inbox != null) inbox.close(false); } catch (Exception ignored) {}
            try { if (store != null) store.close(); } catch (Exception ignored) {}
        }
        return filed;
    }

    private static String fromText(Message msg) {
        try {
            Address[] from = msg.getFrom();
            if (from != null && from.length > 0) {
                if (from[0] instanceof InternetAddress) {
                    InternetAddress ia = (InternetAddress) from[0];
                    String personal = ia.getPersonal();
                    return personal != null && !personal.isEmpty() ? personal : ia.getAddress();
                }
                return from[0].toString();
            }
        } catch (Exception ignored) {}
        return "unknown sender";
    }

    /** Plain text of a message: text/plain parts win; HTML is
     *  tag-stripped as a fallback. Capped upstream. */
    static String extractText(Part part) {
        try {
            if (part.isMimeType("text/plain")) {
                Object c = part.getContent();
                return c == null ? "" : c.toString();
            }
            if (part.isMimeType("multipart/*")) {
                Multipart mp = (Multipart) part.getContent();
                StringBuilder plain = new StringBuilder();
                String html = "";
                for (int i = 0; i < mp.getCount(); i++) {
                    BodyPart bp = mp.getBodyPart(i);
                    if (bp.isMimeType("text/plain")) {
                        plain.append(extractText(bp)).append("\n");
                    } else if (bp.isMimeType("text/html") && html.isEmpty()) {
                        html = extractText(bp);
                    } else if (bp.isMimeType("multipart/*")) {
                        plain.append(extractText(bp)).append("\n");
                    }
                }
                return plain.length() > 0 ? plain.toString() : html;
            }
            if (part.isMimeType("text/html")) {
                Object c = part.getContent();
                return c == null ? "" : stripHtml(c.toString());
            }
        } catch (Exception ignored) {}
        return "";
    }

    static String stripHtml(String html) {
        String t = html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ");
        t = t.replaceAll("<[^>]+>", " ");
        t = t.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
             .replace("&nbsp;", " ").replace("&quot;", "\"").replace("&#39;", "'");
        return t.replaceAll("\\s+", " ").trim();
    }
}
