package net.drave.atrdiagnostics;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.nio.charset.StandardCharsets;

import static net.drave.atrdiagnostics.Gabi.Role.*;

/** Settings: the bike connection, TFT fit, map theme, testing tools and app information. */
public final class SettingsActivity extends Activity implements DashboardLink.Host {
    private NavSession s; private Gabi.Themer theme; private Gabi.Kit k; private DashboardLink link;
    private TftPreview preview; private TextView modelText, statusText, primary, liveChip, fitNote, licenseText; private LinearLayout permissionBanner, fitControls, scanRow; private View progress; private ValueAnimator progressAnim;
    private LinearLayout updateBox; private final AppUpdate.Listener updateListener = st -> paintUpdate();
    private TextView storageText;
    private LinearLayout mapList; private AreaStore.Catalog catalog; private String confirmRemove;
    // Download progress arrives every 256 KB; the list (which measures each area on disk) is redrawn at most twice a second.
    private long mapsPaintedAt; private final java.util.HashMap<String, Long> sizes = new java.util.HashMap<>();
    private final AreaStore.Listener areaListener = st -> { long now = android.os.SystemClock.elapsedRealtime(); if (st.running && now - mapsPaintedAt < 500) return; mapsPaintedAt = now; if (!st.running) sizes.clear(); paintMaps(); };
    private TextView adjust; private boolean fitting; private float[] old; private String oldLayout;
    private final BroadcastReceiver changes = new BroadcastReceiver() { public void onReceive(Context c, Intent i) { if (preview != null) preview.invalidate(); paint(); } };

