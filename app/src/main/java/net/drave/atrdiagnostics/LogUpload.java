package net.drave.atrdiagnostics;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/**
 * Sends Daanvia's diagnostics log to the developer (site/log-worker), so a dropped bike display or a clock that won't
 * set can be looked at without the rider copying a report. On by default during the beta; Settings › Support turns it
 * off.
 *
 * Lines collect in an outbox file (at most 1 MB, oldest dropped) and go out every couple of minutes while something is
 * being logged, and straight away when the display drops or a ride ends. Without signal they wait for the next try.
 * What is sent: the log lines (already free of coordinates, destinations, passwords and device addresses), a random id
 * this install made for itself, the app version and the phone model.
 */
final class LogUpload {
    private LogUpload() {}

    private static final String ENDPOINT = "https://daanvia-logs.danravekeh123.workers.dev/v1/logs";
    private static final String KEY = "dv-logs-7f3c9a2e5b814d06";
    private static final int MAX_OUTBOX = 1024 * 1024, KEEP_OUTBOX = 800 * 1024, MAX_SEND = 200 * 1024;  // under the server's 256 KB after JSON escaping
    // Routine lines wait up to five minutes (each send wakes the radio; the queue is a file, so a closed app loses nothing);
    // a drop or a ride's end sends at once (now).
    private static final long GAP_MS = 300_000;
    private static final ExecutorService io = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "log-upload"); t.setDaemon(true); return t; });
    private static volatile long lastTry;

    static boolean enabled(Context c) { return c.getSharedPreferences("ui", 0).getBoolean("send_logs", true); }
    static void setEnabled(Context c, boolean on) {
        c.getSharedPreferences("ui", 0).edit().putBoolean("send_logs", on).apply();
        if (!on) synchronized (LogUpload.class) { outbox(c).delete(); sending(c).delete(); }
    }

    /**
     * Last scrub before anything leaves the phone: whatever reached the log (a crash message, a share link) loses
     * coordinates, device addresses and the bike's Wi-Fi name (it often carries a serial or the owner's name) here, even if
     * the line was never cleaned where it was written.
     */
    static String scrub(String line) {
        return line.replaceAll("[-+]?[0-9]{1,3}\\.[0-9]{4,}", "[coordinate]").replaceAll("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}", "[device address]")
            .replaceAll("(?i)(network: )[^;\\n]+", "$1[hidden]");
    }
    /** Queue one log line (it should end with a newline), and send the outbox if the last try was a while ago. Never blocks the caller. */
    static void add(Context c, String line) {
        if (!enabled(c)) return;
        Context app = c.getApplicationContext();
        io.execute(() -> append(app, scrub(line)));
    }
    /** For the crash handler, which can't wait for the queue: written straight away. */
    static void addNow(Context c, String line) { if (enabled(c)) append(c.getApplicationContext(), scrub(line)); }
    private static void append(Context c, String line) {
        synchronized (LogUpload.class) {
            File f = outbox(c);
            try (FileOutputStream o = new FileOutputStream(f, true)) { o.write(line.getBytes(StandardCharsets.UTF_8)); } catch (IOException ignored) {}
            if (f.length() > MAX_OUTBOX) try { byte[] b = Files.readAllBytes(f.toPath()); try (FileOutputStream o = new FileOutputStream(f, false)) { o.write(b, b.length - KEEP_OUTBOX, KEEP_OUTBOX); } } catch (IOException ignored) {}
        }
        if (SystemClock.elapsedRealtime() - lastTry > GAP_MS) now(c);
    }

    /** Send what's queued now: the display dropped, or a ride ended. */
    static void now(Context c) {
        if (!enabled(c)) return;
        lastTry = SystemClock.elapsedRealtime();
        Context app = c.getApplicationContext();
        io.execute(() -> send(app));
    }

    private static File outbox(Context c) { return new File(c.getFilesDir(), "log-outbox.txt"); }
    private static File sending(Context c) { return new File(c.getFilesDir(), "log-sending.txt"); }

    /** Moves the outbox aside, then sends it in pieces; a piece that fails stays for the next try. */
    private static void send(Context c) {
        File out = sending(c);
        synchronized (LogUpload.class) {
            File box = outbox(c);
            if (!out.exists()) { if (!box.exists() || box.length() == 0) return; if (!box.renameTo(out)) return; }
        }
        try {
            byte[] all = Files.readAllBytes(out.toPath());
            int from = 0;
            while (from < all.length) {
                int to = Math.min(all.length, from + MAX_SEND);
                // Cut at a line break so no line is split between uploads.
                if (to < all.length) for (int i = to - 1; i > from; i--) if (all[i] == '\n') { to = i + 1; break; }
                if (!enabled(c)) { out.delete(); return; }  // turned off while sending: nothing more goes out
                post(c, new String(all, from, to - from, StandardCharsets.UTF_8));
                from = to;
                if (from < all.length) { try (FileOutputStream o = new FileOutputStream(out, false)) { o.write(all, from, all.length - from); } }
            }
            out.delete();
        } catch (Exception ignored) {
            // No signal or the server is busy: the rest stays in log-sending.txt and goes with the next try.
        }
    }

    private static void post(Context c, String text) throws Exception {
        JSONObject body = new JSONObject().put("install", install(c)).put("version", BuildInfo.VERSION)
            .put("model", Build.MANUFACTURER + " " + Build.MODEL).put("android", Build.VERSION.SDK_INT).put("text", text);
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        int code = Http.post(ENDPOINT, "application/json", bytes, 10000, 20000, "X-Daanvia-Key", KEY);
        // Refused for good (too big, bad key): dropped, or that piece would block every later log. Busy: tried again later.
        if (code == 408 || code == 429 || code >= 500) throw new IOException("Upload not taken now: " + code);
    }

    /** A random id this install made for itself: tells uploads from different phones apart, and nothing else. */
    static String install(Context c) {
        android.content.SharedPreferences p = c.getSharedPreferences("ui", 0);
        String id = p.getString("log_install", null);
        if (id == null) { id = UUID.randomUUID().toString(); p.edit().putString("log_install", id).apply(); }
        return id;
    }
}
