package net.drave.atrdiagnostics;

import static org.junit.Assert.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class RideMetricsTest {
    private static JSONObject ride(int samples) throws Exception {
        JSONArray a = new JSONArray();
        for (int i = 0; i < samples; i++) a.put(new JSONObject().put("elapsed_s", i * 30));
        return new JSONObject().put("simulation", false).put("samples", a);
    }

    @Test public void aLongRideIsThinnedToSixtySamplesKeepingFirstAndLast() throws Exception {
        JSONObject thin = RideMetrics.thin(ride(720), 60);
        JSONArray kept = thin.getJSONArray("samples");
        assertEquals(60, kept.length());
        assertEquals(720, thin.getInt("samples_total"));
        assertEquals(0, kept.getJSONObject(0).getInt("elapsed_s"));
        assertEquals(719 * 30, kept.getJSONObject(59).getInt("elapsed_s"));
    }

    @Test public void aShortRideIsLeftAlone() throws Exception {
        JSONObject r = ride(12);
        assertSame(r, RideMetrics.thin(r, 60));
    }
}
