package net.drave.atrdiagnostics;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import org.json.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Offline areas the rider chooses, Google Maps style. The country is published as a grid of small cells (plus shared
 * base routing tiles) on the app's GitHub release. The rider frames a rectangle; every cell it touches is downloaded,
 * checked and merged into one area folder under files/regions, where NavSession finds it. Nothing downloads unless
 * the rider asks.
 * <p>Each map build is a generation with its own release ("maps-g20261101T000000Z") that never changes once
 * uploaded. maps/areas-current.json is the live generation's index; it names that release and lists its files with
 * sizes and sha256. A newer generation is uploaded first and areas-current.json switched last; the previous
 * generation's release stays, so a download that started from the old index finishes from it. Before the first
 * generation, and for app versions up to 0.8.0, maps/areas-index.json and its files sit in the maps release itself.
 * An area always comes from one index, and an updated area replaces the installed one only between rides.
 */
public final class AreaStore {
    /** Map areas live in their own "maps" release, so app releases carry only the APKs. */
    public static final String BASE = "https://github.com/danrave1234/daanvia/releases/download/maps/";
    /** Where maps come from. Debug builds may point at a local test server (prefs "areas"/"base"); release builds always use the GitHub release. */
    static String base(Context c) {
        boolean debuggable = (c.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        String o = debuggable ? c.getSharedPreferences("areas", 0).getString("base", "") : "";
        return o.isEmpty() ? BASE : o;
    }
    /** Above this much raw road data the merged area would not fit comfortably in a phone's memory. */
    public static final long MAX_ROADS = 60L * 1024 * 1024;

    public static final class Cell {
        public final String id, file, sha256, label; public final double[] box; public final long bytes, roadsBytes; public final String[] needs;
        Cell(JSONObject o) throws JSONException {
            id = o.getString("id"); file = o.getString("file"); sha256 = o.optString("sha256"); label = o.optString("label");
            JSONArray b = o.getJSONArray("box"); box = new double[]{b.getDouble(0), b.getDouble(1), b.getDouble(2), b.getDouble(3)};
            bytes = o.optLong("bytes"); roadsBytes = o.optLong("roads_bytes");
            JSONArray n = o.optJSONArray("needs"); needs = new String[n == null ? 0 : n.length()]; for (int i = 0; i < needs.length; i++) needs[i] = n.getString(i);
        }
        public boolean touches(double minLat, double minLon, double maxLat, double maxLon) { return box[0] < maxLon && box[2] > minLon && box[1] < maxLat && box[3] > minLat; }
    }
    public static final class Base { public final String file, sha256; public final long bytes; Base(JSONObject o) throws JSONException { file = o.getString("file"); sha256 = o.optString("sha256"); bytes = o.optLong("bytes"); } }
    public static final class Catalog {
        /** {@code generation} and {@code release} (where its files are) are "" for maps built before generations. */
        public final List<Cell> cells; public final Map<String, Base> base; public final String built, generation, release;
        Catalog(List<Cell> c, Map<String, Base> b, String built, String generation, String release) { cells = c; base = b; this.built = built; this.generation = generation; this.release = release; }
    }
    /** The live generation's index, and the index from before generations (the only one app versions up to 0.8.0 read). */
    static final String CURRENT = "areas-current.json", LEGACY = "areas-index.json";
    /**
     * Where a catalog's files are: next to the index ({@code base}, ending in "/maps/"), or for a generation in its own
     * release beside it, ".../maps-g20261101T000000Z/". Works the same for GitHub and a debug test server.
     */
    static String files(String base, Catalog cat) {
        if (cat.release.isEmpty()) return base;
        if (!cat.release.matches("maps-[A-Za-z0-9]+")) throw new IllegalArgumentException("Bad map release " + cat.release);
        String b = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return b.substring(0, b.lastIndexOf('/') + 1) + cat.release + "/";
    }
    /** What a framed rectangle would download. */
    public static final class Selection {
        public final List<Cell> cells; public final long bytes, roadsBytes; public final double widthKm, heightKm; public final String label;
        Selection(List<Cell> cells, long bytes, long roads, double w, double h, String label) { this.cells = cells; this.bytes = bytes; roadsBytes = roads; widthKm = w; heightKm = h; this.label = label; }
        public boolean tooBig() { return roadsBytes > MAX_ROADS; }
        public String sizeLabel() { return Math.max(1, Math.round(bytes / 1048576.0)) + " MB"; }
        public String kmLabel() { return "About " + Math.round(widthKm) + " × " + Math.round(heightKm) + " km"; }
    }

    public static Catalog parse(String json) throws JSONException {
        JSONObject j = new JSONObject(json); ArrayList<Cell> cells = new ArrayList<>(); HashMap<String, Base> base = new HashMap<>();
        JSONArray c = j.optJSONArray("cells"); if (c != null) for (int i = 0; i < c.length(); i++) cells.add(new Cell(c.getJSONObject(i)));
        JSONArray b = j.optJSONArray("base"); if (b != null) for (int i = 0; i < b.length(); i++) { Base x = new Base(b.getJSONObject(i)); base.put(x.file, x); }
        return new Catalog(cells, base, j.optString("built"), j.optString("generation"), j.optString("release"));
    }

    /** Cells touched by the framed rectangle, with download size (shared tiles counted once), road data size and km. */
    public static Selection select(Catalog cat, double minLat, double minLon, double maxLat, double maxLon, double centerLat, double centerLon) {
        ArrayList<Cell> out = new ArrayList<>(); LinkedHashSet<String> needs = new LinkedHashSet<>(); long bytes = 0, roads = 0;
        Cell middle = null; double best = Double.MAX_VALUE;
        for (Cell c : cat.cells) if (c.touches(minLat, minLon, maxLat, maxLon)) {
            out.add(c); bytes += c.bytes; roads += c.roadsBytes; needs.addAll(Arrays.asList(c.needs));
            double d = GeoMath.distance(centerLat, centerLon, (c.box[1] + c.box[3]) / 2, (c.box[0] + c.box[2]) / 2);
            if (!c.label.isEmpty() && d < best) { best = d; middle = c; }
        }
        for (String n : needs) { Base b = cat.base.get(n); if (b != null) bytes += b.bytes; }
        double w = GeoMath.distance(centerLat, minLon, centerLat, maxLon) / 1000, h = GeoMath.distance(minLat, centerLon, maxLat, centerLon) / 1000;
        return new Selection(out, bytes, roads, w, h, middle == null ? "" : middle.label);
    }

    // ---------------------------------------------------------------- catalog
    public static Catalog fetch(Context c) throws Exception {
        String from = base(c), json;
        try { json = new String(get(from + CURRENT, null, null, null), StandardCharsets.UTF_8); }
        catch (Missing none) { json = new String(get(from + LEGACY, null, null, null), StandardCharsets.UTF_8); }  // no generation published yet, or mid-switch
        Catalog cat = parse(json); c.getSharedPreferences("areas", 0).edit().putString("index", json).apply(); return cat;
    }
    /**
     * The squares that make up an installed area in a newer map. A busy square can be split into quarters in a newer
     * build, so when some of the area's squares are gone, every square that now lies inside its old ones is taken.
     */
    public static List<Cell> updateCells(Catalog cat, Area a) {
        ArrayList<Cell> cells = new ArrayList<>(); boolean all = true;
        for (String id : a.cells) { boolean found = false; for (Cell c : cat.cells) if (c.id.equals(id)) { cells.add(c); found = true; } all &= found; }
        if (!all) {
            cells.clear();
            for (Cell c : cat.cells) { double lon = (c.box[0] + c.box[2]) / 2, lat = (c.box[1] + c.box[3]) / 2;
                for (double[] b : a.boxes) if (lon >= b[0] && lon <= b[2] && lat >= b[1] && lat <= b[3]) { cells.add(c); break; } }
        }
        return cells;
    }
    /** Download an installed area again from a newer map. False when its squares can't be matched (then: remove and download it again). */
    public static boolean update(Context c, Catalog cat, Area a) {
        List<Cell> cells = updateCells(cat, a); if (cells.isEmpty()) return false;
        download(c, cat, cells, a.name, a.home, a); return true;
    }
    public static Catalog cached(Context c) {
        try { return parse(c.getSharedPreferences("areas", 0).getString("index", "{}")); } catch (JSONException e) { return new Catalog(Collections.emptyList(), Collections.emptyMap(), "", "", ""); }
    }

    // ---------------------------------------------------------------- installed areas
    public static final class Area {
        public final String id, name, built; public final File dir; public final double[] bounds; public final List<String> cells;
        /** The boxes of the cells it was made of, [minLon, minLat, maxLon, maxLat], and its home point [lat, lon] (null if none). */
        public final List<double[]> boxes = new ArrayList<>(); public final double[] home;
        Area(File dir, JSONObject r) throws JSONException {
            this.dir = dir; id = r.getString("id"); name = r.optString("display_name", id); built = r.optString("built");
            JSONArray b = r.getJSONArray("bounds"); bounds = new double[]{b.getDouble(0), b.getDouble(1), b.getDouble(2), b.getDouble(3)};
            ArrayList<String> c = new ArrayList<>(); JSONArray a = r.optJSONArray("cells"); if (a != null) for (int i = 0; i < a.length(); i++) c.add(a.getString(i)); cells = c;
            JSONArray x = r.optJSONArray("areas"); if (x != null) for (int i = 0; i < x.length(); i++) { JSONArray q = x.optJSONArray(i); if (q != null && q.length() == 4) boxes.add(new double[]{q.optDouble(0), q.optDouble(1), q.optDouble(2), q.optDouble(3)}); }
            JSONArray h = r.optJSONArray("home"); home = h != null && h.length() == 2 ? new double[]{h.optDouble(0), h.optDouble(1)} : null;
        }
        public long diskBytes() { return AreaStore.diskBytes(dir); }
        public String kmLabel() {
            double lat = (bounds[1] + bounds[3]) / 2, lon = (bounds[0] + bounds[2]) / 2;
            return Math.round(GeoMath.distance(lat, bounds[0], lat, bounds[2]) / 1000) + " × " + Math.round(GeoMath.distance(bounds[1], lon, bounds[3], lon) / 1000) + " km";
        }
        public boolean outdated(Catalog cat) { return !cat.built.isEmpty() && !built.isEmpty() && cat.built.compareTo(built) > 0; }
    }
    public static File root(Context c) { return new File(c.getFilesDir(), "regions"); }
    public static List<Area> installed(Context c) {
        ArrayList<Area> out = new ArrayList<>(); File[] dirs = root(c).listFiles(); if (dirs == null) return out;
        for (File d : dirs) if (d.getName().startsWith("area-")) try (InputStream in = new FileInputStream(new File(d, "region.json"))) { out.add(new Area(d, new JSONObject(readAll(in)))); } catch (Exception ignored) {}
        out.sort(Comparator.comparing(a -> a.name));
        return out;
    }
    /**
     * Remove an area. Its folder is first renamed out of the way in one step, so the app never sees half of it, and the
     * files (thousands of tiles) are deleted in the background instead of freezing the screen.
     */
    public static void remove(Context c, Area a) {
        File trash = trash(c, a.id); boolean moved = a.dir.renameTo(trash);
        NavSession.get(c).forget(a.id); NavSession.get(c).reload();
        File gone = moved ? trash : a.dir; new Thread(() -> delete(gone), "daanvia-remove").start();
    }
    /** Folders on their way out; Housekeeping clears any left behind. */
    static final String TRASH = "trash-";
    private static File trash(Context c, String id) { return new File(root(c), TRASH + id + "-" + System.nanoTime()); }
    public static void rename(Area a, String name) throws Exception {
        File f = new File(a.dir, "region.json"); JSONObject r = new JSONObject(readAll(new FileInputStream(f))); r.put("display_name", name);
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(r.toString(2).getBytes(StandardCharsets.UTF_8)); }
    }

