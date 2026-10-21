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
