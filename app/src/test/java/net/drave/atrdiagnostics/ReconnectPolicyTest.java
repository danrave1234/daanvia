package net.drave.atrdiagnostics;
import org.junit.Test;import static org.junit.Assert.*;
/** When Daanvia tries again within seconds, and when it backs off (ReconnectPolicy). */
public class ReconnectPolicyTest {
    private static final long LONG_AGO = Long.MAX_VALUE / 2;
    @Test public void justDroppedTriesAtOnceAndEveryTwoSeconds() {
        assertEquals(500, ReconnectPolicy.firstTry(false));
        assertEquals(3000, ReconnectPolicy.firstTry(true));          // failed within seconds of starting: let it settle
        assertEquals(2000, ReconnectPolicy.afterDrop(5_000));
        assertEquals(2000, ReconnectPolicy.afterDrop(59_000));
        assertEquals(8000, ReconnectPolicy.afterDrop(61_000));
    }
    @Test public void ridingSearchesBackToBack() {
        assertEquals(2000, ReconnectPolicy.rideGap(30, true, LONG_AGO));   // moving: the bike is on, however many misses
        assertEquals(2000, ReconnectPolicy.rideGap(30, false, 10_000));    // stopped, but it dropped a moment ago
    }
    @Test public void stoppedWithTheBikeGoneBacksOff() {
        assertEquals(10000, ReconnectPolicy.rideGap(0, false, LONG_AGO));
        assertEquals(10000, ReconnectPolicy.rideGap(12, false, LONG_AGO));
        assertEquals(20000, ReconnectPolicy.rideGap(13, false, LONG_AGO));
        assertEquals(60000, ReconnectPolicy.rideGap(100, false, LONG_AGO));
    }
    @Test public void movingMeansARecentFixAtRidingSpeed() {
        assertTrue(ReconnectPolicy.moving(8f, 1000, false));
        assertFalse(ReconnectPolicy.moving(1f, 1000, false));        // walking the bike, or stopped
        assertFalse(ReconnectPolicy.moving(8f, 30_000, false));      // an old fix says nothing about now
        assertFalse(ReconnectPolicy.moving(8f, 1000, true));         // the demo ride moves without a bike
    }
}
