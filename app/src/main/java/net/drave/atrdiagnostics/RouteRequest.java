package net.drave.atrdiagnostics;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * All routing requests keep the same access rules, except the one last resort when no reachable road is near the
 * destination at all (a home deep inside a gated village): then access is ignored and the route card says the place
 * is inside a private subdivision.
 */
final class RouteRequest {
    private RouteRequest() {}

    static JSONObject between(double fromLat, double fromLon, double toLat, double toLon,
                              boolean alternatives) throws JSONException {
        JSONArray locations = new JSONArray()
            .put(new JSONObject().put("lat", fromLat).put("lon", fromLon))
            .put(new JSONObject().put("lat", toLat).put("lon", toLon));
        return through(locations, alternatives);
    }

    static JSONObject through(JSONArray locations, boolean alternatives) throws JSONException { return through(locations, alternatives, false); }

    static JSONObject through(JSONArray locations, boolean alternatives, boolean anyAccess) throws JSONException {
        JSONObject scooter = new JSONObject().put("top_speed", 60).put("gate_cost", 60)
            .put("gate_penalty", 300).put("private_access_penalty", 600);
        if (anyAccess) scooter.put("ignore_access", true);
        return new JSONObject().put("locations", locations).put("costing", "motor_scooter")
            .put("units", "kilometers").put("language", "en-US")
            .put("alternates", alternatives && locations.length() == 2 ? 2 : 0)
            .put("costing_options", new JSONObject().put("motor_scooter", scooter));
    }

    /**
     * Start the route the way a rider who is moving is heading (Valhalla's location options, tested on the Metro Cebu
     * graph with Valhalla 3.9.0, the version the app runs):
     * <ul>
     *   <li>{@code heading} with {@code heading_tolerance}: only roads running the rider's way. GPS course is steadier the
     *   faster the bike goes, so the tolerance narrows from 60 to 45 degrees.</li>
     *   <li>{@code radius} from the GPS error: the heading alone does nothing when the closest road runs the other way
     *   (a fix nearer the far carriageway of a divided road: Valhalla still started there and asked for a U-turn).
     *   With a radius, the carriageway the rider is on is a candidate too, and candidates are ranked by distance.</li>
     * </ul>
     * Stopped (a pump, a parking space) the request keeps Valhalla's defaults: the closest road, which is the station's
     * own driveway or parking aisle where the map has one (the graph keeps them: include_driveways), else the road beside
     * it. A radius without a heading would let the ranking start on a cheaper road farther off, such as the other
     * carriageway, so it is only sent with one. {@code preferred_side} stays "either": "same" made riders turn right out
     * of a station and U-turn, where a scooter may turn left.
     */
    static JSONObject headed(JSONObject location, double heading, float accuracy, float speed) throws JSONException {
        int tolerance = speed >= 8 ? 45 : speed >= 5 ? 50 : 60;
        int radius = (int)Math.round(Math.max(12, Math.min(35, 5 + 1.5 * (Float.isNaN(accuracy) ? 20 : accuracy))));
        return location.put("heading", (int)Math.round(GeoMath.normalise(heading))).put("heading_tolerance", tolerance).put("radius", radius);
    }
}
