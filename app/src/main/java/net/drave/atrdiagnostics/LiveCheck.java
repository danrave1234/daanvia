package net.drave.atrdiagnostics;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/**
 * Asks Overpass, OpenStreetMap's public read-only query service (free, no key), whether a lot is still mapped, with its
 * current tags and last edit date; a deleted lot comes back with no elements. (OpenStreetMap's own API is meant for
 * editors, not apps reading data.) Runs only when the rider opens a lot; never during a ride. Call off the main thread.
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
        long number;
        try { number = Long.parseLong(id.substring(1)); } catch (NumberFormatException e) { return new Result(false, false, null, null); }
        // Public Overpass servers are often busy: ask three at once and take the first answer, so the rider waits for the
        // quickest one instead of each in turn.
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(SERVERS.length);
        java.util.concurrent.CompletionService<Result> answers = new java.util.concurrent.ExecutorCompletionService<>(pool);
        final String element = type + "(" + number + ")";
        for (String server : SERVERS) answers.submit(() -> ask(server, element));
        try { for (int i = 0; i < SERVERS.length; i++) { Result r = answers.take().get(); if (r.reachable) return r; } }
        catch (Exception ignored) {}
        finally { pool.shutdownNow(); }
        return new Result(false, false, null, null);
    }
    private static final String[] SERVERS = {"https://overpass-api.de/api/interpreter", "https://maps.mail.ru/osm/tools/overpass/api/interpreter", "https://overpass.private.coffee/api/interpreter"};
    private static Result ask(String server, String element) {
        try {
            String query = "[out:json][timeout:10];" + element + ";out tags meta;";
            // A form POST is how Overpass asks to be queried; some servers refuse a GET with 406.
            HttpURLConnection c = (HttpURLConnection)new URL(server).openConnection();
            c.setConnectTimeout(6000); c.setReadTimeout(20000); c.setRequestMethod("POST"); c.setDoOutput(true);
            c.setRequestProperty("User-Agent", "Daanvia/" + BuildInfo.VERSION + " (Android motorcycle navigation; https://daanvia-app.vercel.app)");
            c.setRequestProperty("Accept", "application/json"); c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            try (OutputStream out = c.getOutputStream()) { out.write(("data=" + URLEncoder.encode(query, "UTF-8")).getBytes(StandardCharsets.UTF_8)); }
            int code = c.getResponseCode();
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
