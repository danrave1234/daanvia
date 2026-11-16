package net.drave.atrdiagnostics;

import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Where the rider is drawn and what they are told when they are not on the middle of the road: at a pump in a gas
 * station, in a car park, on a mall driveway or a service road beside the route. Car navigation (Mapbox's route
 * snapping, Google Maps) draws the rider on the route line only when the fix is close to it and agrees in heading;
 * otherwise it shows the real position, a dotted line to the route, and how to get onto it ("Turn left onto ...").
 *
 * <p>All decisions are pure and fix by fix, so they are unit tested (RouteSnapTest). Inputs, for one fix:
 * <ul>
 *   <li>{@code gap}: metres from the fix to the route line where it was matched;</li>
 *   <li>{@code diff}: degrees between the rider's course and the route there, 0 to 180, NaN when the course isn't
 *   known (stopped or creeping: GPS course is noise below walking pace);</li>
 *   <li>{@code accuracy}: the fix's stated error in metres; {@code speed} in m/s.</li>
 * </ul>
 */
final class RouteSnap {
    /** GPS course counts from here (about 5 km/h); below it the bike is pushed or stopped and the course is noise. */
    static final float COURSE_SPEED = 1.5f;
    /** Riding for real (9 km/h): only then does a course along the route pull the arrow onto it. */
    static final float RIDING_SPEED = 2.5f;
    /** Course this far off the route, a few metres from it: turned into a station, a lot or a side street. */
    static final double LEAVE_ANGLE = 55;
    /** Course this close to the route counts as riding along it. */
    static final double ALONG_ANGLE = 35;
    /** Within this of the line, only riding the opposite way takes the arrow off it (sharp corners swing the course). */
    static final double ON_LINE = 8;
    /** Off the route by at least this, the rider is shown the way onto it instead of the next turn. */
    static final double JOIN_MIN = 12;

    private boolean on; private int against;

    boolean snapped() { return on; }
    /** A new ride: start from the raw position until the fixes say the rider is on the route. */
    void reset() { on = false; against = 0; }

    /**
     * Updates and returns whether the arrow sits on the route line. A change needs two fixes in a row, so one bounced fix
     * doesn't move the arrow between the parking aisle and the road; it is at once when it's plain: right on the line or
     * riding along it, or twice the keep distance away.
     */
    boolean update(double gap, double diff, float accuracy, float speed) {
        boolean want = verdict(on, gap, diff, accuracy, speed);
        if (want == on) { against = 0; return on; }
        boolean along = !Double.isNaN(diff) && speed >= RIDING_SPEED && diff <= ALONG_ANGLE;
        boolean plain = want ? gap <= still(accuracy) || along : gap > 2 * keep(accuracy);
        if (plain || ++against >= 2) { on = want; against = 0; }
        return on;
    }

    /** What this one fix says, given whether the arrow is on the line now. The distances grow with the GPS error. */
    static boolean verdict(boolean on, double gap, double diff, float accuracy, float speed) {
        if (!(accuracy <= 50)) return on;  // too rough to tell a pump from the road: no change
        boolean course = !Double.isNaN(diff) && speed >= COURSE_SPEED;
        // Riding the other way, or heading off the road a few metres from it.
        if (course && (diff > 150 || diff > LEAVE_ANGLE && gap > ON_LINE)) return false;
        if (on) return gap <= keep(accuracy);
        if (course && speed >= RIDING_SPEED && diff <= ALONG_ANGLE) return gap <= along(accuracy);
        return gap <= still(accuracy);
    }
    /** Keeps the arrow on the road: lanes of a wide road (up to about 12 m from the line) plus GPS error. 17 m at ±5 m. */
    static double keep(float accuracy) { return 12 + clamp(accuracy, 0, 20); }
    /** Puts the arrow on the road while riding along it. 10 m at ±5 m, 22 m at ±20 m. */
    static double along(float accuracy) { return 6 + 0.8 * clamp(accuracy, 0, 20); }
    /** Puts the arrow on the road without a course (stopped): only right on the line. 6.5 m at ±5 m. */
    static double still(float accuracy) { return 4 + 0.5 * clamp(accuracy, 0, 10); }

    /**
     * Whether to show the way onto the route (see {@link #joinType}) instead of the next turn: off the line by
     * {@link #JOIN_MIN} or more with a fair fix, and not riding along a parallel road or away from the route (then the
     * next turn, or the reroute, is what matters). {@code toJoin} is the degrees between the rider's course and the
     * direction to the point where they join; NaN like {@code diff}.
     */
    static boolean joining(boolean snapped, double gap, double diff, double toJoin, float accuracy, float speed) {
        if (snapped || gap < JOIN_MIN || !(accuracy < 35)) return false;
        boolean riding = speed >= RIDING_SPEED;
        if (riding && !Double.isNaN(diff) && diff <= ALONG_ANGLE) return false;   // a service road beside the route
        return !(riding && !Double.isNaN(toJoin) && toJoin > 100);               // riding away from it
    }

