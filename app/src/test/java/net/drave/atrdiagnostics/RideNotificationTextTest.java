package net.drave.atrdiagnostics;
import org.junit.Test;import static org.junit.Assert.*;
/** The ride notification's line: the next turn, so the rider sees it with the screen off (NavService.turnLine). */
public class RideNotificationTextTest {
    @Test public void showsDistanceAndTurn() {
        assertEquals("In 40 m · Turn left onto Don Jose Avila Street",
            NavService.turnLine(true, false, "Turn left onto Don Jose Avila Street.", 42));
        assertEquals("In 1.2 km · Continue on N. Escario Street",
            NavService.turnLine(true, false, "Continue on N. Escario Street", 1230));
    }
    @Test public void dropsTheRouteNumberTheRouterJoinsToTheName() {
        assertEquals("In 260 m · Turn right onto General Maxilom Avenue",
            NavService.turnLine(true, false, "Turn right onto General Maxilom Avenue/8.", 262));
        assertEquals("In 500 m · Keep left onto Cebu South Road",
            NavService.turnLine(true, false, "Keep left onto Cebu South Road/N8", 500));
        assertEquals("In 100 m · Turn left onto A/B Street",
            NavService.turnLine(true, false, "Turn left onto A/B Street", 100));
    }
    @Test public void noTurnBeforeTheRouteIsKnown() {
        assertNull(NavService.turnLine(false, false, "Turn left", 40));
        assertNull(NavService.turnLine(true, false, null, 40));
        assertNull(NavService.turnLine(true, false, "  ", 40));
        assertNull(NavService.turnLine(true, false, "Route ready", 40));
        assertNull(NavService.turnLine(true, false, "Choose a destination", 40));
    }
    @Test public void arrivalAndUnknownDistanceShowJustTheText() {
        assertEquals("Destination nearby", NavService.turnLine(true, false, "Destination nearby", 20));
        assertEquals("You have arrived", NavService.turnLine(true, true, "You have arrived.", 5));
        assertEquals("Continue", NavService.turnLine(true, false, "Continue", 0));
        assertEquals("Continue", NavService.turnLine(true, false, "Continue", Double.NaN));
    }
}
