package com.irwinlabs.recallvault;
import java.util.*;
/** EvalMain - Ask ranking eval over a fixed corpus (dev tool). */
public class EvalMain {
  public static void main(String[] a) {
    List<Item> items = new ArrayList<Item>();
    { Item it = new Item(); it.id = 1; it.title = "Gate code for the building"; it.body = "The front gate code is 4419. The side door takes the same code."; it.extracted = ""; it.amounts = ""; it.category = "Notes"; it.createdAt = 1762000000000L + 0L * 86400000L; items.add(it); }
    { Item it = new Item(); it.id = 2; it.title = "Wifi at home"; it.body = "Network: IrwinHome, password: maple-street-42. Router is in the office closet."; it.extracted = ""; it.amounts = ""; it.category = "Notes"; it.createdAt = 1762000000000L + 1L * 86400000L; items.add(it); }
    { Item it = new Item(); it.id = 3; it.title = "City Utilities bill"; it.body = "City Utilities statement. Amount due $105.67, due November 16, 2026. Account 88-4410."; it.extracted = ""; it.amounts = ""; it.category = "Notes"; it.createdAt = 1762000000000L + 2L * 86400000L; items.add(it); }
    { Item it = new Item(); it.id = 4; it.title = "Water bill"; it.body = "Water and sewer bill, $41.20, due December 2, 2026."; it.extracted = ""; it.amounts = ""; it.category = "Notes"; it.createdAt = 1762000000000L + 3L * 86400000L; items.add(it); }
    { Item it = new Item(); it.id = 5; it.title = "Dentist appointment"; it.body = "Cleaning at Cheney Dental on December 5, 2026 at 9:00 AM. Bring the insurance card."; it.extracted = ""; it.amounts = ""; it.category = "Notes"; it.createdAt = 1762000000000L + 4L * 86400000L; items.add(it); }
    { Item it = new Item(); it.id = 6; it.title = "Gina's pharmacy"; it.body = "Gina picks up prescriptions at Walgreens on 1st Street in Cheney."; it.extracted = ""; it.amounts = ""; it.category = "Notes"; it.createdAt = 1762000000000L + 5L * 86400000L; items.add(it); }
    { Item it = new Item(); it.id = 7; it.title = "Truck oil change"; it.body = "Oil changed at 82,400 miles, 5W-30 full synthetic. Next due at 87,400 miles."; it.extracted = ""; it.amounts = ""; it.category = "Notes"; it.createdAt = 1762000000000L + 6L * 86400000L; items.add(it); }
    { Item it = new Item(); it.id = 8; it.title = "Mom's birthday"; it.body = "Mom's birthday is March 14. She likes tulips."; it.extracted = ""; it.amounts = ""; it.category = "Notes"; it.createdAt = 1762000000000L + 7L * 86400000L; items.add(it); }
    { Item it = new Item(); it.id = 9; it.title = "Landlord"; it.body = "Landlord is Dana Ruiz, phone 509-555-0184. Rent is $1600, due the 1st of the month."; it.extracted = ""; it.amounts = ""; it.category = "Notes"; it.createdAt = 1762000000000L + 8L * 86400000L; items.add(it); }
    { Item it = new Item(); it.id = 10; it.title = "Sparky vet"; it.body = "Sparky's rabies shot is good through June 2027. Vet: Cheney Vet Clinic on 2nd Street."; it.extracted = ""; it.amounts = ""; it.category = "Notes"; it.createdAt = 1762000000000L + 9L * 86400000L; items.add(it); }
    { Item it = new Item(); it.id = 11; it.title = "Router admin"; it.body = "Router admin page is 192.168.0.1. The admin password is on a sticker under the desk."; it.extracted = ""; it.amounts = ""; it.category = "Notes"; it.createdAt = 1762000000000L + 10L * 86400000L; items.add(it); }
    { Item it = new Item(); it.id = 12; it.title = "Car insurance"; it.body = "Policy with State Farm renews January 30, 2027. $612 for six months."; it.extracted = ""; it.amounts = ""; it.category = "Notes"; it.createdAt = 1762000000000L + 11L * 86400000L; items.add(it); }
    { Item it = new Item(); it.id = 13; it.title = "Cloud 9 Cannabis receipt"; it.body = "Receipt total $20.00, paid in cash on November 2, 2026."; it.extracted = ""; it.amounts = ""; it.category = "Notes"; it.createdAt = 1762000000000L + 12L * 86400000L; items.add(it); }
    { Item it = new Item(); it.id = 14; it.title = "Grocery list"; it.body = "Eggs, milk, coffee, tortillas, cheese, apples."; it.extracted = ""; it.amounts = ""; it.category = "Notes"; it.createdAt = 1762000000000L + 13L * 86400000L; items.add(it); }
    { Item it = new Item(); it.id = 15; it.title = "HOA meeting notes"; it.body = "Discussed fence repairs and parking lot resurfacing. Next meeting in January."; it.extracted = ""; it.amounts = ""; it.category = "Notes"; it.createdAt = 1762000000000L + 14L * 86400000L; items.add(it); }
    { Item it = new Item(); it.id = 16; it.title = "Library card"; it.body = "Cheney library card number 2764 0019 8841. PIN is the last four digits of my phone number."; it.extracted = ""; it.amounts = ""; it.category = "Notes"; it.createdAt = 1762000000000L + 15L * 86400000L; items.add(it); }
    { Item it = new Item(); it.id = 17; it.title = "Passport"; it.body = "Passport expires March 15, 2028. Kept in the fire safe."; it.extracted = ""; it.amounts = ""; it.category = "Notes"; it.createdAt = 1762000000000L + 16L * 86400000L; items.add(it); }
    { Item it = new Item(); it.id = 18; it.title = "Trash day"; it.body = "Trash pickup is Tuesday morning. Bins go out Monday night."; it.extracted = ""; it.amounts = ""; it.category = "Notes"; it.createdAt = 1762000000000L + 17L * 86400000L; items.add(it); }
    { Item it = new Item(); it.id = 19; it.title = "Plumber"; it.body = "Mike's Plumbing, 509-555-0117. Fixed the kitchen sink in October."; it.extracted = ""; it.amounts = ""; it.category = "Notes"; it.createdAt = 1762000000000L + 18L * 86400000L; items.add(it); }
    { Item it = new Item(); it.id = 20; it.title = "Internet plan"; it.body = "Xfinity 300 Mbps for $55 a month. Modem in the office closet."; it.extracted = ""; it.amounts = ""; it.category = "Notes"; it.createdAt = 1762000000000L + 19L * 86400000L; items.add(it); }
    String[][] qs = {
      {"how do I get into the building", "0"},
      {"what's the wifi password", "1"},
      {"when is the water bill due", "3"},
      {"how much is the utility bill", "2"},
      {"where does Gina get her medicine", "5"},
      {"when does Sparky need his next shot", "9"},
      {"what's Dana's phone number", "8"},
      {"when do the bins go out", "17"},
      {"how fast is our internet", "19"},
      {"when does my passport expire", "16"},
      {"who fixed the sink", "18"},
      {"what oil does the truck take", "6"},
      {"how much is rent", "8"},
      {"when is mom's birthday", "7"},
    };
    int top1 = 0, top3 = 0;
    for (String[] qq : qs) {
      List<Ask.Result> r = Ask.search(items, qq[0], null);
      int want = Integer.parseInt(qq[1]);
      int rank = -1;
      for (int i = 0; i < r.size(); i++) if (r.get(i).item.id == want + 1) { rank = i; break; }
      if (rank == 0) top1++;
      if (rank >= 0 && rank < 3) top3++;
      System.out.println("Q: " + qq[0] + " -> want #" + (want + 1) + " rank " + (rank < 0 ? "MISS" : String.valueOf(rank + 1)) + (r.isEmpty() ? " (no results)" : " top=" + r.get(0).item.title));
    }
    System.out.println("TOP1 " + top1 + "/" + qs.length + "  TOP3 " + top3 + "/" + qs.length);
  }
}
