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
import java.nio.charset.StandardCharsets;

import static net.drave.atrdiagnostics.Gabi.Role.*;

/** Settings: the bike connection, TFT fit, map theme, testing tools and app information. */
public final class SettingsActivity extends Activity implements DashboardLink.Host {
    private NavSession s; private Gabi.Themer theme; private Gabi.Kit k; private DashboardLink link;
    private TftPreview preview; private TextView modelText, statusText, primary, liveChip, fitNote, licenseText; private LinearLayout permissionBanner, fitControls, scanRow; private View progress; private ValueAnimator progressAnim;
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
        more.addView(k.listRow(IconDrawable.SHARE, "Share navigation report", "Locations, destinations and pairing details are left out", null,
            () -> startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, s.report()), "Share navigation report"))));
        more.addView(k.listRow(IconDrawable.CHECK, "Licenses and map data", "© OpenStreetMap contributors · ODbL", null, this::toggleLicenses));
        licenseText = k.text("", 11, 500, MUTED); licenseText.setTypeface(android.graphics.Typeface.MONOSPACE); licenseText.setVisibility(View.GONE); licenseText.setTextIsSelectable(true);
        more.addView(licenseText);
        c.addView(more);

        link = new DashboardLink(this, this);
        if (getIntent().getBooleanExtra("navigation_connect", false) && link.profileReady()) link.sendNavigation();
        paint();
    }

    protected void onStart() { super.onStart(); androidx.core.content.ContextCompat.registerReceiver(this, changes, new IntentFilter(NavSession.EVENT), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED); paint(); }
    protected void onStop() { try { unregisterReceiver(changes); } catch (Exception ignored) {} super.onStop(); }
    protected void onDestroy() { if (fitting) cancelFit(); progressAnim.cancel(); link.destroy(); super.onDestroy(); }
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