    public void onCreate(Bundle state) {
        super.onCreate(state);
        s = NavSession.get(this); theme = new Gabi.Themer(Gabi.resolve(this, s.lat, s.lon, s.fixAt > 0)); k = new Gabi.Kit(this, theme);
        GabiPage page = new GabiPage(this, k, "Settings");
        LinearLayout c = page.content;

        // ---- connection
        LinearLayout bike = GabiPage.card(k);
        bike.addView(k.label("Bike"));
        LinearLayout head = k.row(12);
        FrameLayout bub = new FrameLayout(this); k.fill(bub, 14, ACCENT_SOFT); bub.addView(k.icon(IconDrawable.BIKE, 22, ACCENT), new FrameLayout.LayoutParams(k.dp(22), k.dp(22), Gravity.CENTER));
        head.addView(bub, new LinearLayout.LayoutParams(k.dp(46), k.dp(46)));
        LinearLayout names = k.column(3); modelText = k.single(k.text("", 17, 750, INK)); statusText = k.text("", 13, 500, MUTED); names.addView(modelText); names.addView(statusText);
        head.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
        liveChip = k.chip("Live", OK_SOFT, OK); head.addView(liveChip);
        bike.addView(head);
        FrameLayout track = new FrameLayout(this); k.fill(track, 2, CHIP); progress = new View(this); k.fill(progress, 2, ACCENT); track.addView(progress, new FrameLayout.LayoutParams(k.dp(80), -1));
        bike.addView(track, new LinearLayout.LayoutParams(-1, k.dp(3)));
        progressAnim = ValueAnimator.ofFloat(-0.3f, 1.1f); progressAnim.setDuration(1200); progressAnim.setRepeatCount(ValueAnimator.INFINITE); progressAnim.setInterpolator(Gabi.EASE_IN_OUT);
        progressAnim.addUpdateListener(a -> progress.setTranslationX((float)a.getAnimatedValue() * track.getWidth())); progressAnim.start();
        primary = k.primary("", this::onPrimary); bike.addView(primary);
        scanRow = k.row(8);
        scanRow.addView(k.ghost("Scan QR", this::scanCamera), new LinearLayout.LayoutParams(0, -2, 1));
        scanRow.addView(k.ghost("QR photo", this::scanPhoto), new LinearLayout.LayoutParams(0, -2, 1));
        bike.addView(scanRow);
        bike.addView(k.text("Park first. Close Carbit Ride and keep the dashboard’s Android QR screen open. Wi-Fi on, hotspot off.", 12.5f, 500, MUTED));
        bike.addView(k.text("Connect automatically when Daanvia opens", 14, 650, INK));
        boolean auto = getSharedPreferences("ui", 0).getBoolean("autoconnect", true);
        bike.addView(k.segmented(new String[]{"On", "Off"}, auto ? 0 : 1, i -> getSharedPreferences("ui", 0).edit().putBoolean("autoconnect", i == 0).apply()));
        bike.addView(k.text("While the app is open, Daanvia keeps looking for the saved dashboard and connects as soon as its QR screen is waiting.", 12.5f, 500, MUTED));
        bike.addView(k.text("Rest the phone while riding", 14, 650, INK));
        boolean rest = getSharedPreferences("ui", 0).getBoolean("bikemode", true);
        bike.addView(k.segmented(new String[]{"On", "Off"}, rest ? 0 : 1, i -> getSharedPreferences("ui", 0).edit().putBoolean("bikemode", i == 0).apply()));
        bike.addView(k.text("When the bike display is showing directions and you haven’t touched the phone for 15 seconds, the phone switches to a dark screen with just the next turn and may turn off, to save battery. Tap it to bring the map back. The moon button on the ride screen does it right away.", 12.5f, 500, MUTED));
        permissionBanner = k.column(10); permissionBanner.setPadding(k.dp(14), k.dp(12), k.dp(14), k.dp(12)); k.fill(permissionBanner, 16, DANGER_SOFT); permissionBanner.setVisibility(View.GONE);
        permissionBanner.addView(k.text("Android needs Nearby devices and precise location for the Wi-Fi connection. Daanvia does not collect GPS positions for this.", 13, 550, INK));
        permissionBanner.addView(k.ghost("Open app settings", () -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())))));
        bike.addView(permissionBanner);
        c.addView(bike);

        // ---- TFT preview and fit
        LinearLayout tft = GabiPage.card(k);
        LinearLayout tftHead = k.row(4); tftHead.addView(k.label("Bike display"), new LinearLayout.LayoutParams(0, -2, 1));
        tftHead.addView(k.info("This is what Daanvia sends to your bike’s screen. It updates live, but it’s drawn here on the phone, so colors may look a little different on the bike."));
        tft.addView(tftHead);
        preview = new TftPreview(this, s); tft.addView(preview, new LinearLayout.LayoutParams(-1, -2));
        TextView layoutBtn = k.primary("Edit display layout", () -> { if (s.active) { android.widget.Toast.makeText(this, "End the ride first, then edit the layout while parked.", android.widget.Toast.LENGTH_SHORT).show(); return; } startActivity(new Intent(this, TftLayoutActivity.class)); });
        tft.addView(layoutBtn);
        adjust = k.ghost("Fit my TFT", this::beginFit); tft.addView(adjust);
        fitNote = k.text("End the ride and adjust fit while parked.", 12.5f, 600, ACCENT); fitNote.setVisibility(View.GONE); tft.addView(fitNote);
        fitControls = k.column(6); fitControls.setVisibility(View.GONE); tft.addView(fitControls);
        c.addView(tft);

        // ---- theme
        LinearLayout look = GabiPage.card(k);
        look.addView(k.label("Map theme"));
        String[] keys = {Gabi.THEME_AUTO, Gabi.THEME_DAY, Gabi.THEME_NIGHT}; String cur = Gabi.themeSetting(this); int sel = 0; for (int i = 0; i < 3; i++) if (keys[i].equals(cur)) sel = i;
        look.addView(k.segmented(new String[]{"Auto", "Day", "Night"}, sel, i -> { Gabi.setThemeSetting(this, keys[i]); theme.set(Gabi.resolve(this, s.lat, s.lon, s.fixAt > 0)); }));
        look.addView(k.text("Auto switches to the night map after sunset where you are riding, and back at sunrise.", 12.5f, 500, MUTED));
        c.addView(look);

        // ---- GPS
        LinearLayout gps = GabiPage.card(k);
        LinearLayout gpsHead = k.row(4); gpsHead.addView(k.label("GPS"), new LinearLayout.LayoutParams(0, -2, 1));
        gpsHead.addView(k.info("Recalibrate clears the satellite data your phone saved and downloads fresh data, so GPS locks on again from scratch. Use it when your location is stuck, far off, or slow to find you. GPS check shows how accurate it is right now."));
        gps.addView(gpsHead);
        LinearLayout gpsRow = k.row(8);
        gpsRow.addView(k.ghost("Recalibrate GPS", this::recalibrateGps), new LinearLayout.LayoutParams(0, -2, 1));
        gpsRow.addView(k.ghost("GPS check", () -> startActivity(new Intent(this, SupportActivity.class))), new LinearLayout.LayoutParams(0, -2, 1));
        gps.addView(gpsRow);
        c.addView(gps);

        // ---- downloaded maps
        LinearLayout maps = GabiPage.card(k);
        LinearLayout mapsHead = k.row(4); mapsHead.addView(k.label("Maps"), new LinearLayout.LayoutParams(0, -2, 1));
        mapsHead.addView(k.info("Downloaded areas live on your phone, so the map, search and directions work without signal. Download the places you ride. Remove ones you no longer need to free up space."));
        maps.addView(mapsHead);
        mapList = k.column(0); maps.addView(mapList);
        storageText = k.text("", 12.5f, 500, MUTED); maps.addView(storageText);
        maps.addView(k.ghost("Free up space", this::freeUp));
        maps.addView(k.ghost("Download another area", () -> { startActivity(new Intent(this, HomeActivity.class).putExtra("select_area", true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)); finish(); }));
        c.addView(maps);

        // ---- testing
        LinearLayout test = GabiPage.card(k); test.setPadding(k.dp(16), k.dp(12), k.dp(16), k.dp(6));
        test.addView(k.label("Testing"));
        test.addView(k.listRow(IconDrawable.PLAY, "Demo ride", "Simulates a sample route on the phone and the bike display, to check the setup while parked", null,
            () -> { startActivity(new Intent(this, HomeActivity.class).putExtra("demo", true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)); finish(); }));
        test.addView(k.listRow(IconDrawable.TOOLS, "Support and test tools", "Connection test, test pattern, mirror test, logs", null, () -> startActivity(new Intent(this, SupportActivity.class))));
        c.addView(test);

        // ---- about
        LinearLayout more = GabiPage.card(k); more.setPadding(k.dp(16), k.dp(12), k.dp(16), k.dp(6));
        more.addView(k.label("About"));
        updateBox = k.column(0); more.addView(updateBox);
        more.addView(k.listRow(IconDrawable.SHARE, "Share navigation report", "Locations, destinations and pairing details are left out", null,
            () -> startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, s.report()), "Share navigation report"))));
        more.addView(k.listRow(IconDrawable.CHECK, "Licenses and map data", "© OpenStreetMap contributors · ODbL", null, this::toggleLicenses));
        licenseText = k.text("", 11, 500, MUTED); licenseText.setTypeface(android.graphics.Typeface.MONOSPACE); licenseText.setVisibility(View.GONE); licenseText.setTextIsSelectable(true);
        more.addView(licenseText);
        c.addView(more);

        link = DashboardLink.attach(this, this);
        if (getIntent().getBooleanExtra("navigation_connect", false) && link.profileReady()) link.sendNavigation();
        paint();
    }

    protected void onStart() {
        super.onStart(); AppUpdate.listen(updateListener); paintUpdate(); AreaStore.listen(areaListener); catalog = AreaStore.cached(this); paintMaps();
        new Thread(() -> { try { AreaStore.Catalog fresh = AreaStore.fetch(this); runOnUiThread(() -> { catalog = fresh; paintMaps(); }); } catch (Exception ignored) {} }).start(); androidx.core.content.ContextCompat.registerReceiver(this, changes, new IntentFilter(NavSession.EVENT), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED); paint(); }
    protected void onStop() { AppUpdate.unlisten(updateListener); AreaStore.unlisten(areaListener); try { unregisterReceiver(changes); } catch (Exception ignored) {} super.onStop(); }
    protected void onDestroy() { if (fitting) cancelFit(); progressAnim.cancel(); link.detach(this); super.onDestroy(); }
    public void finish() { super.finish(); overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out); }

    private void paint() {
        if (link == null) return;
        boolean ready = link.profileReady(), live = StreamService.navigationActive, busy = link.searching();
        modelText.setText(ready ? (link.model() == null || link.model().isEmpty() ? "Saved bike" : link.model()) : "No bike saved yet");
        statusText.setText(live ? "Daanvia is on the dashboard" : link.status());
        liveChip.setVisibility(live ? View.VISIBLE : View.GONE);
        ((View)progress.getParent()).setVisibility(busy ? View.VISIBLE : View.GONE);
        primary.setText(busy ? "Stop searching" : live ? "Back to the map" : ready ? "Send Daanvia to dashboard" : "Scan dashboard QR");
        scanRow.setVisibility(ready && !busy ? View.VISIBLE : View.GONE);
        adjust.setVisibility(fitting ? View.GONE : View.VISIBLE);
        fitNote.setVisibility(!fitting && s.active ? View.VISIBLE : View.GONE);
        adjust.setAlpha(s.active ? 0.45f : 1f);
    }
    private void onPrimary() {
        if (link.searching()) { link.stop(); return; }
        if (StreamService.navigationActive) { finish(); return; }
        if (link.profileReady()) link.sendNavigation(); else scanCamera();
        paint();
    }

    // ---- fit
    private void beginFit() {
        if (s.active) { Gabi.shake(adjust); return; }
        fitting = true; s.calibration = true; old = new float[]{s.insetLeft, s.insetTop, s.insetRight, s.insetBottom, s.textScale}; oldLayout = s.layout;
        fitControls.removeAllViews();
        fitControls.addView(k.text("The grid shows on the bike. Move each edge until all four corners are visible and the circle is round.", 12.5f, 500, MUTED));
        String[] layouts = {"Map focus", "Large turns"};
        fitControls.addView(k.segmented(new String[]{"Map focus", "Large turns"}, "Large turns".equals(s.layout) ? 1 : 0, i -> { s.layout = layouts[i]; s.signal(); }));
        String[] names = {"Left edge", "Top edge", "Right edge", "Bike footer", "Text size"};
        for (int i = 0; i < 5; i++) {
            final int n = i;
            LinearLayout r = k.row(8);
            TextView name = k.text(names[i], 13, 600, INK_SOFT); r.addView(name, new LinearLayout.LayoutParams(k.dp(92), -2));
            SeekBar bar = k.slider(i == 4 ? 40 : 30, i == 4 ? Math.round((s.textScale - 1) * 100) : Math.round(old[i] * 100));
            r.addView(bar, new LinearLayout.LayoutParams(0, -2, 1));
            TextView val = k.num(value(i, bar.getProgress()), 12.5f, 650, MUTED); val.setGravity(Gravity.END); r.addView(val, new LinearLayout.LayoutParams(k.dp(44), -2));
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onStartTrackingTouch(SeekBar b) {} public void onStopTrackingTouch(SeekBar b) {}
                public void onProgressChanged(SeekBar b, int v, boolean user) {
                    if (n == 0) s.insetLeft = v / 100f; else if (n == 1) s.insetTop = v / 100f; else if (n == 2) s.insetRight = v / 100f; else if (n == 3) s.insetBottom = v / 100f; else s.textScale = 1 + v / 100f;
                    val.setText(value(n, v)); s.signal();
                }
            });
            fitControls.addView(r);
        }
        LinearLayout btns = k.row(8);
        btns.addView(k.ghost("Reset", () -> { s.insetLeft = s.insetTop = s.insetRight = s.insetBottom = 0; s.textScale = 1; s.saveFit(); endFit(); }), new LinearLayout.LayoutParams(0, -2, 1));
        btns.addView(k.ghost("Cancel", this::cancelFit), new LinearLayout.LayoutParams(0, -2, 1));
        String model = link.model() == null || link.model().isEmpty() ? "this bike" : link.model();
        btns.addView(k.primary("Save for " + model, () -> { s.saveFit(); endFit(); }), new LinearLayout.LayoutParams(0, -2, 1.4f));
        fitControls.addView(btns);
        Gabi.show(fitControls, true, k.dp(12)); s.signal(); paint();
    }
    private static String value(int n, int v) { return n == 4 ? (100 + v) + "%" : v + "%"; }
    private void cancelFit() { s.insetLeft = old[0]; s.insetTop = old[1]; s.insetRight = old[2]; s.insetBottom = old[3]; s.textScale = old[4]; s.layout = oldLayout; endFit(); }
    private void endFit() { fitting = false; s.calibration = false; fitControls.setVisibility(View.GONE); s.signal(); paint(); }

    /** App version, with check, download progress and install. */
    private void paintUpdate() {
        if (updateBox == null) return;
        updateBox.removeAllViews();
        AppUpdate.State st = AppUpdate.state();
        String sub; String action = null; Runnable run = null;
        if (st == null) { sub = "Tap to check for updates"; action = "Check"; run = () -> AppUpdate.check(this, false); }
        else if (st.checking) sub = "Checking for updates…";
        else if (st.downloading) sub = "Downloading " + st.release.version + " · " + st.percent() + "%";
        else if (st.error != null) { sub = st.error; action = st.available() ? "Update" : "Check"; run = st.available() ? () -> AppUpdate.install(this) : () -> AppUpdate.check(this, false); }
        else if (st.available()) { sub = "Version " + st.release.version + " is available · " + st.release.sizeLabel(); action = st.ready ? "Install" : "Update"; run = () -> AppUpdate.install(this); }
        else { sub = "You have the latest version"; action = "Check"; run = () -> AppUpdate.check(this, false); }
        LinearLayout r = k.listRow(IconDrawable.DOWNLOAD, "Daanvia " + BuildInfo.VERSION, sub, null, null);
        if (action != null) { TextView a = k.text(action, 13.5f, 700, ACCENT); a.setPadding(k.dp(12), k.dp(8), 0, k.dp(8)); Runnable go = run; Gabi.press(a, () -> { if (s.active) { Gabi.shake(a); return; } go.run(); }); r.addView(a); }
        updateBox.addView(r);
    }
    /** Downloaded areas with size, km and update/remove, plus the download in progress. */
    private void paintMaps() {
        if (mapList == null) return;
        mapList.removeAllViews();
        AreaStore.State st = AreaStore.state();
        if (st != null && st.running) {
            LinearLayout r = k.listRow(IconDrawable.DOWNLOAD, "Downloading " + st.name, st.progressLabel(), null, null);
            TextView cancel = k.text("Cancel", 13.5f, 700, ACCENT); cancel.setPadding(k.dp(12), k.dp(8), 0, k.dp(8)); Gabi.press(cancel, AreaStore::cancel); r.addView(cancel);
            mapList.addView(r);
        }
        List<AreaStore.Area> list = AreaStore.installed(this);
        for (AreaStore.Area a : list) {
            boolean update = a.outdated(catalog), confirming = a.id.equals(confirmRemove);
            Long bytes = sizes.get(a.dir.getPath()); if (bytes == null) { bytes = a.diskBytes(); sizes.put(a.dir.getPath(), bytes); }
            String sub = a.kmLabel() + " · " + Math.max(1, Math.round(bytes / 1048576.0)) + " MB" + (update ? " · update available" : "");
            LinearLayout r = k.listRow(IconDrawable.MAP, a.name, sub, null, null);
            if (update) { TextView u = k.text("Update", 13.5f, 700, ACCENT); u.setPadding(k.dp(12), k.dp(8), k.dp(6), k.dp(8)); Gabi.press(u, () -> updateArea(a)); r.addView(u); }
            TextView del = k.text(confirming ? "Remove?" : "Remove", 13.5f, 700, confirming ? DANGER : MUTED); del.setPadding(k.dp(12), k.dp(8), 0, k.dp(8));
            Gabi.press(del, () -> {
                if (s.active) { Gabi.shake(del); return; }
                if (!confirming) { confirmRemove = a.id; paintMaps(); return; }
                confirmRemove = null; AreaStore.remove(this, a); sizes.clear(); paintMaps();
            });
            r.addView(del); mapList.addView(r);
        }
        if (st == null || !st.running) paintStorage();
        if (list.isEmpty() && (st == null || !st.running)) mapList.addView(k.listRow(IconDrawable.MAP, "No areas downloaded yet", "Download the area you ride in", null, null));
    }
    private void paintStorage() {
        new Thread(() -> { long[] u = Housekeeping.usage(this); runOnUiThread(() -> storageText.setText("Daanvia uses " + mb(u[0] + u[1] + u[2]) + " on this phone: " + mb(u[0]) + " of downloaded maps"
            + (u[2] >= 1048576 ? ", " + mb(u[2]) + " saved from online maps and searches" : "") + (u[1] >= 1048576 ? ", " + mb(u[1]) + " for everything else" : "") + ".")); }).start();
    }
    private static String mb(long b) { return Math.max(0, Math.round(b / 1048576.0)) + " MB"; }
    private void recalibrateGps() {
        if (s.active) { android.widget.Toast.makeText(this, "End the ride first. Recalibrating restarts GPS from scratch.", android.widget.Toast.LENGTH_LONG).show(); return; }
        if (!Here.allowed(this)) { android.widget.Toast.makeText(this, "Allow location for Daanvia first.", android.widget.Toast.LENGTH_LONG).show(); return; }
        boolean ok = Here.recalibrate(this); s.fixAt = 0; s.accuracy = 999; s.signal();
        android.widget.Toast.makeText(this, ok ? "GPS reset. Go outside; it can take a minute to lock." : "Fresh GPS data requested. Go outside to lock.", android.widget.Toast.LENGTH_LONG).show();
    }
    private void freeUp() {
        AreaStore.State st = AreaStore.state();
        if (s.active || (st != null && st.running)) { storageText.setText("Finish the ride or download first, then try again."); return; }
        storageText.setText("Cleaning up…");
        new Thread(() -> {
            long freed = Housekeeping.run(this, true) + Housekeeping.savedOnline(this) - new java.io.File(getFilesDir(), "places-cache.json").length();
            runOnUiThread(() -> {
                // The saved online map is rebuilt as the rider looks around with signal; downloaded areas stay.
                org.maplibre.android.MapLibre.getInstance(this);
                org.maplibre.android.offline.OfflineManager.getInstance(this).clearAmbientCache(new org.maplibre.android.offline.OfflineManager.FileSourceCallback() {
                    public void onSuccess() { done(); } public void onError(String message) { done(); }
                    private void done() { paintStorage(); android.widget.Toast.makeText(SettingsActivity.this, freed < 1048576 ? "Already tidy" : "Freed " + mb(freed), android.widget.Toast.LENGTH_SHORT).show(); }
                });
            });
        }).start();
    }
    private void updateArea(AreaStore.Area a) {
        // The update replaces the files the ride is navigating with.
        if (s.active) { android.widget.Toast.makeText(this, "End the ride first, then update the map.", android.widget.Toast.LENGTH_LONG).show(); return; }
        ArrayList<AreaStore.Cell> cells = new ArrayList<>(); boolean all = true;
        for (String id : a.cells) { boolean found = false; for (AreaStore.Cell c : catalog.cells) if (c.id.equals(id)) { cells.add(c); found = true; } all &= found; }
        // A busy square can be split into smaller ones in a newer map: take every square that now lies inside the area's old ones.
        if (!all) {
            cells.clear();
            for (AreaStore.Cell c : catalog.cells) { double lon = (c.box[0] + c.box[2]) / 2, lat = (c.box[1] + c.box[3]) / 2;
                for (double[] b : a.boxes) if (lon >= b[0] && lon <= b[2] && lat >= b[1] && lat <= b[3]) { cells.add(c); break; } }
        }
        if (cells.isEmpty()) { android.widget.Toast.makeText(this, "This area's map has changed. Remove it and download it again.", android.widget.Toast.LENGTH_LONG).show(); return; }
        AreaStore.download(this, catalog, cells, a.name, a.home, a);
    }
    private void toggleLicenses() {
        if (licenseText.getVisibility() == View.VISIBLE) { licenseText.setVisibility(View.GONE); return; }
        StringBuilder b = new StringBuilder("Map data © OpenStreetMap contributors, ODbL 1.0. https://www.openstreetmap.org/copyright\n\n");
        try { for (String f : getAssets().list("licenses")) { b.append("── ").append(f).append(" ──\n"); try (InputStream in = getAssets().open("licenses/" + f)) { ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n; while ((n = in.read(buf)) > 0) o.write(buf, 0, n); b.append(o.toString(StandardCharsets.UTF_8.name())).append("\n\n"); } } }
        catch (IOException ignored) {}
        licenseText.setText(b); Gabi.show(licenseText, true, k.dp(8));
    }

    // ---- QR
    private void scanCamera() {
        link.resetForScan();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.CAMERA}, 20);
        else startActivityForResult(new Intent(this, ScanActivity.class), 30);
    }
    private void scanPhoto() {
        link.resetForScan();
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), 31);
    }
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null) return;
        if (request == 30) link.acceptQr(data.getStringExtra("qr"));
        if (request == 31 && data.getData() != null) link.decodePhoto(data.getData());
    }
    public void onRequestPermissionsResult(int r, String[] p, int[] results) {
        super.onRequestPermissionsResult(r, p, results);
        if (r == 20) { if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) scanCamera(); else link.note("Camera access denied. You can choose a QR photo instead."); }
        if (r == DashboardLink.PERMISSIONS) { permissionBanner.setVisibility(View.GONE); link.onPermissionsResult(); }
    }

    // ---- DashboardLink.Host
    public void onStatus(String status) { paint(); }
    public void onLog() {}
    public void onChanged() { paint(); }
    public void onPermissionsMissing() { Gabi.show(permissionBanner, true, k.dp(8)); }
    public void onNavigationStarted() { startActivity(new Intent(this, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)); }
}
