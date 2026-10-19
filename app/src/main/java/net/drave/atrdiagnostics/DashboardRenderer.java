package net.drave.atrdiagnostics;

import android.graphics.*;
import android.os.SystemClock;
import org.json.*;
import java.util.*;

/**
 * The scene sent to the motorcycle TFT, laid out for its landscape screen the way Carbit-style dashboards
 * do: an instrument panel on the left (turn, distance, street, next turn, arrival) and a heading-up map on
 * the right. "Large turns" widens the panel. Safe insets, text size and the calibration grid apply as before.
 */
public final class DashboardRenderer {
    private final NavSession s; private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); private final Path path = new Path();
    private Gabi.Palette pal = Gabi.NIGHT; private long palAt;
    private Typeface bold, semi, black;

    public DashboardRenderer(NavSession session) { s = session; }

    private void fonts() {
        if (bold != null) return;
        bold = Gabi.font(s.context, 760); semi = Gabi.font(s.context, 600); black = Gabi.font(s.context, 860);
    }
    private void palette() {
        long now = SystemClock.elapsedRealtime();
        if (now - palAt < 5000 && palAt != 0) return;
        palAt = now; pal = Gabi.resolve(s.context, s.lat, s.lon, s.fixAt > 0);
    }

    // ---------------------------------------------------------------- colors
    private int panel() { return pal.night ? 0xFF0B1117 : 0xFFFFFFFF; }
    private int ink() { return pal.night ? 0xFFF2F4F6 : 0xFF121A21; }
    private int soft() { return pal.night ? 0xFFB7C3CF : 0xFF3A4652; }
    private int muted() { return pal.night ? 0xFF7F8FA0 : 0xFF6B7885; }
    private int land() { return pal.night ? 0xFF0E151C : 0xFFEEEDE7; }
    private int road(String kind) {
        boolean major = kind.contains("primary") || kind.contains("trunk") || kind.contains("motorway");
        if (pal.night) return major ? 0xFF46566A : kind.contains("secondary") ? 0xFF38475A : kind.contains("tertiary") ? 0xFF2D3A49 : 0xFF222D39;
        return major ? 0xFFFFDE93 : kind.contains("secondary") ? 0xFFFFF1CC : 0xFFFFFFFF;
    }
    private float roadWidth(String kind) { return kind.contains("primary") || kind.contains("trunk") || kind.contains("motorway") ? 11 : kind.contains("secondary") ? 8 : kind.contains("tertiary") ? 6 : 4; }
    private static boolean path(String kind) { return kind.equals("footway") || kind.equals("path") || kind.equals("steps") || kind.equals("pedestrian") || kind.equals("cycleway") || kind.equals("corridor") || kind.equals("track") || kind.equals("construction") || kind.equals("proposed"); }

    // ---------------------------------------------------------------- text
    private void text(Canvas c, String str, float x, float y, float size, int color, Typeface face) { p.setStyle(Paint.Style.FILL); p.setColor(color); p.setTypeface(face); p.setTextSize(size); p.setTextAlign(Paint.Align.LEFT); c.drawText(str, x, y, p); }
    private String fit(String value, float width, float size, Typeface face) {
        p.setTextSize(size); p.setTypeface(face);
        if (p.measureText(value) <= width) return value;
        while (value.length() > 1 && p.measureText(value + "…") > width) value = value.substring(0, value.length() - 1);
        return value + "…";
    }
    private List<String> wrap(String value, float width, float size, Typeface face, int lines) {
        p.setTextSize(size); p.setTypeface(face); ArrayList<String> out = new ArrayList<>(); String line = "";
        for (String w : value.split(" ")) {
            String next = line.isEmpty() ? w : line + " " + w;
            if (p.measureText(next) <= width || line.isEmpty()) line = next; else { out.add(line); line = w; }
        }
        if (!line.isEmpty()) out.add(line);
        if (out.size() > lines) { List<String> cut = new ArrayList<>(out.subList(0, lines)); cut.set(lines - 1, fit(String.join(" ", out.subList(lines - 1, out.size())), width, size, face)); return cut; }
        for (int i = 0; i < out.size(); i++) out.set(i, fit(out.get(i), width, size, face));
        return out;
    }

    // ---------------------------------------------------------------- scene
    public void draw(Canvas target, int w, int h) {
        fonts(); palette();
        target.drawColor(Color.BLACK);
        double[] r = GeoMath.safeRect(w, h, s.insetLeft, s.insetTop, s.insetRight, s.insetBottom);
        float width = (float)(r[2] - r[0]), height = (float)(r[3] - r[1]);
        target.save(); target.clipRect((float)r[0], (float)r[1], (float)r[2], (float)r[3]); target.translate((float)r[0], (float)r[1]);
        float scale = Math.min(width / 800f, height / 480f); float vw = width / scale, vh = height / scale; target.scale(scale, scale);
        if (s.calibration) { grid(target, vw, vh); target.restore(); return; }
        boolean large = "Large turns".equals(s.layout);
        float panelW = Math.round(vw * (large ? 0.5f : 0.37f));
        drawMap(target, panelW, 0, vw - panelW, vh);
        p.setStyle(Paint.Style.FILL); p.setColor(panel()); target.drawRect(0, 0, panelW, vh, p);
        p.setColor(pal.night ? 0x14FFFFFF : 0x14000000); target.drawRect(panelW - 1.5f, 0, panelW, vh, p);
        if (s.active) ride(target, panelW, vh, large); else idle(target, panelW, vh);
        target.restore();
    }

    private void ride(Canvas c, float pw, float vh, boolean large) {
        float t = s.textScale, pad = 22;
        JSONObject m = s.maneuvers.optJSONObject(s.maneuver);
        boolean arriving = "Destination nearby".equals(s.instruction);
        boolean stale = !s.simulation && (s.fixAt == 0 || SystemClock.elapsedRealtime() - s.fixAt > 12000 || s.accuracy > 50);
        int type = arriving ? 4 : m == null ? 8 : m.optInt("type", 8);
        float tile = (large ? 150 : 108) * Math.min(t, 1.25f);
        RectF box = new RectF(pad, pad, pad + tile, pad + tile);
        p.setStyle(Paint.Style.FILL); p.setColor(pal.accent); c.drawRoundRect(box, tile * 0.22f, tile * 0.22f, p);
        IconDrawable arrow = new IconDrawable(Maneuvers.icon(type), 0xFF1A1203).stroke(2.8f);
        int inset = Math.round(tile * 0.16f); arrow.setBounds(Math.round(box.left) + inset, Math.round(box.top) + inset, Math.round(box.right) - inset, Math.round(box.bottom) - inset); arrow.draw(c);
        float y = box.bottom + (large ? 76 : 58) * t;
        String dist = stale ? "GPS weak" : arriving ? "Arriving" : distance(s.distanceToTurn);
        text(c, fit(dist, pw - pad * 2, (large ? 78 : 56) * t, black), pad, y, (large ? 78 : 56) * t, stale ? pal.danger : ink(), black);
        String street = arriving ? (s.destination.isEmpty() ? "Destination" : s.destination) : streetOf(m, s.instruction);
        float ss = (large ? 30 : 24) * t;
        for (String line : wrap(street, pw - pad * 2, ss, bold, 2)) { y += ss * 1.2f; text(c, line, pad, y, ss, soft(), bold); }
        JSONObject next = arriving ? null : s.maneuvers.optJSONObject(s.maneuver + 1);
        float statsTop = vh - 92 * Math.min(t, 1.2f);
        if (next != null && y + 52 < statsTop) {
            y += 40; float is = 26;
            text(c, "Then", pad, y, 18, muted(), semi);
            p.setTextSize(18); p.setTypeface(semi); float tx = pad + p.measureText("Then") + 10;
            IconDrawable ni = new IconDrawable(Maneuvers.icon(next.optInt("type", 8)), soft()).stroke(2.4f); ni.setBounds(Math.round(tx), Math.round(y - is + 4), Math.round(tx + is), Math.round(y + 4)); ni.draw(c);
            int nt = next.optInt("type"); String ns = nt >= 4 && nt <= 6 ? "Arrive" : streetOf(next, next.optString("instruction", ""));
            text(c, fit(ns, pw - tx - is - pad - 8, 18, semi), tx + is + 8, y, 18, soft(), semi);
        }
        p.setColor(pal.night ? 0x14FFFFFF : 0x12000000); c.drawRect(pad, statsTop - 14, pw - pad, statsTop - 12.5f, p);
        String[] v = {String.valueOf(Math.max(1, Math.round(s.seconds / 60))), String.format(Locale.US, "%.1f", s.remaining / 1000), Gabi.clock(s.seconds).replace(" AM", "").replace(" PM", "")};
        String[] u = {"MIN", "KM", "ARRIVE"};
        float col = (pw - pad * 2) / 3f, vs = (large ? 38 : 30) * Math.min(t, 1.2f);
        for (int i = 0; i < 3; i++) {
            float x = pad + col * i;
            text(c, fit(v[i], col - 6, vs, black), x, statsTop + vs, vs, i == 0 ? pal.accent : ink(), black);
            p.setLetterSpacing(0.12f); text(c, u[i], x, statsTop + vs + 24, 13, muted(), bold); p.setLetterSpacing(0);
        }
    }

    private void idle(Canvas c, float pw, float vh) {
        float pad = 24, t = s.textScale;
        p.setLetterSpacing(0.18f); text(c, "DAANVIA", pad, pad + 16, 16, pal.accent, black); p.setLetterSpacing(0);
        String clock = new java.text.SimpleDateFormat("h:mm", Locale.US).format(new Date());
        text(c, clock, pad, pad + 16 + 92 * t, 88 * t, ink(), black);
        float y = pad + 16 + 92 * t + 44;
        if (!s.shape.isEmpty() && !s.destination.isEmpty()) {
            text(c, "Route ready", pad, y, 18, muted(), bold);
            for (String line : wrap(s.destination, pw - pad * 2, 26 * t, bold, 2)) { y += 32 * t; text(c, line, pad, y, 26 * t, ink(), bold); }
            y += 40 * t; text(c, Gabi.minutes(s.seconds) + " · " + distance(s.remaining), pad, y, 24 * t, pal.accent, black);
        } else {
            text(c, fit(s.ready ? s.regionName : "Getting the map ready…", pw - pad * 2, 20, bold), pad, y, 20, soft(), bold);
            y += 30; text(c, fit("Choose a destination on your phone", pw - pad * 2, 17, semi), pad, y, 17, muted(), semi);
        }
    }

    private static String distance(double m) { return m < 1000 ? Math.max(0, Math.round(m / 10) * 10) + " m" : String.format(Locale.US, "%.1f km", m / 1000); }
    private static String streetOf(JSONObject m, String fallback) {
        if (m == null) return fallback;
        JSONArray n = m.optJSONArray("street_names"); if (n != null && n.length() > 0) return n.optString(0);
        n = m.optJSONArray("begin_street_names"); if (n != null && n.length() > 0) return n.optString(0);
        return m.optString("instruction", fallback);
    }

    // ---------------------------------------------------------------- map
    private float cx, cy, cos, sin; private double clat, clon, mpp, coslat;
    private void project(double lat, double lon, float[] out) {
        float x = (float)((lon - clon) * coslat * 111195 / mpp), y = (float)(-(lat - clat) * 111195 / mpp);
        out[0] = cx + x * cos - y * sin; out[1] = cy + x * sin + y * cos;
    }

    private void drawMap(Canvas c, float left, float top, float w, float h) {
        c.save(); c.clipRect(left, top, left + w, top + h);
        p.setStyle(Paint.Style.FILL); p.setColor(land()); c.drawRect(left, top, left + w, top + h, p);
        boolean heading = s.active;
        List<double[]> route = s.shape;
        clat = s.viewLat; clon = s.viewLon; coslat = Math.cos(Math.toRadians(clat));
        mpp = s.active ? (s.remaining < 1000 ? 2.2 : 3.4) : 5.5;
        double theta = heading ? Math.toRadians(-s.viewBearing) : 0; cos = (float)Math.cos(theta); sin = (float)Math.sin(theta);
        cx = left + w / 2; cy = top + h * (heading ? 0.70f : 0.5f);
        double reach = Math.hypot(w, h) * mpp, dLat = reach / 111195, dLon = reach / (111195 * coslat);
        float[] pt = new float[2];
        p.setStyle(Paint.Style.STROKE); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
        for (int pass = 0; pass < 2; pass++) for (NavSession.Road road : s.roads) {
            if (road.points.length < 2 || path(road.kind)) continue;
            boolean major = road.kind.contains("primary") || road.kind.contains("trunk") || road.kind.contains("motorway") || road.kind.contains("secondary");
            if ((pass == 1) != major) continue;
            if (road.maxLat < clat - dLat || road.minLat > clat + dLat || road.maxLon < clon - dLon || road.minLon > clon + dLon) continue;
            path.reset();
            for (int j = 0; j < road.points.length; j++) { project(road.points[j][0], road.points[j][1], pt); if (j == 0) path.moveTo(pt[0], pt[1]); else path.lineTo(pt[0], pt[1]); }
            p.setColor(road(road.kind)); p.setStrokeWidth(roadWidth(road.kind) * (float)(3.4 / mpp + 0.4)); c.drawPath(path, p);
        }
        if (route.size() > 1) {
            int from = s.active ? Math.max(0, Math.min(s.progress, route.size() - 1)) : 0;
            path.reset();
            if (s.active) { project(clat, clon, pt); path.moveTo(pt[0], pt[1]); }
            for (int i = from + (s.active ? 1 : 0); i < route.size(); i++) { double[] a = route.get(i); project(a[0], a[1], pt); if (path.isEmpty()) path.moveTo(pt[0], pt[1]); else path.lineTo(pt[0], pt[1]); }
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(17); p.setColor(pal.night ? 0xFF3B2804 : 0xFF8A5600); c.drawPath(path, p);
            p.setStrokeWidth(10); p.setColor(pal.accent); c.drawPath(path, p);
            project(s.destLat, s.destLon, pt);
            p.setStyle(Paint.Style.FILL); p.setColor(0xFFFFFFFF); c.drawCircle(pt[0], pt[1], 15, p); p.setColor(pal.accent); c.drawCircle(pt[0], pt[1], 12, p); p.setColor(0xFF1A1203); c.drawCircle(pt[0], pt[1], 4.5f, p);
        }
        if (!route.isEmpty() && (!s.active || s.remaining < 1000)) parking(c, left, top, w, h, pt);
        if (s.active || s.fixAt > 0) {
            project(clat, clon, pt);
            c.save(); c.rotate(heading ? 0 : s.viewBearing, pt[0], pt[1]);
            p.setStyle(Paint.Style.FILL); p.setColor(pal.night ? 0x40FFB020 : 0x30F5A300); c.drawCircle(pt[0], pt[1], 30, p);
            path.reset(); path.moveTo(pt[0], pt[1] - 20); path.lineTo(pt[0] + 15, pt[1] + 15); path.lineTo(pt[0], pt[1] + 7); path.lineTo(pt[0] - 15, pt[1] + 15); path.close();
            p.setColor(0xFFFFFFFF); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(6); c.drawPath(path, p);
            p.setStyle(Paint.Style.FILL); p.setColor(pal.night ? 0xFF1A1203 : 0xFF121A21); c.drawPath(path, p);
            c.restore();
        }
        if (s.simulation) {
            p.setTextSize(15); p.setTypeface(black); p.setLetterSpacing(0.1f); float tw = p.measureText("DEMO");
            RectF b = new RectF(left + w - tw - 40, top + 16, left + w - 16, top + 46); p.setStyle(Paint.Style.FILL); p.setColor(pal.accent); c.drawRoundRect(b, 15, 15, p);
            text(c, "DEMO", b.left + 12, b.bottom - 9, 15, 0xFF1A1203, black); p.setLetterSpacing(0);
        }
        p.setTextSize(11); p.setTypeface(semi); float aw = p.measureText("© OpenStreetMap contributors");
        text(c, "© OpenStreetMap contributors", left + w - aw - 10, top + h - 8, 11, muted(), semi);
        c.restore();
    }

    private void parking(Canvas c, float left, float top, float w, float h, float[] pt) {
        ArrayList<RectF> occupied = new ArrayList<>(); int shown = 0;
        for (int i = 0; i < s.nearbyParking.size() && shown < 3; i++) try {
            JSONObject lot = s.nearbyParking.get(i); double a = lot.getDouble("lat"), b = lot.getDouble("lon");
            if (GeoMath.distance(a, b, s.destLat, s.destLon) > 700) continue;
            project(a, b, pt); float x = pt[0], y = pt[1];
            if (x < left + 24 || x > left + w - 24 || y < top + 24 || y > top + h - 30) continue;
            String price = MapStyle.priceOf(lot);
            p.setTextSize(18); p.setTypeface(black); float lw = price.isEmpty() ? 0 : Math.min(150, p.measureText(price) + 16);
            if (x + 22 + lw > left + w - 8) lw = 0; if (lw == 0) price = "";
            RectF box = new RectF(x - 19, y - 19, x + 19 + lw, y + 19);
            boolean overlap = false; for (RectF o : occupied) if (RectF.intersects(box, o)) overlap = true;
            if (overlap) continue; occupied.add(box);
            p.setStyle(Paint.Style.FILL); p.setColor(pal.night ? 0xFF1B2530 : 0xFFFFFFFF); c.drawRoundRect(box, 11, 11, p);
            p.setColor(pal.accent); c.drawRoundRect(new RectF(x - 15, y - 15, x + 15, y + 15), 8, 8, p);
            p.setTextAlign(Paint.Align.CENTER); p.setColor(0xFF1A1203); p.setTextSize(20); c.drawText("P", x, y + 7, p); p.setTextAlign(Paint.Align.LEFT);
            if (!price.isEmpty()) text(c, price, x + 23, y + 6.5f, 18, pal.night ? pal.accent : 0xFF8A5600, black);
            shown++;
        } catch (Exception ignored) {}
    }

    private void grid(Canvas c, float w, float h) {
        c.drawColor(panel());
        p.setStyle(Paint.Style.STROKE); p.setColor(pal.night ? 0xFF243140 : 0xFFD5DAD3); p.setStrokeWidth(1);
        for (int x = 0; x < w; x += 40) c.drawLine(x, 0, x, h, p);
        for (int y = 0; y < h; y += 40) c.drawLine(0, y, w, y, p);
        p.setColor(pal.accent); p.setStrokeWidth(5); c.drawRect(3, 3, w - 3, h - 3, p); c.drawCircle(w / 2, h / 2, 60, p);
        text(c, "FIT MY TFT", 22, 48, 32, ink(), black);
        text(c, "All four edges visible · circle round", 22, h - 26, 22, soft(), bold);
    }
}
