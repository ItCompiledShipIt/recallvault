package com.irwinlabs.recallvault;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import java.io.File;

/**
 * PdfText - reads a PDF's words on-device, two ways:
 *  1. Its embedded text layer (PdfBox-Android) - fast, exact.
 *  2. If there is (almost) no text layer, the PDF is a scan: pages are
 *     rendered with the framework PdfRenderer and read with ML Kit
 *     OCR (v0.3). Up to 20 pages.
 * Everything runs on the phone. Call read() from a background thread.
 */
public final class PdfText {

    private PdfText() {}

    public static class Result {
        public String text = "";
        /** true when the words came from OCR of rendered pages, not a text layer. */
        public boolean ocr = false;
    }

    private static boolean initialized = false;
    private static final int MAX_CHARS = 200000;
    private static final int MIN_LAYER_CHARS = 40;
    private static final int MAX_OCR_PAGES = 20;

    public static synchronized void init(Context ctx) {
        if (!initialized) {
            PDFBoxResourceLoader.init(ctx.getApplicationContext());
            initialized = true;
        }
    }

    /** Extract only the embedded text layer. Blocking. Returns "" on any failure. */
    public static String extract(File pdf) {
        PDDocument doc = null;
        try {
            doc = PDDocument.load(pdf);
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(doc);
            if (text == null) return "";
            text = text.trim();
            if (text.length() > MAX_CHARS) text = text.substring(0, MAX_CHARS);
            return text;
        } catch (Exception e) {
            return "";
        } finally {
            if (doc != null) {
                try { doc.close(); } catch (Exception e) {}
            }
        }
    }

    /** Full read: text layer first, OCR of rendered pages when the layer is (near-)empty. Blocking. */
    public static Result read(File pdf) {
        Result r = new Result();
        String layer = extract(pdf);
        if (layer.length() >= MIN_LAYER_CHARS) {
            r.text = layer;
            r.ocr = false;
            return r;
        }
        String ocr = ocrPages(pdf);
        if (ocr.length() > layer.length()) {
            r.text = ocr;
            r.ocr = true;
        } else {
            r.text = layer;
            r.ocr = false;
        }
        return r;
    }

    /** Render pages to bitmaps and OCR them. Returns "" on any failure. */
    private static String ocrPages(File pdf) {
        ParcelFileDescriptor pfd = null;
        PdfRenderer renderer = null;
        TextRecognizer client = null;
        try {
            pfd = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY);
            renderer = new PdfRenderer(pfd);
            client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
            StringBuilder sb = new StringBuilder();
            int pages = Math.min(renderer.getPageCount(), MAX_OCR_PAGES);
            for (int i = 0; i < pages; i++) {
                PdfRenderer.Page page = renderer.openPage(i);
                try {
                    int width = 1700;
                    int height = Math.max(1, (int) ((long) width * page.getHeight() / page.getWidth()));
                    Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                    bmp.eraseColor(Color.WHITE);
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    com.google.mlkit.vision.text.Text result =
                        Tasks.await(client.process(InputImage.fromBitmap(bmp, 0)));
                    if (result != null && result.getText() != null) {
                        String t = result.getText().trim();
                        if (!t.isEmpty()) {
                            if (sb.length() > 0) sb.append('\n');
                            sb.append(t);
                        }
                    }
                    bmp.recycle();
                    if (sb.length() > MAX_CHARS) break;
                } finally {
                    page.close();
                }
            }
            String out = sb.toString().trim();
            if (out.length() > MAX_CHARS) out = out.substring(0, MAX_CHARS);
            return out;
        } catch (Exception e) {
            return "";
        } finally {
            if (client != null) client.close();
            if (renderer != null) renderer.close();
            if (pfd != null) {
                try { pfd.close(); } catch (Exception e) {}
            }
        }
    }
}
