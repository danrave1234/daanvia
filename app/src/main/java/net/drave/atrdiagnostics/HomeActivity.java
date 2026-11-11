package net.drave.atrdiagnostics;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.text.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import org.json.*;
import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.*;
import org.maplibre.android.style.layers.Layer;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.geojson.Feature;
import java.util.*;
import java.util.concurrent.*;

import static net.drave.atrdiagnostics.Gabi.Role.*;

/**
 * The whole riding app on one map: explore, search, pin, saved places, route preview and the live ride.
 * While it is open it keeps trying to put Daanvia on the saved bike's dashboard, like Carbit does.
 * The search (SearchPanel), the area download frame (AreaPicker), the ride screen (RideScreen) and what's drawn on the
 * map (MapPainter) are their own classes; this one holds the screen's lifecycle, its modes and what refreshes them.
 */
public final class HomeActivity extends Activity implements DashboardLink.Host {
    public void askPermissions(String[] permissions, int requestCode) { requestPermissions(permissions, requestCode); }
    private enum Mode { SELECT, EXPLORE, SEARCH, RESULTS, PIN, SAVE, LOT, ROUTE, RIDE, PARK_FORM }

    private NavSession s; private Gabi.Themer theme; private Gabi.Kit k;
    // Lists rebuilt from scratch (saved and recent places, parking, a lot's facts) colour their rows through themes of
    // their own, cleared at each rebuild, so the screen's theme list doesn't grow with every rebuild.
    private Gabi.Themer savedTheme, recentsTheme, parkTheme, lotTheme; private Gabi.Kit sk, ck, pk, lk;
    private final Handler ui = new Handler(Looper.getMainLooper());
    // One thread for offline work (search, street squares, files), one for the network, so a slow connection never holds up the
    // offline map. Work handed over after the screen closed is dropped instead of crashing.
    private final ExecutorService worker = single(), net = single();
    private static ExecutorService single() { return new java.util.concurrent.ThreadPoolExecutor(1, 1, 0, java.util.concurrent.TimeUnit.MILLISECONDS, new java.util.concurrent.LinkedBlockingQueue<>(), new java.util.concurrent.ThreadPoolExecutor.DiscardPolicy()); }
    private Mode mode = Mode.EXPLORE;

    private MapView mapView; private MapLibreMap map; private org.maplibre.android.maps.Style style; private boolean layersReady;
    private FrameLayout root; private LinearLayout top, chips, fabs; private TextView regionChip, bikeChip, attribution, notice;
    private FrameLayout fabLocate, fabTft, fabVoice, fabBike, fabCompass, fabMirror; private boolean compassShown; private ImageView fabIcon; private SheetView sheet;
    private final HomeLayout lay = new HomeLayout();
    private SearchPanel search; private AreaPicker picker; private RideScreen ride; private MapPainter painter;

    // parking lot details
    private LinearLayout lotSheet, lotFacts; private TextView lotTitle, lotSub, lotLive; private View lotLiveDot; private Lots.Lot lot; private String lotParent; private int lotToken;
    // explore
    private LinearLayout explore, savedBox, recentsBox; private TextView exploreStatus; private Bookmarks bookmarks;
    // save place
    private LinearLayout saveSheet; private EditText saveName; private TextView saveSub; private double saveLat, saveLon;
    private boolean overview, suggestedOutside;
    private final AreaStore.Listener areas = this::onArea;
    private final AppUpdate.Listener updates = this::onAppUpdate; private boolean updateOffered;
    // bike
    private DashboardLink link; private boolean started, askedLinkPermissions; private String linkedQr; private boolean pendingDemo;
    // pin
    private LinearLayout pinSheet; private TextView pinSub; private double dropLat, dropLon;
    // route
    private LinearLayout routeSheet, parkRow; private TextView routeName, routeEta, routeMeta, routeStatus, parkText, startBtn, simNote; private FrameLayout routeStar; private View shimmer;
    private RouteChoicesView routePicker;
    // parking
    private boolean parkIsEntrance; private LinearLayout parkEntranceRow; private LinearLayout startRow;
    private LinearLayout parkForm; private EditText parkName, parkPrice; private TextView parkError; private String parkBasis = "hour"; private double parkLat, parkLon;

    private double destLat, destLon; private String destName; private boolean pendingPlan, demoTrip;
    private List<TripStop> plannedTrip=Collections.emptyList();
    private Here here; private boolean centerOnFix, userMovedMap, firstFixHandled;
    static final long MAP_CACHE_BYTES = 200L * 1024 * 1024;
    private long themeCheckedAt; private boolean centeredOnRegion, coastline; private int shownRegion = -1;

    private final BroadcastReceiver changes = new BroadcastReceiver() { public void onReceive(Context c, Intent i) { refresh(); } };

