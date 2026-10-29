package net.drave.atrdiagnostics;

import static org.junit.Assert.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class ForkGuidanceTest {
    private static JSONObject edge(int from, int to, boolean bridge, boolean tunnel) throws Exception {
        return new JSONObject().put("begin_shape_index", from).put("end_shape_index", to).put("bridge", bridge).put("tunnel", tunnel);
    }

    @Test public void aRampThatClimbsOntoABridgeIsAnOverpass() throws Exception {
        // ground road 0-10, the ramp 10-14 (still ground level), then the flyover 14-30
        JSONArray edges = new JSONArray().put(edge(0, 10, false, false)).put(edge(10, 14, false, false)).put(edge(14, 30, true, false));
        assertEquals("overpass", NavSession.structureAt(edges, 10, 20));
    }

    @Test public void stayingOnTheBridgeIsNotNewsAndAFarBridgeDoesNotCount() throws Exception {
        JSONArray onBridge = new JSONArray().put(edge(0, 10, true, false)).put(edge(10, 30, true, false));
        assertNull(NavSession.structureAt(onBridge, 10, 20));
        JSONArray later = new JSONArray().put(edge(0, 10, false, false)).put(edge(10, 25, false, false)).put(edge(25, 40, true, false));
        assertNull("the bridge starts after the stretch that belongs to this turn", NavSession.structureAt(later, 10, 20));
    }

    @Test public void aTunnelIsAnUnderpass() throws Exception {
        JSONArray edges = new JSONArray().put(edge(0, 10, false, false)).put(edge(10, 18, false, true));
        assertEquals("underpass", NavSession.structureAt(edges, 10, 15));
    }

    @Test public void forksLeadWithTheCommand() throws Exception {
        JSONObject keepLeft = new JSONObject().put("type", 24).put("structure", "overpass");
        assertEquals("Keep left · overpass · Osmeña Boulevard", Maneuvers.label(keepLeft, "Osmeña Boulevard"));
        assertEquals("Keep right · Mango Avenue", Maneuvers.label(new JSONObject().put("type", 23), "Mango Avenue"));
        assertEquals("Mango Avenue", Maneuvers.label(new JSONObject().put("type", 10), "Mango Avenue"));
    }

    @Test public void blocksOlderThanTwoWeeksOrFarAwayAreIgnored() throws Exception {
        long now = 2_000_000_000_000L;
        JSONArray all = new JSONArray()
            .put(new JSONArray().put(10.30).put(123.90).put(now - 3600_000))
            .put(new JSONArray().put(10.31).put(123.91).put(now - RoadBlocks.KEEP_MS - 1))
            .put(new JSONArray().put(14.55).put(121.02).put(now));
        JSONArray near = RoadBlocks.near(all, now, 10.30, 123.90, 80);
        assertEquals(1, near.length());
        assertEquals(10.30, near.getJSONObject(0).getDouble("lat"), 1e-9);
    }

    @Test public void roadBlockedIsAVoiceCommand() {
        assertEquals(LocalCommands.Action.BLOCKED, LocalCommands.match("road blocked"));
        assertEquals(LocalCommands.Action.BLOCKED, LocalCommands.match("the road is closed"));
        assertEquals(LocalCommands.Action.UNKNOWN, LocalCommands.match("the road is not blocked"));
    }
}
