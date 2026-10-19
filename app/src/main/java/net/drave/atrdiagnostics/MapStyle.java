package net.drave.atrdiagnostics;

import android.content.Context;
import android.graphics.*;
import org.json.JSONObject;
import org.maplibre.android.style.expressions.Expression;
import org.maplibre.android.style.layers.*;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.android.style.sources.VectorSource;
import org.maplibre.android.style.sources.GeoJsonOptions;
import org.maplibre.android.maps.Style;
import org.maplibre.geojson.*;
import org.maplibre.geojson.Point;
import java.util.*;

import static org.maplibre.android.style.expressions.Expression.*;
import static org.maplibre.android.style.layers.PropertyFactory.*;

/**
 * Gabi map style. Roads, coastline and parking come from the downloaded area, so the map works without signal.
 * With signal, OpenFreeMap's OpenStreetMap tiles add what Google Maps shows when you zoom in: buildings, parks,
 * shops and food with their names, street names and neighbourhoods. Tiles already seen stay in MapLibre's cache.
 */
final class MapStyle {
    private MapStyle() {}

    /** OpenFreeMap: free OpenStreetMap vector tiles and fonts, no account or key. */
    static final String ONLINE = "online", ONLINE_TILES = "https://tiles.openfreemap.org/planet", GLYPHS = "https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf";
    static final String[] FONT = {"Noto Sans Regular"}, FONT_BOLD = {"Noto Sans Bold"};
    static final String OVERVIEW = "overview", CELLS = "cells", LAND = "land", WATER = "water", ROADS = "roads", ROUTE = "route", DONE = "route-done", DEST = "dest", DROP = "drop", PARKING = "parking", PUCK = "puck";

    static String base(Gabi.Palette p) {
        return "{\"version\":8,\"glyphs\":\"" + GLYPHS + "\",\"sources\":{},\"layers\":[{\"id\":\"background\",\"type\":\"background\",\"paint\":{\"background-color\":\"" + hex(p.bg) + "\"}}]}";
    }
    private static String hex(int c) { return String.format(Locale.US, "#%06X", c & 0xFFFFFF); }

    private static Expression kinds(String... k) {
        Expression.Stop[] stops = new Expression.Stop[k.length];
        for (int i = 0; i < k.length; i++) stops[i] = stop(k[i], true);
        return match(get("kind"), literal(false), stops);
    }
    private static Expression width(float... zw) {
        Expression.Stop[] stops = new Expression.Stop[zw.length / 2];
        for (int i = 0; i < stops.length; i++) stops[i] = stop(zw[i * 2], zw[i * 2 + 1]);
        return interpolate(exponential(1.6f), zoom(), stops);
    }

    static final String[] PATHS = {"footway", "path", "track", "steps", "pedestrian", "cycleway", "corridor", "bridleway"};
    static final String[] MINOR = {"residential", "unclassified", "service", "living_street", "road"};
    static final String[] TERTIARY = {"tertiary", "tertiary_link"};
    static final String[] SECONDARY = {"secondary", "secondary_link"};
    static final String[] PRIMARY = {"primary", "primary_link", "trunk", "trunk_link", "motorway", "motorway_link"};