    // ================================================================ lifecycle
    public void onCreate(Bundle state) {
        super.onCreate(state);
        // First launch with the old Daanvia still installed: offer to move its maps and settings before anything loads.
        if (MoveActivity.shouldOffer(this)) { moving = true; startActivity(new Intent(this, MoveActivity.class)); finish(); return; }
        if (getIntent().getBooleanExtra("moved", false)) offerUninstallOld = true;
        s = NavSession.get(this); MapLibre.getInstance(this); here = new Here(this, s);
        // Online map pieces stay on the phone after they are seen, so those places still show without signal.
        org.maplibre.android.offline.OfflineManager.getInstance(this).setMaximumAmbientCacheSize(MAP_CACHE_BYTES, null);
        theme = new Gabi.Themer(Gabi.resolve(this, s.lat, s.lon, s.fixAt > 0)); k = new Gabi.Kit(this, theme);
        sk = new Gabi.Kit(this, savedTheme = theme.child()); ck = new Gabi.Kit(this, recentsTheme = theme.child());
        pk = new Gabi.Kit(this, parkTheme = theme.child()); lk = new Gabi.Kit(this, lotTheme = theme.child());
        Window w = getWindow(); Gabi.edgeToEdge(w);
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        theme.add(p -> Gabi.barIcons(w, p));

        root = new FrameLayout(this); theme.add(p -> root.setBackgroundColor(p.bg));
        // Labels are checked for overlap only against labels of their own source: with streets, shops and places on
        // screen at once, the all-against-all check was what made panning stutter when zoomed in.
        mapView = new MapView(this, org.maplibre.android.maps.MapLibreMapOptions.createFromAttributes(this).crossSourceCollisions(false)); mapView.onCreate(state); root.addView(mapView, new FrameLayout.LayoutParams(-1, -1));
        buildTop(); buildFabs();
        ride = new RideScreen(this, s, k, root, mapView, ui, lay, new RideScreen.Host() {
            public boolean riding() { return mode == Mode.RIDE; }
            public boolean mapReady() { return style != null; }
            public void refresh() { HomeActivity.this.refresh(); }
            public void notice(String text) { HomeActivity.this.notice(text); }
            public void onRideFrame(long now, double lat, double lon, int routeFrom) { painter.onRideFrame(now, lat, lon, routeFrom); }
            public void puckStale() { painter.puckStale(); }
        });
        painter = new MapPainter(this, s, k, ui, worker, new MapPainter.Host() {
            public boolean previewing() { return mode == Mode.ROUTE; }
            public boolean riding() { return mode == Mode.RIDE; }
            public void startRideFrames() { ride.startFrames(); }
            public boolean rideFramesRunning() { return ride.framesRunning(); }
            public int[] routePadding() {
                int sheetH = sheet.getHeight() > 0 ? sheet.getHeight() : k.dp(330);
                return lay.landscape ? new int[]{lay.panelW + k.dp(56), lay.top + k.dp(48), k.dp(96), lay.bottom + k.dp(48)} : new int[]{k.dp(56), lay.top + k.dp(56), k.dp(56), sheetH + lay.bottom + k.dp(36)};
            }
        });
        sheet = new SheetView(this, k);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM); sp.leftMargin = sp.rightMargin = k.dp(8);
        root.addView(sheet, sp);
        sheet.setListener(h -> { lay.sheet = h; if (picker != null) picker.invalidate(); if (lay.landscape) return; float lift = -Math.max(h, mode == Mode.RIDE ? ride.barHeight() + k.dp(8) : 0); fabs.setTranslationY(lift); attribution.setTranslationY(lift); });
        bookmarks = new Bookmarks(this);
        search = new SearchPanel(this, s, k, root, ui, worker, net, bookmarks, new SearchPanel.Host() {
            public void choose(double lat, double lon, String name) { HomeActivity.this.choose(lat, lon, name); }
            public void openLot(Lots.Lot l, String parent) { HomeActivity.this.openLot(l, parent); }
            public void close() { go(Mode.EXPLORE); }
            public void notice(String text) { HomeActivity.this.notice(text); }
            public void hideKeyboard() { HomeActivity.this.hideKeyboard(); }
            public String lotName(Lots.Lot l, String parent) { return HomeActivity.this.lotName(l, parent); }
            public JSONArray recents() { return HomeActivity.this.recents(); }
            public double[] searchFrom() {
                return new double[]{s.fixAt > 0 ? s.lat : map != null ? map.getCameraPosition().target.getLatitude() : s.lat, s.fixAt > 0 ? s.lon : map != null ? map.getCameraPosition().target.getLongitude() : s.lon};
            }
            public void onIndexed() { paintRecents(); if (mode == Mode.SEARCH) search.run.run(); }
            public void showOnMap() { showResults(); }
        });
        buildNotice();
        picker = new AreaPicker(this, s, k, root, mapView, ui, net, lay, () -> search.towns, new AreaPicker.Host() {
            public boolean selecting() { return mode == Mode.SELECT; }
            public void open() { go(Mode.SELECT); }
            public void leave() { go(Mode.EXPLORE); }
            public void notice(String text) { HomeActivity.this.notice(text); }
            public void hideKeyboard() { HomeActivity.this.hideKeyboard(); }
            public GeoJsonSource cells() { return layersReady ? src(MapStyle.CELLS) : null; }
        });
        resultsSheet = buildResults(); explore = buildExplore(); pinSheet = buildPin(); saveSheet = buildSave(); lotSheet = buildLot(); routeSheet = buildRoute(); parkForm = buildParkForm();
        setContentView(root);
        Gabi.insets(root, (l, t, r, b, ime) -> { lay.top = t; lay.bottom = b; lay.ime = ime; layoutInsets(); });
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> { if (r - l != laidW || b - t != laidH) { laidW = r - l; laidH = b - t; root.post(()->{layoutInsets();if(mode==Mode.ROUTE&&!s.shape.isEmpty())ui.postDelayed(()->painter.fitRoute(s.shape, destLat, destLon),200);}); } });
        sheet.swap(explore);
        mapView.getMapAsync(this::onMap);
        worker.execute(() -> { if (!s.active && (AreaStore.state() == null || !AreaStore.state().running)) Housekeeping.run(this, false); });
        link = DashboardLink.attach(this, this); linkedQr = savedQr();
        // Exit on a notification (RideActions) closes the map too.
        androidx.core.content.ContextCompat.registerReceiver(this, closeScreens, new IntentFilter(RideActions.CLOSE_SCREENS), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
        if (Build.VERSION.SDK_INT >= 33) back = new BackCallback(this);
        handleIntent(getIntent());
        syncBack();
    }
    protected void onNewIntent(Intent i) { super.onNewIntent(i); setIntent(i); handleIntent(i); }

    /** Landscape puts the sheet, search and turn card in a left column and keeps the map on the right. */
    private int laidW, laidH;
    private void layoutInsets() {
        int w = root.getWidth() > 0 ? root.getWidth() : getResources().getDisplayMetrics().widthPixels, h = root.getHeight() > 0 ? root.getHeight() : getResources().getDisplayMetrics().heightPixels;
        lay.landscape = w > h; lay.panelW = lay.landscape ? Math.min(k.dp(420), Math.round(w * 0.42f)) : 0;
        boolean landscape = lay.landscape; int column = landscape ? lay.panelW : -1;
        place(top, column, Gravity.TOP | Gravity.START, k.dp(14), lay.top + k.dp(8), landscape ? 0 : k.dp(14), 0);
        if (picker != null) place(picker.top, column, Gravity.TOP | Gravity.START, k.dp(14), lay.top + k.dp(8), landscape ? 0 : k.dp(14), 0);
        place(ride.top, column, Gravity.TOP | Gravity.START, k.dp(10), lay.top + k.dp(8), landscape ? 0 : k.dp(10), 0);
        place(sheet, column, Gravity.BOTTOM | Gravity.START, k.dp(8), 0, landscape ? 0 : k.dp(8), lay.bottom + k.dp(8) + (mode == Mode.PARK_FORM || mode == Mode.SAVE ? lay.ime : 0));
        place(ride.bar, column, Gravity.BOTTOM | Gravity.START, k.dp(10), 0, landscape ? 0 : k.dp(10), lay.bottom + k.dp(8));
        place(notice, column, Gravity.TOP | Gravity.START, k.dp(14), lay.top + k.dp(70), landscape ? 0 : k.dp(14), 0);
        ((FrameLayout.LayoutParams)fabs.getLayoutParams()).bottomMargin = lay.bottom + k.dp(16); fabs.requestLayout();
        FrameLayout.LayoutParams ap = (FrameLayout.LayoutParams)attribution.getLayoutParams();
        ap.bottomMargin = lay.bottom + k.dp(14); ap.leftMargin = landscape ? lay.panelW + k.dp(26) : k.dp(18); attribution.requestLayout();
        search.layout(lay);
        if (landscape) { fabs.setTranslationY(0); attribution.setTranslationY(0); }
        if (picker != null) picker.invalidate();
    }
    private void place(View v, int width, int gravity, int left, int topMargin, int right, int bottom) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams)v.getLayoutParams();
        lp.width = width; lp.gravity = gravity; lp.leftMargin = left; lp.topMargin = topMargin; lp.rightMargin = right; lp.bottomMargin = bottom; v.requestLayout();
    }

    protected void onStart() {
        super.onStart(); mapView.onStart(); started = true; StreamService.appOnScreen = true;
        androidx.core.content.ContextCompat.registerReceiver(this, changes, new IntentFilter(NavSession.EVENT), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
        String qr = savedQr(); if (!qr.equals(linkedQr)) { linkedQr = qr; if (!qr.isEmpty()) link.acceptQr(qr); }
        AreaStore.listen(areas); AppUpdate.listen(updates); AppUpdate.check(this, true);
        if (!s.active) { here.start(); checkMapUpdates(); } boolean asking = askLocationOnce(); if (!asking && !s.active) askAccuracy(false);
        // Opening Daanvia (not just coming back from one of its own screens) looks for the bike again, even if the display was stopped before.
        if (stoppedAt == 0 || SystemClock.elapsedRealtime() - stoppedAt > 60_000) link.allowAuto();
        lookStep = 0;
        // Android shows one permission request at a time; the location answer starts auto-connect (onRequestPermissionsResult).
        refresh(); if (!asking) { autoConnect(); lookAgain(); bluetoothForClock(); }
    }
    protected void onResume() { super.onResume(); mapView.onResume(); themeCheckedAt = 0; markSaved(); refresh(); }
    protected void onPause() {
        if (map != null && s.ready && (mode == Mode.EXPLORE || mode == Mode.PIN)) { CameraPosition c = map.getCameraPosition();
            getSharedPreferences("ui", 0).edit().putFloat("cam_lat", (float)c.target.getLatitude()).putFloat("cam_lon", (float)c.target.getLongitude()).putFloat("cam_zoom", (float)c.zoom).apply(); }
        mapView.onPause(); super.onPause();
    }
    protected void onStop() { ride.stop(); here.stop(); AppUpdate.unlisten(updates); AreaStore.unlisten(areas); started = false; StreamService.appOnScreen = false; stoppedAt = SystemClock.elapsedRealtime(); ui.removeCallbacks(retryConnect); if (!s.active) link.pauseSearch(); /* mid-ride the search for the bike goes on; NavService keeps retrying */ try { unregisterReceiver(changes); } catch (Exception ignored) {} mapView.onStop(); super.onStop(); }
    /** This launch only opened the move screen: nothing was set up, so there is nothing to tear down. */
    private boolean moving, offerUninstallOld;
    private final BroadcastReceiver closeScreens = new BroadcastReceiver() { public void onReceive(Context c, Intent i) { finishAndRemoveTask(); } };
    protected void onDestroy() { if (moving) { super.onDestroy(); return; } try { unregisterReceiver(closeScreens); } catch (Exception ignored) {} if (Build.VERSION.SDK_INT >= 33 && back != null) back.unregister(); ui.removeCallbacksAndMessages(null); if (shimmerAnim != null) shimmerAnim.cancel(); link.detach(this); if (debugReceiver != null) unregisterReceiver(debugReceiver); style = null; painter.forget(); MapStyle.forget(); layersReady = false; worker.shutdownNow(); net.shutdownNow(); mapView.onDestroy(); super.onDestroy(); }
    public void onLowMemory() { super.onLowMemory(); if (mapView != null) mapView.onLowMemory(); }
    /*
     * The phone hidden in a pocket, or Android short of memory: let the map drop the tiles it keeps in memory. A smaller
     * app is less likely to be the one Android closes mid-ride, which would cut off the bike display.
     */
    public void onTrimMemory(int level) {
        super.onTrimMemory(level); if (mapView == null) return;
        if (level >= TRIM_MEMORY_RUNNING_LOW) mapView.onLowMemory();
    }
    protected void onSaveInstanceState(Bundle b) { super.onSaveInstanceState(b); mapView.onSaveInstanceState(b); }

    // ================================================================ back
    /** Back steps out of the current sheet; on the map, during a ride or with no map downloaded it leaves Daanvia (Android's default). */
    private boolean consumesBack() {
        switch (mode) {
            case EXPLORE: case RIDE: return false;
            case SELECT: return !s.noMap;
            default: return true;
        }
    }
    private void handleBack() {
        switch (mode) {
            case SEARCH: case PIN: case SAVE: case PARK_FORM: go(Mode.EXPLORE); return;
            case SELECT: picker.close(); return;
            case LOT: case RESULTS: go(Mode.SEARCH); return;
            case ROUTE: closeRoute(); return;
            default:
        }
    }
    /** Android 10-15: the system still calls this (the app has not opted into predictive back there). Android 16 uses the callback. */
    @SuppressWarnings("deprecation") @android.annotation.SuppressLint("GestureBackNavigation")
    public void onBackPressed() { if (consumesBack()) handleBack(); else super.onBackPressed(); }
    /*
     * There, back goes to a callback, registered only while Daanvia has something to step back from: with none
     * registered Android runs its own back (the back-to-home animation), exactly what super.onBackPressed() did.
     */
    private BackCallback back;
    @androidx.annotation.RequiresApi(33)
    private static final class BackCallback implements android.window.OnBackInvokedCallback {
        private final HomeActivity home; private boolean registered;
        BackCallback(HomeActivity home) { this.home = home; }
        public void onBackInvoked() { home.handleBack(); }
        void sync(boolean want) {
            if (want == registered) return; registered = want;
            if (want) home.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this);
            else home.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(this);
        }
        void unregister() { sync(false); }
    }
    /** After every mode change (and when the map area appears): hold back only while it steps out of something. */
    private void syncBack() { if (Build.VERSION.SDK_INT >= 33 && back != null) back.sync(consumesBack()); }

    // ================================================================ map
    private long fpsFrames, fpsSince, fpsLast, fpsWorst;
    private void onMap(MapLibreMap m) {
        map = m; ride.attach(m); picker.attach(m); painter.attach(m);
        if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) mapView.addOnDidFinishRenderingFrameListener((fully, encode, render) -> {
            long now = SystemClock.uptimeMillis(); if (fpsLast > 0 && now - fpsLast < 1000) fpsWorst = Math.max(fpsWorst, now - fpsLast); fpsLast = now; fpsFrames++;
            if (fpsSince == 0) fpsSince = now;
            if (now - fpsSince >= 2000) { android.util.Log.d("DaanviaFPS", "fps " + (fpsFrames * 1000 / (now - fpsSince)) + " worst " + fpsWorst + " ms"); fpsFrames = 0; fpsSince = now; fpsWorst = 0; }
        });
        if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) debugLayers();
        UiSettings u = map.getUiSettings(); u.setCompassEnabled(false); u.setLogoEnabled(false); u.setAttributionEnabled(false);
        map.setMinZoomPreference(5); map.setMaxZoomPreference(19.5);
        android.content.SharedPreferences prefs = getSharedPreferences("ui", 0);
        if (prefs.contains("cam_lat")) { centeredOnRegion = true; map.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(prefs.getFloat("cam_lat", 0), prefs.getFloat("cam_lon", 0)), prefs.getFloat("cam_zoom", 13))); }
        else map.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(12.3, 122.6), 5.2));
        map.setStyle(new org.maplibre.android.maps.Style.Builder().fromJson(MapStyle.base(theme.palette)), st -> { style = st; refresh(); if (mode == Mode.SELECT) picker.center(null); });
        map.addOnMapLongClickListener(p -> { onLongPress(p.getLatitude(), p.getLongitude()); return true; });
        map.addOnMapClickListener(this::tapResult);
        map.addOnMapClickListener(this::tapRoute);
        map.addOnMapClickListener(this::tapLabel);
        map.addOnMapClickListener(p -> { if (mode == Mode.PIN) { go(Mode.EXPLORE); return true; } return false; });
        map.addOnCameraMoveListener(this::paintCompass);
        map.addOnCameraMoveListener(() -> { if (mode == Mode.SELECT) picker.reselectSoon(); });
        map.addOnCameraIdleListener(() -> {
            if (mode == Mode.SELECT) { picker.reselectNow(); return; }
            if (mode == Mode.EXPLORE || mode == Mode.PIN) { LatLng t = map.getCameraPosition().target; s.showAt(t.getLatitude(), t.getLongitude()); }
        });
        map.addOnCameraMoveStartedListener(reason -> {
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) { userMovedMap = true; centerOnFix = false; }
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE && mode == Mode.RIDE && ride.following()) { ride.unfollow(); paintLocateFab(); }
        });
    }

    private void installLayers() {
        if (layersReady || style == null) return;
        MapStyle.install(this, style, s.roadsUri); MapStyle.paint(style, theme.palette, coastline); layersReady = true; shownRegion = s.regionVersion; loadLandcover(); loadOverview();
        painter.ready(style);
        if (s.ready) { search.index(); paintRecents(); }
    }
    private void loadOverview() {
        worker.execute(() -> {
            String json = null, townJson = null;
            try { json = AreaStore.readAll(getAssets().open("overview/overview.geojson")); } catch (Exception ignored) {}
            try { townJson = AreaStore.readAll(getAssets().open("overview/towns.json")); } catch (Exception ignored) {}
            SearchIndex t = null;
            if (townJson != null) try { JSONArray a = new JSONArray(townJson); ArrayList<String> n = new ArrayList<>(); double[] la = new double[a.length()], lo = new double[a.length()];
                for (int i = 0; i < a.length(); i++) { JSONObject o = a.getJSONObject(i); n.add(o.optString("name")); la[i] = o.optDouble("lat"); lo[i] = o.optDouble("lon"); } t = new SearchIndex(n, la, lo); } catch (JSONException ignored) {}
            String j = json; SearchIndex tw = t;
            ui.post(() -> { search.towns = tw; if (j == null || style == null) return; src(MapStyle.OVERVIEW).setGeoJson(j); overview = true; coastline = true; MapStyle.paint(style, theme.palette, true); });
        });
    }
    /** First time an area is ready: frame it, unless the rider is already looking somewhere inside it. */
    private void centerOnArea() {
        if (s.fixAt == 0 && !s.active && (!centeredOnRegion || !s.inRegion(map.getCameraPosition().target.getLatitude(), map.getCameraPosition().target.getLongitude()))) { centeredOnRegion = true; map.animateCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(s.homeLat(), s.homeLon()), 13.2), 1200); }
    }
    /** The active pack changed (a trip into another map area): swap roads, coastline, search and parking to it. */
    private void regionChanged() {
        if (!layersReady || shownRegion == s.regionVersion) return;
        shownRegion = s.regionVersion;
        painter.loadRoads(); search.reset(); search.index(); loadLandcover(); paintRecents();
        if (s.ready) centerOnArea();
    }
    private void loadLandcover() {
        worker.execute(() -> {
            String land = null, water = null;
            try { land = s.regionText("land.geojson"); } catch (Exception ignored) {}
            try { water = s.regionText("water.geojson"); } catch (Exception ignored) {}
            String l = land, w = water;
            ui.post(() -> {
                if (style == null || !layersReady) return;
                if (l == null) src(MapStyle.LAND).setGeoJson(MapStyle.empty()); else src(MapStyle.LAND).setGeoJson(l);
                if (w == null) src(MapStyle.WATER).setGeoJson(MapStyle.empty()); else src(MapStyle.WATER).setGeoJson(w);
                coastline = l != null || overview; MapStyle.paint(style, theme.palette, coastline);
            });
        });
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
        Gabi.press(regionChip, () -> k.tip(regionChip, "Daanvia keeps the map, search and routes on your phone, so it works with no signal or mobile data. Right now it covers " + s.regionName + "."));
        // Daanvia is in beta until its paid launch (1.0); the chip says so and explains it when tapped.
        TextView beta = chip("Beta");
        Gabi.press(beta, () -> k.tip(beta, "Daanvia is in beta: it works for real riding, and it's still being improved. Something wrong? Settings › Support and test tools › Share report."));
        chips.addView(regionChip); chips.addView(bikeChip); chips.addView(beta);
        top.addView(chips);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP); lp.leftMargin = lp.rightMargin = k.dp(14);
        root.addView(top, lp);
    }
    private TextView chip(String s) { TextView v = k.text(s, 12, 650, INK_SOFT); v.setPadding(k.dp(10), k.dp(7), k.dp(12), k.dp(7)); k.glass(v, 99); v.setCompoundDrawablePadding(k.dp(7)); v.setElevation(k.dp(4)); return v; }
    /** A chip with a coloured dot. Called on every fix: only redrawn when its words, dot or the theme changed. */
    private void dotChip(TextView v, String text, Gabi.Role dot) {
        Object[] shown = {text, dot, theme.palette};
        if (v.getTag() instanceof Object[] && Arrays.equals((Object[])v.getTag(), shown)) return;
        v.setTag(shown); v.setText(text);
        android.graphics.drawable.GradientDrawable d = Gabi.round(dot.color(theme.palette), k.dp(4)); d.setBounds(0, 0, k.dp(8), k.dp(8));
        v.setCompoundDrawablesRelative(d, null, null, null);
    }

    private void buildFabs() {
        fabs = k.column(10);
        // Shown only while the map is turned or tilted, like Google Maps; the needle points north.
        fabCompass = k.circle(IconDrawable.COMPASS, 42, GLASS, ACCENT, this::realign); fabCompass.setVisibility(View.GONE);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2); cp.gravity = Gravity.CENTER_HORIZONTAL; fabs.addView(fabCompass, cp);
        fabLocate = k.circle(IconDrawable.LOC, 50, GLASS, ACCENT, this::onLocateFab);
        fabIcon = (ImageView)fabLocate.getChildAt(0);
        fabs.addView(fabLocate);
        fabVoice = k.circle(IconDrawable.VOICE, 50, GLASS, INK, this::toggleVoice); fabVoice.setVisibility(View.GONE); fabs.addView(fabVoice);
        fabMirror = k.circle(IconDrawable.MIRROR, 50, GLASS, INK, this::toggleMirror); fabMirror.setVisibility(View.GONE); fabs.addView(fabMirror);
        fabTft = k.circle(IconDrawable.TFT, 50, GLASS, INK, this::openSettings); fabTft.setVisibility(View.GONE); fabs.addView(fabTft);
        fabBike = k.circle(IconDrawable.MOON, 50, GLASS, INK, () -> ride.enterBikeMode()); fabBike.setVisibility(View.GONE); fabs.addView(fabBike);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END); lp.rightMargin = k.dp(16);
        root.addView(fabs, lp);
        attribution = k.text("© OpenStreetMap contributors", 10.5f, 500, MUTED);
        FrameLayout.LayoutParams ap = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START); ap.leftMargin = k.dp(18);
        root.addView(attribution, ap);
    }
    private void paintLocateFab() {
        boolean recenter = mode == Mode.RIDE && !ride.following();
        ((IconDrawable)fabIcon.getDrawable()).setColor(recenter ? theme.palette.ink : theme.palette.accent);
        Gabi.show(fabTft, mode == Mode.RIDE, k.dp(10)); Gabi.show(fabVoice, mode == Mode.RIDE, k.dp(10)); Gabi.show(fabBike, mode == Mode.RIDE, k.dp(10)); paintVoice();
        if (mode == Mode.RIDE) Gabi.show(fabLocate, recenter, k.dp(10)); else Gabi.show(fabLocate, mode == Mode.EXPLORE || mode == Mode.PIN || mode == Mode.SAVE || mode == Mode.SELECT, k.dp(10));
        paintMirror(); paintCompass();
    }

    // ================================================================ realign & mirror
    /** Show the compass while the map is turned or tilted (not while a ride follows the rider's heading), needle to north. */
    private void paintCompass() {
        if (map == null || fabCompass == null) return;
        CameraPosition c = map.getCameraPosition(); double b = ((c.bearing % 360) + 360) % 360;
        boolean show = (Math.min(b, 360 - b) > 2 || c.tilt > 2) && mode != Mode.SELECT && !(mode == Mode.RIDE && ride.following());
        fabCompass.getChildAt(0).setRotation((float)-c.bearing);
        if (show != compassShown) { compassShown = show; Gabi.show(fabCompass, show, k.dp(10)); }
    }
    /** North up and flat again. */
    private void realign() {
        if (map == null) return;
        map.animateCamera(CameraUpdateFactory.newCameraPosition(new CameraPosition.Builder(map.getCameraPosition()).bearing(0).tilt(0).build()), 500);
    }
    /** The mirror button: once a bike is set up, on the map and during rides; orange while the phone's screen is on the bike. */
    private void paintMirror() {
        if (fabMirror == null) return;
        Gabi.show(fabMirror, link != null && link.profileReady() && (mode == Mode.EXPLORE || mode == Mode.RIDE), k.dp(10));
        int tint = StreamService.mirroring ? theme.palette.accent : theme.palette.ink;
        if (tint != mirrorTint) { mirrorTint = tint; ((ImageView)fabMirror.getChildAt(0)).setImageDrawable(new IconDrawable(IconDrawable.MIRROR, tint)); }
    }
    private int mirrorTint;
    /** Put the phone's whole screen (Google Maps, Waze, music…) on the bike display, or go back to Daanvia's directions there. */
    private void toggleMirror() {
        if (link == null || !link.profileReady()) { notice("Set up your bike first"); openSettings(); return; }
        if (StreamService.mirroring) { link.stopMirror(); notice("Back to Daanvia directions on your bike"); return; }
        try { startActivityForResult(getSystemService(android.media.projection.MediaProjectionManager.class).createScreenCaptureIntent(), 75); }
        catch (Exception e) { notice("Screen sharing isn’t available on this phone"); }
    }
    private boolean voiceOn() { return getSharedPreferences("ui", 0).getBoolean("voice", true); }
    private void paintVoice() { ((ImageView)fabVoice.getChildAt(0)).setImageDrawable(new IconDrawable(voiceOn() ? IconDrawable.VOICE : IconDrawable.MUTE, voiceOn() ? theme.palette.ink : theme.palette.danger)); fabVoice.setContentDescription(voiceOn()?"Mute voice directions":"Enable voice directions"); }
    private void toggleVoice() { boolean on = !voiceOn(); getSharedPreferences("ui", 0).edit().putBoolean("voice", on).apply(); if(!on&&s.active)startService(new Intent(this,NavService.class).setAction("audio_stop")); paintVoice(); notice(on ? "Voice directions on" : "Voice directions muted"); }
    private void onLocateFab() {
        if (mode != Mode.RIDE) { userMovedMap = false; askAccuracy(true); }
        if (mode == Mode.RIDE) { ride.recenter(); paintLocateFab(); painter.puckStale(); refresh(); return; }
        if (mode == Mode.SELECT) { double[] h = picker.whereAmI(); if (h != null) map.animateCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(h[0], h[1]), 10.4), 900); else locate(); return; }
        if (s.fixAt > 0 && map != null) map.animateCamera(CameraUpdateFactory.newCameraPosition(new CameraPosition.Builder().target(new LatLng(s.lat, s.lon)).zoom(15.5).tilt(0).bearing(0).build()), 900);
        else { centerOnFix = true; if (Here.allowed(this) && here.enabled()) notice("Finding your location…"); }
        locate();
    }
    /** Move to the rider when a fix arrives after they asked, or on the first fix if they haven't moved the map yet. */
    private long settleUntil;
    private void followFirstFix() {
        if (s.fixAt == 0 || map == null || s.active || mode == Mode.RIDE) return;
        boolean first = !firstFixHandled && !userMovedMap && (mode == Mode.EXPLORE || mode == Mode.SELECT);
        firstFixHandled = true;
        if (!centerOnFix && !first) {
            // The first fixes are often rough (Wi-Fi, an old spot). Keep the rider in view while they sharpen, until the map is touched.
            if (!userMovedMap && SystemClock.elapsedRealtime() < settleUntil) {
                LatLng t = map.getCameraPosition().target;
                if (GeoMath.distance(t.getLatitude(), t.getLongitude(), s.lat, s.lon) > Math.max(25, s.accuracy * 0.5)) map.animateCamera(CameraUpdateFactory.newLatLng(new LatLng(s.lat, s.lon)), 700);
            }
            return;
        }
        centerOnFix = false; settleUntil = SystemClock.elapsedRealtime() + 45000;
        // Choosing a map area frames the rider's surroundings; everywhere else zooms to street level.
        double zoom = mode == Mode.SELECT ? 10.4 : Math.max(15, map.getCameraPosition().zoom);
        map.animateCamera(CameraUpdateFactory.newCameraPosition(new CameraPosition.Builder().target(new LatLng(s.lat, s.lon)).zoom(zoom).tilt(0).bearing(0).build()), 900);
    }

    private void buildNotice() {
        notice = k.text("", 13.5f, 600, INK); notice.setPadding(k.dp(16), k.dp(12), k.dp(16), k.dp(12)); k.glass(notice, 18); notice.setVisibility(View.GONE); notice.setElevation(k.dp(30));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP); lp.leftMargin = lp.rightMargin = k.dp(14);
        root.addView(notice, lp);
    }
    private final Runnable hideNotice = () -> Gabi.show(notice, false, -k.dp(12));
    private void notice(String text) { notice(text, null); }
    private void notice(String text, Runnable undo) { notice(text, "Undo", undo); }
    /** Toast-like glass notice; with an action, tapping it runs the action. */
    private void notice(String text, String label, Runnable action) { notice(text, label, action, action == null ? 3600 : 5000); }
    private void notice(String text, String label, Runnable action, long showMs) {
        notice.setText(action == null ? text : text + "  ·  " + label); notice.bringToFront(); Gabi.show(notice, true, -k.dp(12));
        notice.setOnClickListener(action == null ? null : v -> { action.run(); hideNotice.run(); });
        ui.removeCallbacks(hideNotice); ui.postDelayed(hideNotice, showMs);
    }

    // ================================================================ map areas
    /** Download progress: on the frame sheet while choosing, on the area chip otherwise. */
    private void onArea(AreaStore.State st) {
        if (mode == Mode.SELECT) { picker.onArea(st); return; }
        if (st.running) dotChip(regionChip, "Downloading · " + st.percent() + "%", ACCENT);
        else if (st.finished) { notice(st.name + " is ready to use offline"); refresh(); }
        else if (st.error != null) { notice(st.error); refresh(); }
    }
    /** A newer Daanvia is on GitHub: offer it once per launch, never mid-ride. */
    private void onAppUpdate(AppUpdate.State st) {
        if (updateOffered || st.release == null || !st.available() || st.downloading || s.active || mode == Mode.SELECT) return;
        updateOffered = true;
        notice("Daanvia " + st.release.version + " is available", "Update", this::openSettings);
    }
    /** Riding outside every downloaded area: offer to download where they are, once. */
    private void suggestOutside() {
        if (suggestedOutside || s.fixAt == 0 || s.active || s.noMap || s.inRegion(s.lat, s.lon)) return;
        suggestedOutside = true;
        notice("You’re outside your downloaded maps", "Download this area", () -> picker.open(new double[]{s.lat, s.lon}));
    }

    // ================================================================ explore
    private LinearLayout buildExplore() {
        LinearLayout c = k.column(0);
        LinearLayout head = k.row(8);
        TextView title = k.text("Ride somewhere", 19, 750, INK); head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(k.info("Search for a place, tap a saved place, or press and hold anywhere on the map to drop a pin. You can also share a place to Daanvia from Google Maps to save it here."));
        c.addView(head);
        exploreStatus = k.text("", 13, 500, MUTED); exploreStatus.setPadding(0, k.dp(4), 0, k.dp(14)); c.addView(exploreStatus);
        c.addView(k.label("Saved places"));
        ScrollView sv = new ScrollView(this); sv.setVerticalScrollBarEnabled(false);
        LinearLayout lists = k.column(0); sv.addView(lists);
        savedBox = k.column(0); lists.addView(savedBox);
        TextView rl = k.label("Recent"); rl.setPadding(0, k.dp(12), 0, 0); lists.addView(rl);
        recentsBox = k.column(0); lists.addView(recentsBox);
        c.addView(sv, new LinearLayout.LayoutParams(-1, -2));
        sv.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int max = Math.round(getResources().getDisplayMetrics().heightPixels * 0.42f), want = lists.getHeight() > max ? max : -2;
            if (sv.getLayoutParams().height != want) { sv.getLayoutParams().height = want; sv.post(sv::requestLayout); }
        });
        return c;
    }
    /** How far a place is from the rider, or null before the first fix. */
    private String fromHere(double lat, double lon) { return s.fixAt > 0 ? Gabi.distance(GeoMath.distance(s.lat, s.lon, lat, lon)) : null; }
    /** Under a pin or a place being saved: how far it is, else the area it's in, else that it's outside the maps ({@code outside}). */
    private String whereLabel(double lat, double lon, String outside) {
        String d = fromHere(lat, lon);
        return d != null ? d + " from you" : s.inRegion(lat, lon) ? s.regionName : "Outside " + s.regionName + outside;
    }
    private void paintSaved() {
        savedBox.removeAllViews(); savedTheme.clear();
        List<Bookmarks.Place> saved = bookmarks.all();
        for (Bookmarks.Place b : saved) {
            LinearLayout row = search.starRow(sk, b, b.name, s.inRegion(b.lat, b.lon) ? "Hold to remove" : "Outside " + s.regionName + " · hold to remove", fromHere(b.lat, b.lon));
            row.setOnLongClickListener(v -> { v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); bookmarks.remove(b.lat, b.lon); paintSaved(); notice("Removed " + b.name, () -> { bookmarks.save(b.name, b.lat, b.lon); paintSaved(); }); return true; });
            savedBox.addView(row);
        }
        if (saved.isEmpty()) savedBox.addView(sk.listRow(IconDrawable.STAR, "No saved places yet", "Tap the star on a place, or share one to Daanvia from Google Maps", null, null));
    }
    private void paintRecents() {
        paintSaved();
        recentsBox.removeAllViews(); recentsTheme.clear();
        JSONArray r = rides(); int shown = 0;
        for (int i = 0; i < r.length() && shown < 3; i++) {
            JSONObject o = r.optJSONObject(i); if (o == null) continue;
            double lat = o.optDouble("lat"), lon = o.optDouble("lon"); String name = search.branchName(Lots.renamed(o.optString("name")), lat, lon);
            recentsBox.addView(ck.listRow(IconDrawable.CLOCK, name, "Ridden recently", fromHere(lat, lon), () -> choose(lat, lon, name))); shown++;
        }
        if (shown == 0) recentsBox.addView(ck.listRow(IconDrawable.PIN, "No rides yet", "Places you ride to show up here. Long-press the map to drop a pin.", null, null));
    }
    /** Search history, shown in the search bar. */
    private JSONArray recents() { return list("recents"); }
    /** Places the rider actually rode to, shown under Recent on the home sheet. */
    private JSONArray rides() { return list("rides"); }
    private JSONArray list(String name) { try { return new JSONArray(getSharedPreferences(name, 0).getString("list", "[]")); } catch (JSONException e) { return new JSONArray(); } }
    private void remember(double lat, double lon, String name) { push("recents", lat, lon, name); }
    private void rememberRide(double lat, double lon, String name) { push("rides", lat, lon, name); }
    private void push(String list, double lat, double lon, String name) {
        if ("Map pin".equals(name)) return;
        try {
            JSONArray old = list(list), out = new JSONArray(); out.put(new JSONObject().put("name", name).put("lat", lat).put("lon", lon));
            for (int i = 0; i < old.length() && out.length() < 8; i++) { JSONObject o = old.optJSONObject(i); if (o != null && !(name.equals(o.optString("name")) && GeoMath.distance(lat, lon, o.optDouble("lat"), o.optDouble("lon")) < 150)) out.put(o); }
            getSharedPreferences(list, 0).edit().putString("list", out.toString()).apply();
        } catch (JSONException ignored) {}
    }

    // ================================================================ places from Google Maps
    /** Back from Google Maps with a copied link: offer to ride there. Only right after the handoff, so the clipboard is left alone otherwise. */
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (!focused || !search.fromGoogle() || s.active) return;
        String shared = search.copiedPlace(); if (shared == null) return;
        notice("Use the place you copied from Google Maps?", "Ride there", () -> openShared(shared, true), 12000);
    }
    /** A place from Google Maps (shared or copied): straight to the route preview when it came from a search, else saved as a place. */
    private void openShared(String text, boolean ride) {
        ShareLinks.Place place = ShareLinks.parse(text);
        if (place == null) { notice("That share has no place in it. In Google Maps, open the place and tap Share."); return; }
        if (place.located()) { useShared(place, ride); return; }
        notice("Opening the shared place…");
        net.execute(() -> {
            ShareLinks.Place found;
            try { found = ShareLinks.resolve(place); } catch (Exception e) { found = place; }
            ShareLinks.Place r = found;
            ui.post(() -> {
                if (isDestroyed()) return;
                if (r.located()) useShared(r, ride);
                else notice("Couldn’t open that link. Try again with signal, or press and hold the map to pin it.");
            });
        });
    }
    private void useShared(ShareLinks.Place p, boolean ride) {
        String name = p.name.equals("Shared place") ? "" : p.name;
        if (ride) choose(p.lat, p.lon, name.isEmpty() ? "Place from Google Maps" : name); else openSave(name, p.lat, p.lon);
    }
    private void hideKeyboard() { ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(root.getWindowToken(), 0); }
    private void showKeyboard(View v) { ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(v, 0); }

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

    // ================================================================ parking lot
    private LinearLayout buildLot() {
        LinearLayout c = k.column(0);
        LinearLayout head = k.row(8);
        LinearLayout tc = k.column(3); lotTitle = k.text("", 19, 750, INK); lotSub = k.text("", 13, 500, MUTED); tc.addView(lotTitle); tc.addView(lotSub);
        head.addView(tc, new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(k.info("Parking details come from OpenStreetMap, a public map that people keep up to date. When you have signal, Daanvia checks it again so you see the latest. Hours, prices and access can still be missing or out of date, so check the signs when you arrive."));
        head.addView(k.circle(IconDrawable.CLOSE, 38, CHIP, MUTED, () -> go(Mode.SEARCH)));
        c.addView(head);
        lotFacts = k.column(2); lotFacts.setPadding(0, k.dp(10), 0, k.dp(4)); c.addView(lotFacts);
        LinearLayout live = k.row(8); live.setPadding(k.dp(12), k.dp(10), k.dp(12), k.dp(10)); k.fill(live, 14, CHIP);
        lotLiveDot = new View(this); live.addView(lotLiveDot, new LinearLayout.LayoutParams(k.dp(8), k.dp(8)));
        lotLive = k.text("", 13, 600, INK_SOFT); live.addView(lotLive, new LinearLayout.LayoutParams(0, -2, 1));
        c.addView(live);
        LinearLayout btns = k.row(8); btns.setPadding(0, k.dp(14), 0, 0);
        btns.addView(k.circle(IconDrawable.STAR, 56, CHIP, ACCENT, () -> { if (lot != null) openSave(lotName(lot, lotParent), lot.lat, lot.lon); }));
        btns.addView(k.primary("Ride here", () -> { if (lot != null) choose(lot.lat, lot.lon, lotName(lot, lotParent)); }), new LinearLayout.LayoutParams(0, -2, 1));
        c.addView(btns);
        return c;
    }
    /**
     * What to call a lot: its own name, or "Allson's parking" when it belongs to a place. A lot with neither gets its
     * nearest landmark or street, "Parking lot near Gaisano Country Mall" or "Parking lot · Governor M. Cuenco Avenue",
     * so look-alike unnamed lots can be told apart.
     */
    private String lotName(Lots.Lot l, String parent) {
        String n = l.placeName(parent);
        if ((parent != null && !parent.trim().isEmpty()) || !Lots.Lot.generic(n)) return n;
        String where = lotStreets.get(l.id);
        if (where == null) {
            // A named place within 60 m ("Parking lot near Gaisano Country Mall") tells look-alike lots apart best; else the street.
            String place = nearestPlace(l.lat, l.lon, 60), street = place == null ? Branches.street(l.lat, l.lon, s.roadIndex, 150) : null;
            where = place != null ? " near " + place : street != null ? " · " + street : "";
            lotStreets.put(l.id, where);
        }
        return n + where;
    }
    /** The nearest named place within {@code meters}, leaving out other parking. */
    private String nearestPlace(double lat, double lon, double meters) {
        NavSession.Places p = s.places; String best = null; double bestD = meters, pad = meters / 111000.0;
        for (int i = 0; i < p.size(); i++) {
            if (Math.abs(p.lat[i] - lat) > pad || Math.abs(p.lon[i] - lon) > pad * 1.1) continue;
            String name = p.names.get(i); if (name == null || name.isEmpty() || name.toLowerCase(Locale.ROOT).contains("parking")) continue;
            double d = GeoMath.distance(lat, lon, p.lat[i], p.lon[i]); if (d < bestD) { bestD = d; best = name; }
        }
        return best;
    }
    private final java.util.HashMap<String, String> lotStreets = new java.util.HashMap<>();
    private LinearLayout parkList; private int parkListCount = -1;
    private void toggleArrivalParking() {
        if (parkList.getVisibility() == View.VISIBLE) { parkList.setVisibility(View.GONE); parkList.removeAllViews(); return; }
        List<Lots.Lot> near = new ArrayList<>(); for (JSONObject o : s.nearbyParking) near.add(new Lots.Lot(o));
        Lots.rank(near, destLat, destLon); parkListCount = near.size();
        parkList.removeAllViews(); parkTheme.clear(); search.addLots(pk, parkList, near, null, destLat, destLon, 4); parkList.setVisibility(View.VISIBLE);
    }
    private void openLot(Lots.Lot l, String parent) {
        lot = l; lotParent = parent; hideKeyboard();
        // Headed by the place it belongs to; the lot's own description underneath.
        String named = lotName(l, parent); lotTitle.setText(named); lotSub.setText(parent == null ? "" : named.equals(l.title()) ? "At " + parent : l.title());
        paintLotFacts(l);
        liveState(MUTED, "Checking the map for updates…");
        go(Mode.LOT);
        if (map != null) map.animateCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(l.lat, l.lon), 17), 900);
        int token = ++lotToken;
        if("Owner-entered".equals(l.source)){liveState(MUTED,"Saved by you on this phone · not checked online");return;}
        net.execute(() -> {
            LiveCheck.Result r = LiveCheck.check(l.id);
            ui.post(() -> {
                if (token != lotToken || mode != Mode.LOT) return;
                if (!r.reachable) liveState(MUTED, "OpenStreetMap is busy, so these details are from your downloaded map.");
                else if (!r.exists) liveState(DANGER, "This parking is no longer on the map. It may have closed.");
                else {
                    liveState(OK, "OpenStreetMap entry exists" + (r.edited == null ? "" : " · map edited " + month(r.edited)) + ". Price and entrance not independently verified.");
                    Lots.Lot fresh = Lots.Lot.fromTags(l.id, l.lat, l.lon, r.tags); lot = fresh; lotTitle.setText(lotName(fresh, lotParent)); paintLotFacts(fresh);
                }
            });
        });
    }
    private void liveState(Gabi.Role role, String text) { lotLive.setText(text); lotLiveDot.setBackground(Gabi.round(role.color(theme.palette), k.dp(4))); }
    private static String month(String isoDate) {
        try { return new java.text.SimpleDateFormat("MMM yyyy", Locale.US).format(new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(isoDate)); } catch (Exception e) { return isoDate; }
    }
    private void paintParkEntrance(boolean entrance) {
        parkIsEntrance = entrance; parkEntranceRow.removeAllViews();
        parkEntranceRow.addView(k.segmented(new String[]{"No", "Yes"}, entrance ? 1 : 0, i -> parkIsEntrance = i == 1));
    }
    private void paintLotFacts(Lots.Lot l) {
        lotFacts.removeAllViews(); lotTheme.clear();
        // Only what the rider added themselves; the sheet otherwise looks as it always has.
        if(l.entrance)fact(IconDrawable.PIN,"Motorcycle entrance · marked by you",OK);
        fact(IconDrawable.BIKE, l.motorcycle() ? "Motorcycles can park here" : l.noMotorcycles() ? "No motorcycles" : "Motorcycle parking not confirmed", l.motorcycle() ? OK : MUTED);
        if (l.hours.isEmpty()) fact(IconDrawable.CLOCK, "Opening hours not listed", MUTED);
        else { OpeningHours.Status st = OpeningHours.now(l.hours); fact(IconDrawable.CLOCK, st.label, st.state == OpeningHours.State.OPEN ? OK : st.state == OpeningHours.State.CLOSED ? DANGER : MUTED); }
        String price = !l.charge.isEmpty() ? l.charge : l.fee.equals("yes") ? "Paid parking · price not listed" : l.fee.equals("no") ? "Free" : "Price not listed";
        fact(IconDrawable.PARK, price, MUTED);
        if(l.verifiedAt>0)fact(IconDrawable.CLOCK,"Rate entered by you on "+new java.text.SimpleDateFormat("d MMM yyyy",Locale.US).format(new java.util.Date(l.verifiedAt))+". Recheck the current signs.",MUTED);
        String access = l.restricted() ? "Private · not open to the public" : l.access.equals("customers") ? "For customers" : l.access.equals("yes") ? "Open to the public" : l.access.equals("destination") ? "For visitors" : "Access not listed";
        fact(IconDrawable.CHECK, access, l.restricted() ? DANGER : MUTED);
        if (!l.capacityMoto.isEmpty()) fact(IconDrawable.BIKE, "Space for " + l.capacityMoto + " motorcycles", MUTED);
        else if (!l.capacity.isEmpty()) fact(IconDrawable.PARK, l.capacity + " spaces", MUTED);
        if (!l.operator.isEmpty()) fact(IconDrawable.TOOLS, "Run by " + l.operator, MUTED);
    }
    private void fact(int icon, String text, Gabi.Role tint) {
        LinearLayout r = lk.row(12); r.setPadding(0, lk.dp(6), 0, lk.dp(6));
        r.addView(lk.icon(icon, 18, tint)); r.addView(lk.text(text, 14, 550, INK), new LinearLayout.LayoutParams(0, -2, 1));
        lotFacts.addView(r);
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
            bookmarks.save(n, saveLat, saveLon); hideKeyboard(); go(Mode.EXPLORE); notice("Saved " + n);
        }), new LinearLayout.LayoutParams(0, -2, 1.3f));
        c.addView(btns);
        return c;
    }
    private void openSave(String name, double lat, double lon) {
        if (s.active) { notice("End the ride before saving places"); return; }
        saveLat = lat; saveLon = lon; saveName.setText(name); saveName.setSelection(saveName.length());
        saveSub.setText(whereLabel(lat, lon, " for now · saved for later"));
        dropLat = lat; dropLon = lon;
        go(Mode.SAVE);
        if (map != null) map.animateCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(lat, lon), 16), 900);
    }
    /** Tapping a place's name on the map (a shop, a mall, a barangay) picks it as the destination, like Google Maps. */
    private boolean tapRoute(LatLng p) {
        if(map==null||mode!=Mode.ROUTE||s.active||s.routing)return false;
        android.graphics.PointF at=map.getProjection().toScreenLocation(p);float r=k.dp(12);
        android.graphics.RectF box=new android.graphics.RectF(at.x-r,at.y-r,at.x+r,at.y+r);
        if(s.routeChoices.size()<2)return false;  // one route: nothing to choose, so place names stay tappable
        if(!map.queryRenderedFeatures(box,"route-line").isEmpty())return true;
        for(Feature hit:map.queryRenderedFeatures(box,"alternative-line"))if(hit.hasProperty("choice")){
            s.selectRoute(hit.getNumberProperty("choice").intValue());root.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);return true;
        }
        return false;
    }
    private boolean tapLabel(LatLng p) {
        if (map == null || s.active || !(mode == Mode.EXPLORE || mode == Mode.PIN || mode == Mode.ROUTE || mode == Mode.RESULTS)) return false;
        android.graphics.PointF at = map.getProjection().toScreenLocation(p); float r = k.dp(16);
        List<Feature> hits = map.queryRenderedFeatures(new android.graphics.RectF(at.x - r, at.y - r, at.x + r, at.y + r), "online-poi-major", "online-poi", "online-places");
        Feature best = null; double bestD = Double.MAX_VALUE; double[] spot = null;
        for (Feature f : hits) {
            if (!(f.geometry() instanceof org.maplibre.geojson.Point) || !f.hasProperty("name") || f.getStringProperty("name").trim().isEmpty()) continue;
            org.maplibre.geojson.Point pt = (org.maplibre.geojson.Point)f.geometry();
            android.graphics.PointF sp = map.getProjection().toScreenLocation(new LatLng(pt.latitude(), pt.longitude()));
            double d = Math.hypot(sp.x - at.x, sp.y - at.y); if (d < bestD) { bestD = d; best = f; spot = new double[]{pt.latitude(), pt.longitude()}; }
        }
        if (best == null) return false;
        String name = search.branchLabel(best.getStringProperty("name").trim(), spot[0], spot[1]);
        root.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        choose(spot[0], spot[1], name);
        return true;
    }
    private void onLongPress(double lat, double lon) {
        if (s.active) { notice("End the ride before changing destination"); return; }
        if (mode == Mode.PARK_FORM || mode == Mode.SAVE) return;
        pendingPlan = false; dropLat = lat; dropLon = lon;
        pinSub.setText(whereLabel(lat, lon, " for now"));
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
        // Minutes on their own line, distance and arrival under them: side by side, a long arrival time wrapped and large
        // system font sizes pushed the minutes out of the card.
        LinearLayout etaRow = k.row(10); etaRow.setGravity(Gravity.CENTER_VERTICAL); etaRow.setPadding(0, k.dp(4), 0, 0);
        routeEta = k.single(k.num("—", 40, 800, INK)); routeEta.setLetterSpacing(-0.03f); etaRow.addView(routeEta, new LinearLayout.LayoutParams(0, -2, 1));
        routeMeta = k.single(k.num("", 15, 600, MUTED)); routeMeta.setPadding(0, 0, 0, k.dp(2));
        etaRow.addView(k.info("Rough offline estimate. Live traffic, current signal waits and roadworks are unavailable. Times use the downloaded road data with a fixed general allowance. Time-of-day congestion is unknown. Routes use scooter access rules in the downloaded map."));
        c.addView(etaRow); c.addView(routeMeta);
        shimmer = new View(this); k.fill(shimmer, 2, ACCENT); shimmer.setPivotX(0);
        FrameLayout track = new FrameLayout(this); k.fill(track, 2, CHIP); track.addView(shimmer, new FrameLayout.LayoutParams(k.dp(90), -1));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, k.dp(3)); tp.topMargin = k.dp(10); c.addView(track, tp);
        ValueAnimator sh = ValueAnimator.ofFloat(-0.3f, 1.1f); sh.setDuration(1100); sh.setRepeatCount(ValueAnimator.INFINITE); sh.setInterpolator(Gabi.EASE_IN_OUT);
        sh.addUpdateListener(a -> shimmer.setTranslationX((float)a.getAnimatedValue() * track.getWidth()));
        track.setTag(sh); shimmerAnim = sh;
        routeStatus = k.text("", 13, 550, INK_SOFT); routeStatus.setPadding(0, k.dp(8), 0, 0); c.addView(routeStatus);
        routePicker = new RouteChoicesView(k, option -> s.selectRoute(option)); c.addView(routePicker.view);
        coverageButton=k.ghost("Review offline map coverage", () -> {
            double[] point=s.coverageGap!=null?s.coverageGap:!s.inRegion(s.lat,s.lon)?new double[]{s.lat,s.lon}:new double[]{destLat,destLon};
            picker.open(point);notice("Download the start, destination and connecting areas. Update older areas in Settings.");
        });c.addView(coverageButton);
        View gap = new View(this); c.addView(gap, new LinearLayout.LayoutParams(1, k.dp(12)));
        parkRow = k.row(10); parkRow.setPadding(k.dp(12), k.dp(10), k.dp(12), k.dp(10)); k.fill(parkRow, 16, ACCENT_SOFT);
        parkRow.addView(k.icon(IconDrawable.PARK, 18, ACCENT)); parkText = k.text("", 13, 600, INK_SOFT); parkRow.addView(parkText, new LinearLayout.LayoutParams(0, -2, 1));
        parkRow.addView(k.icon(IconDrawable.CHEVRON, 16, INK_SOFT));
        c.addView(parkRow);
        // Tap to see which lots they are: each by its name, its place, or its street, nearest to the destination first.
        parkList = k.column(0); parkList.setVisibility(View.GONE); c.addView(parkList);
        Gabi.press(parkRow, this::toggleArrivalParking);
        simNote = k.text("The demo moves by itself so you can check the bike display while parked. Don’t follow it while riding.", 12.5f, 500, MUTED);
        simNote.setPadding(0, k.dp(10), 0, 0); c.addView(simNote);
        LinearLayout btns = k.row(0); k.fill(btns, 28, ACCENT); btns.setClipToOutline(true); startRow = btns;
        startBtn = k.primary("Start ride", this::start);
        TextView editStops = k.primary("Edit stops", () -> startActivity(new Intent(this, TripPlannerActivity.class))); editStops.setTextSize(15);
        View divider = new View(this); divider.setAlpha(.25f);
        theme.add(p -> {
            startBtn.setBackground(Gabi.ripple(p, null, 0)); editStops.setBackground(Gabi.ripple(p, null, 0));
            divider.setBackgroundColor(p.onAccent);
        });
        btns.addView(startBtn, new LinearLayout.LayoutParams(0, -2, 1.6f));
        btns.addView(divider, new LinearLayout.LayoutParams(k.dp(1), k.dp(24)));
        btns.addView(editStops, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams actions = new LinearLayout.LayoutParams(-1, -2); actions.topMargin = k.dp(12); c.addView(btns, actions);
        return c;
    }
    private ValueAnimator shimmerAnim;
    private TextView coverageButton;
    /** The destination is a saved place: worked out when it changes (not on every fix, which would read the saved list each time). */
    private boolean routeSaved; private Boolean starShown; private Gabi.Palette starPalette;
    private void markSaved() { routeSaved = destName != null && bookmarks.find(destLat, destLon) != null; }
    private void paintRoute() {
        boolean mine = destName != null && destName.equals(s.destination) && !s.shape.isEmpty();
        routeName.setText(demoTrip ? "Demo ride" : destName == null ? "" : destName);
        boolean busy = s.routing || pendingPlan;
        ((View)shimmer.getParent()).setVisibility(busy ? View.VISIBLE : View.GONE);
        if (busy && !shimmerAnim.isStarted()) shimmerAnim.start(); else if (!busy && shimmerAnim.isStarted()) shimmerAnim.cancel();
        if (s.routing) { routeEta.setText("—"); routeMeta.setText(""); routeStatus.setText("Finding the best route…"); }
        else if (pendingPlan) { routeEta.setText("—"); routeMeta.setText(""); routeStatus.setText("Finding your location…"); }
        else if (mine) {
            routeEta.setText(Gabi.minutes(s.seconds)); routeMeta.setText(Gabi.distance(s.remaining) + " · arrive " + Gabi.clock(s.seconds));
            routeStatus.setText(s.simulation ? "Demo route ready" : s.privateRoad ? "This place is inside a private subdivision. Expect a guardhouse and have your ID ready, or ride to a spot outside the gate."
                : s.endsShort > 80 ? "The route ends at the nearest road it can reach, " + Gabi.distance(s.endsShort) + " from the place. The way in may be private or customers-only."
                : s.gates > 0 ? (s.gates == 1 ? "A mapped gate may be on this route." : s.gates + " mapped gates may be on this route.") + " Check access; these are not confirmed guardhouses." : "");
            if(!s.simulation&&s.startsShort>80)routeStatus.append((routeStatus.length()>0?"\n":"")+"Guidance starts on a reachable road "+Gabi.distance(s.startsShort)+" from your location. Check the way out before starting.");
        } else { routeEta.setText("—"); routeMeta.setText(""); routeStatus.setText(friendly(s.status)); }
        if(mine && s.coverageGap!=null)routeStatus.append((routeStatus.length()>0?"\n":"")+"This route leaves your downloaded detailed maps. Download the connecting areas before riding.");
        String problem = s.status;
        boolean noMap = problem.contains("Outside the installed") || problem.contains("compatible offline maps") || problem.contains("offline coverage");
        coverageButton.setVisibility(!busy && ((!mine && noMap) || (mine && s.coverageGap!=null)) ? View.VISIBLE : View.GONE);
        routeStatus.setVisibility(routeStatus.length() == 0 ? View.GONE : View.VISIBLE);
        routePicker.render(mine?s.routeChoices:Collections.emptyList(),s.selectedRoute,NavSession.OFFLINE_TIME_ALLOWANCE,busy);
        int lots = s.nearbyParking.size(), priced = 0;
        for (JSONObject l : s.nearbyParking) if (!MapStyle.priceOf(l).isEmpty()) priced++;
        parkRow.setVisibility(mine && lots > 0 ? View.VISIBLE : View.GONE);
        if (!(mine && lots > 0) || lots != parkListCount) { parkList.setVisibility(View.GONE); parkList.removeAllViews(); }
        parkText.setText(lots + " parking " + (lots == 1 ? "spot" : "spots") + " near arrival" + (priced > 0 ? " · " + priced + " with a saved rate" : ""));
        simNote.setVisibility(demoTrip ? View.VISIBLE : View.GONE);
        startBtn.setText(demoTrip ? "Start demo" : "Start ride");
        boolean can = mine && !s.routing; startRow.setAlpha(can ? 1f : 0.45f);
        routeStar.setVisibility(demoTrip ? View.GONE : View.VISIBLE);
        if (starShown == null || starShown != routeSaved || starPalette != theme.palette) {
            starShown = routeSaved; starPalette = theme.palette;
            routeStar.setContentDescription(routeSaved ? "Remove saved destination" : "Save destination");
            ((ImageView)routeStar.getChildAt(0)).setImageDrawable(new IconDrawable(routeSaved ? IconDrawable.STAR_FILLED : IconDrawable.STAR, theme.palette.accent));
        }
    }
    /** Turn engine status lines into plain words for the route card. */
    private String friendly(String status) {
        if (status == null) return "";
        if (status.startsWith("Outside")) return s.regions.size() > 1 ? "That place isn’t covered by the maps on your phone yet." : "That place is outside " + s.regionName + " for now.";
        if (status.startsWith("Start and destination")) return "Update both offline areas to the same map build, then preview this trip again.";
        if (status.contains("unconnected")) return "Your destination isn’t in your downloaded areas. Download the area that includes it in Settings › Maps.";
        if (status.startsWith("No offline route")) return "Couldn’t find a route there. Try a spot on a nearby road.";
        if (status.startsWith("Wait")) return "Getting the map ready…";
        return "";
    }
    private void toggleRouteStar() {
        if (destName == null) return;
        Bookmarks.Place b = bookmarks.find(destLat, destLon);
        if (b != null) { bookmarks.remove(destLat, destLon); notice("Removed " + b.name, () -> { bookmarks.save(b.name, b.lat, b.lon); markSaved(); paintRoute(); }); }
        else if ("Map pin".equals(destName)) { openSave("", destLat, destLon); return; }
        else { bookmarks.save(destName, destLat, destLon); notice("Saved " + destName); }
        markSaved(); paintRoute();
    }

    /** Leave the demo: the simulated position goes, so real GPS takes over. */
    private void endSimulation() { if (s.simulation) { s.simulation = false; s.fixAt = 0; } }
    private void choose(double lat, double lon, String name) {
        if (pickStops != null) { addPickedStop(lat, lon, name); return; }
        fromResults = mode == Mode.RESULTS;
        if (s.active) { notice("End the ride before changing destination"); return; }
        if (s.routing) { notice("Wait for the route calculation"); return; }
        hideKeyboard();
        endSimulation();
        plannedTrip=Collections.emptyList();
        destLat = lat; destLon = lon; destName = name; demoTrip = false; remember(lat, lon, name);
        go(Mode.ROUTE);
        if (!freshFix()) { pendingPlan = true; locate(); }
        else { pendingPlan = false; s.plan(lat, lon, name, false); }
        if (map != null && s.fixAt == 0) map.animateCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(lat, lon), 15), 900);
        paintRoute();
    }
    /** A live fix from the last two minutes; the phone's last known spot can be much older. */
    private boolean freshFix() { return s.fixAt > 0 && SystemClock.elapsedRealtime() - s.fixAt < 120000; }
    private void closeRoute() { if (!s.active) endSimulation(); pendingPlan = false; destName = null; demoTrip = false; go(fromResults && resultsShown != null ? Mode.RESULTS : Mode.EXPLORE); if (!s.active) getSharedPreferences("ui", 0).edit().remove("ride").apply(); }

    private void start() {
        boolean demo = demoTrip;
        if (!s.ready || s.shape.isEmpty() || s.routing || pendingPlan || destName==null || !destName.equals(s.destination) || Math.abs(destLat-s.destLat)>1e-6 || Math.abs(destLon-s.destLon)>1e-6) { Gabi.shake(startBtn); notice("Choose a destination and calculate a route first"); return; }
        if (!demo && !Here.precise(this)) { notice("Directions need precise location"); requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 73); return; }
        if (!demo && (s.simulation || s.fixAt == 0 || SystemClock.elapsedRealtime() - s.fixAt > 12000 || s.accuracy > 40)) {
            Gabi.shake(startBtn); notice(s.fixAt > 0 && s.accuracy > 40 ? "Your location is still rough (±" + Math.round(s.accuracy) + " m). Step outside or wait a moment." : "Waiting for a clear GPS signal. Try again in a moment."); locate(); return;
        }
        boolean askingNotifications = askNotifications(demo);
        JSONObject keep = null;
        if (!demo) {
            rememberRide(destLat, destLon, destName);
            try { keep = new JSONObject().put("lat", destLat).put("lon", destLon).put("name", destName).put("stops",TripStop.json(s.remainingStops())); } catch (JSONException ignored) {}
        }
        rideStarting(keep);
        if (!demo) rideAsks(askingNotifications);
        startForegroundService(new Intent(this, NavService.class).putExtra("demo", demo));
    }
    /**
     * What every ride start shares. The ride ({@code keep}, null for a demo) is kept until it ends, so a ride Android cut
     * short (the app killed in the background) can be picked up again; and a ride connects the bike display straight away.
     */
    private void rideStarting(JSONObject keep) {
        if (keep != null) try { getSharedPreferences("ui", 0).edit().putString("ride", keep.put("at", System.currentTimeMillis()).toString()).apply(); } catch (JSONException ignored) {}
        link.allowAuto(); lookStep = 0; autoConnect(); lookAgain();
    }
    /**
     * Ask the phone to leave the ride running. Never when a ride starts again on its own after the phone closed the app:
     * the rider may already be moving. Nor on top of the notification question (Android shows one at a time): it follows
     * that answer (onRequestPermissionsResult).
     */
    private void rideAsks(boolean askingNotifications) {
        if (!resuming && !askingNotifications) KeepAlive.askForRide(this);
        if (!resuming && KeepAlive.remindColorOs(this)) notice("Your " + KeepAlive.colorOs() + " phone can close Daanvia mid-ride. Set it up so your ride keeps going", "Show me", () -> openSettings("coloros"), 15000);
    }
    /**
     * The ride's notification carries the lock-screen buttons (Reconnect, Commands, Exit). Asked once; after a refusal,
     * a reminder with the way to Android's settings instead, since Android stops showing the question. True while asking.
     */
    private boolean askNotifications(boolean demo) {
        if (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return false;
        android.content.SharedPreferences p = getSharedPreferences("ui", 0);
        if (!p.getBoolean("asked_notifications", false)) { p.edit().putBoolean("asked_notifications", true).apply(); requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 72); return true; }
        if (!demo && System.currentTimeMillis() - p.getLong("notifications_reminded", 0) > 3L * 24 * 3600 * 1000) { p.edit().putLong("notifications_reminded", System.currentTimeMillis()).apply(); notificationsNotice(); }
        return false;
    }
    private void openTrip(List<TripStop> stops,String name) {
        if(s.active){notice("End the ride before changing its stops");return;}
        if(stops.isEmpty()){notice("Add a destination first");return;}
        endSimulation();
        plannedTrip=new ArrayList<>(stops);TripStop end=stops.get(stops.size()-1);
        destLat=end.lat;destLon=end.lon;destName=name;demoTrip=false;pendingPlan=true;go(Mode.ROUTE);
        // While another route is still calculating, the trip waits and is planned as soon as that one finishes.
        if(s.ready&&freshFix()&&!s.routing){pendingPlan=false;s.planTrip(plannedTrip,name);}else if(!s.routing)locate();
        paintRoute();
    }
    private boolean resumeOffered;
    /**
     * Pick up a ride the phone ended without the rider (the ride is kept from Start ride until it ends or arrives, and
     * marked alive while it runs): route to the same place again and start it on its own once GPS is clear, instead of
     * an offer that was easy to miss. Rides last seen more than 30 minutes ago are dropped.
     */
    /** Bluetooth only sets the bike's clock: if asking for it ever fails, Daanvia opens anyway and the log says why. */
    private void bluetoothForClock() {
        try { link.askBluetoothIfNeeded(); } catch (RuntimeException e) { link.note("Couldn't ask for Bluetooth for the bike's clock: " + e); }
    }
    private void offerResume() {
        if (s.active) return;
        android.content.SharedPreferences u = getSharedPreferences("ui", 0); String raw = u.getString("ride", null); if (raw == null) return;
        try {
            org.json.JSONObject r = new org.json.JSONObject(raw);
            if (System.currentTimeMillis() - Math.max(r.getLong("at"), u.getLong("ride_alive", 0)) > 30 * 60_000L) { u.edit().remove("ride").apply(); return; }
            boolean killed = KeepAlive.killedBySystem(this); KeepAlive.recheckColorOs(this);
            // A free ride from 0.9.21 or older: there's nothing to resume now, the bike display rides along on its own.
            if (r.optBoolean("cruise")) { u.edit().remove("ride").apply(); return; }
            double la = r.getDouble("lat"), lo = r.getDouble("lon"); String name = r.getString("name");
            JSONArray stops=r.optJSONArray("stops"),via=r.optJSONArray("via");
            // Back on the route the rider chose, not the quickest one (see NavSession.chosenVia).
            if(via!=null&&via.length()==2)s.resumeVia(new double[]{via.getDouble(0),via.getDouble(1)});
            if(stops!=null&&stops.length()>0)openTrip(TripStop.parse(stops.toString()),name);else choose(la, lo, name); resumeTries = 0; ui.removeCallbacks(resumeStart); ui.postDelayed(resumeStart, 1500);
            if (killed) notice("Your phone closed Daanvia, so your ride to " + name + " is starting again. Tap to stop your phone closing it", "Fix", () -> { if (KeepAlive.colorOs() != null) openSettings("coloros"); else KeepAlive.fix(this); }, 15000);
            else notice("Continuing your ride to " + name, null, null, 6000);
        } catch (org.json.JSONException | IllegalArgumentException e) { u.edit().remove("ride").apply(); }
    }
    private int resumeTries; private boolean resuming;
    /** Start the picked-up ride once its route is ready and GPS is clear; after about two minutes, leave it to the rider. */
    private final Runnable resumeStart = new Runnable() { public void run() {
        if (s.active || mode != Mode.ROUTE || destName == null || demoTrip) return;
        boolean go = s.ready && !s.shape.isEmpty() && !s.routing && s.fixAt > 0 && SystemClock.elapsedRealtime() - s.fixAt < 12000 && s.accuracy <= 40;
        // Only while Daanvia is on screen: Android refuses to start the ride from the background.
        if (go && started) { resuming = true; try { start(); } finally { resuming = false; } return; }
        if (++resumeTries < 80) ui.postDelayed(this, 1500); else notice("Your ride is ready. Tap Start ride once GPS is clear.");
    } };

    /** Simulated ride over the region's sample route, started from Settings › Testing. */
    private void demo() {
        if (s.active) { notice("End the current ride first"); return; }
        if (!s.ready) { pendingDemo = true; notice("Getting the map ready first…"); return; }
        if (s.routing) { notice("Wait for the route calculation"); return; }
        here.stop(); s.simulation=true; centerOnFix=false;
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
        c.addView(k.text("Add the motorcycle rate you saw at the gate. Leave the price blank if you’re not sure.", 13, 500, MUTED));
        parkName = input("Parking name", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS); c.addView(parkName, new LinearLayout.LayoutParams(-1, k.dp(52)));
        parkPrice = input("₱ amount · optional", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL); c.addView(parkPrice, new LinearLayout.LayoutParams(-1, k.dp(52)));
        String[] basis = {"hour", "entry", "2h"};
        c.addView(k.segmented(new String[]{"Per hour", "Per entry", "Per 2 hours"}, 0, i -> parkBasis = basis[i]));
        c.addView(k.text("Is this pin the motorcycle entrance?", 13, 600, INK_SOFT));
        parkEntranceRow = k.column(0); c.addView(parkEntranceRow); paintParkEntrance(false);
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
            s.saveParking(parkLat, parkLon, name.isEmpty() ? "Saved parking" : name, parkPrice.getText().toString(), parkBasis,parkIsEntrance);
            hideKeyboard(); parkName.setText(""); parkPrice.setText("");paintParkEntrance(false); parkError.setVisibility(View.GONE);
            go(Mode.EXPLORE); notice("Saved. The rate shows on the parking pin for 30 days.");
        } catch (Exception e) { parkError.setText("Enter a valid non-negative amount or leave it blank"); parkError.setVisibility(View.VISIBLE); Gabi.shake(parkPrice); }
    }

    // ================================================================ search results on the map
    private LinearLayout resultsSheet, resultsList; private TextView resultsTitle, resultsSub;
    /** The results on the map (kept while a route from them is previewed, to come back to); null when none. */
    private org.maplibre.geojson.FeatureCollection resultsShown;
    /** The route on screen was picked from the results on the map: closing it goes back to them. */
    private boolean fromResults;
    private LinearLayout buildResults() {
        LinearLayout c = k.column(0);
        LinearLayout head = k.row(10); LinearLayout titles = k.column(2);
        resultsTitle = k.single(k.text("", 17, 750, INK)); resultsSub = k.single(k.text("", 13, 550, MUTED));
        titles.addView(resultsTitle); titles.addView(resultsSub); head.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(k.circle(IconDrawable.SEARCH, 36, CHIP, ACCENT, () -> go(Mode.SEARCH)));
        head.addView(k.circle(IconDrawable.CLOSE, 36, CHIP, MUTED, () -> { resultsShown = null; go(Mode.EXPLORE); }));
        c.addView(head);
        resultsList = k.column(0); resultsList.setPadding(0, k.dp(6), 0, 0); c.addView(resultsList);
        return c;
    }
    /** Search's results as pins, the closest first in the list under the map, the map framed on the closest few and the rider. */
    private void showResults() {
        List<SearchPanel.Spot> near = search.nearest(30);
        if (near.isEmpty()) { notice("Nothing to show on the map yet"); return; }
        double[] from = search.from();
        ArrayList<Feature> pins = new ArrayList<>();
        for (int i = 0; i < near.size(); i++) { SearchPanel.Spot p = near.get(i); Feature f = MapStyle.point(p.lat, p.lon);
            f.addStringProperty("label", i < 5 ? p.name : ""); f.addStringProperty("name", p.name); f.addStringProperty("icon", i == 0 ? "result-near" : "result"); f.addNumberProperty("rank", near.size() - i); pins.add(f); }
        resultsShown = org.maplibre.geojson.FeatureCollection.fromFeatures(pins);
        resultsTitle.setText(pickStops != null ? "Add a stop" : "“" + search.query() + "”");
        resultsSub.setText((near.size() == 1 ? "1 place" : near.size() + " places") + ", closest first" + (pickStops != null ? " · tap one to add it" : ""));
        resultsList.removeAllViews();
        // The closest four under the map, so the map stays in view; the rest are pins, and all of them in the list.
        for (int i = 0; i < near.size() && i < 4; i++) { SearchPanel.Spot p = near.get(i);
            resultsList.addView(k.listRow(IconDrawable.PIN, p.name, p.sub, Gabi.distance(GeoMath.distance(from[0], from[1], p.lat, p.lon)), () -> choose(p.lat, p.lon, p.name))); }
        if (near.size() > 4) resultsList.addView(k.listRow(IconDrawable.SEARCH, "See all " + near.size() + " in the list", null, null, () -> go(Mode.SEARCH)));
        go(Mode.RESULTS);
        // Framed on the closest five and the rider (when the phone knows where they are), above the sheet.
        if (map == null) return;
        org.maplibre.android.geometry.LatLngBounds.Builder b = new org.maplibre.android.geometry.LatLngBounds.Builder();
        int framed = Math.min(5, near.size()); for (int i = 0; i < framed; i++) b.include(new LatLng(near.get(i).lat, near.get(i).lon));
        if (s.fixAt > 0 && GeoMath.distance(s.lat, s.lon, near.get(0).lat, near.get(0).lon) < 30_000) b.include(new LatLng(s.lat, s.lon));
        ui.postDelayed(() -> {
            if (map == null || mode != Mode.RESULTS) return;
            int sheetH = sheet.getHeight() > 0 ? sheet.getHeight() : k.dp(330);
            int[] pad = lay.landscape ? new int[]{lay.panelW + k.dp(56), lay.top + k.dp(56), k.dp(56), lay.bottom + k.dp(56)} : new int[]{k.dp(56), lay.top + k.dp(56), k.dp(56), sheetH + lay.bottom + k.dp(40)};
            try {
                if (framed == 1 && s.fixAt == 0) map.animateCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(near.get(0).lat, near.get(0).lon), 15.5), 800);
                else map.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), pad[0], pad[1], pad[2], pad[3]), 800);
            } catch (Exception e) { map.animateCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(near.get(0).lat, near.get(0).lon), 15), 800); }
        }, 250);
    }
    private void pins(org.maplibre.geojson.FeatureCollection fc) { GeoJsonSource src = src(MapStyle.RESULTS); if (src != null) src.setGeoJson(fc == null ? MapStyle.empty() : fc); }
    /** A result's pin tapped: go there (or add it as a stop). */
    private boolean tapResult(LatLng p) {
        if (map == null || mode != Mode.RESULTS) return false;
        android.graphics.PointF at = map.getProjection().toScreenLocation(p); float r = k.dp(22);
        Feature best = null; double bestD = Double.MAX_VALUE;
        for (Feature f : map.queryRenderedFeatures(new android.graphics.RectF(at.x - r, at.y - r * 1.6f, at.x + r, at.y + r), "results")) {
            if (!(f.geometry() instanceof org.maplibre.geojson.Point)) continue; org.maplibre.geojson.Point pt = (org.maplibre.geojson.Point)f.geometry();
            android.graphics.PointF sp = map.getProjection().toScreenLocation(new LatLng(pt.latitude(), pt.longitude()));
            double d = Math.hypot(sp.x - at.x, sp.y - k.dp(14) - at.y); if (d < bestD) { bestD = d; best = f; }
        }
        if (best == null) return false;
        org.maplibre.geojson.Point pt = (org.maplibre.geojson.Point)best.geometry();
        root.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        choose(pt.latitude(), pt.longitude(), best.getStringProperty("name"));
        return true;
    }

    // ================================================================ a stop for the trip planner
    /** Stops of the trip being planned, while its next stop is searched for here; null otherwise. */
    private List<TripStop> pickStops; private String pickTrip;
    private void addPickedStop(double lat, double lon, String name) {
        List<TripStop> stops = new ArrayList<>(pickStops);
        if (stops.size() >= TripStop.MAX) { notice("Use at most 20 stops"); return; }
        stops.add(new TripStop(name, lat, lon)); pickStops = null; search.hint("Search a place"); resultsShown = null;
        hideKeyboard(); go(Mode.EXPLORE); openPlanner(stops, true);
    }
    private void openPlanner(List<TripStop> stops, boolean added) {
        try { startActivity(new Intent(this, TripPlannerActivity.class).putExtra("stops", TripStop.json(stops).toString()).putExtra("title", pickTrip).putExtra("added", added)); }
        catch (JSONException e) { notice("Could not open the trip: " + e.getMessage()); }
    }

    // ================================================================ modes
    private void go(Mode next) {
        // Leaving the stop search without a stop: back to the trip, as it was.
        if (next == Mode.EXPLORE && pickStops != null) { List<TripStop> keep = pickStops; pickStops = null; search.hint("Search a place"); openPlanner(keep, false); }
        Mode prev = mode; mode = next;
        boolean searching = next == Mode.SEARCH, riding = next == Mode.RIDE;
        if (prev == Mode.RESULTS && next != Mode.RESULTS) pins(null);
        if (next == Mode.SEARCH && prev != Mode.ROUTE) fromResults = false;
        if (searching && (prev == Mode.LOT || prev == Mode.RESULTS)) { sheet.hide(); search.reveal(); }
        else if (searching) search.open();
        else if (prev == Mode.SEARCH) { hideKeyboard(); search.hide(); }
        if (next == Mode.SEARCH && (prev == Mode.LOT || prev == Mode.RESULTS)) { layoutInsets(); paintLocateFab(); paintMap(); syncBack(); return; }
        Gabi.show(top, next == Mode.EXPLORE || next == Mode.PIN, -k.dp(14));
        if (prev == Mode.SAVE && next != Mode.SAVE) hideKeyboard();
        ride.show(riding);
        picker.show(next == Mode.SELECT);
        switch (next) {
            case SELECT: sheet.swap(picker.sheet); break;
            case EXPLORE: paintRecents(); sheet.swap(explore); break;
            case PIN: sheet.swap(pinSheet); break;
            case ROUTE: markSaved(); paintRoute(); sheet.swap(routeSheet); painter.redrawRoute(); break;
            case SAVE: sheet.swap(saveSheet); break;
            case LOT: sheet.swap(lotSheet); break;
            case PARK_FORM: sheet.swap(parkForm); parkName.requestFocus(); ui.postDelayed(() -> { if (mode == Mode.PARK_FORM) showKeyboard(parkName); }, 120); break;
            case SEARCH: sheet.hide(); break;
            case RESULTS: pins(resultsShown); sheet.swap(resultsSheet); break;
            case RIDE: sheet.hide(); ride.enter(); painter.puckStale(); break;
        }
        // During a ride the ride service follows GPS; the map's own listener would only duplicate it.
        // During a ride the arrow follows the road; standing around, the dot's beam follows the phone's compass.
        if (riding) here.stop(); else if (started) here.start();
        ride.settle(riding);
        if (prev == Mode.RIDE && !riding && map != null) map.animateCamera(CameraUpdateFactory.newCameraPosition(new CameraPosition.Builder(map.getCameraPosition()).tilt(0).bearing(0).zoom(14.5).padding(0, 0, 0, 0).build()), 900);
        layoutInsets(); paintLocateFab(); paintMap(); syncBack();
    }

    // ================================================================ refresh
    /** Runs on every change to the ride or the map (each GPS fix among them), so what it does is kept cheap. */
    private void refresh() {
        if (isDestroyed() || root == null) return;
        long now = SystemClock.elapsedRealtime();
        if (now - themeCheckedAt > 60000) { themeCheckedAt = now; applyTheme(Gabi.resolve(this, s.lat, s.lon, s.fixAt > 0)); }
        installLayers(); regionChanged(); if (layersReady) search.index();
        if (s.noMap && mode != Mode.SELECT && !s.active) { picker.open(null); return; }
        if (mode == Mode.SELECT && s.ready && AreaStore.state() != null && AreaStore.state().finished && shownRegion == s.regionVersion) { AreaStore.State done = AreaStore.state(); go(Mode.EXPLORE); notice(done.name + " is ready to use offline"); }
        syncBack();
        suggestOutside();
        AreaStore.State dl = AreaStore.state();
        if (dl != null && dl.running) dotChip(regionChip, "Downloading · " + dl.percent() + "%", ACCENT);
        else if (s.noMap) dotChip(regionChip, "No map downloaded", MUTED);
        else if (!s.ready) dotChip(regionChip, "Getting the map ready…", MUTED);
        else dotChip(regionChip, s.regionName, OK);
        String model = savedModel();
        if (StreamService.mirroring) dotChip(bikeChip, "Your screen is on the bike", OK);
        else if (StreamService.navigationActive) dotChip(bikeChip, "Bike display live", OK);
        // No "Looking for…" state and no raw model code (e.g. 37501): the chip stays quiet until the display is live.
        else dotChip(bikeChip, model == null ? "Set up your bike" : "Bike not connected", MUTED);
        paintMirror();
        if (pendingDemo && s.ready) { pendingDemo = false; demo(); }
        if (offerUninstallOld && s.ready && mode == Mode.EXPLORE) {
            // Just moved from the old Daanvia: its copy of the maps is no longer needed. Android asks before uninstalling.
            offerUninstallOld = false; resumeOffered = true; getSharedPreferences("ui", 0).edit().remove("ride").apply();
            if (MoveActivity.oldVersion(this) >= 0) notice("Your maps and settings moved over. Uninstall the old Daanvia to free its space", "Uninstall",
                () -> { try { startActivity(new Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:" + MoveActivity.OLD_APP))); } catch (Exception ignored) {} }, 30000);
        }
        if (!resumeOffered && started && s.ready && !s.active && mode == Mode.EXPLORE) { resumeOffered = true; offerResume(); }
        exploreStatus.setText(!s.ready ? "Getting the map ready…" : s.status.startsWith("Offline region could not load") ? "The map couldn't load. Open Settings › Support." : "");
        exploreStatus.setVisibility(exploreStatus.length() == 0 ? View.GONE : View.VISIBLE);
        if (pendingPlan && s.ready && !s.routing && freshFix()) { pendingPlan = false; if(plannedTrip.isEmpty())s.plan(destLat, destLon, destName, false);else s.planTrip(plannedTrip,destName); }
        if (s.active && mode != Mode.RIDE) go(Mode.RIDE);
        else if (!s.active && mode == Mode.RIDE) {
            destName = null; demoTrip = false; go(Mode.EXPLORE);
            String done = s.finished; if (done != null) { s.finished = null; notice("Arrived at " + done + " · ride ended"); }
        }
        if (mode == Mode.ROUTE) paintRoute();
        ride.paint(mode == Mode.RIDE);
        painter.checkRoads();
        paintMap();
        followFirstFix();
    }
    /** The saved bike's model, parsed once per QR code rather than on every fix. */
    private String modelQr, model;
    private String savedModel() {
        String raw = savedQr();
        if (!raw.equals(modelQr)) { modelQr = raw; model = modelOf(raw); }
        return model;
    }
    private static String modelOf(String raw) {
        if (raw.isEmpty()) return null;
        try { String m = QrProfile.parse(raw).model; return m == null || m.isEmpty() ? "Saved bike" : m; } catch (Exception e) { return null; }
    }
    private void applyTheme(Gabi.Palette p) {
        if (!theme.set(p)) return;
        if (style != null) { MapStyle.paint(style, p, coastline); painter.repaintParking(); }
        paintRecents();
    }
    private void paintMap() {
        double[] pin = mode == Mode.PIN || mode == Mode.SAVE ? new double[]{dropLat, dropLon} : mode == Mode.LOT && lot != null ? new double[]{lot.lat, lot.lon} : null;
        painter.paint(mode == Mode.ROUTE, mode == Mode.RIDE, destName, destLat, destLon, pin);
    }

    /** Debug builds only: adb shell am broadcast -a net.drave.atrdiagnostics.DEBUG_MAP --es hide id,id --es show id --ez puck false (performance checks). */
    private BroadcastReceiver debugReceiver;
    private void debugLayers() {
        androidx.core.content.ContextCompat.registerReceiver(this, debugReceiver = new BroadcastReceiver() { public void onReceive(Context c, Intent i) {
            if (style == null) return;
            for (String key : new String[]{"hide", "show"}) { String ids = i.getStringExtra(key); if (ids == null) continue;
                for (String id : ids.split(",")) { Layer l = style.getLayer(id); if (l != null) l.setProperties(org.maplibre.android.style.layers.PropertyFactory.visibility(key.equals("hide") ? org.maplibre.android.style.layers.Property.NONE : org.maplibre.android.style.layers.Property.VISIBLE)); } }
            if (i.hasExtra("puck")) painter.showLocationPuck(i.getBooleanExtra("puck", true));
            // Encode the bike scene for a few seconds as if a bike pulled frames at the given rate, and log the result.
            if (i.hasExtra("encode")) { int want = i.getIntExtra("encode", 30), secs = i.getIntExtra("secs", 5); new Thread(() -> {
                VideoEncoder e = new VideoEncoder(m -> android.util.Log.d("DaanviaEncode", m)); e.setNavigation(s); e.pace(want, null);
                try { e.start(800, 480, null, 160); long t0 = SystemClock.uptimeMillis(), until = t0 + secs * 1000L; int got = 0; long bytes = 0;
                    while (SystemClock.uptimeMillis() < until) { byte[] f = e.next(); got++; bytes += f.length; Thread.sleep(Math.max(0, 1000 / want - 2)); }
                    android.util.Log.d("DaanviaEncode", "pulled " + got + " frames in " + secs + " s (" + (got / secs) + "/s), drawn " + e.drawn + ", rate now " + e.fps() + ", " + (bytes / Math.max(1, got)) + " bytes/frame");
                } catch (Exception x) { android.util.Log.e("DaanviaEncode", "encode test", x); } finally { e.close(); } }, "encode-test").start(); }
        } }, new IntentFilter("net.drave.atrdiagnostics.DEBUG_MAP"), androidx.core.content.ContextCompat.RECEIVER_EXPORTED);
    }

    public void onUserInteraction() { super.onUserInteraction(); ride.onUserInteraction(mode == Mode.RIDE); }

    // ================================================================ GPS & screens
    private void locate() {
        if (s.active && s.simulation) { notice("End simulation before starting GPS"); return; }
        if (!Here.allowed(this)) { requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 71); return; }
        if (!here.enabled()) {
            // Google's one-tap dialog turns Location on right here; without Play services the rider goes to quick settings.
            if (LocationFeed.google(this)) askAccuracy(true); else { centerOnFix = false; notice("Turn on Location in your phone’s quick settings so Daanvia can find you"); }
            return;
        }
        here.start();
    }
    /**
     * Google's "turn on location accuracy" dialog, shown only when Location or Wi-Fi/Bluetooth scanning is off. Without
     * them the phone falls back to GPS alone, which is slow to find the rider and poor indoors. Asked when the rider taps
     * locate, and on its own at most once a week.
     */
    private void askAccuracy(boolean tapped) {
        android.content.SharedPreferences u = getSharedPreferences("ui", 0);
        if (!Here.allowed(this) || !tapped && System.currentTimeMillis() - u.getLong("asked_accuracy", 0) < 7 * 86400_000L) return;
        u.edit().putLong("asked_accuracy", System.currentTimeMillis()).apply();
        LocationFeed.askAccuracy(this, 74);
    }
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == 74 && result == RESULT_OK) { here.stop(); here.start(); centerOnFix = true; }
        if (code == 75) { if (result == RESULT_OK && data != null) { link.mirror(data); notice("Putting your screen on the bike display…"); } else notice("Screen sharing cancelled"); }
    }
    /**
     * When a newer map is published, offer to update the downloaded areas, so search, roads and parking stay current
     * without a trip to Settings. Checked at most every 6 hours, never during a ride or a download.
     */
    private void checkMapUpdates() {
        android.content.SharedPreferences p = getSharedPreferences("areas", 0);
        if (System.currentTimeMillis() - p.getLong("update_checked", 0) < 6 * 3600_000L) return;
        AreaStore.State st = AreaStore.state(); if (st != null && st.running) return;
        net.execute(() -> {
            AreaStore.Catalog cat; try { cat = AreaStore.fetch(this); } catch (Exception e) { return; }
            p.edit().putLong("update_checked", System.currentTimeMillis()).apply();
            AreaStore.Area old = null; for (AreaStore.Area a : AreaStore.installed(this)) if (a.outdated(cat)) { old = a; break; }
            AreaStore.Area area = old; if (area == null) return;
            ui.post(() -> {
                if (isDestroyed() || s.active) return;
                notice("New map data for " + area.name, "Update", () -> {
                    AreaStore.State now = AreaStore.state();
                    if (s.active || now != null && now.running) { notice("Finish the ride or download first"); return; }
                    if (AreaStore.update(this, cat, area)) notice("Updating " + area.name + "…"); else notice("This area changed. Remove it in Settings › Maps and download it again.");
                }, 15000);
            });
        });
    }
    /** Ask for location the first time the app opens, so the map can show where the rider is. */
    private boolean askLocationOnce() {
        android.content.SharedPreferences prefs = getSharedPreferences("ui", 0);
        if (Here.allowed(this) || prefs.getBoolean("asked_location", false)) return false;
        prefs.edit().putBoolean("asked_location", true).apply();
        requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 71);
        return true;
    }
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        super.onRequestPermissionsResult(code, p, r);
        if (code == 71 && Here.allowed(this)) {
            centerOnFix = true; locate(); autoConnect(); lookAgain();
            if (!Here.precise(this)) notice("Using your approximate location. Allow precise location for turn-by-turn directions.");
        } else if (code == 71) { pendingPlan = false; centerOnFix = false; notice("Allow location so Daanvia can show where you are."); refresh(); autoConnect(); lookAgain(); }
        if (code == 72) { if (r.length > 0 && r[0] != PackageManager.PERMISSION_GRANTED) notificationsNotice(); else if (s.active && !s.simulation) KeepAlive.askForRide(this); }
        if (code == 73 && Here.precise(this)) { here.stop(); here.start(); notice("Precise location on. Tap Start ride when the dot settles."); }
        else if (code == 73) notice("Directions need precise location. Turn it on in Android’s app settings for Daanvia.");
        if (code == DashboardLink.PERMISSIONS) link.onPermissionsResult();
        if (code == DashboardLink.BLUETOOTH_PERMISSIONS) {
            if (BleClock.allowed(this)) bluetoothForClock();
            else if (!getSharedPreferences("ui", 0).getBoolean("bt_clock_noticed", false)) { getSharedPreferences("ui", 0).edit().putBoolean("bt_clock_noticed", true).apply(); notice("Without Nearby devices, Daanvia can't set the bike's clock. You can allow it in Android's app settings."); }
        }
    }
    private void notificationsNotice() {
        notice("Allow notifications so Reconnect and Exit show on your lock screen during rides", "Settings",
            () -> { try { startActivity(new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName())); } catch (Exception ignored) {} }, 12000);
    }
    private void openSettings() { openSettings(null); }
    /** {@code show}: a part of Settings to open at, e.g. "coloros" for the OPPO / OnePlus / realme steps. */
    private void openSettings(String show) {
        startActivity(new Intent(this, SettingsActivity.class).putExtra("show", show));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }
    private String savedQr() { return getSharedPreferences("saved-bike", 0).getString("qr", ""); }

    // ================================================================ shared places
    private void handleIntent(Intent i) {
        if (i == null) return;
        if (i.hasExtra("pick_stop")) { String raw = i.getStringExtra("pick_stop"); pickTrip = i.getStringExtra("trip_name"); i.removeExtra("pick_stop");
            try { pickStops = new ArrayList<>(TripStop.parse(raw)); } catch (Exception e) { pickStops = new ArrayList<>(); }
            search.hint("Search a stop to add"); search.clear(); resultsShown = null; go(Mode.SEARCH); return; }
        if(i.hasExtra("trip")){String raw=i.getStringExtra("trip"), name=i.getStringExtra("trip_name");i.removeExtra("trip");try{openTrip(TripStop.parse(raw),name==null?"My ride":name);}catch(Exception e){notice("Could not open trip: "+e.getMessage());}return;}
        if (i.getBooleanExtra("demo", false)) { i.removeExtra("demo"); demo(); return; }
        if (i.getBooleanExtra("select_area", false)) { i.removeExtra("select_area"); if (map != null) picker.open(null); else ui.postDelayed(() -> picker.open(null), 600); return; }
        String text = null;
        if (Intent.ACTION_SEND.equals(i.getAction())) {
            CharSequence t = i.getCharSequenceExtra(Intent.EXTRA_TEXT); text = t == null ? null : t.toString();
            String subject = i.getStringExtra(Intent.EXTRA_SUBJECT);
            if (text != null && subject != null && !text.contains(subject)) text = subject + "\n" + text;
        } else if (Intent.ACTION_VIEW.equals(i.getAction()) && i.getData() != null) text = i.getData().toString();
        if (text == null) return;
        setIntent(new Intent(this, HomeActivity.class));
        boolean ride = search.fromGoogle() && !s.active; search.forgetGoogle();
        openShared(text, ride);
    }

    // ================================================================ bike display
    private final Runnable retryConnect = () -> { autoConnect(); lookAgain(); };
    /** Keep trying to reach the saved dashboard while the app is open and the display is not live. */
    private void autoConnect() {
        if (!started || link == null || !link.profileReady() || StreamService.navigationActive || link.searching()) return;
        if (!link.hasPermissions()) { if (askedLinkPermissions) return; askedLinkPermissions = true; link.askPermissions(); refresh(); return; }
        if (!link.wifiOn()) { if (!wifiNoticed) { wifiNoticed = true; notice("Turn on Wi-Fi so Daanvia can connect to your bike"); } return; }
        wifiNoticed = false; link.autoNavigation(); refresh();
    }
    private boolean wifiNoticed;
    /*
     * While Daanvia is open and the bike display isn't live, it keeps looking: a new search 5 s after the last one ended,
     * then 10 s, 20 s, 30 s, 1 min, and every 2 min after that. A timer of its own, so it goes on whatever the last search
     * reported (it used to wait for a status change, and stopped after the first miss when none came).
     */
    private static final long[] LOOK_AFTER = {5_000, 10_000, 20_000, 30_000, 60_000};
    private static final long LOOK_SLOW = 120_000;
    private int lookStep; private long stoppedAt;
    private void lookAgain() {
        ui.removeCallbacks(retryConnect);
        if (!started || link == null) return;
        long wait;
        if (StreamService.navigationActive) { lookStep = 0; wait = 30_000; }   // live: check now and then that it still is
        else if (link.searching()) wait = 5_000;                               // a search is running: wait for it to end
        else wait = lookStep < LOOK_AFTER.length ? LOOK_AFTER[lookStep++] : LOOK_SLOW;
        ui.postDelayed(retryConnect, wait);
    }
    public void onStatus(String status) { refresh(); }
    public void onLog() {}
    public void onChanged() { refresh(); if (StreamService.navigationActive) lookStep = 0; }
    public void onPermissionsMissing() { notice("Allow Nearby devices and precise location so Daanvia can find your bike"); }
    public void onNavigationStarted() { link.settle(); notice("Connected. Daanvia is on the bike display."); refresh(); if (mode == Mode.RIDE) ride.armBikeMode(); }
}
