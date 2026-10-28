package net.drave.atrdiagnostics;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Places found with online search, kept on the phone so they are still found without signal. Each carries its
 * street line, so "chowking guadalupe" matches it offline too. Newest first, at most {@link #MAX} places.
 */
final class PlaceCache {
    static final int MAX = 1500;
    private final File file; private final ArrayList<Entry> entries = new ArrayList<>();

    static final class Entry {
        final String name, address; final double lat, lon; final String folded;
        Entry(String name, String address, double lat, double lon) { this.name = name; this.address = address == null ? "" : address; this.lat = lat; this.lon = lon; folded = SearchIndex.fold(name + " " + this.address); }
    }

    PlaceCache(File file) { this.file = file; load(); }
    static PlaceCache open(Context c) { return new PlaceCache(new File(c.getFilesDir(), "places-cache.json")); }

    private synchronized void load() {
        entries.clear();
        if (!file.isFile()) return;
        try (InputStream in = new FileInputStream(file)) {
            JSONArray a = new JSONArray(AreaStore.readAll(in));
            for (int i = 0; i < a.length(); i++) { JSONObject o = a.getJSONObject(i); entries.add(new Entry(o.optString("name"), o.optString("address"), o.optDouble("lat"), o.optDouble("lon"))); }
        } catch (Exception ignored) {}
    }

    /** Keep these places, newest first; the same name within 150 m is stored once. */
    synchronized void add(List<OnlineSearch.Place> found) {
        if (found == null || found.isEmpty()) return;
        ArrayList<Entry> next = new ArrayList<>();
        for (OnlineSearch.Place p : found) next.add(new Entry(p.name, p.address, p.lat, p.lon));
        for (Entry e : entries) if (next.size() < MAX && !contains(next, e)) next.add(e);
        entries.clear(); entries.addAll(next);
        save();
    }
    private static boolean contains(List<Entry> list, Entry e) {
        for (Entry x : list) if (x.name.equals(e.name) && GeoMath.distance(x.lat, x.lon, e.lat, e.lon) < SearchIndex.SAME_PLACE_METERS) return true;
        return false;
    }
    private void save() {
        JSONArray a = new JSONArray();
        try { for (Entry e : entries) a.put(new JSONObject().put("name", e.name).put("address", e.address).put("lat", e.lat).put("lon", e.lon)); } catch (JSONException ignored) {}
        File tmp = new File(file.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) { out.write(a.toString().getBytes(StandardCharsets.UTF_8)); } catch (IOException e) { return; }
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file); }
    }

    /** Every word in the name or street line, nearest first. */
    synchronized List<Entry> search(String text, double fromLat, double fromLon, int limit) {
        String q = SearchIndex.fold(text); ArrayList<Entry> out = new ArrayList<>(); if (q.isEmpty()) return out;
        String[] words = q.split(" ");
        for (Entry e : entries) { boolean all = true; for (String w : words) if (!e.folded.contains(w)) { all = false; break; } if (all) out.add(e); }
        out.sort(Comparator.comparingDouble(e -> GeoMath.distance(fromLat, fromLon, e.lat, e.lon)));
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }
}
