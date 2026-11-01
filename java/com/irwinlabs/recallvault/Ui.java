package com.irwinlabs.recallvault;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * RecallVault design system (v0.5.0 professional pass).
 *
 * Near-black surfaces, one periwinkle accent, teal secondary, bone
 * text. Everything tappable ripples; every input is a filled rounded
 * field with an accent focus ring; icons are Material glyphs
 * (Apache 2.0) tinted at runtime. No emojis anywhere.
 */
public final class Ui {

    private Ui() {}

    // The palette is mutable since v0.49.0: three full themes
    // (Console / Paper / Aurora) write these fields via
    // applyTheme(), and every screen rebuilds from them. The
    // values here are the Aurora defaults.
    public static int BG = 0xFF0B0E13;
    public static int PANEL = 0xFF141922;
    public static int PANEL_HI = 0xFF1C232F;
    public static int BORDER = 0xFF273040;
    public static int TEXT = 0xFFF2F4F8;
    public static int DIM = 0xFF98A2B3;
    public static int ACCENT = 0xFF6C8CFF;
    public static int ACCENT_DARK_TEXT = 0xFF0B0E13;
    public static int ACCENT2 = 0xFF2DD4BF;
    public static int WARN = 0xFFF5A524;
    public static int DANGER = 0xFFF2555A;

    private static int RIPPLE_MAIN = 0x2EFFFFFF;
    private static final int RIPPLE_ON_ACCENT = 0x330B0E13;
    private static boolean LIGHT_BARS = false;

    // ---------- themes (v0.49.0) ----------

    public static final String PREF_THEME = "ui_theme";
    public static final int THEME_CONSOLE = 0;
    public static final int THEME_PAPER = 1;
    public static final int THEME_AURORA = 2;
    public static final String[] THEME_NAMES = {"Console", "Paper", "Aurora"};
    public static final String[] THEME_BLURBS = {
        "Amber on black - the ship-console instrument look",
        "Warm paper and ink - a field journal, light theme",
        "Periwinkle and teal on deep navy - gradient glass"};

    public static int theme(android.content.Context ctx) {
        return ctx.getSharedPreferences(Embed.PREFS, android.content.Context.MODE_PRIVATE)
            .getInt(PREF_THEME, THEME_CONSOLE);
    }

