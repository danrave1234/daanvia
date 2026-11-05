package net.drave.atrdiagnostics;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Tells the branches of a chain apart. OpenStreetMap names nearly every branch just "Jollibee", so a list of forty
 * Jollibees, a saved place or a Recent entry says nothing about which one. Each branch is named after the road it is
 * on and its town instead, from the downloaded area's own roads: "Jollibee · EDSA, Makati".
 */
final class Branches {
    private Branches() {}
    /** Roads too small to name a place, or the rider's street, after. */
    static final Set<String> MINOR = new HashSet<>(Arrays.asList("footway", "path", "steps", "pedestrian", "cycleway", "bridleway", "corridor", "platform",
        "service", "track", "elevator", "construction", "proposed"));
    /** How far a branch can be from the road it is named after, and from its town's centre. */
    static final double ROAD_METERS = 70, TOWN_METERS = 9000;

    /** "Jollibee · EDSA, Makati"; the plain name when neither a road nor a town is near. */
    static String label(String name, double lat, double lon, RoadTiles.Index roads, SearchIndex towns) {
        String road = roads == null ? null : road(name, lat, lon, roads), town = towns == null ? null : town(lat, lon, towns);
        if (road == null && town == null) return name;
        return name + " · " + (road == null ? town : town == null ? road : road + ", " + town);
    }

    /** The name of the nearest named street within {@link #ROAD_METERS}, not counting ones named after the place itself (its drive-thru). */
    static String road(String name, double lat, double lon, RoadTiles.Index roads) {
        return nearestNamed(lat, lon, roads, ROAD_METERS, Float.NaN, SearchIndex.fold(name));
    }

    /** The nearest named street within {@code meters}, or null: used to tell unnamed parking lots apart. */
    static String street(double lat, double lon, RoadTiles.Index roads, double meters) {
        return roads == null ? null : nearestNamed(lat, lon, roads, meters, Float.NaN, null);
    }

    /**
     * The nearest named, non-minor road within {@code meters}, or null. With a {@code heading} (not NaN), only stretches
     * running that way or the opposite way count, so a cross street at a junction doesn't win for a moment. Roads whose
     * folded name contains {@code exclude} are skipped (a place's own drive-thru).
     */
    static String nearestNamed(double lat, double lon, RoadTiles.Index roads, double meters, float heading, String exclude) {
        String best = null; double bestD = meters;
        for (NavSession.Road r : roads.near(lat, lon, meters)) {
            if (r.name.isEmpty() || MINOR.contains(r.kind) || exclude != null && SearchIndex.fold(r.name).contains(exclude)) continue;
            for (int i = 0; i + 1 < r.size(); i++) {
                double[] p = {r.lat(i), r.lon(i)}, q = {r.lat(i + 1), r.lon(i + 1)};
                double d = GeoMath.segmentDistance(lat, lon, p, q); if (d >= bestD) continue;
                if (!Float.isNaN(heading)) { double off = GeoMath.angleBetween(GeoMath.bearing(p, q), heading); if (off > 40 && off < 140) continue; }
                bestD = d; best = r.name;
            }
        }
        return best;
    }

    /** The nearest city or town centre within {@link #TOWN_METERS}. */
    static String town(double lat, double lon, SearchIndex towns) {
        String best = null; double bestD = TOWN_METERS;
        for (int i = 0; i < towns.names.length; i++) {
            if (Math.abs(towns.lat[i] - lat) > 0.1 || Math.abs(towns.lon[i] - lon) > 0.1) continue;
            double d = GeoMath.distance(lat, lon, towns.lat[i], towns.lon[i]); if (d < bestD) { bestD = d; best = towns.names[i]; }
        }
        return best;
    }
}
