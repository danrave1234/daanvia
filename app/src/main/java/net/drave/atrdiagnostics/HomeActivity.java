package net.drave.atrdiagnostics;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.*;
import android.text.*;
import android.text.style.ForegroundColorSpan;
import android.view.*;
import android.view.animation.LinearInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import org.json.*;
import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.maps.*;
import org.maplibre.android.style.layers.Layer;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.FeatureCollection;
import java.util.*;
import java.util.concurrent.*;

import static org.maplibre.android.style.layers.PropertyFactory.*;
import static net.drave.atrdiagnostics.Gabi.Role.*;

/**
 * The whole riding app on one map: explore, search, pin, saved places, route preview and the live ride.
 * While it is open it keeps trying to put Daanvia on the saved bike's dashboard, like Carbit does.
 */
public final class HomeActivity extends Activity implements DashboardLink.Host {
    private enum Mode { EXPLORE, SEARCH, PIN, SAVE, ROUTE, RIDE, PARK_FORM }

    private NavSession s; private Gabi.Themer theme; private Gabi.Kit k;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Mode mode = Mode.EXPLORE;

    private MapView mapView; private MapLibreMap map; private org.maplibre.android.maps.Style style; private boolean layersReady;
    private FrameLayout root; private LinearLayout top, chips, fabs; private TextView regionChip, bikeChip, attribution, notice;
    private FrameLayout fabLocate, fabTft; private ImageView fabIcon; private SheetView sheet;
    private int insetTop, insetBottom, insetIme;

    // search
    private LinearLayout searchPanel, results; private EditText field; private SearchIndex index; private int searchToken;
    // explore
    private LinearLayout explore, savedBox, recentsBox; private TextView exploreStatus; private Bookmarks bookmarks;
    // save place
    private LinearLayout saveSheet; private EditText saveName; private TextView saveSub; private double saveLat, saveLon;
    // bike
    private DashboardLink link; private boolean started, askedLinkPermissions; private String linkedQr; private boolean pendingDemo;
    // pin
    private LinearLayout pinSheet; private TextView pinSub; private double dropLat, dropLon;
    // route
    private LinearLayout routeSheet, routeTags, parkRow; private TextView routeName, routeEta, routeMeta, routeStatus, parkText, startBtn, simNote; private FrameLayout routeStar; private View shimmer;
    // parking
    private LinearLayout parkForm; private EditText parkName, parkPrice; private TextView parkError; private String parkBasis = "hour"; private double parkLat, parkLon;
    // ride
    private LinearLayout rideTop, maneuver, thenChip, etaBar; private ImageView maneuverIcon, thenIcon; private TextView maneuverDist, maneuverStreet, thenText, etaMin, etaMeta, endBtn, simBadge, tftBadge;
    private int shownManeuver = -1; private boolean endArmed;

    private double destLat, destLon; private String destName; private boolean pendingPlan, demoTrip;
    private boolean routeCleared; private List<double[]> drawnShape; private List<JSONObject> drawnParking; private Gabi.Palette drawnParkingPalette; private ValueAnimator routeAnim;
    private boolean follow = true, haveShown; private double shownLat, shownLon; private float shownBearing; private ValueAnimator puckAnim, pulse; private long lastFix = -1;
    private long themeCheckedAt; private boolean centeredOnRegion;

    private final BroadcastReceiver changes = new BroadcastReceiver() { public void onReceive(Context c, Intent i) { refresh(); } };

