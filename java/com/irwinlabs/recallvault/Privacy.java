package com.irwinlabs.recallvault;

import java.util.ArrayList;
import java.util.List;

/**
 * Private memories (v0.46.0): a memory can be marked extra-private.
 * Its words then stay out of everything casual - lists show a
 * placeholder, Ask and the watch never see it, notifications and
 * the digest mask its title - until the item itself is unlocked
 * with the device credential on its own page. The data itself is
 * already encrypted at rest like the rest of the vault; this is
 * about not displaying it.
 */
public final class Privacy {

    private Privacy() {}

    public static final String MASKED_TITLE = "Private memory";

    public static boolean isPrivate(Item it) {
        return it != null && it.isPrivate == 1;
    }

    /** The title as the world may see it. */
    public static String displayTitle(Item it) {
        return isPrivate(it) ? MASKED_TITLE : it.title;
    }

    /** The items a surface is allowed to read words from. */
    public static List<Item> visible(List<Item> items) {
        List<Item> out = new ArrayList<Item>();
        for (Item it : items) {
            if (!isPrivate(it)) out.add(it);
        }
        return out;
    }
}
