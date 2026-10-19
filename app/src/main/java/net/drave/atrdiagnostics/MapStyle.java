package net.drave.atrdiagnostics;

import android.content.Context;
import android.graphics.*;
import org.json.JSONObject;
import org.maplibre.android.style.expressions.Expression;
import org.maplibre.android.style.layers.*;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.android.maps.Style;
import org.maplibre.geojson.*;
import org.maplibre.geojson.Point;
import java.util.*;

import static org.maplibre.android.style.expressions.Expression.*;
import static org.maplibre.android.style.layers.PropertyFactory.*;

/**
 * Offline Gabi map style. Everything is drawn from the bundled road lines; symbols are icons only,
 * so no glyph or sprite download is ever needed.
 */
final class MapStyle {
    private MapStyle() {}

    static final String ROADS = "roads", ROUTE = "route", DONE = "route-done", DEST = "dest", DROP = "drop", PARKING = "parking", PUCK = "puck";

    static String base(Gabi.Palette p) {
        return "{\"version\":8,\"sources\":{},\"layers\":[{\"id\":\"background\",\"type\":\"background\",\"paint\":{\"background-color\":\"" + hex(p.bg) + "\"}}]}";
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

    static void install(Context c, Style st, String roadsJson) {
        st.addSource(new GeoJsonSource(ROADS, roadsJson));
        LineLayer path = new LineLayer("road-path", ROADS).withFilter(kinds(PATHS)).withProperties(lineWidth(width(15, 0.8f, 18, 2.4f)), lineDasharray(new Float[]{1.2f, 1.6f}));
        path.setMinZoom(15);
        st.addLayer(path);
        addRoad(st, "minor", MINOR, width(11, 0.3f, 14, 2.2f, 16, 6, 18, 15), width(13, 0.6f, 14, 3.4f, 16, 8, 18, 18), 13);
        addRoad(st, "tertiary", TERTIARY, width(10, 0.5f, 14, 3.4f, 16, 8, 18, 19), width(10, 0.9f, 14, 5, 16, 10.5f, 18, 23), 0);
        addRoad(st, "secondary", SECONDARY, width(9, 0.7f, 14, 4.2f, 16, 9.5f, 18, 23), width(9, 1.2f, 14, 6, 16, 12.5f, 18, 27), 0);
        addRoad(st, "primary", PRIMARY, width(7, 0.9f, 14, 5.2f, 16, 11.5f, 18, 27), width(7, 1.4f, 14, 7.4f, 16, 14.5f, 18, 31), 0);

        st.addSource(new GeoJsonSource(DONE)); st.addSource(new GeoJsonSource(ROUTE));
        st.addLayer(new LineLayer("route-done", DONE).withProperties(lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND), lineWidth(width(10, 3, 14, 5, 17, 10))));
        st.addLayer(new LineLayer("route-glow", ROUTE).withProperties(lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND), lineBlur(7f), lineWidth(width(10, 12, 14, 18, 17, 34))));
        st.addLayer(new LineLayer("route-case", ROUTE).withProperties(lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND), lineWidth(width(10, 6.5f, 14, 9, 17, 16))));
        st.addLayer(new LineLayer("route-line", ROUTE).withProperties(lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND), lineWidth(width(10, 3.8f, 14, 5.6f, 17, 10.5f))));

        st.addSource(new GeoJsonSource(PARKING));
        st.addLayer(new SymbolLayer("parking", PARKING).withProperties(iconImage(get("icon")), iconAllowOverlap(false), iconIgnorePlacement(false), iconAnchor(Property.ICON_ANCHOR_BOTTOM_LEFT), iconOffset(new Float[]{-14f, 14f})));
        st.addSource(new GeoJsonSource(DEST)); st.addSource(new GeoJsonSource(DROP));
        st.addLayer(new SymbolLayer("dest", DEST).withProperties(iconImage("dest"), iconAllowOverlap(true), iconIgnorePlacement(true), iconAnchor(Property.ICON_ANCHOR_CENTER)));
        st.addLayer(new SymbolLayer("drop", DROP).withProperties(iconImage("drop"), iconAllowOverlap(true), iconIgnorePlacement(true), iconAnchor(Property.ICON_ANCHOR_BOTTOM)));

        st.addSource(new GeoJsonSource(PUCK));
        st.addLayer(new CircleLayer("puck-pulse", PUCK).withProperties(circleRadius(8f), circleOpacity(0f), circlePitchAlignment(Property.CIRCLE_PITCH_ALIGNMENT_MAP)));
        st.addLayer(new SymbolLayer("puck", PUCK).withProperties(iconImage(get("icon")), iconRotate(get("bearing")), iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP), iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
            iconAllowOverlap(true), iconIgnorePlacement(true)));

        st.addImage("puck-arrow", puck(c, true)); st.addImage("puck-dot", puck(c, false));
        st.addImage("dest", dest(c)); st.addImage("drop", drop(c));
    }

    private static void addRoad(Style st, String id, String[] kinds, Expression fill, Expression casing, float caseMinZoom) {
        LineLayer cs = new LineLayer("road-" + id + "-case", ROADS).withFilter(kinds(kinds)).withProperties(lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND), lineWidth(casing));
        if (caseMinZoom > 0) cs.setMinZoom(caseMinZoom);
        st.addLayer(cs);
        st.addLayer(new LineLayer("road-" + id, ROADS).withFilter(kinds(kinds)).withProperties(lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND), lineWidth(fill)));
    }

    /** Recolor every layer in place; MapLibre crossfades paint changes, so a theme switch is a soft dissolve. */
    static void paint(Style st, Gabi.Palette p) {
        boolean n = p.night;
        Layer bg = st.getLayer("background"); if (bg != null) bg.setProperties(backgroundColor(p.bg));
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
    }
    private static void line(Style st, String id, int color, float opacity) { Layer l = st.getLayer(id); if (l != null) l.setProperties(lineColor(color), lineOpacity(opacity)); }

    // ---------------------------------------------------------------- data helpers
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
