package com.irwinlabs.recallvault;

import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;

/** SHA-256 of a file's bytes - the duplicate guard's fingerprint (v0.15.0). */
public final class Hashes {

    private Hashes() {}

    public static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        FileInputStream in = new FileInputStream(f);
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        in.close();
        byte[] d = md.digest();
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
