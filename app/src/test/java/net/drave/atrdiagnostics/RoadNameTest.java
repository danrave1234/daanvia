package net.drave.atrdiagnostics;

import static org.junit.Assert.*;
import java.util.TimeZone;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Turn cards name the road the way it reads on a sign, not by its national route number (Maneuvers.road). */
public class RoadNameTest {
    private static JSONObject turn(String key, String... names) throws Exception {
        JSONArray a = new JSONArray(); for (String n : names) a.put(n);
        return new JSONObject().put("type", 10).put(key, a).put("instruction", "Turn right onto " + String.join("/", names) + ".");
    }

    @Test public void theNameWinsOverTheRouteNumber() throws Exception {
        assertEquals("Don Andres Soriano Avenue", Maneuvers.road(turn("street_names", "840", "Don Andres Soriano Avenue")));
        assertEquals("Sergio Osmeña Avenue", Maneuvers.road(turn("street_names", "Sergio Osmeña Avenue", "840")));
    }
    @Test public void aRoadKnownOnlyByItsNumberKeepsIt() throws Exception {
        assertEquals("N8", Maneuvers.road(turn("street_names", "N8")));
        assertEquals("840", Maneuvers.road(turn("street_names", "840", "8")));
    }
    @Test public void shortNamesAndCodes() throws Exception {
        assertEquals("EDSA", Maneuvers.road(turn("street_names", "C-4", "EDSA")));
        assertEquals("C-3", Maneuvers.road(turn("street_names", "C-3")));
    }
    @Test public void fallsBackToTheRoadItBeginsOn() throws Exception {
        assertEquals("D.M. Cortes Avenue", Maneuvers.road(turn("begin_street_names", "840", "D.M. Cortes Avenue")));
        assertEquals("", Maneuvers.road(new JSONObject().put("type", 15)));
        assertEquals("", Maneuvers.road(null));
    }
    @Test public void phoneCardBikeCardAndBikeArrowsAllSayTheName() throws Exception {
        JSONArray turns = new JSONArray().put(turn("street_names", "840", "Don Andres Soriano Avenue")).put(turn("street_names", "Juan Luna Avenue", "815"));
        TurnCard card = TurnCard.of(turns, 0, "Turn right onto 840/Don Andres Soriano Avenue.", "Ayala");
        assertEquals("Don Andres Soriano Avenue", card.street);
        assertEquals("Juan Luna Avenue", TurnCard.thenLabel(card.next, "Ayala"));
        String hud = BikeHud.info(turns, 0, 120, 3000, 400, 19, 0, TimeZone.getTimeZone("Asia/Manila"));
        assertEquals("Don Andres Soriano Avenue", new JSONObject(hud).getString("nextRoad"));
    }
}
