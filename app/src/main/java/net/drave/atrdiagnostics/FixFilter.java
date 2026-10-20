package net.drave.atrdiagnostics;

/**
 * Keeps location glitches off the map. Two kinds made the dot jump hundreds of metres and come back:
 * <ul>
 *   <li>A rough Wi-Fi or cell-tower fix (±300–800 m) arriving between GPS fixes, e.g. under a roof or between tall
 *   buildings. It no longer moves a sharp dot; it only takes over once no sharp fix has come for 30 s.</li>
 *   <li>A GPS fix that bounced off buildings (multipath): sharp-looking, but further away than anyone could have
 *   ridden since the last fix. It is held back until the next fixes agree with it, which a glitch never does.</li>
 * </ul>
 */
final class FixFilter {
    /** Faster than any rider (250 km/h), counted beyond both fixes' stated error. */
    static final double MAX_SPEED = 70;
    /** How long a sharp fix keeps a rougher one from taking over, and how long a jump stays suspicious. */
    static final long ROUGH_WAIT = 30_000, JUMP_WAIT = 60_000;
    /** Fixes in a row, all at the new place, that make a jump real. */
    static final int CONFIRM = 3;

    private double lat, lon; private float acc; private long at = -1;
    private double candLat, candLon; private int cand;

    void reset() { at = -1; cand = 0; }

    /** Whether to use this fix. {@code t} is when it was taken and {@code now} the time now (both elapsedRealtime, ms). */
    boolean accept(double la, double lo, float ac, long t, long now) {
        if (at < 0) return take(la, lo, ac, t);
        if (t < at) return false;
        long age = now - at; double d = GeoMath.distance(lat, lon, la, lo);
        // Much rougher than the dot: wait for the sharp source to come back, unless it has been gone a while.
        if (ac > Math.max(acc * 2, acc + 25) && age < ROUGH_WAIT) return false;
        double beyond = d - ac - acc, dt = Math.max(1, (t - at) / 1000.0);
        if (beyond > MAX_SPEED * dt && age < JUMP_WAIT) {
            if (cand > 0 && GeoMath.distance(candLat, candLon, la, lo) < Math.max(50, ac * 2)) { if (++cand >= CONFIRM) return take(la, lo, ac, t); }
            else { candLat = la; candLon = lo; cand = 1; }
            return false;
        }
        return take(la, lo, ac, t);
    }
    private boolean take(double la, double lo, float ac, long t) { lat = la; lon = lo; acc = ac; at = t; cand = 0; return true; }
}
