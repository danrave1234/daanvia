package net.drave.atrdiagnostics;

import java.util.TimeZone;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * What the bike's own screen shows when the map is closed: the next turn's arrow, how far it is, and the trip's distance,
 * time and arrival. The same messages Carbit Ride sends (read from Carbit Ride 2.4):
 *
 *  - The dashboard asks for them with 0x10040 ({"supportFunction":…, "maxNaviIcon":…}; no maxNaviIcon means 19).
 *  - 0x201e0 {"status":true|false} says navigation started or ended.
 *  - 0x20110 carries the turn: naviIcon (the arrow, numbered below), nextRoad, roadRemainingDistance (metres to the turn,
 *    in steps of 50), the trip's remaining distance and seconds, and the arrival time. {"status":1} means no route.
 *  - 0x20170 closes the phone's picture so the bike shows its own screen with these arrows; 0x201d0 brings it back.
 */
final class BikeHud {
    private BikeHud() {}

    static final int ASK = 0x10040, NAVI_STATUS = 0x201e0, INFO = 0x20110, OWN_SCREEN = 0x20170, PHONE_SCREEN = 0x201d0;

    /** The arrows every dashboard knows (up to 19), as Carbit numbers them. */
    static final int LEFT = 2, RIGHT = 3, SLIGHT_LEFT = 4, SLIGHT_RIGHT = 5, SHARP_LEFT = 6, SHARP_RIGHT = 7, UTURN = 8,
        STRAIGHT = 9, ROUNDABOUT = 11, ROUNDABOUT_EXIT = 12, ARRIVE = 15;
    /** Newer dashboards (maxNaviIcon over 19) also know these. */
    static final int KEEP_LEFT = 51, KEEP_RIGHT = 52;

    /** The arrow for a Valhalla maneuver type, within what the dashboard knows ({@code max}); 0 when none fits. */
    static int icon(int type, int max) {
        int i;
        switch (type) {
            case 1: case 7: case 8: case 17: case 22: i = STRAIGHT; break;          // start, becomes, continue, ramp/stay straight
            case 2: case 10: i = RIGHT; break;
            case 3: case 15: i = LEFT; break;
            case 4: case 5: case 6: i = ARRIVE; break;                               // destination (ahead, right, left)
            case 9: case 18: case 20: case 23: case 37: i = SLIGHT_RIGHT; break;     // slight right, ramp/exit/stay right, merge right
            case 16: case 19: case 21: case 24: case 38: i = SLIGHT_LEFT; break;     // slight left, ramp/exit/stay left, merge left
            case 11: i = SHARP_RIGHT; break;
            case 14: i = SHARP_LEFT; break;
            case 12: case 13: i = UTURN; break;
            case 26: i = ROUNDABOUT; break;
            case 27: i = ROUNDABOUT_EXIT; break;
            case 25: i = STRAIGHT; break;                                             // merge
            default: i = STRAIGHT;
        }
        // On a newer dashboard a fork or ramp to one side has its own "keep" arrow.
        if (max > 19 && (type == 23 || type == 18 || type == 20)) i = KEEP_RIGHT;
        if (max > 19 && (type == 24 || type == 19 || type == 21)) i = KEEP_LEFT;
        return i <= max ? i : 0;
    }

    /** Metres to the turn the way Carbit rounds them: 0, then at least 50, in steps of 50. */
    static int toTurn(double metres) {
        if (metres <= 0) return 0;
        int m = (int)metres;
        return m < 50 ? 50 : m / 50 * 50;
    }

    /** The 0x20110 body for the ride as it stands, or the "no route" body when {@code maneuvers} is empty. */
    static String info(JSONArray maneuvers, int at, double toTurn, double remaining, double seconds, String destination, int maxIcon, long nowMs, TimeZone zone) {
        try {
            JSONObject j = new JSONObject();
            JSONObject m = maneuvers == null ? null : maneuvers.optJSONObject(at);
            if (m == null) return j.put("status", 1).toString();
            int sec = (int)Math.max(0, Math.round(seconds));
            j.put("status", 0).put("currentRoad", "").put("carDirection", 0).put("cameraType", 0).put("cameraSpeed", 0).put("cameraDistance", 0)
                .put("naviIcon", icon(m.optInt("type"), maxIcon)).put("nextRoad", road(m))
                .put("roadRemainingDistance", toTurn(toTurn)).put("roadRemainingTime", 0)
                .put("destinationRemainingDistance", (int)Math.max(0, remaining)).put("destinationRemainingTime", sec)
                .put("arriveTimeZone", zone.getID()).put("arriveTime", nowMs + sec * 1000L).put("signalIntensity", 1)
                .put("naviDestination", destination == null ? "" : destination);
            return j.toString();
        } catch (JSONException e) { return "{\"status\":1}"; }
    }

    private static String road(JSONObject m) {
        JSONArray n = m.optJSONArray("street_names");
        if (n != null && n.length() > 0) return n.optString(0);
        n = m.optJSONArray("begin_street_names");
        return n != null && n.length() > 0 ? n.optString(0) : "";
    }

    /** The dashboard's 0x10040 request: how many arrows it knows (19 when it doesn't say). */
    static int maxIcon(String body) {
        try { return body == null || body.trim().isEmpty() ? 19 : new JSONObject(body).optInt("maxNaviIcon", 19); }
        catch (JSONException e) { return 19; }
    }

    /**
     * The reply to the dashboard's 0x10430 location question (0x10431), as Carbit answers it: where the phone is, its
     * speed and heading, and the time of that fix. Daanvia used to answer with nothing.
     */
    static String location(boolean known, double lat, double lon, float speed, double altitude, float bearing, long fixTimeMs) {
        try {
            JSONObject j = new JSONObject();
            if (!known) return j.put("status", false).toString();
            return j.put("status", true).put("longitude", lon).put("latitude", lat).put("speed", speed).put("altitude", altitude).put("course", bearing)
                .put("time", fixTimeMs).put("province", "").put("city", "").put("district", "").put("road", "").put("number", "").put("poiname", "").toString();
        } catch (JSONException e) { return "{\"status\":false}"; }
    }
}
