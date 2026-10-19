package net.drave.atrdiagnostics;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.*;
import android.os.SystemClock;
import androidx.core.location.LocationManagerCompat;

/**
 * Where the rider is while the map is on screen, the way Google Maps finds you: the phone's last known spot at once,
 * then live fixes from Google's fused location (Wi-Fi, cell towers, GPS and motion sensors), see {@link LocationFeed}.
 * Rough fixes still place the dot, drawn with a circle showing how sure it is. During a ride, NavService owns location.
 */
final class Here {
    private final Context c; private final NavSession s; private final LocationFeed feed; private LocationManager lm;

    Here(Context c, NavSession s) { this.c = c.getApplicationContext(); this.s = s; feed = new LocationFeed(c, this::feed, false); }

    /**
     * Clear the phone's saved GPS assistance (satellite almanac, last position and time) and fetch fresh copies, like the
     * "reset A-GPS" of GPS toolbox apps. Helps when the location is stuck, far off or slow to lock. The next fix is a cold
     * start, so it needs a clear view of the sky and can take up to a minute.
     */
    static boolean recalibrate(Context c) {
        LocationManager lm = c.getSystemService(LocationManager.class); if (lm == null) return false;
        boolean cleared = false;
        try { cleared = lm.sendExtraCommand(LocationManager.GPS_PROVIDER, "delete_aiding_data", null); } catch (Exception ignored) {}
        for (String cmd : new String[]{"force_time_injection", "force_psds_injection", "force_xtra_injection"}) try { lm.sendExtraCommand(LocationManager.GPS_PROVIDER, cmd, null); } catch (Exception ignored) {}
        return cleared;
    }
    static boolean allowed(Context c) { return c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED || c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED; }
    static boolean precise(Context c) { return c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED; }

    /** Whether Location is switched on in the phone's settings. */
    boolean enabled() { manager(); return lm != null && LocationManagerCompat.isLocationEnabled(lm); }
    private void manager() { if (lm == null) lm = c.getSystemService(LocationManager.class); }

    void start() {
        if (feed.running() || !allowed(c)) return;
        // The phone's last known spot shows at once while the first live fix comes in. An old one is drawn as rough
        // (a wide circle), since the rider may have moved since; live fixes replace it.
        Location last = LocationFeed.lastKnown(c);
        if (last != null && ageMillis(last) < 30 * 60_000L) feed(last, ageMillis(last) > 60_000 ? Math.max(last.getAccuracy(), 150) : last.getAccuracy());
        feed.start();
    }
    void stop() { feed.stop(); }

    private void feed(Location l) { feed(l, l.getAccuracy()); }
    private void feed(Location l, float acc) {
        if (s.active) return;
        long at = SystemClock.elapsedRealtime() - ageMillis(l);
        if (!better(acc, at, s.accuracy, s.fixAt, SystemClock.elapsedRealtime())) return;
        // Lying still: the same spot again doesn't wake the screen or redraw the map; a fresh fix every 15 s keeps it current.
        if (s.fixAt > 0 && at - s.fixAt < 15_000 && Math.abs(acc - s.accuracy) < 3
            && GeoMath.distance(s.lat, s.lon, l.getLatitude(), l.getLongitude()) < Math.max(1.5, acc * 0.1)) return;
        boolean moving = l.hasBearing() && (!l.hasSpeed() || l.getSpeed() > 1.4f);
        s.update(l.getLatitude(), l.getLongitude(), acc, moving ? l.getBearing() : s.bearing, false, at);
    }
    private static long ageMillis(Location l) { return l.getElapsedRealtimeNanos() > 0 ? Math.max(0, (SystemClock.elapsedRealtimeNanos() - l.getElapsedRealtimeNanos()) / 1_000_000L) : 0; }

    /** A rougher fix only replaces a sharper one once the sharper one is 10 s old, so Wi-Fi doesn't drag a good GPS dot around. */
    static boolean better(float acc, long at, float oldAcc, long oldAt, long now) {
        if (oldAt <= 0) return true;
        if (at < oldAt) return false;
        return acc <= oldAcc * 1.5f + 5 || now - oldAt > 10_000;
    }
}
