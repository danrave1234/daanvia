package net.drave.atrdiagnostics;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Tells the branches of a chain apart. OpenStreetMap names nearly every branch just "Jollibee", so a list of forty
 * Jollibees, a saved place or a Recent entry says nothing about which one. Each branch is named after the road it is
 * on and its town instead, from the downloaded area's own roads: "Jollibee · EDSA, Makati".
 */
final class Branches {
    private Branches() {}
    /** Roads too small to name a branch after. */
    private static final Set<String> MINOR = new HashSet<>(Arrays.asList("footway", "path", "steps", "pedestrian", "cycleway", "bridleway", "corridor", "platform",
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
        double pad = ROAD_METERS / 111000.0, padLon = pad / Math.max(0.2, Math.cos(Math.toRadians(lat)));
        List<NavSession.Road> near = roads.near(lat - pad, lon - padLon, lat + pad, lon + padLon);
        String own = SearchIndex.fold(name), best = null; double bestD = ROAD_METERS;
        for (NavSession.Road r : near) {
            if (r.name.isEmpty() || MINOR.contains(r.kind) || SearchIndex.fold(r.name).contains(own)) continue;
            for (int i = 0; i + 1 < r.size(); i++) {
                double d = GeoMath.segmentDistance(lat, lon, new double[]{r.lat(i), r.lon(i)}, new double[]{r.lat(i + 1), r.lon(i + 1)});
                if (d < bestD) { bestD = d; best = r.name; }
            }
        }
        return best;
    }

    /** The nearest named street within {@code meters}, or null: used to tell unnamed parking lots apart. */
    static String street(double lat, double lon, RoadTiles.Index roads, double meters) {
        if (roads == null) return null;
        double pad = meters / 111000.0, padLon = pad / Math.max(0.2, Math.cos(Math.toRadians(lat)));
        String best = null; double bestD = meters;
        for (NavSession.Road r : roads.near(lat - pad, lon - padLon, lat + pad, lon + padLon)) {
            if (r.name.isEmpty() || MINOR.contains(r.kind)) continue;
            for (int i = 0; i + 1 < r.size(); i++) {
                double d = GeoMath.segmentDistance(lat, lon, new double[]{r.lat(i), r.lon(i)}, new double[]{r.lat(i + 1), r.lon(i + 1)});
                if (d < bestD) { bestD = d; best = r.name; }
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
