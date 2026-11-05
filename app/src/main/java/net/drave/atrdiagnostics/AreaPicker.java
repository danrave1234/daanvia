package net.drave.atrdiagnostics;

import android.app.Activity;
import android.os.Handler;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.style.sources.GeoJsonSource;
import java.util.ArrayList;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import static net.drave.atrdiagnostics.Gabi.Role.*;

/** Choosing a map area to download: a frame over the map, a town search to jump it, and the size and progress in the sheet. */
final class AreaPicker {
    interface Host {
        /** The home screen is choosing an area (Mode.SELECT). */
        boolean selecting();
        /** Switch the home screen to choosing an area. */
        void open();
        /** Back to the map (Mode.EXPLORE). */
        void leave();
        void notice(String text);
        void hideKeyboard();
        /** The map's download-squares source, null until the layers are installed. */
        GeoJsonSource cells();
    }

    private final Activity a; private final NavSession s; private final Gabi.Kit k; private final Handler ui; private final Executor net; private final HomeLayout lay; private final Supplier<SearchIndex> towns; private final Host host;
    private final Gabi.Themer townTheme; private final Gabi.Kit tk;
    private MapLibreMap map;
    /** The sheet's content while choosing, and the close button with the town search over the map. */
    final LinearLayout sheet, top;
    private final Frame frame; private LinearLayout townResults; private EditText townField; private TextView selTitle, selKm, selSize, selBtn; private View selBar; private FrameLayout selTrack;
    private AreaStore.Catalog catalog; private AreaStore.Selection selection; private String catalogError;

    AreaPicker(Activity a, NavSession s, Gabi.Kit k, FrameLayout root, View mapView, Handler ui, Executor net, HomeLayout lay, Supplier<SearchIndex> towns, Host host) {
        this.a = a; this.s = s; this.k = k; this.ui = ui; this.net = net; this.lay = lay; this.towns = towns; this.host = host;
        tk = new Gabi.Kit(a, townTheme = k.t.child());
        frame = new Frame(); frame.setVisibility(View.GONE); root.addView(frame, root.indexOfChild(mapView) + 1, new FrameLayout.LayoutParams(-1, -1));
        top = buildTop(root); sheet = buildSheet();
    }
    void attach(MapLibreMap m) { map = m; }

