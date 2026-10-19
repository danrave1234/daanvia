package net.drave.atrdiagnostics;

import android.content.Context;
import org.json.*;
import java.util.*;

/** Places the rider saved on this phone. Same name or a spot within 25 m counts as the same place. */
public final class Bookmarks {
    public static final class Place { public final String name; public final double lat, lon; Place(String n, double a, double b) { name = n; lat = a; lon = b; } }
    private final Context c;
    public Bookmarks(Context c) { this.c = c.getApplicationContext(); }

    public List<Place> all() {
        ArrayList<Place> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(c.getSharedPreferences("bookmarks", 0).getString("list", "[]"));
            for (int i = 0; i < a.length(); i++) { JSONObject o = a.getJSONObject(i); out.add(new Place(o.getString("name"), o.getDouble("lat"), o.getDouble("lon"))); }
        } catch (JSONException ignored) {}
        return out;
    }
    public Place find(double lat, double lon) {
        for (Place p : all()) if (GeoMath.distance(p.lat, p.lon, lat, lon) < 25) return p;
        return null;
    }
    public void save(String name, double lat, double lon) {
        List<Place> list = all(); ArrayList<Place> out = new ArrayList<>();
        out.add(new Place(name.trim().isEmpty() ? "Saved place" : name.trim(), lat, lon));
        for (Place p : list) if (!p.name.equalsIgnoreCase(name.trim()) && GeoMath.distance(p.lat, p.lon, lat, lon) >= 25) out.add(p);
        write(out);
    }
    public void remove(double lat, double lon) {
        ArrayList<Place> out = new ArrayList<>();
        for (Place p : all()) if (GeoMath.distance(p.lat, p.lon, lat, lon) >= 25) out.add(p);
        write(out);
    }
    private void write(List<Place> list) {
        JSONArray a = new JSONArray();
        try { for (Place p : list) a.put(new JSONObject().put("name", p.name).put("lat", p.lat).put("lon", p.lon)); } catch (JSONException ignored) {}
        c.getSharedPreferences("bookmarks", 0).edit().putString("list", a.toString()).apply();
    }
    /** Saved places whose name holds every typed word, accents ignored. */
    public List<Place> match(String query) {
        String q = SearchIndex.fold(query); ArrayList<Place> out = new ArrayList<>();
        if (q.isEmpty()) return all();
        for (Place p : all()) { String f = SearchIndex.fold(p.name); boolean ok = true; for (String w : q.split(" ")) if (!f.contains(w)) { ok = false; break; } if (ok) out.add(p); }
        return out;
    }
}
