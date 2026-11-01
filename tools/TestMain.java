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
        System.out.println("RESULT: " + pass + " pass, " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
