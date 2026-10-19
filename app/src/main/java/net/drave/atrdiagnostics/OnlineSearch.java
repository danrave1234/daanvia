package net.drave.atrdiagnostics;

import org.json.*;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Online place search with Photon, komoot's free OpenStreetMap search (no key). It knows every branch, street and
 * barangay in the country, so "chowking guadalupe" finds the Chowking on M. Velez Street. Results lean towards
 * the rider's position and stay inside the Philippines. Offline search still works without signal.
 */
final class OnlineSearch {
    private OnlineSearch() {}
    static final String URL = "https://photon.komoot.io/api/";
    /** The Philippines, so a "Guadalupe" never jumps to Mexico. */
    static final String BBOX = "116.9,4.5,126.7,21.2";

    static final class Place {
        final String name, address; final double lat, lon;
        /** The name with its street and city, to tell one branch of a chain from the next: "Jollibee · EDSA, Makati". */
        String branch;
        Place(String name, String address, double lat, double lon) { this.name = name; this.address = address; this.lat = lat; this.lon = lon; branch = name; }
    }

    static List<Place> search(String query, double nearLat, double nearLon, int limit) throws IOException {
        String url = URL + "?q=" + URLEncoder.encode(query.trim(), "UTF-8") + "&limit=" + limit + "&lang=en&bbox=" + BBOX
            + String.format(Locale.US, "&lat=%.5f&lon=%.5f", nearLat, nearLon);
        HttpURLConnection c = (HttpURLConnection)new URL(url).openConnection();
        c.setConnectTimeout(5000); c.setReadTimeout(7000); c.setRequestProperty("User-Agent", "Daanvia/" + BuildInfo.VERSION + " (github.com/danrave1234/daanvia)");
        try {
            if (c.getResponseCode() != 200) throw new IOException("Search unavailable");
            return parse(AreaStore.readAll(c.getInputStream()));
        } catch (JSONException e) { throw new IOException("Search unavailable"); }
        finally { c.disconnect(); }
    }

    /** Photon's GeoJSON: a name (or the street address when a place has none) and a short "street, barangay, city" line. */
    static List<Place> parse(String json) throws JSONException {
        ArrayList<Place> out = new ArrayList<>();
        JSONArray features = new JSONObject(json).optJSONArray("features"); if (features == null) return out;
        for (int i = 0; i < features.length(); i++) {
            JSONObject f = features.getJSONObject(i), p = f.optJSONObject("properties"); JSONArray xy = f.optJSONObject("geometry") == null ? null : f.getJSONObject("geometry").optJSONArray("coordinates");
            if (p == null || xy == null || xy.length() < 2) continue;
            String street = join(" ", p.optString("housenumber"), p.optString("street"));
            String name = p.optString("name"); if (name.isEmpty()) name = street; if (name.isEmpty()) continue;
            LinkedHashSet<String> parts = new LinkedHashSet<>();
            for (String part : new String[]{street, p.optString("locality"), p.optString("district"), p.optString("city"), p.optString("county")}) {
                if (!part.isEmpty() && !part.equalsIgnoreCase(name) && parts.size() < 3) parts.add(part);
            }
            if (parts.isEmpty() && !p.optString("state").isEmpty()) parts.add(p.optString("state"));
            Place place = new Place(name, String.join(", ", parts), xy.getDouble(1), xy.getDouble(0));
            String where = !p.optString("street").isEmpty() ? p.optString("street") : !p.optString("locality").isEmpty() ? p.optString("locality") : p.optString("district");
            String town = !p.optString("city").isEmpty() ? p.optString("city") : p.optString("county");
            String at = join(", ", where.equalsIgnoreCase(name) ? "" : where, town.equalsIgnoreCase(where) ? "" : town);
            if (!at.isEmpty()) place.branch = name + " · " + at;
            out.add(place);
        }
        return out;
    }
    private static String join(String sep, String... parts) { StringBuilder b = new StringBuilder(); for (String s : parts) if (s != null && !s.isEmpty()) { if (b.length() > 0) b.append(sep); b.append(s); } return b.toString(); }
}
