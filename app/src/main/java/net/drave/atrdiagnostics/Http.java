package net.drave.atrdiagnostics;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Every web request Daanvia makes (map and update downloads, search, Overpass, log upload) goes through here, so they
 * all say who they are the same way and handle redirects, short reads and checksums alike. Redirects are followed by
 * Android itself: GitHub's release downloads hop from https to https, never to plain http. ShareLinks keeps its own
 * loop, because it must look at every hop before following it. Call off the main thread.
 */
final class Http {
    private Http() {}
    /** OpenStreetMap services ask apps to name themselves and a way to reach the developer. */
    static final String AGENT = "Daanvia/" + BuildInfo.VERSION + " (github.com/danrave1234/daanvia)";

    /** The server answered, but not with 200 (a 404 means the file isn't published). */
    static class Status extends IOException { final int code; Status(int code) { super("Server error " + code); this.code = code; } }
    /** The download's sha256 didn't match: never kept. */
    static final class Damaged extends IOException { Damaged() { super("The download was damaged. Try again."); } }
    interface Progress { void update(long done, long total); }

    /** A connection with Daanvia's name, the caller's timeouts and any "Name", "value" header pairs. Not yet sent. */
    static HttpURLConnection open(String url, int connectMs, int readMs, String... headers) throws IOException {
        HttpURLConnection c = (HttpURLConnection)new URL(url).openConnection();
        c.setInstanceFollowRedirects(true); c.setConnectTimeout(connectMs); c.setReadTimeout(readMs); c.setRequestProperty("User-Agent", AGENT);
        for (int i = 0; i + 1 < headers.length; i += 2) c.setRequestProperty(headers[i], headers[i + 1]);
        return c;
    }

    /** GET into memory: a 200 answer of at most {@code maxBytes}, else an IOException ({@link Status} for an HTTP error). */
    static byte[] get(String url, int connectMs, int readMs, long maxBytes, String... headers) throws IOException {
        return body(open(url, connectMs, readMs, headers), maxBytes);
    }
    static String getText(String url, int connectMs, int readMs, long maxBytes, String... headers) throws IOException {
        return new String(get(url, connectMs, readMs, maxBytes, headers), StandardCharsets.UTF_8);
    }
    /** A form POST with one field, {@code data=...}: how Overpass asks to be queried (some servers refuse a GET with 406). */
    static String postForm(String url, String data, int connectMs, int readMs, long maxBytes, String... headers) throws IOException {
        HttpURLConnection c = open(url, connectMs, readMs, headers);
        try { send(c, "application/x-www-form-urlencoded; charset=UTF-8", ("data=" + URLEncoder.encode(data, "UTF-8")).getBytes(StandardCharsets.UTF_8)); }
        catch (IOException e) { c.disconnect(); throw e; }
        return new String(body(c, maxBytes), StandardCharsets.UTF_8);
    }
    /** POST {@code body} and return the response code; the answer itself isn't read. */
    static int post(String url, String type, byte[] body, int connectMs, int readMs, String... headers) throws IOException {
        HttpURLConnection c = open(url, connectMs, readMs, headers);
        try { send(c, type, body); return c.getResponseCode(); } finally { c.disconnect(); }
    }
    private static void send(HttpURLConnection c, String type, byte[] body) throws IOException {
        c.setRequestMethod("POST"); c.setDoOutput(true); c.setRequestProperty("Content-Type", type); c.setFixedLengthStreamingMode(body.length);
        try (OutputStream o = c.getOutputStream()) { o.write(body); }
    }
    private static byte[] body(HttpURLConnection c, long maxBytes) throws IOException {
        try {
            int code = c.getResponseCode(); if (code != 200) throw new Status(code);
            long total = c.getContentLengthLong(); ByteArrayOutputStream o = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] b = new byte[1 << 16]; int n;
                while ((n = in.read(b)) > 0) { if (o.size() + n > maxBytes) throw new IOException("The answer is too large"); o.write(b, 0, n); }
            }
            // A connection closed early by a proxy or a dropped signal ends the stream without an error.
            if (total > 0 && o.size() != total) throw new IOException("The download was cut short. Try again.");
            return o.toByteArray();
        } finally { c.disconnect(); }
    }

    /**
     * Download to {@code to}: written as "to.part", checked against {@code sha256} (when given) and only then renamed, so
     * a file at {@code to} is always whole. A failed, cut-short or damaged download leaves nothing behind. Setting
     * {@code cancel} stops it at the next read. {@code progress} hears every 256 KB (not at the end).
     */
    static void download(String url, File to, String sha256, AtomicBoolean cancel, Progress progress, int connectMs, int readMs) throws IOException {
        File part = part(to); boolean ok = false;
        try {
            HttpURLConnection c = open(url, connectMs, readMs);
            MessageDigest sha = digest(); long total, done = 0, last = 0;
            try {
                int code = c.getResponseCode(); if (code != 200) throw new Status(code);
                total = c.getContentLengthLong();
                try (InputStream in = c.getInputStream(); OutputStream out = new BufferedOutputStream(new FileOutputStream(part), 1 << 16)) {
                    byte[] b = new byte[1 << 16]; int n;
                    while ((n = in.read(b)) > 0) {
                        if (cancel != null && cancel.get()) throw new IOException("Download cancelled");
                        out.write(b, 0, n); sha.update(b, 0, n);
                        done += n; if (progress != null && done - last > 256 * 1024) { last = done; progress.update(done, total); }
                    }
                }
            } finally { c.disconnect(); }
            if (total > 0 && done != total) throw new IOException("The download was cut short. Try again.");
            finish(part, to, sha256, hex(sha.digest())); ok = true;
        } finally { if (!ok) part.delete(); }
    }
    static File part(File to) { return new File(to.getPath() + ".part"); }
    /** Puts a finished ".part" file in place if its checksum matches (no expected sum: always), else deletes it. */
    static void finish(File part, File to, String expected, String actual) throws IOException {
        if (expected != null && !expected.isEmpty() && !expected.equalsIgnoreCase(actual)) { part.delete(); throw new Damaged(); }
        if ((to.exists() && !to.delete()) || !part.renameTo(to)) { part.delete(); throw new IOException("Not enough storage"); }
    }

    static String sha256(File f) throws IOException {
        MessageDigest d = digest();
        try (InputStream in = new FileInputStream(f)) { byte[] b = new byte[1 << 16]; int n; while ((n = in.read(b)) > 0) d.update(b, 0, n); }
        return hex(d.digest());
    }
    private static MessageDigest digest() { try { return MessageDigest.getInstance("SHA-256"); } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); } }
    static String hex(byte[] d) { StringBuilder s = new StringBuilder(d.length * 2); for (byte x : d) s.append(String.format(Locale.US, "%02x", x)); return s.toString(); }
    /** All of a stream as UTF-8 text. Leaves the stream open: a zip is read entry by entry. */
    static String readAll(InputStream in) throws IOException { ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] b = new byte[1 << 16]; int n; while ((n = in.read(b)) > 0) o.write(b, 0, n); return o.toString("UTF-8"); }
}
