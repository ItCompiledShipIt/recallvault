package com.irwinlabs.recallvault;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Profile and contact photos (v0.63.0): picked images are
 * copied into the app's private storage, scaled down to
 * a small square-ish JPEG, and drawn as round discs. The
 * originals never leave the phone and nothing is synced.
 * Contact photos live in files/contact_photos/; the
 * profile photo is files/profile.jpg.
 */
public class ContactPhotos {

    private static final int MAX_PX = 512;
    private static final Map<String, Bitmap> CACHE = new HashMap<String, Bitmap>();

    public static File dir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "contact_photos");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static File profileFile(Context ctx) {
        return new File(ctx.getFilesDir(), "profile.jpg");
    }

    /** Copy a picked image into private storage, scaled.
     *  Returns the stored filename, or null on failure. */
    public static String savePicked(Context ctx, Uri uri) {
        try {
            Bitmap b = decodeScaled(ctx, uri);
            if (b == null) return null;
            String name = "c" + System.currentTimeMillis() + ".jpg";
            File out = new File(dir(ctx), name);
            writeJpeg(b, out);
            CACHE.remove(name);
            return out.exists() ? name : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Replace the profile photo with a picked image. */
    public static boolean saveProfile(Context ctx, Uri uri) {
        try {
            Bitmap b = decodeScaled(ctx, uri);
            if (b == null) return false;
            writeJpeg(b, profileFile(ctx));
            CACHE.remove("__profile__");
            return profileFile(ctx).exists();
        } catch (Exception e) {
            return false;
        }
    }

    private static Bitmap decodeScaled(Context ctx, Uri uri) throws Exception {
        InputStream in = ctx.getContentResolver().openInputStream(uri);
        if (in == null) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeStream(in, null, bounds);
        in.close();
        int sample = 1;
        int maxDim = Math.max(bounds.outWidth, bounds.outHeight);
        while (maxDim / sample > MAX_PX * 2) sample *= 2;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        in = ctx.getContentResolver().openInputStream(uri);
        Bitmap b = BitmapFactory.decodeStream(in, null, o);
        if (in != null) in.close();
        if (b == null) return null;
        if (Math.max(b.getWidth(), b.getHeight()) > MAX_PX) {
            float f = (float) MAX_PX / Math.max(b.getWidth(), b.getHeight());
            b = Bitmap.createScaledBitmap(b,
                Math.round(b.getWidth() * f), Math.round(b.getHeight() * f), true);
        }
        return b;
    }

    private static void writeJpeg(Bitmap b, File out) throws Exception {
        FileOutputStream fos = new FileOutputStream(out);
        b.compress(Bitmap.CompressFormat.JPEG, 85, fos);
        fos.flush();
        fos.close();
    }

    public static void delete(Context ctx, String filename) {
        if (filename == null || filename.isEmpty()) return;
        try {
            new File(dir(ctx), filename).delete();
        } catch (Exception ignored) {}
        CACHE.remove(filename);
    }

    /** A round-cropped bitmap for a stored photo, or null. */
    public static Bitmap roundBitmap(Context ctx, File f, int sizePx) {
        if (f == null || !f.exists()) return null;
        String key = f.getName() + "@" + sizePx + "@" + f.lastModified();
        Bitmap hit = CACHE.get(key);
        if (hit != null) return hit;
        Bitmap src = BitmapFactory.decodeFile(f.getAbsolutePath());
        if (src == null) return null;
        // Center-crop to a square, scale, then mask to a circle.
        int side = Math.min(src.getWidth(), src.getHeight());
        int x = (src.getWidth() - side) / 2;
        int y = (src.getHeight() - side) / 2;
        Bitmap sq = Bitmap.createBitmap(src, x, y, side, side);
        if (side != sizePx) sq = Bitmap.createScaledBitmap(sq, sizePx, sizePx, true);
        Bitmap out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        Path clip = new Path();
        clip.addCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, Path.Direction.CW);
        cv.clipPath(clip);
        cv.drawBitmap(sq, 0, 0, new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
        CACHE.put(key, out);
        return out;
    }

    public static Bitmap contactRound(Context ctx, String filename, int sizePx) {
        if (filename == null || filename.isEmpty()) return null;
        return roundBitmap(ctx, new File(dir(ctx), filename), sizePx);
    }

    public static Bitmap profileRound(Context ctx, int sizePx) {
        File f = profileFile(ctx);
        if (!f.exists()) return null;
        String key = "__profile__@" + sizePx + "@" + f.lastModified();
        Bitmap hit = CACHE.get(key);
        if (hit != null) return hit;
        Bitmap b = roundBitmap(ctx, f, sizePx);
        if (b != null) CACHE.put(key, b);
        return b;
    }
}
