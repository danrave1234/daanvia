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
    public void askPermissions(String[] permissions, int requestCode) { requestPermissions(permissions, requestCode); }
    private NavSession s; private Gabi.Themer theme; private Gabi.Kit k; private DashboardLink link;
    /** The update row and the map list are rebuilt often (twice a second during a download): their rows get themes of their own, cleared each time. */
    private Gabi.Themer updateTheme, mapsTheme; private Gabi.Kit uk, mk;
    private TftPreview preview; private TextView modelText, statusText, primary, liveChip, fitNote, licenseText; private LinearLayout permissionBanner, fitControls, scanRow; private View progress; private ValueAnimator progressAnim;
    private LinearLayout updateBox; private final AppUpdate.Listener updateListener = st -> paintUpdate();
    private TextView storageText;
    private LinearLayout mapList; private AreaStore.Catalog catalog; private String confirmRemove;
    // Download progress arrives every 256 KB; the list (which measures each area on disk) is redrawn at most twice a second.
    private long mapsPaintedAt; private final java.util.HashMap<String, Long> sizes = new java.util.HashMap<>();
    private final AreaStore.Listener areaListener = st -> { long now = android.os.SystemClock.elapsedRealtime(); if (st.running && now - mapsPaintedAt < 500) return; mapsPaintedAt = now; if (!st.running) { sizes.clear(); loadAreas(); } paintMaps(); };
    /**
     * The installed areas, read (each one's region.json) off the main thread when the screen opens and after a download
     * ends or an area is removed; progress repaints reuse it.
     */
    private List<AreaStore.Area> areas = new ArrayList<>(); private boolean areasLoaded; private int areasAsked;
    private TextView adjust; private boolean fitting; private float[] old; private String oldLayout;
    private final BroadcastReceiver changes = new BroadcastReceiver() { public void onReceive(Context c, Intent i) { if (preview != null) preview.invalidate(); paint(); } };

    public void onCreate(Bundle state) {
        super.onCreate(state);
        s = NavSession.get(this); theme = new Gabi.Themer(Gabi.resolve(this, s.lat, s.lon, s.fixAt > 0)); k = new Gabi.Kit(this, theme);
        uk = new Gabi.Kit(this, updateTheme = theme.child()); mk = new Gabi.Kit(this, mapsTheme = theme.child());
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
        progressAnim.addUpdateListener(a -> progress.setTranslationX((float)a.getAnimatedValue() * track.getWidth()));
        primary = k.primary("", this::onPrimary); bike.addView(primary);
        scanRow = k.row(8);
        scanRow.addView(k.ghost("Scan again", this::scanCamera), new LinearLayout.LayoutParams(0, -2, 1));
        scanRow.addView(k.ghost("Use a photo", this::scanPhoto), new LinearLayout.LayoutParams(0, -2, 1));
        bike.addView(scanRow);
        LinearLayout scanHint = k.row(4); scanHint.addView(k.text("Scanning the QR: park first, close Carbit Ride, keep Wi-Fi on", 12.5f, 500, MUTED), new LinearLayout.LayoutParams(0, -2, 1));
        scanHint.addView(k.info("Park first. Close Carbit Ride and keep the QR screen open on the bike display. Wi-Fi on, hotspot off.")); bike.addView(scanHint);
        // Everything Daanvia needs from Android, on one screen (SetupActivity).
        setupRow = k.listRow(IconDrawable.CHECK, "Get ready to ride", "", null, () -> startActivity(new Intent(this, SetupActivity.class)));
        bike.addView(setupRow);
        String brand = KeepAlive.colorOs();
        if (brand != null) {
            // OPPO, OnePlus and realme phones (ColorOS) close apps mid-ride even when Android allows them to run, so their
            // riders get the phone's own settings step by step, highlighted, until they say they've done it.
            colorBox = k.column(8); colorBox.setPadding(k.dp(14), k.dp(12), k.dp(14), k.dp(12)); k.fill(colorBox, 16, ACCENT_SOFT);
            colorBox.addView(k.text("On your " + brand + " phone", 15, 700, INK));
            colorBox.addView(k.text(brand + " phones have their own battery manager that can close Daanvia in the middle of a ride, even when Android allows it to keep running. The bike display then goes blank. To stop it:", 13, 500, INK_SOFT));
            colorBox.addView(k.text("1.  Tap Open battery settings below.\n2.  Open Battery usage (or Battery) and turn on Allow background activity, and Allow foreground activity if you see it.\n3.  Turn on Allow auto launch, if your phone shows it.\n4.  Open your recent apps and lock Daanvia: swipe down on its card, or tap ⋮ on it and choose Lock.", 13, 550, INK));
            colorBox.addView(k.primary("Open battery settings", this::openAppInfo));
            colorBox.addView(k.ghost("I've done this", () -> { getSharedPreferences("ui", 0).edit().putBoolean("coloros_done", true).apply(); paintColorOs(); }));
            colorDone = k.text("", 12.5f, 600, ACCENT); Gabi.press(colorDone, () -> { getSharedPreferences("ui", 0).edit().putBoolean("coloros_done", false).apply(); paintColorOs(); });
            bike.addView(colorBox); bike.addView(colorDone); paintColorOs();
            // Opened from a ride reminder: go straight to these steps.
            if ("coloros".equals(getIntent().getStringExtra("show"))) page.scroll.post(() -> page.scroll.smoothScrollTo(0, Math.max(0, bike.getTop() + colorBox.getTop() - k.dp(16))));
        }
        permissionBanner = k.column(10); permissionBanner.setPadding(k.dp(14), k.dp(12), k.dp(14), k.dp(12)); k.fill(permissionBanner, 16, DANGER_SOFT); permissionBanner.setVisibility(View.GONE);
        permissionBanner.addView(k.text("Android needs Nearby devices and precise location for the Wi-Fi connection. Daanvia does not collect GPS positions for this.", 13, 550, INK));
        permissionBanner.addView(k.ghost("Open app settings", () -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())))));
        bike.addView(permissionBanner);
        c.addView(bike);

        // ---- TFT preview and fit
        LinearLayout tft = GabiPage.card(k);
        LinearLayout tftHead = k.row(4); tftHead.addView(k.label("Bike display"), new LinearLayout.LayoutParams(0, -2, 1));
        tftHead.addView(k.info("This is what Daanvia sends to your bike’s screen. When you’re not riding it shows a sample ride, with a sample notification, so you can see every part. During a ride it shows the real thing. It’s drawn here on the phone, so colors may look a little different on the bike."));
        tft.addView(tftHead);
        preview = new TftPreview(this, s); tft.addView(preview, new LinearLayout.LayoutParams(-1, -2));
        previewNote = k.text("Sample ride. Your real directions show here while you ride.", 12.5f, 600, ACCENT); previewNote.setPadding(0, k.dp(8), 0, 0); tft.addView(previewNote);
        // What the bike reported about its screen when it last connected (see NavSession.screenReport).
        screenText = k.text("", 13, 500, MUTED); screenText.setPadding(0, k.dp(10), 0, 0); tft.addView(screenText);
        shapeRow = k.column(6);
        shapeRow.addView(switchRow("Match the bike's screen shape", "This bike's panel is a different shape from the video it asks for. On, Daanvia draws for the panel so nothing looks stretched. Turn it off if the display looks squeezed.",
            getSharedPreferences("ui", 0).getBoolean("matchshape", true), on -> { getSharedPreferences("ui", 0).edit().putBoolean("matchshape", on).apply(); s.signal(); repaintPreview(); }));
        tft.addView(shapeRow);
        tft.addView(titled("What the bike shows while riding", "Directions only shows the next turn and your trip on a plain screen, without the moving map. The phone does about a third of the work, so it runs cooler and the battery lasts longer on long rides."));
        boolean simple = getSharedPreferences("ui", 0).getBoolean("directionsonly", false);
        tft.addView(k.segmented(new String[]{"Map and directions", "Directions only"}, simple ? 1 : 0, i -> { getSharedPreferences("ui", 0).edit().putBoolean("directionsonly", i == 1).apply(); s.signal(); repaintPreview(); }));
        tft.addView(titled("Notifications on the bike", "While the bike display is connected, a card shows on it for a few seconds: who it’s from and the start of the message. Calls & social covers incoming calls, texts, Messenger, Facebook, Instagram, TikTok, WhatsApp, Viber, Telegram and other chat and social apps. Everything adds every other app, except silent ones, music and downloads. Needs Android’s Notification access. Nothing is saved or sent anywhere."));
        boolean notifying = RideNotifications.enabled(this) && RideNotifications.allowed(this);
        tft.addView(k.segmented(new String[]{"Off", "Calls & social", "Everything"}, !notifying ? 0 : RideNotifications.scope(this) == RideNotifications.EVERYTHING ? 2 : 1, i -> {
            RideNotifications.setEnabled(this, i != 0);
            if (i != 0) RideNotifications.setScope(this, i == 2 ? RideNotifications.EVERYTHING : RideNotifications.SOCIAL);
            preview.invalidate();  // the sample card shows only while notifications are on
            if (i != 0 && !RideNotifications.allowed(this)) RideNotifications.explainThenOpen(this);
        }));
        appsRow = k.listRow(IconDrawable.MIRROR, "Choose apps", "", null, () -> startActivity(new Intent(this, NotifyAppsActivity.class)));
        tft.addView(appsRow);
        tft.addView(switchRow("Rest the phone while riding", "When the bike display is showing directions and you haven’t touched the phone for 15 seconds, the phone switches to a dark screen with just the next turn"
            + (KeepAlive.keepScreenOn() ? ", kept on but dim: your " + KeepAlive.colorOs() + " phone would close Daanvia soon after the screen went off." : " and may turn off, to save battery.")
            + " Tap it to bring the map back. On the ride screen, More › Rest phone screen does it right away.",
            getSharedPreferences("ui", 0).getBoolean("bikemode", true), on -> getSharedPreferences("ui", 0).edit().putBoolean("bikemode", on).apply()));
        TextView layoutBtn = k.primary("Edit display layout", () -> { if (s.active) { android.widget.Toast.makeText(this, "End the ride first, then edit the layout while parked.", android.widget.Toast.LENGTH_SHORT).show(); return; } startActivity(new Intent(this, TftLayoutActivity.class)); });
        tft.addView(layoutBtn);
        adjust = k.ghost("Fit to the screen edges", this::beginFit); tft.addView(adjust);
        fitNote = k.text("End the ride first, then fit the screen while parked.", 12.5f, 600, ACCENT); fitNote.setVisibility(View.GONE); tft.addView(fitNote);
        fitControls = k.column(6); fitControls.setVisibility(View.GONE); tft.addView(fitControls);
        c.addView(tft);
        // Opened from the ride's More menu: straight to the bike display.
        if ("display".equals(getIntent().getStringExtra("show"))) page.scroll.post(() -> page.scroll.smoothScrollTo(0, Math.max(0, tft.getTop() - k.dp(12))));

        // ---- theme
        LinearLayout look = GabiPage.card(k);
        look.addView(k.label("Map"));
        look.addView(titled("Theme", "Auto switches to the night map after sunset where you are riding, and back at sunrise."));
        String[] keys = {Gabi.THEME_AUTO, Gabi.THEME_DAY, Gabi.THEME_NIGHT}; String cur = Gabi.themeSetting(this); int sel = 0; for (int i = 0; i < 3; i++) if (keys[i].equals(cur)) sel = i;
        look.addView(k.segmented(new String[]{"Auto", "Day", "Night"}, sel, i -> { Gabi.setThemeSetting(this, keys[i]); theme.set(Gabi.resolve(this, s.lat, s.lon, s.fixAt > 0)); }));
        // ---- GPS, in the same card
        LinearLayout gps = look;
        LinearLayout gpsHead = k.row(4); gpsHead.addView(k.text("GPS", 15, 650, INK), new LinearLayout.LayoutParams(0, -2, 1));
        gpsHead.addView(k.info("Recalibrate clears the satellite data your phone saved and downloads fresh data, so GPS locks on again from scratch. Use it when your location is stuck, far off, or slow to find you. GPS check shows how accurate it is right now."));
        gps.addView(gpsHead);
        LinearLayout gpsRow = k.row(8);
        gpsRow.addView(k.ghost("Recalibrate GPS", this::recalibrateGps), new LinearLayout.LayoutParams(0, -2, 1));
        gpsRow.addView(k.ghost("GPS check", () -> startActivity(new Intent(this, SupportActivity.class))), new LinearLayout.LayoutParams(0, -2, 1));
        gps.addView(gpsRow);
        c.addView(look);

        // ---- downloaded maps
        LinearLayout maps = GabiPage.card(k);
        LinearLayout mapsHead = k.row(4); mapsHead.addView(k.label("Downloaded maps"), new LinearLayout.LayoutParams(0, -2, 1));
        mapsHead.addView(k.info("Downloaded areas live on your phone, so the map, search and directions work without signal. Download the places you ride. Remove ones you no longer need to free up space."));
        maps.addView(mapsHead);
        mapList = k.column(0); maps.addView(mapList);
        storageText = k.text("", 12.5f, 500, MUTED); maps.addView(storageText);
        maps.addView(k.ghost("Free up space", this::freeUp));
        maps.addView(k.ghost("Download another area", () -> { startActivity(new Intent(this, HomeActivity.class).putExtra("select_area", true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)); finish(); }));
        c.addView(maps);

        // ---- testing
        LinearLayout test = GabiPage.card(k); test.setPadding(k.dp(16), k.dp(12), k.dp(16), k.dp(6));
        test.addView(k.label("Help"));
        test.addView(k.listRow(IconDrawable.PLAY, "Demo ride", "A sample route on the phone and bike display", null,
            () -> { startActivity(new Intent(this, HomeActivity.class).putExtra("demo", true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)); finish(); }));
        test.addView(k.listRow(IconDrawable.TOOLS, "Support and test tools", "Connection test, test pattern, mirror test, logs", null, () -> startActivity(new Intent(this, SupportActivity.class))));
        TextView logState = k.text(LogUpload.status(this), 12.5f, 600, INK_SOFT);
        test.addView(switchRow("Send diagnostics to the developer", "During the beta, Daanvia sends its connection and ride log every few minutes, so problems like a bike display that drops can be fixed. It never includes where you are or where you’re going, passwords or device addresses: only the log lines, the app version, the phone model and a random id.",
            LogUpload.enabled(this), on -> { LogUpload.setEnabled(this, on); logState.setText(LogUpload.status(this)); }));
        // Whether the log is actually getting through: rides of 14 Nov never reached the developer, and nothing showed it.
        LinearLayout logRow = k.row(10); logRow.addView(logState, new LinearLayout.LayoutParams(0, -2, 1));
        logRow.addView(k.ghost("Send now", () -> { logState.setText("Sending…"); LogUpload.sendNow(this, () -> logState.setText(LogUpload.status(this))); }));
        test.addView(logRow);
        c.addView(test);

        // ---- about
        LinearLayout more = GabiPage.card(k); more.setPadding(k.dp(16), k.dp(12), k.dp(16), k.dp(6));
        more.addView(k.label("About"));
        updateBox = k.column(0); more.addView(updateBox);
        more.addView(k.listRow(IconDrawable.SHARE, "Share navigation report", "Locations, destinations and pairing details are left out", null,
            () -> startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareable(s.report())), "Share navigation report"))));
        more.addView(k.listRow(IconDrawable.INFO, "Licenses and map data", "© OpenStreetMap contributors · ODbL", null, this::toggleLicenses));
        licenseText = k.text("", 11, 500, MUTED); licenseText.setTypeface(android.graphics.Typeface.MONOSPACE); licenseText.setVisibility(View.GONE); licenseText.setTextIsSelectable(true);
        more.addView(licenseText);
        c.addView(more);
        // Opened from the home screen's update notice: straight to the update, at the bottom of the page.
        if ("update".equals(getIntent().getStringExtra("show"))) page.scroll.post(() -> page.scroll.smoothScrollTo(0, Math.max(0, more.getTop() - k.dp(12))));

        link = DashboardLink.attach(this, this);
        if (getIntent().getBooleanExtra("navigation_connect", false) && link.profileReady()) link.sendNavigation();
        paint();
    }

    /** NavSession reads these settings at most twice a second, so the preview draws again once it has the new value. */
    private void repaintPreview() { preview.postDelayed(preview::invalidate, 600); }
    private LinearLayout appsRow;
    /** "All on" or "2 turned off" under Choose apps. */
    private void paintApps() {
        if (appsRow == null) return; int off = RideNotifications.hidden(this).size(); TextView sub = findSub(appsRow);
        if (sub != null) sub.setText(off == 0 ? "All on · turn off an app or one kind" : off + " turned off · tap to change");
    }
    private TextView colorDone, previewNote; private LinearLayout colorBox; private LinearLayout setupRow;
    /** An option that is simply on or off: its name, an (i) that explains it, and a switch. Tapping the name flips it too. */
    private LinearLayout switchRow(String title, String explanation, boolean on, java.util.function.Consumer<Boolean> change) {
        LinearLayout r = k.row(4); TextView name = k.text(title, 15, 650, INK); r.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        r.addView(k.info(explanation)); FrameLayout sw = k.toggle(on, change); r.addView(sw);
        name.setOnClickListener(v -> sw.performClick()); r.setMinimumHeight(k.dp(48)); return r;
    }
    /**
     * The bike's name for the rider. The QR's model field is often just a number (a QJ's is its channel, 37501), which
     * means nothing to them.
     */
    private String bikeName() { String m = link.model(); return m == null || m.trim().isEmpty() || m.trim().matches("[0-9]+") ? "Your bike" : m.trim(); }
    /** An option's name with an (i) beside it that explains it, instead of a paragraph under every option. */
    private LinearLayout titled(String title, String explanation) {
        LinearLayout r = k.row(4); r.addView(k.text(title, 15, 650, INK), new LinearLayout.LayoutParams(0, -2, 1)); r.addView(k.info(explanation)); return r;
    }
    /** "3 of 5 ready" under Get ready to ride. */
    private void paintSetup() {
        if (setupRow == null) return; int[] p = SetupActivity.progress(this);
        TextView sub = findSub(setupRow); if (sub != null) sub.setText(p[0] == p[1] ? "All set" : p[0] + " of " + p[1] + " ready · tap to finish");
    }
    /** A list row's second line (its subtitle). */
    private static TextView findSub(LinearLayout row) {
        for (int i = 0; i < row.getChildCount(); i++) if (row.getChildAt(i) instanceof LinearLayout) {
            LinearLayout col = (LinearLayout)row.getChildAt(i); if (col.getChildCount() > 1 && col.getChildAt(1) instanceof TextView) return (TextView)col.getChildAt(1);
        }
        return null;
    }
    private void openAppInfo() { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); }
    private void paintColorOs() {
        if (colorBox == null) return; boolean done = KeepAlive.colorOsDone(this);
        colorBox.setVisibility(done ? View.GONE : View.VISIBLE); colorDone.setVisibility(done ? View.VISIBLE : View.GONE);
        colorDone.setText("Set up for " + KeepAlive.colorOs() + ". Show the steps again");
    }
    protected void onStart() {
        super.onStart(); AppUpdate.listen(updateListener); paintUpdate(); AreaStore.listen(areaListener); catalog = AreaStore.cached(this); paintMaps(); loadAreas(); refreshCatalog();
        androidx.core.content.ContextCompat.registerReceiver(this, changes, new IntentFilter(NavSession.EVENT), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED); paint(); }
    protected void onStop() { AppUpdate.unlisten(updateListener); AreaStore.unlisten(areaListener); try { unregisterReceiver(changes); } catch (Exception ignored) {} super.onStop(); }
    protected void onDestroy() { if (fitting) cancelFit(); progressAnim.cancel(); link.detach(this); super.onDestroy(); }
    public void finish() { super.finish(); overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out); }

    /** Text for the share sheet, cut to what Android reliably passes between apps (as Support's report does). */
    private static String shareable(String report) { return report.length() <= 60000 ? report : report.substring(0, 60000) + "\n(cut short)"; }
    private TextView screenText; private LinearLayout shapeRow;
    private void paintScreen() {
        if (screenText == null) return;
        if (s.panelW <= 0) { screenText.setText("Connect to your bike once and Daanvia learns its screen size and frame rate, and draws for them."); shapeRow.setVisibility(View.GONE); return; }
        StringBuilder b = new StringBuilder("Bike screen ").append(s.panelW).append(" × ").append(s.panelH);
        if (s.videoW > 0 && (s.videoW != s.panelW || s.videoH != s.panelH)) b.append(" · video ").append(s.videoW).append(" × ").append(s.videoH);
        if (s.bikeFps > 0) b.append(" · ").append(s.bikeFps).append(" frames a second");
        if (!s.screenType.isEmpty()) b.append(" · ").append(s.screenType);
        screenText.setText(b);
        boolean differs = s.videoW > 0 && Math.abs((float)s.panelW / s.panelH - (float)s.videoW / s.videoH) > 0.03f;
        shapeRow.setVisibility(differs ? View.VISIBLE : View.GONE);
    }
    private void paint() {
        paintScreen(); paintSetup(); paintApps();
        if (link == null) return;
        boolean ready = link.profileReady(), live = StreamService.navigationActive, busy = link.searching();
        modelText.setText(ready ? bikeName() : "No bike saved yet");
        statusText.setText(live ? "Connected. Daanvia is on the bike display" : link.status());
        previewNote.setVisibility(s.active ? View.GONE : View.VISIBLE);
        liveChip.setVisibility(live ? View.VISIBLE : View.GONE);
        ((View)progress.getParent()).setVisibility(busy ? View.VISIBLE : View.GONE);
        // The searching bar moves only while it shows; running all the time it redrew Settings every frame.
        if (busy) { if (!progressAnim.isStarted()) progressAnim.start(); } else progressAnim.cancel();
        // The same words as the ride screen's Connect bike button.
        primary.setText(busy ? "Stop searching" : live ? "Back to the map" : ready ? "Connect bike" : "Scan your bike's QR");
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
        btns.addView(k.primary("Save", () -> { s.saveFit(); endFit(); }), new LinearLayout.LayoutParams(0, -2, 1.4f));
        fitControls.addView(btns);
        Gabi.show(fitControls, true, k.dp(12)); s.signal(); paint();
    }
    private static String value(int n, int v) { return n == 4 ? (100 + v) + "%" : v + "%"; }
    private void cancelFit() { s.insetLeft = old[0]; s.insetTop = old[1]; s.insetRight = old[2]; s.insetBottom = old[3]; s.textScale = old[4]; s.layout = oldLayout; endFit(); }
    private void endFit() { fitting = false; s.calibration = false; fitControls.setVisibility(View.GONE); s.signal(); paint(); }

    /** App version, with check, download progress and install. */
    private void paintUpdate() {
        if (updateBox == null) return;
        updateBox.removeAllViews(); updateTheme.clear();
        AppUpdate.State st = AppUpdate.state();
        String sub; String action = null; Runnable run = null;
        if (BuildInfo.PLAY) { sub = "Updates come from Google Play"; action = "Open"; run = () -> AppUpdate.openPlay(this); }
        else if (st == null) { sub = "Tap to check for updates"; action = "Check"; run = () -> AppUpdate.check(this, false); }
        else if (st.checking) sub = "Checking for updates…";
        else if (st.downloading) sub = "Downloading " + st.release.version + " · " + st.percent() + "%";
        else if (st.error != null) { sub = st.error; action = st.available() ? "Update" : "Check"; run = st.available() ? () -> AppUpdate.install(this) : () -> AppUpdate.check(this, false); }
        else if (st.available()) { sub = "Version " + st.release.version + " is available · " + st.release.sizeLabel(); action = st.ready ? "Install" : "Update"; run = () -> AppUpdate.install(this); }
        else { sub = "You have the latest version"; action = "Check"; run = () -> AppUpdate.check(this, false); }
        LinearLayout r = uk.listRow(IconDrawable.DOWNLOAD, "Daanvia " + BuildInfo.VERSION, sub, null, null);
        if (action != null) { TextView a = uk.text(action, 13.5f, 700, ACCENT); a.setPadding(uk.dp(12), uk.dp(8), 0, uk.dp(8)); Runnable go = run; Gabi.press(a, () -> { if (s.active) { Gabi.shake(a); return; } go.run(); }); r.addView(a); }
        updateBox.addView(r);
        // Been through the phone's installer and still on this version: what may be in the way, and the other way to get it.
        if (st != null && st.blocked) {
            TextView help = uk.text(AppUpdate.BLOCKED_HELP, 12.5f, 500, MUTED); help.setPadding(0, 0, 0, uk.dp(10)); updateBox.addView(help);
            updateBox.addView(uk.ghost("Get it from the website", () -> AppUpdate.openWebsite(this)));
        }
    }
    private void loadAreas() {
        int ask = ++areasAsked; Context app = getApplicationContext();
        new Thread(() -> { List<AreaStore.Area> found = AreaStore.installed(app); runOnUiThread(() -> {
            if (isDestroyed() || ask != areasAsked) return; areas = found; areasLoaded = true; paintMaps(); }); }, "area-list").start();
    }
    /**
     * A newer map index, for the Update buttons: at most every 6 hours, like the home screen's own check (whose time it
     * reads but doesn't set, so the home screen still offers the update). Otherwise the index saved on the phone is used.
     */
    private void refreshCatalog() {
        SharedPreferences p = getSharedPreferences("areas", 0); long now = System.currentTimeMillis();
        if (now - Math.max(p.getLong("update_checked", 0), p.getLong("settings_checked", 0)) < 6 * 3600_000L) return;
        Context app = getApplicationContext(); java.lang.ref.WeakReference<SettingsActivity> screen = new java.lang.ref.WeakReference<>(this);
        new Thread(() -> {
            AreaStore.Catalog fresh; try { fresh = AreaStore.fetch(app); } catch (Exception e) { return; }
            p.edit().putLong("settings_checked", System.currentTimeMillis()).apply();
            SettingsActivity a = screen.get(); if (a != null) a.runOnUiThread(() -> { if (a.isDestroyed()) return; a.catalog = fresh; a.paintMaps(); });
        }, "daanvia-map-index").start();
    }
    /** Downloaded areas with size, km and update/remove, plus the download in progress. */
    private void paintMaps() {
        if (mapList == null) return;
        mapList.removeAllViews(); mapsTheme.clear();
        AreaStore.State st = AreaStore.state();
        if (st != null && st.running) {
            LinearLayout r = mk.listRow(IconDrawable.DOWNLOAD, "Downloading " + st.name, st.progressLabel(), null, null);
            TextView cancel = mk.text("Cancel", 13.5f, 700, ACCENT); cancel.setPadding(mk.dp(12), mk.dp(8), 0, mk.dp(8)); Gabi.press(cancel, AreaStore::cancel); r.addView(cancel);
            mapList.addView(r);
        }
        List<AreaStore.Area> list = areas; boolean busy = st != null && st.running;
        for (AreaStore.Area a : list) {
            boolean update = a.outdated(catalog), confirming = a.id.equals(confirmRemove);
            Long bytes = sizes.get(a.dir.getPath()); if (bytes == null) measureSizes(list);
            String sub = a.kmLabel() + (bytes == null ? "" : " · " + Math.max(1, Math.round(bytes / 1048576.0)) + " MB") + (update ? " · update available" : "");
            LinearLayout r = mk.listRow(IconDrawable.MAP, a.name, sub, null, null);
            if (update) { TextView u = mk.text("Update", 13.5f, 700, ACCENT); u.setPadding(mk.dp(12), mk.dp(8), mk.dp(6), mk.dp(8)); Gabi.press(u, () -> updateArea(a)); r.addView(u); }
            // No Remove while a download runs: it may be this very area's update, swapping its folder.
            if (!busy) {
                TextView del = mk.text(confirming ? "Remove?" : "Remove", 13.5f, 700, confirming ? DANGER : MUTED); del.setPadding(mk.dp(12), mk.dp(8), 0, mk.dp(8));
                Gabi.press(del, () -> {
                    AreaStore.State now = AreaStore.state();
                    if (s.active || (now != null && now.running)) { Gabi.shake(del); return; }
                    if (!confirming) { confirmRemove = a.id; paintMaps(); return; }
                    confirmRemove = null; AreaStore.remove(this, a); sizes.clear();
                    List<AreaStore.Area> left = new ArrayList<>(areas); left.remove(a); areas = left; paintMaps();
                });
                r.addView(del);
            }
            mapList.addView(r);
        }
        if (st == null || !st.running) paintStorage();
        if (areasLoaded && list.isEmpty() && !busy) mapList.addView(mk.listRow(IconDrawable.MAP, "No areas downloaded yet", "Download the area you ride in", null, null));
    }
    /** An area's size means walking thousands of tile files: done off the main thread, then the list is painted again. */
    private boolean measuring;
    private void measureSizes(List<AreaStore.Area> areas) {
        if (measuring) return; measuring = true;
        new Thread(() -> {
            java.util.HashMap<String, Long> found = new java.util.HashMap<>(); for (AreaStore.Area a : areas) found.put(a.dir.getPath(), a.diskBytes());
            runOnUiThread(() -> { measuring = false; if (isDestroyed()) return; sizes.putAll(found); paintMaps(); });
        }, "area-sizes").start();
    }
    /** Measuring walks every tile file: once per visit (and after a change), not at each repaint of the map list. */
    private boolean measuringStorage; private long storageAt;
    private void paintStorage() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (measuringStorage || now - storageAt < 30000) return; measuringStorage = true; storageAt = now;
        new Thread(() -> { long[] u = Housekeeping.usage(this); runOnUiThread(() -> { measuringStorage = false; if (!isDestroyed()) storageText.setText("Daanvia uses " + mb(u[0] + u[1] + u[2]) + " on this phone: " + mb(u[0]) + " of downloaded maps"
            + (u[2] >= 1048576 ? ", " + mb(u[2]) + " saved from online maps and searches" : "") + (u[1] >= 1048576 ? ", " + mb(u[1]) + " for everything else" : "") + "."); }); }).start();
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
                    private void done() { storageAt = 0; paintStorage(); android.widget.Toast.makeText(SettingsActivity.this, freed < 1048576 ? "Already tidy" : "Freed " + mb(freed), android.widget.Toast.LENGTH_SHORT).show(); }
                });
            });
        }).start();
    }
    private void updateArea(AreaStore.Area a) {
        // The update replaces the files the ride is navigating with.
        if (s.active) { android.widget.Toast.makeText(this, "End the ride first, then update the map.", android.widget.Toast.LENGTH_LONG).show(); return; }
        if (!AreaStore.update(this, catalog, a)) android.widget.Toast.makeText(this, "This area's map has changed. Remove it and download it again.", android.widget.Toast.LENGTH_LONG).show();
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
