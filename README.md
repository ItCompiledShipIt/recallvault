# RecallVault

A local-first personal memory vault for Android — the MemX idea, on your own hardware.

Save anything in one tap — a note, a voice memo, a photo, a scan, a document,
a meeting. It files itself (no folders), pulls out the dates and amounts, and
when you need it back you just ask in plain words. The answer comes back with
the memory it came from.

**Version:** 0.5.0 · **Package:** `com.irwinlabs.recallvault` · **Min Android:** 8.0 (API 26)

## What it does

- **Capture** — Note, Voice (dictation to searchable text, or audio recording),
  Photo (picker), Scan (full-resolution camera via an in-app file provider),
  Document import (PDF / text / image), Meeting recorder. Android share-sheet
  "send text" also lands here as a note.
- **OCR (v0.2)** — photos and scans are read on-device with ML Kit's bundled
  Latin model the moment they land in the editor; the words in the image
  become searchable text, kept apart from your own note. Existing photos can
  be (re)read from the detail view. No image leaves the phone.
- **PDF text (v0.2)** — importing a PDF extracts its embedded text layer
  on-device (PdfBox-Android), so the words inside are searchable and feed
  filing. A scanned-image PDF has no text layer; the import says so plainly.
- **Meeting recorder (v0.2)** — records the room with a live timer, files
  under Meetings, and pulls dates/amounts out of a short dictated summary.
- **Transcription (v0.3; on-device from v0.4)** — recordings (voice
  notes and meetings) are transcribed by Whisper (whisper.cpp) running
  on the phone itself: button in the detail view, and meetings
  auto-transcribe in the background after saving. Optional 57 MB model
  download in Settings. The words land as machine text - searchable,
  date/amount-mined. The audio never leaves the phone.
- **Reminder notifications (v0.3)** — a daily WorkManager check (plus one
  at app start) posts a notification for anything due within 48 hours or
  overdue, once per deadline, tap to open the memory.
- **Scanned PDFs (v0.3)** — a PDF with no text layer is no longer a dead
  end: its pages are rendered and OCR'd on-device (up to 20 pages).
- **On-device semantic search (v0.3)** — EmbeddingGemma 2 itself, on the
  phone (MediaPipe Universal Embedder, LiteRT-LM). Optional 388 MB
  model download in Settings (the text+vision 440M file - the runtime
  refuses the text-only one). Ask order: on-device semantic -> keyword.
- **Auto-filing** — every save is analyzed on-device: category
  (Bills / Receipts / Wallet / People / Meetings / Notes), dates
  (with due/expires preference), and money amounts are extracted by
  `Extract.java` — pure Java, no libraries, unit-tested on the JVM (27 checks).
  Date detection tolerates classic OCR confusions (1/l/I, 0/O) in
  date-shaped contexts — ML Kit read a real test bill's "November 14" as
  "November l4" and the due date still landed, Oct 31, 2026.
- **Vault** — SQLite on the phone + app-private file storage. Filter by text
  or category chip. No account, no cloud, no sync.
- **Ask** — hybrid search, entirely on-device. Keyword scoring always
  works. With the model downloaded, the question is embedded by
  **EmbeddingGemma 2** on the phone and fused with keyword scores, so
  "when do I have to pay for power" finds the electricity bill even with
  no shared words. Answers are composed from the extracted facts (due
  date, amount) plus a snippet, and always name their source memory.
- **Reminders** — every deadline found in what you saved, sorted, with
  "in N days" / overdue marking.
- **Detail view** — full text, photo preview, voice playback, extracted
  facts, machine-read text (OCR / PDF), semantic-index status, category
  correction chips, edit, delete.

## Architecture (v0.4.0 - everything on the phone)

```
Phone (everything runs and stays here)
--------------------------------------
SQLite vault + app-private files
Extract: dates / amounts / categories (pure Java)
ML Kit OCR (bundled model)
PdfBox-Android text layer; scanned PDFs rendered -> ML Kit OCR
EmbeddingGemma 2 via MediaPipe (optional 388 MB model download)
Whisper base.en via whisper.cpp JNI (optional 57 MB model download;
  native lib built in native/, see native/build.sh)
Ask: keyword + on-device cosine fusion
WorkManager reminder notifications
```

There is NO server component from v0.4.0 on. The v0.1-v0.3 design
sent embeddings and transcription to services on Chris's bigpc over
Tailscale; he asked for everything on the phone, so both were
replaced with on-device engines and the bigpc services
(embeddinggemma-2, recallvault-transcribe) were retired (disabled,
not deleted, Nov 1, 2026). The old server-vector DB column remains
in the schema, unused, so existing vaults upgrade in place.

## Verified on the real device (v0.2.1, Oct 31, 2026)

Driven over ADB on the S24, end to end:

- Import of a generated electricity-bill image -> OCR read it ->
  filed as Bills, $84.12, due Sat, Nov 14, 2026 -> saved -> Ask
  "when is my electricity bill due" answered "due Sat, Nov 14, 2026
  (in 14 days)" with the source cited.
- Import of a text-layer PDF (property tax statement) -> text extracted
  on-device -> filed as Bills, $412.55, due Fri, Apr 30, 2027.
- Both test items were deleted afterwards; the vault ships empty.

## Honest limits (v0.4.0)

- **Meeting transcription is after-the-fact**, not live captions.
  Speed on the S24: a 7-second clip transcribed in ~11 s including
  first-run model load; long meetings take proportionally longer.
- **Whisper is the base English model** - strong on clear speech,
  weaker in noisy rooms. A larger model is a future Settings option.
- **Voice dictation** uses the device's SpeechRecognizer (which may
  itself use a network service depending on the phone's settings -
  that is Android's, not this app's, doing).
