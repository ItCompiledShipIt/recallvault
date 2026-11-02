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

    // ---------- Sanctuary (v0.58.0) ----------
    // The warm-organic design language chris picked: oat
    // linen canvas, white tactile cards, forest sage,
    // terracotta, Plus Jakarta Sans. SANCTUARY switches the
    // layout branches in MainActivity and the component
    // shapes below; the extra tokens are only meaningful
    // under that theme.
    public static boolean SANCTUARY = false;
    public static int MINT = 0xFFBCEDDC;       // primary-fixed
    public static int MINT_TEXT = 0xFF204F42;
    public static int TERRA = 0xFFD17D58;
    public static int TERRA_BG = 0xFFFFDBCD;
    public static int TERRA_TEXT = 0xFF773617;
    public static int SAGE_BG = 0xFFD0E9CC;
    public static int SAGE_TEXT = 0xFF374C36;
    public static int FOREST = 0xFF154539;
    private static int CARD_R = 8;
    private static int BTN_R = 8;

    // Plus Jakarta Sans (OFL), variable font in assets.
    private static Typeface F_REG, F_SEMI, F_BOLD;

    private static void loadFont(android.content.Context ctx) {
        if (F_REG != null) return;
        try {
            Typeface base = Typeface.createFromAsset(ctx.getAssets(), "fonts/PlusJakartaSans.ttf");
            F_REG = base;
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                F_SEMI = Typeface.create(base, 600, false);
                F_BOLD = Typeface.create(base, 700, false);
            } else {
                F_SEMI = Typeface.create(base, Typeface.BOLD);
                F_BOLD = F_SEMI;
            }
        } catch (Exception e) {
            F_REG = Typeface.DEFAULT;
            F_SEMI = Typeface.DEFAULT_BOLD;
            F_BOLD = Typeface.DEFAULT_BOLD;
        }
    }

    /** The face for body text under the active theme. */
    static Typeface face(android.content.Context ctx, boolean bold) {
        if (!SANCTUARY) return bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT;
        loadFont(ctx);
        return bold ? F_SEMI : F_REG;
    }

    /** Display face (headlines) under the active theme. */
    static Typeface faceDisplay(android.content.Context ctx) {
        if (!SANCTUARY) return Typeface.DEFAULT_BOLD;
        loadFont(ctx);
        return F_BOLD;
    }

    /** The design's mint pill: lock dot + label, forest text. */
    public static TextView pill(Activity a, String label) {
        TextView t = text(a, label, 11, MINT_TEXT, true);
        t.setBackground(panelBg(MINT, 0, 999, a));
        t.setPadding(dp(a, 10), dp(a, 4), dp(a, 10), dp(a, 4));
        return t;
    }

    /** Soft tinted category pill (Sanctuary card headers). */
    public static TextView catPill(Activity a, String label, int bg, int fg) {
        TextView t = text(a, label, 11, fg, true);
        t.setBackground(panelBg(bg, 0, 999, a));
        t.setPadding(dp(a, 10), dp(a, 4), dp(a, 10), dp(a, 4));
        return t;
    }

    /** The header avatar: a forest disc with a person glyph;
     *  opens Settings, the design's profile entry. */
    public static FrameLayout avatarDisc(Activity a, View.OnClickListener click) {
        FrameLayout f = new FrameLayout(a);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(FOREST);
        f.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(a, 40), dp(a, 40));
        f.setLayoutParams(lp);
        ImageView iv = new ImageView(a);
        iv.setImageResource(R.drawable.ic_person);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(dp(a, 22), dp(a, 22));
        ilp.gravity = Gravity.CENTER;
        iv.setLayoutParams(ilp);
        f.addView(iv);
        f.setClickable(true);
        f.setFocusable(true);
        f.setOnClickListener(click);
        return f;
    }

    // ---------- themes (v0.49.0) ----------

    public static final String PREF_THEME = "ui_theme";
    public static final int THEME_CONSOLE = 0;
    public static final int THEME_SANCTUARY = 3;

    /**
     * Sanctuary is the only theme (v0.63.0, chris's word:
     * the Console, Paper and Aurora themes are removed).
     * The constant stays so older call sites keep meaning
     * what they always meant.
     */
    public static int theme(android.content.Context ctx) {
        return THEME_SANCTUARY;
    }

    /** Load the Sanctuary palette into the fields above. */
    public static void applyTheme(android.content.Context ctx) {
        SANCTUARY = true;
        CARD_R = 20;
        BTN_R = 14;
        // DESIGN.md tokens: oat linen canvas, white cards,
        // espresso ink, umber secondary, forest sage
        // primary, terracotta warmth, stone lines.
        BG = 0xFFF9F6F0; PANEL = 0xFFFFFFFF; PANEL_HI = 0xFFF0EEE8;
        BORDER = 0xFFE7E2D8; TEXT = 0xFF221F1D; DIM = 0xFF69645E;
        ACCENT = 0xFF2F5D50; ACCENT_DARK_TEXT = 0xFFFFFFFF;
        ACCENT2 = 0xFFD17D58; WARN = 0xFF924B2A; DANGER = 0xFFBA1A1A;
        RIPPLE_MAIN = 0x1F000000; LIGHT_BARS = true;
    }

    public static int dp(Activity a, int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }

    /** Theme + status/navigation bar colors for the chosen theme. */
    /** Dense mode (v0.52.0): compact card padding, set from the
     *  ui_dense pref on every activity start. */
    public static boolean DENSE = false;

    public static void applyChrome(Activity a) {
        applyTheme(a);
        DENSE = a.getSharedPreferences(Embed.PREFS, android.content.Context.MODE_PRIVATE)
            .getBoolean("ui_dense", false);
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

    // ---------- Stitch layout kit (v0.50.0) ----------
    // The instrument-register pieces from the Stitch design:
    // rectangular tags, screen titles, stat tiles, section
    // heads with a right-hand readout.

    /** Rectangular tag: style 0 = filled accent, 1 = panel with
     *  colored text, 2 = panel with dim text. */
    public static TextView tag(Activity ctx, String label, int style, int color) {
        TextView t = text(ctx, label, 10, style == 0 ? ACCENT_DARK_TEXT : color, true);
        if (SANCTUARY) {
            // Soft pills in the Sanctuary register.
            t.setLetterSpacing(0.02f);
            t.setTypeface(face(ctx, true));
            int bg = style == 0 ? ACCENT : PANEL_HI;
            t.setBackground(panelBg(bg, 0, 999, ctx));
            t.setPadding(dp(ctx, 10), dp(ctx, 4), dp(ctx, 10), dp(ctx, 4));
            return t;
        }
        t.setLetterSpacing(0.08f);
        t.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        int bg = style == 0 ? ACCENT : PANEL_HI;
        t.setBackground(panelBg(bg, style == 0 ? 0 : BORDER, 4, ctx));
        t.setPadding(dp(ctx, 8), dp(ctx, 3), dp(ctx, 8), dp(ctx, 3));
        return t;
    }

    /** Big screen title: amber square + caps title + right tag. */
    public static LinearLayout screenTitle(Activity ctx, String title, String rightTag) {
        if (SANCTUARY) {
            // The design's headline: plain title case, Jakarta
            // bold, with the privacy pill on the right.
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            String pretty = title.isEmpty() ? title
                : title.substring(0, 1).toUpperCase(java.util.Locale.US)
                    + title.substring(1).toLowerCase(java.util.Locale.US);
            TextView t = text(ctx, pretty, 24, TEXT, true);
            t.setTypeface(faceDisplay(ctx));
            row.addView(t);
            if (rightTag != null) {
                View sp = new View(ctx);
                sp.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
                row.addView(sp);
                row.addView(pill(ctx, "On-device only • Private"));
            }
            return row;
        }
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        View sq = new View(ctx);
        sq.setBackgroundColor(ACCENT);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(ctx, 9), dp(ctx, 9));
        sq.setLayoutParams(slp);
        row.addView(sq);
        TextView t = text(ctx, title, 20, TEXT, true);
        t.setLetterSpacing(0.05f);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.setMargins(dp(ctx, 10), 0, 0, 0);
        t.setLayoutParams(tlp);
        row.addView(t);
        if (rightTag != null) {
            View sp = new View(ctx);
            sp.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
            row.addView(sp);
            row.addView(tag(ctx, rightTag, 1, ACCENT2));
        }
        return row;
    }

    /** Stat tile: small caps label over a big value, in a panel. */
    public static LinearLayout statTile(Activity ctx, String label, String value, int valueColor) {
        LinearLayout tile = new LinearLayout(ctx);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setBackground(panelBg(PANEL, BORDER, 10, ctx));
        tile.setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 10), dp(ctx, 8));
        TextView l = text(ctx, label, 9, DIM, true);
        l.setLetterSpacing(0.1f);
        tile.addView(l);
        TextView v = text(ctx, value, 18, valueColor, true);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        vlp.setMargins(0, dp(ctx, 2), 0, 0);
        v.setLayoutParams(vlp);
        tile.addView(v);
        return tile;
    }

    /** Section header with a right-hand readout. */
    public static LinearLayout sectionHead(Activity ctx, String left, String right) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.addView(sectionLabel(ctx, left));
        if (right != null) {
            View sp = new View(ctx);
            sp.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
            row.addView(sp);
            TextView r = text(ctx, right, 10, DIM, true);
            r.setLetterSpacing(0.08f);
            row.addView(r);
        }
        return row;
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
        if (SANCTUARY) {
            t.setTypeface(face(a, bold));
            t.setLineSpacing(0, 1.25f);
        } else {
            if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            t.setLineSpacing(0, 1.12f);
        }
        return t;
    }

    /** Small all-caps section label. */
    public static TextView sectionLabel(Activity a, String s) {
        TextView t = text(a, s.toUpperCase(java.util.Locale.US), 11, DIM, true);
        if (SANCTUARY) {
            // The Sanctuary eyebrows: Jakarta semibold, gentle
            // positive tracking - labels, not telemetry.
            t.setLetterSpacing(0.04f);
            t.setTypeface(face(a, true));
        } else {
            t.setLetterSpacing(0.12f);
            // Register match (v0.51.0): the design's micro-labels are
            // monospace - telemetry, not prose.
            t.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        }
        return t;
    }

    private static void styleButton(Activity a, Button b, int bg, int fg, boolean bordered) {
        b.setTextColor(fg);
        b.setTextSize(15);
        b.setAllCaps(false);
        if (SANCTUARY) b.setTypeface(face(a, true));
        else b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        int ripple = (bg == ACCENT || bg == ACCENT2) ? RIPPLE_ON_ACCENT : RIPPLE_MAIN;
        b.setBackground(rippleBg(a, bg, bordered && !SANCTUARY ? BORDER : 0, BTN_R, ripple));
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
    /** Header mark: Clio's glyph on a transparent ground -
     *  the C/vault door (chris's image, brightened cutout)
     *  sits directly on the header, no tile behind it. */
    public static FrameLayout brandMark(Activity a) {
        FrameLayout f = new FrameLayout(a);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(a, 44), dp(a, 44));
        f.setLayoutParams(lp);
        ImageView iv = new ImageView(a);
        iv.setImageResource(R.drawable.clio_mark);
        iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        iv.setLayoutParams(new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
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
        if (SANCTUARY) b.setTypeface(face(a, selected));
        else b.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
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
        // Tighter radius (v0.51.0): the design's panels are
        // near-rectangular registers, not soft cards.
        // Sanctuary (v0.58.0): soft tactile cards - white,
        // borderless, generously rounded, a whisper of lift.
        c.setBackground(panelBg(PANEL, SANCTUARY ? 0 : BORDER, CARD_R, a));
        if (SANCTUARY) c.setElevation(dp(a, 2));
        c.setPadding(dp(a, SANCTUARY ? 18 : 14), dp(a, DENSE ? 7 : 12), dp(a, SANCTUARY ? 18 : 14), dp(a, DENSE ? 7 : 12));
        c.setLayoutParams(matchWrap(10, a));
        c.setGravity(Gravity.START);
        return c;
    }

    /** Card with touch ripple (for tappable list items). */
    public static LinearLayout pressCard(Activity a) {
        LinearLayout c = card(a);
        c.setBackground(rippleBg(a, PANEL, SANCTUARY ? 0 : BORDER, CARD_R, RIPPLE_MAIN));
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
