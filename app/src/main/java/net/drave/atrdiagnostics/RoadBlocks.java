package net.drave.atrdiagnostics;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Roads the rider found blocked (a closure, roadworks, a gate that's now locked). The offline map can't know, so the
 * rider marks it ("Road blocked" on the ride screen, or the voice command) and routes keep away from that spot for two
 * weeks. Kept on the phone only.
 */
final class RoadBlocks {
    private RoadBlocks() {}
    static final long KEEP_MS = 14L * 24 * 3600_000;
    private static final int MAX = 50;

    static void add(Context c, double lat, double lon) {
        JSONArray all = read(c), out = new JSONArray(); long now = System.currentTimeMillis();
        try {
            for (int i = Math.max(0, all.length() - (MAX - 1)); i < all.length(); i++) out.put(all.getJSONArray(i));
            out.put(new JSONArray().put(lat).put(lon).put(now));
        } catch (JSONException ignored) {}
        c.getSharedPreferences("ui", 0).edit().putString("road_blocks", out.toString()).apply();
    }

    /** Blocks less than two weeks old within {@code km} of a point, as Valhalla exclude_locations. */
    static JSONArray near(Context c, double lat, double lon, double km) {
        return near(read(c), System.currentTimeMillis(), lat, lon, km);
    }
    static JSONArray near(JSONArray all, long now, double lat, double lon, double km) {
        JSONArray out = new JSONArray();
        for (int i = 0; i < all.length(); i++) {
            JSONArray b = all.optJSONArray(i); if (b == null || b.length() < 3) continue;
            if (now - b.optLong(2) > KEEP_MS) continue;
            double la = b.optDouble(0), lo = b.optDouble(1);
            if (GeoMath.distance(lat, lon, la, lo) > km * 1000) continue;
            try { out.put(new JSONObject().put("lat", la).put("lon", lo)); } catch (JSONException ignored) {}
        }
        return out;
    }
    static int count(Context c) { return near(read(c), System.currentTimeMillis(), 0, 0, 1e6).length(); }
    static void clear(Context c) { c.getSharedPreferences("ui", 0).edit().remove("road_blocks").apply(); }

    private static JSONArray read(Context c) {
        try { return new JSONArray(c.getSharedPreferences("ui", 0).getString("road_blocks", "[]")); } catch (JSONException e) { return new JSONArray(); }
    }
}
