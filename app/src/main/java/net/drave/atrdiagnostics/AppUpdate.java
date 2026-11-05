package net.drave.atrdiagnostics;

import android.app.PendingIntent;
import android.content.*;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * App updates from the GitHub release: checks for a newer version, downloads the APK for this phone's CPU inside the
 * app, checks its sha256 and hands it to Android's installer. Android always asks the rider to confirm; because every
 * build is signed with the same key it installs as an update, keeping saved places, maps and the bike pairing.
 */
public final class AppUpdate {
    public static final String LATEST = "https://api.github.com/repos/danrave1234/daanvia/releases/latest";

    public static final class Release {
        public final String version, notes, apkUrl, apkName, sha256; public final long bytes;
        Release(String v, String n, String u, String name, String sha, long b) { version = v; notes = n; apkUrl = u; apkName = name; sha256 = sha; bytes = b; }
        public String sizeLabel() { return Math.max(1, Math.round(bytes / 1048576.0)) + " MB"; }
    }
    public interface Listener { void onUpdate(State s); }
    public static final class State {
        public final Release release; public final long done; public final boolean checking, downloading, ready, upToDate; public final String error;
        State(Release r, long done, boolean checking, boolean downloading, boolean ready, boolean upToDate, String error) { release = r; this.done = done; this.checking = checking; this.downloading = downloading; this.ready = ready; this.upToDate = upToDate; this.error = error; }
        public int percent() { return release == null || release.bytes <= 0 ? 0 : (int)Math.min(100, done * 100 / release.bytes); }
        public boolean available() { return release != null && !upToDate; }
    }

    private static volatile State state; private static final List<Listener> listeners = new ArrayList<>(); private static Handler main;
    private static final AtomicBoolean busy = new AtomicBoolean();
    /** When the last install was handed to Android (elapsedRealtime), 0 once Android reports it failed or was cancelled. */
    private static volatile long committedAt;
    /**
     * Android's own "install?" screen, kept in case it couldn't be shown: Android ignores an activity started from the
     * background, which is where Daanvia is when a slow download ends after the rider has moved on. The next Install tap
     * shows it (see install).
     */
    private static volatile Intent pendingConfirm;
    /** Downloading or installing right now: its APK must stay. */
    static boolean busy() { return busy.get() || android.os.SystemClock.elapsedRealtime() - committedAt < 60000; }
    public static State state() { return state; }
    public static void listen(Listener l) { synchronized (listeners) { listeners.add(l); } }
    public static void unlisten(Listener l) { synchronized (listeners) { listeners.remove(l); } }
    private static synchronized Handler main() { if (main == null) main = new Handler(Looper.getMainLooper()); return main; }
    private static void post(State s) { state = s; main().post(() -> { List<Listener> copy; synchronized (listeners) { copy = new ArrayList<>(listeners); } for (Listener l : copy) l.onUpdate(s); }); }

    /** "0.5.10" > "0.5.9"; a leading "v" and any "-suffix" are ignored, and a pre-release sorts before its release. */
    public static int compare(String a, String b) {
        String[] x = clean(a).split("-", 2), y = clean(b).split("-", 2);
        String[] p = x[0].split("\\."), q = y[0].split("\\.");
        for (int i = 0; i < Math.max(p.length, q.length); i++) {
            int u = i < p.length ? num(p[i]) : 0, v = i < q.length ? num(q[i]) : 0;
            if (u != v) return Integer.compare(u, v);
        }
        if (x.length != y.length) return x.length == 1 ? 1 : -1;
        return x.length == 1 ? 0 : x[1].compareTo(y[1]);
    }
    private static String clean(String v) { v = v.trim(); return v.startsWith("v") || v.startsWith("V") ? v.substring(1) : v; }
    private static int num(String s) { try { return Integer.parseInt(s.replaceAll("\\D", "")); } catch (NumberFormatException e) { return 0; } }