    // ---------------------------------------------------------------- download
    public interface Listener { void onArea(State s); }
    public static final class State {
        public final String name, error; public final long done, total; public final boolean running, finished;
        /** Downloaded and checked; replaces the installed area when the ride ends. */
        public final boolean waiting;
        State(String name, long done, long total, boolean running, boolean finished, String error) { this(name, done, total, running, finished, error, false); }
        State(String name, long done, long total, boolean running, boolean finished, String error, boolean waiting) { this.name = name; this.done = done; this.total = total; this.running = running; this.finished = finished; this.error = error; this.waiting = waiting; }
        public int percent() { return total <= 0 ? 0 : (int)Math.min(100, done * 100 / total); }
        public String progressLabel() { return waiting ? "Ready · updates when the ride ends" : percent() + "% · " + Math.round(done / 1048576.0) + " of " + Math.max(1, Math.round(total / 1048576.0)) + " MB"; }
    }
    private static final List<Listener> listeners = new ArrayList<>();
    private static volatile State state; private static final AtomicBoolean cancel = new AtomicBoolean();
    private static Handler main;
    public static State state() { return state; }
    public static void listen(Listener l) { synchronized (listeners) { listeners.add(l); } }
    public static void unlisten(Listener l) { synchronized (listeners) { listeners.remove(l); } }
    public static void cancel() { cancel.set(true); }
    private static synchronized Handler main() { if (main == null) main = new Handler(Looper.getMainLooper()); return main; }
    private static void post(State s) { state = s; main().post(() -> { List<Listener> copy; synchronized (listeners) { copy = new ArrayList<>(listeners); } for (Listener l : copy) l.onArea(s); }); }