- Signed with the debug keystore, like the other sideloaded house apps.
- **APK is ~213 MB** — the LiteRT-LM runtime ships native libraries
  for every ABI, plus the 16 MB whisper JNI library. An arm64-only
  split is the known trim; not done yet.

## UI design (v0.5.0, Nov 1, 2026)

A professional design pass over the same feature set. One design
system (`Ui.java`): near-black surfaces, periwinkle accent, teal
secondary; every tappable surface ripples; text fields are filled
and rounded with an accent focus ring; capture is an icon-tile grid;
the bottom bar is icon tabs with a selected pill; list rows are
cards with per-type icon badges; screens carry a brand mark
(gradient tile + vault lock) that matches the new adaptive launcher
icon (white lock on the brand gradient). Facts on the detail screen
sit in their own card, section labels are small caps, empty states
have an icon, a title and a hint. Icons are Google's Material glyphs
(Apache License 2.0), pinned from @material-design-icons/svg 0.14.15.

Verified by a full screenshot tour on the S24 (Vault, Ask,
Reminders, Detail, Editor, Voice, Settings, app drawer). The tour
caught the launcher icon's first build: a vector-group pivot placed
the lock in a corner - the shipped foreground has the transform
baked into the path data instead, bbox-verified in the safe zone.

## Verification status (v0.4.0, Nov 1, 2026)

The all-on-device build was proven the hard way: with the phone in
**airplane mode** (radios off, verified via settings), on the real S24:

- **Offline transcription**: a staged meeting recording transcribed
  from the Detail button in ~11 s - "The electricity bill is due on
  November 14th and the total amount is $84." - with the date
  (Sat, Nov 14, 2026) and $84 mined from the words. Same sentence
  the bigpc server used to produce; whisper.cpp + the q5_1 model
  were additionally host-verified on bigpc before the phone run.
- **Offline semantic Ask**: "when do I have to pay for power" ranked
  the Avista electricity bill first under "Semantic + keyword search
  (EmbeddingGemma 2 on this phone)", again in airplane mode.
- **Settings** shows no server fields at all - just the two model
  downloads, both fetched through the real UI (369 MB search model
  from v0.3.1 intact; 59,721,011-byte whisper model exact).

Verification caught two real bugs before either shipped as "done":
(1) ggml's default OpenMP build made librecallwhisper.so depend on
libomp.so, which Android does not ship - dlopen failed; fixed with
GGML_OPENMP=OFF and verified via the .so's NEEDED list. The app's
guard caught it as a clean "unavailable" instead of a crash.
(2) The new library's ELF segments were 4 KB-aligned; Android 15's
compatibility check flagged it (LOAD segment not aligned) - fixed
with -Wl,-z,max-page-size=16384, verified 0x4000 alignment, and the
warning dialog no longer appears. JVM suite: 36/36 (incl. the new
audio decode/resample checks).

Chris's own voice note (saved the same morning) was in the vault
throughout verification and was left untouched; all test items were
deleted afterwards.

(The v0.3.1 verification history - four feature proofs, the
litertlm crash-loop find, the 440M model switch - is in the git
history of this file and the project journal.)

## v0.6.0 - backup & app lock (Nov 1, 2026)

- **Encrypted backup & restore** (Settings -> Backup & security):
  Export writes ONE `.rvbackup` file - magic + salt + IV +
  AES-256-GCM ciphertext (key = PBKDF2-HmacSHA256, 120k rounds,
  from a user-set password that is never stored). Plaintext is a ZIP
  of vault.json (every item, all three embedding spaces included)
  plus each item's file. Import (SAF pick + password) decrypts,
  restores files under `files/restored/`, and skips items already in
  the vault (same title + created-at), so re-importing is safe.
  Wrong password fails the GCM tag - that is the error message.
- **App lock** (same Settings card, OFF by default): fingerprint or
  phone PIN via the framework BiometricPrompt. Gates Main + Detail
  on cold start and after 2 minutes in the background; the unlock
  stamps live only in memory, so a killed process returns locked.
  Turning it on first checks `BiometricManager.canAuthenticate` -
  no fingerprint/secure PIN on the phone, no lock (plain message).
- JVM suite: 43/43 (crypto round trip, wrong-password rejection,
  zip + file-bytes round trip).
- Device-verified: lock gate cold-starts into LockActivity with the
  system prompt live ("Scan your fingerprint." / "Use PIN" offered);
  export + restore with password confirmed working by chris himself
  on the S24 (Nov 1) - automated tap-driving of the Samsung IME in
  the password dialog was the only path not machine-proven.

## v0.7.0 - photo search by meaning (Nov 1, 2026)

- The 440M model's vision tower is wired up: `UniversalEmbedder`
  in the pinned tasks-retrieval 1.2.0 exposes `embedImage(MPImage)`
  (verified by javap on the shipped AAR - no new dependencies).
  Photos/scans get an image vector (DB v3, `embedding_image`) in
  the SAME unified 768-dim space as text; the pass runs inside
  `embedPendingLocal`, decoding at <=1024px.
- Ask (local mode) scores an item by the better of its text vector
  and its photo vector; a photo win is flagged and the result card
  says "matched by what's in the photo". Backups carry the new
  vector too (old backups still parse - the key is optional).
- JVM suite: 46/46 (photo-with-no-words outranks an unrelated note
  on the image vector alone; viaPhoto flagging).
- Device-verified: staged dog photo (no words anywhere on the item)
  received a 4096-char image vector from the on-device pass. The
  live Ask ranking proof is queued behind chris's own app lock,
  which he enabled while testing v0.6.0 - ask "dog" after unlocking
  to see it land.

## v0.8.0 - live captions, Whisper sizes, slim APK (Nov 1, 2026)

