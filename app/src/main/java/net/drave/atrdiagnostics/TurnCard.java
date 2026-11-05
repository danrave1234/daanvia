package net.drave.atrdiagnostics;

import org.json.JSONArray;
import org.json.JSONObject;

/** What the phone's turn card says: the turn's icon and the street under the distance. Shared by the ride screen and the resting screen. */
final class TurnCard {
    /** The destination is close: the card says Arriving and names the place instead of a street. */
    final boolean arriving;
    /** Valhalla's maneuver type (4 when arriving, 8 when there's no maneuver), and the icon drawn for it. */
    final int type, icon;
    final String street;
    /** The turn after this one, null when arriving or at the last one. */
    final JSONObject next;

    private TurnCard(boolean arriving, int type, String street, JSONObject next) { this.arriving = arriving; this.type = type; icon = Maneuvers.icon(type); this.street = street; this.next = next; }

    static TurnCard of(JSONArray maneuvers, int index, String instruction, String destination) {
        JSONObject m = maneuvers.optJSONObject(index);
        boolean arriving = "Destination nearby".equals(instruction);
        int type = arriving ? 4 : m == null ? 8 : m.optInt("type", 8);
        String street = arriving ? (destination.isEmpty() ? "Destination" : destination) : Maneuvers.label(m, streetOf(m, instruction));
        return new TurnCard(arriving, type, street, arriving ? null : maneuvers.optJSONObject(index + 1));
    }
    /** The "Then" chip's words: the place when the next turn is the arrival, else that turn's street. */
    static String thenLabel(JSONObject next, String destination) {
        int t = next.optInt("type");
        return t >= 4 && t <= 6 ? (destination.isEmpty() ? "Arrive" : destination) : Maneuvers.label(next, streetOf(next, next.optString("instruction", "")));
    }
    static String streetOf(JSONObject m, String fallback) {
        if (m == null) return fallback;
        JSONArray n = m.optJSONArray("street_names"); if (n != null && n.length() > 0) return n.optString(0);
        n = m.optJSONArray("begin_street_names"); if (n != null && n.length() > 0) return n.optString(0);
        return m.optString("instruction", fallback);
    }
}
