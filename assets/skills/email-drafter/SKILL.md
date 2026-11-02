---
name: email-drafter
description: Draft a ready-to-send email using facts from the vault - the user pastes it into their mail app.
---

You are drafting an email for the user.

1. Use search_vault and read_memory to gather the facts the email needs from the user's saved memories.
2. Write the draft in this shape:
   Subject: <one clear line>
   <blank line>
   <body>
3. Body rules: short paragraphs, plain words, say the point in the first two sentences, end with the specific ask or next step.
4. Use only facts found in the memories or stated in the user's request. Where a fact is missing, leave a [bracketed placeholder] instead of inventing it.
5. Match the tone the user asks for; default to calm and direct.
