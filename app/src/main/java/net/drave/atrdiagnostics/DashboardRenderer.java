package net.drave.atrdiagnostics;

import android.graphics.*;
import android.os.SystemClock;
import org.json.*;
import java.util.*;

/**
 * The scene sent to the motorcycle TFT: a heading-up, tilted map across the whole screen, with a turn card and a trip
 * card floating on it. The rider places both cards and the arrow per bike (Settings › Bike display › Edit layout) so
 * they stay clear of that bike's own speedometer and gauges. Safe insets, text size and the calibration grid apply too.
 */
public final class DashboardRenderer {
    private final NavSession s; private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); private final Path path = new Path();
    private final RideMotion motion;
    private Gabi.Palette pal = Gabi.NIGHT; private long palAt;
    private Typeface bold, semi, black;

    public DashboardRenderer(NavSession session) { s = session; motion = new RideMotion(session); }

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
    private int panel() { return pal.night ? 0xF20B1117 : 0xF7FFFFFF; }
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
    /** The visible part of a w×h display after the safe insets, in display pixels. */
    public RectF scene(int w, int h) { double[] r = GeoMath.safeRect(w, h, s.insetLeft, s.insetTop, s.insetRight, s.insetBottom); return new RectF((float)r[0], (float)r[1], (float)r[2], (float)r[3]); }

    /**
     * The bike scales the video to fill its panel. When the panel's shape differs from the video's (a 1280×480 panel fed
     * 800×480 video, say), everything would come out stretched, so the scene is drawn for the panel's shape and squeezed
     * into the video by the same amount the bike will stretch it back.
     */
    public void draw(Canvas target, int w, int h) {
        float pa = s.panelAspect(), k = pa > 0 ? pa / ((float)w / h) : 1;
        if (Math.abs(k - 1) < 0.03f) { scene(target, w, h); return; }
        target.save(); target.scale(1 / k, 1); scene(target, Math.round(w * k), h); target.restore();
    }
    private void scene(Canvas target, int w, int h) {
        fonts(); palette();
        target.drawColor(Color.BLACK);
        RectF r = scene(w, h); float width = r.width(), height = r.height();
        target.save(); target.clipRect(r); target.translate(r.left, r.top);
        // A portrait display gets a portrait canvas (and, until the rider moves the cards, a portrait layout), so text keeps
        // the same size on a tall screen as on a wide one.
        boolean portrait = height > width * 1.1f; if (portrait) portraitLayout();
        float scale = portrait ? Math.min(width / 480f, height / 800f) : Math.min(width / 800f, height / 480f); float vw = width / scale, vh = height / scale; target.scale(scale, scale);
        if (s.calibration) { grid(target, vw, vh); target.restore(); return; }
        // Directions only (Settings › Bike display): the cards on a plain background, no moving map. About a third of the
        // work of the full picture (measured), and only a few frames a second are needed since little moves.
        boolean simple = s.directionsOnly() && s.active && !s.layoutEditing;
        if (simple) { target.drawColor(land()); pixelsPerSecond = 0; } else drawMap(target, vw, vh);
        // With no map to keep clear, the cards fill the screen: a large turn card, and the trip along the bottom.
        RectF turn = simple ? new RectF(vw * 0.04f, vh * 0.04f, vw * 0.96f, vh * 0.68f) : card(s.turnCard, vw, vh);
        RectF trip = simple ? new RectF(vw * 0.04f, vh * 0.72f, vw * 0.96f, vh * 0.96f) : card(s.tripCard, vw, vh);
        boolean sample = s.layoutEditing && !s.active;
        if (s.active || sample) { cached(target, turnNode, turnKey(turn, sample), true, turn, sample, vw, vh); cached(target, tripNode, tripKey(trip, sample), false, trip, sample, vw, vh); }
        else idle(target, turn);
        if (s.layoutEditing) outlines(target, turn, trip, s.arrowX * vw, s.arrowY * vh);
        target.restore();
    }
    // ---- Cards recorded once and replayed ----
    // The turn and trip cards change far less often than the map moves: the distance to the turn about once a second,
    // the trip numbers about once a minute. Each card is recorded once and that recording is replayed every frame until
    // what the card shows changes, instead of measuring and drawing its text every frame.
    private final android.graphics.RenderNode turnNode = new android.graphics.RenderNode("turn-card"), tripNode = new android.graphics.RenderNode("trip-card");
    private String turnShown, tripShown;
    private void cached(Canvas c, android.graphics.RenderNode node, String key, boolean turn, RectF r, boolean sample, float vw, float vh) {
        if (!c.isHardwareAccelerated()) { if (turn) turnCard(c, r, sample); else tripCard(c, r, sample); return; }
        if (!key.equals(turn ? turnShown : tripShown) || !node.hasDisplayList()) {
            node.setPosition(0, 0, (int)Math.ceil(vw), (int)Math.ceil(vh));
            android.graphics.RecordingCanvas rc = node.beginRecording();
            try { if (turn) turnCard(rc, r, sample); else tripCard(rc, r, sample); } finally { node.endRecording(); }
            if (turn) turnShown = key; else tripShown = key;
        }
        c.drawRenderNode(node);
    }
    /** Everything the turn card's picture depends on (see turnCard). */
    private String turnKey(RectF r, boolean sample) {
        boolean arriving = !sample && "Destination nearby".equals(s.instruction);
        boolean stale = !sample && !s.simulation && (s.fixAt == 0 || SystemClock.elapsedRealtime() - s.fixAt > 12000 || s.accuracy > 50);
        return look(r) + sample + arriving + stale + '|' + s.maneuver + '|' + System.identityHashCode(s.maneuvers) + '|' + distance(s.distanceToTurn) + '|' + s.instruction + '|' + s.destination;
    }
    /** Everything the trip card's picture depends on (see tripCard). */
    private String tripKey(RectF r, boolean sample) {
        double secs = sample ? 12 * 60 : s.seconds, metres = sample ? 4500 : s.remaining;
        return look(r) + sample + '|' + Math.max(1, Math.round(secs / 60)) + '|' + Math.round(metres / 100) + '|' + Gabi.clock(secs);
    }
    private String look(RectF r) { return r.left + "," + r.top + "," + r.right + "," + r.bottom + '|' + pal.night + pal.accent + '|' + s.textScale + s.layout + '|' + System.identityHashCode(black) + System.identityHashCode(bold) + '|'; }
    /** The landscape default cards don't suit a tall display: the turn across the top, the trip across the bottom. */
    private void portraitLayout() {
        if (!java.util.Arrays.equals(s.turnCard, NavSession.DEFAULT_TURN) || !java.util.Arrays.equals(s.tripCard, NavSession.DEFAULT_TRIP)) return;
        s.turnCard = new float[]{0.03f, 0.02f, 0.94f, 0.26f}; s.tripCard = new float[]{0.03f, 0.85f, 0.94f, 0.12f}; s.arrowX = 0.5f; s.arrowY = 0.66f;
    }
    private static RectF card(float[] f, float vw, float vh) { return new RectF(f[0] * vw, f[1] * vh, (f[0] + f[2]) * vw, (f[1] + f[3]) * vh); }
    private void cardBackground(Canvas c, RectF r) {
        p.setStyle(Paint.Style.FILL); p.setColor(panel()); c.drawRoundRect(r, 18, 18, p);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.5f); p.setColor(pal.night ? 0x1FFFFFFF : 0x14000000); c.drawRoundRect(r, 18, 18, p);
    }
    /** Content grows and shrinks with the card the rider sized, and with the text size from Fit my TFT. */
    private float unit(RectF r, float baseW, float baseH) { return Math.max(0.6f, Math.min(2.2f, Math.min(r.width() / baseW, r.height() / baseH) * s.textScale * ("Large turns".equals(s.layout) ? 1.15f : 1f))); }

    private void turnCard(Canvas c, RectF r, boolean sample) {
        cardBackground(c, r);
        JSONObject m = s.maneuvers.optJSONObject(s.maneuver);
        boolean arriving = !sample && "Destination nearby".equals(s.instruction);
        boolean stale = !sample && !s.simulation && (s.fixAt == 0 || SystemClock.elapsedRealtime() - s.fixAt > 12000 || s.accuracy > 50);
        int type = sample ? 10 : arriving ? 4 : m == null ? 8 : m.optInt("type", 8);
        String dist = sample ? "200 m" : stale ? "GPS weak" : arriving ? "Arriving" : distance(s.distanceToTurn);
        String street = sample ? "Sample Street" : arriving ? (s.destination.isEmpty() ? "Destination" : s.destination) : streetOf(m, s.instruction);
        JSONObject next = sample || arriving ? null : s.maneuvers.optJSONObject(s.maneuver + 1);
        String then = sample ? "Turn left" : next == null ? null : (next.optInt("type") >= 4 && next.optInt("type") <= 6 ? "Arrive" : streetOf(next, next.optString("instruction", "")));
        int thenIcon = sample ? 15 : next == null ? 8 : next.optInt("type", 8);
        boolean wide = r.width() > r.height() * 1.55f;
        float k = unit(r, wide ? 420 : 280, wide ? 150 : 270), pad = 16 * k;
        float tile = wide ? Math.min(r.height() - pad * 2, 112 * k) : Math.min(108 * k, r.height() * 0.4f);
        RectF box = new RectF(r.left + pad, r.top + pad, r.left + pad + tile, r.top + pad + tile);
        p.setStyle(Paint.Style.FILL); p.setColor(pal.accent); c.drawRoundRect(box, tile * 0.22f, tile * 0.22f, p);
        IconDrawable arrow = new IconDrawable(Maneuvers.icon(type), 0xFF1A1203).stroke(2.8f);
        int inset = Math.round(tile * 0.16f); arrow.setBounds(Math.round(box.left) + inset, Math.round(box.top) + inset, Math.round(box.right) - inset, Math.round(box.bottom) - inset); arrow.draw(c);
        float x = wide ? box.right + pad : r.left + pad, textW = r.right - pad - x;
        float ds = 62 * k, ss = 29 * k, ts = 22 * k;
        float y = wide ? r.top + pad + ds * 0.86f : box.bottom + ds * 1.02f;
        text(c, fit(dist, textW, ds, black), x, y, ds, stale ? pal.danger : ink(), black);
        for (String line : wrap(street, textW, ss, bold, wide ? 1 : 2)) { if (y + ss * 1.25f > r.bottom - pad * 0.5f) break; y += ss * 1.25f; text(c, line, x, y, ss, soft(), bold); }
        if (then != null && y + ts * 2.1f < r.bottom - pad * 0.4f) {
            y += ts * 1.9f; text(c, "Then", x, y, ts, muted(), semi);
            p.setTextSize(ts); p.setTypeface(semi); float tx = x + p.measureText("Then") + 8 * k, is = ts * 1.35f;
            IconDrawable ni = new IconDrawable(Maneuvers.icon(thenIcon), soft()).stroke(2.4f); ni.setBounds(Math.round(tx), Math.round(y - is + 3 * k), Math.round(tx + is), Math.round(y + 3 * k)); ni.draw(c);
            text(c, fit(then, r.right - pad - tx - is - 6 * k, ts, semi), tx + is + 6 * k, y, ts, soft(), semi);
        }
    }

    private void tripCard(Canvas c, RectF r, boolean sample) {
        cardBackground(c, r);
        double secs = sample ? 12 * 60 : s.seconds, metres = sample ? 4500 : s.remaining;
        String[] v = {String.valueOf(Math.max(1, Math.round(secs / 60))), String.format(Locale.US, "%.1f", metres / 1000), Gabi.clock(secs).replace(" AM", "").replace(" PM", "")};
        String[] u = {"MIN", "KM", "ARRIVE"};
        boolean row = r.width() >= r.height() * 1.25f;
        // Readable at a glance from the saddle: big numbers, and labels about half their height in a bright colour (they
        // were small grey caps you had to lean in to read). Everything shrinks together only when the card is too small.
        p.setLetterSpacing(0.08f); p.setTypeface(bold); p.setTextSize(100); float labelW = 0; for (String t : u) labelW = Math.max(labelW, p.measureText(t) / 100f); p.setLetterSpacing(0);
        p.setTypeface(black); p.setTextSize(100); float valueW = 0; for (String t : v) valueW = Math.max(valueW, p.measureText(t) / 100f);
        if (row) {
            // Each column as wide as its own number or label ("7" needs far less room than "10:59"), so the numbers can
            // be as large as the card allows rather than all fitting the widest one.
            float k = unit(r, 280, 140), pad = 12 * k, gap = 22 * k, avail = r.width() - pad * 2 - gap * 2, ratio = 0.46f;
            float[] em = new float[3]; float sum = 0;
            for (int i = 0; i < 3; i++) {
                p.setTypeface(black); p.setTextSize(100); float vw = p.measureText(v[i]) / 100f;
                p.setTypeface(bold); p.setLetterSpacing(0.08f); float lw = p.measureText(u[i]) / 100f * ratio; p.setLetterSpacing(0);
                em[i] = Math.max(vw, lw); sum += em[i];
            }
            float vs = Math.min(Math.min(60 * k, r.height() * 0.52f), avail / sum), us = vs * ratio;
            float block = vs * 0.74f + us * 1.45f, base = r.top + (r.height() - block) / 2f + vs * 0.74f;
            // Whatever width the numbers don't need is shared evenly between the columns, and each label sits centred under
            // its number, so the three read as one balanced row however long "11:12" or "10" is.
            float space = (r.width() - pad * 2 - sum * vs) / 2f, x = r.left + pad;
            for (int i = 0; i < 3; i++) {
                float mid = x + em[i] * vs / 2f;
                p.setTypeface(black); p.setTextSize(vs); float vw = p.measureText(v[i]);
                text(c, v[i], mid - vw / 2f, base, vs, i == 0 ? pal.accent : ink(), black);
                p.setTypeface(bold); p.setTextSize(us); p.setLetterSpacing(0.08f); float lw = p.measureText(u[i]);
                text(c, u[i], mid - lw / 2f, base + us * 1.35f, us, soft(), bold); p.setLetterSpacing(0);
                x += em[i] * vs + space;
            }
        } else {
            float k = unit(r, 150, 260), pad = 12 * k, line = (r.height() - pad * 2) / 3f, room = r.width() - pad * 2;
            float vs = Math.min(Math.min(48 * k, line * 0.56f), room / valueW), us = Math.min(Math.min(Math.max(16 * k, vs * 0.44f), line * 0.3f), room / labelW);
            for (int i = 0; i < 3; i++) {
                float y = r.top + pad + line * i + (line - vs * 0.74f - us * 1.4f) / 2f + vs * 0.74f;
                text(c, v[i], r.left + pad, y, vs, i == 0 ? pal.accent : ink(), black);
                p.setLetterSpacing(0.08f); text(c, u[i], r.left + pad, y + us * 1.3f, us, soft(), bold); p.setLetterSpacing(0);
            }
        }
    }

    private void idle(Canvas c, RectF r) {
        cardBackground(c, r);
        float k = unit(r, 280, 270), pad = 18 * k, x = r.left + pad, w = r.width() - pad * 2;
        p.setLetterSpacing(0.18f); text(c, "DAANVIA BETA", x, r.top + pad + 13 * k, 14 * k, pal.accent, black); p.setLetterSpacing(0);
        String clock = new java.text.SimpleDateFormat("h:mm", Locale.US).format(new Date());
        float cs = Math.min(78 * k, r.height() * 0.34f), y = r.top + pad + 13 * k + cs * 1.02f;
        text(c, fit(clock, w, cs, black), x, y, cs, ink(), black);
        if (!s.shape.isEmpty() && !s.destination.isEmpty()) {
            y += 30 * k; text(c, "Route ready", x, y, 15 * k, muted(), bold);
            for (String line : wrap(s.destination, w, 22 * k, bold, 2)) { if (y + 28 * k > r.bottom - pad) break; y += 27 * k; text(c, line, x, y, 22 * k, ink(), bold); }
            if (y + 32 * k < r.bottom) { y += 32 * k; text(c, fit(Gabi.minutes(s.seconds) + " · " + distance(s.remaining), w, 20 * k, black), x, y, 20 * k, pal.accent, black); }
        } else {
            y += 28 * k; text(c, fit(s.ready ? s.regionName : "Getting the map ready…", w, 17 * k, bold), x, y, 17 * k, soft(), bold);
            if (y + 24 * k < r.bottom) { y += 24 * k; text(c, fit("Choose a destination on your phone", w, 14 * k, semi), x, y, 14 * k, muted(), semi); }
        }
    }

    /** While editing: each card outlined and named, a corner handle to resize, and the arrow's spot. */
    private void outlines(Canvas c, RectF turn, RectF trip, float ax, float ay) {
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(3); p.setColor(pal.accent); p.setPathEffect(new DashPathEffect(new float[]{12, 8}, 0));
        c.drawRoundRect(turn, 18, 18, p); c.drawRoundRect(trip, 18, 18, p); c.drawCircle(ax, ay, 34, p); p.setPathEffect(null);
        for (RectF r : new RectF[]{turn, trip}) { p.setStyle(Paint.Style.FILL); p.setColor(pal.accent); c.drawCircle(r.right - 4, r.bottom - 4, 11, p); p.setColor(0xFF1A1203); c.drawCircle(r.right - 4, r.bottom - 4, 4, p); }
        label(c, "TURN", turn.left + 10, turn.top - 6); label(c, "TRIP", trip.left + 10, trip.top - 6); label(c, "ARROW", ax - 26, ay - 40);
    }
    private void label(Canvas c, String t, float x, float y) {
        p.setTextSize(13); p.setTypeface(black); p.setLetterSpacing(0.1f); float tw = p.measureText(t);
        float top = Math.max(2, y - 16); RectF b = new RectF(x - 6, top, x + tw + 8, top + 22);
        p.setStyle(Paint.Style.FILL); p.setColor(pal.accent); c.drawRoundRect(b, 8, 8, p); text(c, t, x, top + 16, 13, 0xFF1A1203, black); p.setLetterSpacing(0);
    }

    private static String distance(double m) { return m < 1000 ? Math.max(0, Math.round(m / 10) * 10) + " m" : String.format(Locale.US, "%.1f km", m / 1000); }
    private static String streetOf(JSONObject m, String fallback) {
        if (m == null) return fallback;
        JSONArray n = m.optJSONArray("street_names"); if (n != null && n.length() > 0) return n.optString(0);
        n = m.optJSONArray("begin_street_names"); if (n != null && n.length() > 0) return n.optString(0);
        return m.optString("instruction", fallback);
    }

    // ---------------------------------------------------------------- map
    private final Path[] layers = {new Path(), new Path(), new Path(), new Path()};
    /** Drawing order: small streets, tertiary, secondary, then main roads on top. */
    private static int layer(String kind) { return kind.contains("primary") || kind.contains("trunk") || kind.contains("motorway") ? 3 : kind.contains("secondary") ? 2 : kind.contains("tertiary") ? 1 : 0; }
    private float cx, cy, cos, sin, tilt; private double clat, clon, mpp = 3, coslat; private long lastFrame;
    private final android.graphics.Camera lens = new android.graphics.Camera(); private final Matrix tiltMatrix = new Matrix();
    private List<NavSession.Road> nearCache; private Object nearIndex; private double nearLat, nearLon, nearReach;
    /**
     * True when the next frame would look like the last one: the rider standing still (a red light, parked), zoom and
     * tilt settled. The bike display then needs only a few frames a second; the encoder repeats the picture in between.
     */
    public boolean still() {
        if (s.layoutEditing || s.calibration) return false;
        long now = android.os.SystemClock.uptimeMillis();
        // On a ride the scene counts as still only after 15 s stopped, so creeping through traffic, or GPS speed
        // wobbling around walking pace, never lowers the frame rate.
        if (s.active && (s.simulation || motion.speed > 0.4)) movedAt = now;
        return now - movedAt > (s.active ? 15000 : 3000) && Math.abs(targetMpp() - mpp) < 0.02 * mpp && Math.abs(targetTilt() - tilt) < 0.3f;
    }
    private long movedAt = android.os.SystemClock.uptimeMillis();
    private double prevLat, prevLon, prevMpp; private float prevTilt, prevBearing;
    private volatile float pixelsPerSecond = 1000;
    /** How fast the bike picture moved over the last frame, in screen pixels a second (see drawMap). */
    public float pixelsPerSecond() { return pixelsPerSecond; }
    /** Metres per pixel: close like Google Maps, a little wider at speed, closer for a turn and when arriving. */
    private double targetMpp() {
        if (!s.active) return 3.0;
        double z = Math.max(1.0, Math.min(2.4, 1.0 + motion.speed * 0.07));
        if (s.distanceToTurn > 0 && s.distanceToTurn < 200) z *= 0.8;
        if (s.remaining < 400) z = Math.min(z, 0.85);
        return z;
    }
    private float targetTilt() { return !s.active ? 0 : s.remaining < 400 ? 58 : 48; }
    private void project(double lat, double lon, float[] out) {
        float x = (float)((lon - clon) * coslat * 111195 / mpp), y = (float)(-(lat - clat) * 111195 / mpp);
        out[0] = cx + x * cos - y * sin; out[1] = cy + x * sin + y * cos;
    }
    /** Where a place lands on screen after the tilt; far off screen when it lies behind the tilted view. */
    private void screen(double lat, double lon, float[] out) { project(lat, lon, out); if (out[1] > yMax) { out[0] = out[1] = -9999; return; } tiltMatrix.mapPoints(out); }

    // The tilt is a camera 720 px in front of the screen. A point far enough behind the arrow passes behind that camera and
    // projects flipped, so a road crossing it became a huge wedge on the display. Lines are cut at yMax, a little past the
    // bottom edge of the screen and well before the camera.
    private float yMax = Float.MAX_VALUE, lastX, lastY; private boolean lastIn;
    private void clipLimit(float h) {
        double t = Math.toRadians(tilt), f = 720, below = Math.max(40, h - cy);
        if (tilt < 1) { yMax = Float.MAX_VALUE; return; }
        double bottom = below * f / (f * Math.cos(t) + below * Math.sin(t)), near = f / Math.sin(t);
        yMax = cy + (float)Math.min(near * 0.8, bottom * 1.5 + 80);
    }
    private void point(Path l, float x, float y, boolean first) {
        boolean in = y <= yMax;
        if (first) { if (in) l.moveTo(x, y); }
        else if (in && lastIn) l.lineTo(x, y);
        else if (in != lastIn) {
            float t = (yMax - lastY) / (y - lastY), ix = lastX + (x - lastX) * t;
            if (lastIn) l.lineTo(ix, yMax); else { l.moveTo(ix, yMax); l.lineTo(x, y); }
        }
        lastX = x; lastY = y; lastIn = in;
    }

    private void drawMap(Canvas c, float w, float h) {
        p.setStyle(Paint.Style.FILL); p.setColor(land()); c.drawRect(0, 0, w, h, p);
        boolean heading = s.active;
        List<double[]> route = s.shape;
        long now = SystemClock.elapsedRealtime(); float gap = lastFrame == 0 ? 1000f : Math.min(1000f, now - lastFrame), ease = Math.min(1f, gap / 700f); lastFrame = now;
        motion.update(now, gap);
        mpp += (targetMpp() - mpp) * ease; tilt += (targetTilt() - tilt) * ease;
        clat = motion.lat == 0 ? s.viewLat : motion.lat; clon = motion.lon == 0 ? s.viewLon : motion.lon; coslat = Math.cos(Math.toRadians(clat));
        double theta = heading ? Math.toRadians(-motion.bearing) : 0; cos = (float)Math.cos(theta); sin = (float)Math.sin(theta);
        cx = s.arrowX * w; cy = s.arrowY * h;
        // How fast the picture moves on screen, in pixels a second: travel (doubled, since the tilt magnifies the road near
        // the bottom edge), turning at the screen's corners, and zoom and tilt changes. The encoder draws only as often as
        // that needs, so a straight road at an even speed costs far fewer frames than a turn.
        double half = Math.hypot(w, h) / 2;
        if (prevMpp > 0) {
            double move = Math.hypot((clon - prevLon) * coslat, clat - prevLat) * 111195 / mpp * 2;
            double turn = heading ? Math.abs(((motion.bearing - prevBearing + 540) % 360) - 180) * Math.PI / 180 * half : 0;
            double zoom = Math.abs(mpp - prevMpp) / mpp * half + Math.abs(tilt - prevTilt) * 8;
            pixelsPerSecond = (float)((move + turn + zoom) * 1000 / Math.max(1f, gap));
        }
        prevLat = clat; prevLon = clon; prevMpp = mpp; prevTilt = tilt; prevBearing = motion.bearing;
        // Tilt the map away from the rider like the phone's 3D view: the road ahead recedes towards the horizon.
        lens.save(); lens.setLocation(0, 0, -10); lens.rotateX(tilt); lens.getMatrix(tiltMatrix); lens.restore();
        tiltMatrix.preTranslate(-cx, -cy); tiltMatrix.postTranslate(cx, cy); clipLimit(h);
        // How far the map reaches on screen: flat, the screen's corners; tilted, about 1,100 px ahead of the arrow before the
        // horizon squeezes everything (worked out from the lens: tilt 48-58 degrees, camera 10 inches away).
        double reach = Math.max(Math.hypot(w, h) * 1.05, tilt > 5 ? 1150 : 0) * mpp, dLat = reach / 111195, dLon = reach / (111195 * coslat);
        float[] pt = new float[2];
        c.save(); c.concat(tiltMatrix);
        p.setStyle(Paint.Style.STROKE); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
        float roadScale = (float)Math.max(0.6, Math.min(1.8, 2.0 / mpp));
        // Every road of one kind goes into one path and is drawn in a single call: thousands of separate draws per frame
        // were most of the cost. Roads shorter than about two pixels are skipped.
        // The roads around are looked up for a box a quarter larger than the view and reused until the rider has moved a
        // fifth of the way across it, instead of on every frame.
        if (nearCache == null || nearIndex != s.roadIndex || Math.abs(clat - nearLat) * 111195 > reach * 0.2 || Math.abs(clon - nearLon) * 111195 * coslat > reach * 0.2
            || reach > nearReach || reach < nearReach * 0.6) {
            double q = 1.25; nearCache = s.roadIndex.near(clat - dLat * q, clon - dLon * q, clat + dLat * q, clon + dLon * q);
            nearIndex = s.roadIndex; nearLat = clat; nearLon = clon; nearReach = reach * q;
        }
        List<NavSession.Road> nearby = nearCache;
        for (Path l : layers) l.reset();
        double tiny = 2 * mpp / 111195;
        for (NavSession.Road road : nearby) {
            if (road.size() < 2 || path(road.kind)) continue;
            if (road.maxLat - road.minLat < tiny && road.maxLon - road.minLon < tiny) continue;
            if (road.maxLat < clat - dLat || road.minLat > clat + dLat || road.maxLon < clon - dLon || road.minLon > clon + dLon) continue;
            Path l = layers[layer(road.kind)];
            for (int j = 0; j < road.size(); j++) { project(road.lat(j), road.lon(j), pt); point(l, pt[0], pt[1], j == 0); }
        }
        String[] kinds = {"residential", "tertiary", "secondary", "primary"};
        for (int i = 0; i < layers.length; i++) { p.setColor(road(kinds[i])); p.setStrokeWidth(roadWidth(kinds[i]) * roadScale); c.drawPath(layers[i], p); }
        if (route.size() > 1) {
            int from = s.active ? Math.max(0, Math.min(motion.routeFrom, route.size() - 1)) : 0;
            path.reset();
            boolean first = true;
            if (s.active) { project(clat, clon, pt); point(path, pt[0], pt[1], true); first = false; }
            // Only the stretch that can be on screen: on a long trip, walking the route to the far destination every frame
            // was wasted work. The point just past the limit is kept so the line still runs off the edge.
            double[] cum = s.cumulative; boolean measured = cum.length == route.size();
            double limit = measured ? cum[Math.min(from, cum.length - 1)] + reach * 1.5 : Double.MAX_VALUE;
            for (int i = from + (s.active ? 1 : 0); i < route.size(); i++) { double[] a = route.get(i); project(a[0], a[1], pt); point(path, pt[0], pt[1], first); first = false; if (measured && cum[i] > limit) break; }
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(17); p.setColor(pal.night ? 0xFF3B2804 : 0xFF8A5600); c.drawPath(path, p);
            p.setStrokeWidth(10); p.setColor(pal.accent); c.drawPath(path, p);
        }
        c.restore();
        // Pins and the rider stand upright on the tilted map.
        if (route.size() > 1) {
            screen(s.destLat, s.destLon, pt);
            p.setStyle(Paint.Style.FILL); p.setColor(0xFFFFFFFF); c.drawCircle(pt[0], pt[1], 15, p); p.setColor(pal.accent); c.drawCircle(pt[0], pt[1], 12, p); p.setColor(0xFF1A1203); c.drawCircle(pt[0], pt[1], 4.5f, p);
        }
        if (!route.isEmpty() && (!s.active || s.remaining < 1000)) parking(c, 0, 0, w, h, pt);
        if (s.active || s.fixAt > 0) {
            screen(clat, clon, pt);
            c.save(); c.rotate(heading ? 0 : motion.bearing, pt[0], pt[1]);
            p.setStyle(Paint.Style.FILL); p.setColor(pal.night ? 0x40FFB020 : 0x30F5A300); c.drawCircle(pt[0], pt[1], 30, p);
            path.reset(); path.moveTo(pt[0], pt[1] - 20); path.lineTo(pt[0] + 15, pt[1] + 15); path.lineTo(pt[0], pt[1] + 7); path.lineTo(pt[0] - 15, pt[1] + 15); path.close();
            p.setColor(0xFFFFFFFF); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(6); c.drawPath(path, p);
            p.setStyle(Paint.Style.FILL); p.setColor(pal.night ? 0xFF1A1203 : 0xFF121A21); c.drawPath(path, p);
            c.restore();
        }
        if (s.simulation) {
            p.setTextSize(15); p.setTypeface(black); p.setLetterSpacing(0.1f); float tw = p.measureText("DEMO");
            RectF b = new RectF(w - tw - 40, 16, w - 16, 46); p.setStyle(Paint.Style.FILL); p.setColor(pal.accent); c.drawRoundRect(b, 15, 15, p);
            text(c, "DEMO", b.left + 12, b.bottom - 9, 15, 0xFF1A1203, black); p.setLetterSpacing(0);
        }
        p.setTextSize(11); p.setTypeface(semi); float aw = p.measureText("© OpenStreetMap contributors");
        text(c, "© OpenStreetMap contributors", w - aw - 10, h - 8, 11, muted(), semi);
    }

    private void parking(Canvas c, float left, float top, float w, float h, float[] pt) {
        ArrayList<RectF> occupied = new ArrayList<>(); int shown = 0;
        for (int i = 0; i < s.nearbyParking.size() && shown < 3; i++) try {
            JSONObject lot = s.nearbyParking.get(i); double a = lot.getDouble("lat"), b = lot.getDouble("lon");
            if (GeoMath.distance(a, b, s.destLat, s.destLon) > 300) continue;
            screen(a, b, pt); float x = pt[0], y = pt[1];
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
