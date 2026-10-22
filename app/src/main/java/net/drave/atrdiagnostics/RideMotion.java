package net.drave.atrdiagnostics;

import java.util.List;

/**
 * Where to draw the rider, frame by frame, shared by the phone map and the bike display. On a route the arrow moves
 * along the road at the current speed between GPS fixes, like car navigation, so it never trails the real position
 * and the route line starts right at it. Off a route it glides from one fix to the next.
 */
final class RideMotion {
    private final NavSession s;
    double lat, lon, speed; float bearing; int routeFrom;
    private long lastFix = -1, poseAt; private double fromLat, fromLon, toLat, toLon; private float fromBearing, toBearing;
    private double along = -1, alongShown = -1; private long alongAt; private List<double[]> alongRoute;

    RideMotion(NavSession s) { this.s = s; }

    /** Advance to {@code now} (elapsedRealtime); {@code dtMs} is the time since the previous frame. */
    void update(long now, float dtMs) {
        if (followRoute(now, dtMs)) return;
        routeFrom = Math.max(0, Math.min(s.progress, Math.max(0, s.shape.size() - 1)));
        if (s.fixAt != lastFix) {
            boolean first = lastFix == -1 || lat == 0;
            double moved = first ? 0 : GeoMath.distance(toLat, toLon, s.viewLat, s.viewLon); long dt = Math.max(1, now - poseAt);
            if (!first && dt < 5000) speed = speed * 0.6 + Math.min(40, moved / (dt / 1000.0)) * 0.4;
            fromLat = first ? s.viewLat : lat; fromLon = first ? s.viewLon : lon; fromBearing = first ? s.viewBearing : bearing;
            toLat = s.viewLat; toLon = s.viewLon; toBearing = s.viewBearing; poseAt = now; lastFix = s.fixAt;
        }
        float f = Math.min(1f, (now - poseAt) / 1000f);
        lat = fromLat + (toLat - fromLat) * f; lon = fromLon + (toLon - fromLon) * f;
        float turn = ((toBearing - fromBearing + 540) % 360) - 180; bearing = (fromBearing + turn * f + 360) % 360;
    }

    private boolean followRoute(long now, float dtMs) {
        List<double[]> route = s.shape; double[] cum = s.cumulative;
        if (!s.active || !s.snapped || route.size() < 2 || cum.length != route.size()) { alongShown = -1; return false; }
        if (route != alongRoute) { alongRoute = route; along = -1; alongShown = -1; }
        if (s.fixAt != lastFix) {
            double a = alongOf(route, cum, s.viewLat, s.viewLon); long dt = Math.max(1, now - alongAt);
            if (along >= 0 && dt < 5000) speed = speed * 0.5 + Math.max(0, Math.min(45, (a - along) / (dt / 1000.0))) * 0.5;
            along = a; alongAt = now; lastFix = s.fixAt;
        }
        if (along < 0) return false;
        double predicted = Math.min(cum[cum.length - 1], along + speed * Math.min(1.5, (now - alongAt) / 1000.0));
        if (alongShown < 0 || Math.abs(predicted - alongShown) > 120) alongShown = predicted;
        // A few metres behind the arrow means the rider braked or GPS wobbled back: creep back slowly instead of sliding back.
        else if (predicted < alongShown && alongShown - predicted < 40) alongShown += (predicted - alongShown) * Math.min(1f, dtMs / 3000f);
        else alongShown += (predicted - alongShown) * Math.min(1f, dtMs / 250f);
        int i = segmentAt(cum, alongShown); routeFrom = i;
        // The map turns for real bends only: it faces a point 40 m ahead, and wobbles of under 2 degrees in the road's line
        // are ignored. Turning at every small kink made the bike picture rotate constantly, which looked restless and
        // needed about a fifth more frames (measured), so more work for the phone.
        double[] here = pointAt(route, cum, i, alongShown), ahead = pointAt(route, cum, segmentAt(cum, alongShown + 40), alongShown + 40);
        lat = here[0]; lon = here[1];
        float want = here[0] == ahead[0] && here[1] == ahead[1] ? bearing : GeoMath.bearing(here, ahead);
        float turn = ((want - bearing + 540) % 360) - 180;
        if (Math.abs(turn) < 2f) turn = 0;
        bearing = (bearing + turn * Math.min(1f, dtMs / 300f) + 360) % 360;
        return true;
    }
    static int segmentAt(double[] cum, double d) { int lo = 0, hi = cum.length - 2; while (lo < hi) { int mid = (lo + hi + 1) / 2; if (cum[mid] <= d) lo = mid; else hi = mid - 1; } return Math.max(0, lo); }
    static double[] pointAt(List<double[]> route, double[] cum, int i, double d) {
        double[] a = route.get(i), b = route.get(Math.min(i + 1, route.size() - 1)); double len = cum[Math.min(i + 1, cum.length - 1)] - cum[i];
        double t = len <= 0 ? 0 : Math.max(0, Math.min(1, (d - cum[i]) / len)); return new double[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t};
    }
    private double alongOf(List<double[]> route, double[] cum, double la, double lo) {
        int best = Math.max(0, Math.min(s.progress, route.size() - 2)); double bestD = Double.MAX_VALUE;
        for (int i = Math.max(0, s.progress - 4); i < Math.min(route.size() - 1, s.progress + 5); i++) { double d = GeoMath.segmentDistance(la, lo, route.get(i), route.get(i + 1)); if (d < bestD) { bestD = d; best = i; } }
        return cum[best] + GeoMath.distance(route.get(best)[0], route.get(best)[1], la, lo);
    }
}
