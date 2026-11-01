package com.irwinlabs.recallvault;

/** One saved memory: a note, voice memo, photo, scan, or imported file. */
public class Item {
    public long id = -1;
    public String title = "";
    /** TEXT, VOICE, PHOTO, SCAN, DOCUMENT */
    public String type = "TEXT";
    /** Bills, Receipts, Wallet, People, Meetings, Notes */
    public String category = "Notes";
    /** The user's own text / transcript / note attached to a file. */
    public String body = "";
    /** Machine-read text (OCR/import/transcript copy) used for search. */
    public String extracted = "";
    /** Absolute path inside app storage for file-backed items, or "". */
    public String filePath = "";
    public String mime = "";
    public long createdAt = 0;
    /** Deadline/reminder epoch millis, or -1. */
    public long reminderAt = -1;
    /** Comma-joined amounts found, e.g. "$84.12,$10.00". */
    public String amounts = "";
    /** EmbeddingGemma 2 vector as comma-joined floats, or "" when not embedded yet. */
    public String embedding = "";
    /**
     * On-device EmbeddingGemma 2 vector (LiteRT int4 build), comma-joined
     * floats, or "". A separate space from the server vector on purpose:
     * the two builds are quantized differently, so vectors are only ever
     * compared within their own space.
     */
    public String embeddingLocal = "";
    /**
     * On-device EmbeddingGemma 2 IMAGE vector (the 440M model's vision
     * tower), same unified 768-dim space as embeddingLocal, or "".
     * Lets a text question find a photo by what is IN the picture.
     * Only for image-backed items (PHOTO / SCAN).
     */
    public String embeddingImage = "";
    /** When a dated memory was marked paid/done (epoch millis), or 0 while open. */
    public long handledAt = 0;
    /** SHA-256 of the attached file's bytes, or "" - the duplicate guard. */
    public String fileHash = "";
    /** Extra page image paths for a multi-page scan, newline-joined (v0.20.0). Page 1 is filePath. */
    public String pages = "";
    /** Linked memory ids, comma-separated, stored on both sides (v0.23.0, Links). */
    public String links = "";
    /** When this memory was moved to trash; 0 = not trashed (v0.25.0). */
    public long deletedAt = 0;
    /** When the thing in this memory stops being valid; 0 = none (v0.30.0). */
    public long expiryAt = 0;

    /** All page paths in order: filePath first, then the extra pages. */
    public java.util.List<String> pagePaths() {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (filePath != null && !filePath.isEmpty()) out.add(filePath);
        if (pages != null && !pages.isEmpty()) {
            for (String p : pages.split("\n")) if (!p.isEmpty()) out.add(p);
        }
        return out;
    }

    public String searchableText() {
        StringBuilder sb = new StringBuilder();
        sb.append(title).append('\n').append(category).append('\n');
        sb.append(body).append('\n').append(extracted).append('\n').append(amounts);
        return sb.toString();
    }
}
