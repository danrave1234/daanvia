package net.drave.atrdiagnostics;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.*;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import androidx.core.location.LocationListenerCompat;
import androidx.core.location.LocationManagerCompat;
import androidx.core.location.LocationRequestCompat;
import java.util.ArrayList;
import java.util.List;

/**
 * Where the rider is while the map is on screen, the way Google Maps finds you: the phone's last known spot at once,
 * then live fixes from every source Android allows (Wi-Fi and cell towers, GPS, and Android 12+'s fused location).
 * Rough fixes still place the dot, drawn with a circle showing how sure it is. During a ride, NavService owns location.
 */
final class Here implements LocationListenerCompat {
    private final Context c; private final NavSession s; private LocationManager lm; private boolean on;

    Here(Context c, NavSession s) { this.c = c.getApplicationContext(); this.s = s; }

    static boolean allowed(Context c) { return c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED || c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED; }
    static boolean precise(Context c) { return c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED; }

    /** Whether Location is switched on in the phone's settings. */
    boolean enabled() { manager(); return lm != null && LocationManagerCompat.isLocationEnabled(lm); }
    private void manager() { if (lm == null) lm = c.getSystemService(LocationManager.class); }

    void start() {
        if (on || !allowed(c)) return;
        manager(); if (lm == null) return; on = true;
        Location best = null;
        try { for (String p : lm.getProviders(true)) { Location l = lm.getLastKnownLocation(p); if (l != null && (best == null || l.getElapsedRealtimeNanos() > best.getElapsedRealtimeNanos())) best = l; } } catch (SecurityException ignored) {}
        if (best != null && ageMillis(best) < 30 * 60_000L) feed(best);
        for (String p : providers()) {
            try { LocationManagerCompat.requestLocationUpdates(lm, p, request(), this, Looper.getMainLooper()); } catch (SecurityException | IllegalArgumentException ignored) {}
        }
    }
    /** Removing updates needs no permission; the compat API is annotated as if it did. */
    @android.annotation.SuppressLint("MissingPermission")
    void stop() { if (!on) return; on = false; try { LocationManagerCompat.removeUpdates(lm, this); } catch (Exception ignored) {} }
    /** Every second at high accuracy: without it Android's fused location settles for Wi-Fi and cell towers and leaves GPS off. */
    static LocationRequestCompat request() { return new LocationRequestCompat.Builder(1000).setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY).setMinUpdateIntervalMillis(500).build(); }

    /** Network gives a fix indoors within seconds, GPS gives the precise one outside, fused blends both when present. */
    private List<String> providers() {
        ArrayList<String> out = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31 && lm.hasProvider(LocationManager.FUSED_PROVIDER)) out.add(LocationManager.FUSED_PROVIDER);
        if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) out.add(LocationManager.NETWORK_PROVIDER);
        if (precise(c) && lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) out.add(LocationManager.GPS_PROVIDER);
        return out;
    }

    public void onLocationChanged(Location l) { feed(l); }

    private void feed(Location l) {
        if (s.active) return;
        long at = SystemClock.elapsedRealtime() - ageMillis(l);
        if (!better(l.getAccuracy(), at, s.accuracy, s.fixAt, SystemClock.elapsedRealtime())) return;
        boolean moving = l.hasBearing() && (!l.hasSpeed() || l.getSpeed() > 1.4f);
        s.update(l.getLatitude(), l.getLongitude(), l.getAccuracy(), moving ? l.getBearing() : s.bearing, false, at);
    }
    private static long ageMillis(Location l) { return l.getElapsedRealtimeNanos() > 0 ? Math.max(0, (SystemClock.elapsedRealtimeNanos() - l.getElapsedRealtimeNanos()) / 1_000_000L) : 0; }

    /** A rougher fix only replaces a sharper one once the sharper one is 10 s old, so Wi-Fi doesn't drag a good GPS dot around. */
    static boolean better(float acc, long at, float oldAcc, long oldAt, long now) {
        if (oldAt <= 0) return true;
        if (at < oldAt) return false;
        return acc <= oldAcc * 1.5f + 5 || now - oldAt > 10_000;
    }
}