    /**
     * The turn onto the route, as a Valhalla maneuver type, for a rider leaving a station or car park towards the route:
     * {@code approach} is the bearing from the rider to the point where they join it, {@code road} the route's bearing
     * there. Right-hand traffic doesn't matter here: a station on the right of the route means turning right out of it.
     * 8 continue, 10 right, 11 sharp right, 15 left, 14 sharp left.
     */
    static int joinType(double approach, double road) { return typeOf(GeoMath.turn((float)approach, (float)road)); }
    /**
     * {@link #joinType}, keeping {@code previous} (0: none) unless the turn is 10 degrees past the line between them: a fix
     * wobbling at a pump shouldn't flip "Turn right" and "Join" back and forth, each change said again.
     */
    static int joinType(double approach, double road, int previous) {
        double t = GeoMath.turn((float)approach, (float)road); int now = typeOf(t);
        if (previous != 0 && now != previous && (typeOf(t - 10) == previous || typeOf(t + 10) == previous)) return previous;
        return now;
    }
    private static int typeOf(double t) {
        double a = Math.abs(t);
        if (a <= 30) return 8;
        if (t > 0) return a > 135 ? 11 : 10;
        return a > 135 ? 14 : 15;
    }
    /** The words for {@link #joinType}: "Turn left onto Osmeña Boulevard", or "Turn left" when the road has no name. */
    static String joinText(int type, String street) {
        boolean named = street != null && !street.isEmpty();
        switch (type) {
            case 10: return named ? "Turn right onto " + street : "Turn right";
            case 11: return named ? "Turn sharp right onto " + street : "Turn sharp right";
            case 15: return named ? "Turn left onto " + street : "Turn left";
            case 14: return named ? "Turn sharp left onto " + street : "Turn sharp left";
            default: return named ? "Join " + street : "Join the route";
        }
    }

    /**
     * Fixes in a row that, like this one, ask for a new route: 0 when this fix doesn't count. {@code joined}: the rider
     * has been on this route; {@code startGap}: how far from the route the rider was when it was calculated (a pump
     * 70 m from the road the router started on), so waiting there doesn't reroute again and again to the same route.
     */
    static int offRouteFixes(double gap, double diff, float accuracy, float speed, boolean joined, double startGap) {
        if (!(accuracy < 35)) return 0;   // too rough to judge
        boolean course = !Double.isNaN(diff) && speed >= 4;
        // Turned off into a side street: heading clearly away, already a little off it. Two fixes, about 25 m in.
        if (joined && course && accuracy < 30 && gap > 22 && diff > LEAVE_ANGLE) return 2;
        // Riding the route the wrong way (past a U-turn the rider didn't take), right on its line.
        if (joined && course && diff > 150) return 4;
        // Standing still (a pump, a parking space): the rider hasn't gone anywhere; only a big gap counts.
        if (speed < 1 && gap < 150) return 0;
        double far = joined ? Math.max(65, accuracy * 1.5) : Math.max(65, startGap + 50);
        return gap > far ? 3 : 0;
    }

    /** The way onto the route as a maneuver, put in front of turn {@code next} of a copy of the route's turns. */
    static JSONArray withJoin(JSONArray turns, int next, int type, String street, int shapeIndex) throws JSONException {
        String text = joinText(type, street);
        JSONObject join = new JSONObject().put("type", type).put("instruction", text + ".").put("verbal_pre_transition_instruction", text + ".")
            .put("begin_shape_index", shapeIndex).put("end_shape_index", shapeIndex).put("join", true);
        if (street != null && !street.isEmpty()) join.put("street_names", new JSONArray().put(street));
        JSONArray out = new JSONArray();
        for (int i = 0; i < turns.length(); i++) { if (i == next) out.put(join); out.put(turns.get(i)); }
        if (next >= turns.length()) out.put(join);
        return out;
    }
    /** The turn whose stretch holds route point {@code index}: the last that begins at or before it. */
    static int turnAt(JSONArray turns, int index) {
        int at = 0;
        for (int i = 0; i < turns.length(); i++) { JSONObject m = turns.optJSONObject(i); if (m != null && m.optInt("begin_shape_index", 0) <= index) at = i; else if (m != null) break; }
        return at;
    }
    /**
     * The road's name as a rider reads it on a sign: the first of the turn's names with words in it, so "840 / D.M. Cortes
     * Avenue" says D.M. Cortes Avenue rather than the route number. Empty when the road has no name.
     */
    static String street(JSONObject m) {
        if (m == null) return "";
        JSONArray names = m.optJSONArray("street_names"); if (names == null || names.length() == 0) names = m.optJSONArray("begin_street_names");
        if (names == null || names.length() == 0) return "";
        for (int i = 0; i < names.length(); i++) { String n = names.optString(i, ""); if (n.matches(".*\\p{L}\\p{L}.*")) return n; }
        return names.optString(0, "");
    }
    /** The route's bearing at {@code at} (a point on segment {@code i}), over the next 15 m so a tiny kink doesn't decide. */
    static double roadBearing(List<double[]> route, int i, double[] at) {
        int n = route.size(); if (n < 2) return 0;
        i = Math.max(0, Math.min(i, n - 2));
        for (int j = i + 1; j < n; j++) { double[] q = route.get(j); if (GeoMath.distance(at[0], at[1], q[0], q[1]) >= 15 || j == n - 1) { if (q[0] != at[0] || q[1] != at[1]) return GeoMath.bearing(at, q); } }
        return GeoMath.bearing(route.get(i), route.get(i + 1));
    }
    /** How far (lat, lon) is from the first {@code meters} of the route: from a pump, the road the router started on. */
    static double gapTo(List<double[]> route, double[] cumulative, double lat, double lon, double meters) {
        if (route.isEmpty()) return 0;
        if (route.size() == 1) return GeoMath.distance(lat, lon, route.get(0)[0], route.get(0)[1]);
        double best = Double.MAX_VALUE; boolean measured = cumulative != null && cumulative.length == route.size();
        for (int i = 0; i < route.size() - 1; i++) { if (measured && cumulative[i] > meters) break; best = Math.min(best, GeoMath.segmentDistance(lat, lon, route.get(i), route.get(i + 1))); }
        return best;
    }

    private static double clamp(float v, double lo, double hi) { return Math.max(lo, Math.min(hi, Float.isNaN(v) ? hi : v)); }
}
