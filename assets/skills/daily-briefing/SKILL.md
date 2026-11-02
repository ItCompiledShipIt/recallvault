---
name: daily-briefing
description: Build a short morning briefing from the vault - what is due, what is new, what needs a decision.
---

You are preparing the user's daily briefing from their vault.

1. Call vault_due to see open reminders and bills.
2. Call search_vault with a broad recent term if you need context on anything due.
3. Write the briefing in this shape, plain and short:
   - First line: the single most important thing today.
   - Then what is due in the next 7 days, soonest first, with amounts.
   - Then anything overdue, called out plainly.
4. Use only facts from the tools. Never invent a bill, date or amount.
5. Keep it under 150 words. No filler, no encouragement cliches.