    /** Download the selection as a new area, or (with {@code replace}) refresh an existing one. One at a time. */
    public static synchronized void download(Context context, Catalog chosen, List<Cell> chosenCells, String name, double[] home, Area replace) {
        if (state != null && state.running) return;
        Context c = context.getApplicationContext(); cancel.set(false);
        post(new State(name, 0, 1, true, false, null));
        new Thread(() -> {
            // The squares may have been picked from the index saved on the phone. If the maps were rebuilt since, take
            // the newer build, square by square: an older generation's files are eventually deleted (and before
            // generations, files were replaced in place and no longer matched their checksums).
            Catalog cat = chosen; List<Cell> cells = chosenCells;
            try { Catalog fresh = fetch(c); if (newer(fresh, chosen)) { cat = fresh; cells = same(fresh, chosenCells); } } catch (Exception ignored) {}
            if (cells.isEmpty()) { post(new State(name, 0, 1, false, false, "The maps were just updated. Choose your area again.")); return; }
            LinkedHashSet<String> needs = new LinkedHashSet<>(); long total = 0;
            for (Cell x : cells) { total += x.bytes; needs.addAll(Arrays.asList(x.needs)); }
            for (String n : needs) { Base b = cat.base.get(n); if (b != null) total += b.bytes; }
            long all = total; post(new State(name, 0, all, true, false, null));
            String id = replace != null ? replace.id : "area-" + System.currentTimeMillis();
            File tmp = new File(root(c), id + ".building"), target = new File(root(c), id), cache = new File(c.getCacheDir(), "area-download");
            long[] done = {0}; Merger merge = null;
            try {
                delete(tmp); cache.mkdirs(); if (!tmp.mkdirs()) throw new IOException("Not enough storage");
                merge = new Merger(tmp);
                String from = files(base(c), cat);
                for (String n : needs) { Base b = cat.base.get(n); if (b == null) continue; File z = fetch(from, new File(cache, b.file), b.file, b.sha256, done, all, name); cancelled(); merge.add(z); z.delete(); }
                double[] bounds = {180, 90, -180, -90}; JSONArray areas = new JSONArray(), ids = new JSONArray();
                for (Cell x : cells) {
                    File z = fetch(from, new File(cache, x.file), x.file, x.sha256, done, all, name); cancelled(); merge.add(z); z.delete();
                    bounds[0] = Math.min(bounds[0], x.box[0]); bounds[1] = Math.min(bounds[1], x.box[1]); bounds[2] = Math.max(bounds[2], x.box[2]); bounds[3] = Math.max(bounds[3], x.box[3]);
                    areas.put(new JSONArray().put(x.box[0]).put(x.box[1]).put(x.box[2]).put(x.box[3])); ids.put(x.id);
                }
                merge.close(); merge = null; cancelled();
                if (!new File(tmp, "valhalla").isDirectory()) throw new IOException("The map file is incomplete");
                double[] h = home != null ? home : new double[]{(bounds[1] + bounds[3]) / 2, (bounds[0] + bounds[2]) / 2};
                JSONObject region = new JSONObject().put("id", id).put("display_name", name).put("bounds", new JSONArray().put(bounds[0]).put(bounds[1]).put(bounds[2]).put(bounds[3]))
                    .put("areas", areas).put("cells", ids).put("home", new JSONArray().put(h[0]).put(h[1])).put("built", cat.built).put("generation", cat.generation)
                    .put("source", "OpenStreetMap").put("license", "ODbL-1.0").put("attribution", "© OpenStreetMap contributors");
                JSONObject demo = demo(new File(tmp, "places.json"), h); if (demo != null) region.put("demo", demo);
                try (FileOutputStream o = new FileOutputStream(new File(tmp, "region.json"))) { o.write(region.toString(2).getBytes(StandardCharsets.UTF_8)); }
                // A ride reads the installed area's routing tiles and road squares as it goes: an update waits for the ride to end.
                NavSession nav = NavSession.get(c);
                if (target.exists() && nav.active) { post(new State(name, all, all, true, false, null, true)); awaitRideEnd(() -> nav.active, cancel, 3000); }
                nav.forget(id);
                File old = trash(c, id); boolean hadOld = target.exists();
                swap(tmp, target, old);
                if (hadOld) new Thread(() -> delete(old), "daanvia-remove").start();
                post(new State(name, all, all, false, true, null));
                NavSession.get(c).reload(id);
            } catch (Exception e) {
                if (merge != null) try { merge.close(); } catch (IOException ignored) {}
                delete(tmp);
                String why = cancel.get() ? "Download cancelled" : e instanceof IOException && e.getMessage() != null && !e.getMessage().contains("://") ? e.getMessage() : "Download failed. Check your connection and try again.";
                post(new State(name, 0, all, false, false, why));
            }
        }, "daanvia-area").start();
    }