    private static final String[] ABIS = {"arm64-v8a", "armeabi-v7a", "x86_64", "x86"};
    /** Pick the APK built for this phone's CPU from the release's files, else the one APK that runs on every phone. */
    static Release parse(String json, String[] abis) throws JSONException {
        JSONObject r = new JSONObject(json); JSONArray assets = r.optJSONArray("assets");
        String version = clean(r.optString("tag_name")); JSONObject pick = null;
        if (assets != null) for (String abi : abis) {
            for (int i = 0; i < assets.length() && pick == null; i++) { JSONObject a = assets.getJSONObject(i); String n = a.optString("name"); if (n.endsWith(".apk") && n.contains(abi)) pick = a; }
            if (pick != null) break;
        }
        if (pick == null && assets != null) for (int i = 0; i < assets.length() && pick == null; i++) {
            JSONObject a = assets.getJSONObject(i); String n = a.optString("name"); boolean universal = n.endsWith(".apk");
            for (String abi : ABIS) if (n.contains(abi)) universal = false;
            if (universal) pick = a;
        }
        if (pick == null) return new Release(version, r.optString("body"), null, null, "", 0);
        String digest = pick.optString("digest"); if (digest.startsWith("sha256:")) digest = digest.substring(7);
        return new Release(version, r.optString("body"), pick.getString("browser_download_url"), pick.getString("name"), digest, pick.optLong("size"));
    }

    /** Check GitHub for a newer version. With {@code quiet}, at most every 12 hours. Never during a ride. */
    public static void check(Context context, boolean quiet) {
        Context c = context.getApplicationContext();
        SharedPreferences prefs = c.getSharedPreferences("update", 0);
        // A newer version found by an earlier check is looked up again, so the update banner is back after a restart.
        String latest = prefs.getString("latest", ""); boolean knownNewer = !latest.isEmpty() && compare(latest, BuildInfo.VERSION) > 0;
        // (hourly once a newer version is known, so the banner is back after a restart without a request at every open)
        if (quiet && System.currentTimeMillis() - prefs.getLong("checked", 0) < (knownNewer ? 3600_000L : 12 * 3600_000L)) { if (state == null) restore(c); return; }
        if (NavSession.get(c).active || !busy.compareAndSet(false, true)) return;
        post(new State(null, 0, true, false, false, false, null));
        new Thread(() -> {
            try {
                Release r = parse(read(LATEST), Build.SUPPORTED_ABIS);
                prefs.edit().putLong("checked", System.currentTimeMillis()).putString("latest", r.version).apply();
                boolean newer = r.apkUrl != null && compare(r.version, BuildInfo.VERSION) > 0;
                File apk = file(c, r); boolean ready = newer && apk.isFile() && apk.length() == r.bytes;
                post(new State(r, ready ? r.bytes : 0, false, false, ready, !newer, null));
            } catch (Exception e) { post(new State(null, 0, false, false, false, false, quiet ? null : "Couldn’t check for updates. Try again with signal.")); }
            finally { busy.set(false); }
        }, "daanvia-update-check").start();
    }
    private static void restore(Context c) {
        String latest = c.getSharedPreferences("update", 0).getString("latest", "");
        if (!latest.isEmpty() && compare(latest, BuildInfo.VERSION) <= 0) state = new State(null, 0, false, false, false, true, null);
    }
    private static File file(Context c, Release r) { File d = new File(c.getCacheDir(), "updates"); d.mkdirs(); return new File(d, r.apkName == null ? "update.apk" : r.apkName); }