    /** Dims everything outside the download frame, Google Maps offline style, and draws its corners. */
    private final class Frame extends View {
        final android.graphics.Paint dim = new android.graphics.Paint(), edge = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG); final android.graphics.Path hole = new android.graphics.Path();
        final android.graphics.RectF rect = new android.graphics.RectF();
        Frame() { super(a); edge.setStyle(android.graphics.Paint.Style.STROKE); edge.setStrokeWidth(k.dp(3)); edge.setStrokeCap(android.graphics.Paint.Cap.ROUND); setWillNotDraw(false); }
        android.graphics.RectF rect() {
            if (lay.landscape) { rect.set(lay.panelW + k.dp(36), lay.top + k.dp(24), getWidth() - k.dp(90), getHeight() - lay.bottom - k.dp(24)); return rect; }
            float top = lay.top + k.dp(132), bottom = getHeight() - Math.max(lay.sheet, k.dp(200)) - k.dp(20);
            rect.set(k.dp(28), top, getWidth() - k.dp(28), Math.max(top + k.dp(120), bottom)); return rect;
        }
        protected void onDraw(android.graphics.Canvas c) {
            android.graphics.RectF r = rect(); float rad = k.dp(18), arm = k.dp(26);
            dim.setColor(k.t.palette.night ? 0x99000000 : 0x66101820);
            hole.reset(); hole.setFillType(android.graphics.Path.FillType.EVEN_ODD); hole.addRect(0, 0, getWidth(), getHeight(), android.graphics.Path.Direction.CW); hole.addRoundRect(r, rad, rad, android.graphics.Path.Direction.CW);
            c.drawPath(hole, dim);
            edge.setColor(k.t.palette.accent);
            c.drawLine(r.left, r.top + arm, r.left, r.top + rad, edge); c.drawArc(r.left, r.top, r.left + 2 * rad, r.top + 2 * rad, 180, 90, false, edge); c.drawLine(r.left + rad, r.top, r.left + arm, r.top, edge);
            c.drawLine(r.right - arm, r.top, r.right - rad, r.top, edge); c.drawArc(r.right - 2 * rad, r.top, r.right, r.top + 2 * rad, 270, 90, false, edge); c.drawLine(r.right, r.top + rad, r.right, r.top + arm, edge);
            c.drawLine(r.right, r.bottom - arm, r.right, r.bottom - rad, edge); c.drawArc(r.right - 2 * rad, r.bottom - 2 * rad, r.right, r.bottom, 0, 90, false, edge); c.drawLine(r.right - rad, r.bottom, r.right - arm, r.bottom, edge);
            c.drawLine(r.left + arm, r.bottom, r.left + rad, r.bottom, edge); c.drawArc(r.left, r.bottom - 2 * rad, r.left + 2 * rad, r.bottom, 90, 90, false, edge); c.drawLine(r.left, r.bottom - rad, r.left, r.bottom - arm, edge);
        }
    }
    /** Top: close, and a town search to jump the frame. */
    private LinearLayout buildTop(FrameLayout root) {
        LinearLayout selTop = k.column(6); selTop.setVisibility(View.GONE);
        LinearLayout bar = k.row(10); bar.setPadding(k.dp(6), 0, k.dp(16), 0); k.glass(bar, 25);
        bar.addView(k.circle(IconDrawable.CLOSE, 40, CHIP, INK_SOFT, this::close));
        townField = new EditText(a); townField.setBackground(null); townField.setSingleLine(); townField.setTextSize(15.5f); townField.setTypeface(Gabi.font(a, 550)); townField.setHint("Find a city or town");
        townField.setImeOptions(EditorInfo.IME_ACTION_SEARCH); townField.setPadding(0, 0, 0, 0);
        k.t.add(p -> { townField.setTextColor(p.ink); townField.setHintTextColor(p.muted); });
        bar.addView(townField, new LinearLayout.LayoutParams(0, -1, 1));
        selTop.addView(bar, new LinearLayout.LayoutParams(-1, k.dp(52)));
        townResults = k.column(0); townResults.setPadding(k.dp(14), k.dp(4), k.dp(14), k.dp(4)); k.glass(townResults, 18); townResults.setVisibility(View.GONE);
        selTop.addView(townResults);
        townField.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence x, int b, int c, int d) {} public void onTextChanged(CharSequence x, int b, int c, int d) {}
            public void afterTextChanged(Editable e) { paintTowns(e.toString()); }
        });
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP); tl.leftMargin = tl.rightMargin = k.dp(14);
        root.addView(selTop, tl);
        return selTop;
    }
    private LinearLayout buildSheet() {
        LinearLayout c = k.column(0);
        LinearLayout head = k.row(8);
        selTitle = k.text("Download this area?", 19, 750, INK); head.addView(selTitle, new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(k.info("Move and zoom the map so the frame covers where you ride, then download. Daanvia saves that area on your phone, so the map, search and directions work without signal. Bigger areas take more space, and very big areas are limited so the app stays fast. Manage downloaded areas in Settings › Maps."));
        c.addView(head);
        selKm = k.num("", 15, 650, INK_SOFT); selKm.setPadding(0, k.dp(8), 0, 0); c.addView(selKm);
        selSize = k.text("", 13, 550, MUTED); selSize.setPadding(0, k.dp(4), 0, k.dp(14)); c.addView(selSize);
        selTrack = new FrameLayout(a); k.fill(selTrack, 3, CHIP); selBar = new View(a); k.fill(selBar, 3, ACCENT); selBar.setPivotX(0);
        selTrack.addView(selBar, new FrameLayout.LayoutParams(-1, -1)); selTrack.setVisibility(View.GONE);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, k.dp(6)); tp.bottomMargin = k.dp(14); c.addView(selTrack, tp);
        selBtn = k.primary("Download", this::download); c.addView(selBtn);
        return c;
    }
    private void paintTowns(String q) {
        townResults.removeAllViews(); townTheme.clear();
        SearchIndex towns = this.towns.get();
        if (towns == null || q.trim().isEmpty()) { townResults.setVisibility(View.GONE); return; }
        LatLng t = map.getCameraPosition().target;
        int[] hits = towns.query(q, t.getLatitude(), t.getLongitude(), 5);
        for (int h : hits) { double la = towns.lat[h], lo = towns.lon[h];
            townResults.addView(tk.listRow(IconDrawable.PIN, SearchPanel.highlight(towns.names[h], q, k.t.palette.accent), null, null, () -> { host.hideKeyboard(); townField.setText(""); map.animateCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(la, lo), 10.4), 1000); })); }
        townResults.setVisibility(hits.length == 0 ? View.GONE : View.VISIBLE);
    }

    // ---------------------------------------------------------------- opening and closing
    /** The frame and town search show only while choosing; the download squares leave the map with them. */
    void show(boolean selecting) {
        frame.setVisibility(selecting ? View.VISIBLE : View.GONE); Gabi.show(top, selecting, -k.dp(14));
        if (!selecting) { GeoJsonSource cells = host.cells(); if (cells != null) cells.setGeoJson(MapStyle.empty()); }
    }
    /** The frame follows the sheet and the screen's layout. */
    void invalidate() { frame.invalidate(); }
    /** The rider's spot: the current fix, else the phone's last known location, else null. */
    double[] whereAmI() {
        if (s.fixAt > 0) return new double[]{s.lat, s.lon};
        if (!Here.allowed(a)) return null;
        android.location.Location last = null;
        try { android.location.LocationManager lm = (android.location.LocationManager)a.getSystemService(Activity.LOCATION_SERVICE);
            for (String pr : lm.getProviders(true)) { android.location.Location l = lm.getLastKnownLocation(pr); if (l != null && (last == null || l.getTime() > last.getTime())) last = l; } } catch (SecurityException ignored) {}
        return last == null ? null : new double[]{last.getLatitude(), last.getLongitude()};
    }
    /** Open the frame, on the rider's location when known. Nothing downloads until they tap Download. */
    void open(double[] at) {
        if (s.active) { host.notice("End the ride before downloading maps"); return; }
        host.open();
        center(at);
        catalog = AreaStore.cached(a); reselect.run();
        net.execute(() -> {
            try { AreaStore.Catalog c = AreaStore.fetch(a); ui.post(() -> { if (a.isDestroyed()) return; catalog = c; catalogError = null; reselect.run(); }); }
            catch (Exception e) { boolean missing = e.getMessage() != null && e.getMessage().contains("isn’t available"); ui.post(() -> { if (a.isDestroyed()) return; catalogError = missing ? "Maps aren’t available to download yet." : "Connect to the internet to download a map."; if (catalog.cells.isEmpty() && host.selecting()) { selKm.setText(missing ? "Maps aren’t available to download yet." : "Connect to the internet to download a map."); selSize.setText("After that, Daanvia works without signal."); selBtn.setText("Try again"); selBtn.setAlpha(1f); selBtn.setOnClickListener(v -> open(null)); } }); }
        });
    }
    /** Frame the rider's spot: a known location now, else a quick fix if location is allowed, else the whole country. */
    void center(double[] at) {
        if (map == null) return;
        double[] h = at != null ? at : whereAmI();
        map.animateCamera(CameraUpdateFactory.newCameraPosition(new CameraPosition.Builder(map.getCameraPosition()).target(h == null ? new LatLng(12.3, 122.6) : new LatLng(h[0], h[1]))
            .zoom(h == null ? 5.2 : 10.4).tilt(0).bearing(0).padding(0, 0, 0, 0).build()), 900);
        if (h != null || at != null) return;
        if (!Here.allowed(a)) return;
        android.location.LocationManager lm = (android.location.LocationManager)a.getSystemService(Activity.LOCATION_SERVICE);
        String provider = lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER) ? android.location.LocationManager.NETWORK_PROVIDER : android.location.LocationManager.GPS_PROVIDER;
        android.os.CancellationSignal stop = new android.os.CancellationSignal(); ui.postDelayed(stop::cancel, 15000);
        try { androidx.core.location.LocationManagerCompat.getCurrentLocation(lm, provider, stop, a.getMainExecutor(), loc -> {
            if (loc != null && !a.isDestroyed() && host.selecting() && map != null) map.animateCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(loc.getLatitude(), loc.getLongitude()), 10.4), 1000); }); }
        catch (SecurityException ignored) {}
    }
    /** Close: back to the map, unless there's no map at all yet. */
    void close() {
        host.hideKeyboard();
        if (s.noMap) { if (AreaStore.state() == null || !AreaStore.state().running) host.notice("Daanvia needs a downloaded area to give directions"); return; }
        host.leave();
    }

    // ---------------------------------------------------------------- the area under the frame
    /** Size up the area under the frame again, a moment after the map moves. */
    void reselectSoon() { ui.removeCallbacks(reselect); ui.postDelayed(reselect, 90); }
    void reselectNow() { reselect.run(); }
    private final Runnable reselect = this::reselect;
    private void reselect() {
        if (!host.selecting() || map == null) return;
        AreaStore.State st = AreaStore.state(); if (st != null && st.running) { onArea(st); return; }
        selTrack.setVisibility(View.GONE);
        selTitle.setText(s.noMap ? "Choose your map area" : "Download this area?");
        android.graphics.RectF r = frame.rect();
        org.maplibre.android.maps.Projection pr = map.getProjection();
        LatLng a1 = pr.fromScreenLocation(new android.graphics.PointF(r.left, r.top)), b = pr.fromScreenLocation(new android.graphics.PointF(r.right, r.bottom)), m = pr.fromScreenLocation(new android.graphics.PointF(r.centerX(), r.centerY()));
        double minLat = Math.min(a1.getLatitude(), b.getLatitude()), maxLat = Math.max(a1.getLatitude(), b.getLatitude()), minLon = Math.min(a1.getLongitude(), b.getLongitude()), maxLon = Math.max(a1.getLongitude(), b.getLongitude());
        if (catalog == null) catalog = AreaStore.cached(a);
        selection = AreaStore.select(catalog, minLat, minLon, maxLat, maxLon, m.getLatitude(), m.getLongitude());
        ArrayList<double[]> boxes = new ArrayList<>(); for (AreaStore.Cell c : selection.cells) boxes.add(c.box);
        GeoJsonSource cells = host.cells(); if (cells != null) cells.setGeoJson(MapStyle.boxes(boxes));
        selKm.setText(selection.kmLabel());
        boolean empty = selection.cells.isEmpty(), big = selection.tooBig();
        Gabi.Palette p = k.t.palette;
        if (catalog.cells.isEmpty() && catalogError != null) { selKm.setText(catalogError); selSize.setText("Tap Try again when you have signal."); selSize.setTextColor(p.muted); selBtn.setText("Try again"); selBtn.setAlpha(1f); selBtn.setOnClickListener(v -> open(null)); return; }
        selSize.setText(catalog.cells.isEmpty() ? "Getting the list of maps…" : empty ? "There’s no map for this spot yet. Move the frame over land in the Philippines." : big ? "This area is too big. Zoom in to download a smaller area." :
            "Download uses about " + selection.sizeLabel() + ". Wi-Fi recommended.");
        selSize.setTextColor(big ? p.danger : p.muted);
        selBtn.setText(empty || big ? "Download" : "Download " + selection.sizeLabel());
        selBtn.setOnClickListener(v -> download());
        selBtn.setAlpha(empty || big ? 0.45f : 1f);
    }
    private void download() {
        if (selection == null || selection.cells.isEmpty() || selection.tooBig()) { Gabi.shake(selBtn); return; }
        String name = selection.label.isEmpty() ? "My area" : selection.label + " area";
        LatLng m = map.getCameraPosition().target;
        AreaStore.download(a, catalog, selection.cells, name, new double[]{m.getLatitude(), m.getLongitude()}, null);
    }
    /** Download progress on the frame's sheet, while choosing. */
    void onArea(AreaStore.State st) {
        if (st.running) {
            selTitle.setText("Downloading " + st.name); selKm.setText(st.progressLabel()); selSize.setText("Keep Daanvia open until it finishes."); selSize.setTextColor(k.t.palette.muted);
            selTrack.setVisibility(View.VISIBLE); selBar.setScaleX(Math.max(0.02f, st.percent() / 100f));
            selBtn.setText("Cancel"); selBtn.setAlpha(1f); selBtn.setOnClickListener(v -> AreaStore.cancel());
        } else if (st.finished) { selTitle.setText("Setting up " + st.name); selKm.setText("Almost ready…"); selBar.setScaleX(1f); }
        else if (st.error != null) { host.notice(st.error); reselect.run(); }
    }
}
