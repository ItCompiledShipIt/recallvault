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
            // v0.52.0: the summary carries its ledger lines, due-first.
            check("ledger: lines match the counted items",
                sMonth != null && sMonth.lines.size() == 2
                    && sMonth.lines.get(0).cents + sMonth.lines.get(1).cents == 12412);
            check("ledger: earliest due first",
                sMonth != null && sMonth.lines.size() == 2
                    && sMonth.lines.get(0).item.reminderAt == Extract.parseIso("2026-11-05"));
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

            // v0.52.0 hardening: an amount with reminderAt 0 (no due
            // date) never counts toward what is owed.
            Item receipt0 = new Item();
            receipt0.title = "Corner store receipt"; receipt0.category = "Receipts";
            receipt0.amounts = "$9.99"; receipt0.reminderAt = 0;
            Ask.Summary sZero = Ask.summarize(
                java.util.Arrays.asList(billA, billB, receipt0),
                "how much do I owe this month", null, nowA);
            check("aggregate: undated amount excluded from owed",
                sZero != null && sZero.count == 2 && sZero.totalCents == 12412);

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

        // ---------- v0.38.0: meeting recaps ----------
        {
            String mtg = "We decided to renew the lease for another year. "
                + "Everyone agreed the rent stays the same. "
                + "Gina will send the paperwork by Nov 28, 2026. "
                + "I need to call the insurance company about the rider. "
                + "The inspection is on Dec 3, 2026 and the deposit is $500.00.";
            java.util.List<String> rl = Recap.lines(mtg);
            String joined = String.join("\n", rl);
            check("recap: decisions found", joined.contains("Decided:")
                && joined.contains("renew the lease"));
            check("recap: actions carried with dates", joined.contains("To do:")
                && joined.contains("send the paperwork") && joined.contains("Nov 28, 2026"));
            check("recap: key dates + money lines", joined.contains("Key dates:")
                && joined.contains("Dec 3, 2026") && joined.contains("$500.00"));
            check("recap: chit-chat gives no recap",
                Recap.recapText("We talked about the weather and lunch plans for a while today.") == null);
            int todoAt = joined.indexOf("To do:");
            check("recap: a decision is not repeated as a to-do",
                todoAt < 0 || !joined.substring(todoAt).contains("renew the lease"));
            // v0.52.0: structured parts agree with the text lines.
            Recap.Parts rp = Recap.parts(mtg);
            check("recap parts: decisions + todos structured",
                !rp.decisions.isEmpty() && rp.todos.size() >= 2
                    && rp.datesLine != null && rp.moneyLine != null);
            boolean partsDedupe = true;
            for (Actions.Action act52 : rp.todos) {
                for (String d : rp.decisions) {
                    if (act52.text.equalsIgnoreCase(d)) partsDedupe = false;
                }
            }
            check("recap parts: todos never repeat decisions", partsDedupe);
            check("recap parts: empty text is empty",
                Recap.parts("").isEmpty() && Recap.parts(null).isEmpty());
        }

        // ---------- v0.39.0: bills forecast ----------
        {
            long fnow = 1793560000000L; // Nov 1, 2026
            long fday = 24L * 3600 * 1000;
            Item fe1 = new Item();
            fe1.title = "Electricity bill"; fe1.amounts = "$78.00";
            fe1.reminderAt = fnow - 61 * fday; fe1.createdAt = fe1.reminderAt - fday;
            Item fe2 = new Item();
            fe2.title = "Electricity bill"; fe2.amounts = "$81.50";
            fe2.reminderAt = fnow - 30 * fday; fe2.createdAt = fe2.reminderAt - fday;
            Item fe3 = new Item();
            fe3.title = "Electricity bill"; fe3.amounts = "$92.00";
            fe3.reminderAt = fnow - 1 * fday; fe3.createdAt = fe3.reminderAt - fday;
            java.util.List<Item> fv = new java.util.ArrayList<Item>(java.util.Arrays.asList(fe1, fe2, fe3));
            java.util.List<Forecast.Entry> fc = Forecast.forecast(fv, fnow);
            check("forecast: projects the monthly bill forward",
                !fc.isEmpty() && fc.get(0).predicted && fc.get(0).cents == 8150);
            long projDue = fc.get(0).dueMs;
            check("forecast: next projection about a month out",
                projDue > fnow + 20 * fday && projDue < fnow + 40 * fday);
            // A real bill already booked for that slot replaces the projection.
            Item fe4 = new Item();
            fe4.title = "Electricity bill"; fe4.amounts = "$88.00";
            fe4.reminderAt = projDue; fe4.createdAt = fnow;
            fv.add(fe4);
            java.util.List<Forecast.Entry> fc2 = Forecast.forecast(fv, fnow);
            boolean slotReal = false, slotPredicted = false;
            for (Forecast.Entry e : fc2) {
                if (Math.abs(e.dueMs - projDue) <= 10 * fday) {
                    if (e.predicted) slotPredicted = true; else slotReal = true;
                }
            }
            check("forecast: real bill suppresses its projection", slotReal && !slotPredicted);
            Ask.Summary fa = Forecast.answer(fv, "what bills are coming", fnow);
            check("forecast: ask hook answers", fa != null && fa.text.contains("What's coming"));
            check("forecast: ask hook ignores other questions",
                Forecast.answer(fv, "how much do I owe", fnow) == null);
        }

        // ---------- v0.40.0: heads-up reminders ----------
        {
            long hnow = 1793560000000L;
            long hday = 24L * 3600 * 1000;
            Item h1 = new Item(); h1.id = 1; h1.reminderAt = hnow + 3 * hday; // inside lead 3, outside 48h
            Item h2 = new Item(); h2.id = 2; h2.reminderAt = hnow + 1 * hday; // due window covers it
            Item h3 = new Item(); h3.id = 3; h3.reminderAt = hnow + 9 * hday; // too far out
            Item h4 = new Item(); h4.id = 4; h4.reminderAt = hnow + 3 * hday; h4.handledAt = 1; // handled
            java.util.List<Item> hv = new java.util.ArrayList<Item>(
                java.util.Arrays.asList(h1, h2, h3, h4));
            java.util.List<Item> hd = HeadsUp.due(hv, hnow, 3, new java.util.HashSet<String>());
            check("headsup: only the in-window unhandled item qualifies",
                hd.size() == 1 && hd.get(0).id == 1);
            java.util.Set<String> hn = new java.util.HashSet<String>();
            hn.add(HeadsUp.key(h1));
            check("headsup: notified items stay quiet",
                HeadsUp.due(hv, hnow, 3, hn).isEmpty());
            check("headsup: lead 0 disables", HeadsUp.due(hv, hnow, 0, hn).isEmpty());
            check("headsup: lead 14 reaches the far item",
                HeadsUp.due(hv, hnow, 14, new java.util.HashSet<String>()).size() == 2);
            check("headsup: choice cycle wraps", HeadsUp.nextChoice(14) == 0
                && HeadsUp.nextChoice(3) == 5);
            check("headsup: labels", HeadsUp.label(0).equals("Off")
                && HeadsUp.label(1).equals("1 day before")
                && HeadsUp.label(3).equals("3 days before"));
        }

        // ---------- v0.41.0: snooze ----------
        {
            long due = 1793822400000L; // Nov 4, 2026 (noon PT)
            check("snooze: +1 day moves the date exactly a day",
                Snooze.snoozedUntil(due, 1) == due + Snooze.DAY_MS);
            check("snooze: +7 days moves a week",
                Snooze.snoozedUntil(due, 7) == due + 7 * Snooze.DAY_MS);
            check("snooze: unset date stays unset", Snooze.snoozedUntil(0, 1) == 0);
            Item si = new Item(); si.id = 9; si.reminderAt = due;
            String before = HeadsUp.key(si);
            si.reminderAt = Snooze.snoozedUntil(si.reminderAt, 1);
            check("snooze: new date earns fresh notification keys",
                !HeadsUp.key(si).equals(before));
            check("snooze: labels", Snooze.choiceLabel(1).equals("1 day")
                && Snooze.choiceLabel(7).equals("7 days"));
        }

        // ---------- v0.42.0: receipt mode ----------
        {
            String ocr = "WALMART SUPERCENTER\n123 Main St\n10/28/2026 14:32\n"
                + "MILK 3.98\nBREAD 2.50\nSUBTOTAL 77.98\nSALES TAX 6.42\n"
                + "TOTAL 84.40\nCASH 100.00\nCHANGE 15.60\n";
            Receipts.Parsed rp = Receipts.parse(ocr);
            check("receipt: parses", rp != null);
            check("receipt: store from first line", rp.store.startsWith("WALMART"));
            check("receipt: TOTAL line wins over cash tendered", rp.totalCents == 8440);
            check("receipt: tax pulled", rp.taxCents == 642);
            check("receipt: date found", rp.dateMs > 0);
            check("receipt: prose is not a receipt", Receipts.parse("Just a note about lunch plans.") == null);
            Item ri = new Item(); ri.type = "SCAN"; ri.reminderAt = 1793000000000L;
            check("receipt: refine files it as an expense",
                Receipts.refine(ri, ocr, false) && Receipts.CATEGORY.equals(ri.category)
                    && "WALMART SUPERCENTER receipt".equals(ri.title)
                    && "$84.40".equals(ri.amounts) && ri.reminderAt == -1);
            long rnow = 1793560000000L; // Nov 1, 2026
            Item r1 = new Item(); r1.title = "WALMART SUPERCENTER receipt";
            r1.category = "Receipts"; r1.amounts = "$84.40"; r1.createdAt = rnow - 4L * 86400000;
            Item r2 = new Item(); r2.title = "Rosauers receipt";
            r2.category = "Receipts"; r2.amounts = "$22.10"; r2.createdAt = rnow - 2L * 86400000;
            Item r3 = new Item(); r3.title = "WALMART SUPERCENTER receipt";
            r3.category = "Receipts"; r3.amounts = "$30.00"; r3.createdAt = rnow - 40L * 86400000; // September
            java.util.List<Item> rv = new java.util.ArrayList<Item>(java.util.Arrays.asList(r1, r2, r3));
            Ask.Summary ra = Receipts.answer(rv, "how much did I spend in October", rnow);
            check("receipt: october spending totals october only",
                ra != null && ra.text.contains("$106.50") && ra.text.contains("October 2026"));
            Ask.Summary rb = Receipts.answer(rv, "how much did I spend at walmart", rnow);
            check("receipt: store-scoped spending", rb != null && rb.text.contains("$114.40"));
            check("receipt: non-spending question ignored",
                Receipts.answer(rv, "when is my bill due", rnow) == null);
            // v0.52.0: spending answers carry a ledger, newest first.
            check("receipt ledger: october lines sum to the total",
                ra != null && ra.lines.size() == 2
                    && ra.lines.get(0).cents + ra.lines.get(1).cents == 10650
                    && ra.lines.get(0).item == r2);
            String csv = Receipts.csv(rv);
            check("receipt: csv has header + rows", csv.startsWith("date,store,total,tax")
                && csv.contains("WALMART SUPERCENTER") && csv.contains("84.40"));
        }

        // ---------- v0.68.0: selective classification ----------
        {
            // His real false positives (Nov 2, 2026): a game menu
            // screenshot filed as a receipt because it says "Store".
            String menuShot = "SpatialScreen\nMy Status,\nProfile\nQuests\nSettings\nStore\n"
                + "X Close\nSNEAK / SWAP HAND: CLOSE - CLICK: SELECT";
            check("selective: game menu is not a receipt",
                "Notes".equals(Extract.analyze(menuShot).category));
            check("selective: game menu files as an image",
                "Images".equals(Extract.forType(Extract.analyze(menuShot).category, "PHOTO")));
            check("selective: game menu does not parse as a receipt",
                Receipts.parse(menuShot) == null);
            // A screenshot of Clio's own home screen, which says
            // "Receipts" and "Receipt or note" in its own UI.
            String homeShot = "Clio\nGood afternoon\nSaved on this phone only 100% Private\n"
                + "Record Voice\nQuick audio\nAll\nSearch your memories, voice notes, photos...\n"
                + "PHOTO LOOK-ALIKES\nTODAY\nRECENT MEMORIES\nReceipts\nweed\nVault\n"
                + "Scan Doc\nReceipt or note\nBills\n";
            check("selective: own home screen is not a receipt",
                "Notes".equals(Extract.analyze(homeShot).category));
            check("selective: own home screen does not parse",
                Receipts.parse(homeShot) == null);
            // A screenshot that merely displays a price is not a receipt.
            check("selective: lone price does not parse",
                Receipts.parse("Big sale this weekend\nOnly $20.00\nCome on down") == null);
            // His real dispensary receipt: labels and prices on
            // separate OCR lines, structure everywhere.
            String weed = "$16.00\nQueen - Dab'Ums - (7g)\nClarity\nNight -\n$4.00\n$20.00\n"
                + "GF41969006162188\nRegular Price\nSavings\n$20.00\n-$4.00\n$16.00\nSubtotal\n"
                + "Purchase Total\nDiscount\nTax Breakdowrn\n$4.07\n$0.91\n"
                + "WA State Excise Tax (included)\nCheney Sales Tax (included)\n"
                + "Payment Breakdown\n$16.00\nTx Amount\n$20.00\nCash\n$4.00\n$20.00\n$16.00\n"
                + "Total Tendered\nCash Back\nTotal\nCassidy R\nTerminal 6\n"
                + "Nov 02, 2026 09:26 AM PST\nCHRISTOPHER IRWIN\nReceipt ID\nTime\nCashier\n"
                + "Register\nPurchased by\nCloud 9 Cannabis Co.- Cheney\n1845 1st St.\nCheney, WA";
            Receipts.Parsed wp = Receipts.parse(weed);
            check("selective: real receipt still parses", wp != null);
            check("selective: real receipt total is the tendered total",
                wp != null && wp.totalCents == 2000);
            check("selective: real receipt classifies as Receipts",
                "Receipts".equals(Extract.analyze(weed).category));
            // A utility bill is a bill, never a receipt - and it
            // keeps its due date.
            String util = "City of Cheney\nUtility Statement\nAccount 4419\n"
                + "Amount Due $105.67\nDue Date November 16, 2026\nWater and electric service";
            check("selective: utility bill does not parse as a receipt",
                Receipts.parse(util) == null);
            Extract.Facts uf = Extract.analyze(util);
            check("selective: utility bill classifies as Bills", "Bills".equals(uf.category));
            check("selective: utility bill keeps its due date", uf.dueDate > 0);
            // Substring traps: words inside words do not count.
            check("selective: stored totals are prose, not a receipt",
                "Notes".equals(Extract.analyze(
                    "I stored the totals from the tournament in a spreadsheet").category));
            check("selective: a note about keeping a receipt stays a note",
                "Notes".equals(Extract.analyze("Keep the receipt for the TV in the drawer").category));
            // Phantom dates (v0.70.0): an unhinted past date is
            // history, not a deadline - the Clio home-screen
            // screenshot filed itself a reminder this way.
            check("phantom: past date alone earns no due date",
                Extract.analyze("Photo taken January 15, 2020 at the lake").dueDate == -1);
            check("phantom: future date alone still earns one",
                Extract.analyze("Concert tickets for June 15, 2031, section B").dueDate
                    == Extract.parseIso("2031-06-15"));
            check("phantom: hinted past date keeps its due date",
                Extract.analyze("This bill was due January 15, 2020").dueDate
                    == Extract.parseIso("2020-01-15"));
            // Notification judge (v0.70.0): facts file, noise does not.
            check("notify: bank spend alert kept",
                NotifyFilter.judge("Chase", "You spent $42.10 at SHELL OIL.").keep);
            check("notify: delivery event kept",
                NotifyFilter.judge("Amazon", "Your package was delivered.").keep);
            check("notify: appointment with date kept",
                NotifyFilter.judge("CHAS", "Appointment confirmed for December 5, 2026 at 9:00 AM.").keep);
            check("notify: amount due kept",
                NotifyFilter.judge("City Utilities", "Your bill is ready. Amount due $105.67.").keep);
            check("notify: bare code skipped",
                !NotifyFilter.judge("Google", "Your verification code is 481516.").keep);
            check("notify: social chatter skipped",
                !NotifyFilter.judge("Instagram", "john liked your post.").keep);
            check("notify: follower noise skipped",
                !NotifyFilter.judge("Threads", "You have 3 new followers this week.").keep);
            check("notify: empty skipped",
                !NotifyFilter.judge("", "").keep);
            check("notify: vague reminder skipped",
                !NotifyFilter.judge("Clock", "Reminder: drink some water.").keep);
            // A plain photo with no signal files as Images; a
            // plain typed note stays Notes.
            check("selective: silent photo files as Images",
                "Images".equals(Extract.forType(
                    Extract.analyze("A quiet afternoon at the lake").category, "PHOTO")));
            check("selective: typed note stays Notes",
                "Notes".equals(Extract.forType(
                    Extract.analyze("A quiet afternoon at the lake").category, "NOTE")));
        }

        // ---------- v0.70.0: superseded facts sink, answers cite freshness ----------
        {
            Item oldGate = new Item();
            oldGate.title = "Gate code for the building";
            oldGate.body = "The gate code is 1234.";
            oldGate.amounts = "";
            oldGate.createdAt = 1760000000000L;
            Item newGate = new Item();
            newGate.title = "Gate code for the building";
            newGate.body = "The gate code is 4419 now.";
            newGate.amounts = "$5";
            newGate.createdAt = 1760000000000L + 10L * 86400000;
            java.util.List<Ask.Result> gr = Ask.search(
                java.util.Arrays.asList(oldGate, newGate), "gate code building", null);
            check("superseded: both versions still found", gr.size() == 2);
            check("superseded: newer answers first",
                gr.size() == 2 && gr.get(0).item == newGate);
            check("superseded: older is marked",
                gr.size() == 2 && gr.get(1).superseded && !gr.get(0).superseded);
            check("provenance: names count and newest date",
                Ask.provenance(gr).startsWith("From 2 memories · newest "));
            check("provenance: empty is quiet", Ask.provenance(
                new java.util.ArrayList<Ask.Result>()).isEmpty());
        }

        // ---------- v0.71.0: FactMiner learns durable facts ----------
        {
            java.util.List<String> gate = FactMiner.mine("Gate code for the building",
                "The front gate code is 4419. The side door takes the same code.");
            check("miner: gate code learned",
                gate.contains("The front gate code is 4419."));
            java.util.List<String> wifi = FactMiner.mine("Wifi at home",
                "Network: IrwinHome, password: maple-street-42. Router is in the office closet.");
            check("miner: wifi password learned",
                wifi.contains("Password is maple-street-42."));
            check("miner: closet prose not a fact", wifi.size() == 1);
            java.util.List<String> landlord = FactMiner.mine("Landlord",
                "Landlord is Dana Ruiz, phone 509-555-0184. Rent is $1600, due the 1st of the month.");
            check("miner: named phone learned",
                landlord.contains("Dana Ruiz's phone is 509-555-0184."));
            java.util.List<String> mom = FactMiner.mine("Mom's birthday",
                "Mom's birthday is March 14. She likes tulips.");
            check("miner: birthday learned", mom.contains("Mom's birthday is March 14."));
            java.util.List<String> plumber = FactMiner.mine("Plumber",
                "Mike's Plumbing, 509-555-0117. Fixed the kitchen sink in October.");
            check("miner: bare phone learned",
                plumber.contains("The phone number for Mike's Plumbing is 509-555-0117."));
            java.util.List<String> router = FactMiner.mine("Router admin",
                "Router admin page is 192.168.0.1. The admin password is on a sticker under the desk.");
            check("miner: sticker prose yields no fake password",
                !router.toString().contains("is on."));
            check("miner: groceries teach nothing",
                FactMiner.mine("Grocery list", "Eggs, milk, coffee, tortillas.").isEmpty());
        }

        // ---------- v0.43.0: people cards ----------
        {
            Item g1 = new Item();
            g1.title = "Note about Gina";
            g1.body = "Gina's cell is 509-555-0142 and she is at 4211 W 1st St now. "
                + "Email is gina.irwin@example.com. Insurance renews Mar 20, 2027.";
            g1.createdAt = 1793400000000L;
            Item g2 = new Item();
            g2.title = "Dinner with Gina";
            g2.body = "Gina suggested the place on Sprague Ave.";
            g2.createdAt = 1793480000000L;
            java.util.List<Item> gv = new java.util.ArrayList<Item>(java.util.Arrays.asList(g1, g2));
            String card = Entities.cardText(gv, "Gina", 1793560000000L);
            check("personcard: builds", card != null);
            check("personcard: phone found", card.contains("509-555-0142"));
            check("personcard: email found", card.contains("gina.irwin@example.com"));
            check("personcard: address found", card.contains("4211 W 1st St"));
            check("personcard: count line", card.contains("2 memories"));
            check("personcard: unknown name gives no card",
                Entities.cardText(gv, "Zebedee", 1793560000000L) == null);
            Ask.Summary ga = Entities.answer(gv, "what is Gina's phone number");
            check("personcard: contact question returns the card",
                ga != null && ga.text.contains("at a glance") && ga.text.contains("509-555-0142"));
        }

        // ---------- v0.44.0: tap-the-facts ----------
        {
            String words = "Call Gina at 509-555-0142 or email gina.irwin@example.com. "
                + "The office is at 4211 W 1st St. Details at https://example.com/info. "
                + "UPS package 1Z884W9A0391234567 is on its way. Again: 509-555-0142.";
            java.util.List<Facts.Fact> ff = Facts.find(words);
            int phones = 0, emails = 0, webs = 0, addrs = 0, tracks = 0;
            String telUri = null, geoUri = null;
            for (Facts.Fact f : ff) {
                if (f.kind == Facts.PHONE) { phones++; telUri = f.uri; }
                if (f.kind == Facts.EMAIL) emails++;
                if (f.kind == Facts.WEB) webs++;
                if (f.kind == Facts.ADDRESS) { addrs++; geoUri = f.uri; }
                if (f.kind == Facts.TRACKING) tracks++;
            }
            check("facts: one of each kind, phone deduped",
                phones == 1 && emails == 1 && webs == 1 && addrs == 1 && tracks == 1);
            check("facts: tel uri normalized", "tel:+15095550142".equals(telUri));
            check("facts: geo uri carries the address", geoUri != null && geoUri.contains("4211"));
            check("facts: plain prose has none",
                Facts.find("We talked about lunch and the weather for a while.").isEmpty());
        }

        // ---------- v0.45.0: vault checkup ----------
        {
            Item c1 = new Item(); c1.id = 1; c1.reminderAt = -1;
            c1.body = "The lease renews on Dec 3, 2026 and is due then.";
            Item c2 = new Item(); c2.id = 2; c2.reminderAt = 1794000000000L;
            c2.body = "The lease renews on Dec 3, 2026.";
            Item c3 = new Item(); c3.id = 3; c3.reminderAt = -1;
            c3.body = "Just a note about lunch.";
            Item c4 = new Item(); c4.id = 4; c4.type = "PHOTO"; c4.filePath = "/x/p.jpg";
            c4.extracted = ""; c4.body = "";
            Item c5 = new Item(); c5.id = 5; c5.body = "Some words";
            c5.embeddingLocal = "0.1,0.2";
            java.util.List<Item> cv = new java.util.ArrayList<Item>(
                java.util.Arrays.asList(c1, c2, c3, c4, c5));
            java.util.List<Checkup.MissedDate> md = Checkup.missedDates(cv);
            check("checkup: missed date found with suggestion",
                md.size() == 1 && md.get(0).item.id == 1 && md.get(0).suggestedMs > 0);
            check("checkup: unindexed counts text without a vector",
                Checkup.unindexed(cv).size() == 3); // c1, c2, c3 (c5 has one, c4 has no text)
            check("checkup: unread photo found", Checkup.unread(cv).size() == 1
                && Checkup.unread(cv).get(0).id == 4);
            java.util.Map<Long, Long> sizes = new java.util.HashMap<Long, Long>();
            sizes.put(4L, 9000000L); sizes.put(1L, 1000L); sizes.put(5L, 5000000L);
            java.util.List<Item> big = Checkup.biggest(cv, sizes);
            check("checkup: biggest first", big.size() == 3 && big.get(0).id == 4
                && big.get(1).id == 5);
        }

        // ---------- v0.46.0: private memories ----------
        {
            Item pub = new Item(); pub.id = 1; pub.title = "Grocery list";
            Item prv = new Item(); prv.id = 2; prv.title = "Therapy notes"; prv.isPrivate = 1;
            check("privacy: mask hides the title",
                "Private memory".equals(Privacy.displayTitle(prv)));
            check("privacy: public title untouched",
                "Grocery list".equals(Privacy.displayTitle(pub)));
            java.util.List<Item> vis = Privacy.visible(
                new java.util.ArrayList<Item>(java.util.Arrays.asList(pub, prv)));
            check("privacy: visible() drops private items",
                vis.size() == 1 && vis.get(0).id == 1);
            check("privacy: isPrivate flag", Privacy.isPrivate(prv) && !Privacy.isPrivate(pub));
        }

        // ---------- v0.48.0: day-grouped feed labels ----------
        {
            long fixedNow = 1794000000000L; // a fixed "now"
            check("daylabel: today",
                "Today".equals(DayGroup.label(fixedNow, fixedNow)));
            check("daylabel: yesterday",
                "Yesterday".equals(DayGroup.label(fixedNow - 24L * 3600 * 1000, fixedNow)));
            String older = DayGroup.label(fixedNow - 9L * 24 * 3600 * 1000, fixedNow);
            check("daylabel: older is a dated label",
                older != null && !older.equals("Today") && !older.equals("Yesterday")
                    && older.contains(","));
        }

        // ---------- v0.53.0: SKILL.md parsing + catalog ----------
        {
            String md = "---\nname: daily-briefing\ndescription: Build a short morning briefing.\n---\n\nDo the thing.\nSecond line.\n";
            Skills.Skill s = Skills.parse(md, true, "daily-briefing");
            check("skills: parses frontmatter",
                s != null && "daily-briefing".equals(s.name)
                    && "Build a short morning briefing.".equals(s.description));
            check("skills: body is instructions",
                s != null && s.instructions.startsWith("Do the thing."));
            check("skills: no frontmatter, no skill",
                Skills.parse("just some text", true, "x") == null);
            check("skills: nameless frontmatter rejected",
                Skills.parse("---\ndescription: hi\n---\nbody", true, "x") == null);
            java.util.List<Skills.Skill> two = new java.util.ArrayList<Skills.Skill>();
            two.add(s);
            check("skills: catalog lists name + description",
                Skills.catalogBlock(two).contains("daily-briefing")
                    && Skills.catalogBlock(two).contains("morning briefing"));
        }

        // ---------- v0.54.0: smart text filter ----------
        {
            check("sms: utility bill kept",
                SmsFilter.judge("Your City Utilities bill of $41.00 is due on Nov 3, 2026. Pay online to avoid late fees.").keep);
            check("sms: appointment reminder kept",
                SmsFilter.judge("CHAS Cheney: Reminder - you have an appointment on Tuesday, Nov 4, 2026 at 11:30 AM. Reply C to confirm.").keep);
            check("sms: shipped + tracking link kept",
                SmsFilter.judge("Good news - your order has shipped! Track it here: https://track.example.com/abc123").keep);
            check("sms: prescription pickup kept",
                SmsFilter.judge("Your prescription is ready for pickup at Walgreens Cheney.").keep);
            check("sms: bank payment alert kept",
                SmsFilter.judge("Alert: a payment of $1,600.00 to your card ending 4417 has posted.").keep);
            check("sms: verification code skipped",
                !SmsFilter.judge("Your verification code is 482910. It expires in 10 minutes. Do not share it.").keep);
            check("sms: casual chat skipped",
                !SmsFilter.judge("lol yeah that was wild, call me later").keep);
            check("sms: plan with time kept (chris criteria)",
                SmsFilter.judge("see you at 5, bring the thing").keep);
            check("sms: meeting moved kept",
                SmsFilter.judge("Meeting moved to Nov 6, 2026, bring the papers").keep);
            check("sms: saturday meetup kept",
                SmsFilter.judge("Lets meet at the lake Saturday at noon").keep);
            check("sms: tomorrow dinner kept",
                SmsFilter.judge("Dinner at Rosas tomorrow at 7pm, dont be late").keep);
            check("sms: gate code is a remember-thing",
                SmsFilter.judge("Dont forget the gate code is 4521 for Saturday").keep);
            check("sms: bare reaction skipped",
                !SmsFilter.judge("omw").keep);
            check("sms: promo blast skipped",
                !SmsFilter.judge("SALE! 50% off everything this weekend only!! Do not miss out - shop now!").keep);
            check("sms: long fact-free chat skipped",
                !SmsFilter.judge("Hey man the game last night was absolutely insane, we should totally do that again next weekend for sure").keep);
            check("sms: empty skipped",
                !SmsFilter.judge("   ").keep);
            check("sms: bare spend alert skipped (v2 rules)",
                !SmsFilter.judge("Alert: $5.00 spent at COFFEE SHOP. Reply STOP to end.").keep);
            check("sms: balance status ping skipped (v2 rules)",
                !SmsFilter.judge("Your account balance is $2,341.00 as of today.").keep);
            check("sms: dateless order-shipped chatter skipped (v2 rules)",
                !SmsFilter.judge("Great news, your order has shipped and is on its way!").keep);

            // v0.55.0: web search result parsing (pure).
            String ddg = "<div class=\"result\"><h2><a rel=\"nofollow\" class=\"result__a\" "
                + "href=\"//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Ft7-manual&amp;rut=abc\">Canon T7 &amp; Manual</a></h2>"
                + "<a class=\"result__snippet\" href=\"//duckduckgo.com/l/?uddg=x\">The <b>manual</b> for the camera &amp; lens.</a></div>"
                + "<div class=\"result\"><h2><a rel=\"nofollow\" class=\"result__a\" "
                + "href=\"//duckduckgo.com/l/?uddg=https%3A%2F%2Fshop.example%2Fbuy\">Buy it</a></h2>"
                + "<a class=\"result__snippet\" href=\"//duckduckgo.com/l/?uddg=y\">In stock now.</a></div>";
            java.util.List<WebSearch.Hit> hits = WebSearch.parseResults(ddg, 5);
            check("web: two hits parsed", hits.size() == 2);
            check("web: uddg unwrapped + entity decoded",
                hits.size() == 2 && hits.get(0).url.equals("https://example.com/t7-manual")
                    && hits.get(0).title.equals("Canon T7 & Manual"));
            check("web: snippet tags stripped",
                hits.size() == 2 && hits.get(0).snippet.equals("The manual for the camera & lens."));
            check("web: empty page parses to none", WebSearch.parseResults("<html>blocked</html>", 5).isEmpty());

            // v0.56.0: mail filter.
            check("mail: utility bill kept",
                MailFilter.judge("Your City Utilities statement is ready",
                    "Your bill of $41.00 is due Nov 3, 2026. View it at https://city.example", false).keep);
            check("mail: appointment confirm kept",
                MailFilter.judge("Appointment confirmed",
                    "Your appointment is on Nov 9, 2026 at 2:30 pm at Cheney Health Center.", false).keep);
            check("mail: order shipped kept",
                MailFilter.judge("Your order has shipped",
                    "Order 8841 shipped. Track it: https://track.example/8841", false).keep);
            check("mail: newsletter skipped despite facts-free bulk",
                !MailFilter.judge("Weekly newsletter: 10 tips",
                    "This week we talk about gardens, plus a sale on seeds.", true).keep);
            check("mail: bulk bill still kept (strong fact wins)",
                MailFilter.judge("Statement ready",
                    "Your payment of $120.50 is due Dec 1, 2026.", true).keep);
            check("mail: promo blast skipped",
                !MailFilter.judge("50% OFF EVERYTHING",
                    "Biggest sale of the year, shop now at https://shop.example", false).keep);
            check("mail: personal note with a plan kept",
                MailFilter.judge("Saturday",
                    "Dont forget to bring the chairs on Saturday, we eat at 6.", false).keep);
            check("mail compose: valid passes",
                MailFilter.composeError("sam@example.com", "Hi", "Body text").isEmpty());
            check("mail compose: bad address blocked",
                !MailFilter.composeError("not-an-address", "Hi", "Body").isEmpty());
            check("mail compose: empty subject blocked",
                !MailFilter.composeError("a@b.com", "  ", "Body").isEmpty());
            check("mail compose: empty body blocked",
                !MailFilter.composeError("a@b.com", "Hi", "").isEmpty());
        }

        // ---------- v0.73.0: SwitchParse reads other apps' exports ----------
        {
            byte[] zip = null;
            try {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(bos);
                java.util.zip.ZipEntry e1 = new java.util.zip.ZipEntry("Takeout/Keep/Groceries.json");
                e1.setTime(1735689600000L);
                zos.putNextEntry(e1);
                zos.write(("{\"title\":\"Groceries\",\"textContent\":\"Eggs and coffee\","
                    + "\"createdTimestampUsec\":1735689600000000,"
                    + "\"labels\":[{\"name\":\"home\"}]}").getBytes("UTF-8"));
                zos.closeEntry();
                zos.putNextEntry(new java.util.zip.ZipEntry("Takeout/Keep/Tasks.json"));
                zos.write(("{\"title\":\"\",\"listContent\":[{\"text\":\"Call the dentist\",\"isChecked\":false},"
                    + "{\"text\":\"Buy filters\",\"isChecked\":true}],\"createdTimestampUsec\":1735776000000000}")
                    .getBytes("UTF-8"));
                zos.closeEntry();
                zos.putNextEntry(new java.util.zip.ZipEntry("Takeout/Keep/Old.json"));
                zos.write("{\"title\":\"Trash\",\"textContent\":\"gone\",\"isTrashed\":true,\"createdTimestampUsec\":1733000000000000}".getBytes("UTF-8"));
                zos.closeEntry();
                zos.putNextEntry(new java.util.zip.ZipEntry("Takeout/Keep/Photo.json"));
                zos.write(("{\"title\":\"Whiteboard\",\"textContent\":\"Sketch\","
                    + "\"createdTimestampUsec\":1739217600000000,"
                    + "\"attachments\":[{\"filePath\":\"wb.jpg\",\"mimetype\":\"image/jpeg\"}]}").getBytes("UTF-8"));
                zos.closeEntry();
                zos.putNextEntry(new java.util.zip.ZipEntry("Takeout/Keep/wb.jpg"));
                zos.write(new byte[]{(byte) 0xFF, (byte) 0xD8, 1, 2});
                zos.closeEntry();
                zos.putNextEntry(new java.util.zip.ZipEntry("Takeout/Keep/random.json"));
                zos.write("{\"foo\":1}".getBytes("UTF-8"));
                zos.closeEntry();
                zos.putNextEntry(new java.util.zip.ZipEntry(
                    "lifelogs/2026/10/05/2026-10-05_09h30m00s_Studio-session.md"));
                zos.write("Speaker 1: We ship Friday.".getBytes("UTF-8"));
                zos.closeEntry();
                zos.putNextEntry(new java.util.zip.ZipEntry("Ideas.md"));
                zos.write("# Ideas\n\nA porch garden.".getBytes("UTF-8"));
                zos.closeEntry();
                zos.close();
                zip = bos.toByteArray();
            } catch (Exception e) {
                check("switch: fixture builds", false);
            }
            SwitchParse.Out o = SwitchParse.parse("takeout.zip", zip);
            check("switch: parses 5 notes", o.notes.size() == 5);
            check("switch: trashed skipped + counted", o.skipped == 1);
            check("switch: all three sources named",
                o.sources.contains("Google Keep") && o.sources.contains("Limitless")
                    && o.sources.contains("Markdown"));
            SwitchParse.Note g = null, tasks = null, life = null, photo = null, ideas = null;
            for (SwitchParse.Note n : o.notes) {
                if ("Groceries".equals(n.title)) g = n;
                if (n.body.contains("Call the dentist")) tasks = n;
                if ("MEETING".equals(n.type)) life = n;
                if ("Whiteboard".equals(n.title)) photo = n;
                if ("Ideas".equals(n.title)) ideas = n;
            }
            check("switch: keep note keeps title, body, labels",
                g != null && g.body.contains("Eggs and coffee") && g.body.contains("Labels: home"));
            check("switch: keep created from usec",
                g != null && g.createdAt == 1735689600000L);
            check("switch: checklist renders ticks",
                tasks != null && tasks.body.contains("- [ ] Call the dentist")
                    && tasks.body.contains("- [x] Buy filters"));
            check("switch: attachment carried",
                photo != null && photo.attachBytes != null && photo.attachBytes.length == 4
                    && "image/jpeg".equals(photo.attachMime));
            check("switch: lifelog is a meeting with a readable title",
                life != null && "Studio session".equals(life.title)
                    && "Limitless".equals(life.source));
            check("switch: lifelog date parsed",
                life != null && new java.util.GregorianCalendar(2026, 9, 5).getTimeInMillis()
                    <= life.createdAt
                    && life.createdAt < new java.util.GregorianCalendar(2026, 9, 6).getTimeInMillis());
            check("switch: markdown heading becomes the title",
                ideas != null && ideas.body.contains("porch garden"));
            SwitchParse.Out loose = SwitchParse.parse("Note.md",
                "# Loose\n\nBody text.".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            check("switch: a loose markdown file parses",
                loose.notes.size() == 1 && "Loose".equals(loose.notes.get(0).title));
        }

        System.out.println("RESULT: " + pass + " pass, " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
