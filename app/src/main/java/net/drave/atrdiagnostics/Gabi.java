package net.drave.atrdiagnostics;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.*;
import android.graphics.drawable.*;
import android.text.TextUtils;
import android.view.*;
import android.view.animation.PathInterpolator;
import android.widget.*;
import java.util.*;

/** Gabi design system: palettes, type, shapes and the small view kit every screen is built from. */
public final class Gabi {
    private Gabi() {}

    public static final class Palette {
        public final boolean night;
        public final int bg, glass, glassStrong, stroke, ink, inkSoft, muted, accent, onAccent, accentSoft, danger, dangerSoft, ok, okSoft, chip, scrim;
        Palette(boolean night, int bg, int glass, int glassStrong, int stroke, int ink, int inkSoft, int muted, int accent, int onAccent, int accentSoft, int danger, int dangerSoft, int ok, int okSoft, int chip, int scrim) {
            this.night = night; this.bg = bg; this.glass = glass; this.glassStrong = glassStrong; this.stroke = stroke; this.ink = ink; this.inkSoft = inkSoft; this.muted = muted;
            this.accent = accent; this.onAccent = onAccent; this.accentSoft = accentSoft; this.danger = danger; this.dangerSoft = dangerSoft; this.ok = ok; this.okSoft = okSoft; this.chip = chip; this.scrim = scrim;
        }
    }

    public static final Palette NIGHT = new Palette(true,
        0xFF0E151C, 0xEB151E28, 0xF7121A22, 0x17FFFFFF, 0xFFF2F4F6, 0xFFC9D3DC, 0xFF8494A4,
        0xFFFFB020, 0xFF1A1203, 0x26FFB020, 0xFFE5484D, 0x33E5484D, 0xFF3DDC97, 0x263DDC97, 0x12FFFFFF, 0xE60A0F14);
    public static final Palette DAY = new Palette(false,
        0xFFEEEDE7, 0xF5FFFFFF, 0xFCFFFFFF, 0x14101820, 0xFF121A21, 0xFF33414F, 0xFF6B7885,
        0xFFF5A300, 0xFF1A1203, 0x2EF5A300, 0xFFD92D20, 0x1FD92D20, 0xFF12A150, 0x2212A150, 0x0D101820, 0xF0F4F5F1);

    public static final String THEME_AUTO = "auto", THEME_DAY = "day", THEME_NIGHT = "night";
    public static String themeSetting(Context c) { return c.getSharedPreferences("ui", 0).getString("theme", THEME_AUTO); }
    public static void setThemeSetting(Context c, String v) { c.getSharedPreferences("ui", 0).edit().putString("theme", v).apply(); }

    /** Auto follows the sun at the rider's position, or the phone's dark mode before the first fix. */
    public static Palette resolve(Context c, double lat, double lon, boolean haveLocation) {
        String mode = themeSetting(c);
        if (THEME_DAY.equals(mode)) return DAY;
        if (THEME_NIGHT.equals(mode)) return NIGHT;
        if (haveLocation) return SunClock.isNight(lat, lon, System.currentTimeMillis()) ? NIGHT : DAY;
        // No fix yet: the last place the rider was (or looked at the map) has the same sky, so the look doesn't flip on the first fix.
        android.content.SharedPreferences u = c.getSharedPreferences("ui", 0);
        String at = u.contains("fix_lat") ? "fix" : u.contains("cam_lat") ? "cam" : null;
        if (at != null) return SunClock.isNight(u.getFloat(at + "_lat", 0), u.getFloat(at + "_lon", 0), System.currentTimeMillis()) ? NIGHT : DAY;
        int ui = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return ui == Configuration.UI_MODE_NIGHT_NO ? DAY : NIGHT;
    }

    // ---------------------------------------------------------------- type
    private static final Map<Integer, Typeface> FACES = new HashMap<>();
    public static synchronized Typeface font(Context c, int weight) {
        Typeface t = FACES.get(weight);
        if (t != null) return t;
        try { t = new Typeface.Builder(c.getAssets(), "fonts/Figtree.ttf").setFontVariationSettings("'wght' " + weight).build(); }
        catch (Exception e) { t = null; }
        if (t == null) t = Typeface.create(Typeface.SANS_SERIF, weight, false);
        FACES.put(weight, t);
        return t;
    }

