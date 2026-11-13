package net.drave.atrdiagnostics;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.os.Handler;
import android.os.SystemClock;
import org.json.JSONObject;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.layers.Layer;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.geojson.Feature;
import java.util.*;
import java.util.concurrent.Executor;

import static org.maplibre.android.style.layers.PropertyFactory.*;

/**
 * What the home screen draws on the map itself: the route (and the other route choices), the destination and pin,
 * parking near the destination, the rider's dot, and the street squares under the view. Each piece goes to the map
 * only when it changed: every change makes the map re-process that data.
 */
final class MapPainter {
    interface Host {
        /** The route preview is on screen (asked again when a delayed fit runs). */
        boolean previewing();
        /** The live ride is on screen. */
        boolean riding();
        void startRideFrames();
        boolean rideFramesRunning();
        /** Space the framed route must keep clear of the panels: left, top, right, bottom. */
        int[] routePadding();
    }

    private final Activity a; private final NavSession s; private final Gabi.Kit k; private final Handler ui; private final Executor worker; private final Host host;
    private MapLibreMap map; private Style style; private boolean ready;

    MapPainter(Activity a, NavSession s, Gabi.Kit k, Handler ui, Executor worker, Host host) { this.a = a; this.s = s; this.k = k; this.ui = ui; this.worker = worker; this.host = host; }
    void attach(MapLibreMap m) {
        map = m;
        // Streets for the new view load a moment after the map settles, not between quick swipes (each load costs a frame).
        map.addOnCameraIdleListener(() -> { ui.removeCallbacks(nearRoadsLater); ui.postDelayed(nearRoadsLater, 300); });
        map.addOnCameraMoveStartedListener(reason -> { if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) ui.removeCallbacks(nearRoadsLater); });
    }
    /** HomeActivity installed the layers in this style: from now on there is something to draw into. */
    void ready(Style st) { style = st; ready = true; setUpLocationPuck(); }
    /** The map is gone. */
    void forget() { style = null; ready = false; }
    private GeoJsonSource src(String id) { return style == null ? null : (GeoJsonSource)style.getSource(id); }

    // ---------------------------------------------------------------- route, pins, parking
    private String drawnDest = null, drawnDrop = null;
    private boolean routeCleared; private List<double[]> drawnShape; private List<JSONObject> drawnParking; private Gabi.Palette drawnParkingPalette;
    private List<RoutePlan> drawnChoices; private int drawnChoice = -1;
    /** Draw the route again (and frame it) the next time the preview paints. */
    void redrawRoute() { drawnShape = null; }
    void repaintParking() { drawnParking = null; }

    /**
     * Bring the map up to date with the screen: {@code preview} and {@code ride} say which route screen is on, the
     * destination is the one being previewed, and {@code pin} is a dropped pin or open parking lot, null for none.
     */
    void paint(boolean preview, boolean ride, String destName, double destLat, double destLon, double[] pin) {
        if (!ready || map == null) return;
        boolean routeMode = preview || ride;
        List<double[]> shape = s.shape;
        boolean showRoute = routeMode && !shape.isEmpty() && (ride || (destName != null && destName.equals(s.destination)));
        List<RoutePlan> choices = showRoute && preview ? s.routeChoices : Collections.emptyList();
        if (choices != drawnChoices || drawnChoice != s.selectedRoute) {
            drawnChoices = choices; drawnChoice = s.selectedRoute; ArrayList<Feature> other = new ArrayList<>();
            for (int i = 0; i < choices.size(); i++) if (i != s.selectedRoute) { List<double[]> line = choices.get(i).shape; Feature f = Feature.fromGeometry(MapStyle.line(line, 0, line.size())); f.addNumberProperty("choice", i); other.add(f); }
            src(MapStyle.ALTERNATIVES).setGeoJson(org.maplibre.geojson.FeatureCollection.fromFeatures(other));
        }
        GeoJsonSource route = src(MapStyle.ROUTE);
        if (!showRoute) {
            if (!routeCleared) { route.setGeoJson(MapStyle.empty()); MapStyle.routeProgress(style, 0, 1); routeCleared = true; }
            drawnShape = null;
        } else if (preview && shape != drawnShape) { routeCleared = false; drawnShape = shape; drawRouteIn(shape); ui.postDelayed(() -> { if (drawnShape == shape) fitRoute(shape, destLat, destLon); }, 120); }
        else if (ride && !host.rideFramesRunning()) paintRideRoute(shape, Math.min(s.progress, shape.size() - 1), s.viewLat, s.viewLon);
        // destination / pin
        GeoJsonSource dest = src(MapStyle.DEST), drop = src(MapStyle.DROP);
        String destKey = routeMode && destName != null || ride ? (ride ? s.destLat + "," + s.destLon : destLat + "," + destLon) : "";
        if (!destKey.equals(drawnDest)) { drawnDest = destKey; if (destKey.isEmpty()) dest.setGeoJson(MapStyle.empty()); else dest.setGeoJson(ride ? MapStyle.point(s.destLat, s.destLon) : MapStyle.point(destLat, destLon)); }
        String dropKey = pin == null ? "" : pin[0] + "," + pin[1];
        if (!dropKey.equals(drawnDrop)) { drawnDrop = dropKey; if (pin == null) drop.setGeoJson(MapStyle.empty()); else drop.setGeoJson(MapStyle.point(pin[0], pin[1])); }
        // parking near the destination, before the ride and in the last kilometre
        Gabi.Palette palette = k.t.palette;
        List<JSONObject> lots = (showRoute && (preview || s.remaining < 1000)) ? s.nearbyParking : Collections.emptyList();
        if (lots != drawnParking || drawnParkingPalette != palette) {
            drawnParking = lots; drawnParkingPalette = palette;
            src(MapStyle.PARKING).setGeoJson(lots.isEmpty() ? MapStyle.empty() : MapStyle.parking(a, style, palette, lots, 8));
            Layer l = style.getLayer("parking");
            if (l != null && !lots.isEmpty()) { ValueAnimator an = ValueAnimator.ofFloat(0, 1); an.setDuration(520); an.setInterpolator(new android.view.animation.OvershootInterpolator(1.6f));
                an.addUpdateListener(x -> { float v = (float)x.getAnimatedValue(); l.setProperties(iconOpacity(Math.min(1f, v * 1.4f)), iconTranslate(new Float[]{0f, (1 - v) * -k.dp(14)})); }); an.start(); }
        }
        paintPuck();
    }

    /** A new route goes to the map once and fades in (see MapStyle.routeFadeIn). */
    private void drawRouteIn(List<double[]> shape) {
        GeoJsonSource route = src(MapStyle.ROUTE); if (route == null) return;
        MapStyle.routeProgress(style, 0, 1); MapStyle.routeFadeIn(style, ui);
        route.setGeoJson(Feature.fromGeometry(MapStyle.line(shape, 0, shape.size())));
    }
    /** How far along the route a point on it is, as a fraction of its length. */
    private float routeFraction(List<double[]> shape, int from, double lat, double lon) {
        double[] cum = s.cumulative; if (cum.length != shape.size() || shape.size() < 2 || cum[cum.length - 1] <= 0) return 0;
        from = Math.max(0, Math.min(from, shape.size() - 1));
        return (float)((cum[from] + GeoMath.distance(shape.get(from)[0], shape.get(from)[1], lat, lon)) / cum[cum.length - 1]);
    }
    /** Ride: the route goes to the map when it changes (a reroute); otherwise only how much of it is hidden as ridden. */
    private void paintRideRoute(List<double[]> shape, int from, double lat, double lon) {
        GeoJsonSource route = src(MapStyle.ROUTE); if (route == null || style == null) return;
        if (shape != drawnShape) { drawnShape = shape; route.setGeoJson(Feature.fromGeometry(MapStyle.line(shape, 0, shape.size()))); }
        routeCleared = false; MapStyle.routeProgress(style, routeFraction(shape, from, lat, lon), 1);
    }
    /** Frame the route (and, while choosing, every route choice) and the destination clear of the panels. */
    void fitRoute(List<double[]> shape, double destLat, double destLon) {
        // Four numbers instead of a LatLng per route point (tens of thousands on a long route).
        double s0 = destLat, n0 = destLat, w0 = destLon, e0 = destLon;
        for (double[] p : shape) { s0 = Math.min(s0, p[0]); n0 = Math.max(n0, p[0]); w0 = Math.min(w0, p[1]); e0 = Math.max(e0, p[1]); }
        if (host.previewing()) for (RoutePlan option : s.routeChoices) for (double[] p : option.shape) { s0 = Math.min(s0, p[0]); n0 = Math.max(n0, p[0]); w0 = Math.min(w0, p[1]); e0 = Math.max(e0, p[1]); }
        LatLngBounds.Builder b = new LatLngBounds.Builder().include(new LatLng(s0, w0)).include(new LatLng(n0, e0));
        int[] pad = host.routePadding();
        try { map.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), pad[0], pad[1], pad[2], pad[3]), 1100); } catch (Exception ignored) {}
    }
    /** Every frame of a ride (RideScreen): the street squares once a second, the ridden part of the route ten times a second. */
    void onRideFrame(long now, double lat, double lon, int routeFrom) {
        if (now - nearAt > 1000) { nearAt = now; updateNearRoads(); }
        // The ridden part of the route disappears right up to the arrow (paint only).
        List<double[]> shape = s.shape;
        if ((now - routeSplitAt > 100 || shape != drawnShape) && shape.size() > 1) { routeSplitAt = now; paintRideRoute(shape, Math.min(routeFrom, shape.size() - 1), lat, lon); }
    }
    private long routeSplitAt;

    // ---------------------------------------------------------------- the rider's dot
    // Outside rides the dot is MapLibre's own location puck: it glides between fixes and turns its beam with the
    // phone's compass inside the map renderer, every frame, with no map data changing hands. (Doing that by hand
    // with a GeoJSON point updated many times a second was what made the dot and the map stutter.)
    private org.maplibre.android.location.LocationComponent locPuck; private boolean locPuckOn, puckArrow; private long lastFix = -1;
    /** Hand the dot the latest fix on the next paint even if it hasn't changed (after a ride, or the locate button). */
    void puckStale() { lastFix = -1; }
    private void setUpLocationPuck() {
        try {
            org.maplibre.android.location.LocationComponentOptions o = org.maplibre.android.location.LocationComponentOptions.builder(a)
                .foregroundDrawable(R.drawable.puck_dot).foregroundDrawableStale(R.drawable.puck_dot).bearingDrawable(R.drawable.puck_beam)
                .gpsDrawable(R.drawable.puck_arrow)
                .backgroundDrawable(R.drawable.puck_none).backgroundDrawableStale(R.drawable.puck_none)
                .accuracyColor(0xFFFFB020).accuracyAlpha(0.12f).accuracyAnimationEnabled(true).compassAnimationEnabled(true).enableStaleState(false).pulseEnabled(false)
                .trackingGesturesManagement(false).minZoomIconScale(0.85f).maxZoomIconScale(1f).elevation(0f).build();
            locPuck = map.getLocationComponent();
            locPuck.activateLocationComponent(new org.maplibre.android.location.LocationComponentActivationOptions.Builder(a, style)
                .locationComponentOptions(o).useDefaultLocationEngine(false).useSpecializedLocationLayer(true).build());
            locPuck.setMaxAnimationFps(60);
        } catch (Exception e) { locPuck = null; android.util.Log.w("Daanvia", "Location puck unavailable", e); }
    }
    @android.annotation.SuppressLint("MissingPermission") // only switched on once location is allowed
    void showLocationPuck(boolean on) {
        if (locPuck == null || on == locPuckOn || on && !Here.allowed(a)) return;
        try {
            locPuck.setLocationComponentEnabled(on); locPuckOn = on;
            if (on) { locPuck.setCameraMode(org.maplibre.android.location.modes.CameraMode.NONE); puckArrow = false; locPuck.setRenderMode(org.maplibre.android.location.modes.RenderMode.COMPASS); }
        } catch (Exception e) { android.util.Log.w("Daanvia", "Location puck", e); }
    }
    private android.location.Location puckLocation() {
        android.location.Location l = new android.location.Location("daanvia");
        l.setLatitude(s.viewLat); l.setLongitude(s.viewLon); l.setAccuracy(Math.max(1f, Math.min(s.accuracy, 2000f)));
        l.setTime(System.currentTimeMillis()); l.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos()); l.setBearing(s.viewBearing);
        return l;
    }
    private final Runnable puckCheck = this::paintPuck;
    private void paintPuck() {
        if (style == null) return;
        boolean visible = s.fixAt > 0 || s.active;
        if (!visible) { showLocationPuck(false); return; }
        if (host.riding() && s.active) { showLocationPuck(false); host.startRideFrames(); return; }
        if (locPuck == null) return;
        boolean was = locPuckOn; showLocationPuck(true);
        // Moving: an arrow pointing the way the rider goes. Standing: the dot, its beam turning with the phone's compass.
        boolean arrow = s.speedMetersPerSecond > 2 && SystemClock.elapsedRealtime() - s.fixAt < 6000;
        // Fixes stopping (a tunnel, indoors) bring no refresh, so the arrow checks again once it would be out of date.
        if (arrow) { ui.removeCallbacks(puckCheck); ui.postDelayed(puckCheck, 6500); }
        if (locPuckOn && arrow != puckArrow) { puckArrow = arrow; try { locPuck.setRenderMode(arrow ? org.maplibre.android.location.modes.RenderMode.GPS : org.maplibre.android.location.modes.RenderMode.COMPASS); } catch (Exception ignored) {} }
        if (s.fixAt == lastFix && was) return;
        lastFix = s.fixAt;
        if (locPuckOn) locPuck.forceLocationUpdate(puckLocation());
    }

    // ---------------------------------------------------------------- street squares
    // Only the streets on screen go to the map (see RoadTiles): the squares under the view plus a margin, swapped as the
    // rider pans or rides. Below zoom 13.5 small streets aren't drawn, so none are loaded.
    private String nearKey = ""; private long nearAt; private int shownRoads = -1;
    private final Runnable nearRoadsLater = this::updateNearRoads;
    /** Point the roads layer at the active area's file again and reload the streets on screen. */
    void loadRoads() { MapStyle.roads(src(MapStyle.ROADS), s.roadsUri); shownRoads = s.roadsVersion; nearKey = ""; updateNearRoads(); }
    /** The area's roads changed (rebuilt street squares, an update): load them. */
    void checkRoads() { if (ready && shownRoads != s.roadsVersion) loadRoads(); }
    void updateNearRoads() {
        if (map == null || !ready) return;
        GeoJsonSource near = src(MapStyle.ROADS_NEAR); java.io.File tiles = s.roadTiles; if (near == null) return;
        if (tiles == null || map.getCameraPosition().zoom < 13.5) { if (!nearKey.equals("none")) { nearKey = "none"; near.setGeoJson(MapStyle.empty()); } return; }
        LatLngBounds b = map.getProjection().getVisibleRegion().latLngBounds;
        double padLat = (b.getLatNorth() - b.getLatSouth()) * 0.25, padLon = (b.getLonEast() - b.getLonWest()) * 0.25;
        double s0 = b.getLatSouth() - padLat, n0 = b.getLatNorth() + padLat, w0 = b.getLonWest() - padLon, e0 = b.getLonEast() + padLon;
        String key = tiles.getPath() + "|" + RoadTiles.cell(w0) + "," + RoadTiles.cell(s0) + "," + RoadTiles.cell(e0) + "," + RoadTiles.cell(n0);
        if (key.equals(nearKey)) return; nearKey = key;
        worker.execute(() -> {
            String json = RoadTiles.collection(tiles, s0, w0, n0, e0, 120);
            ui.post(() -> { if (key.equals(nearKey) && json != null && !a.isDestroyed()) { GeoJsonSource src = src(MapStyle.ROADS_NEAR); if (src != null) src.setGeoJson(json); } });
        });
    }
}
