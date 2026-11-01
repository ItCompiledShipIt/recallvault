package com.irwinlabs.recallvault;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;

/**
 * Minimal file provider (own implementation, no AndroidX dependency).
 * Exposes only app-private storage dirs as
 * content://com.irwinlabs.recallvault.files/<dir>/<name> where dir is
 * one of captures / imports / voice - so the camera app can write a
 * full-resolution photo in, and viewer apps can read files out,
 * without any broad storage permission.
 */
public class ShareProvider extends ContentProvider {

    private static final String[] DIRS = {"captures", "imports", "voice", "restored", "pages"};

    public static Uri uriFor(File f) {
        String dir = f.getParentFile() != null ? f.getParentFile().getName() : "captures";
        return Uri.parse("content://com.irwinlabs.recallvault.files/" + dir + "/" + f.getName());
    }

    private File resolve(Uri uri) {
        List<String> seg = uri.getPathSegments();
        if (seg.size() != 2) return null;
        String dir = seg.get(0);
        String name = seg.get(1);
        boolean dirOk = false;
        for (String d : DIRS) if (d.equals(dir)) dirOk = true;
        if (!dirOk) return null;
        if (name.contains("/") || name.contains("..")) return null;
        return new File(new File(getContext().getFilesDir(), dir), name);
    }

    @Override public boolean onCreate() { return true; }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = resolve(uri);
        if (f == null) throw new FileNotFoundException("bad uri");
        int m = ParcelFileDescriptor.MODE_READ_ONLY;
        if (mode.contains("w")) {
            f.getParentFile().mkdirs();
            m = ParcelFileDescriptor.MODE_WRITE_ONLY
                | ParcelFileDescriptor.MODE_CREATE
                | ParcelFileDescriptor.MODE_TRUNCATE;
        }
        return ParcelFileDescriptor.open(f, m);
    }

    @Override
    public String getType(Uri uri) {
        String name = uri.getLastPathSegment();
        if (name == null) return "application/octet-stream";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".pdf")) return "application/pdf";
        if (name.endsWith(".m4a")) return "audio/mp4";
        if (name.endsWith(".txt")) return "text/plain";
        return "application/octet-stream";
    }

    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
