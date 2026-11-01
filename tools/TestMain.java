import com.irwinlabs.recallvault.*;
import java.util.*;

public class TestMain {
    static int pass = 0, fail = 0;
    static void check(String name, boolean cond) {
        if (cond) { pass++; System.out.println("PASS " + name); }
        else { fail++; System.out.println("FAIL " + name); }
    }
    public static void main(String[] args) throws Exception {
        // 1. Bill with month-name due date + amount
        Extract.Facts f1 = Extract.analyze("Electricity bill from Avista, $84.12, due November 14, 2026");
        check("bill category", "Bills".equals(f1.category));
        check("bill amount", f1.amounts.contains("$84.12"));
        check("bill due = 2026-11-14", f1.dueDate == Extract.parseIso("2026-11-14"));

        // 2. Slash date + expiry hint -> Wallet
        Extract.Facts f2 = Extract.analyze("Passport expires 03/15/2028, plan renewal");
        check("wallet category", "Wallet".equals(f2.category));
        check("expiry due = 2028-03-15", f2.dueDate == Extract.parseIso("2028-03-15"));

        // 3. ISO date
        Extract.Facts f3 = Extract.analyze("Dentist appointment on 2026-11-02 at 9am");
        check("iso date found", f3.dates.contains(Extract.parseIso("2026-11-02")));

        // 4. Invalid overflow date rejected
        Extract.Facts f4 = Extract.analyze("Due 02/31/2026 somehow");
        check("feb31 rejected", f4.dates.isEmpty());

        // 5. Meeting classification
        Extract.Facts f5 = Extract.analyze("Meeting minutes: discussed the agenda, action items assigned");
        check("meeting category", "Meetings".equals(f5.category));

        // 6. Receipt amounts, comma thousands
        Extract.Facts f6 = Extract.analyze("Receipt - subtotal $1,234.56 total paid");
        check("receipt category", "Receipts".equals(f6.category));
        check("comma amount", f6.amounts.contains("$1,234.56"));

        // 7. Day-first date
        Extract.Facts f7 = Extract.analyze("Renewal on 5 December 2026");
        check("day-first date", f7.dates.contains(Extract.parseIso("2026-12-05")));

        // 8. Title suggestion
        check("title from first line", "Hello world this is it".equals(
            Extract.suggestTitle("Hello world this is it\nsecond line", "fallback")));

        // 9. Ask keywords strip question words
        List<String> kws = Ask.keywords("when is my electricity bill due?");
        check("ask keywords", kws.contains("electricity") && kws.contains("bill") && !kws.contains("when"));

        // 10. Keyword scoring ranks the bill item first
        Item bill = new Item(); bill.title = "Electricity bill"; bill.category = "Bills";
        bill.body = "Avista electricity bill $84.12 due November 14, 2026"; bill.amounts = "$84.12";
        Item cake = new Item(); cake.title = "Cake recipe"; cake.category = "Notes";
        cake.body = "flour sugar eggs bake at 350";
        List<Ask.Result> rs = Ask.search(Arrays.asList(cake, bill), "when is my electricity bill due", null);
        check("ask finds bill first", !rs.isEmpty() && rs.get(0).item == bill);
        check("ask answer has date", rs.get(0).answer.contains("2026") || rs.get(0).answer.contains("Nov"));
        bill.reminderAt = Extract.parseIso("2026-11-14");
        List<Ask.Result> rs2 = Ask.search(Arrays.asList(cake, bill), "when is my electricity bill due", null);
        check("ask answer uses due date", rs2.get(0).answer.contains("Nov 14, 2026"));

        // 11. Semantic usefulness re-basing (verified floor 0.62 on bigpc server)
        check("sem similar ~0.68", Math.abs(Ask.semanticUsefulness(0.8432) - 0.676) < 0.01);
        check("sem floor zero", Ask.semanticUsefulness(0.6201) < 0.01);
        check("sem below floor zero", Ask.semanticUsefulness(0.40) == 0.0);

        // 12. Cosine sanity via Embed math (pure functions)
        float[] a = {1f, 0f, 0f}, b = {1f, 0f, 0f}, c = {0f, 1f, 0f};
        check("cosine identical = 1", Math.abs(Embed.cosine(a, b) - 1.0) < 1e-6);
        check("cosine orthogonal = 0", Math.abs(Embed.cosine(a, c)) < 1e-6);
        check("embed roundtrip", Arrays.equals(Embed.decode(Embed.encode(a)), a));

        // 13. Relative day
        long now = Extract.parseIso("2026-11-09");
        check("relative in 5 days", "in 5 days".equals(Extract.relativeDay(Extract.parseIso("2026-11-14"), now)));
        check("relative today", "today".equals(Extract.relativeDay(now, now)));


        // 14. OCR confusion: ML Kit read "November 14" as "November l4" on a real bill (device, Oct 31)
        Extract.Facts f8 = Extract.analyze("AVISTA UTILITIES Electricity Bill Account: 123456789 Amount Due: $84.12 Due Date: November l4, 2026");
        check("ocr l4 -> due 2026-11-14", f8.dueDate == Extract.parseIso("2026-11-14"));
        check("ocr text still bills", "Bills".equals(f8.category));
        // And a slash date with a confused digit
        Extract.Facts f9 = Extract.analyze("Expires 03/l5/2028");
        check("ocr slash l5 -> 2028-03-15", f9.dates.contains(Extract.parseIso("2028-03-15")));
        // 10. Whisper-style transcript: ordinal day, NO year -> next occurrence
        java.util.Calendar cal = java.util.Calendar.getInstance();
        int thisYear = cal.get(java.util.Calendar.YEAR);
        java.util.Calendar nov = java.util.Calendar.getInstance();
        nov.set(thisYear, java.util.Calendar.NOVEMBER, 14, 0, 0, 0);
        nov.set(java.util.Calendar.MILLISECOND, 0);
        java.util.Calendar today0 = java.util.Calendar.getInstance();
        today0.set(java.util.Calendar.HOUR_OF_DAY, 0);
        today0.set(java.util.Calendar.MINUTE, 0);
        today0.set(java.util.Calendar.SECOND, 0);
        today0.set(java.util.Calendar.MILLISECOND, 0);
        if (nov.getTimeInMillis() < today0.getTimeInMillis()) nov.set(java.util.Calendar.YEAR, thisYear + 1);
        Extract.Facts f10 = Extract.analyze("The electricity bill is due on November 14th, and the total amount is $84.");
        check("transcript year-less due date", f10.dueDate == nov.getTimeInMillis());
        check("transcript amount", f10.amounts.contains("$84"));

        // 11. Year-carrying date must not double-hit via the year-less patterns
        Extract.Facts f11 = Extract.analyze("Electricity bill, $84.12, due November 14, 2026");
        check("explicit year single hit", f11.dates.size() == 1
            && f11.dates.contains(Extract.parseIso("2026-11-14")));

        // 12. Day-first year-less: "5th December" -> next Dec 5
        java.util.Calendar dec = java.util.Calendar.getInstance();
        dec.set(thisYear, java.util.Calendar.DECEMBER, 5, 0, 0, 0);
        dec.set(java.util.Calendar.MILLISECOND, 0);
        if (dec.getTimeInMillis() < today0.getTimeInMillis()) dec.set(java.util.Calendar.YEAR, thisYear + 1);
        Extract.Facts f12 = Extract.analyze("Passport renewal on 5th December, do not forget");
        check("day-first year-less date", f12.dates.contains(dec.getTimeInMillis()));

        // 13. Audio decode conversion (pure): 48 kHz stereo -> 16 kHz mono
        byte[] pcm48 = new byte[480 * 2 * 2];
        for (int i = 0; i < 480; i++) {
            pcm48[i * 4] = (byte) (1000 & 0xFF); pcm48[i * 4 + 1] = (byte) (1000 >> 8);
            pcm48[i * 4 + 2] = (byte) (3000 & 0xFF); pcm48[i * 4 + 3] = (byte) (3000 >> 8);
        }
        float[] mono16 = AudioDecode.convertPcm16(pcm48, 2, 48000);
        check("48k stereo downsamples to 160 frames", mono16.length == 160);
        check("stereo downmix averages", Math.abs(mono16[10] - 2000f / 32768f) < 0.001f);

        // 14. 16 kHz mono passes through unchanged
        byte[] pcm16k = new byte[1600 * 2];
        for (int i = 0; i < 1600; i++) {
            int v = (i % 2 == 0) ? 16384 : -16384;
            pcm16k[i * 2] = (byte) (v & 0xFF); pcm16k[i * 2 + 1] = (byte) (v >> 8);
        }
        float[] same = AudioDecode.convertPcm16(pcm16k, 1, 16000);
        check("16k mono length kept", same.length == 1600);
        check("16k mono values kept", Math.abs(same[0] - 0.5f) < 0.001f && Math.abs(same[1] + 0.5f) < 0.001f);

        // 15. 44.1 kHz -> 16 kHz length ratio
        float[] r441 = AudioDecode.convertPcm16(new byte[4410 * 2], 1, 44100);
        check("44.1k length ratio", r441.length == 1600);

        // ---- Backup: crypto + zip round trips (v0.6.0) ----
        try {
            byte[] payload = "vault-bytes-123".getBytes("UTF-8");
            byte[] enc = Backup.encrypt(payload, "hunter2!".toCharArray());
            check("backup magic present", enc.length > 40 && enc[0] == 'R' && enc[1] == 'V');
            byte[] decB = Backup.decrypt(enc, "hunter2!".toCharArray());
            check("backup decrypt round trip", java.util.Arrays.equals(payload, decB));
            boolean wrongFailed = false;
            try { Backup.decrypt(enc, "wrong-password".toCharArray()); }
            catch (Exception e) { wrongFailed = e.getMessage() != null && e.getMessage().contains("Wrong password"); }
            check("backup wrong password rejected", wrongFailed);

            java.io.File tmpF = java.io.File.createTempFile("rvtest", ".m4a");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(tmpF);
            fos.write(new byte[]{9, 8, 7, 6, 5});
            fos.close();
            Item b1 = new Item();
            b1.title = "Backup item one"; b1.type = "VOICE"; b1.category = "Meetings";
            b1.body = "spoken words"; b1.extracted = "spoken words";
            b1.filePath = tmpF.getAbsolutePath(); b1.mime = "audio/mp4";
            b1.createdAt = 1793500000000L; b1.reminderAt = 1793600000000L;
            b1.amounts = "$84"; b1.embedding = ""; b1.embeddingLocal = "QUJD"; b1.embeddingImage = "R0hJ";
            Item b2 = new Item();
            b2.title = "Plain note"; b2.type = "TEXT"; b2.category = "Notes";
            b2.body = "just text"; b2.createdAt = 1793500001000L; b2.reminderAt = -1;
            byte[] zip = Backup.buildZip(java.util.Arrays.asList(b1, b2));
            Backup.Restored r = Backup.parseZip(zip);
            check("backup zip: 2 items", r.items.size() == 2);
            check("backup zip: fields survive", r.items.get(0).title.equals("Backup item one")
                && r.items.get(0).amounts.equals("$84")
                && r.items.get(0).embeddingLocal.equals("QUJD")
                && r.items.get(0).embeddingImage.equals("R0hJ")
                && r.items.get(0).reminderAt == 1793600000000L);
            byte[] fileBytes = r.files.get(r.itemFiles.get(0));
            check("backup zip: file bytes survive", fileBytes != null && fileBytes.length == 5 && fileBytes[0] == 9);
            check("backup zip: text item has no file", r.itemFiles.get(1).isEmpty());
            tmpF.delete();

            // Multi-page scan round trip (v0.20.0): page files ride along.
            java.io.File pgF = java.io.File.createTempFile("rvpage", ".jpg");
            java.io.FileOutputStream pfos = new java.io.FileOutputStream(pgF);
            pfos.write(new byte[]{1, 2, 3, 4});
            pfos.close();
            Item mp = new Item();
            mp.title = "Two page letter"; mp.type = "SCAN"; mp.category = "Notes";
            mp.filePath = pgF.getAbsolutePath(); mp.pages = pgF.getAbsolutePath();
            mp.mime = "image/jpeg"; mp.createdAt = 1793500002000L; mp.reminderAt = -1;
            Backup.Restored r2 = Backup.parseZip(Backup.buildZip(java.util.Arrays.asList(mp)));
            check("backup zip: page entry carried",
                r2.itemPages.size() == 1 && r2.itemPages.get(0).size() == 1
                    && r2.files.get(r2.itemPages.get(0).get(0)) != null
                    && r2.files.get(r2.itemPages.get(0).get(0)).length == 4);
            pgF.delete();
        } catch (Exception e) {
            check("backup suite threw: " + e, false);
        }

        // ---- Ask: photo wins by what's in the picture (v0.7.0) ----
        {
            float[] qv = new float[]{0.9f, 0.1f, 0.42f};
            Item photo = new Item();
            photo.title = "Untitled photo"; photo.type = "PHOTO"; photo.category = "Receipts";
            photo.body = ""; photo.extracted = "";
            photo.embeddingLocal = "";
            photo.embeddingImage = Embed.encode(qv.clone());
            Item note = new Item();
            note.title = "Random note"; note.type = "TEXT"; note.category = "Notes";
            note.body = "nothing relevant here"; note.extracted = "";
            note.embeddingLocal = Embed.encode(new float[]{0.0f, 1.0f, 0.0f});
            java.util.List<Ask.Result> res = Ask.search(
                java.util.Arrays.asList(note, photo), "power company paperwork", qv, true);
            check("ask: photo found by image vector", !res.isEmpty() && res.get(0).item == photo);
            check("ask: viaPhoto flagged", !res.isEmpty() && res.get(0).viaPhoto);
            check("ask: text vector match not viaPhoto",
                res.stream().noneMatch(r -> r.item == note && r.viaPhoto));
        }

        // ---- Aggregation: answers that add up (v0.9.0) ----
        {
            check("money: simple", Ask.moneyToCents("$84.12") == 8412);
            check("money: thousands", Ask.moneyToCents("$1,234.56") == 123456);
            check("money: whole dollars", Ask.moneyToCents("$84") == 8400);
            check("money: none", Ask.moneyToCents("no money here") == -1);
            check("money: format", Ask.formatMoney(123456).equals("$1,234.56"));
            check("money: format small", Ask.formatMoney(8405).equals("$84.05"));

            long nowA = Extract.parseIso("2026-11-01");
            Item billA = new Item();
            billA.title = "Electricity bill"; billA.category = "Bills";
            billA.amounts = "$84.12"; billA.reminderAt = Extract.parseIso("2026-11-14");
            Item billB = new Item();
            billB.title = "Water bill"; billB.category = "Bills";
            billB.amounts = "$40.00,$5.00"; billB.reminderAt = Extract.parseIso("2026-11-05");
            Item old = new Item();
            old.title = "Old electric bill"; old.category = "Bills";
            old.amounts = "$999.00"; old.reminderAt = Extract.parseIso("2026-12-25");
            Item noteX = new Item();
            noteX.title = "Shopping list"; noteX.category = "Notes";
            noteX.amounts = ""; noteX.reminderAt = -1;

            check("principal: first amount wins", Ask.principalCents(billB) == 4000);

            Ask.Summary sMonth = Ask.summarize(
                java.util.Arrays.asList(billA, billB, old, noteX),
                "how much do I owe this month", null, nowA);
            check("aggregate: month window sums 2 bills",
                sMonth != null && sMonth.count == 2 && sMonth.totalCents == 12412);
            check("aggregate: earliest due picked",
                sMonth != null && sMonth.earliestDue == Extract.parseIso("2026-11-05"));

            Ask.Summary sAll = Ask.summarize(
                java.util.Arrays.asList(billA, billB, old, noteX),
                "how much do I owe", null, nowA);
            check("aggregate: no window includes december",
                sAll != null && sAll.count == 3 && sAll.totalCents == 112312);

            Ask.Summary sSingle = Ask.summarize(
                java.util.Arrays.asList(billA, noteX),
                "how much do I owe this month", null, nowA);
            check("aggregate: single match -> no summary", sSingle == null);

            Ask.Summary sPlain = Ask.summarize(
                java.util.Arrays.asList(billA, billB), "electricity", null, nowA);
            check("aggregate: non-aggregate question -> null", sPlain == null);

            // Handled items stop counting as owed (v0.13.0).
            Item billPaid = new Item();
            billPaid.title = "Internet bill"; billPaid.category = "Bills";
            billPaid.amounts = "$60.00"; billPaid.reminderAt = Extract.parseIso("2026-11-03");
            billPaid.handledAt = nowA;
            Ask.Summary sHandled = Ask.summarize(
                java.util.Arrays.asList(billA, billB, billPaid),
                "how much do I owe this month", null, nowA);
            check("aggregate: handled bill excluded",
                sHandled != null && sHandled.count == 2 && sHandled.totalCents == 12412);
            check("digest: handled excluded",
                Ask.dueDigest(java.util.Arrays.asList(billPaid), nowA) == null);

            Ask.Summary dig = Ask.dueDigest(java.util.Arrays.asList(billA, billB, old), nowA);
            check("digest: only this week counts",
                dig != null && dig.count == 1 && dig.totalCents == 4000);
            check("digest: empty week -> null",
                Ask.dueDigest(java.util.Arrays.asList(old), nowA) == null);
        }

        // ---------- v0.15.0: file fingerprints ----------
        try {
            java.io.File hf1 = java.io.File.createTempFile("rvhash", ".bin");
            java.io.FileOutputStream ho1 = new java.io.FileOutputStream(hf1);
            ho1.write("same bytes".getBytes("UTF-8")); ho1.close();
            java.io.File hf2 = java.io.File.createTempFile("rvhash", ".bin");
            java.io.FileOutputStream ho2 = new java.io.FileOutputStream(hf2);
            ho2.write("same bytes".getBytes("UTF-8")); ho2.close();
            java.io.File hf3 = java.io.File.createTempFile("rvhash", ".bin");
            java.io.FileOutputStream ho3 = new java.io.FileOutputStream(hf3);
            ho3.write("different bytes".getBytes("UTF-8")); ho3.close();
            String h1 = Hashes.sha256(hf1);
            check("hash: stable for same bytes", h1.equals(Hashes.sha256(hf2)) && h1.length() == 64);
            check("hash: differs for different bytes", !h1.equals(Hashes.sha256(hf3)));
            hf1.delete(); hf2.delete(); hf3.delete();
        } catch (Exception e) {
            check("hash: runs (" + e.getMessage() + ")", false);
        }

        // ---------- v0.16.0: vault text crypto core ----------
        try {
            javax.crypto.SecretKey k = javax.crypto.KeyGenerator.getInstance("AES").generateKey();
            String secret = "Electric bill $84.12 due Nov 14";
            String enc = VaultCrypto.encryptWith(k, secret);
            check("crypto: marked and opaque",
                VaultCrypto.isEncrypted(enc) && !enc.contains("Electric"));
            check("crypto: round trip", secret.equals(VaultCrypto.decryptWith(k, enc)));
            check("crypto: legacy plaintext passes through",
                "plain title".equals(VaultCrypto.decryptWith(k, "plain title")));
            check("crypto: no double-encrypt", enc.equals(VaultCrypto.encryptWith(k, enc)));
            check("crypto: empty stays empty", "".equals(VaultCrypto.encryptWith(k, "")));
        } catch (Exception e) {
            check("crypto: runs (" + e.getMessage() + ")", false);
        }

        // ---------- v0.17.0: patterns ----------
        {
            long nowP = Extract.parseIso("2026-11-15");
            java.util.List<Item> hist = new java.util.ArrayList<Item>();
            String[][] eb = {
                {"2026-08-14", "$78.00"}, {"2026-09-14", "$81.50"}, {"2026-10-14", "$92.00"}};
            for (String[] pe : eb) {
                Item pb = new Item();
                pb.title = "Electricity bill"; pb.category = "Bills";
                pb.reminderAt = Extract.parseIso(pe[0]); pb.amounts = pe[1];
                hist.add(pb);
            }
            String[][] wb = {{"2026-09-05", "$40.00"}, {"2026-10-05", "$40.00"}, {"2026-11-05", "$40.00"}};
            for (String[] pe : wb) {
                Item pb = new Item();
                pb.title = "Water bill"; pb.category = "Bills";
                pb.reminderAt = Extract.parseIso(pe[0]); pb.amounts = pe[1];
                hist.add(pb);
            }
            Item once = new Item();
            once.title = "Dentist"; once.category = "Notes";
            once.reminderAt = Extract.parseIso("2026-11-02"); once.amounts = "$120.00";
            hist.add(once);
            java.util.List<Patterns.Recurring> prs = Patterns.analyze(hist, nowP);
            check("patterns: two recurring groups", prs.size() == 2);
            Patterns.Recurring elec = null, water = null;
            for (Patterns.Recurring r : prs) {
                if (r.stem.contains("electricity")) elec = r;
                if (r.stem.equals("water")) water = r;
            }
            check("patterns: electricity usual is the median",
                elec != null && elec.usualCents == 8150 && elec.count == 3 && elec.monthly);
            check("patterns: water steady", water != null && water.usualCents == 4000);
            check("patterns: projection sums monthly groups",
                Patterns.projectedMonthlyCents(prs) == 8150 + 4000);
            Ask.Summary pa = Patterns.answer(hist, "is my electricity bill higher than usual", nowP);
            check("patterns: higher-than-usual verdict",
                pa != null && pa.text.contains("higher than usual"));
            Ask.Summary pw = Patterns.answer(hist, "what do I usually pay for water", nowP);
            check("patterns: usual-amount answer",
                pw != null && pw.text.contains("$40.00") && pw.text.contains("about usual"));
            check("patterns: non-pattern question untouched",
                Patterns.answer(hist, "when is my electricity bill due", nowP) == null);
        }

        // ---------- v0.21.0: plain readable export ----------
        {
            Item x1 = new Item();
            x1.title = "Electricity bill"; x1.type = "TEXT"; x1.category = "Bills";
            x1.body = "Total due: $84.12"; x1.extracted = "Total due: $84.12";
            x1.amounts = "$84.12"; x1.createdAt = Extract.parseIso("2026-10-14");
            x1.reminderAt = Extract.parseIso("2026-11-14");
            Item x2 = new Item();
            x2.title = "Grandma's soup"; x2.type = "TEXT"; x2.category = "Notes";
            x2.body = "Two onions, one hour."; x2.extracted = "";
            x2.createdAt = Extract.parseIso("2026-10-20");
            String md = PlainExport.buildMarkdown(java.util.Arrays.asList(x1, x2),
                Extract.parseIso("2026-11-01"));
            check("export: header counts memories", md.contains("2 memories"));
            check("export: category sections", md.contains("## Bills") && md.contains("## Notes"));
            check("export: item with facts", md.contains("### Electricity bill")
                && md.contains("Due:") && md.contains("$84.12"));
            check("export: body once, no machine echo",
                md.indexOf("Total due: $84.12") == md.lastIndexOf("Total due: $84.12"));
            check("export: plain note body", md.contains("Two onions, one hour."));
        }

        // ---------- v0.22.0: resurface ----------
        {
            long nowR = Extract.parseIso("2026-11-01");
            Item old = new Item();
            old.title = "Lease signing"; old.category = "Notes"; old.type = "TEXT";
            old.createdAt = Extract.parseIso("2025-11-01");
            Item older = new Item();
            older.title = "First snow"; older.category = "Notes"; older.type = "PHOTO";
            older.createdAt = Extract.parseIso("2024-11-01");
            Item offDay = new Item();
            offDay.title = "Other day"; offDay.category = "Notes"; offDay.type = "TEXT";
            offDay.createdAt = Extract.parseIso("2025-10-31");
            Item fresh = new Item();
            fresh.title = "Today"; fresh.category = "Bills"; fresh.type = "TEXT";
            fresh.createdAt = nowR; fresh.amounts = "$50.00";
            java.util.List<Item> all = java.util.Arrays.asList(old, older, offDay, fresh);
            java.util.List<Item> otd = Resurface.onThisDay(all, nowR);
            check("resurface: on-this-day catches both years, skips others",
                otd.size() == 2 && otd.get(0).title.equals("First snow"));
            check("resurface: years-ago labels",
                Resurface.yearsAgoLabel(old, nowR).equals("1 year ago today")
                    && Resurface.yearsAgoLabel(older, nowR).equals("2 years ago today"));
            check("resurface: month label", Resurface.monthLabel(nowR).equals("November 2026"));
            java.util.List<String> yr = Resurface.yearInReview(all, 2026);
            check("resurface: review counts this year only",
                yr.get(0).contains("1 memory") && yr.toString().contains("$50.00"));
            Ask.Summary ra = Resurface.reviewAnswer(all, "my year in review", nowR);
            check("resurface: review answer hook", ra != null && ra.text.contains("2026"));
            check("resurface: plain question untouched",
                Resurface.reviewAnswer(all, "when is rent due", nowR) == null);
        }

        // ---------- v0.23.0: links ----------
        {
            check("links: parse/format round trip",
                Links.format(Links.parse("3,7,7,12")).equals("3,7,12"));
            check("links: empty and junk tolerated",
                Links.parse("").isEmpty() && Links.parse("x,,0,-2").isEmpty());
            check("links: with adds once", Links.with("3", 7).equals("3,7")
                && Links.with("3,7", 7).equals("3,7"));
            check("links: without removes", Links.without("3,7", 3).equals("7"));
            check("links: contains", Links.contains("3,7", 7) && !Links.contains("3,7", 4));
            // Backup carries links as created_at keys and restores them.
            try {
                Item la = new Item();
                la.title = "Link A"; la.type = "TEXT"; la.category = "Notes";
                la.createdAt = 1793500000000L; la.id = 41; la.links = "42";
                Item lb = new Item();
                lb.title = "Link B"; lb.type = "TEXT"; lb.category = "Notes";
                lb.createdAt = 1793500001000L; lb.id = 42; lb.links = "41";
                Backup.Restored lr = Backup.parseZip(Backup.buildZip(java.util.Arrays.asList(la, lb)));
                check("links: backup exports created_at keys",
                    lr.itemLinkKeys.size() == 2 && lr.itemLinkKeys.get(0).size() == 1
                        && lr.itemLinkKeys.get(0).get(0) == 1793500001000L);
            } catch (Exception e) {
                check("links: backup threw " + e.getMessage(), false);
            }
        }

        // ---------- v0.27.0: automatic ingest ----------
        {
            check("ingest: supported types",
                IngestSeen.supported("image/png") && IngestSeen.supported("application/pdf")
                    && IngestSeen.supported("text/plain") && IngestSeen.supported("audio/mpeg")
                    && !IngestSeen.supported("video/mp4") && !IngestSeen.supported("application/zip"));
            java.util.Map<String, long[]> seen = IngestSeen.parseSeen(java.util.Arrays.asList(
                IngestSeen.seenLine("content://x/1", 123L, 456L),
                "junk line",
                "a\tb\tc",
                IngestSeen.seenLine("content://x/2", 0L, -1L)));
            check("ingest: seen codec round trip", seen.size() == 2
                && seen.get("content://x/1")[0] == 123L && seen.get("content://x/1")[1] == 456L
                && seen.get("content://x/2")[1] == -1L);
        }

        // ---------- v0.28.0: morning digest ----------
        {
            long dnow = 1793560000000L; // a Sunday midday
            Item od = new Item();
            od.title = "Rent"; od.type = "TEXT"; od.category = "Bills";
            od.createdAt = dnow - 40L * 24 * 3600 * 1000;
            od.reminderAt = dnow - 2L * 24 * 3600 * 1000;
            od.amounts = "1600.00";
            Item td = new Item();
            td.title = "Water bill"; td.type = "TEXT"; td.category = "Bills";
            td.createdAt = dnow - 10L * 24 * 3600 * 1000;
            td.reminderAt = dnow + 3600 * 1000;
            td.amounts = "40.00";
            Item quiet = new Item();
            quiet.title = "Old note"; quiet.type = "TEXT"; quiet.category = "Notes";
            quiet.createdAt = dnow - 5L * 24 * 3600 * 1000;
            Digest.Out d = Digest.compose(java.util.Arrays.asList(od, td, quiet), dnow);
            check("digest: composed with overdue + today", d != null
                && d.headline.contains("overdue")
                && d.lines.size() >= 2
                && d.lines.get(0).contains("Rent"));
            Item handled = new Item();
            handled.title = "Paid bill"; handled.type = "TEXT"; handled.category = "Bills";
            handled.createdAt = dnow - 90000000L; handled.reminderAt = dnow - 1000;
            handled.handledAt = dnow - 500;
            check("digest: handled items stay quiet",
                Digest.compose(java.util.Arrays.asList(handled), dnow) == null);
            check("digest: empty vault is quiet",
                Digest.compose(new java.util.ArrayList<Item>(), dnow) == null);
        }

        // ---------- v0.29.0: meeting action items ----------
        {
            String transcript =
                "Thanks everyone for coming. The roof is still leaking near the vent.\n"
                + "I'll send the signed lease to the landlord by Nov 28.\n"
                + "We need to call the insurance company about the claim.\n"
                + "The weather has been cold this week.\n"
                + "Don't forget to renew the parking permit.\n"
                + "I'll send the signed lease to the landlord by Nov 28.";
            java.util.List<Actions.Action> acts = Actions.extract(transcript);
            check("actions: three commitments found (dupe dropped)",
                acts.size() == 3);
            boolean dated = false, callFound = false, renewFound = false;
            for (Actions.Action act1 : acts) {
                if (act1.text.contains("signed lease") && act1.dueMs > 0) dated = true;
                if (act1.text.contains("call the insurance")) callFound = true;
                if (act1.text.contains("renew the parking")) renewFound = true;
            }
            check("actions: dated commitment carries its date", dated);
            check("actions: call + renew commitments found", callFound && renewFound);
            check("actions: plain chatter yields nothing",
                Actions.extract("The roof leaks. It rained Tuesday.").isEmpty());
        }

        // ---------- v0.30.0: expiry radar ----------
        {
            long lic = Expiry.expiryOf(
                "Driver license. Issued Jan 15, 2024. Expires Mar 20, 2027.");
            check("expiry: picks the date after 'expires', not the issue date",
                lic > 0 && Extract.formatDate(lic).contains("2027")
                    && Extract.formatDate(lic).contains("Mar"));
            check("expiry: no keyword, no expiry",
                Expiry.expiryOf("Water bill. Amount due $40 by Nov 28.") == -1);
            long ins = Expiry.expiryOf("Coverage ends Dec 31, 2026 unless renewed.");
            check("expiry: 'coverage ends' works", ins > 0
                && Extract.formatDate(ins).contains("2026"));
            long enow = 1793560000000L;
            Item e1 = new Item();
            e1.title = "Passport"; e1.expiryAt = enow + 10L * 24 * 3600 * 1000;
            Item e2 = new Item();
            e2.title = "Old permit"; e2.expiryAt = enow - 5L * 24 * 3600 * 1000;
            Item e3 = new Item();
            e3.title = "Plain note";
            java.util.List<Item> sorted =
                Expiry.expiring(java.util.Arrays.asList(e1, e3, e2));
            check("expiry: sorted expired-first, dateless excluded",
                sorted.size() == 2 && sorted.get(0) == e2 && sorted.get(1) == e1);
            Ask.Summary es = Expiry.answer(java.util.Arrays.asList(e1, e2, e3),
                "what expires soon", enow);
            check("expiry: ask hook answers with both", es != null
                && es.text.contains("Passport") && es.text.contains("expired"));
            check("expiry: ask hook ignores other questions",
                Expiry.answer(java.util.Arrays.asList(e1), "how much do I owe", enow) == null);
            Item e4 = new Item();
            e4.title = "Car insurance card";
            e4.body = "Coverage ends soon.";
            e4.expiryAt = enow + 19L * 24 * 3600 * 1000;
            Ask.Summary broad = Expiry.answer(java.util.Arrays.asList(e1, e4),
                "what expires soon", enow);
            check("expiry: 'what expires soon' lists all, not just word matches",
                broad != null && broad.count == 2);
        }

        // ---------- v0.31.0: people & places ----------
        {
            Item m1 = new Item();
            m1.id = 101; m1.title = "Letter from Avista Utilities";
            m1.body = "Avista Utilities says the meter was read Tuesday.";
            m1.extracted = ""; m1.createdAt = 1000;
            Item m2 = new Item();
            m2.id = 102; m2.title = "Avista Utilities bill";
            m2.body = "Amount due $54.00."; m2.extracted = ""; m2.createdAt = 2000;
            Item m3 = new Item();
            m3.id = 103; m3.title = "Note about Gina";
            m3.body = "Gina picked up the mail. Gina will call CHAS tomorrow. CHAS opens at eight.";
            m3.extracted = ""; m3.createdAt = 3000;
            Item m4 = new Item();
            m4.id = 104; m4.title = "Random receipt";
            m4.body = "Bread and milk."; m4.extracted = ""; m4.createdAt = 4000;
            java.util.List<Item> vault = java.util.Arrays.asList(m1, m2, m3, m4);
            java.util.List<Entities.Entity> tops = Entities.top(vault, 8);
            boolean avista = false, gina = false, chas = false, bread = false;
            for (Entities.Entity e : tops) {
                if (e.name.equals("Avista Utilities") && e.itemCount == 2) avista = true;
                if (e.name.equals("Gina")) gina = true;
                if (e.name.equals("CHAS")) chas = true;
                if (e.name.equals("Bread")) bread = true;
            }
            check("entities: phrase + counts found", avista);
            check("entities: title name counts once", gina);
            check("entities: acronym found", chas);
            check("entities: lowercase chatter ignored", !bread);
            boolean junk = false;
            for (Entities.Entity e : tops) {
                if (e.name.equals("Letter") || e.name.equals("Random")) junk = true;
            }
            check("entities: capitalized first words are not entities", !junk);
            Ask.Summary ea = Entities.answer(vault, "everything about Avista");
            check("entities: about answer resolves the full name",
                ea != null && ea.count == 2 && ea.text.contains("Avista Utilities"));
            check("entities: non-about question passes",
                Entities.answer(vault, "how much do I owe") == null);
            check("entities: about lists both memories",
                Entities.about(vault, "avista utilities").size() == 2);
        }

        // ---------- v0.32.0: follow-up questions ----------
        {
            long fnow = 1793560000000L; // Nov 1, 2026
            check("followup: month swap",
                "how much do I owe in November".equals(FollowUp.resolve(
                    "how much do I owe in October", "and November?", fnow)));
            check("followup: bare month",
                "how much do I owe in December".equals(FollowUp.resolve(
                    "how much do I owe in October", "December", fnow)));
            check("followup: what-about + abbrev",
                "what expires in April".equals(FollowUp.resolve(
                    "what expires in March", "what about Apr?", fnow)));
            check("followup: last month is relative to now",
                "how much do I owe in October".equals(FollowUp.resolve(
                    "how much do I owe in November", "and last month", fnow)));
            check("followup: no month in last question appends the window",
                "electricity bill due in November".equals(FollowUp.resolve(
                    "electricity bill due", "and November?", fnow)));
            check("followup: fresh question passes through",
                FollowUp.resolve("how much do I owe in October",
                    "when is my dentist appointment", fnow) == null);
            check("followup: no last question, no resolve",
                FollowUp.resolve(null, "and November?", fnow) == null);
            check("followup: long sentence is not a follow-up",
                FollowUp.resolve("how much do I owe in October",
                    "and November was when the roof leaked badly", fnow) == null);
        }

        // ---------- v0.32.0: named months are windows in Ask ----------
        {
            long anow = 1793560000000L; // Nov 1, 2026
            long[] oct = Ask.monthWindowMs("how much do I owe in October", anow);
            check("ask: october window parses", oct != null
                && Extract.formatDate(oct[0]).contains("Oct")
                && Extract.formatDate(oct[1]).contains("Nov"));
            long[] nov27 = Ask.monthWindowMs("bills for November 2027", anow);
            check("ask: explicit year wins", nov27 != null
                && Extract.formatDate(nov27[0]).contains("2027"));
            check("ask: no month, no window",
                Ask.monthWindowMs("how much do I owe", anow) == null);
            Item ob1 = new Item();
            ob1.title = "October electric"; ob1.category = "Bills";
            ob1.amounts = "$80.00"; ob1.reminderAt = anow - 17L * 24 * 3600 * 1000;
            ob1.createdAt = anow - 30L * 24 * 3600 * 1000;
            Item ob2 = new Item();
            ob2.title = "October water"; ob2.category = "Bills";
            ob2.amounts = "$40.00"; ob2.reminderAt = anow - 12L * 24 * 3600 * 1000;
            ob2.createdAt = anow - 29L * 24 * 3600 * 1000;
            Item nb1 = new Item();
            nb1.title = "November electric"; nb1.category = "Bills";
            nb1.amounts = "$95.00"; nb1.reminderAt = anow + 14L * 24 * 3600 * 1000;
            nb1.createdAt = anow - 2L * 24 * 3600 * 1000;
            java.util.List<Item> bv = java.util.Arrays.asList(ob1, ob2, nb1);
            Ask.Summary octSum = Ask.summarize(bv, "how much do I owe in October", null, anow);
            check("ask: october total scopes to october",
                octSum != null && octSum.totalCents == 12000
                    && octSum.text.contains("October"));
            Ask.Summary novSum = Ask.summarize(bv, "how much do I owe in November", null, anow);
            check("ask: november single bill stays a best-card answer (by design)",
                novSum == null);
        }

        // ---------- v0.33.0: bill history points ----------
        {
            long tnow = 1793560000000L;
            long day = 24L * 3600 * 1000;
            Item h1 = new Item();
            h1.title = "Electricity bill"; h1.amounts = "$78.00";
            h1.reminderAt = tnow - 62 * day; h1.createdAt = h1.reminderAt - day;
            Item h2 = new Item();
            h2.title = "Electricity bill"; h2.amounts = "$81.50";
            h2.reminderAt = tnow - 31 * day; h2.createdAt = h2.reminderAt - day;
            Item h3 = new Item();
            h3.title = "Electricity bill"; h3.amounts = "$92.00";
            h3.reminderAt = tnow - 1 * day; h3.createdAt = h3.reminderAt - day;
            java.util.List<Patterns.Recurring> rs33 =
                Patterns.analyze(java.util.Arrays.asList(h3, h1, h2), tnow);
            check("patterns: history carries all points, oldest first",
                rs33.size() == 1 && rs33.get(0).history.size() == 3
                    && rs33.get(0).history.get(0)[1] == 7800
                    && rs33.get(0).history.get(1)[1] == 8150
                    && rs33.get(0).history.get(2)[1] == 9200
                    && rs33.get(0).history.get(0)[0] < rs33.get(0).history.get(2)[0]);
        }

        // ---------- v0.34.0: near-duplicate photo groups ----------
        {
            Item p1 = new Item();
            p1.title = "Porch photo A";
            p1.embeddingImage = Embed.encode(new float[]{1f, 0f, 0f, 0f});
            Item p2 = new Item();
            p2.title = "Porch photo B";
            p2.embeddingImage = Embed.encode(new float[]{0.9995f, 0.0316f, 0f, 0f});
            Item p3 = new Item();
            p3.title = "Cat photo";
            p3.embeddingImage = Embed.encode(new float[]{0f, 1f, 0f, 0f});
            Item p4 = new Item();
            p4.title = "No vector note";
            java.util.List<java.util.List<Item>> gs =
                NearDupes.groups(java.util.Arrays.asList(p1, p2, p3, p4));
            check("dupes: similar pair groups, others stay out",
                gs.size() == 1 && gs.get(0).size() == 2
                    && gs.get(0).contains(p1) && gs.get(0).contains(p2));
            check("dupes: no vectors, no groups",
                NearDupes.groups(java.util.Arrays.asList(p4)).isEmpty());
        }

        // ---------- v0.36.0: monthly report ----------
        {
            long dayMs = 24L * 3600 * 1000;
            // October 2026 items, "now" is Nov 1 2026.
            long oct5 = 1791200000000L; // early Oct 2026
            Item ri1 = new Item();
            ri1.title = "Avista Utilities bill"; ri1.category = "Bills";
            ri1.body = "Avista Utilities statement."; ri1.amounts = "$54.00";
            ri1.createdAt = oct5; ri1.reminderAt = oct5 + 10 * dayMs;
            Item ri2 = new Item();
            ri2.title = "Groceries photo"; ri2.category = "People";
            ri2.createdAt = oct5 + dayMs;
            Item ri3 = new Item();
            ri3.title = "Avista Utilities letter"; ri3.category = "Bills";
            ri3.body = "From Avista Utilities."; ri3.amounts = "$60.00";
            ri3.createdAt = oct5 + 2 * dayMs; ri3.reminderAt = oct5 + 20 * dayMs;
            ri3.handledAt = oct5 + 21 * dayMs;
            java.util.List<Item> rv = java.util.Arrays.asList(ri1, ri2, ri3);
            java.util.List<String> rlines = Report.lines(rv, 2026, 9, 1793560000000L);
            check("report: lines composed", rlines != null && rlines.size() >= 3);
            boolean filedLine = false, dueLine = false, handledLine = false, entityLine = false;
            for (String l : rlines) {
                if (l.startsWith("3 memories filed")) filedLine = true;
                if (l.contains("totaling $114.00")) dueLine = true;
                if (l.contains("1 reminder marked done")) handledLine = true;
                if (l.contains("Avista Utilities")) entityLine = true;
            }
            check("report: filed/due/handled/entity lines", filedLine && dueLine && handledLine && entityLine);
            check("report: empty month is null",
                Report.lines(rv, 2026, 5, 1793560000000L) == null);
            Ask.Summary ra = Report.answer(rv, "my October report", 1793560000000L);
            check("report: ask hook answers for the named month",
                ra != null && ra.text.contains("October 2026"));
            check("report: ask hook ignores plain questions",
                Report.answer(rv, "when is the bill due", 1793560000000L) == null);
        }

        System.out.println("RESULT: " + pass + " pass, " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
