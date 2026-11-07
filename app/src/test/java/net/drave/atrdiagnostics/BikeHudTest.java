package net.drave.atrdiagnostics;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.TimeZone;
import static org.junit.Assert.*;

/** The turn arrows for the bike's own screen, numbered and rounded the way Carbit Ride 2.4 sends them. */
public class BikeHudTest {
    @Test public void arrowsMatchCarbit() {
        assertEquals(2, BikeHud.icon(15, 19));   // left
        assertEquals(3, BikeHud.icon(10, 19));   // right
        assertEquals(4, BikeHud.icon(16, 19));   // slight left
        assertEquals(7, BikeHud.icon(11, 19));   // sharp right
        assertEquals(8, BikeHud.icon(13, 19));   // u-turn
        assertEquals(9, BikeHud.icon(8, 19));    // continue
        assertEquals(11, BikeHud.icon(26, 19));  // roundabout
        assertEquals(15, BikeHud.icon(4, 19));   // arrive
        assertEquals(5, BikeHud.icon(23, 19));   // keep right on an older dashboard
        assertEquals(52, BikeHud.icon(23, 80));  // its own arrow on a newer one
    }
    @Test public void distanceToTheTurnInStepsOf50() {
        assertEquals(0, BikeHud.toTurn(0)); assertEquals(50, BikeHud.toTurn(12)); assertEquals(150, BikeHud.toTurn(199)); assertEquals(1200, BikeHud.toTurn(1234));
    }
    @Test public void turnMessage() throws Exception {
        JSONArray m = new JSONArray().put(new JSONObject().put("type", 15).put("street_names", new JSONArray().put("Osmeña Blvd")));
        JSONObject j = new JSONObject(BikeHud.info(m, 0, 234, 5000, 600, "Ayala", 19, 1_000_000L, TimeZone.getTimeZone("Asia/Manila")));
        assertEquals(0, j.getInt("status")); assertEquals(2, j.getInt("naviIcon")); assertEquals("Osmeña Blvd", j.getString("nextRoad"));
        assertEquals(200, j.getInt("roadRemainingDistance")); assertEquals(5000, j.getInt("destinationRemainingDistance"));
        assertEquals(600, j.getInt("destinationRemainingTime")); assertEquals(1_600_000L, j.getLong("arriveTime"));
        assertEquals(1, new JSONObject(BikeHud.info(new JSONArray(), 0, 0, 0, 0, "", 19, 0, TimeZone.getDefault())).getInt("status"));
        assertEquals(19, BikeHud.maxIcon("{\"supportFunction\":0}")); assertEquals(80, BikeHud.maxIcon("{\"maxNaviIcon\":80}"));
    }
    @Test public void locationReplyCarriesTheFixTime() throws Exception {
        JSONObject j = new JSONObject(BikeHud.location(true, 10.3, 123.9, 5f, 0, 90f, 1_700_000_000_000L));
        assertTrue(j.getBoolean("status")); assertEquals(1_700_000_000_000L, j.getLong("time")); assertEquals(123.9, j.getDouble("longitude"), 1e-9);
        assertFalse(new JSONObject(BikeHud.location(false, 0, 0, 0, 0, 0, 0)).getBoolean("status"));
    }
}