- **Live meeting captions**: meetings now record PCM/WAV via
  AudioRecord (`PcmRecorder`, 16 kHz mono - Whisper's native diet;
  header re-patched per buffer so a killed app still leaves a
  playable file). A caption worker cuts every ~12s of new audio
  (max 30s segments) and runs it through the SAME persistent
  whisper context; captions land in the meeting's text the moment
  it is saved, and the existing full-file pass still replaces them
  with a cleaner transcript in the background. Voice notes keep
  M4A. Known wart: Stop/Save waits for the final <=12s segment to
  finish captioning (a few seconds on Balanced).
- **Whisper model choice** (Settings -> Transcription chips):
  Fast (tiny.en q5_1, ~31 MB) / Balanced (base.en q5_1, ~57 MB,
  the default - nothing changes until another size is picked) /
  Sharpest (small.en q5_1, ~173 MB). Switching keeps other
  downloads; Remove deletes the selected one.
- **Slim APK**: arm64-only ABI split - 213 MB -> **89 MB**
  installed build (the rest is the LiteRT/MediaPipe arm64 native
  stack the features genuinely use).
- Device-verified: captions streamed live on the S24 during a
  staged meeting (bigpc speakers -> phone mic): the electricity
  sentence appeared segment by segment; the saved meeting carried
  the caption text + a valid 1.9 MB WAV. Test data deleted after.
  The variant chips themselves were not screenshot-verified -
  chris's app lock (his setting, restored untouched after the
  caption test) gates Settings too.

## v0.9.0 - answers that add up (Nov 1, 2026)

- **Ask aggregates.** Set questions ("how much do I owe this month",
  "what's due this week") now lead with a summary card computed
  across EVERY match: count, total of each item's principal (first)
  amount, and the next due date - above the usual cited cards.
  `Ask.summarize`: the question's frame words (owe/due/bill/month...)
  are stripped to find topic words; no topic word means the frame
  itself is the filter. A window word bounds the dates; without one,
  overdue counts too. Fewer than 2 contributors keeps the old
  single-card answer. Money parsing handles thousands separators.
- **Reminders digest.** A "Due this week" card tops the Reminders
  tab when anything lands in the next 7 days: count, total amounts,
  next date (`Ask.dueDigest`).
- JVM 60/60 (14 new aggregation tests). Device-verified with two
  staged bills: "2 due this month, totaling $124.12. Next: Thu,
  Nov 5, 2026 (in 4 days)" and the matching Reminders digest.
  (Staging lesson: `$8` inside nested shell heredocs is a positional
  parameter - the first attempt stored "4.12" as the amount.)

## v0.10.0 - share anything in (Nov 1, 2026)

