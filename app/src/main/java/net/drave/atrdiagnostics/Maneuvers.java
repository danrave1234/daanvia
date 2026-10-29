package net.drave.atrdiagnostics;

/** Maps Valhalla maneuver types to the turn icons shown on the phone. */
public final class Maneuvers {
    private Maneuvers() {}

    /** Keep left or right, a ramp, an exit or a slight turn: where two roads split and it's easy to take the wrong one. */
    public static boolean fork(int type) { return type == 9 || type == 16 || (type >= 17 && type <= 24); }
    /** The short command for a fork, said and shown first so it isn't lost behind the street name; null for other turns. */
    public static String forkCommand(int type) {
        switch (type) {
            case 9: case 18: case 20: case 23: return "Keep right";
            case 16: case 19: case 21: case 24: return "Keep left";
            case 17: case 22: return "Keep straight";
            default: return null;
        }
    }
    /** What the turn card says under the distance: for a fork, "Keep left · overpass · Street"; otherwise the street. */
    public static String label(org.json.JSONObject m, String street) {
        if (m == null) return street;
        String command = fork(m.optInt("type")) ? forkCommand(m.optInt("type")) : null;
        if (command == null) return street;
        String structure = m.optString("structure", "");
        return command + (structure.isEmpty() ? "" : " · " + structure) + (street == null || street.isEmpty() ? "" : " · " + street);
    }
    public static int icon(int valhallaType) {
        switch (valhallaType) {
            case 4: case 5: case 6: return IconDrawable.FLAG;                       // destination
            case 2: case 10: return IconDrawable.RIGHT;                             // start right, right
            case 3: case 15: return IconDrawable.LEFT;                              // start left, left
            case 9: case 18: case 20: case 23: return IconDrawable.SLIGHT_RIGHT;    // slight / ramp / exit / stay right
            case 16: case 19: case 21: case 24: return IconDrawable.SLIGHT_LEFT;
            case 11: return IconDrawable.SHARP_RIGHT;
            case 14: return IconDrawable.SHARP_LEFT;
            case 12: case 13: return IconDrawable.UTURN;
            case 26: case 27: return IconDrawable.ROUNDABOUT;
            case 25: case 37: case 38: return IconDrawable.MERGE;
            default: return IconDrawable.STRAIGHT;
        }
    }
}
