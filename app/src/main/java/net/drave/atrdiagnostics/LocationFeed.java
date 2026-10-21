package net.drave.atrdiagnostics;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Looper;
import androidx.core.location.LocationListenerCompat;
import androidx.core.location.LocationManagerCompat;
import androidx.core.location.LocationRequestCompat;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.common.api.ResolvableApiException;
import com.google.android.gms.location.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Live location for the map and for rides. On phones with Google Play services it uses Google's fused location, the
 * same source as Google Maps: Wi-Fi, cell towers, GPS and the motion sensors together, so a first fix comes in seconds
 * (even indoors) and GPS fixes are steadier. Without Play services it falls back to Android's own providers.
 */
final class LocationFeed {
    interface Sink { void fix(Location l); }

    private final Context c; private final Sink sink; private final boolean gpsOnly;
    private FusedLocationProviderClient fused; private LocationCallback callback;
    private LocationManager lm; private LocationListenerCompat listener; private boolean on;

    /** {@code gpsOnly}: without Play services, listen to GPS only (rides), not Wi-Fi and cell locations too. */
    LocationFeed(Context c, Sink sink, boolean gpsOnly) { this.c = c.getApplicationContext(); this.sink = sink; this.gpsOnly = gpsOnly; }

    static boolean google(Context c) {
        try { return GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(c) == ConnectionResult.SUCCESS; } catch (Throwable t) { return false; }
    }

    boolean running() { return on; }

    /**
     * Start updates at the best accuracy, every second on a ride and every two seconds while browsing the map (the dot glides
     * between fixes); also asks once for a fresh fix right away. False if nothing could listen.
     */
    @SuppressLint("MissingPermission") // callers check the permission first; a SecurityException is caught below
    boolean start() {
        if (on) return true;
        if (google(c)) try {
            fused = LocationServices.getFusedLocationProviderClient(c);
            callback = new LocationCallback() { public void onLocationResult(LocationResult r) { for (Location l : r.getLocations()) sink.fix(l); } };
            fused.requestLocationUpdates(new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, gpsOnly ? 1000 : 2000).setMinUpdateIntervalMillis(1000).setWaitForAccurateLocation(false).build(), callback, Looper.getMainLooper());
            // The repeating request can take a few seconds to deliver its first fix; this one is answered as soon as possible.
            fused.getCurrentLocation(new CurrentLocationRequest.Builder().setPriority(Priority.PRIORITY_HIGH_ACCURACY).setMaxUpdateAgeMillis(15000).build(), null)
                .addOnSuccessListener(l -> { if (l != null && on) sink.fix(l); });
            on = true; return true;
        } catch (SecurityException | IllegalStateException e) { fused = null; callback = null; }
        lm = c.getSystemService(LocationManager.class); if (lm == null) return false;
        listener = sink::fix;
        for (String p : providers()) try { LocationManagerCompat.requestLocationUpdates(lm, p, request(), listener, Looper.getMainLooper()); on = true; } catch (SecurityException | IllegalArgumentException ignored) {}
        return on;
    }

    @SuppressLint("MissingPermission") // removing updates needs no permission; the compat API is annotated as if it did
    void stop() {
        if (!on) return; on = false;
        if (fused != null) { fused.removeLocationUpdates(callback); fused = null; callback = null; }
        if (lm != null && listener != null) try { LocationManagerCompat.removeUpdates(lm, listener); } catch (Exception ignored) {}
        listener = null;
    }

    /** The phone's last known location from any source, newest first; null if none. */
    @SuppressLint("MissingPermission")
    static Location lastKnown(Context c) {
        LocationManager lm = c.getSystemService(LocationManager.class); if (lm == null) return null;
        Location best = null;
        try { for (String p : lm.getProviders(true)) { Location l = lm.getLastKnownLocation(p); if (l != null && (best == null || l.getElapsedRealtimeNanos() > best.getElapsedRealtimeNanos())) best = l; } } catch (SecurityException ignored) {}
        return best;
    }

    /**
     * Ask, the way Google Maps does, to turn on Location and Google's location accuracy (Wi-Fi and Bluetooth scanning)
     * when either is off. Android shows its own one-tap dialog; nothing happens when everything is already on.
     */
    static void askAccuracy(Activity a, int requestCode) {
        if (!google(a)) return;
        try {
            LocationSettingsRequest req = new LocationSettingsRequest.Builder().addLocationRequest(new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000).build()).setAlwaysShow(true).build();
            LocationServices.getSettingsClient(a).checkLocationSettings(req).addOnFailureListener(e -> {
                if (e instanceof ResolvableApiException && !a.isFinishing()) try { ((ResolvableApiException)e).startResolutionForResult(a, requestCode); } catch (Exception ignored) {}
            });
        } catch (Throwable ignored) {}
    }

    static LocationRequestCompat request() { return new LocationRequestCompat.Builder(1000).setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY).setMinUpdateIntervalMillis(500).build(); }

    /** Without Play services: Android 12+'s fused provider when present, else Wi-Fi/cell plus GPS (GPS only for rides). */
    private List<String> providers() {
        ArrayList<String> out = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31 && lm.hasProvider(LocationManager.FUSED_PROVIDER)) { out.add(LocationManager.FUSED_PROVIDER); return out; }
        if (!gpsOnly && lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) out.add(LocationManager.NETWORK_PROVIDER);
        if (Here.precise(c)) out.add(LocationManager.GPS_PROVIDER);
        return out;
    }
}
