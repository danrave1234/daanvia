package net.drave.atrdiagnostics;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/**
 * Asks the OpenStreetMap API (free, no key) whether a lot is still mapped, with its current tags and last edit
 * date. A deleted lot answers 410 Gone. Runs only when the rider opens a lot; never during a ride. Call off the main thread.
 */
public final class LiveCheck {
    public static final class Result {
        public final boolean reachable, exists; public final JSONObject tags; public final String edited;
        Result(boolean reachable, boolean exists, JSONObject tags, String edited) { this.reachable = reachable; this.exists = exists; this.tags = tags; this.edited = edited; }
    }
    private LiveCheck() {}

    public static Result check(String id) {
        if (id == null || id.length() < 2) return new Result(false, false, null, null);
        String type = id.charAt(0) == 'w' ? "way" : id.charAt(0) == 'r' ? "relation" : "node";
        try {
            HttpURLConnection c = (HttpURLConnection)new URL("https://api.openstreetmap.org/api/0.6/" + type + "/" + Long.parseLong(id.substring(1)) + ".json").openConnection();
            c.setConnectTimeout(8000); c.setReadTimeout(10000); c.setRequestProperty("User-Agent", "Daanvia/" + BuildInfo.VERSION + " (motorcycle navigation)");
            int code = c.getResponseCode();
            if (code == 410 || code == 404) { c.disconnect(); return new Result(true, false, null, null); }
            if (code != 200) { c.disconnect(); return new Result(false, false, null, null); }
            String body;
            try (InputStream in = c.getInputStream()) { ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int n; while ((n = in.read(b)) > 0) o.write(b, 0, n); body = o.toString(StandardCharsets.UTF_8.name()); }
            finally { c.disconnect(); }
            return parse(body);
        } catch (Exception e) { return new Result(false, false, null, null); }
    }

    static Result parse(String body) throws JSONException {
        JSONArray els = new JSONObject(body).optJSONArray("elements");
        if (els == null || els.length() == 0) return new Result(true, false, null, null);
        JSONObject e = els.getJSONObject(0); JSONObject tags = e.optJSONObject("tags");
        boolean parking = tags != null && ("parking".equals(tags.optString("amenity")) || "motorcycle_parking".equals(tags.optString("amenity")));
        String ts = e.optString("timestamp", "");
        return new Result(true, parking, tags, ts.length() >= 10 ? ts.substring(0, 10) : null);
    }
}
