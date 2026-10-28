package net.drave.atrdiagnostics;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * First launch of the new Daanvia (app id net.drave.daanvia, signed with the private release key) while the old app
 * (net.drave.atrdiagnostics, signed with the public test key) is still installed: bring over its downloaded maps, saved
 * places, bike pairing and settings, so the rider loses nothing. The old app hands them over from version 0.8.1
 * (TransferProvider there); an older one is first updated to 0.8.1. Settings must not be read here before the copy, so
 * this screen uses a fixed look and keeps its own state in a marker file, and the app restarts after the move.
 */
public final class MoveActivity extends Activity {
    static final String OLD_APP = "net.drave.atrdiagnostics";
    private static final String AUTHORITY = "net.drave.atrdiagnostics.transfer";
    /** 0.8.1, the first old version that can hand its data over; the new app installs it over an older one. */
    private static final long HANDOVER_VERSION = 39;
    private static final String HANDOVER_URL = "https://github.com/danrave1234/daanvia/releases/download/v0.8.1/daanvia-0.8.1.apk";
    private static final String HANDOVER_SHA256 = "57f0a61368f6caaf626530bced158ae58c2e1a7b51cd67b557b888d28fdf30dd";

    /** A file rather than a setting: settings can't be loaded before the move copies the old ones in. */
    static File marker(Context c) { return new File(c.getNoBackupFilesDir(), "moved-from-old-daanvia"); }
    static long oldVersion(Context c) {
        try { PackageInfo p = c.getPackageManager().getPackageInfo(OLD_APP, 0); return p.getLongVersionCode(); }
        catch (PackageManager.NameNotFoundException e) { return -1; }
    }
    /** Offer the move on the first launch, while the old app is still on the phone. */
    static boolean shouldOffer(Context c) { return !marker(c).exists() && oldVersion(c) >= 0; }

    private Gabi.Kit k; private TextView status, primary, skip; private volatile boolean working;

    protected void onCreate(Bundle state) {
        super.onCreate(state);
        k = new Gabi.Kit(this, new Gabi.Themer(Gabi.NIGHT));
        GabiPage page = new GabiPage(this, k, "Your old Daanvia");
        LinearLayout card = GabiPage.card(k);
        card.addView(k.text("Bring over your maps and settings", 18, 750, Gabi.Role.INK));
        card.addView(k.text("This is the new Daanvia, with its own secure signing key. The old Daanvia is still on your phone. Move your downloaded maps, saved places, bike pairing and settings here, so you don't have to download or set anything up again.", 14, 500, Gabi.Role.MUTED));
        status = k.text("", 14, 600, Gabi.Role.INK); card.addView(status);
        primary = k.primary("Move my maps and settings", this::go); card.addView(primary);
        skip = k.ghost("Start fresh instead", this::fresh); card.addView(skip);
        page.content.addView(card);
    }

    protected void onResume() { super.onResume(); if (!working) paint(); }

    private boolean ready() { return oldVersion(this) >= HANDOVER_VERSION && getPackageManager().resolveContentProvider(AUTHORITY, 0) != null; }
    private void paint() {
        if (oldVersion(this) < 0) { status.setText("The old Daanvia isn't on this phone any more."); primary.setText("Open Daanvia"); return; }
        if (ready()) { status.setText(""); primary.setText("Move my maps and settings"); }
        else { status.setText("First, the old Daanvia needs a small update (0.8.1) so it can hand over your maps. Android will ask you to confirm it."); primary.setText("Update the old Daanvia"); }
    }

    private void go() {
        if (working) return;
        if (oldVersion(this) < 0) { fresh(); return; }
        if (!ready()) { updateOld(); return; }
        working = true; skip.setEnabled(false); primary.setEnabled(false); status.setText("Getting ready…");
        new Thread(this::move, "daanvia-move").start();
    }

    /** Leave the old app's data where it is and start the new app empty. */
    private void fresh() {
        try { File m = marker(this); m.getParentFile().mkdirs(); try (FileOutputStream o = new FileOutputStream(m)) { o.write("fresh".getBytes(StandardCharsets.UTF_8)); } } catch (IOException ignored) {}
        startActivity(new Intent(this, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)); finish();
    }

    private void say(String s) { runOnUiThread(() -> status.setText(s)); }
    private void failed(String s) { runOnUiThread(() -> { working = false; status.setText(s); primary.setEnabled(true); skip.setEnabled(true); }); }

