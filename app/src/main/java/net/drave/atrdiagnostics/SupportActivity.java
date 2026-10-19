package net.drave.atrdiagnostics;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.*;
import android.widget.*;

import static net.drave.atrdiagnostics.Gabi.Role.*;

/** Support and test tools: the connection test, test pattern, mirror test, visual confirmation, logs and report. */
public final class SupportActivity extends Activity implements DashboardLink.Host {
    private Gabi.Kit k; private DashboardLink link; private TextView status, log, pattern, mirror, confirm; private LinearLayout choices, permissionBanner; private ScrollView logScroll;

    public void onCreate(Bundle state) {
        super.onCreate(state);
        NavSession s = NavSession.get(this);
        k = new Gabi.Kit(this, new Gabi.Themer(Gabi.resolve(this, s.lat, s.lon, s.fixAt > 0)));
        GabiPage page = new GabiPage(this, k, "Support and test tools");
        LinearLayout c = page.content;

        LinearLayout top = GabiPage.card(k);
        top.addView(k.label("Status"));
        status = k.text("", 16, 700, OK); top.addView(status);
        top.addView(k.text("Tests are local. Park first, close Carbit Ride and keep the dashboard’s QR screen open. Wi-Fi on, hotspot off.", 12.5f, 500, MUTED));
        permissionBanner = k.column(10); permissionBanner.setPadding(k.dp(14), k.dp(12), k.dp(14), k.dp(12)); k.fill(permissionBanner, 16, DANGER_SOFT); permissionBanner.setVisibility(View.GONE);
        permissionBanner.addView(k.text("Android needs Nearby devices and precise location access for these Wi-Fi checks. The app does not request GPS coordinates.", 13, 550, INK));
        permissionBanner.addView(k.ghost("Open app settings", () -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())))));
        top.addView(permissionBanner);
        c.addView(top);

        LinearLayout steps = GabiPage.card(k);
        steps.addView(k.label("Pair"));
        LinearLayout r1 = k.row(8);
        r1.addView(k.ghost("Scan dashboard QR", this::scanCamera), new LinearLayout.LayoutParams(0, -2, 1));
        r1.addView(k.ghost("QR photo", this::scanPhoto), new LinearLayout.LayoutParams(0, -2, 1));
        steps.addView(r1);
        steps.addView(k.ghost("Test connection", () -> link.testConnection()));
        steps.addView(k.primary("Send Daanvia to dashboard", () -> link.sendNavigation()));
        steps.addView(k.label("Video tests"));
        pattern = k.ghost("Send animated test pattern", () -> link.requestStream(DashboardLink.MODE_PATTERN, null)); steps.addView(pattern);
        mirror = k.ghost("Mirror phone · 90 seconds", () -> {
            if (!link.identificationAccepted()) { link.note("Complete Test connection first."); return; }
            if (link.serviceActive()) { link.note("Stop the current stream test first."); return; }
            startActivityForResult(getSystemService(MediaProjectionManager.class).createScreenCaptureIntent(), 40);
        });
        steps.addView(mirror);
        confirm = k.ghost("Confirm what appeared on the bike", this::askConfirm); steps.addView(confirm);
        choices = k.column(8); choices.setVisibility(View.GONE); steps.addView(choices);
        LinearLayout r2 = k.row(8);
        r2.addView(k.ghost("Stop", () -> link.stop()), new LinearLayout.LayoutParams(0, -2, 1));
        r2.addView(k.ghost("Share report", this::shareReport), new LinearLayout.LayoutParams(0, -2, 1));
        steps.addView(r2);
        c.addView(steps);

        // ---- GPS check: live accuracy and satellites from the phone, to confirm a good fix on the bike
        LinearLayout gpsCard = GabiPage.card(k);
        LinearLayout gh = k.row(4); gh.addView(k.label("GPS check"), new LinearLayout.LayoutParams(0, -2, 1));
        gh.addView(k.info("Shows what the phone's GPS is reporting right now. Under 10 m is great for directions; over 30 m usually means you're indoors, under a roof or near tall buildings. Try it outside with a clear view of the sky."));
        gpsCard.addView(gh);
        gpsText = k.num("Not running", 14, 600, INK); gpsCard.addView(gpsText);
        gpsBtn = k.ghost("Start GPS check", this::toggleGps); gpsCard.addView(gpsBtn);
        c.addView(gpsCard);

        LinearLayout logs = GabiPage.card(k);
        logs.addView(k.label("Log"));
        logScroll = new ScrollView(this); log = k.text("", 11.5f, 500, INK_SOFT); log.setTypeface(android.graphics.Typeface.MONOSPACE); log.setTextIsSelectable(true);
        logScroll.addView(log); logs.addView(logScroll, new LinearLayout.LayoutParams(-1, k.dp(260)));
        c.addView(logs);

        link = new DashboardLink(this, this);
        status.setText(link.status()); onLog(); onChanged();
    }
    protected void onDestroy() { stopGps(); link.destroy(); super.onDestroy(); }
    protected void onPause() { stopGps(); super.onPause(); }

    // ---- GPS check
    private TextView gpsText, gpsBtn; private android.location.LocationManager lm; private android.location.Location fix; private int used, seen; private boolean gpsOn;
    private final android.os.Handler tick = new android.os.Handler(android.os.Looper.getMainLooper());
    private final android.location.LocationListener gpsListener = l -> { fix = l; paintGps(); };
    private final android.location.GnssStatus.Callback gnss = new android.location.GnssStatus.Callback() {
        public void onSatelliteStatusChanged(android.location.GnssStatus st) { int u = 0; for (int i = 0; i < st.getSatelliteCount(); i++) if (st.usedInFix(i)) u++; used = u; seen = st.getSatelliteCount(); paintGps(); }
    };
    private final Runnable every = new Runnable() { public void run() { paintGps(); if (gpsOn) tick.postDelayed(this, 1000); } };
    private void toggleGps() {
        if (gpsOn) { stopGps(); return; }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) { requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 74); return; }
        lm = getSystemService(android.location.LocationManager.class);
        try {
            lm.requestLocationUpdates(android.location.LocationManager.GPS_PROVIDER, 1000, 0, gpsListener, getMainLooper());
            lm.registerGnssStatusCallback(gnss, tick);
        } catch (SecurityException e) { return; }
        gpsOn = true; fix = null; used = seen = 0; gpsBtn.setText("Stop GPS check"); tick.post(every);
    }
    private void stopGps() {
        if (!gpsOn) return; gpsOn = false; tick.removeCallbacks(every);
        try { lm.removeUpdates(gpsListener); lm.unregisterGnssStatusCallback(gnss); } catch (Exception ignored) {}
        gpsBtn.setText("Start GPS check");
    }
    private void paintGps() {
        if (!gpsOn) return;
        boolean located = lm != null && lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER);
        if (!located) { gpsText.setText("Location is off. Turn it on in the phone's quick settings."); return; }
        String sats = used + " of " + seen + " satellites in use";
        if (fix == null) { gpsText.setText("Waiting for a fix… " + sats); return; }
        long age = (android.os.SystemClock.elapsedRealtimeNanos() - fix.getElapsedRealtimeNanos()) / 1_000_000_000L;
        float acc = fix.getAccuracy();
        String quality = acc <= 10 ? "Great" : acc <= 20 ? "Good" : acc <= 35 ? "Fair" : "Weak";
        gpsText.setText(quality + " · ±" + Math.round(acc) + " m\n" + sats + "\nLast fix " + age + " s ago" + (fix.hasSpeed() ? " · " + Math.round(fix.getSpeed() * 3.6f) + " km/h" : "")
            + "\nDirections use " + (Build.VERSION.SDK_INT >= 31 && lm.hasProvider(android.location.LocationManager.FUSED_PROVIDER) ? "GPS + motion sensors" : "GPS"));
    }

    private void askConfirm() {
        if (!link.sawFrames()) return;
        choices.removeAllViews();
        choices.addView(k.text(link.confirmQuestion(), 14, 650, INK));
        String[] options = link.confirmChoices();
        for (int i = 0; i < options.length; i++) { final int n = i; choices.addView(k.ghost(options[i], () -> { link.confirmVideo(n); choices.setVisibility(View.GONE); })); }
        Gabi.show(choices, true, k.dp(8));
    }
    private void shareReport() {
        try {
            Uri uri = link.writeReport();
            Intent send = new Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            send.setClipData(ClipData.newRawUri("Daanvia diagnostic report", uri)); startActivity(Intent.createChooser(send, "Share diagnostic report"));
        } catch (Exception e) { link.note("Could not share report: " + e.getMessage()); }
    }

    private void scanCamera() {
        link.resetForScan();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.CAMERA}, 20);
        else startActivityForResult(new Intent(this, ScanActivity.class), 30);
    }
    private void scanPhoto() { link.resetForScan(); startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), 31); }
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 40) { if (result == RESULT_OK && data != null) link.requestStream(DashboardLink.MODE_MIRROR, data); else link.note("Screen sharing declined. Nothing was captured."); return; }
        if (result != RESULT_OK || data == null) return;
        if (request == 30) link.acceptQr(data.getStringExtra("qr"));
        if (request == 31 && data.getData() != null) link.decodePhoto(data.getData());
    }
    public void onRequestPermissionsResult(int r, String[] p, int[] results) {
        super.onRequestPermissionsResult(r, p, results);
        if (r == 20) { if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) scanCamera(); else link.note("Camera access denied. You can choose a QR photo instead."); }
        if (r == DashboardLink.PERMISSIONS) { permissionBanner.setVisibility(View.GONE); link.onPermissionsResult(); }
        if (r == 74 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) toggleGps();
    }

    public void onStatus(String s) { status.setText(s); }
    public void onLog() { log.setText(link == null ? "" : link.reportText()); logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN)); }
    public void onChanged() {
        if (link == null) return;
        boolean id = link.identificationAccepted();
        pattern.setEnabled(id); pattern.setAlpha(id ? 1f : 0.4f);
        mirror.setEnabled(id); mirror.setAlpha(id ? 1f : 0.4f);
        confirm.setEnabled(link.sawFrames()); confirm.setAlpha(link.sawFrames() ? 1f : 0.4f);
    }
    public void onPermissionsMissing() { Gabi.show(permissionBanner, true, k.dp(8)); }
    public void onNavigationStarted() { startActivity(new Intent(this, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)); }
}
