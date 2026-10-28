package net.drave.atrdiagnostics;

import static org.junit.Assert.*;
import org.junit.Test;

public class ArrivalTest {
    @Test public void rollingUpToTheDestinationDoesNotEndTheRide() {
        assertFalse(NavService.arrivalDone(0, 20, 8000));
        assertFalse(NavService.arrivalDone(12000, 10, 15000));
    }
    @Test public void parkingForTwentySecondsEndsIt() { assertTrue(NavService.arrivalDone(20000, 10, 25000)); }
    @Test public void ridingOnPastTheDestinationEndsIt() { assertTrue(NavService.arrivalDone(0, 260, 40000)); }
    @Test public void fiveMinutesAfterArrivingEndsIt() { assertTrue(NavService.arrivalDone(0, 80, 300000)); }
}
