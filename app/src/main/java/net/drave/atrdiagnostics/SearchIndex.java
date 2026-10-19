package net.drave.atrdiagnostics;

import java.util.*;

/**
 * Offline place search over the installed region's place names. Every word typed must appear in the name.
 * Names that start with the query rank first, then names with a word starting with it, then other matches;
 * ties go to the nearest place. Every branch of a chain is kept; only the same name within 150 m counts once.
 * When no name has every word, the search reads the query as a place plus the area it is in, like
 * "chowking guadalupe": Chowking branches within 2 km of a place called Guadalupe, nearest first.
 */
public final class SearchIndex {
    public final String[] names; public final double[] lat, lon; private final String[] folded;
    static final double NEAR_METERS = 2000, SAME_PLACE_METERS = 150;

    /** A result: an index into {@link #names}, and for "place in area" matches the area it was found near. */
    public static final class Hit { public final int i; public final String near; Hit(int i, String near) { this.i = i; this.near = near; } }

    public SearchIndex(List<String> names, double[] lat, double[] lon) {
        HashMap<String, List<Integer>> kept = new HashMap<>(); ArrayList<Integer> order = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            String n = names.get(i); if (n == null || n.trim().isEmpty()) continue;
            List<Integer> same = kept.computeIfAbsent(n, x -> new ArrayList<>()); boolean dup = false;
            for (int j : same) if (GeoMath.distance(lat[j], lon[j], lat[i], lon[i]) < SAME_PLACE_METERS) { dup = true; break; }
            if (dup) continue;
            same.add(i); order.add(i);
        }
        int n = order.size(); this.names = new String[n]; this.lat = new double[n]; this.lon = new double[n]; this.folded = new String[n];
        for (int k = 0; k < n; k++) { int i = order.get(k); this.names[k] = names.get(i); this.lat[k] = lat[i]; this.lon[k] = lon[i]; this.folded[k] = fold(names.get(i)); copies.merge(this.folded[k], 1, Integer::sum); }
    }

    private final HashMap<String, Integer> copies = new HashMap<>();
    /** How many places in the area have this name: more than one means a chain whose branches need telling apart (see Branches). */
    public int copies(String name) { Integer n = copies.get(fold(name)); return n == null ? 0 : n; }

    public static String fold(String s) {
        String n = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return n.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]+", " ").replaceAll(" +", " ").trim();
    }

    /**
     * For each result, the result it is part of: a place whose name it starts or ends with ("SM City Cebu PUJ
     * Terminal", "Botejyu SM City Cebu" → "SM City Cebu") within 800 m. The shortest such name wins; -1 for main places.
     */
    public static int[] parents(String[] names, double[] lat, double[] lon) {
        int[] parent = new int[names.length]; Arrays.fill(parent, -1);
        Integer[] byLength = new Integer[names.length]; for (int i = 0; i < names.length; i++) byLength[i] = i;
        Arrays.sort(byLength, (a, b) -> Integer.compare(names[a].length(), names[b].length()));
        for (int n = 0; n < names.length; n++) {
            String b = names[n].toLowerCase(Locale.ROOT);
            for (int m : byLength) {
                if (m == n || parent[m] != -1 || names[m].length() >= names[n].length()) continue;
                String a = names[m].toLowerCase(Locale.ROOT);
                if ((b.startsWith(a + " ") || b.endsWith(" " + a)) && GeoMath.distance(lat[m], lon[m], lat[n], lon[n]) < 800) { parent[n] = m; break; }
            }
        }
        for (int n = 0; n < names.length; n++) if (parent[n] != -1 && parent[parent[n]] != -1) parent[n] = parent[parent[n]];
        return parent;
    }
    /** Display order: each main place where its best-ranked member first appears, followed by its parts in rank order. */
    public static int[] grouped(int[] parent) {
        int[] out = new int[parent.length]; boolean[] done = new boolean[parent.length]; int k = 0;
        for (int n = 0; n < parent.length; n++) {
            int root = parent[n] == -1 ? n : parent[n];
            if (done[root]) continue;
            done[root] = true; out[k++] = root;
            for (int c = 0; c < parent.length; c++) if (parent[c] == root && !done[c]) { done[c] = true; out[k++] = c; }
        }
        return out;
    }

    /** Returns indexes into {@link #names}, best first. */
    public int[] query(String text, double fromLat, double fromLon, int limit) {
        List<Hit> hits = search(text, fromLat, fromLon, limit); int[] out = new int[hits.size()];
        for (int i = 0; i < out.length; i++) out[i] = hits.get(i).i;
        return out;
    }

    public List<Hit> search(String text, double fromLat, double fromLon, int limit) {
        String q = fold(text); ArrayList<Hit> out = new ArrayList<>(); if (q.isEmpty()) return out;
        String[] words = q.split(" ");
        ArrayList<long[]> hits = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            if (!containsAll(folded[i], words)) continue;
            int rank = folded[i].startsWith(q) ? 0 : (folded[i].contains(" " + words[0]) ? 1 : 2);
            hits.add(new long[]{rank, Math.round(GeoMath.distance(fromLat, fromLon, lat[i], lon[i])), i});
        }
        hits.sort((a, b) -> a[0] != b[0] ? Long.compare(a[0], b[0]) : Long.compare(a[1], b[1]));
        for (int i = 0; i < hits.size() && out.size() < limit; i++) out.add(new Hit((int)hits.get(i)[2], null));
        if (out.isEmpty() && words.length >= 2) near(words, fromLat, fromLon, limit, out);
        return out;
    }

    /**
     * "chowking guadalupe" or "guadalupe chowking": try each split into what is wanted and the area it is in. Of the
     * two readings of a split, the area is the side that names fewer places: a chain has many branches, a barangay one.
     */
    private void near(String[] words, double fromLat, double fromLon, int limit, List<Hit> out) {
        HashMap<Integer, double[]> best = new HashMap<>(); HashMap<Integer, Integer> anchorOf = new HashMap<>();
        for (int cut = words.length - 1; cut >= 1; cut--) {
            String[] head = Arrays.copyOfRange(words, 0, cut), tail = Arrays.copyOfRange(words, cut, words.length);
            List<Integer> inTail = anchors(tail), inHead = anchors(head);
            if (!inTail.isEmpty() && (inHead.isEmpty() || inTail.size() <= inHead.size())) near(head, inTail, fromLat, fromLon, best, anchorOf);
            else if (!inHead.isEmpty()) near(tail, inHead, fromLat, fromLon, best, anchorOf);
        }
        ArrayList<Integer> found = new ArrayList<>(best.keySet());
        found.sort((a, b) -> Double.compare(best.get(a)[0], best.get(b)[0]));
        for (int i = 0; i < found.size() && out.size() < limit; i++) out.add(new Hit(found.get(i), names[anchorOf.get(found.get(i))]));
    }
    /** Places named exactly the area, else places with a name word starting each area word. */
    private List<Integer> anchors(String[] where) {
        String phrase = String.join(" ", where);
        ArrayList<Integer> exact = new ArrayList<>(), partial = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            if (folded[i].equals(phrase)) exact.add(i);
            else if (exact.isEmpty() && startsWords(folded[i], where)) partial.add(i);
        }
        return exact.isEmpty() ? partial : exact;
    }
    private void near(String[] what, List<Integer> anchors, double fromLat, double fromLon, Map<Integer, double[]> best, Map<Integer, Integer> anchorOf) {
        if (anchors.size() > 80) { anchors = new ArrayList<>(anchors); anchors.sort(Comparator.comparingDouble(a -> GeoMath.distance(fromLat, fromLon, lat[a], lon[a]))); anchors = anchors.subList(0, 80); }
        for (int i = 0; i < names.length; i++) {
            if (!containsAll(folded[i], what)) continue;
            for (int a : anchors) {
                if (a == i) continue;
                double d = GeoMath.distance(lat[a], lon[a], lat[i], lon[i]);
                if (d > NEAR_METERS) continue;
                double[] b = best.get(i);
                if (b == null || d < b[0]) { best.put(i, new double[]{d}); anchorOf.put(i, a); }
            }
        }
    }
    private static boolean containsAll(String folded, String[] words) { for (String w : words) if (!folded.contains(w)) return false; return true; }
    /** Every word starts a word of the name: "guadalupe" matches "Puregold Guadalupe" but "lupe" does not. */
    private static boolean startsWords(String folded, String[] words) { String f = " " + folded; for (String w : words) if (!f.contains(" " + w)) return false; return true; }
}