    /** Copy every file the old app lists into the same place here, then restart so everything loads from the copies. */
    private void move() {
        ContentResolver cr = getContentResolver();
        List<String> paths = new ArrayList<>(); long total = 0;
        try (Cursor q = cr.query(Uri.parse("content://" + AUTHORITY + "/list"), null, null, null, null)) {
            if (q == null) throw new IOException("no answer");
            while (q.moveToNext()) {
                String rel = q.getString(0);
                if (rel == null || rel.contains("..") || !(rel.startsWith("files/") || rel.startsWith("shared_prefs/") || rel.startsWith("databases/"))) continue;
                paths.add(rel); total += q.getLong(1);
            }
        } catch (Exception e) { failed("The old Daanvia didn't answer (" + e.getClass().getSimpleName() + "). Open it once, then try again."); return; }
        File data = getDataDir();
        long free = data.getUsableSpace();
        if (free < total + 50L * 1048576) { failed("Not enough space: the move needs " + mb(total + 50L * 1048576) + " free and the phone has " + mb(free) + ". Free some space and try again."); return; }
        String oldA = "/data/user/0/" + OLD_APP, oldB = "/data/data/" + OLD_APP, here = data.getPath();
        long done = 0, shown = 0; byte[] buf = new byte[1 << 16];
        try {
            for (String rel : paths) {
                File out = new File(data, rel), tmp = new File(out.getPath() + ".moving");
                File dir = out.getParentFile(); if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("Can't create " + rel);
                Uri u = Uri.parse("content://" + AUTHORITY + "/file").buildUpon().appendQueryParameter("path", rel).build();
                try (InputStream in = cr.openInputStream(u); OutputStream o = new BufferedOutputStream(new FileOutputStream(tmp), 1 << 16)) {
                    if (in == null) throw new IOException("Can't read " + rel);
                    int n; while ((n = in.read(buf)) > 0) { o.write(buf, 0, n); done += n;
                        if (done - shown > 2 * 1048576) { shown = done; say("Moving… " + mb(done) + " of " + mb(total)); } }
                }
                // Small text files (settings, configs) could name the old app's folder; point them at this one.
                String name = out.getName();
                if (tmp.length() < 2 * 1048576 && (name.endsWith(".xml") || name.endsWith(".json") || name.endsWith(".txt"))) {
                    String text = new String(java.nio.file.Files.readAllBytes(tmp.toPath()), StandardCharsets.UTF_8);
                    if (text.contains(oldA) || text.contains(oldB)) java.nio.file.Files.write(tmp.toPath(), text.replace(oldA, here).replace(oldB, here).getBytes(StandardCharsets.UTF_8));
                }
                if (out.exists() && !out.delete()) throw new IOException("Can't replace " + rel);
                if (!tmp.renameTo(out)) throw new IOException("Can't save " + rel);
            }
            File m = marker(this); m.getParentFile().mkdirs();
            try (FileOutputStream o = new FileOutputStream(m)) { o.write("moved".getBytes(StandardCharsets.UTF_8)); }
        } catch (Exception e) { failed("The move stopped: " + e.getMessage() + ". Try again; nothing in the old Daanvia was changed."); return; }
        say("Done. Opening Daanvia…");
        // Restart, so settings and maps load fresh from the copies (a separate small process ends this one and reopens the app).
        runOnUiThread(() -> startActivity(new Intent(this, RestartActivity.class).putExtra("pid", android.os.Process.myPid())));
    }

    private static String mb(long b) { return String.format(Locale.US, "%d MB", Math.max(1, Math.round(b / 1048576.0))); }

    /** Install 0.8.1 over an older old app, so it can hand over its data. Android asks the rider to confirm. */
    private void updateOld() {
        if (!getPackageManager().canRequestPackageInstalls()) {
            status.setText("Allow Daanvia to install apps, then tap the button again.");
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()))); return;
        }
        working = true; primary.setEnabled(false); status.setText("Downloading the update for the old Daanvia…");
        Context c = getApplicationContext();
        new Thread(() -> {
            File apk = new File(getCacheDir(), "daanvia-0.8.1.apk"); PackageInstaller pi = null; int id = -1; boolean committed = false;
            try {
                download(HANDOVER_URL, apk);
                pi = getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(OLD_APP);
                id = pi.createSession(params);
                try (PackageInstaller.Session session = pi.openSession(id)) {
                    try (InputStream in = new FileInputStream(apk); OutputStream out = session.openWrite("daanvia-old.apk", 0, apk.length())) {
                        byte[] b = new byte[1 << 16]; int n; while ((n = in.read(b)) > 0) out.write(b, 0, n); session.fsync(out);
                    }
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                    session.commit(PendingIntent.getBroadcast(c, id, new Intent(c, AppUpdate.InstallReceiver.class).putExtra("old", true), flags).getIntentSender()); committed = true;
                    apk.delete();  // the install session holds its own copy
                }
                runOnUiThread(() -> { working = false; primary.setEnabled(true); status.setText("Confirm the update when Android asks, then come back here and tap Move."); });
            } catch (Exception e) {
                if (pi != null && id >= 0 && !committed) try { pi.abandonSession(id); } catch (Exception ignored) {}
                failed("The update didn't download. Try again with signal.");
            }
        }, "daanvia-old-update").start();
    }
    private static void download(String url, File to) throws Exception {
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection c = (HttpURLConnection)new URL(url).openConnection();
            c.setInstanceFollowRedirects(false); c.setConnectTimeout(10000); c.setReadTimeout(30000); c.setRequestProperty("User-Agent", "Daanvia/" + BuildInfo.VERSION);
            int code = c.getResponseCode();
            if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) { url = new URL(new URL(url), c.getHeaderField("Location")).toString(); c.disconnect(); continue; }
            if (code != 200) { c.disconnect(); throw new IOException("HTTP " + code); }
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            try (InputStream in = c.getInputStream(); OutputStream out = new BufferedOutputStream(new FileOutputStream(to), 1 << 16)) {
                byte[] b = new byte[1 << 16]; int n; while ((n = in.read(b)) > 0) { out.write(b, 0, n); sha.update(b, 0, n); }
            } finally { c.disconnect(); }
            StringBuilder hex = new StringBuilder(); for (byte x : sha.digest()) hex.append(String.format(Locale.US, "%02x", x));
            if (!HANDOVER_SHA256.equalsIgnoreCase(hex.toString())) { to.delete(); throw new IOException("damaged download"); }
            return;
        }
        throw new IOException("Too many redirects");
    }
}
