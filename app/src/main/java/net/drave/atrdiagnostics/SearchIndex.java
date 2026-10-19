package net.drave.atrdiagnostics;

import java.util.*;

/**
 * Offline place search over the installed region's place names. Every word typed must appear in the name.
 * Names that start with the query rank first, then names with a word starting with it, then other matches;
 * ties go to the nearest place. Duplicate names keep their first entry, as the old search box did.
 */
public final class SearchIndex {
    public final String[] names; public final double[] lat, lon; private final String[] folded;

    public SearchIndex(List<String> names, double[] lat, double[] lon) {
        LinkedHashMap<String,Integer> seen = new LinkedHashMap<>();
        for (int i = 0; i < names.size(); i++) if (names.get(i) != null && !names.get(i).trim().isEmpty() && !seen.containsKey(names.get(i))) seen.put(names.get(i), i);
        int n = seen.size(); this.names = new String[n]; this.lat = new double[n]; this.lon = new double[n]; this.folded = new String[n];
        int k = 0;
        for (Map.Entry<String,Integer> e : seen.entrySet()) { this.names[k] = e.getKey(); this.lat[k] = lat[e.getValue()]; this.lon[k] = lon[e.getValue()]; this.folded[k] = fold(e.getKey()); k++; }
    }

    public static String fold(String s) {
        String n = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return n.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]+", " ").replaceAll(" +", " ").trim();
    }

    /** Returns indexes into {@link #names}, best first. */
    public int[] query(String text, double fromLat, double fromLon, int limit) {
        String q = fold(text); if (q.isEmpty()) return new int[0];
        String[] words = q.split(" ");
        ArrayList<long[]> hits = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            String f = folded[i]; boolean all = true;
            for (String w : words) if (!f.contains(w)) { all = false; break; }
            if (!all) continue;
            int rank = f.startsWith(q) ? 0 : (f.contains(" " + words[0]) ? 1 : 2);
            long meters = Math.round(GeoMath.distance(fromLat, fromLon, lat[i], lon[i]));
            hits.add(new long[]{rank, meters, i});
        }
        hits.sort((a, b) -> a[0] != b[0] ? Long.compare(a[0], b[0]) : Long.compare(a[1], b[1]));
        int[] out = new int[Math.min(limit, hits.size())];
        for (int i = 0; i < out.length; i++) out[i] = (int)hits.get(i)[2];
        return out;
    }
}