- **Share-sheet import.** MainActivity now takes ACTION_SEND with
  image/* and application/pdf, plus ACTION_SEND_MULTIPLE for images.
  Single shares run the SAME pipeline as picked files (the pick
  handler was refactored into `importUri(Uri, forcePhoto)`): copy,
  PDF read / text layer, then the annotate editor. Multi-share files
  items headlessly (`importUrisHeadless`: OCR via a new
  `Ocr.recognizeSync`, PDF read, facts, embeddings, one toast).
- **Copy path hardened:** `copyUriToVault` now reads through
  `openFileDescriptor` instead of `openInputStream` - against
  Samsung's MediaStore the stream form STALLS FOREVER on a denied
  Uri while the FD form throws the SecurityException immediately.
  An empty copy now fails loudly instead of filing a 0-byte item.
- Test finding: `adb am --grant-read-uri-permission` grants do NOT
  stick for media Uris (shell cannot delegate what scoped storage
  does not give it) - the adb-driven share fails with a clean
  "Import failed". The REAL path was proven end-to-end by driving
  Samsung Gallery's own share sheet: RecallVault listed, tapped,
  photo copied (114,800 B), OCR ran in the editor.

## v0.11.0 - backup freshness, no-wait stop, voice notes transcribe (Nov 1, 2026)

- **Backup freshness.** A successful export records when it ran and
  how many memories it holds (prefs backup_last_at /
  backup_item_count). Settings' Backup & security card now says
  "No backup yet - your memories exist only on this phone", "Last
  backup: <date> - it covers everything", or "N memories are not in
  it yet" (amber when stale). Tracking starts with the first export
  made on v0.11.0+.
- **Non-blocking meeting stop.** The UI-thread `join(25s)` on the
  caption worker is gone. Stop returns instantly; the worker flushes
  the final caption segment on its own thread, saveNote snapshots
  what has landed, and the full background pass replaces it with the
  complete transcript (the design the wart was excepted from).
- **Voice notes auto-transcribe.** The post-save background
  transcription that meetings always had now runs for voice notes
  too; an undictated note also gets its title from its own words
  when the transcript lands. Device-proven: a speaker-played note
  saved with no dictation titled itself from its transcript about
  10 seconds later.

## v0.12.0 - automatic backup (Nov 1, 2026)

- The vault backs itself up weekly to a folder chris picks
  (SAF tree, persisted grant). Format RVBK2: the same ZIP as the
  password export, encrypted with a random AES-256 key held in
  the Android Keystore - these copies restore on this phone only;
  the password RVBK1 export stays the phone-moving one, and the
  Settings copy says exactly that. Keeps the newest 4.
- Runs piggybacked on the daily reminder worker when the last
  backup is over 6 days old; Settings shows when it last ran and
  how much it held. Device-proven: it ran by itself within
  seconds of the folder grant and wrote a valid RVBK2 file.
- Settings dialog content is scrollable now (the stack outgrew
  one screen and the bottom cards were clipped).
- House rule (chris): a model card whose model is already on the
  phone shows a disabled "Downloaded" button, never a live
  Download button.

## v0.13.0 - reminders you can finish (Nov 1, 2026)

- DB v4 handled_at. A bill you paid stops nagging: notification
  actions "Mark paid" / "Mark done", and the Reminders tab lists
  open items first with the same buttons, then a dimmed Handled
  section with "Undo - still open". Ask answers and the due
  digest skip handled items. Device-proven with staged bills.

## v0.14.0 - home-screen widget (Nov 1, 2026)

- 4x2 widget: what is due this week (same digest as Ask), a
  Voice note button that drops straight into dictation, and an
  Open vault button. Refreshed on every app render and by the
  daily worker. It lives on page 2 of chris's home screen.

## v0.15.0 - duplicate guard + share a memory out (Nov 1, 2026)

- DB v5 file_hash (SHA-256). Importing bytes that are already
  filed refuses the copy, says "Already in your vault", and
  opens the existing memory instead. Single and multi imports.
- Detail gains a Share action: the memory's text, or its file
  through the app's own ShareProvider.
- Photo search by meaning, proven end to end at last: a textless
  red-car drawing (zero OCR-able text) ranked #1 for "red"
  against a screenshot photo and a bill - the vision embedding
  did it.

## v0.16.0 - vault text encrypted at rest (Nov 1, 2026)

- Titles, bodies, extracted text and amounts are stored as
  AES-256-GCM ciphertext (Keystore key, "enc1:" prefix) and
  decrypted on read; unprefixed legacy rows pass through, and a
  one-time background migration rewrote every row. Media files
  are not encrypted (app sandbox + phone lock), and Settings
  says so plainly. Device-proven: the raw database shows
  ciphertext, the app shows his words.

## v0.17.0 - patterns (Nov 1, 2026)

- The vault learns his bills: same-stem dated amounts group into
  recurring series; the Reminders tab shows "usually $X" with
  the saved range, and projects the monthly total. Ask answers
  "is my electricity bill higher than usual" with the usual
  figure and the difference. Device-proven with a staged
  electricity series ($78 / $81.50 / $92 - "higher than usual
  by $10.50").

## v0.18.0 - Galaxy Watch capture (Nov 1, 2026)

- Phone half: a Wearable listener files anything the watch sends
  as a voice memory (facts extracted, embedded, confirmation
  notification). Watch half: a standalone Wear OS app -
  dictate, and the note lands in the vault. Both built and the
  phone service is registered.
- NOT yet proven on the watch itself: installing needs the
  pairing code from the watch's Wireless debugging screen,
  which only chris can read off the watch.

## v0.19.0 - Listen button (Nov 1, 2026)

- A memory can be read aloud: Detail's Listen button speaks the
  title, the words, the due date and the amounts via the phone's
  own text-to-speech; the button becomes Stop while speaking.
  Device-proven on his voice note.

## v0.20.0 - multi-page scans (Nov 1, 2026)

- DB v6 pages. A scan can grow: "Add a page (camera)" appends a
  photographed page; every page is OCR'd into the memory's text,
  the detail screen pages through them, backups carry the page
  files, delete removes them.
- Real bug found by the proof: Samsung's camera refuses the
  capture intent when the caller lacks the CAMERA runtime
  permission (auto-revoked on his phone) - the detail screen now
  asks for it first, like the main Scan flow always did.
  Device-proven: "Page 2 of 2" on a real second photograph.

## v0.21.0 - plain readable export (Nov 1, 2026)

- Settings exports one Markdown file a person can open anywhere:
  every memory grouped by category with its dates, amounts,
  words and file names - a reading copy, not a restore format.
  Device-proven into the backup folder next to the auto-backup.

## v0.22.0 - on this day, timeline, year in review (Nov 1, 2026)

- The Vault tab surfaces an "On this day" card (same month/day,
  a year or more back), a By-month view groups the vault under
  month headers, and Ask answers "my year in review" with the
  year's counts, money and busiest month. Pure logic is
  JVM-tested (suite at 88 then). Device-proven with a staged
  year-old lease memory.

## v0.23.0 - linked memories (Nov 1, 2026)

- DB v7 links. Any two memories can be linked, both ways, from
  the detail screen's picker; linked memories show on each
  other's pages and open on tap. Deleting one scrubs the link
  everywhere. Backups carry links as creation-time keys so a
  restore re-links correctly on the new phone. JVM suite: 94.
  Device-proven both directions.

## v0.24.0 - add to calendar (Nov 1, 2026)

- A dated memory offers "Add to calendar": the phone's calendar
  app opens with the event prefilled (title, due time, one hour,
  the memory's words as the description) and does the saving.
  Device-proven in Samsung Calendar - opened prefilled, then
  discarded so no test event landed in his calendar.

## v0.25.0 - trash with undo (Nov 1, 2026)

- DB v8 deleted_at. Delete now moves a memory to Trash
  (Settings - Trash): hidden everywhere, restorable for 30 days
  with its files intact, then purged for good on app start.
  "Delete forever" is there too. Device-proven round trip:
  trashed via the detail dialog, listed with "30 days left",
  restored back into the vault.

## v0.26.0 - staples (Nov 1, 2026)

- Storage meter in Settings ("Storage used by the vault and its
  models: 703 MB" on his phone - models included).
- Check a backup file: pick any .rvbackup and hear whether it is
  good without restoring it - automatic backups verify against
  the phone's key; password backups ask for the password.
  Device-proven on his real auto-backup: "Automatic backup - 1
  memories inside", with the date range.
- Import a folder: pick a folder and every readable file in it
  (subfolders included, 300 max) files itself headlessly - text
  categorized, PDFs read, photos OCR'd. Device-proven: a folder
  of a water bill, a meeting note and two images filed as Bills,
  Meetings and Photos.

## v0.27.0 - automatic ingest (Nov 1, 2026)

- Chris's ask: give the vault access to phone files - limited or
  all - and have it index and ingest by itself. Settings gains
  an "Automatic ingest" card with both scopes:
  - Watched folders (SAF grants he picks): walked on the daily
    worker pass and on app open; a seen-index (document id +
    last-modified + size, codec JVM-tested) keeps rewalks cheap
    and the SHA-256 guard catches anything it misses.
  - Whole phone: new photos and audio via MediaStore, gated by
    the media permissions and a watermark set when the mode is
    turned on - only files added after that moment, never the
    existing library. (Android's media permissions do not reach
    documents; folders cover those, and the copy says so.)
  - Everything filed goes through the standard pipeline: copy,
    hash, OCR/PDF text, fact extraction, embeddings - indexed
    and searchable like a manual save.
- Device-proven all three ways: a watched folder's electric
  bill filed itself as Bills, a new water bill was picked up on
  the next scan, a repeat scan filed 0 (no duplicates), and with
  phone mode on, a fresh screenshot was ingested as a Photo and
  the watermark advanced to its timestamp. Shipped OFF with no
  sources configured - chris picks the real folders (or phone
  mode) when he is ready. JVM suite: 96/96.
- Note from the proof: a probe tap briefly trashed his voice
  note during testing; the v0.25.0 trash caught it exactly as
  designed and it was restored untouched within the session.

## v0.28.0 - morning digest (Nov 1, 2026)

- Once a day the reminder worker also posts a digest
  notification: what is overdue, what is due today, what is due
  this week, a bill that is running hot against its own pattern,
  and an on-this-day memory - composed only when there is
  something worth saying. Settings gains a Morning digest switch
  and a Preview button.
- Device-proven: the posted notification read "Today in your
  vault - 1 overdue item" with the staged overdue and due-today
  lines visible in the notification shade dump.

## v0.29.0 - meeting action items (Nov 1, 2026)

- Meetings give up their homework: commitment sentences ("I'll
  renew...", "don't forget to...", "call the...") are extracted
  from transcripts and filed as linked "Actions" memories with
  any date they carry; Ask and the Reminders tab gain an Action
  items section with Mark done / Undo. Live captions at save
  time and the post-transcription pass both file, deduped.
- Device-proven: a staged landlord meeting produced all three
  commitments in the Reminders tab, the lease carrying its date.

## v0.30.0 - expiry radar (Nov 1, 2026)

- Expiry is tracked apart from due dates (schema v9, expiry_at
  recomputed from the words at every save, one-time backfill):
  insurance cards, licenses, warranties. Reminders tab gains an
  Expiring section (later ones are counted, not listed); Ask
  answers "what expires soon"; the worker pings once when an
  expiry comes within 30 days or passes.
- Device-proven end to end. The proof caught a real bug - the
  Ask narrowing treated the word "expires" as a topic keyword,
  so only items containing it were listed; trigger words are
  now excluded from narrowing, and "what expires soon" lists
  everything.

## v0.31.0 - people & places (Nov 1, 2026)

- The vault notices names: capitalized phrases and acronyms
  that span memories, repeat, or sit inside a title (lone
  capitalized first words are not names). The Ask tab lists the
  top names as one-tap chips; "everything about X" lines up
  every memory that mentions X, newest first.
- Device-proven: chips read "Avista Utilities (2 memories)",
  "CHAS (1 memory)", "Gina (1 memory)"; tapping Avista lined
  up both dated memories.

## v0.32.0 - follow-up questions (Nov 1, 2026)

- Ask remembers the last question's window: "and November?",
  "what about Apr?", a bare "December" - short follow-ups
  rewrite themselves against the previous question, and the
  rewrite is shown in the box. Supporting fix found by the
  proof: named months are now real windows in Ask summaries
  (before, "how much do I owe in November" never scoped -
  the month leaked into the topic keywords).
- Device-proven: "how much do I owe in October" answered "2
  due in October, totaling $120.00"; "and November?" rewrote
  itself in the box and answered on the November bill.

## v0.33.0 - bill trend view (Nov 1, 2026)

- Recurring patterns now carry every occurrence; tapping a
  bill on the Reminders Patterns card opens its trend: usual,
  count, range, and a bar per occurrence (amber when above
  usual), with a plain line when the latest runs hot.
- Device-proven: the electricity series showed "Usually
  $81.50 - 3 saved - range $78.00 to $92.00 - about monthly"
  and "The latest is $10.50 above usual."

## v0.34.0 - photo look-alikes (Nov 1, 2026)

- Photos whose vision vectors nearly coincide (cosine >=
  0.98) are grouped on the Vault tab as look-alikes - bursts,
  re-saves, recompressed copies the hash guard cannot catch.
  Review-only by design: each photo opens its detail page,
  and tidying happens from there, never automatically.
- Device-proven with real embeddings: two saves of the same
  drawing grouped; a different drawing stayed out.

## v0.35.0 - quick-capture tile (Nov 1, 2026)

- A Quick Settings tile ("Voice note") and a launcher
  long-press shortcut, both landing on MainActivity with a
  quick-capture extra: the voice recorder opens as soon as
  the app lock clears - the tile goes through the same lock
  gate as opening the app, never around it. (Placing the
  tile in the shade panel is the standard one-time drag.)
- Device-proven: service registered for QS_TILE; a cold
  start with the lock on lands on the biometric prompt; with
  the lock off, straight into the recorder.

## v0.36.0 - monthly report (Nov 1, 2026)

- "Your October in the vault": memories filed, the biggest
  categories, bills that came due and their total, reminders
  marked done, the busiest day, who kept coming up, and what
  next month already holds (plus the recurring run-rate).
  Offered as a Vault card once when a new month starts, and
  by Ask for any month ("my October report").
- Device-proven: the staged October read "3 memories filed.
  Mostly 2 bills, 1 people. Bills and dates that came due: 2,
  totaling $114.00. 1 reminder marked done. Kept coming up:
  Avista Utilities (2 memories)."

## v0.37.0 - Ask on the watch (Nov 1, 2026)

- The watch gains "Ask the vault": dictate a question and it
  travels to the phone (/recallvault/ask), which answers with
  the same hook chain as the Ask tab (semantic pass included)
  and sends the answer back (/recallvault/answer) for the
  watch to display. Companion watch app 1.1.0; note capture
  is unchanged.
- Both halves built and the phone half installed; the
  on-watch half still waits on the same pairing step as
  v0.18.0 (the code and port from the watch's Wireless
  debugging screen, which only chris can read).

## v0.38.0 - meeting recaps (Nov 1, 2026)

- A meeting's page opens with a recap card (Recap.java):
  what was decided, who owes what (with due dates), the key
  dates, and any money mentioned. A sentence that ran as a
  decision is never repeated as a to-do.

## v0.39.0 - bills forecast (Nov 1, 2026)

- "What's coming" on the Reminders tab (Forecast.java):
  the next three months merged from real dated bills plus
  recurrences projected from their own history (average gap,
  usual amount). A booked bill suppresses its own projection.
  Ask answers "what bills are coming" with month totals.

## v0.40.0 - heads-up reminders (Nov 1, 2026)

- One earlier nudge when a deadline first enters the lead
  window (HeadsUp.java; default 3 days, Settings cycles
  off/1/2/3/5/7/14). Exactly once per due date, outside the
  due worker's own 48h window so the two never stack.

## v0.41.0 - snooze (Nov 1, 2026)

- Snooze a reminder 1 day or 7 days from its Reminders row,
  its detail page, or the due notification itself
  (SnoozeReceiver). Snoozing moves the due date, so the new
  date earns its own fresh heads-up and due pings.

## v0.42.0 - receipt mode (Nov 1, 2026)

- Scanned/photo receipts file as structured expenses
  (Receipts.java): store from the first line, total from the
  TOTAL line (never the subtotal or the cash tendered), tax,
  date. Ask totals spending by month or store, and Settings
  exports expenses as CSV. (Parser/answer/CSV suite-proven;
  the on-phone Ask tap-through proof was cut short by the
  phone being in active use at ship time.)

## v0.43.0 - people cards (Nov 1, 2026)

- Name chips now open an at-a-glance card first (in
  Entities.java): phone numbers, emails, and street
  addresses found in the memories that mention the name,
  plus dates and money totals. "All memories" remains one
  tap deeper, and contact questions ("Gina's phone number")
  return the card from Ask directly.

## v0.44.0 - tap-the-facts (Nov 1, 2026)

- A memory's page gains a "Facts in this memory" section
  (Facts.java): phone numbers dial, emails compose, links
  open, street addresses map, UPS/USPS tracking numbers
  track. Tap uses a fact; long-press copies it.

## v0.45.0 - vault checkup (Nov 1, 2026)

- Settings -> Vault checkup (Checkup.java): dates named in
  words that never became reminders, text missing from the
  meaning index, photos whose words were never read, the
  biggest files, and backup freshness. "Fix what's fixable"
  sets the missed dates and re-runs the index pass.

## v0.46.0 - private memories (Nov 1, 2026)

- Any memory can be made private (Db v10, is_private;
  Privacy.java). Lists show a "Private memory" placeholder,
  Ask/watch/notifications/digest mask or skip it entirely,
  and its own page stays locked until that one item is
  unlocked with fingerprint/PIN. Item unlocks live for the
  session and re-lock with the app lock.

## v0.47.0 - Ask from anywhere (Nov 1, 2026)

- Highlight text in any app and "Ask vault about this"
  (AskTextActivity, PROCESS_TEXT) searches the vault for
  the selection - app lock gates it, private memories never
  appear. A second home-screen widget (AskWidget) goes
  straight to a voice question on the Ask tab.

## v0.48.0 - layout overhaul (Nov 1, 2026)

- Requested as "rethink the whole app layout - it looks
  really basic". Structure, not paint:
  - Shell: compact one-line header (brand + name + live
    memory count); the Reminders tab carries a badge with
    the number of deadlines overdue or due inside 48h.
  - Vault: the seven-tile capture card is gone from the
    home screen. A capture dock leads instead - Voice note
    hero, Note shortcut, and "+" opening the full grid as a
    sheet (Settings included, unchanged inside). Below it,
    an attention strip appears only when deadlines need the
    user (overdue or inside a week), one tap to Reminders.
  - The feed is day-grouped (DayGroup.java): Today,
    Yesterday, then dated days - a journal, not a stack.
    The by-month toggle still switches to month headers.
  - Ask reads as a conversation: the question sits in its
    own bubble above the answer, and earlier questions stay
    listed (tap to re-ask) instead of vanishing.
  - Reminders opens with at-a-glance counts (overdue / due
    today / more this week) above the existing sections.
- v0.48.0 as first built was BROKEN: a weighted spacer
  View in the new header row stretched to the full screen
  height on this phone, the header swallowed the whole
  window, and the app showed the brand row alone on black.
  v0.48.1 removes the spacer (the count now sits under the
  title) - verified with a pulled screenshot: header, dock,
  filter, chips, TODAY group, the voice-note card, and the
  tab bar all render as designed. The lesson is banked:
  the uiautomator tree was telling the truth the whole
  time; a "settled" dump with missing subtrees is a bug
  report, not a tooling quirk.

## Next queue

1. Watch install + on-watch proof (v0.18.0 capture and
   v0.37.0 ask) - needs the pairing code/port from the
   watch's Wireless debugging screen.
2. Final app name - chris's pick.
3. Automatic ingest scope pick (v0.27.0, shipped off) -
   whole-phone watching or watched folders; chris's call.
4. Ask widget placement - a one-time manual add from the
   launcher's widget picker, like the QS tile (v0.35.0).
   (Three full build lists are now complete: the Nov 1
   "do what i say" list v0.12.0-v0.26.0 plus v0.27.0, the
   Nov 1 "all" list v0.28.0-v0.37.0, and the second "all"
   list v0.38.0-v0.47.0.)

## v0.49.0 - three themes (Nov 1, 2026)

Directed after the v0.48 layout overhaul still read as "very
unimpressed with the ui layout and style": all three proposed
style directions, built as switchable themes.

- Ui's palette is now mutable state written by
  Ui.applyTheme(); applyChrome() (called by every activity)
  loads the current theme first, so one switch re-skins every
  screen. Themes: Console (amber #FFB224 on near-black brown,
  cream text, orange second accent - the ship-console
  language), Paper (warm cream background, ink text, rust
  primary, dark system-bar icons), Aurora (the original
  periwinkle/teal palette, kept intact).
- Settings gains an Appearance card listing the three
  themes with one-line descriptions; tapping one saves the
  choice, re-skins the shell live and reopens Settings in
  the new colors. Default on a fresh install: Console.
- Verified on the S24 Ultra with screenshots of all three
  themes on the Vault tab; Paper's light status/navigation
  icons confirmed readable. JVM suite 193/193.
- Note: the home-screen widgets use XML colors and do not
  follow the theme yet.

## v0.50.0 - the Stitch layout (Nov 1, 2026)

Chris generated a design in Google Stitch from a written brief
and sent the export; this release rebuilds the app's layout
in its register (an instrument console: rectangular tags,
monospace micro-labels, stat readouts), keeping only claims
that are true of this app (no fake "LLM synthesis", no
confidence percentages, no TEE/enclave language).

- Shell: top utility strip (ON-DEVICE / NO ACCOUNT - NO
  CLOUD), header count gains the vault's size on the phone.
- Every tab gets the design's big square-bullet title with
  an ON-DEVICE tag.
- Vault cards: type tag left (filled for voice), amount or
  category chip right, honest status word at the tail
  (TRANSCRIBED / REMINDER SET / INDEXED / OCR - LOCAL,
  RESTRICTED + masked line + [TAP TO AUTH] for private).
  Day headers carry entry counts.
- Capture sheet: hero voice card, rich tiles with bracket
  tags and one-line descriptions, meeting card, settings row.
- Ask: QUERY // time register block, VAULT ANSWER tag on
  summary cards, SOURCE rows as bordered sub-panels.
- Reminders: stat tiles (OVERDUE / TODAY / 7-DAY / PAYABLE -
  the payable total is new), bucketed register (DUE IN 24
  HOURS / THIS WEEK / LATER / HANDLED), register cards with
  DONE - PAID + direct +1 DAY / +7 DAYS snooze buttons, and
  the forecast gains a stacked share bar and per-month item
  lines.
- Empty vault: a first-run register card (three setup steps,
  record-first-memory and import-backup actions).

## v0.51.0 / v0.51.1 - no prose, bottom Ask bar (Nov 1, 2026)

Directed the same day: "Dont add hints on how it works.
Clean all that stuff up" and "it still doesn't look like
what I sent you".

- All how-it-works copy removed app-wide: help paragraphs,
  try-examples, search-mode lines, Settings intro and model
  explanations, tap-hints. Labels, values and state stay.
- Ask input moved to a bottom bar (input + mic + ASK),
  matching the design; results render at the top of the
  Ask screen, people & places below the fold (v0.51.1).
- Register tightened: cards and buttons go near-rectangular
  (8dp radius), section labels and tags are monospace.
- Verified on the S24 Ultra in the Console theme (the
  design's register): Vault, Ask, Reminders, capture sheet.

## v0.52.0 - the design's missing features (Nov 1, 2026)

Everything the Stitch screens showed that the app did not
have yet, built honestly on the engines already here:

- Ask answers that add up now carry their ledger: one row
  per item behind the total (date, title, amount, due-first
  for owed answers, newest-first for spending), tap a row
  to open the memory. Applies to Ask.summarize and to
  receipt spending answers.
- Bill actions on answers: a summary's next open bill and
  any result whose memory still has an open reminder get
  DONE - PAID / SNOOZE +24H right on the answer card; the
  answer refreshes in place. Follow-up chips sit under the
  results (owe / spending / due-this-week, or a
  higher-than-usual verdict chip for a recurring bill).
- Hardening found by the device proof: an amount with no
  due date (reminderAt 0) no longer counts toward "what do
  I owe" - a receipt staged with 0 briefly inflated a total.
- Meeting recaps render from Recap.parts (one source of
  truth with the text lines): decisions as text, to-dos as
  checkboxes with a n/m DONE counter, checked state kept
  per meeting in prefs - the transcript is never rewritten.
- Settings: Re-index vault (runs the same pending-embed
  pass used at save time, with a live unindexed count);
  Dense mode switch in Appearance (compact card padding,
  app-wide via Ui.DENSE); Backups on this phone - the
  automatic backups actually in the folder, tap one to
  decrypt-and-parse verify it; Danger zone ERASE
  EVERYTHING behind two confirmations (memories, files,
  trash, ingest seen-log, and the automatic backups).
- Export scope: the backup password dialog now offers All
  memories / Last 30 days only; freshness bookkeeping only
  advances on full exports, so Settings never overstates
  what a scoped export covers.
- JVM suite 200/200. Device-verified on the S24 Ultra:
  ledger + totals ($186.20 across two bills; a raw receipt
  no longer counted), DONE - PAID dropping the total to
  $145.20, chips after scroll, recap checkbox ticking to
  1/2 DONE and persisting, the real 20261101-0935 backup
  verifying from the list, dense pref toggling on/off,
  export scope radios rendering. The erase flow was
  verified up to its button only - never run.

## v0.53.0 - the brain: Gemma 4 + skills (Nov 1, 2026)

A second on-device model joins the vault: Gemma 4 E4B
(litert-community, Apache 2.0, ungated), the generic
.litertlm at 3,659,530,240 bytes, downloaded on demand
into app storage like the other models - Chris picked E4B
over E2B (smarter over smaller). It runs through LiteRT-LM
0.18, the runtime the embedder already shipped; the Kotlin
API is consumed from Java via its full constructors (the
$default bridges are synthetic and invisible to javac -
build-verified). Engine init tries GPU, falls back to CPU,
and any failure disables the feature quietly instead of
crashing the vault (the LocalEmbed rule). The native
engine also refuses a cache directory that does not exist
yet - GemmaBrain creates it first (device-verified).

What the brain does:
- Detail: "Summarize with the brain" writes a plain-words
  summary plus a Key points list, facts kept exact.
- Ask: a BRAIN card under the results - [ WRITE IT UP ]
  turns the retrieved memories into a written answer with
  [n] citations. The grounding block carries each item's
  due date and amount (without them the model mistook the
  saved date for the due date - caught in device proof).
- Tools: the conversation carries BrainTools - search_vault,
  read_memory, vault_due as LiteRT-LM function calls
  (InternalJsonTool), read-only, private memories excluded.
- Skills: SKILL.md instruction packs in the AI Edge Gallery
  community format (agentskills.io shape). Three bundled
  (daily-briefing, bill-helper, email-drafter); Settings
  lists them with per-skill switches, imports new ones from
  a picked SKILL.md, and links out to the Gallery community
  skills discussions. Enabled skills ride in the brain's
  system prompt as a catalog.

JVM suite 205/205 (skills parser). Device-verified on the
S24 Ultra: model downloaded through the app (exact byte
count), Settings cards render, summarize produced a correct
summary of a staged note, WRITE IT UP answered "what do I
owe" with a cited sentence. The due-date grounding fix was
built and installed; its on-device re-check was cut short
when the phone disconnected, and is owed a 30-second
re-proof when it reconnects.

## v0.53.1 - ingest race fix (Nov 1, 2026)

The triple-filed screenshot mystery, solved from the phone's
own DB: the three copies carried IDENTICAL file hashes and
were copied within 90 ms of each other - overlapping scans
(app-open hook + daily worker) racing the hash guard, which
is check-then-insert. Three fixes:
- A process-wide scan lock: a second scan() while one is
  running returns immediately.
- Phone mode now also records settled MediaStore ids in the
  seen-index ("media:<collection>:<id>"); the watermark only
  bounds the query, it is no longer the only memory.
- saveSeen writes a temp file and renames it - a kill
  mid-write had truncated the index to zero bytes.
Device-verified cleanup in the same visit: staged test rows
and the three dupe copies removed, their files deleted, the
app lock restored on (readback-checked).

## v0.54.0 - smart text ingest (Nov 2, 2026)

Texts join automatic ingest, but only the ones with data
worth keeping - Chris's criteria: plans, dates, meetings,
appointments, times, things to remember. SmsFilter (pure,
JVM-tested) builds on Extract's facts: a text files when it
carries a due date, a plan (day/time next to a plan word),
an explicit remember-signal (don't-forget, gate codes,
addresses), an amount WITH bill context or a date, two
go/do/get keywords (prescription + pickup, flight +
boarding), or an action keyword with a link. One-time
verification codes are vetoed. Filed texts become notes
under the sender, stamped with the message's own time,
facts extracted like everything else. New texts only
(watermark), settled ids in the seen-index, READ_SMS behind
a Settings switch and the runtime permission.

Device reality (S24 Ultra): Samsung's provider refuses
shell-injected rows, so synthetic staging was impossible;
the pipeline was proven against his real inbox instead.
His provider store holds nothing newer than Apr 2023, and
a deliberate 2023-window run exposed the first filter as
too loose (100 automated alerts filed, hitting the scan
cap) - every one removed again, and the rules tightened
(v0.54.1) before the feature ever saw a live text: bare
amounts no longer file, the score catch-all is gone, and
keyword pairs count only from the go/do/get set. Suite
224/224.

## v0.54.1 - her name is Clio (Nov 2, 2026)

RecallVault is retired as the display name. Chris picked
Clio - the muse of history - as the app's character, and
supplied her mark: a bold C that is also a vault door.
Launcher label, header, lock screen, exports and backup
messages now say Clio; her glyph (his image, unmodified)
is the launcher icon and the header mark. The package
stays com.irwinlabs.recallvault, so the vault, models and
settings carry over untouched. The brain speaks as Clio:
the Ask system prompt and the summary prompt introduce
her as the keeper of the vault, first person, same
grounding rules - only facts from the memories, no
invention. The Settings card is "Clio - Gemma 4", the
Ask card is tagged CLIO, and the Detail button reads
"Summarize with Clio". Includes the v0.54.0 filter
tightening above. Header verified on device.

## v0.54.2 - her mark, readable (Nov 2, 2026)

Chris's call: the logo's black ground was too dark and
the glyph itself disappeared in the app. The mark is now
a cutout - background fully transparent (assets/
clio-logo-transparent.png, derived from his original,
which is kept untouched) - and the charcoal fill is
lifted to a graphite that reads on dark surfaces; the
magenta rim is preserved pixel for pixel. The launcher
icon uses the transparent cutout over a transparent
adaptive background, and the header mark drops its tile:
the C sits directly on the header. Verified on device.
