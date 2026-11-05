package net.drave.atrdiagnostics;

import static org.junit.Assert.*;
import org.json.JSONArray;
import org.junit.Test;

public class TurnCardTest {
    private static final String ROUTE = "[{\"type\":1,\"instruction\":\"Head north\",\"street_names\":[\"Osmeña Blvd\"]},"
        + "{\"type\":10,\"instruction\":\"Turn right onto Colon Street\",\"street_names\":[\"Colon Street\"]},"
        + "{\"type\":18,\"instruction\":\"Keep right\",\"begin_street_names\":[\"Mandaue Flyover\"],\"structure\":\"overpass\"},"
        + "{\"type\":4,\"instruction\":\"You have arrived\"}]";

    @Test public void aTurnShowsItsIconStreetAndTheNextTurn() throws Exception {
        TurnCard c = TurnCard.of(new JSONArray(ROUTE), 1, "Turn right onto Colon Street", "Ayala Center Cebu");
        assertFalse(c.arriving); assertEquals(10, c.type); assertEquals(IconDrawable.RIGHT, c.icon); assertEquals("Colon Street", c.street);
        assertEquals(18, c.next.optInt("type"));
        assertEquals("Keep right · overpass · Mandaue Flyover", TurnCard.thenLabel(c.next, "Ayala Center Cebu"));
    }
    @Test public void theTurnBeforeArrivalNamesThePlace() throws Exception {
        JSONArray m = new JSONArray(ROUTE);
        assertEquals("Ayala Center Cebu", TurnCard.thenLabel(m.getJSONObject(3), "Ayala Center Cebu"));
        assertEquals("Arrive", TurnCard.thenLabel(m.getJSONObject(3), ""));
    }
    @Test public void arrivingNamesTheDestinationAndHasNoNextTurn() throws Exception {
        TurnCard c = TurnCard.of(new JSONArray(ROUTE), 1, "Destination nearby", "Ayala Center Cebu");
        assertTrue(c.arriving); assertEquals(4, c.type); assertEquals(IconDrawable.FLAG, c.icon); assertEquals("Ayala Center Cebu", c.street); assertNull(c.next);
        assertEquals("Destination", TurnCard.of(new JSONArray(ROUTE), 1, "Destination nearby", "").street);
    }
    @Test public void noManeuverGoesStraightOnWithTheInstruction() throws Exception {
        TurnCard c = TurnCard.of(new JSONArray(), 0, "Choose a destination", "");
        assertEquals(8, c.type); assertEquals(IconDrawable.STRAIGHT, c.icon); assertEquals("Choose a destination", c.street); assertNull(c.next);
    }
    @Test public void speedRoundsToWholeKilometresPerHour() {
        assertEquals("0 km/h", Gabi.speed(0)); assertEquals("36 km/h", Gabi.speed(10)); assertEquals("43 km/h", Gabi.speed(11.9f));
    }
}
