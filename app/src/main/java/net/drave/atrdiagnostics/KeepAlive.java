package net.drave.atrdiagnostics;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;

/**
 * Keeping Daanvia running through a ride. Some phones (OPPO, realme, OnePlus, Xiaomi and others) close apps they think
 * use too much battery, even a navigation app showing on screen, which cuts the bike display off mid-ride. Being let off
 * Android's battery optimisation is what those phones check first.
 */
final class KeepAlive {
    private KeepAlive() {}

    static boolean allowed(Context c) {
        PowerManager p = c.getSystemService(PowerManager.class);
        return p == null || p.isIgnoringBatteryOptimizations(c.getPackageName());
    }

    /** Android's own one-tap "Allow" dialog; the battery settings list if this phone has no such dialog. */
    @SuppressLint("BatteryLife") // a navigation app that streams to a dashboard is the case this permission is for
    static void ask(Activity a) {
        if (allowed(a)) return;
        try { a.startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + a.getPackageName()))); }
        catch (Exception e) { try { a.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); } catch (Exception ignored) {} }
    }

    /**
     * True when Android, or the phone maker's battery saver, ended the app last time: low memory, too much power use, or
     * a kill of its own (OPPO's shows as "o-kill"). Not a crash, an update or the rider closing it.
     */
    static boolean killedBySystem(Context c) {
        if (android.os.Build.VERSION.SDK_INT < 30) return false;
        try {
            for (android.app.ApplicationExitInfo e : c.getSystemService(android.app.ActivityManager.class).getHistoricalProcessExitReasons(null, 0, 1)) {
                int r = e.getReason();
                return r == android.app.ApplicationExitInfo.REASON_SIGNALED || r == android.app.ApplicationExitInfo.REASON_LOW_MEMORY
                    || r == android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE || r == android.app.ApplicationExitInfo.REASON_OTHER
                    || r == android.app.ApplicationExitInfo.REASON_FREEZER;
            }
        } catch (Exception ignored) {}
        return false;
    }

    /** After the phone closed Daanvia mid-ride: Android's Allow dialog, or, once that's allowed, the app's own settings page. */
    static void fix(Activity a) {
        if (!allowed(a)) { ask(a); return; }
        try { a.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.getPackageName()))); } catch (Exception ignored) {}
    }

    /**
     * The phone maker's name when the phone runs ColorOS (OPPO, OnePlus, realme), whose own battery manager closes apps
     * mid-ride even when Android allows them to run ("o-kill"); null for other phones. Those riders get their own steps.
     */
    static String colorOs() {
        String m = (android.os.Build.MANUFACTURER + " " + android.os.Build.BRAND).toLowerCase(java.util.Locale.ROOT);
        return m.contains("oneplus") ? "OnePlus" : m.contains("realme") ? "realme" : m.contains("oppo") ? "OPPO" : null;
    }
    /** The rider said they've set up ColorOS's own settings for Daanvia. */
    static boolean colorOsDone(Context c) { return c.getSharedPreferences("ui", 0).getBoolean("coloros_done", false); }
    /** On a ColorOS phone not yet set up: a reminder at most once every three days. */
    static boolean remindColorOs(Context c) {
        if (colorOs() == null || colorOsDone(c)) return false;
        android.content.SharedPreferences p = c.getSharedPreferences("ui", 0); long now = System.currentTimeMillis();
        if (now - p.getLong("coloros_reminded", 0) < 3L * 24 * 3600 * 1000) return false;
        p.edit().putLong("coloros_reminded", now).apply(); return true;
    }

    /** At the start of a ride with a saved bike: ask, at most once every three days, until allowed. */
    static void askForRide(Activity a) {
        if (allowed(a) || a.getSharedPreferences("saved-bike", 0).getString("qr", "").isEmpty()) return;
        android.content.SharedPreferences p = a.getSharedPreferences("ui", 0);
        long now = System.currentTimeMillis();
        if (now - p.getLong("keepalive_asked", 0) < 3L * 24 * 3600 * 1000) return;
        p.edit().putLong("keepalive_asked", now).apply();
        ask(a);
    }
}
