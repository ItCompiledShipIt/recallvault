package com.irwinlabs.recallvault;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.File;

/**
 * Ocr - on-device text recognition for photos and scans, via ML Kit's
 * bundled Latin text-recognition model. No image leaves the phone:
 * the model ships inside the APK and runs locally.
 */
public final class Ocr {

    private Ocr() {}

    public interface Callback {
        void onText(String text);
        void onError(String message);
    }

    /** Recognize text in an image file. Callbacks fire on the main thread (ML Kit default executor). */
    public static void recognize(File imageFile, final Callback cb) {
        Bitmap bmp = decodeScaled(imageFile, 2048);
        if (bmp == null) {
            cb.onError("Could not read that image");
            return;
        }
        InputImage image = InputImage.fromBitmap(bmp, 0);
        final TextRecognizer client =
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        client.process(image)
            .addOnSuccessListener(new OnSuccessListener<Text>() {
                @Override public void onSuccess(Text result) {
                    client.close();
                    cb.onText(result.getText() == null ? "" : result.getText());
                }
            })
            .addOnFailureListener(new OnFailureListener() {
                @Override public void onFailure(Exception e) {
                    client.close();
                    cb.onError(e.getMessage() == null ? "OCR failed" : e.getMessage());
                }
            });
    }

    /**
     * Synchronous recognize for background workers (v0.10.0 multi-share
     * files items headlessly). Blocks up to 30s; returns "" on any
     * failure - a photo with no readable text is a normal outcome.
     */
    public static String recognizeSync(File imageFile) {
        try {
            Bitmap bmp = decodeScaled(imageFile, 2048);
            if (bmp == null) return "";
            InputImage image = InputImage.fromBitmap(bmp, 0);
            TextRecognizer client =
                TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
            try {
                Text result = com.google.android.gms.tasks.Tasks.await(
                    client.process(image), 30, java.util.concurrent.TimeUnit.SECONDS);
                return result == null || result.getText() == null ? "" : result.getText();
            } finally {
                client.close();
            }
        } catch (Exception e) {
            return "";
        }
    }

    /** Decode with sampling so the long edge is at most maxDim px (ML Kit sweet spot, sane memory). */
    static Bitmap decodeScaled(File f, int maxDim) {
        BitmapFactory.Options probe = new BitmapFactory.Options();
        probe.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), probe);
        if (probe.outWidth <= 0 || probe.outHeight <= 0) return null;
        int sample = 1;
        int longest = Math.max(probe.outWidth, probe.outHeight);
        while (longest / sample > maxDim) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        return BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
    }
}
