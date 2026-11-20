package net.drave.atrdiagnostics;

/**
 * How hard to look for the bike display, from what Daanvia knows at the moment. Searching keeps Wi-Fi scanning, so it
 * shouldn't run flat out while the bike is switched off; but when the bike must be there, a few seconds' wait is too long.
 * <ul>
 *   <li>The link dropped a moment ago: the bike is on and near (logs of 20 Nov: back within 1 to 4 s), so try at once and
 *   every 2 s.</li>
 *   <li>The rider is moving on a ride: they're on the bike, so it is on. Search back to back.</li>
 *   <li>Stopped, and the bike not found: it may be switched off (a stop for fuel or food). Every 10 s at first, then
 *   slower, up to once a minute.</li>
 * </ul>
 * The rider asking for it (a route, a ride, Connect) always searches at once; see HomeActivity.lookNow.
 */
final class ReconnectPolicy {
    private ReconnectPolicy() {}
    /** The link dropped less than this long ago (ms): the bike is almost surely still on and in reach. */
    static final long JUST_DROPPED = 60_000;
    /** Faster than this (m/s, about 11 km/h) on a ride: riding, so the bike is on. */
    static final float RIDING = 3f;

    /** Before the first try after a drop. A stream that failed within seconds of starting gets a moment to settle. */
    static long firstTry(boolean quickDrop) { return quickDrop ? 3000 : 500; }
    /** Between tries after a drop (each starts only once the last search has ended). */
    static long afterDrop(long sinceDrop) { return sinceDrop < JUST_DROPPED ? 2000 : 8000; }
    /** Between the ride's own searches for the bike. */
    static long rideGap(int misses, boolean moving, long sinceDrop) {
        if (moving || sinceDrop < JUST_DROPPED) return 2000;
        return Math.min(60000, 10000 + Math.max(0, misses - 12) * 10000L);
    }
    /** Moving on a ride: a recent fix (under 10 s old) at riding speed, not a demo. */
    static boolean moving(float speed, long fixAge, boolean simulation) { return !simulation && fixAge < 10000 && speed >= RIDING; }
}
