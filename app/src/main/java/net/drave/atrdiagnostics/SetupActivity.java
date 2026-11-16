package net.drave.atrdiagnostics;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.util.ArrayList;
import java.util.List;

import static net.drave.atrdiagnostics.Gabi.Role.*;

/**
 * Get ready to ride: everything Daanvia needs from Android and the rider, on one screen with a reason and a button each,
 * ticked off as it's done. Opens once by itself when something is missing (HomeActivity), and from Settings. It replaces
 * the questions that used to pop up one at a time on different screens.
 */
public final class SetupActivity extends Activity {
    /** One thing to have ready. */
    private static final class Item {
        final int icon; final String title, why; final boolean optional; final java.util.function.Predicate<Context> done; final java.util.function.Consumer<SetupActivity> fix;
        Item(int icon, String title, String why, boolean optional, java.util.function.Predicate<Context> done, java.util.function.Consumer<SetupActivity> fix) {
            this.icon = icon; this.title = title; this.why = why; this.optional = optional; this.done = done; this.fix = fix;
        }
    }
    private static boolean granted(Context c, String permission) { return c.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED; }
    static boolean bikeSaved(Context c) { return !c.getSharedPreferences("saved-bike", 0).getString("qr", "").isEmpty(); }

    private static List<Item> items() {
        List<Item> l = new ArrayList<>();
        l.add(new Item(IconDrawable.LOC, "Precise location", "For turn-by-turn directions. Only used on this phone", false,
            Here::precise, a -> a.ask(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION})));
        if (Build.VERSION.SDK_INT >= 33) {
            l.add(new Item(IconDrawable.TFT, "Nearby devices", "Lets Daanvia find your bike's Wi-Fi", false,
                c -> granted(c, Manifest.permission.NEARBY_WIFI_DEVICES), a -> a.ask(new String[]{Manifest.permission.NEARBY_WIFI_DEVICES})));
            l.add(new Item(IconDrawable.INFO, "Notifications", "Reconnect and Exit on your lock screen during rides", false,
                c -> granted(c, Manifest.permission.POST_NOTIFICATIONS), a -> a.ask(new String[]{Manifest.permission.POST_NOTIFICATIONS})));
        }
        l.add(new Item(IconDrawable.PLAY, "Keep running during rides", "So Android doesn't stop directions with the screen off", false,
            KeepAlive::allowed, KeepAlive::ask));
        if (KeepAlive.colorOs() != null)
            l.add(new Item(IconDrawable.TOOLS, KeepAlive.colorOs() + " battery settings", "Your phone has its own battery manager that can close Daanvia mid-ride", false,
                KeepAlive::colorOsDone, a -> a.startActivity(new Intent(a, SettingsActivity.class).putExtra("show", "coloros"))));
        l.add(new Item(IconDrawable.BIKE, "Your bike", "Scan the QR code on your bike's display once", false,
            SetupActivity::bikeSaved, a -> a.startActivity(new Intent(a, SettingsActivity.class))));
        l.add(new Item(IconDrawable.MIRROR, "Notifications on the bike", "Optional: calls and messages shown on the bike display", true,
            c -> RideNotifications.enabled(c) && RideNotifications.allowed(c), a -> {
                RideNotifications.setEnabled(a, true);
                if (!RideNotifications.allowed(a)) try { a.startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); } catch (Exception ignored) {}
            }));
        return l;
    }
    /** How many of the needed (not optional) items are done, and how many there are. */
    static int[] progress(Context c) {
        int done = 0, all = 0;
        for (Item i : items()) if (!i.optional) { all++; if (i.done.test(c)) done++; }
        return new int[]{done, all};
    }
    /** Something needed is missing. */
    static boolean missing(Context c) { int[] p = progress(c); return p[0] < p[1]; }

    private Gabi.Kit k; private LinearLayout list; private TextView summary;
    private final List<Item> items = items();

    public void onCreate(Bundle state) {
        super.onCreate(state);
        NavSession s = NavSession.get(this);
        k = new Gabi.Kit(this, new Gabi.Themer(Gabi.resolve(this, s.lat, s.lon, s.fixAt > 0)));
        GabiPage page = new GabiPage(this, k, "Get ready to ride");
        summary = k.text("", 14, 600, MUTED); page.content.addView(summary);
        LinearLayout card = GabiPage.card(k); list = k.column(4); card.addView(list); page.content.addView(card);
        page.content.addView(k.primary("Done", this::finish));
    }
    protected void onResume() { super.onResume(); paint(); }

    private void paint() {
        list.removeAllViews();
        int[] p = progress(this);
        summary.setText(p[0] == p[1] ? "All set. You're ready to ride." : p[0] + " of " + p[1] + " ready. Tap each one to allow it.");
        for (Item i : items) {
            boolean done = i.done.test(this);
            LinearLayout row = k.row(12); row.setPadding(0, k.dp(10), 0, k.dp(10)); row.setMinimumHeight(k.dp(64));
            FrameLayout tile = new FrameLayout(this); k.fill(tile, 14, done ? OK_SOFT : ACCENT_SOFT);
            tile.addView(k.icon(done ? IconDrawable.CHECK : i.icon, 20, done ? OK : ACCENT), new FrameLayout.LayoutParams(k.dp(20), k.dp(20), Gravity.CENTER));
            row.addView(tile, new LinearLayout.LayoutParams(k.dp(44), k.dp(44)));
            LinearLayout text = k.column(2);
            text.addView(k.text(i.title, 15, 700, INK)); text.addView(k.text(i.why, 13, 500, MUTED));
            row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
            if (done) row.addView(k.text("Done", 13, 700, OK));
            else { TextView go = k.single(k.chip(i.optional ? "Turn on" : "Allow", ACCENT_SOFT, ACCENT)); row.addView(go); Gabi.press(row, () -> i.fix.accept(this)); }
            list.addView(row);
        }
    }

    /** A permission question; once Android stops asking (refused twice), its app settings page instead. */
    private void ask(String[] permissions) {
        String key = "setup_asked_" + permissions[0];
        boolean askedBefore = getSharedPreferences("ui", 0).getBoolean(key, false);
        if (askedBefore && !shouldShowRequestPermissionRationale(permissions[0])) {
            try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); } catch (Exception ignored) {}
            return;
        }
        getSharedPreferences("ui", 0).edit().putBoolean(key, true).apply();
        requestPermissions(permissions, 1);
    }
    public void onRequestPermissionsResult(int code, String[] p, int[] r) { super.onRequestPermissionsResult(code, p, r); paint(); }
    public void finish() { super.finish(); overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out); }
}
