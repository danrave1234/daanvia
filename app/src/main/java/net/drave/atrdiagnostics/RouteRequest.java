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
}
