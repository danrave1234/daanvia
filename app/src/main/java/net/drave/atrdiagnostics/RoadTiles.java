package net.drave.atrdiagnostics;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * A downloaded area's roads, cut so the map never holds them all at once. Handing a whole area's road file (tens of
 * megabytes) to the map engine cost about 600 MB of memory. Instead the main roads (motorway to tertiary) stay in one
 * small file for the whole area, and every other street and path is filed into squares of {@link #CELL} degrees
 * (about 2 km), of which the map only loads the ones on screen. Built once per download, next to the area's files.
 * The same squares index the road lines the bike display draws.
 */
final class RoadTiles {
    static final double CELL = 0.02;
    static final String DIR = "road-tiles", MAJOR = "roads-major.geojson", DONE = "done";
    private RoadTiles() {}

    static boolean major(String kind) { return kind.contains("motorway") || kind.contains("trunk") || kind.contains("primary") || kind.contains("secondary") || kind.contains("tertiary"); }
    static long key(int x, int y) { return ((long)x << 32) ^ (y & 0xffffffffL); }
    static int cell(double v) { return (int)Math.floor(v / CELL); }

    /** True when the tiles were built from this exact roads file. */
    static boolean ready(File area) {
        File roads = new File(area, "roads.geojson"), done = new File(new File(area, DIR), DONE);
        if (!roads.isFile() || !done.isFile()) return false;
        try { return stamp(roads).equals(AreaStore.readFile(done).trim()); } catch (IOException e) { return false; }
    }
    private static String stamp(File roads) { return roads.length() + ":" + roads.lastModified(); }

    /** Collects features while the roads file is read, then writes the main-roads file and one file per square. */
    static final class Writer {
        private final File area; private final String from; private final ByteArrayOutputStream majors = new ByteArrayOutputStream(1 << 20);
        private final HashMap<Long, ByteArrayOutputStream> cells = new HashMap<>();
        Writer(File area) { this.area = area; from = stamp(new File(area, "roads.geojson")); }
        void add(String name, String kind, float[] xy, int n) {
            if (n < 4) return;
            StringBuilder b = new StringBuilder(32 + n * 9);
            b.append("{\"type\":\"Feature\",\"properties\":{\"kind\":").append(org.json.JSONObject.quote(kind));
            if (!name.isEmpty()) b.append(",\"name\":").append(org.json.JSONObject.quote(name));
            b.append("},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[");
            double minLat = 90, maxLat = -90, minLon = 180, maxLon = -180;
            for (int i = 0; i + 1 < n; i += 2) {
                if (i > 0) b.append(',');
                b.append('[').append(String.format(Locale.US, "%.5f", xy[i + 1])).append(',').append(String.format(Locale.US, "%.5f", xy[i])).append(']');
                minLat = Math.min(minLat, xy[i]); maxLat = Math.max(maxLat, xy[i]); minLon = Math.min(minLon, xy[i + 1]); maxLon = Math.max(maxLon, xy[i + 1]);
            }
            b.append("]}}");
            byte[] bytes = b.toString().getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream out = major(kind) ? majors : cells.computeIfAbsent(key(cell((minLon + maxLon) / 2), cell((minLat + maxLat) / 2)), k -> new ByteArrayOutputStream(16 << 10));
            if (out.size() > 0) out.write(',');
            out.write(bytes, 0, bytes.length);
        }
        void finish() throws IOException {
            File dir = new File(area, DIR), tmp = new File(area, DIR + ".building");
            // The area was removed or updated while its roads were being read: these tiles belong to nothing now.
            if (!area.isDirectory() || !from.equals(stamp(new File(area, "roads.geojson")))) throw new IOException("Area changed while building road tiles");
            AreaStore.delete(tmp); if (!tmp.mkdirs()) throw new IOException("Cannot write road tiles");
            try (OutputStream o = new BufferedOutputStream(new FileOutputStream(new File(tmp, MAJOR)), 1 << 16)) {
                o.write("{\"type\":\"FeatureCollection\",\"features\":[".getBytes(StandardCharsets.UTF_8)); majors.writeTo(o); o.write("]}".getBytes(StandardCharsets.UTF_8));
            }
            for (Map.Entry<Long, ByteArrayOutputStream> e : cells.entrySet()) {
                int x = (int)(e.getKey() >> 32), y = (int)(long)e.getKey();
                try (OutputStream o = new BufferedOutputStream(new FileOutputStream(new File(tmp, x + "_" + y)), 1 << 15)) { e.getValue().writeTo(o); }
            }
            try (OutputStream o = new FileOutputStream(new File(tmp, DONE))) { o.write(from.getBytes(StandardCharsets.UTF_8)); }
            AreaStore.delete(dir); if (!tmp.renameTo(dir)) throw new IOException("Cannot save road tiles");
        }
    }

    /** The streets in the squares covering this box, as one GeoJSON collection; null if more than {@code maxCells} squares. */
    static String collection(File tiles, double minLat, double minLon, double maxLat, double maxLon, int maxCells) {
        int x0 = cell(minLon), x1 = cell(maxLon), y0 = cell(minLat), y1 = cell(maxLat);
        if ((long)(x1 - x0 + 1) * (y1 - y0 + 1) > maxCells) return null;
        StringBuilder b = new StringBuilder(1 << 16).append("{\"type\":\"FeatureCollection\",\"features\":[");
        boolean any = false; byte[] buf = new byte[1 << 15];
        for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) {
            File f = new File(tiles, x + "_" + y); if (!f.isFile()) continue;
            try (InputStream in = new FileInputStream(f)) {
                ByteArrayOutputStream o = new ByteArrayOutputStream((int)f.length()); int n; while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                if (o.size() == 0) continue; if (any) b.append(','); b.append(o.toString("UTF-8")); any = true;
            } catch (IOException ignored) {}
        }
        return b.append("]}").toString();
    }

    /** Road lines filed by square, so the bike display only looks at roads near the rider. */
    static final class Index {
        private final HashMap<Long, ArrayList<NavSession.Road>> cells = new HashMap<>();
        void add(NavSession.Road r) {
            for (int x = cell(r.minLon); x <= cell(r.maxLon); x++) for (int y = cell(r.minLat); y <= cell(r.maxLat); y++) cells.computeIfAbsent(key(x, y), k -> new ArrayList<>()).add(r);
        }
        /** Roads touching the box, each once. */
        List<NavSession.Road> near(double minLat, double minLon, double maxLat, double maxLon) {
            int x0 = cell(minLon), x1 = cell(maxLon), y0 = cell(minLat), y1 = cell(maxLat);
            ArrayList<NavSession.Road> out = new ArrayList<>(); Set<NavSession.Road> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) { List<NavSession.Road> l = cells.get(key(x, y)); if (l != null) for (NavSession.Road r : l) if (seen.add(r)) out.add(r); }
            return out;
        }
    }
}