    /** A later build than {@code than} (an empty "built" is oldest). */
    static boolean newer(Catalog cat, Catalog than) { return cat.built.compareTo(than.built) > 0; }
    interface Riding { boolean now(); }
    /** Returns once no ride is on; throws if {@code stop} is set meanwhile (the download was cancelled). */
    static void awaitRideEnd(Riding riding, AtomicBoolean stop, long pollMs) throws IOException, InterruptedException {
        while (riding.now()) { if (stop.get()) throw new IOException("Download cancelled"); Thread.sleep(pollMs); }
        if (stop.get()) throw new IOException("Download cancelled");
    }
    /** Puts {@code fresh} in place of {@code target} with renames only: the old copy goes to {@code aside} first and comes back if the swap fails. */
    static void swap(File fresh, File target, File aside) throws IOException {
        boolean hadOld = target.exists();
        if (hadOld && !target.renameTo(aside)) throw new IOException("Could not save the map");
        if (!fresh.renameTo(target)) { if (hadOld) aside.renameTo(target); throw new IOException("Could not save the map"); }
    }

    private static File fetch(String from, File to, String file, String sha256, long[] done, long total, String name) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256"); long start = done[0];
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(to), 1 << 16)) {
            get(from + file, out, sha, (d, t) -> { done[0] = start + d; post(new State(name, done[0], total, true, false, null)); });
        }
        if (!sha256.isEmpty() && !sha256.equalsIgnoreCase(hex(sha.digest()))) { to.delete(); throw new IOException("The download was damaged. Try again."); }
        return to;
    }

    /** A demo route for the area: from its middle to a named place about 3 km away. */
    static JSONObject demo(File places, double[] home) {
        try {
            JSONArray a = new JSONArray(readAll(new FileInputStream(places))); JSONObject best = null; double score = Double.MAX_VALUE;
            for (int i = 0; i < a.length(); i++) { JSONObject p = a.getJSONObject(i); double d = GeoMath.distance(home[0], home[1], p.optDouble("lat"), p.optDouble("lon")); if (Math.abs(d - 3000) < score) { score = Math.abs(d - 3000); best = p; } }
            if (best == null) return null;
            return new JSONObject().put("name", "Demo to " + best.optString("name")).put("from", new JSONArray().put(home[0]).put(home[1])).put("to", new JSONArray().put(best.getDouble("lat")).put(best.getDouble("lon")));
        } catch (Exception e) { return null; }
    }

    /**
     * Merges cell and base zips into one area folder: graph tiles keep their paths under valhalla/, and each data file
     * is joined by concatenating the cells' feature arrays. Points (places, parking, lots, gates) live in exactly one
     * cell; roads are cut at each cell's edge and run ~110 m past it, so next to a shared edge both cells' short
     * pieces are kept and simply draw over each other.
     */
    static final class Merger implements Closeable {
        private static final String[][] FILES = {{"roads.geojson", "{\"type\":\"FeatureCollection\",\"features\":[", "]}"}, {"land.geojson", "{\"type\":\"FeatureCollection\",\"features\":[", "]}"},
            {"water.geojson", "{\"type\":\"FeatureCollection\",\"features\":[", "]}"}, {"places.json", "[", "]"}, {"parking.json", "[", "]"}, {"lots.json", "[", "]"}, {"gates.json", "[", "]"}};
        private final File dir; private final Map<String, Writer> out = new HashMap<>(); private final Map<String, Boolean> any = new HashMap<>(); private final String root;
        Merger(File dir) throws IOException {
            this.dir = dir; root = dir.getCanonicalPath() + File.separator;
            for (String[] f : FILES) { Writer w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(new File(dir, f[0])), StandardCharsets.UTF_8), 1 << 16); w.write(f[1]); out.put(f[0], w); any.put(f[0], false); }
        }
        void add(File zip) throws IOException {
            try (ZipInputStream z = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))) {
                ZipEntry e; byte[] b = new byte[1 << 16];
                while ((e = z.getNextEntry()) != null) {
                    if (e.isDirectory()) continue;
                    String name = e.getName().replace('\\', '/');
                    if (name.startsWith("valhalla/")) {
                        File f = new File(dir, name); if (!f.getCanonicalPath().startsWith(root)) throw new IOException("Bad map file");
                        f.getParentFile().mkdirs();
                        try (OutputStream o = new BufferedOutputStream(new FileOutputStream(f), 1 << 16)) { int n; while ((n = z.read(b)) > 0) o.write(b, 0, n); }
                    } else if (out.containsKey(name)) {
                        String body = inner(readAll(z), name);
                        if (!body.isEmpty()) { Writer w = out.get(name); if (any.get(name)) w.write(','); w.write(body); any.put(name, true); }
                    }
                }
            }
        }
        /** The array contents between a file's wrapper, trimmed. */
        static String inner(String text, String name) {
            String t = text.trim(); int open = t.indexOf('['), close = t.lastIndexOf(']');
            if (open < 0 || close <= open) return "";
            return t.substring(open + 1, close).trim();
        }
        public void close() throws IOException { for (String[] f : FILES) { Writer w = out.get(f[0]); w.write(f[2]); w.close(); } }
    }

    // ---------------------------------------------------------------- io
    interface Progress { void update(long done, long total); }
    /** HTTP 404. */
    static final class Missing extends IOException { Missing() { super("That map isn’t available yet"); } }
    private static byte[] get(String url, OutputStream out, MessageDigest sha, Progress progress) throws Exception {
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection c = (HttpURLConnection)new URL(url).openConnection();
            c.setInstanceFollowRedirects(false); c.setConnectTimeout(10000); c.setReadTimeout(30000); c.setRequestProperty("User-Agent", "Daanvia/" + BuildInfo.VERSION);
            int code = c.getResponseCode();
            if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) { url = new URL(new URL(url), c.getHeaderField("Location")).toString(); c.disconnect(); continue; }
            if (code != 200) { c.disconnect(); if (code == 404) throw new Missing(); throw new IOException("Server error " + code); }
            long total = c.getContentLengthLong(), done = 0, last = 0;
            ByteArrayOutputStream mem = out == null ? new ByteArrayOutputStream() : null;
            try (InputStream in = c.getInputStream()) {
                byte[] b = new byte[1 << 16]; int n;
                while ((n = in.read(b)) > 0) {
                    if (cancel.get() && out != null) throw new IOException("Download cancelled");
                    if (sha != null) sha.update(b, 0, n);
                    if (out != null) out.write(b, 0, n); else mem.write(b, 0, n);
                    done += n; if (progress != null && done - last > 256 * 1024) { last = done; progress.update(done, total); }
                }
            } finally { c.disconnect(); }
            if (progress != null) progress.update(done, total);
            return mem == null ? null : mem.toByteArray();
        }
        throw new IOException("Too many redirects");
    }
    static String readAll(InputStream in) throws IOException { ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] b = new byte[1 << 16]; int n; while ((n = in.read(b)) > 0) o.write(b, 0, n); return o.toString("UTF-8"); }
    public static long diskBytes(File f) { if (f.isFile()) return f.length(); long s = 0; File[] k = f.listFiles(); if (k != null) for (File x : k) s += diskBytes(x); return s; }
    /** The squares of {@code picked} in a newer index: the same ids, or the quarters a busy square was split into. */
    static List<Cell> same(Catalog fresh, List<Cell> picked) {
        ArrayList<Cell> out = new ArrayList<>();
        for (Cell p : picked) {
            boolean found = false; for (Cell f : fresh.cells) if (f.id.equals(p.id)) { out.add(f); found = true; break; }
            if (!found) for (Cell f : fresh.cells) { double lon = (f.box[0] + f.box[2]) / 2, lat = (f.box[1] + f.box[3]) / 2;
                if (lon >= p.box[0] && lon <= p.box[2] && lat >= p.box[1] && lat <= p.box[3] && !out.contains(f)) out.add(f); }
        }
        return out;
    }
    private static void cancelled() throws IOException { if (cancel.get()) throw new IOException("Download cancelled"); }
    public static void delete(File f) { File[] k = f.listFiles(); if (k != null) for (File x : k) delete(x); f.delete(); }
    private static String hex(byte[] d) { StringBuilder s = new StringBuilder(); for (byte x : d) s.append(String.format(Locale.US, "%02x", x)); return s.toString(); }
}