    public static void setTheme(android.content.Context ctx, int t) {
        ctx.getSharedPreferences(Embed.PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putInt(PREF_THEME, t).apply();
        applyTheme(ctx);
    }

    /** Load the chosen theme's palette into the fields above. */
    public static void applyTheme(android.content.Context ctx) {
        switch (theme(ctx)) {
            case THEME_CONSOLE:
                BG = 0xFF0A0703; PANEL = 0xFF171208; PANEL_HI = 0xFF241A0D;
                BORDER = 0xFF3D2E13; TEXT = 0xFFF7EBCE; DIM = 0xFFB3A284;
                ACCENT = 0xFFFFB224; ACCENT_DARK_TEXT = 0xFF1D1305;
                ACCENT2 = 0xFFFF7A29; WARN = 0xFFFF6B4A; DANGER = 0xFFFF5252;
                RIPPLE_MAIN = 0x2EFFFFFF; LIGHT_BARS = false;
                break;
            case THEME_PAPER:
                BG = 0xFFF6F1E7; PANEL = 0xFFFFFDF6; PANEL_HI = 0xFFEFE7D6;
                BORDER = 0xFFD9CDB2; TEXT = 0xFF241E12; DIM = 0xFF7D7462;
                ACCENT = 0xFFA85B12; ACCENT_DARK_TEXT = 0xFFFFF7E8;
                ACCENT2 = 0xFF2E6E5E; WARN = 0xFFC2410C; DANGER = 0xFFB3261E;
                RIPPLE_MAIN = 0x1F000000; LIGHT_BARS = true;
                break;
            default: // THEME_AURORA - the original palette
                BG = 0xFF0B0E13; PANEL = 0xFF141922; PANEL_HI = 0xFF1C232F;
                BORDER = 0xFF273040; TEXT = 0xFFF2F4F8; DIM = 0xFF98A2B3;
                ACCENT = 0xFF6C8CFF; ACCENT_DARK_TEXT = 0xFF0B0E13;
                ACCENT2 = 0xFF2DD4BF; WARN = 0xFFF5A524; DANGER = 0xFFF2555A;
                RIPPLE_MAIN = 0x2EFFFFFF; LIGHT_BARS = false;
                break;
        }
    }

    public static int dp(Activity a, int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }

    /** Theme + status/navigation bar colors for the chosen theme. */
    public static void applyChrome(Activity a) {
        applyTheme(a);
        a.getWindow().setStatusBarColor(BG);
        a.getWindow().setNavigationBarColor(PANEL);
        // Light themes need dark system-bar icons to stay readable.
        android.view.View decor = a.getWindow().getDecorView();
        int flags = decor.getSystemUiVisibility();
        int light = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            | android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        decor.setSystemUiVisibility(LIGHT_BARS ? (flags | light) : (flags & ~light));
    }

    public static GradientDrawable panelBg(int color, int border, int radiusDp, Activity a) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(a, radiusDp));
        if (border != 0) g.setStroke(Math.max(1, dp(a, 1)), border);
        return g;
    }

    /** Panel background with a touch ripple on top. */
    public static Drawable rippleBg(Activity a, int color, int border, int radiusDp, int rippleColor) {
        return new RippleDrawable(ColorStateList.valueOf(rippleColor),
            panelBg(color, border, radiusDp, a), null);
    }

    public static TextView text(Activity a, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setLineSpacing(0, 1.12f);
        return t;
    }

    /** Small all-caps section label. */
    public static TextView sectionLabel(Activity a, String s) {
        TextView t = text(a, s.toUpperCase(java.util.Locale.US), 11, DIM, true);
        t.setLetterSpacing(0.12f);
        return t;
    }

    private static void styleButton(Activity a, Button b, int bg, int fg, boolean bordered) {
        b.setTextColor(fg);
        b.setTextSize(15);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        int ripple = (bg == ACCENT || bg == ACCENT2) ? RIPPLE_ON_ACCENT : RIPPLE_MAIN;
        b.setBackground(rippleBg(a, bg, bordered ? BORDER : 0, 12, ripple));
        b.setPadding(dp(a, 16), dp(a, 12), dp(a, 16), dp(a, 12));
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        b.setElevation(0);
    }

    public static Button button(Activity a, String label, int bg, int fg) {
        Button b = new Button(a);
        b.setText(label);
        styleButton(a, b, bg, fg, bg == PANEL_HI);
        return b;
    }

    public static Button primaryButton(Activity a, String label) {
        return button(a, label, ACCENT, ACCENT_DARK_TEXT);
    }

    public static Button ghostButton(Activity a, String label) {
        return button(a, label, PANEL_HI, TEXT);
    }

    /** Button with a leading icon (icon tinted to the text color). */
    public static Button iconButton(Activity a, String label, int iconRes, int bg, int fg) {
        Button b = button(a, label, bg, fg);
        Drawable d = iconDrawable(a, iconRes, fg);
        b.setCompoundDrawablesWithIntrinsicBounds(d, null, null, null);
        b.setCompoundDrawablePadding(dp(a, 8));
        return b;
    }

    public static Drawable iconDrawable(Activity a, int resId, int tint) {
        Drawable d = a.getResources().getDrawable(resId, a.getTheme()).mutate();
        d.setTint(tint);
        int s = dp(a, 20);
        d.setBounds(0, 0, s, s);
        return d;
    }

    public static ImageView icon(Activity a, int resId, int sizeDp, int tint) {
        ImageView iv = new ImageView(a);
        iv.setImageResource(resId);
        iv.setColorFilter(tint);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(a, sizeDp), dp(a, sizeDp));
        iv.setLayoutParams(lp);
        return iv;
    }

    /** Rounded-square icon badge (tinted glyph on a soft tint of itself). */
    public static FrameLayout badge(Activity a, int resId, int tint) {
        FrameLayout f = new FrameLayout(a);
        int soft = (tint & 0x00FFFFFF) | 0x26000000;
        f.setBackground(panelBg(soft, 0, 12, a));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(a, 44), dp(a, 44));
        f.setLayoutParams(lp);
        ImageView iv = icon(a, resId, 24, tint);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(dp(a, 24), dp(a, 24));
        ilp.gravity = Gravity.CENTER;
        iv.setLayoutParams(ilp);
        f.addView(iv);
        return f;
    }

    /** Brand mark: gradient tile with the vault-lock glyph. */
    public static FrameLayout brandMark(Activity a) {
        FrameLayout f = new FrameLayout(a);
        GradientDrawable g = new GradientDrawable(
            GradientDrawable.Orientation.TL_BR, new int[]{ACCENT, ACCENT2});
        g.setCornerRadius(dp(a, 11));
        f.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(a, 40), dp(a, 40));
        f.setLayoutParams(lp);
        ImageView iv = icon(a, R.drawable.ic_lock, 22, 0xFFFFFFFF);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(dp(a, 22), dp(a, 22));
        ilp.gravity = Gravity.CENTER;
        iv.setLayoutParams(ilp);
        f.addView(iv);
        return f;
    }

    /** Capture-grid tile: badge + label, whole tile tappable with ripple. */
    public static LinearLayout tile(Activity a, int iconRes, String label, int tint,
                                    View.OnClickListener click) {
        LinearLayout t = new LinearLayout(a);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setBackground(rippleBg(a, PANEL_HI, BORDER, 14, RIPPLE_MAIN));
        t.setPadding(dp(a, 6), dp(a, 12), dp(a, 6), dp(a, 10));
        t.setClickable(true);
        t.setFocusable(true);
        t.addView(badge(a, iconRes, tint));
        TextView l = text(a, label, 13, TEXT, true);
        l.setLayoutParams(matchWrap(7, a));
        l.setGravity(Gravity.CENTER);
        t.addView(l);
        t.setOnClickListener(click);
        return t;
    }

    /** Bottom-tab item: icon over label, accent when selected. */
    public static LinearLayout tabItem(Activity a, int iconRes, String label, boolean selected,
                                       View.OnClickListener click) {
        LinearLayout t = new LinearLayout(a);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setPadding(dp(a, 4), dp(a, 8), dp(a, 4), dp(a, 8));
        t.setClickable(true);
        t.setFocusable(true);
        int tint = selected ? ACCENT : DIM;
        if (selected) t.setBackground(rippleBg(a, (ACCENT & 0x00FFFFFF) | 0x1A000000, 0, 12, RIPPLE_MAIN));
        else t.setBackground(rippleBg(a, PANEL, 0, 12, RIPPLE_MAIN));
        t.addView(icon(a, iconRes, 22, tint));
        TextView l = text(a, label, 11, tint, selected);
        l.setLayoutParams(matchWrap(3, a));
        l.setGravity(Gravity.CENTER);
        t.addView(l);
        t.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(a, 4), 0, dp(a, 4), 0);
        t.setLayoutParams(lp);
        return t;
    }

    public static Button chip(Activity a, String label, boolean selected) {
        Button b = new Button(a);
        b.setText(label);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
        b.setTextColor(selected ? ACCENT_DARK_TEXT : TEXT);
        b.setBackground(rippleBg(a, selected ? ACCENT : PANEL_HI, selected ? 0 : BORDER, 999,
            selected ? RIPPLE_ON_ACCENT : RIPPLE_MAIN));
        b.setPadding(dp(a, 14), dp(a, 6), dp(a, 14), dp(a, 6));
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        b.setElevation(0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dp(a, 8), 0);
        b.setLayoutParams(lp);
        return b;
    }

    /** Filled text field with an accent focus ring. */
    public static EditText input(Activity a, String hint) {
        EditText e = new EditText(a);
        e.setHint(hint);
        e.setTextColor(TEXT);
        e.setHintTextColor(DIM);
        e.setTextSize(15);
        GradientDrawable normal = panelBg(PANEL, BORDER, 12, a);
        GradientDrawable focused = panelBg(PANEL, ACCENT, 12, a);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, focused);
        bg.addState(new int[]{}, normal);
        e.setBackground(bg);
        e.setPadding(dp(a, 14), dp(a, 12), dp(a, 14), dp(a, 12));
        return e;
    }

    public static LinearLayout.LayoutParams matchWrap(int topMarginDp, Activity a) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        if (topMarginDp > 0) lp.setMargins(0, dp(a, topMarginDp), 0, 0);
        return lp;
    }

    public static LinearLayout.LayoutParams weight1(int leftMarginDp, Activity a) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        if (leftMarginDp > 0) lp.setMargins(dp(a, leftMarginDp), 0, 0, 0);
        return lp;
    }

    public static View divider(Activity a) {
        View v = new View(a);
        v.setBackgroundColor(BORDER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(a, 1)));
        lp.setMargins(0, dp(a, 8), 0, dp(a, 8));
        v.setLayoutParams(lp);
        return v;
    }

    public static String typeLabel(String type) {
        if ("VOICE".equals(type)) return "Voice note";
        if ("PHOTO".equals(type)) return "Photo";
        if ("SCAN".equals(type)) return "Scan";
        if ("DOCUMENT".equals(type)) return "Document";
        return "Note";
    }

    public static String dateLine(long epoch) {
        return Extract.formatDate(epoch);
    }

    public static LinearLayout card(Activity a) {
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(panelBg(PANEL, BORDER, 16, a));
        c.setPadding(dp(a, 16), dp(a, 14), dp(a, 16), dp(a, 14));
        c.setLayoutParams(matchWrap(10, a));
        c.setGravity(Gravity.START);
        return c;
    }

    /** Card with touch ripple (for tappable list items). */
    public static LinearLayout pressCard(Activity a) {
        LinearLayout c = card(a);
        c.setBackground(rippleBg(a, PANEL, BORDER, 16, RIPPLE_MAIN));
        c.setClickable(true);
        c.setFocusable(true);
        return c;
    }

    /** Centered empty-state block with a soft icon badge. */
    public static LinearLayout emptyState(Activity a, int iconRes, String title, String hint) {
        LinearLayout e = new LinearLayout(a);
        e.setOrientation(LinearLayout.VERTICAL);
        e.setGravity(Gravity.CENTER_HORIZONTAL);
        e.setPadding(dp(a, 24), dp(a, 28), dp(a, 24), dp(a, 8));
        e.setLayoutParams(matchWrap(8, a));
        FrameLayout b = badge(a, iconRes, DIM);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(a, 56), dp(a, 56));
        b.setLayoutParams(blp);
        e.addView(b);
        TextView t = text(a, title, 15, TEXT, true);
        t.setLayoutParams(matchWrap(12, a));
        t.setGravity(Gravity.CENTER);
        e.addView(t);
        TextView h = text(a, hint, 13, DIM, false);
        h.setLayoutParams(matchWrap(4, a));
        h.setGravity(Gravity.CENTER);
        e.addView(h);
        return e;
    }
}
