package com.irwinlabs.recallvault;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Photo look-alikes (v0.34.0): the vault already carries a vision
 * vector for every photo (that is what photo search by meaning
 * runs on). Two photos whose vectors nearly coincide are almost
 * certainly the same shot - a burst, a re-save, a recompressed
 * copy the hash guard could not catch. This groups them so they
 * can be reviewed; it never deletes anything by itself.
 */
public final class NearDupes {

    private NearDupes() {}

    /** Cosine at or above this counts as "the same photo". */
    public static final float SIM_THRESHOLD = 0.98f;

    /** Groups (size 2+) of photos with near-identical vision vectors. */
    public static List<List<Item>> groups(List<Item> items) {
        List<Item> photos = new ArrayList<Item>();
        List<float[]> vecs = new ArrayList<float[]>();
        for (Item it : items) {
            if (it.embeddingImage == null || it.embeddingImage.isEmpty()) continue;
            float[] v = Embed.decode(it.embeddingImage);
            if (v == null) continue;
            photos.add(it);
            vecs.add(v);
        }
        // Union-find over the near-identical pairs.
        int n = photos.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (Embed.cosine(vecs.get(i), vecs.get(j)) >= SIM_THRESHOLD) {
                    union(parent, i, j);
                }
            }
        }
        Map<Integer, List<Item>> byRoot = new HashMap<Integer, List<Item>>();
        for (int i = 0; i < n; i++) {
            int r = find(parent, i);
            List<Item> g = byRoot.get(r);
            if (g == null) { g = new ArrayList<Item>(); byRoot.put(r, g); }
            g.add(photos.get(i));
        }
        List<List<Item>> out = new ArrayList<List<Item>>();
        for (List<Item> g : byRoot.values()) {
            if (g.size() >= 2) out.add(g);
        }
        return out;
    }

    private static int find(int[] p, int i) {
        while (p[i] != i) { p[i] = p[p[i]]; i = p[i]; }
        return i;
    }

    private static void union(int[] p, int a, int b) {
        p[find(p, a)] = find(p, b);
    }
}
