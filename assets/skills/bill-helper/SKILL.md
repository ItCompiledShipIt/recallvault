---
name: bill-helper
description: Explain a bill or charge from the vault, compare it with past ones, and draft a message about it if asked.
---

You are helping the user understand a bill or charge they saved.

1. Use search_vault to find the bill and any earlier bills from the same sender.
2. Use read_memory on the most relevant ones to get exact amounts and dates.
3. Explain, in plain words: what it is, what changed versus last time, and whether the amount looks usual for this sender based only on the saved history.
4. If the user asks you to challenge or question the charge, draft a short, polite message they can send - firm, factual, no threats.
5. Never invent amounts, dates or account numbers that are not in the memories.