    static void install(Context c, Style st, String roadsUri) {
        // Country overview (bundled): land and main roads, so riders can find their area before any map is downloaded.
        st.addSource(new GeoJsonSource(OVERVIEW, new GeoJsonOptions().withMaxZoom(9)));
        st.addLayer(new FillLayer("overview-land", OVERVIEW).withFilter(eq(get("kind"), literal("land"))).withProperties(fillAntialias(true)));
        st.addLayer(new LineLayer("overview-roads", OVERVIEW).withFilter(match(get("kind"), literal(false), stop("motorway", true), stop("trunk", true), stop("primary", true)))
            .withProperties(lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND), lineWidth(width(5, 0.6f, 9, 1.4f, 13, 2.6f))));
        // Coastline: land polygons over a sea-coloured background, then lakes and rivers. Empty until an area provides them.
        st.addSource(new GeoJsonSource(LAND, new GeoJsonOptions().withMaxZoom(12))); st.addSource(new GeoJsonSource(WATER, new GeoJsonOptions().withMaxZoom(12)));
        st.addLayer(new FillLayer("land", LAND).withProperties(fillAntialias(true)));
        st.addLayer(new FillLayer("water-area", WATER).withProperties(fillAntialias(true)));
        st.addSource(new VectorSource(ONLINE, ONLINE_TILES));
        online(st, new FillLayer("online-water", ONLINE).withProperties(fillAntialias(true)), "water", 0);
        online(st, new FillLayer("online-park", ONLINE).withFilter(match(get("class"), literal(false), stop("park", true), stop("nature_reserve", true), stop("wood", true), stop("grass", true), stop("forest", true))).withProperties(fillOpacity(0.55f)), "park", 11);
        online(st, new FillLayer("online-building", ONLINE).withProperties(fillAntialias(true)), "building", 15);
        LineLayer onlineMinor = new LineLayer("online-road-minor", ONLINE).withFilter(match(get("class"), literal(false), stop("minor", true), stop("service", true), stop("track", true)))
            .withProperties(lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND), lineWidth(width(12, 0.4f, 14, 2.2f, 16, 6, 18, 15)));
        online(st, onlineMinor, "transportation", 12);
        LineLayer onlineMajor = new LineLayer("online-road-major", ONLINE).withFilter(match(get("class"), literal(false), stop("motorway", true), stop("trunk", true), stop("primary", true), stop("secondary", true), stop("tertiary", true)))
            .withProperties(lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND), lineWidth(width(8, 0.7f, 14, 4.2f, 16, 9.5f, 18, 23)));
        online(st, onlineMajor, "transportation", 6);
        // A downloaded area's roads are tens of megabytes: tile them only up to zoom 14 and let MapLibre stretch those tiles
        // when zoomed in, instead of re-cutting the whole file at every zoom while the rider pans.
        GeoJsonSource roads = new GeoJsonSource(ROADS, new GeoJsonOptions().withMaxZoom(14).withTolerance(0.45f)); st.addSource(roads); roads(roads, roadsUri);
        LineLayer path = new LineLayer("road-path", ROADS).withFilter(kinds(PATHS)).withProperties(lineWidth(width(15, 0.8f, 18, 2.4f)), lineDasharray(new Float[]{1.2f, 1.6f}));
        path.setMinZoom(15);
        st.addLayer(path);
        addRoad(st, "minor", MINOR, width(11, 0.3f, 14, 2.2f, 16, 6, 18, 15), width(13, 0.6f, 14, 3.4f, 16, 8, 18, 18), 13);
        addRoad(st, "tertiary", TERTIARY, width(10, 0.5f, 14, 3.4f, 16, 8, 18, 19), width(10, 0.9f, 14, 5, 16, 10.5f, 18, 23), 0);
        addRoad(st, "secondary", SECONDARY, width(9, 0.7f, 14, 4.2f, 16, 9.5f, 18, 23), width(9, 1.2f, 14, 6, 16, 12.5f, 18, 27), 0);
        addRoad(st, "primary", PRIMARY, width(7, 0.9f, 14, 5.2f, 16, 11.5f, 18, 27), width(7, 1.4f, 14, 7.4f, 16, 14.5f, 18, 31), 0);

        online(st, new SymbolLayer("online-street-names", ONLINE).withFilter(has("name")).withProperties(symbolPlacement(Property.SYMBOL_PLACEMENT_LINE), textField(get("name")), textFont(FONT),
            textSize(interpolate(linear(), zoom(), stop(13, 10f), stop(17, 13f))), textMaxAngle(30f), textPadding(4f)), "transportation_name", 13);

        st.addSource(new GeoJsonSource(DONE, new GeoJsonOptions().withMaxZoom(16))); st.addSource(new GeoJsonSource(ROUTE, new GeoJsonOptions().withMaxZoom(16)));
        st.addLayer(new LineLayer("route-done", DONE).withProperties(lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND), lineWidth(width(10, 3, 14, 5, 17, 10))));
        st.addLayer(new LineLayer("route-glow", ROUTE).withProperties(lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND), lineBlur(7f), lineWidth(width(10, 12, 14, 18, 17, 34))));
        st.addLayer(new LineLayer("route-case", ROUTE).withProperties(lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND), lineWidth(width(10, 6.5f, 14, 9, 17, 16))));
        st.addLayer(new LineLayer("route-line", ROUTE).withProperties(lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND), lineWidth(width(10, 3.8f, 14, 5.6f, 17, 10.5f))));

        // Shops, food and services: a coloured dot and its name placed together, so a name that doesn't fit takes its dot
        // with it. Better-known places (lower rank) win the space; more appear as the rider zooms in.
        online(st, new SymbolLayer("online-poi", ONLINE).withFilter(all(has("name"), lte(toNumber(get("rank")), literal(60)))).withProperties(
            iconImage("poi-dot"), iconSize(interpolate(linear(), zoom(), stop(15, 0.8f), stop(18, 1.1f))), iconHaloWidth(1.2f),
            textField(get("name")), textFont(FONT), textSize(interpolate(linear(), zoom(), stop(15, 10.5f), stop(18, 12.5f))),
            textAnchor(Property.TEXT_ANCHOR_LEFT), textJustify(Property.TEXT_JUSTIFY_LEFT), textOffset(new Float[]{0.8f, 0f}), textMaxWidth(8f), textPadding(3f),
            symbolSortKey(toNumber(get("rank")))), "poi", 16);
        online(st, new SymbolLayer("online-places", ONLINE).withFilter(match(get("class"), literal(false), stop("city", true), stop("town", true), stop("village", true), stop("suburb", true), stop("quarter", true), stop("neighbourhood", true), stop("hamlet", true)))
            .withProperties(textField(get("name")), textFont(FONT_BOLD), textTransform(Property.TEXT_TRANSFORM_UPPERCASE), textLetterSpacing(0.08f),
                textSize(match(get("class"), literal(11f), stop("city", 15f), stop("town", 13.5f))), textMaxWidth(8f), textPadding(6f)), "place", 10);
        st.addSource(new GeoJsonSource(PARKING));
        st.addLayer(new SymbolLayer("parking", PARKING).withProperties(iconImage(get("icon")), iconAllowOverlap(false), iconIgnorePlacement(false), iconAnchor(Property.ICON_ANCHOR_BOTTOM_LEFT), iconOffset(new Float[]{-14f, 14f})));
        st.addSource(new GeoJsonSource(DEST)); st.addSource(new GeoJsonSource(DROP));
        st.addLayer(new SymbolLayer("dest", DEST).withProperties(iconImage("dest"), iconAllowOverlap(true), iconIgnorePlacement(true), iconAnchor(Property.ICON_ANCHOR_CENTER)));
        st.addLayer(new SymbolLayer("drop", DROP).withProperties(iconImage("drop"), iconAllowOverlap(true), iconIgnorePlacement(true), iconAnchor(Property.ICON_ANCHOR_BOTTOM)));

        // Squares an area download would include, while the rider frames one.
        st.addSource(new GeoJsonSource(CELLS));
        st.addLayer(new FillLayer("cells-fill", CELLS).withProperties(fillOpacity(0.16f)));
        st.addLayer(new LineLayer("cells-line", CELLS).withProperties(lineWidth(1.4f), lineOpacity(0.7f), lineDasharray(new Float[]{2f, 2f})));
        st.addSource(new GeoJsonSource(PUCK));
        // How sure the location is: a circle in metres (r0 is its radius in pixels at zoom 0), hidden once the fix is sharp.
        st.addLayer(new CircleLayer("puck-accuracy", PUCK).withProperties(circleRadius(interpolate(exponential(2f), zoom(), stop(0, product(get("r0"), literal(1f))), stop(22, product(get("r0"), literal(4194304f))))),
            circleOpacity(0.12f), circleStrokeWidth(1f), circleStrokeOpacity(0.45f), circlePitchAlignment(Property.CIRCLE_PITCH_ALIGNMENT_MAP)));
        st.addLayer(new CircleLayer("puck-pulse", PUCK).withProperties(circleRadius(8f), circleOpacity(0f), circlePitchAlignment(Property.CIRCLE_PITCH_ALIGNMENT_MAP)));
        st.addLayer(new SymbolLayer("puck", PUCK).withProperties(iconImage(get("icon")), iconRotate(get("bearing")), iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP), iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
            iconAllowOverlap(true), iconIgnorePlacement(false)));

        st.addImage("puck-arrow", puck(c, true)); st.addImage("puck-dot", puck(c, false));
        st.addImage("dest", dest(c)); st.addImage("drop", drop(c));
        st.addImage("poi-dot", poiDot(c), true);
    }

    private static void online(Style st, Layer layer, String sourceLayer, float minZoom) {
        if (layer instanceof FillLayer) ((FillLayer)layer).setSourceLayer(sourceLayer);
        else if (layer instanceof LineLayer) ((LineLayer)layer).setSourceLayer(sourceLayer);
        else if (layer instanceof SymbolLayer) ((SymbolLayer)layer).setSourceLayer(sourceLayer);
        else if (layer instanceof CircleLayer) ((CircleLayer)layer).setSourceLayer(sourceLayer);
        layer.setMinZoom(minZoom); st.addLayer(layer);
    }
    /** Point the roads layer at the area's file; MapLibre reads and parses it on its own thread. */
    static void roads(GeoJsonSource src, String uri) {
        if (src == null) return;
        if (uri == null) src.setGeoJson(empty()); else try { src.setUri(new java.net.URI(uri)); } catch (java.net.URISyntaxException e) { src.setGeoJson(empty()); }
    }
    /** Pixels at zoom 0 for a circle of {@code meters} at this latitude (512 px world, like MapLibre). */
    static double accuracyAtZoom0(double meters, double lat) { return meters / (78271.517 * Math.cos(Math.toRadians(lat))); }

    private static void addRoad(Style st, String id, String[] kinds, Expression fill, Expression casing, float caseMinZoom) {
        LineLayer cs = new LineLayer("road-" + id + "-case", ROADS).withFilter(kinds(kinds)).withProperties(lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND), lineWidth(casing));
        if (caseMinZoom > 0) cs.setMinZoom(caseMinZoom);
        st.addLayer(cs);
        st.addLayer(new LineLayer("road-" + id, ROADS).withFilter(kinds(kinds)).withProperties(lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND), lineWidth(fill)));
    }

    /** Recolor every layer in place; MapLibre crossfades paint changes, so a theme switch is a soft dissolve. */
    static void paint(Style st, Gabi.Palette p, boolean coastline) {
        boolean n = p.night;
        int sea = n ? 0xFF0A1A26 : 0xFFBCD9E4;
        Layer bg = st.getLayer("background"); if (bg != null) bg.setProperties(backgroundColor(coastline ? sea : p.bg));
        Layer land = st.getLayer("land"); if (land != null) land.setProperties(fillColor(p.bg), fillOutlineColor(p.bg));
        Layer oland = st.getLayer("overview-land"); if (oland != null) oland.setProperties(fillColor(p.bg), fillOutlineColor(p.bg));
        line(st, "overview-roads", n ? 0xFF2E3B49 : 0xFFE3D6B4, 1f);
        Layer cf = st.getLayer("cells-fill"); if (cf != null) cf.setProperties(fillColor(p.accent));
        Layer cl = st.getLayer("cells-line"); if (cl != null) cl.setProperties(lineColor(p.accent));
        Layer water = st.getLayer("water-area"); if (water != null) water.setProperties(fillColor(sea), fillOutlineColor(sea));
        line(st, "road-path", n ? 0xFF223040 : 0xFFCFC9B9, 1f);
        line(st, "road-minor-case", n ? 0xFF0A1016 : 0xFFDDD8CB, 1f);
        line(st, "road-minor", n ? 0xFF1C2733 : 0xFFFFFFFF, 1f);
        line(st, "road-tertiary-case", n ? 0xFF0A1016 : 0xFFD6CFBE, 1f);
        line(st, "road-tertiary", n ? 0xFF27323F : 0xFFFFFFFF, 1f);
        line(st, "road-secondary-case", n ? 0xFF0A1016 : 0xFFD9C79A, 1f);
        line(st, "road-secondary", n ? 0xFF313E4C : 0xFFFFF1CC, 1f);
        line(st, "road-primary-case", n ? 0xFF0A1016 : 0xFFD6B26A, 1f);
        line(st, "road-primary", n ? 0xFF3E4C5C : 0xFFFFDE93, 1f);
        line(st, "route-done", n ? 0xFF5E6B78 : 0xFFA9B1B9, 0.55f);
        line(st, "route-glow", p.accent, n ? 0.38f : 0.22f);
        line(st, "route-case", n ? 0xFF3B2804 : 0xFF8A5600, 1f);
        line(st, "route-line", n ? 0xFFFFB020 : 0xFFFFAA0F, 1f);
        Layer pulse = st.getLayer("puck-pulse"); if (pulse != null) pulse.setProperties(circleColor(p.accent));
        Layer acc = st.getLayer("puck-accuracy"); if (acc != null) acc.setProperties(circleColor(p.accent), circleStrokeColor(p.accent));
        // Online detail, toned to sit under the Gabi roads and route.
        Layer ow = st.getLayer("online-water"); if (ow != null) ow.setProperties(fillColor(sea), fillOutlineColor(sea));
        Layer park = st.getLayer("online-park"); if (park != null) park.setProperties(fillColor(n ? 0xFF13261D : 0xFFD3E8C8));
        Layer bld = st.getLayer("online-building"); if (bld != null) bld.setProperties(fillColor(n ? 0xFF1A2430 : 0xFFE6E0D4), fillOutlineColor(n ? 0xFF222E3B : 0xFFD9D1C2));
        line(st, "online-road-minor", n ? 0xFF1C2733 : 0xFFFFFFFF, 1f);
        line(st, "online-road-major", n ? 0xFF313E4C : 0xFFFFF1CC, 1f);
        text(st, "online-street-names", n ? 0xFF9AA8B6 : 0xFF5E6670, n ? 0xFF0E151C : 0xFFFFFFFF);
        text(st, "online-poi", n ? 0xFFC9D3DC : 0xFF3A424B, n ? 0xFF0E151C : 0xFFFFFFFF);
        text(st, "online-places", n ? 0xFFDCE3EA : 0xFF2B323A, n ? 0xFF0E151C : 0xFFF7F4EC);
        Layer dot = st.getLayer("online-poi");
        if (dot != null) dot.setProperties(iconHaloColor(n ? 0xFF0E151C : 0xFFFFFFFF), iconColor(match(get("class"), color(n ? 0xFF8494A4 : 0xFF8A949E),
            stop("fast_food", color(0xFFF08A24)), stop("restaurant", color(0xFFF08A24)), stop("cafe", color(0xFFF08A24)), stop("bakery", color(0xFFF08A24)), stop("ice_cream", color(0xFFF08A24)), stop("bar", color(0xFFF08A24)),
            stop("shop", color(0xFF3D8BE0)), stop("grocery", color(0xFF3D8BE0)), stop("clothing_store", color(0xFF3D8BE0)), stop("hardware", color(0xFF3D8BE0)), stop("mall", color(0xFF3D8BE0)),
            stop("fuel", color(0xFFE5484D)), stop("hospital", color(0xFFE5484D)), stop("doctors", color(0xFFE5484D)), stop("pharmacy", color(0xFF2FB07A)),
            stop("bank", color(0xFF7B61D9)), stop("atm", color(0xFF7B61D9)), stop("school", color(0xFFB08A2F)), stop("college", color(0xFFB08A2F)), stop("place_of_worship", color(0xFF9A7B5C)), stop("lodging", color(0xFFD9618B)))));
    }
    private static void text(Style st, String id, int color, int halo) { Layer l = st.getLayer(id); if (l != null) l.setProperties(textColor(color), textHaloColor(halo), textHaloWidth(1.4f)); }
    private static void line(Style st, String id, int color, float opacity) { Layer l = st.getLayer(id); if (l != null) l.setProperties(lineColor(color), lineOpacity(opacity)); }

    // ---------------------------------------------------------------- data helpers
    /** Cell boxes as polygons for the selection highlight. */
    static FeatureCollection boxes(List<double[]> boxes) {
        ArrayList<Feature> out = new ArrayList<>();
        for (double[] b : boxes) out.add(Feature.fromGeometry(Polygon.fromLngLats(Collections.singletonList(Arrays.asList(
            Point.fromLngLat(b[0], b[1]), Point.fromLngLat(b[2], b[1]), Point.fromLngLat(b[2], b[3]), Point.fromLngLat(b[0], b[3]), Point.fromLngLat(b[0], b[1]))))));
        return FeatureCollection.fromFeatures(out);
    }
    static Feature point(double lat, double lon) { return Feature.fromGeometry(Point.fromLngLat(lon, lat)); }
    static FeatureCollection empty() { return FeatureCollection.fromFeatures(new Feature[0]); }
    static LineString line(List<double[]> latLon, int from, int to, double[] head) {
        ArrayList<Point> pts = new ArrayList<>(Math.max(2, to - from + 1));
        if (head != null) pts.add(Point.fromLngLat(head[1], head[0]));
        for (int i = Math.max(0, from); i < Math.min(to, latLon.size()); i++) pts.add(Point.fromLngLat(latLon.get(i)[1], latLon.get(i)[0]));
        if (pts.size() == 1) pts.add(pts.get(0));
        return LineString.fromLngLats(pts);
    }

    /** Parking pins near the destination: price only when an owner-entered rate is current, like the TFT. */
    static FeatureCollection parking(Context c, Style st, Gabi.Palette p, List<JSONObject> lots, int max) {
        ArrayList<Feature> out = new ArrayList<>();
        for (int i = 0; i < lots.size() && out.size() < max; i++) {
            JSONObject lot = lots.get(i);
            String price = priceOf(lot);
            String key = "park-" + (p.night ? "n-" : "d-") + price;
            if (st.getImage(key) == null) st.addImage(key, parkingPin(c, p, price));
            Feature f = point(lot.optDouble("lat"), lot.optDouble("lon")); f.addStringProperty("icon", key); out.add(f);
        }
        return FeatureCollection.fromFeatures(out);
    }
    static String priceOf(JSONObject lot) {
        String price = lot.optString("price_label", "");
        long age = System.currentTimeMillis() - lot.optLong("verified_at", 0);
        return age < 0 || age > 30L * 24 * 60 * 60 * 1000 ? "" : price;
    }

    // ---------------------------------------------------------------- bitmaps
    private static Paint paint() { Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); p.setStyle(Paint.Style.FILL); return p; }
    private static float d(Context c) { return c.getResources().getDisplayMetrics().density; }

    static Bitmap puck(Context c, boolean arrow) {
        float s = d(c); int size = Math.round(52 * s); Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888); Canvas cv = new Canvas(b); Paint p = paint();
        float cx = size / 2f;
        p.setColor(0x40FFB020); cv.drawCircle(cx, cx, 25 * s, p);
        p.setShadowLayer(4 * s, 0, 1.5f * s, 0x66000000); p.setColor(0xFFFFFFFF); cv.drawCircle(cx, cx, 13 * s, p); p.clearShadowLayer();
        p.setColor(0xFFFFB020);
        if (arrow) {
            Path a = new Path(); a.moveTo(cx, cx - 8.5f * s); a.lineTo(cx + 6.5f * s, cx + 7 * s); a.lineTo(cx, cx + 3.5f * s); a.lineTo(cx - 6.5f * s, cx + 7 * s); a.close();
            p.setColor(0xFF1A1203); cv.drawPath(a, p);
        } else cv.drawCircle(cx, cx, 7.5f * s, p);
        return b;
    }
    /** A plain disc, added as an SDF image so each place type can tint it and give it a halo. */
    static Bitmap poiDot(Context c) {
        float s = d(c); int size = Math.round(12 * s); Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888); Canvas cv = new Canvas(b); Paint p = paint();
        p.setColor(0xFFFFFFFF); cv.drawCircle(size / 2f, size / 2f, 4.5f * s, p); return b;
    }
    static Bitmap dest(Context c) {
        float s = d(c); int size = Math.round(40 * s); Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888); Canvas cv = new Canvas(b); Paint p = paint(); float cx = size / 2f;
        p.setShadowLayer(5 * s, 0, 2 * s, 0x77000000); p.setColor(0xFFFFB020); cv.drawCircle(cx, cx, 14 * s, p); p.clearShadowLayer();
        p.setColor(0xFF1A1203); cv.drawCircle(cx, cx, 5 * s, p);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2.5f * s); p.setColor(0xFFFFFFFF); cv.drawCircle(cx, cx, 14 * s, p);
        return b;
    }
    static Bitmap drop(Context c) {
        float s = d(c); int w = Math.round(36 * s), h = Math.round(48 * s); Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); Canvas cv = new Canvas(b); Paint p = paint();
        float cx = w / 2f, r = 13 * s, cy = r + 4 * s;
        Path pin = new Path(); pin.addCircle(cx, cy, r, Path.Direction.CW); pin.moveTo(cx - r * 0.72f, cy + r * 0.7f); pin.lineTo(cx, h - 2 * s); pin.lineTo(cx + r * 0.72f, cy + r * 0.7f); pin.close();
        p.setShadowLayer(5 * s, 0, 2 * s, 0x77000000); p.setColor(0xFFFFB020); cv.drawPath(pin, p); p.clearShadowLayer();
        p.setColor(0xFF1A1203); cv.drawCircle(cx, cy, 4.5f * s, p);
        return b;
    }
    static Bitmap parkingPin(Context c, Gabi.Palette pal, String price) {
        float s = d(c); Paint t = paint(); t.setTypeface(Gabi.font(c, 800)); t.setTextSize(13 * s);
        float pad = 7 * s, box = 28 * s, textW = price.isEmpty() ? 0 : t.measureText(price) + 10 * s;
        int w = Math.round(box + textW + 6 * s), h = Math.round(box + 6 * s);
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); Canvas cv = new Canvas(b); Paint p = paint();
        RectF r = new RectF(3 * s, 3 * s, w - 3 * s, h - 3 * s);
        p.setShadowLayer(4 * s, 0, 1.5f * s, 0x55000000); p.setColor(pal.night ? 0xFF1B2530 : 0xFFFFFFFF); cv.drawRoundRect(r, 9 * s, 9 * s, p); p.clearShadowLayer();
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.5f * s); p.setColor(price.isEmpty() ? (pal.night ? 0xFF4A5868 : 0xFFB9BFC6) : pal.accent); cv.drawRoundRect(r, 9 * s, 9 * s, p);
        p.setStyle(Paint.Style.FILL); p.setColor(pal.accent); RectF badge = new RectF(r.left + 3 * s, r.top + 3 * s, r.left + box - 3 * s, r.bottom - 3 * s); cv.drawRoundRect(badge, 6 * s, 6 * s, p);
        t.setColor(0xFF1A1203); t.setTextAlign(Paint.Align.CENTER); cv.drawText("P", badge.centerX(), badge.centerY() + 4.6f * s, t);
        if (!price.isEmpty()) { t.setTextAlign(Paint.Align.LEFT); t.setColor(pal.night ? 0xFFFFB020 : 0xFF8A5600); cv.drawText(price, r.left + box + 2 * s, r.centerY() + 4.6f * s, t); }
        return b;
    }
}