    public static final android.view.animation.Interpolator EASE_OUT = new PathInterpolator(0.16f, 1f, 0.3f, 1f);
    public static final android.view.animation.Interpolator EASE_IN_OUT = new PathInterpolator(0.65f, 0f, 0.35f, 1f);

    /** Draw behind the system bars on every supported Android version. */
    public static void edgeToEdge(android.view.Window w) {
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(w, false); w.setStatusBarColor(0); w.setNavigationBarColor(0);
        if (android.os.Build.VERSION.SDK_INT >= 29) w.setNavigationBarContrastEnforced(false);
    }
    public static void barIcons(android.view.Window w, Palette p) {
        androidx.core.view.WindowInsetsControllerCompat c = androidx.core.view.WindowCompat.getInsetsController(w, w.getDecorView());
        c.setAppearanceLightStatusBars(!p.night); c.setAppearanceLightNavigationBars(!p.night);
    }
    /** Listen for system bar (and optionally keyboard) insets as left, top, right, bottom, ime. */
    public interface InsetSink { void apply(int left, int top, int right, int bottom, int ime); }
    public static void insets(View v, InsetSink sink) {
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(v, (view, in) -> {
            androidx.core.graphics.Insets b = in.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());
            androidx.core.graphics.Insets ime = in.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime());
            sink.apply(b.left, b.top, b.right, b.bottom, ime.bottom); return in;
        });
    }

    public static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    // ---------------------------------------------------------------- theming
    /** Every themed view registers how it paints itself; switching palette replays the list. */
    public static final class Themer {
        public interface Paint { void apply(Palette p); }
        private final List<Paint> list = new ArrayList<>();
        public Palette palette;
        public Themer(Palette p) { palette = p; }
        public void add(Paint p) { list.add(p); p.apply(palette); }
        public boolean set(Palette p) { if (p == palette) return false; palette = p; for (Paint x : list) x.apply(p); return true; }
    }

    public static GradientDrawable round(int color, float radius) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(radius); return d;
    }
    public static GradientDrawable round(int color, float radius, int stroke, int strokeWidth) {
        GradientDrawable d = round(color, radius); if (strokeWidth > 0) d.setStroke(strokeWidth, stroke); return d;
    }
    public static GradientDrawable rounded(int color, float tl, float tr, float br, float bl) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadii(new float[]{tl, tl, tr, tr, br, br, bl, bl}); return d;
    }

    // ---------------------------------------------------------------- view kit
    public static final class Kit {
        public final Context c; public final Themer t;
        public Kit(Context c, Themer t) { this.c = c; this.t = t; }
        public int dp(float v) { return Gabi.dp(c, v); }

        public TextView text(CharSequence s, float sp, int weight, Role role) {
            TextView v = new TextView(c); v.setText(s); v.setTextSize(sp); v.setTypeface(font(c, weight)); v.setIncludeFontPadding(false);
            v.setLineSpacing(0, 1.12f);
            t.add(p -> v.setTextColor(role.color(p)));
            return v;
        }
        public TextView num(CharSequence s, float sp, int weight, Role role) { TextView v = text(s, sp, weight, role); v.setFontFeatureSettings("tnum"); return v; }
        public TextView single(TextView v) { v.setSingleLine(); v.setEllipsize(TextUtils.TruncateAt.END); return v; }

        public ImageView icon(int type, float size, Role role) {
            ImageView v = new ImageView(c); IconDrawable d = new IconDrawable(type, 0); v.setImageDrawable(d);
            t.add(p -> d.setColor(role.color(p)));
            v.setLayoutParams(new LinearLayout.LayoutParams(dp(size), dp(size)));
            return v;
        }

        /** Floating translucent panel. Real backdrop blur is not available for map surfaces, so the panel is a dense tint with a hairline edge. */
        public void glass(View v, float radius) {
            float r = dp(radius);
            t.add(p -> v.setBackground(round(p.glass, r, p.stroke, dp(1))));
            v.setElevation(dp(10)); v.setOutlineSpotShadowColor(0x66000000); v.setOutlineAmbientShadowColor(0x33000000);
        }
        public void fill(View v, float radius, Role role) { float r = dp(radius); t.add(p -> v.setBackground(round(role.color(p), r))); }

        public LinearLayout row(int gap) { LinearLayout l = new LinearLayout(c); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); if (gap > 0) l.setDividerDrawable(space(gap, 1)); l.setShowDividers(gap > 0 ? LinearLayout.SHOW_DIVIDER_MIDDLE : 0); return l; }
        public LinearLayout column(int gap) { LinearLayout l = new LinearLayout(c); l.setOrientation(LinearLayout.VERTICAL); if (gap > 0) { l.setDividerDrawable(space(1, gap)); l.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE); } return l; }
        private Drawable space(int w, int h) { GradientDrawable d = new GradientDrawable(); d.setSize(dp(w), dp(h)); d.setColor(0); return d; }

        public TextView primary(String label, Runnable action) {
            TextView b = text(label, 16, 800, Role.ON_ACCENT); b.setGravity(Gravity.CENTER); b.setMinHeight(dp(56));
            t.add(p -> b.setBackground(ripple(p, round(p.accent, dp(28)), dp(28))));
            press(b, action); return b;
        }
        public TextView ghost(String label, Runnable action) {
            TextView b = text(label, 15, 700, Role.INK); b.setGravity(Gravity.CENTER); b.setMinHeight(dp(52)); b.setPadding(dp(18), 0, dp(18), 0);
            t.add(p -> b.setBackground(ripple(p, round(p.chip, dp(26)), dp(26))));
            press(b, action); return b;
        }
        public FrameLayout circle(int iconType, float size, Role bg, Role fg, Runnable action) {
            FrameLayout f = new FrameLayout(c);
            ImageView i = icon(iconType, size * 0.42f, fg);
            f.addView(i, new FrameLayout.LayoutParams(dp(size * 0.42f), dp(size * 0.42f), Gravity.CENTER));
            if (bg == Role.GLASS) glass(f, size / 2); else fill(f, size / 2, bg);
            f.setLayoutParams(new LinearLayout.LayoutParams(dp(size), dp(size)));
            press(f, action); return f;
        }
        public TextView chip(String label, Role bg, Role fg) {
            TextView v = text(label, 12, 700, fg); v.setPadding(dp(10), dp(6), dp(10), dp(6)); v.setGravity(Gravity.CENTER_VERTICAL);
            t.add(p -> v.setBackground(round(bg.color(p), dp(99))));
            return v;
        }
        public View dot(Role role) { View v = new View(c); t.add(p -> v.setBackground(round(role.color(p), dp(4)))); v.setLayoutParams(new LinearLayout.LayoutParams(dp(8), dp(8))); return v; }
        public View grab() {
            View g = new View(c); fill(g, 2, Role.GRAB);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(38), dp(4)); lp.gravity = Gravity.CENTER_HORIZONTAL; lp.bottomMargin = dp(12); g.setLayoutParams(lp); return g;
        }
        /** Small ⓘ that explains something in plain words when tapped, instead of putting the detail on screen. */
        public FrameLayout info(String explanation) {
            FrameLayout f = new FrameLayout(c); ImageView i = icon(IconDrawable.INFO, 18, Role.MUTED);
            f.addView(i, new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER));
            f.setLayoutParams(new LinearLayout.LayoutParams(dp(36), dp(36)));
            f.setContentDescription("More information");
            press(f, () -> tip(f, explanation)); return f;
        }
        /** Glass bubble under (or above) the anchor; any tap closes it. */
        public void tip(View anchor, String text) {
            ViewGroup root = (ViewGroup)anchor.getRootView();
            FrameLayout scrim = new FrameLayout(c); scrim.setClickable(true); scrim.setElevation(dp(40));
            TextView bubble = text(text, 14, 550, Role.INK); bubble.setLineSpacing(0, 1.25f); bubble.setPadding(dp(16), dp(14), dp(16), dp(14));
            t.add(p -> bubble.setBackground(round(p.night ? 0xFF1E2935 : 0xFFFFFFFF, dp(18), p.stroke, dp(1)))); bubble.setElevation(dp(16));
            int width = Math.min(root.getWidth() - dp(32), dp(320));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(width, -2);
            int[] at = new int[2]; anchor.getLocationInWindow(at);
            lp.leftMargin = Math.max(dp(16), Math.min(at[0] + anchor.getWidth() / 2 - width / 2, root.getWidth() - width - dp(16)));
            scrim.addView(bubble, lp);
            root.addView(scrim, new ViewGroup.LayoutParams(-1, -1));
            bubble.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED);
            boolean below = at[1] + anchor.getHeight() + bubble.getMeasuredHeight() + dp(24) < root.getHeight();
            lp.topMargin = below ? at[1] + anchor.getHeight() + dp(4) : Math.max(dp(24), at[1] - bubble.getMeasuredHeight() - dp(4));
            bubble.setAlpha(0f); bubble.setScaleX(0.92f); bubble.setScaleY(0.92f); bubble.setPivotY(below ? 0 : bubble.getMeasuredHeight());
            bubble.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(EASE_OUT).start();
            scrim.setOnClickListener(v -> bubble.animate().alpha(0f).setDuration(140).withEndAction(() -> root.removeView(scrim)).start());
        }
        public TextView label(String s) { TextView v = text(s.toUpperCase(Locale.US), 11.5f, 700, Role.MUTED); v.setLetterSpacing(0.1f); return v; }

        /** Icon bubble + title/subtitle + trailing text, the list row used everywhere. */
        public LinearLayout listRow(int iconType, CharSequence title, CharSequence sub, CharSequence trail, Runnable action) {
            LinearLayout r = row(12); r.setPadding(0, dp(9), 0, dp(9)); r.setMinimumHeight(dp(56));
            FrameLayout bub = new FrameLayout(c); fill(bub, 12, Role.CHIP);
            ImageView i = icon(iconType, 18, Role.MUTED); bub.addView(i, new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER));
            r.addView(bub, new LinearLayout.LayoutParams(dp(38), dp(38)));
            LinearLayout col = column(2);
            TextView a = single(text(title, 15, 600, Role.INK)); col.addView(a);
            if (sub != null) col.addView(single(text(sub, 12, 500, Role.MUTED)));
            r.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
            if (trail != null) r.addView(num(trail, 12.5f, 600, Role.MUTED));
            if (action != null) press(r, action);
            return r;
        }

        /** Two or three options, one selected; the selection slides. */
        public LinearLayout segmented(String[] options, int selected, java.util.function.IntConsumer pick) {
            LinearLayout r = row(4); r.setPadding(dp(4), dp(4), dp(4), dp(4)); fill(r, 16, Role.CHIP);
            TextView[] views = new TextView[options.length]; int[] sel = {selected};
            for (int i = 0; i < options.length; i++) {
                final int n = i; TextView v = text(options[i], 13.5f, 700, Role.INK_SOFT); v.setGravity(Gravity.CENTER); v.setMinHeight(dp(42)); views[i] = v;
                r.addView(v, new LinearLayout.LayoutParams(0, -2, 1));
                press(v, () -> { sel[0] = n; paintSeg(views, n, t.palette); pick.accept(n); });
            }
            t.add(p -> paintSeg(views, sel[0], p));
            return r;
        }
        private void paintSeg(TextView[] views, int selected, Palette p) {
            for (int i = 0; i < views.length; i++) {
                boolean on = i == selected;
                views[i].setBackground(on ? round(p.night ? 0x24FFFFFF : 0xFFFFFFFF, dp(12)) : null);
                views[i].setTextColor(on ? p.ink : p.muted);
                if (on && !p.night) views[i].setElevation(dp(1)); else views[i].setElevation(0);
            }
        }

        /** Slider with an accent fill and a white thumb. */
        public SeekBar slider(int max, int value) {
            SeekBar s = new SeekBar(c); s.setMax(max); s.setProgress(value); s.setSplitTrack(false); s.setPadding(dp(10), dp(10), dp(10), dp(10));
            t.add(p -> {
                GradientDrawable track = round(p.chip, dp(3)); GradientDrawable fill = round(p.accent, dp(3));
                LayerDrawable layers = new LayerDrawable(new Drawable[]{track, new ClipDrawable(fill, Gravity.START, ClipDrawable.HORIZONTAL)});
                layers.setId(0, android.R.id.background); layers.setId(1, android.R.id.progress);
                layers.setLayerHeight(0, dp(5)); layers.setLayerHeight(1, dp(5)); layers.setLayerGravity(0, Gravity.CENTER_VERTICAL); layers.setLayerGravity(1, Gravity.CENTER_VERTICAL);
                s.setProgressDrawable(layers);
                GradientDrawable thumb = new GradientDrawable(); thumb.setShape(GradientDrawable.OVAL); thumb.setColor(0xFFFFFFFF); thumb.setSize(dp(20), dp(20)); thumb.setStroke(dp(1), 0x33000000);
                s.setThumb(thumb);
            });
            return s;
        }
    }

    public enum Role {
        INK, INK_SOFT, MUTED, ACCENT, ON_ACCENT, DANGER, OK, CHIP, GLASS, GRAB, BG, ACCENT_SOFT, OK_SOFT, DANGER_SOFT, WHITE;
        public int color(Palette p) {
            switch (this) {
                case INK: return p.ink; case INK_SOFT: return p.inkSoft; case MUTED: return p.muted; case ACCENT: return p.accent; case ON_ACCENT: return p.onAccent;
                case DANGER: return p.danger; case OK: return p.ok; case CHIP: return p.chip; case GLASS: return p.glass; case GRAB: return p.night ? 0x38FFFFFF : 0x2E101820;
                case BG: return p.bg; case ACCENT_SOFT: return p.accentSoft; case OK_SOFT: return p.okSoft; case DANGER_SOFT: return p.dangerSoft; default: return 0xFFFFFFFF;
            }
        }
    }

    public static RippleDrawable ripple(Palette p, Drawable content, float radius) {
        return new RippleDrawable(ColorStateList.valueOf(p.night ? 0x33FFFFFF : 0x22000000), content, round(0xFFFFFFFF, radius));
    }

    /** Springy press feedback with a light haptic tick; the action runs on release. */
    public static void press(View v, Runnable action) {
        v.setClickable(true); v.setFocusable(true);
        v.setOnTouchListener((view, e) -> {
            if (!view.isEnabled()) return false;
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).setInterpolator(EASE_OUT).start();
            else if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL)
                view.animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(new android.view.animation.OvershootInterpolator(2.2f)).start();
            return false;
        });
        if (action != null) v.setOnClickListener(x -> { x.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); action.run(); });
    }

    /** Fade and lift a view in (or out) with the house easing. */
    public static void show(View v, boolean visible) { show(v, visible, 0); }
    public static void show(View v, boolean visible, float lift) {
        if (visible) {
            if (v.getVisibility() == View.VISIBLE && v.getAlpha() == 1f) return;
            v.animate().cancel();
            if (v.getVisibility() != View.VISIBLE) { v.setAlpha(0f); v.setTranslationY(lift); v.setVisibility(View.VISIBLE); }
            v.animate().alpha(1f).translationY(0).setDuration(380).setInterpolator(EASE_OUT).withEndAction(null).start();
        } else {
            if (v.getVisibility() != View.VISIBLE) return;
            v.animate().cancel();
            v.animate().alpha(0f).translationY(lift).setDuration(200).setInterpolator(EASE_IN_OUT).withEndAction(() -> v.setVisibility(View.GONE)).start();
        }
    }

    /** Short horizontal shake for a refused action. */
    public static void shake(View v) {
        android.animation.ObjectAnimator a = android.animation.ObjectAnimator.ofFloat(v, View.TRANSLATION_X, 0, 14, -12, 9, -6, 3, 0);
        a.setDuration(420); a.start(); v.performHapticFeedback(HapticFeedbackConstants.REJECT);
    }

    public static String distance(double meters) {
        if (meters < 1000) return Math.max(0, Math.round(meters / 10) * 10) + " m";
        return String.format(Locale.US, meters < 10000 ? "%.1f km" : "%.0f km", meters / 1000);
    }
    public static String minutes(double seconds) {
        long m = Math.max(1, Math.round(seconds / 60));
        return m < 60 ? m + " min" : (m / 60) + " h " + (m % 60) + " min";
    }
    public static String clock(double secondsFromNow) {
        return new java.text.SimpleDateFormat("h:mm a", Locale.US).format(new Date(System.currentTimeMillis() + Math.round(secondsFromNow * 1000)));
    }
}
