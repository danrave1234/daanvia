package net.drave.atrdiagnostics;

import static org.junit.Assert.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class SectionTimesTest {
    // Four points 100 m apart: a slow first maneuver (points 0-2, 120 s) and a fast second one (points 2-3, 10 s).
    private static final double[] PREFIX = {0, 100, 200, 300};

    private static JSONArray maneuvers() throws Exception {
        return new JSONArray()
            .put(new JSONObject().put("begin_shape_index", 0).put("end_shape_index", 2).put("time", 120))
            .put(new JSONObject().put("begin_shape_index", 2).put("end_shape_index", 3).put("time", 10));
    }

    @Test public void eachStretchKeepsItsOwnTime() throws Exception {
        double[] ct = NavSession.sectionTimes(PREFIX, maneuvers(), 130);
        assertEquals(0, ct[0], 1e-9);
        assertEquals(60, ct[1], 1e-9);   // half of the slow maneuver
        assertEquals(120, ct[2], 1e-9);
        assertEquals(130, ct[3], 1e-9);
        // Time left from the last stretch is the fast maneuver's 10 s, not a third of the total.
        assertEquals(10, ct[3] - ct[2], 1e-9);
    }

    @Test public void withoutManeuverTimesTheTotalIsSpreadEvenly() throws Exception {
        double[] ct = NavSession.sectionTimes(PREFIX, new JSONArray(), 90);
        assertEquals(30, ct[1], 1e-9);
        assertEquals(90, ct[3], 1e-9);
    }
}
