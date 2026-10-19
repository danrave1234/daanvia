package net.drave.atrdiagnostics;

import android.content.Context;
import java.io.File;
import java.util.*;

/**
 * Keeps Daanvia's storage tidy. Runs quietly once per launch (never during a ride) and on "Free up space":
 * half-finished map downloads, an installed update's APK, routing copies for removed areas, the old built-in
 * Cebu copy, and old support reports. Downloaded areas, saved places and settings are never touched.
 */
public final class Housekeeping {
    private Housekeeping() {}

    /** Bytes removed. Call off the main thread. */
    public static long run(Context c, boolean everything) {
        long freed = 0;
        File cache = c.getCacheDir(), files = c.getFilesDir();
        // map downloads that never finished, or areas that were mid-merge when the app closed
        freed += remove(new File(cache, "area-download"));
        File[] regions = AreaStore.root(c).listFiles();
        if (regions != null) for (File d : regions) if (d.getName().endsWith(".building") || d.getName().endsWith(".unpacking")) freed += remove(d);
        // an app update that is already installed (or older than this version)
        File[] apks = new File(cache, "updates").listFiles();
        if (apks != null) for (File a : apks) {
            if (everything || !a.getName().endsWith(".apk") || AppUpdate.compare(versionOf(a.getName()), BuildInfo.VERSION) <= 0) freed += remove(a);
        }
        // routing copies for packs that are no longer installed
        Set<String> installed = new HashSet<>();
        if (regions != null) for (File d : regions) installed.add(d.getName());
        File[] engines = new File(files, "engines").listFiles();
        if (engines != null) for (File e : engines) if (!installed.contains(e.getName())) freed += remove(e);
        // the copy of the first version's built-in Cebu map, now that maps are downloaded instead
        freed += remove(new File(files, "cebu-v1"));
        File[] legacy = files.listFiles((dir, name) -> name.startsWith("region-"));
        if (legacy != null) for (File l : legacy) if (!assetExists(c, "regions/" + l.getName().substring(7) + "/region.json")) freed += remove(l);
        // support reports and leftovers in the cache
        freed += remove(new File(cache, "atr-report.json"));
        if (everything) { File[] k = cache.listFiles(); if (k != null) for (File x : k) if (!x.getName().equals("updates")) freed += remove(x); }
        return freed;
    }

    /** Space Daanvia uses: downloaded maps, and everything else (app data and cache). */
    public static long[] usage(Context c) {
        long maps = AreaStore.diskBytes(AreaStore.root(c));
        long other = AreaStore.diskBytes(c.getFilesDir()) - maps + AreaStore.diskBytes(c.getCacheDir());
        return new long[]{maps, Math.max(0, other)};
    }

    /** "daanvia-0.5.1-arm64-v8a.apk" → "0.5.1". */
    static String versionOf(String apkName) {
        String v = apkName.startsWith("daanvia-") ? apkName.substring(8) : apkName;
        for (String end : new String[]{"-arm64-v8a.apk", "-armeabi-v7a.apk", "-x86_64.apk", "-x86.apk", ".apk"}) if (v.endsWith(end)) return v.substring(0, v.length() - end.length());
        return v;
    }
    private static boolean assetExists(Context c, String path) { try { c.getAssets().open(path).close(); return true; } catch (Exception e) { return false; } }
    private static long remove(File f) { if (!f.exists()) return 0; long n = AreaStore.diskBytes(f); AreaStore.delete(f); return n; }
}