    /** Download the APK (verified) and open Android's install prompt. */
    public static void install(Context context) {
        State s = state; if (s == null || s.release == null || s.release.apkUrl == null) return;
        // Android's "install?" screen is already prepared (the download finished while Daanvia was in the background, where
        // it couldn't be shown): show it now, from the screen the rider is on.
        Intent confirm = pendingConfirm;
        if (confirm != null) { pendingConfirm = null; try { context.startActivity(confirm); } catch (Exception e) { committedAt = 0; } return; }
        // Android's prompt is up (or about to be): a second tap would open a second install.
        if (android.os.SystemClock.elapsedRealtime() - committedAt < 60000 || !busy.compareAndSet(false, true)) return;
        Context c = context.getApplicationContext(); Release r = s.release;
        if (r.sha256.isEmpty()) {
            busy.set(false);
            post(new State(r, 0, false, false, false, false, "This update can’t be verified. Download it from the website instead."));
            return;
        }
        if (!c.getPackageManager().canRequestPackageInstalls()) {
            busy.set(false);
            post(new State(r, 0, false, false, false, false, "Allow Daanvia to install updates, then tap Update again."));
            context.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + c.getPackageName())));
            return;
        }
        new Thread(() -> {
            File apk = file(c, r);
            try {
                if (!(apk.isFile() && apk.length() == r.bytes && Http.sha256(apk).equalsIgnoreCase(r.sha256))) download(r, apk);
                post(new State(r, r.bytes, false, false, true, false, null));
                // The APK's own package: an update of this app, or the new Daanvia (a separate app id) installed beside it.
                android.content.pm.PackageInfo info = c.getPackageManager().getPackageArchiveInfo(apk.getPath(), 0);
                installSession(c, apk, info != null ? info.packageName : c.getPackageName(), "daanvia.apk", true, new Intent(c, InstallReceiver.class));
                committedAt = android.os.SystemClock.elapsedRealtime();
            } catch (Exception e) {
                apk.delete();
                post(new State(r, 0, false, false, false, false, e instanceof IOException && e.getMessage() != null && !e.getMessage().contains("://") ? e.getMessage() : "The update didn’t download. Try again with signal."));
            } finally { busy.set(false); }
        }, "daanvia-update").start();
    }
    private static void download(Release r, File to) throws IOException {
        try { Http.download(r.apkUrl, to, r.sha256, null, (done, total) -> post(new State(r, done, false, true, false, false, null)), 10000, 30000); }
        catch (Http.Status e) { throw new IOException("The update isn’t available right now"); }
        catch (Http.Damaged e) { throw new IOException("The update was damaged. Try again."); }
    }
    private static String read(String url) throws IOException {
        try { return Http.getText(url, 10000, 15000, 4L << 20, "Accept", "application/vnd.github+json"); } catch (Http.Status e) { throw new IOException("No release"); }
    }

    /**
     * Hands an APK to Android's installer (for this app's update, and for MoveActivity's update of the old app). Android
     * reports to {@code result}. With {@code quiet} (Android 12+), an update of an app Daanvia installed may skip the
     * prompt. Returns once committed; the APK may then be deleted, as the session keeps its own copy.
     */
    static void installSession(Context c, File apk, String packageName, String name, boolean quiet, Intent result) throws IOException {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(packageName);
        if (quiet && Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int id = pi.createSession(params); boolean committed = false;
        try (PackageInstaller.Session session = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream out = session.openWrite(name, 0, apk.length())) {
                byte[] b = new byte[1 << 16]; int n; while ((n = in.read(b)) > 0) out.write(b, 0, n); session.fsync(out);
            }
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            session.commit(PendingIntent.getBroadcast(c, id, result, flags).getIntentSender()); committed = true;
        } finally {
            // A session that wasn't committed keeps its staged copy of the APK until it is abandoned.
            if (!committed) try { pi.abandonSession(id); } catch (Exception ignored) {}
        }
    }

    /** Android's installer reports here: show its confirmation, or surface a failure. */
    public static final class InstallReceiver extends BroadcastReceiver {
        public void onReceive(Context c, Intent i) {
            int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = Build.VERSION.SDK_INT >= 33 ? i.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class) : legacy(i);
                if (confirm == null) return;
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (!i.getBooleanExtra("old", false)) { pendingConfirm = confirm; committedAt = 0; State s = state; if (s != null && s.release != null) post(new State(s.release, s.release.bytes, false, false, true, false, null)); }
                c.startActivity(confirm);
            } else if (status != PackageInstaller.STATUS_SUCCESS) {
                if (i.getBooleanExtra("old", false)) return;  // the old app's handover install (MoveActivity), not Daanvia's own update
                pendingConfirm = null; committedAt = 0; State s = state;
                post(new State(s == null ? null : s.release, 0, false, false, false, false, status == PackageInstaller.STATUS_FAILURE_ABORTED ? "Update cancelled" : "The update couldn’t be installed"));
            }
        }
        @SuppressWarnings("deprecation") private static Intent legacy(Intent i) { return i.getParcelableExtra(Intent.EXTRA_INTENT); }
    }
}