    // ================================================================ lifecycle
    public void onCreate(Bundle state) {
        super.onCreate(state);
        s = NavSession.get(this); MapLibre.getInstance(this);
        theme = new Gabi.Themer(Gabi.resolve(this, s.lat, s.lon, s.fixAt > 0)); k = new Gabi.Kit(this, theme);
        Window w = getWindow(); Gabi.edgeToEdge(w);
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        theme.add(p -> Gabi.barIcons(w, p));

        root = new FrameLayout(this); theme.add(p -> root.setBackgroundColor(p.bg));
        mapView = new MapView(this); mapView.onCreate(state); root.addView(mapView, new FrameLayout.LayoutParams(-1, -1));
        buildTop(); buildFabs(); buildRide();
        sheet = new SheetView(this, k);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM); sp.leftMargin = sp.rightMargin = k.dp(8);
        root.addView(sheet, sp);
        sheet.setListener(h -> { float lift = -Math.max(h, mode == Mode.RIDE ? etaBar.getHeight() + k.dp(8) : 0); fabs.setTranslationY(lift); attribution.setTranslationY(lift); });
        buildSearch(); buildNotice();
        bookmarks = new Bookmarks(this);
        explore = buildExplore(); pinSheet = buildPin(); saveSheet = buildSave(); routeSheet = buildRoute(); parkForm = buildParkForm();
        setContentView(root);
        Gabi.insets(root, (l, t, r, b, ime) -> { insetTop = t; insetBottom = b; insetIme = ime; layoutInsets(); });
        sheet.swap(explore);
        mapView.getMapAsync(this::onMap);
        link = new DashboardLink(this, this); linkedQr = savedQr();
        handleIntent(getIntent());
    }
    protected void onNewIntent(Intent i) { super.onNewIntent(i); setIntent(i); handleIntent(i); }

    private void layoutInsets() {
        ((FrameLayout.LayoutParams)top.getLayoutParams()).topMargin = insetTop + k.dp(8); top.requestLayout();
        ((FrameLayout.LayoutParams)rideTop.getLayoutParams()).topMargin = insetTop + k.dp(8); rideTop.requestLayout();
        ((FrameLayout.LayoutParams)sheet.getLayoutParams()).bottomMargin = insetBottom + k.dp(8) + (mode == Mode.PARK_FORM || mode == Mode.SAVE ? insetIme : 0); sheet.requestLayout();
        ((FrameLayout.LayoutParams)etaBar.getLayoutParams()).bottomMargin = insetBottom + k.dp(8); etaBar.requestLayout();
        ((FrameLayout.LayoutParams)fabs.getLayoutParams()).bottomMargin = insetBottom + k.dp(16); fabs.requestLayout();
        ((FrameLayout.LayoutParams)attribution.getLayoutParams()).bottomMargin = insetBottom + k.dp(14); attribution.requestLayout();
        searchPanel.setPadding(k.dp(14), insetTop + k.dp(8), k.dp(14), Math.max(insetBottom, insetIme));
        ((FrameLayout.LayoutParams)notice.getLayoutParams()).topMargin = insetTop + k.dp(70); notice.requestLayout();
    }

    protected void onStart() {
        super.onStart(); mapView.onStart(); started = true;
        androidx.core.content.ContextCompat.registerReceiver(this, changes, new IntentFilter(NavSession.EVENT), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
        String qr = savedQr(); if (!qr.equals(linkedQr)) { linkedQr = qr; if (!qr.isEmpty()) link.acceptQr(qr); }
        refresh(); autoConnect();
    }
    protected void onResume() { super.onResume(); mapView.onResume(); themeCheckedAt = 0; refresh(); }
    protected void onPause() { mapView.onPause(); super.onPause(); }
    protected void onStop() { started = false; ui.removeCallbacks(retryConnect); link.pauseSearch(); try { unregisterReceiver(changes); } catch (Exception ignored) {} mapView.onStop(); super.onStop(); }
    protected void onDestroy() { ui.removeCallbacksAndMessages(null); link.destroy(); if (pulse != null) pulse.cancel(); if (puckAnim != null) puckAnim.cancel(); worker.shutdownNow(); mapView.onDestroy(); super.onDestroy(); }
    public void onLowMemory() { super.onLowMemory(); mapView.onLowMemory(); }
    protected void onSaveInstanceState(Bundle b) { super.onSaveInstanceState(b); mapView.onSaveInstanceState(b); }

    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        switch (mode) {
            case SEARCH: case PIN: case SAVE: go(Mode.EXPLORE); return;
            case PARK_FORM: go(Mode.EXPLORE); return;
            case ROUTE: closeRoute(); return;
            default: super.onBackPressed();
        }
    }

    // ================================================================ map
    private void onMap(MapLibreMap m) {
        map = m;
        UiSettings u = map.getUiSettings(); u.setCompassEnabled(false); u.setLogoEnabled(false); u.setAttributionEnabled(false);
        map.setMinZoomPreference(5); map.setMaxZoomPreference(19.5);
        map.moveCamera(CameraUpdateFactory.newCameraPosition(new CameraPosition.Builder().target(new LatLng(s.lat, s.lon)).zoom(s.ready ? 12.5 : 11.5).build()));
        map.setStyle(new org.maplibre.android.maps.Style.Builder().fromJson(MapStyle.base(theme.palette)), st -> { style = st; refresh(); });
        map.addOnMapLongClickListener(p -> { onLongPress(p.getLatitude(), p.getLongitude()); return true; });
        map.addOnMapClickListener(p -> { if (mode == Mode.PIN) { go(Mode.EXPLORE); return true; } return false; });
        map.addOnCameraMoveStartedListener(reason -> {
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE && mode == Mode.RIDE && follow) { follow = false; paintLocateFab(); }
        });
    }

    private void installLayers() {
        if (layersReady || style == null || !s.ready) return;
        MapStyle.install(this, style, s.roadsJson); MapStyle.paint(style, theme.palette); layersReady = true;
        if (s.fixAt == 0 && !s.active && !centeredOnRegion) { centeredOnRegion = true; map.animateCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(s.homeLat(), s.homeLon()), 13.2), 1200); }
        index(); paintRecents();
    }
    private GeoJsonSource src(String id) { return style == null ? null : (GeoJsonSource)style.getSource(id); }

    // ================================================================ chrome
    private void buildTop() {
        top = k.column(8);
        FrameLayout pill = new FrameLayout(this); k.glass(pill, 25);
        LinearLayout pr = k.row(10); pr.setPadding(k.dp(18), 0, k.dp(6), 0);
        pr.addView(k.icon(IconDrawable.SEARCH, 20, MUTED));
        TextView hint = k.single(k.text("Where to?", 16, 500, MUTED)); pr.addView(hint, new LinearLayout.LayoutParams(0, -2, 1));
        pr.addView(k.circle(IconDrawable.GEAR, 40, CHIP, INK_SOFT, this::openSettings));
        pill.addView(pr, new FrameLayout.LayoutParams(-1, k.dp(52)));
        Gabi.press(pill, () -> go(Mode.SEARCH));
        top.addView(pill, new LinearLayout.LayoutParams(-1, k.dp(52)));
        chips = k.row(6);
        regionChip = chip(""); bikeChip = chip("");
        Gabi.press(bikeChip, this::openSettings);
        chips.addView(regionChip); chips.addView(bikeChip);
        top.addView(chips);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP); lp.leftMargin = lp.rightMargin = k.dp(14);
        root.addView(top, lp);
    }
    private TextView chip(String s) { TextView v = k.text(s, 12, 650, INK_SOFT); v.setPadding(k.dp(10), k.dp(7), k.dp(12), k.dp(7)); k.glass(v, 99); v.setCompoundDrawablePadding(k.dp(7)); v.setElevation(k.dp(4)); return v; }
    private void dotChip(TextView v, String text, Gabi.Role dot) {
        v.setText(text);
        android.graphics.drawable.GradientDrawable d = Gabi.round(dot.color(theme.palette), k.dp(4)); d.setBounds(0, 0, k.dp(8), k.dp(8));
        v.setCompoundDrawablesRelative(d, null, null, null);
    }

    private void buildFabs() {
        fabs = k.column(10);
        fabLocate = k.circle(IconDrawable.LOC, 50, GLASS, ACCENT, this::onLocateFab);
        fabIcon = (ImageView)fabLocate.getChildAt(0);
        fabs.addView(fabLocate);
        fabTft = k.circle(IconDrawable.TFT, 50, GLASS, INK, this::openSettings); fabTft.setVisibility(View.GONE); fabs.addView(fabTft);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END); lp.rightMargin = k.dp(16);
        root.addView(fabs, lp);
        attribution = k.text("© OpenStreetMap contributors", 10.5f, 500, MUTED);
        FrameLayout.LayoutParams ap = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START); ap.leftMargin = k.dp(18);
        root.addView(attribution, ap);
    }
    private void paintLocateFab() {
        boolean recenter = mode == Mode.RIDE && !follow;
        ((IconDrawable)fabIcon.getDrawable()).setColor(recenter ? theme.palette.ink : theme.palette.accent);
        Gabi.show(fabTft, mode == Mode.RIDE, k.dp(10));
        if (mode == Mode.RIDE) Gabi.show(fabLocate, recenter, k.dp(10)); else Gabi.show(fabLocate, mode == Mode.EXPLORE || mode == Mode.PIN || mode == Mode.SAVE, k.dp(10));
    }
    private void onLocateFab() {
        if (mode == Mode.RIDE) { follow = true; paintLocateFab(); lastFix = -1; refresh(); return; }
        if (s.fixAt > 0 && map != null) map.animateCamera(CameraUpdateFactory.newCameraPosition(new CameraPosition.Builder().target(new LatLng(s.lat, s.lon)).zoom(15.5).tilt(0).bearing(0).build()), 900);
        locate();
    }

    private void buildNotice() {
        notice = k.text("", 13.5f, 600, INK); notice.setPadding(k.dp(16), k.dp(12), k.dp(16), k.dp(12)); k.glass(notice, 18); notice.setVisibility(View.GONE); notice.setElevation(k.dp(30));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP); lp.leftMargin = lp.rightMargin = k.dp(14);
        root.addView(notice, lp);
    }
    private final Runnable hideNotice = () -> Gabi.show(notice, false, -k.dp(12));
    private void notice(String text) { notice(text, null); }
    /** Toast-like glass notice; with an action, tapping it runs the action (used for Undo). */
    private void notice(String text, Runnable action) {
        notice.setText(action == null ? text : text + "  ·  Undo"); notice.bringToFront(); Gabi.show(notice, true, -k.dp(12));
        notice.setOnClickListener(action == null ? null : v -> { action.run(); hideNotice.run(); });
        ui.removeCallbacks(hideNotice); ui.postDelayed(hideNotice, action == null ? 3600 : 5000);
    }

    // ================================================================ explore
    private LinearLayout buildExplore() {
        LinearLayout c = k.column(0);
        LinearLayout head = k.row(8);
        TextView title = k.text("Ride somewhere", 19, 750, INK); head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        c.addView(head);
        exploreStatus = k.text("", 13, 500, MUTED); exploreStatus.setPadding(0, k.dp(4), 0, k.dp(14)); c.addView(exploreStatus);
        c.addView(k.label("Saved places"));
        ScrollView sv = new ScrollView(this); sv.setVerticalScrollBarEnabled(false);
        LinearLayout lists = k.column(0); sv.addView(lists);
        savedBox = k.column(0); lists.addView(savedBox);
        TextView rl = k.label("Recent"); rl.setPadding(0, k.dp(12), 0, 0); lists.addView(rl);
        recentsBox = k.column(0); lists.addView(recentsBox);
        c.addView(sv, new LinearLayout.LayoutParams(-1, -2));
        sv.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> { int max = Math.round(getResources().getDisplayMetrics().heightPixels * 0.42f); if (sv.getHeight() > max) { sv.getLayoutParams().height = max; sv.requestLayout(); } });
        return c;
    }
    private LinearLayout starRow(Bookmarks.Place b, CharSequence title, String sub, String trail) {
        LinearLayout row = k.listRow(IconDrawable.STAR_FILLED, title, sub, trail, () -> choose(b.lat, b.lon, b.name));
        ((ImageView)((FrameLayout)row.getChildAt(0)).getChildAt(0)).setImageDrawable(new IconDrawable(IconDrawable.STAR_FILLED, theme.palette.accent));
        return row;
    }
    private void paintSaved() {
        savedBox.removeAllViews();
        List<Bookmarks.Place> saved = bookmarks.all();
        for (Bookmarks.Place b : saved) {
            String d = s.fixAt > 0 ? Gabi.distance(GeoMath.distance(s.lat, s.lon, b.lat, b.lon)) : null;
            LinearLayout row = starRow(b, b.name, s.inRegion(b.lat, b.lon) ? "Hold to remove" : "Outside " + s.regionName + " · hold to remove", d);
            row.setOnLongClickListener(v -> { v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); bookmarks.remove(b.lat, b.lon); paintSaved(); notice("Removed " + b.name, () -> { bookmarks.save(b.name, b.lat, b.lon); paintSaved(); }); return true; });
            savedBox.addView(row);
        }
        if (saved.isEmpty()) savedBox.addView(k.listRow(IconDrawable.STAR, "No saved places yet", "Tap the star on a place, or share one to Daanvia from Google Maps", null, null));
    }
    private void paintRecents() {
        paintSaved();
        recentsBox.removeAllViews();
        JSONArray r = recents(); int shown = 0;
        for (int i = 0; i < r.length() && shown < 3; i++) {
            JSONObject o = r.optJSONObject(i); if (o == null || !s.inRegion(o.optDouble("lat"), o.optDouble("lon"))) continue;
            double lat = o.optDouble("lat"), lon = o.optDouble("lon"); String name = o.optString("name");
            String d = s.fixAt > 0 ? Gabi.distance(GeoMath.distance(s.lat, s.lon, lat, lon)) : null;
            recentsBox.addView(k.listRow(IconDrawable.CLOCK, name, "Recent", d, () -> choose(lat, lon, name))); shown++;
        }
        if (shown == 0) recentsBox.addView(k.listRow(IconDrawable.PIN, "Long-press the map", "Drop a pin to ride there or save it", null, null));
    }
    private JSONArray recents() { try { return new JSONArray(getSharedPreferences("recents", 0).getString("list", "[]")); } catch (JSONException e) { return new JSONArray(); } }
    private void remember(double lat, double lon, String name) {
        if ("Map pin".equals(name)) return;
        try {
            JSONArray old = recents(), out = new JSONArray(); out.put(new JSONObject().put("name", name).put("lat", lat).put("lon", lon));
            for (int i = 0; i < old.length() && out.length() < 8; i++) if (!name.equals(old.optJSONObject(i).optString("name"))) out.put(old.get(i));
            getSharedPreferences("recents", 0).edit().putString("list", out.toString()).apply();
        } catch (JSONException ignored) {}
    }

    // ================================================================ search
    private void buildSearch() {
        searchPanel = k.column(0); searchPanel.setVisibility(View.GONE); searchPanel.setElevation(k.dp(24));
        theme.add(p -> searchPanel.setBackgroundColor(p.scrim));
        LinearLayout fieldRow = k.row(10); fieldRow.setPadding(k.dp(16), 0, k.dp(8), 0);
        theme.add(p -> fieldRow.setBackground(Gabi.round(p.chip, k.dp(26), p.accent, k.dp(1.5f))));
        fieldRow.addView(k.icon(IconDrawable.SEARCH, 20, ACCENT));
        field = new EditText(this); field.setBackground(null); field.setSingleLine(); field.setTextSize(16.5f); field.setTypeface(Gabi.font(this, 550)); field.setHint("Search places on this phone");
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH); field.setPadding(0, 0, 0, 0);
        theme.add(p -> { field.setTextColor(p.ink); field.setHintTextColor(p.muted); field.setHighlightColor(p.accentSoft); });
        fieldRow.addView(field, new LinearLayout.LayoutParams(0, -1, 1));
        fieldRow.addView(k.circle(IconDrawable.CLOSE, 38, CHIP, MUTED, () -> { if (field.length() > 0) field.setText(""); else go(Mode.EXPLORE); }));
        searchPanel.addView(fieldRow, new LinearLayout.LayoutParams(-1, k.dp(54)));
        TextView lab = k.label("Places on this phone"); lab.setPadding(k.dp(4), k.dp(18), 0, k.dp(6)); searchPanel.addView(lab);
        ScrollView sc = new ScrollView(this); sc.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS); sc.setVerticalScrollBarEnabled(false);
        results = k.column(0); sc.addView(results);
        searchPanel.addView(sc, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(searchPanel, new FrameLayout.LayoutParams(-1, -1));
        field.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence a, int b, int c, int d) {} public void onTextChanged(CharSequence a, int b, int c, int d) {}
            public void afterTextChanged(Editable e) { ui.removeCallbacks(runSearch); ui.postDelayed(runSearch, 110); }
        });
        field.setOnEditorActionListener((v, a, e) -> { hideKeyboard(); return true; });
    }
    private void index() {
        if (index != null || !s.ready) return;
        JSONArray places = s.places;
        worker.execute(() -> {
            int n = places.length(); ArrayList<String> names = new ArrayList<>(n); double[] lat = new double[n], lon = new double[n];
            for (int i = 0; i < n; i++) { JSONObject p = places.optJSONObject(i); names.add(p == null ? null : p.optString("name", null)); lat[i] = p == null ? 0 : p.optDouble("lat"); lon[i] = p == null ? 0 : p.optDouble("lon"); }
            SearchIndex built = new SearchIndex(names, lat, lon);
            ui.post(() -> { index = built; if (mode == Mode.SEARCH) runSearch.run(); });
        });
    }
    private final Runnable runSearch = () -> {
        String q = field.getText().toString(); int token = ++searchToken;
        if (index == null) { showResults(q, null); return; }
        double fromLat = s.fixAt > 0 ? s.lat : map != null ? map.getCameraPosition().target.getLatitude() : s.lat;
        double fromLon = s.fixAt > 0 ? s.lon : map != null ? map.getCameraPosition().target.getLongitude() : s.lon;
        SearchIndex idx = index;
        worker.execute(() -> { int[] hits = idx.query(q, fromLat, fromLon, 30); ui.post(() -> { if (token == searchToken) showResults(q, new Object[]{hits, fromLat, fromLon}); }); });
    };
    private void showResults(String q, Object[] r) {
        results.removeAllViews();
        if (r == null) { results.addView(k.listRow(IconDrawable.CLOCK, s.ready ? "Preparing search…" : s.status, null, null, null)); return; }
        int[] hits = (int[])r[0]; double fromLat = (double)r[1], fromLon = (double)r[2];
        for (Bookmarks.Place b : bookmarks.match(q)) results.addView(starRow(b, highlight(b.name, q), "Saved", Gabi.distance(GeoMath.distance(fromLat, fromLon, b.lat, b.lon))));
        boolean anySaved = results.getChildCount() > 0;
        if (q.trim().isEmpty()) {
            JSONArray rec = recents();
            for (int i = 0; i < rec.length() && i < 6; i++) { JSONObject o = rec.optJSONObject(i); double la = o.optDouble("lat"), lo = o.optDouble("lon"); String n = o.optString("name");
                results.addView(k.listRow(IconDrawable.CLOCK, n, "Recent", Gabi.distance(GeoMath.distance(fromLat, fromLon, la, lo)), () -> choose(la, lo, n))); }
            results.addView(k.listRow(IconDrawable.LOC, "Choose on the map", "Long-press anywhere to drop a pin", null, () -> { go(Mode.EXPLORE); notice("Long-press the map where you want to go"); }));
        } else if (hits.length == 0 && !anySaved) {
            results.addView(k.listRow(IconDrawable.SEARCH, "No places match “" + q.trim() + "”", "Not in the offline map. Share it to Daanvia from Google Maps to save it, or long-press the map.", null, null));
        } else for (int h : hits) {
            String name = index.names[h]; double la = index.lat[h], lo = index.lon[h];
            results.addView(k.listRow(IconDrawable.PIN, highlight(name, q), null, Gabi.distance(GeoMath.distance(fromLat, fromLon, la, lo)), () -> choose(la, lo, name)));
        }
        for (int i = 0; i < results.getChildCount(); i++) {
            View v = results.getChildAt(i); v.setAlpha(0f); v.setTranslationY(k.dp(12));
            v.animate().alpha(1f).translationY(0).setStartDelay(Math.min(i, 10) * 22L).setDuration(360).setInterpolator(Gabi.EASE_OUT).start();
        }
    }
    private CharSequence highlight(String name, String q) {
        SpannableString sp = new SpannableString(name);
        String lower = name.toLowerCase(Locale.ROOT);
        for (String w : q.trim().toLowerCase(Locale.ROOT).split("\\s+")) {
            if (w.isEmpty()) continue; int at = lower.indexOf(w);
            if (at >= 0) sp.setSpan(new ForegroundColorSpan(theme.palette.accent), at, at + w.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sp;
    }
    private void hideKeyboard() { ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(root.getWindowToken(), 0); }

    // ================================================================ pin
    private LinearLayout buildPin() {
        LinearLayout c = k.column(0);
        LinearLayout head = k.row(10);
        LinearLayout tc = k.column(3); tc.addView(k.text("Dropped pin", 19, 750, INK)); pinSub = k.text("", 13, 500, MUTED); tc.addView(pinSub);
        head.addView(tc, new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(k.circle(IconDrawable.CLOSE, 38, CHIP, MUTED, () -> go(Mode.EXPLORE)));
        c.addView(head);
        LinearLayout btns = k.row(8); btns.setPadding(0, k.dp(16), 0, 0);
        btns.addView(k.circle(IconDrawable.STAR, 56, CHIP, ACCENT, () -> openSave("", dropLat, dropLon)));
        btns.addView(k.primary("Ride here", () -> choose(dropLat, dropLon, "Map pin")), new LinearLayout.LayoutParams(0, -2, 1));
        c.addView(btns);
        TextView rate = k.text("Add a motorcycle parking rate here", 13, 600, MUTED); rate.setPadding(0, k.dp(12), 0, k.dp(2)); rate.setGravity(Gravity.CENTER);
        Gabi.press(rate, () -> { parkLat = dropLat; parkLon = dropLon; go(Mode.PARK_FORM); });
        c.addView(rate);
        return c;
    }

    // ================================================================ save place
    private LinearLayout buildSave() {
        LinearLayout c = k.column(10);
        LinearLayout head = k.row(10);
        LinearLayout tc = k.column(3); tc.addView(k.text("Save place", 19, 750, INK)); saveSub = k.text("", 13, 500, MUTED); tc.addView(saveSub);
        head.addView(tc, new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(k.circle(IconDrawable.CLOSE, 38, CHIP, MUTED, () -> go(Mode.EXPLORE)));
        c.addView(head);
        saveName = input("Name, e.g. Balay ni Drave", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS); c.addView(saveName, new LinearLayout.LayoutParams(-1, k.dp(52)));
        LinearLayout btns = k.row(8);
        btns.addView(k.ghost("Ride here", () -> { String n = saveName.getText().toString().trim(); choose(saveLat, saveLon, n.isEmpty() ? "Map pin" : n); }), new LinearLayout.LayoutParams(0, -2, 1));
        btns.addView(k.primary("Save", () -> {
            String n = saveName.getText().toString().trim(); if (n.isEmpty()) { Gabi.shake(saveName); return; }
            bookmarks.save(n, saveLat, saveLon); hideKeyboard(); go(Mode.EXPLORE); notice("Saved " + n + ". Search finds it offline now.");
        }), new LinearLayout.LayoutParams(0, -2, 1.3f));
        c.addView(btns);
        return c;
    }
    private void openSave(String name, double lat, double lon) {
        if (s.active) { notice("End the ride before saving places"); return; }
        saveLat = lat; saveLon = lon; saveName.setText(name); saveName.setSelection(saveName.length());
        saveSub.setText(s.fixAt > 0 ? Gabi.distance(GeoMath.distance(s.lat, s.lon, lat, lon)) + " from you" : s.inRegion(lat, lon) ? "Inside " + s.regionName : "Outside the installed offline region · saved for later");
        dropLat = lat; dropLon = lon;
        go(Mode.SAVE);
        if (map != null) map.animateCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(lat, lon), 16), 900);
    }
    private void onLongPress(double lat, double lon) {
        if (s.active) { notice("End the ride before changing destination"); return; }
        if (mode == Mode.PARK_FORM || mode == Mode.SAVE) return;
        dropLat = lat; dropLon = lon;
        pinSub.setText(s.fixAt > 0 ? Gabi.distance(GeoMath.distance(s.lat, s.lon, lat, lon)) + " from you" : s.inRegion(lat, lon) ? "Inside " + s.regionName : "Outside the installed offline region");
        root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        go(Mode.PIN);
    }

    // ================================================================ route
    private LinearLayout buildRoute() {
        LinearLayout c = k.column(0);
        LinearLayout head = k.row(10);
        routeName = k.single(k.text("", 15, 650, INK_SOFT)); head.addView(routeName, new LinearLayout.LayoutParams(0, -2, 1));
        routeStar = k.circle(IconDrawable.STAR, 36, CHIP, ACCENT, this::toggleRouteStar); head.addView(routeStar);
        head.addView(k.circle(IconDrawable.CLOSE, 36, CHIP, MUTED, this::closeRoute));
        c.addView(head);
        LinearLayout etaRow = k.row(10); etaRow.setGravity(Gravity.BOTTOM); etaRow.setPadding(0, k.dp(6), 0, 0);
        routeEta = k.num("—", 40, 800, INK); routeEta.setLetterSpacing(-0.03f); etaRow.addView(routeEta);
        routeMeta = k.num("", 14, 600, MUTED); routeMeta.setPadding(0, 0, 0, k.dp(6)); etaRow.addView(routeMeta);
        c.addView(etaRow);
        shimmer = new View(this); k.fill(shimmer, 2, ACCENT); shimmer.setPivotX(0);
        FrameLayout track = new FrameLayout(this); k.fill(track, 2, CHIP); track.addView(shimmer, new FrameLayout.LayoutParams(k.dp(90), -1));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, k.dp(3)); tp.topMargin = k.dp(10); c.addView(track, tp);
        ValueAnimator sh = ValueAnimator.ofFloat(-0.3f, 1.1f); sh.setDuration(1100); sh.setRepeatCount(ValueAnimator.INFINITE); sh.setInterpolator(Gabi.EASE_IN_OUT);
        sh.addUpdateListener(a -> shimmer.setTranslationX((float)a.getAnimatedValue() * track.getWidth())); sh.start();
        track.setTag(sh);
        routeStatus = k.text("", 13, 550, INK_SOFT); routeStatus.setPadding(0, k.dp(8), 0, 0); c.addView(routeStatus);
        routeTags = k.row(6); routeTags.setPadding(0, k.dp(12), 0, k.dp(12));
        for (String t : new String[]{"Scooter route", "No highways", "No tolls", "Offline"}) routeTags.addView(k.chip(t, CHIP, INK_SOFT));
        HorizontalScrollView hs = new HorizontalScrollView(this); hs.setHorizontalScrollBarEnabled(false); hs.addView(routeTags); c.addView(hs);
        parkRow = k.row(10); parkRow.setPadding(k.dp(12), k.dp(10), k.dp(12), k.dp(10)); k.fill(parkRow, 16, ACCENT_SOFT);
        parkRow.addView(k.icon(IconDrawable.PARK, 18, ACCENT)); parkText = k.text("", 13, 600, INK_SOFT); parkRow.addView(parkText, new LinearLayout.LayoutParams(0, -2, 1));
        c.addView(parkRow);
        simNote = k.text("Simulates the route on this phone and the connected TFT. Do not follow a simulated position while riding.", 12.5f, 500, MUTED);
        simNote.setPadding(0, k.dp(10), 0, 0); c.addView(simNote);
        LinearLayout btns = k.row(8); btns.setPadding(0, k.dp(12), 0, 0);
        startBtn = k.primary("Start ride", this::start); btns.addView(startBtn, new LinearLayout.LayoutParams(0, -2, 1));
        c.addView(btns);
        return c;
    }
    private void paintRoute() {
        boolean mine = destName != null && destName.equals(s.destination) && !s.shape.isEmpty();
        routeName.setText((demoTrip ? "Simulation · " : "") + (destName == null ? "" : destName));
        boolean busy = s.routing || pendingPlan;
        ((View)shimmer.getParent()).setVisibility(busy ? View.VISIBLE : View.GONE);
        if (s.routing) { routeEta.setText("—"); routeMeta.setText(""); routeStatus.setText("Calculating on this phone…"); }
        else if (pendingPlan) { routeEta.setText("—"); routeMeta.setText(""); routeStatus.setText("Waiting for GPS. Simulation works without it."); }
        else if (mine) {
            routeEta.setText(Gabi.minutes(s.seconds)); routeMeta.setText(Gabi.distance(s.remaining) + " · arrive " + Gabi.clock(s.seconds));
            routeStatus.setText(s.simulation ? "Simulated route ready. No live GPS is used." : "Route calculated offline on this phone.");
        } else { routeEta.setText("—"); routeMeta.setText(""); routeStatus.setText(s.status); }
        int lots = s.nearbyParking.size(), priced = 0;
        for (JSONObject l : s.nearbyParking) if (!MapStyle.priceOf(l).isEmpty()) priced++;
        parkRow.setVisibility(mine && lots > 0 ? View.VISIBLE : View.GONE);
        parkText.setText(lots + " parking " + (lots == 1 ? "spot" : "spots") + " near arrival" + (priced > 0 ? " · " + priced + " with a saved rate" : ""));
        simNote.setVisibility(demoTrip ? View.VISIBLE : View.GONE);
        startBtn.setText(demoTrip ? "Start simulation" : "Start ride");
        boolean can = mine && !s.routing; startBtn.setAlpha(can ? 1f : 0.45f);
        routeStar.setVisibility(demoTrip ? View.GONE : View.VISIBLE);
        boolean saved = destName != null && bookmarks.find(destLat, destLon) != null;
        ((ImageView)routeStar.getChildAt(0)).setImageDrawable(new IconDrawable(saved ? IconDrawable.STAR_FILLED : IconDrawable.STAR, theme.palette.accent));
    }
    private void toggleRouteStar() {
        if (destName == null) return;
        Bookmarks.Place b = bookmarks.find(destLat, destLon);
        if (b != null) { bookmarks.remove(destLat, destLon); notice("Removed " + b.name, () -> { bookmarks.save(b.name, b.lat, b.lon); paintRoute(); }); }
        else if ("Map pin".equals(destName)) { openSave("", destLat, destLon); return; }
        else { bookmarks.save(destName, destLat, destLon); notice("Saved " + destName); }
        paintRoute();
    }

    private void choose(double lat, double lon, String name) {
        if (s.active) { notice("End the ride before changing destination"); return; }
        if (s.routing) { notice("Wait for the route calculation"); return; }
        hideKeyboard();
        destLat = lat; destLon = lon; destName = name; demoTrip = false; remember(lat, lon, name);
        go(Mode.ROUTE);
        if (s.fixAt == 0) { pendingPlan = true; locate(); }
        else { pendingPlan = false; s.plan(lat, lon, name, false); }
        if (map != null && s.fixAt == 0) map.animateCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(lat, lon), 15), 900);
        paintRoute();
    }
    private void closeRoute() { pendingPlan = false; destName = null; demoTrip = false; go(Mode.EXPLORE); }

    private void start() {
        boolean demo = demoTrip;
        if (!s.ready || s.shape.isEmpty() || s.routing) { Gabi.shake(startBtn); notice("Choose a destination and calculate a route first"); return; }
        if (!demo && (s.simulation || s.fixAt == 0 || SystemClock.elapsedRealtime() - s.fixAt > 12000 || s.accuracy > 40)) {
            Gabi.shake(startBtn); notice("Get a fresh precise GPS location before a live ride"); locate(); return;
        }
        startForegroundService(new Intent(this, NavService.class).putExtra("demo", demo));
    }

    /** Simulated ride over the region's sample route, started from Settings › Testing. */
    private void demo() {
        if (s.active) { notice("End the current ride first"); return; }
        if (!s.ready) { pendingDemo = true; notice("Loading the offline map first…"); return; }
        if (s.routing) { notice("Wait for the route calculation"); return; }
        s.lat = s.demoFrom[0]; s.lon = s.demoFrom[1]; s.fixAt = 0;
        destLat = s.demoTo[0]; destLon = s.demoTo[1]; destName = s.demoName; demoTrip = true; pendingPlan = false;
        go(Mode.ROUTE);
        s.plan(destLat, destLon, destName, true);
        paintRoute();
    }

    // ================================================================ parking
    private LinearLayout buildParkForm() {
        LinearLayout c = k.column(10);
        LinearLayout head = k.row(10);
        head.addView(k.text("Save checked parking", 19, 750, INK), new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(k.circle(IconDrawable.CLOSE, 38, CHIP, MUTED, () -> go(Mode.EXPLORE)));
        c.addView(head);
        c.addView(k.text("Local record on this phone. Enter a motorcycle rate you checked. Leave the price blank if you don’t know it.", 13, 500, MUTED));
        parkName = input("Parking name", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS); c.addView(parkName, new LinearLayout.LayoutParams(-1, k.dp(52)));
        parkPrice = input("₱ amount · optional", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL); c.addView(parkPrice, new LinearLayout.LayoutParams(-1, k.dp(52)));
        String[] basis = {"hour", "entry", "2h"};
        c.addView(k.segmented(new String[]{"Per hour", "Per entry", "Per 2 hours"}, 0, i -> parkBasis = basis[i]));
        parkError = k.text("", 12.5f, 600, DANGER); parkError.setVisibility(View.GONE); c.addView(parkError);
        TextView save = k.primary("Save parking", this::saveParking); c.addView(save);
        return c;
    }
    private EditText input(String hint, int type) {
        EditText e = new EditText(this); e.setHint(hint); e.setInputType(type); e.setSingleLine(); e.setTextSize(15.5f); e.setTypeface(Gabi.font(this, 550));
        e.setPadding(k.dp(16), 0, k.dp(16), 0);
        theme.add(p -> { e.setBackground(Gabi.round(p.chip, k.dp(16))); e.setTextColor(p.ink); e.setHintTextColor(p.muted); });
        e.setOnFocusChangeListener((v, f) -> { Gabi.Palette p = theme.palette; v.setBackground(f ? Gabi.round(p.chip, k.dp(16), p.accent, k.dp(1.5f)) : Gabi.round(p.chip, k.dp(16))); });
        return e;
    }
    private void saveParking() {
        String name = parkName.getText().toString().trim();
        try {
            s.saveParking(parkLat, parkLon, name.isEmpty() ? "Saved parking" : name, parkPrice.getText().toString(), parkBasis);
            hideKeyboard(); parkName.setText(""); parkPrice.setText(""); parkError.setVisibility(View.GONE);
            go(Mode.EXPLORE); notice("Saved on this phone. Rates are owner-entered, not live availability.");
        } catch (Exception e) { parkError.setText("Enter a valid non-negative amount or leave it blank"); parkError.setVisibility(View.VISIBLE); Gabi.shake(parkPrice); }
    }

    // ================================================================ ride
    private void buildRide() {
        maneuver = k.row(14); maneuver.setPadding(k.dp(14), k.dp(14), k.dp(18), k.dp(14)); k.glass(maneuver, 26);
        FrameLayout arrow = new FrameLayout(this); k.fill(arrow, 18, ACCENT);
        maneuverIcon = k.icon(IconDrawable.STRAIGHT, 36, ON_ACCENT); ((IconDrawable)maneuverIcon.getDrawable()).stroke(2.6f);
        arrow.addView(maneuverIcon, new FrameLayout.LayoutParams(k.dp(36), k.dp(36), Gravity.CENTER));
        maneuver.addView(arrow, new LinearLayout.LayoutParams(k.dp(62), k.dp(62)));
        LinearLayout mt = k.column(3);
        maneuverDist = k.num("", 32, 800, INK); maneuverDist.setLetterSpacing(-0.02f); mt.addView(maneuverDist);
        maneuverStreet = k.single(k.text("", 15, 600, INK_SOFT)); mt.addView(maneuverStreet);
        maneuver.addView(mt, new LinearLayout.LayoutParams(0, -2, 1));
        rideTop = k.column(6); rideTop.setClipChildren(false); rideTop.setClipToPadding(false);
        rideTop.addView(maneuver, new LinearLayout.LayoutParams(-1, -2));
        FrameLayout.LayoutParams ml = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP); ml.leftMargin = ml.rightMargin = k.dp(10);
        root.addView(rideTop, ml); rideTop.setVisibility(View.GONE);

        thenChip = k.row(6); thenChip.setPadding(k.dp(12), k.dp(7), k.dp(14), k.dp(7)); k.glass(thenChip, 14);
        thenChip.addView(k.text("Then", 12.5f, 650, MUTED)); thenIcon = k.icon(IconDrawable.RIGHT, 16, INK); thenChip.addView(thenIcon);
        thenText = k.single(k.text("", 12.5f, 650, INK_SOFT)); thenText.setMaxWidth(k.dp(200)); thenChip.addView(thenText);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2); tl.leftMargin = k.dp(12);
        rideTop.addView(thenChip, tl); thenChip.setVisibility(View.GONE);

        etaBar = k.row(12); etaBar.setPadding(k.dp(20), k.dp(10), k.dp(10), k.dp(10)); k.glass(etaBar, 30);
        LinearLayout et = k.column(3);
        LinearLayout etTop = k.row(8); etaMin = k.num("", 25, 800, INK); etTop.addView(etaMin);
        simBadge = k.chip("SIMULATION", ACCENT_SOFT, ACCENT); simBadge.setTextSize(10.5f); simBadge.setLetterSpacing(0.08f); etTop.addView(simBadge);
        tftBadge = k.chip("TFT live", OK_SOFT, OK); tftBadge.setTextSize(10.5f); etTop.addView(tftBadge);
        et.addView(etTop);
        etaMeta = k.num("", 13, 600, MUTED); et.addView(etaMeta);
        etaBar.addView(et, new LinearLayout.LayoutParams(0, -2, 1));
        endBtn = k.text("End", 15, 800, WHITE); endBtn.setGravity(Gravity.CENTER); endBtn.setPadding(k.dp(20), 0, k.dp(20), 0); endBtn.setMinHeight(k.dp(52));
        theme.add(p -> endBtn.setBackground(Gabi.round(p.danger, k.dp(26))));
        Gabi.press(endBtn, this::onEnd);
        etaBar.addView(endBtn);
        FrameLayout.LayoutParams el = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM); el.leftMargin = el.rightMargin = k.dp(10);
        root.addView(etaBar, el); etaBar.setVisibility(View.GONE);
    }
    private final Runnable disarmEnd = () -> { endArmed = false; endBtn.setText("End"); };
    private void onEnd() {
        if (!endArmed) { endArmed = true; endBtn.setText("Tap to end"); ui.removeCallbacks(disarmEnd); ui.postDelayed(disarmEnd, 3000); return; }
        ui.removeCallbacks(disarmEnd); disarmEnd.run();
        // The bike display stays connected between rides; the notification's Stop still disconnects everything.
        s.stop(); stopService(new Intent(this, NavService.class)); refresh();
    }
    private void paintRide() {
        JSONArray ms = s.maneuvers; int idx = s.maneuver;
        JSONObject m = ms.optJSONObject(idx);
        boolean arriving = "Destination nearby".equals(s.instruction);
        boolean stale = !s.simulation && (s.fixAt == 0 || SystemClock.elapsedRealtime() - s.fixAt > 12000 || s.accuracy > 50);
        int type = arriving ? 4 : m == null ? 8 : m.optInt("type", 8);
        String street = arriving ? (s.destination.isEmpty() ? "Destination" : s.destination) : streetOf(m, s.instruction);
        if (idx != shownManeuver) {
            shownManeuver = idx; maneuver.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            View content = maneuverIcon; content.setScaleX(0.6f); content.setScaleY(0.6f); content.setRotation(-12);
            content.animate().scaleX(1).scaleY(1).rotation(0).setDuration(520).setInterpolator(new android.view.animation.OvershootInterpolator(2.4f)).start();
            maneuverStreet.setAlpha(0); maneuverStreet.setTranslationY(k.dp(8)); maneuverStreet.animate().alpha(1).translationY(0).setDuration(380).setInterpolator(Gabi.EASE_OUT).start();
        }
        maneuverIcon.setImageDrawable(iconFor(Maneuvers.icon(type), theme.palette.onAccent, 2.6f));
        maneuverDist.setText(stale ? "GPS weak" : arriving ? "Arriving" : Gabi.distance(s.distanceToTurn));
        maneuverDist.setTextColor(stale ? theme.palette.danger : theme.palette.ink);
        maneuverStreet.setText(street);
        JSONObject next = arriving ? null : ms.optJSONObject(idx + 1);
        if (next != null) { thenIcon.setImageDrawable(iconFor(Maneuvers.icon(next.optInt("type", 8)), theme.palette.ink, 2.2f)); thenText.setText(next.optInt("type") >= 4 && next.optInt("type") <= 6 ? (s.destination.isEmpty() ? "Arrive" : s.destination) : streetOf(next, next.optString("instruction", ""))); }
        Gabi.show(thenChip, next != null, -k.dp(6));
        etaMin.setText(Gabi.minutes(s.seconds));
        etaMeta.setText(Gabi.distance(s.remaining) + " · arrive " + Gabi.clock(s.seconds));
        simBadge.setVisibility(s.simulation ? View.VISIBLE : View.GONE);
        tftBadge.setVisibility(StreamService.navigationActive ? View.VISIBLE : View.GONE);
    }
    private IconDrawable iconFor(int type, int color, float stroke) { return new IconDrawable(type, color).stroke(stroke); }
    private static String streetOf(JSONObject m, String fallback) {
        if (m == null) return fallback;
        JSONArray n = m.optJSONArray("street_names"); if (n != null && n.length() > 0) return n.optString(0);
        n = m.optJSONArray("begin_street_names"); if (n != null && n.length() > 0) return n.optString(0);
        return m.optString("instruction", fallback);
    }

    // ================================================================ modes
    private void go(Mode next) {
        Mode prev = mode; mode = next;
        boolean searching = next == Mode.SEARCH, riding = next == Mode.RIDE;
        if (searching) {
            searchPanel.setVisibility(View.VISIBLE); searchPanel.setAlpha(0); searchPanel.setTranslationY(k.dp(16)); searchPanel.bringToFront();
            searchPanel.animate().alpha(1).translationY(0).setDuration(300).setInterpolator(Gabi.EASE_OUT).start();
            field.requestFocus(); ui.postDelayed(() -> ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(field, 0), 120);
            runSearch.run();
        } else if (prev == Mode.SEARCH) { hideKeyboard(); Gabi.show(searchPanel, false, k.dp(16)); }
        Gabi.show(top, next == Mode.EXPLORE || next == Mode.PIN, -k.dp(14));
        if (prev == Mode.SAVE && next != Mode.SAVE) hideKeyboard();
        Gabi.show(rideTop, riding, -k.dp(24)); if (!riding) thenChip.setVisibility(View.GONE);
        Gabi.show(etaBar, riding, k.dp(24));
        switch (next) {
            case EXPLORE: paintRecents(); sheet.swap(explore); break;
            case PIN: sheet.swap(pinSheet); break;
            case ROUTE: paintRoute(); sheet.swap(routeSheet); drawnShape = null; break;
            case SAVE: sheet.swap(saveSheet); break;
            case PARK_FORM: sheet.swap(parkForm); parkName.requestFocus(); break;
            case SEARCH: sheet.hide(); break;
            case RIDE: sheet.hide(); follow = true; shownManeuver = -1; lastFix = -1; break;
        }
        if (riding) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (prev == Mode.RIDE && !riding && map != null) map.animateCamera(CameraUpdateFactory.newCameraPosition(new CameraPosition.Builder(map.getCameraPosition()).tilt(0).bearing(0).zoom(14.5).padding(0, 0, 0, 0).build()), 900);
        layoutInsets(); paintLocateFab(); paintMap();
    }

    // ================================================================ refresh
    private void refresh() {
        if (isDestroyed() || root == null) return;
        long now = SystemClock.elapsedRealtime();
        if (now - themeCheckedAt > 60000) { themeCheckedAt = now; applyTheme(Gabi.resolve(this, s.lat, s.lon, s.fixAt > 0)); }
        installLayers();
        if (!s.ready) dotChip(regionChip, "Preparing offline maps…", MUTED);
        else dotChip(regionChip, s.regionName + " · offline", OK);
        String model = savedModel();
        if (StreamService.navigationActive) dotChip(bikeChip, "Bike display live", OK);
        else if (link != null && link.searching()) dotChip(bikeChip, "Looking for " + (model == null ? "your bike" : model) + "…", ACCENT);
        else dotChip(bikeChip, model == null ? "Set up your bike" : model + " · not connected", MUTED);
        if (pendingDemo && s.ready) { pendingDemo = false; demo(); }
        exploreStatus.setText(!s.ready ? s.status : s.fixAt > 0 ? "GPS ready · maps and routing work without data" : "Maps and routing work without data in " + s.regionName);
        if (pendingPlan && s.ready && !s.routing && s.fixAt > 0) { pendingPlan = false; s.plan(destLat, destLon, destName, false); }
        if (s.active && mode != Mode.RIDE) go(Mode.RIDE);
        else if (!s.active && mode == Mode.RIDE) { destName = null; demoTrip = false; go(Mode.EXPLORE); }
        if (mode == Mode.ROUTE) paintRoute();
        if (mode == Mode.RIDE) paintRide();
        paintMap();
    }
    private String savedModel() {
        String raw = getSharedPreferences("saved-bike", 0).getString("qr", ""); if (raw.isEmpty()) return null;
        try { String m = QrProfile.parse(raw).model; return m == null || m.isEmpty() ? "Saved bike" : m; } catch (Exception e) { return null; }
    }
    private void applyTheme(Gabi.Palette p) {
        if (!theme.set(p)) return;
        if (style != null) { MapStyle.paint(style, p); drawnParking = null; }
        paintRecents();
    }

    private void paintMap() {
        if (!layersReady || map == null) return;
        boolean routeMode = mode == Mode.ROUTE || mode == Mode.RIDE;
        List<double[]> shape = s.shape;
        boolean showRoute = routeMode && !shape.isEmpty() && (mode == Mode.RIDE || (destName != null && destName.equals(s.destination)));
        GeoJsonSource route = src(MapStyle.ROUTE), done = src(MapStyle.DONE);
        if (!showRoute) {
            if (!routeCleared) { if (routeAnim != null) routeAnim.cancel(); route.setGeoJson(MapStyle.empty()); done.setGeoJson(MapStyle.empty()); routeCleared = true; }
            drawnShape = null;
        } else if (mode == Mode.ROUTE && shape != drawnShape) { routeCleared = false; drawnShape = shape; done.setGeoJson(MapStyle.empty()); drawRouteIn(shape); ui.postDelayed(() -> { if (drawnShape == shape) fitRoute(shape); }, 120); }
        else if (mode == Mode.RIDE) {
            drawnShape = shape; routeCleared = false; int prog = Math.min(s.progress, shape.size() - 1); double[] here = {s.lat, s.lon};
            route.setGeoJson(Feature.fromGeometry(MapStyle.line(shape, prog + 1, shape.size(), here)));
            done.setGeoJson(Feature.fromGeometry(withHead(shape, prog, here)));
        }
        // destination / pin
        GeoJsonSource dest = src(MapStyle.DEST), drop = src(MapStyle.DROP);
        if (routeMode && destName != null || mode == Mode.RIDE) dest.setGeoJson(mode == Mode.RIDE ? MapStyle.point(s.destLat, s.destLon) : MapStyle.point(destLat, destLon)); else dest.setGeoJson(MapStyle.empty());
        if (mode == Mode.PIN || mode == Mode.SAVE) drop.setGeoJson(MapStyle.point(dropLat, dropLon)); else drop.setGeoJson(MapStyle.empty());
        // parking near the destination, before the ride and in the last kilometre
        List<JSONObject> lots = (showRoute && (mode == Mode.ROUTE || s.remaining < 1000)) ? s.nearbyParking : Collections.emptyList();
        if (lots != drawnParking || drawnParkingPalette != theme.palette) {
            drawnParking = lots; drawnParkingPalette = theme.palette;
            src(MapStyle.PARKING).setGeoJson(lots.isEmpty() ? MapStyle.empty() : MapStyle.parking(this, style, theme.palette, lots, 8));
            Layer l = style.getLayer("parking");
            if (l != null && !lots.isEmpty()) { ValueAnimator a = ValueAnimator.ofFloat(0, 1); a.setDuration(520); a.setInterpolator(new android.view.animation.OvershootInterpolator(1.6f));
                a.addUpdateListener(x -> { float v = (float)x.getAnimatedValue(); l.setProperties(iconOpacity(Math.min(1f, v * 1.4f)), iconTranslate(new Float[]{0f, (1 - v) * -k.dp(14)})); }); a.start(); }
        }
        paintPuck();
    }
    private org.maplibre.geojson.LineString withHead(List<double[]> shape, int prog, double[] here) {
        ArrayList<org.maplibre.geojson.Point> pts = new ArrayList<>();
        for (int i = 0; i <= prog && i < shape.size(); i++) pts.add(org.maplibre.geojson.Point.fromLngLat(shape.get(i)[1], shape.get(i)[0]));
        pts.add(org.maplibre.geojson.Point.fromLngLat(here[1], here[0]));
        return org.maplibre.geojson.LineString.fromLngLats(pts);
    }

    /** The route draws itself from start to destination, evenly by distance. */
    private void drawRouteIn(List<double[]> shape) {
        if (routeAnim != null) routeAnim.cancel();
        double[] cum = new double[shape.size()];
        for (int i = 1; i < shape.size(); i++) cum[i] = cum[i - 1] + GeoMath.distance(shape.get(i - 1)[0], shape.get(i - 1)[1], shape.get(i)[0], shape.get(i)[1]);
        double total = cum[cum.length - 1];
        GeoJsonSource route = src(MapStyle.ROUTE);
        routeAnim = ValueAnimator.ofFloat(0, 1); routeAnim.setDuration(1100); routeAnim.setStartDelay(250); routeAnim.setInterpolator(Gabi.EASE_IN_OUT);
        routeAnim.addUpdateListener(a -> {
            if (drawnShape != shape) return;
            double want = total * (float)a.getAnimatedValue(); int n = 1; while (n < cum.length && cum[n] <= want) n++;
            route.setGeoJson(Feature.fromGeometry(MapStyle.line(shape, 0, Math.max(2, n), null)));
        });
        routeAnim.start();
    }
    private void fitRoute(List<double[]> shape) {
        LatLngBounds.Builder b = new LatLngBounds.Builder();
        for (double[] p : shape) b.include(new LatLng(p[0], p[1]));
        b.include(new LatLng(destLat, destLon));
        int sheetH = sheet.getHeight() > 0 ? sheet.getHeight() : k.dp(330);
        try { map.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), k.dp(56), insetTop + k.dp(56), k.dp(56), sheetH + insetBottom + k.dp(36)), 1100); } catch (Exception ignored) {}
    }

    // ================================================================ puck & camera
    private void paintPuck() {
        GeoJsonSource puck = src(MapStyle.PUCK); if (puck == null) return;
        boolean visible = s.fixAt > 0 || s.active;
        if (!visible) { puck.setGeoJson(MapStyle.empty()); stopPulse(); haveShown = false; return; }
        if (s.fixAt == lastFix && haveShown) return;
        lastFix = s.fixAt;
        double toLat = s.lat, toLon = s.lon; float toBearing = s.bearing;
        if (!haveShown) { haveShown = true; shownLat = toLat; shownLon = toLon; shownBearing = toBearing; }
        double fromLat = shownLat, fromLon = shownLon; float fromBearing = shownBearing;
        float turn = ((toBearing - fromBearing + 540) % 360) - 180;
        if (puckAnim != null) puckAnim.cancel();
        puckAnim = ValueAnimator.ofFloat(0, 1); puckAnim.setDuration(mode == Mode.RIDE ? 950 : 600); puckAnim.setInterpolator(new LinearInterpolator());
        puckAnim.addUpdateListener(a -> {
            float f = (float)a.getAnimatedValue();
            shownLat = fromLat + (toLat - fromLat) * f; shownLon = fromLon + (toLon - fromLon) * f; shownBearing = (fromBearing + turn * f + 360) % 360;
            Feature feature = MapStyle.point(shownLat, shownLon);
            boolean arrow = s.active || s.bearing != 0;
            feature.addStringProperty("icon", arrow ? "puck-arrow" : "puck-dot"); feature.addNumberProperty("bearing", shownBearing);
            puck.setGeoJson(feature);
            if (mode == Mode.RIDE && follow) follow();
        });
        puckAnim.start();
        if (mode == Mode.RIDE) stopPulse(); else startPulse();
    }
    private void follow() {
        int h = mapView.getHeight();
        CameraPosition pos = new CameraPosition.Builder().target(new LatLng(shownLat, shownLon)).bearing(shownBearing).tilt(52)
            .zoom(s.remaining < 600 ? 17.4 : 16.8).padding(0, h * 0.42, 0, 0).build();
        map.moveCamera(CameraUpdateFactory.newCameraPosition(pos));
    }
    private void startPulse() {
        if (pulse != null && pulse.isRunning()) return;
        Layer l = style.getLayer("puck-pulse"); if (l == null) return;
        pulse = ValueAnimator.ofFloat(0, 1); pulse.setDuration(2200); pulse.setRepeatCount(ValueAnimator.INFINITE); pulse.setInterpolator(Gabi.EASE_OUT);
        pulse.addUpdateListener(a -> { float v = (float)a.getAnimatedValue(); l.setProperties(circleRadius(10 + 26 * v), circleOpacity(0.45f * (1 - v))); });
        pulse.start();
    }
    private void stopPulse() { if (pulse != null) { pulse.cancel(); pulse = null; Layer l = style == null ? null : style.getLayer("puck-pulse"); if (l != null) l.setProperties(circleOpacity(0f)); } }

    // ================================================================ GPS & screens
    private void locate() {
        if (s.active && s.simulation) { notice("End simulation before starting GPS"); return; }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) { requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 71); return; }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 72);
        if (!s.active) startForegroundService(new Intent(this, NavService.class).setAction("preview"));
    }
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        super.onRequestPermissionsResult(code, p, r);
        if (code == 71 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) { locate(); autoConnect(); }
        else if (code == 71) { pendingPlan = false; notice("Precise location is needed for a live ride."); refresh(); }
        if (code == DashboardLink.PERMISSIONS) link.onPermissionsResult();
    }
    private void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }
    private String savedQr() { return getSharedPreferences("saved-bike", 0).getString("qr", ""); }

    // ================================================================ shared places
    private void handleIntent(Intent i) {
        if (i == null) return;
        if (i.getBooleanExtra("demo", false)) { i.removeExtra("demo"); demo(); return; }
        String text = null;
        if (Intent.ACTION_SEND.equals(i.getAction())) {
            CharSequence t = i.getCharSequenceExtra(Intent.EXTRA_TEXT); text = t == null ? null : t.toString();
            String subject = i.getStringExtra(Intent.EXTRA_SUBJECT);
            if (text != null && subject != null && !text.contains(subject)) text = subject + "\n" + text;
        } else if (Intent.ACTION_VIEW.equals(i.getAction()) && i.getData() != null) text = i.getData().toString();
        if (text == null) return;
        setIntent(new Intent(this, HomeActivity.class));
        ShareLinks.Place place = ShareLinks.parse(text);
        if (place == null) { notice("That share has no place in it. In Google Maps, open the place and tap Share."); return; }
        if (place.located()) { openSave(place.name.equals("Shared place") ? "" : place.name, place.lat, place.lon); return; }
        notice("Reading the Google Maps link…");
        worker.execute(() -> {
            ShareLinks.Place found;
            try { found = ShareLinks.resolve(place); } catch (Exception e) { found = place; }
            ShareLinks.Place r = found;
            ui.post(() -> {
                if (r.located()) openSave(r.name.equals("Shared place") ? "" : r.name, r.lat, r.lon);
                else notice("Couldn't read that link. Share it again while online, or long-press the map to pin it.");
            });
        });
    }

    // ================================================================ bike display
    private final Runnable retryConnect = this::autoConnect;
    /** Keep trying to reach the saved dashboard while the app is open and the display is not live. */
    private void autoConnect() {
        if (!started || link == null || !link.profileReady() || StreamService.navigationActive || link.searching()) return;
        if (!getSharedPreferences("ui", 0).getBoolean("autoconnect", true)) return;
        if (!link.hasPermissions()) { if (askedLinkPermissions) return; askedLinkPermissions = true; }
        link.sendNavigation(); refresh();
    }
    public void onStatus(String status) { refresh(); }
    public void onLog() {}
    public void onChanged() {
        refresh();
        if (started && link != null && !link.searching() && !StreamService.navigationActive) { ui.removeCallbacks(retryConnect); ui.postDelayed(retryConnect, 15000); }
    }
    public void onPermissionsMissing() { notice("Allow Nearby devices and location so Daanvia can find your bike"); }
    public void onNavigationStarted() { link.settle(); notice("Connected. Daanvia is on the bike display."); refresh(); }
}
