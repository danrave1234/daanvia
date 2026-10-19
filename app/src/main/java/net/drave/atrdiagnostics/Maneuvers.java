package net.drave.atrdiagnostics;

/** Maps Valhalla maneuver types to the turn icons shown on the phone. */
public final class Maneuvers {
    private Maneuvers() {}

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
