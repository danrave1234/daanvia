package net.drave.atrdiagnostics;

import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a place shared from Google Maps (or any geo: link) into a name and coordinates, so riders can
 * keep their own pins (for example a private "Balay ni Drave") as offline bookmarks. Short links need one
 * online redirect lookup; everything else is parsed locally.
 */
public final class ShareLinks {
    private ShareLinks() {}

    public static final class Place {
        public final String name; public final double lat, lon; public final String unresolved;
        Place(String name, double lat, double lon, String unresolved) { this.name = name; this.lat = lat; this.lon = lon; this.unresolved = unresolved; }
        public boolean located() { return unresolved == null && !Double.isNaN(lat); }
    }

    private static final Pattern URL = Pattern.compile("(https?://\\S+|geo:\\S+)", Pattern.CASE_INSENSITIVE);
    /** Loose text needs 3+ decimals so zoom levels and prices are not read as coordinates; explicit parameters do not. */
    private static final String NUM = "(-?\\d{1,3}\\.\\d{3,})", ANY = "(-?\\d{1,3}(?:\\.\\d+)?)";
    private static final Pattern DATA = Pattern.compile("!3d" + NUM + "!4d" + NUM);
    private static final Pattern AT = Pattern.compile("@" + NUM + "," + NUM);
    private static final Pattern PARAM = Pattern.compile("[?&](?:q|ll|query|destination|center|daddr|sll)=(?:loc:)?" + ANY + "(?:,|%2C)\\s*" + ANY, Pattern.CASE_INSENSITIVE);
    private static final Pattern GEO = Pattern.compile("geo:" + ANY + "," + ANY, Pattern.CASE_INSENSITIVE);
    private static final Pattern GEO_Q = Pattern.compile("[?&]q=" + ANY + "(?:,|%2C)" + ANY + "(?:\\(([^)]*)\\))?", Pattern.CASE_INSENSITIVE);
    private static final Pattern PLAIN = Pattern.compile("(?<![\\d.])" + NUM + "\\s*,\\s*" + NUM + "(?![\\d.])");
    private static final Pattern PLACE = Pattern.compile("/maps/place/([^/@?]+)");
    private static final Pattern CONTINUE = Pattern.compile("[?&]continue=([^&]+)");

    /** Parse shared text. Returns null if it holds no link or coordinates at all. */
    public static Place parse(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        text = text.trim();
        Matcher u = URL.matcher(text);
        String url = u.find() ? u.group(1) : null;
        String name = (url == null ? "" : text.replace(url, "")).replaceAll("\\s+", " ").trim();
        if (url == null) {
            double[] c = coords(text);
            String label = PLAIN.matcher(text).replaceAll(" ").replaceAll("\\s+", " ").trim();
            return c == null ? null : new Place(label.isEmpty() ? "Shared place" : label, c[0], c[1], null);
        }
        return fromUrl(url, name);
    }

    static Place fromUrl(String url, String name) {
        Matcher cont = CONTINUE.matcher(url);
        if (url.contains("consent.google") && cont.find()) url = decode(cont.group(1));
        if (name == null || name.isEmpty()) { Matcher p = PLACE.matcher(url); if (p.find()) name = decode(p.group(1).replace('+', ' ')).trim(); }
        Matcher gq = GEO_Q.matcher(url);
        if (url.toLowerCase().startsWith("geo:") && gq.find() && gq.group(3) != null && (name == null || name.isEmpty())) name = decode(gq.group(3)).trim();
        double[] c = coords(url);
        if (name == null || name.isEmpty()) name = "Shared place";
        if (c != null) return new Place(name, c[0], c[1], null);
        return new Place(name, Double.NaN, Double.NaN, url);
    }

    /** Coordinates in the order Google Maps encodes them most precisely: place data, query, viewport, geo, plain text. */
    static double[] coords(String s) {
        for (Pattern p : new Pattern[]{DATA, PARAM, GEO_Q, AT, GEO, PLAIN}) {
            Matcher m = p.matcher(s);
            while (m.find()) {
                double lat = Double.parseDouble(m.group(1)), lon = Double.parseDouble(m.group(2));
                if (Math.abs(lat) <= 90 && Math.abs(lon) <= 180 && !(lat == 0 && lon == 0)) return new double[]{lat, lon};
            }
        }
        return null;
    }

    private static String decode(String s) { try { return URLDecoder.decode(s, StandardCharsets.UTF_8.name()); } catch (Exception e) { return s; } }

    /** Follow a short link's redirects (online, once) until a URL or page reveals coordinates. Call off the main thread. */
    public static Place resolve(Place p) throws Exception {
        String url = p.unresolved;
        for (int hop = 0; hop < 8 && url != null; hop++) {
            Place found = fromUrl(url, p.name.equals("Shared place") ? "" : p.name);
            if (found.located()) return found;
            HttpURLConnection c = (HttpURLConnection)new URL(url).openConnection();
            c.setInstanceFollowRedirects(false); c.setConnectTimeout(8000); c.setReadTimeout(8000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) Daanvia");
            int code = c.getResponseCode();
            String next = c.getHeaderField("Location");
            if (code >= 300 && code < 400 && next != null) { url = new URL(new URL(url), next).toString(); c.disconnect(); continue; }
            String body = "";
            try (java.io.InputStream in = c.getInputStream()) {
                byte[] buf = new byte[8192]; java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(); int n;
                while ((n = in.read(buf)) > 0 && out.size() < 400_000) out.write(buf, 0, n);
                body = out.toString(StandardCharsets.UTF_8.name());
            } finally { c.disconnect(); }
            double[] coords = coords(body.replace("\\u003d", "=").replace("\\u0026", "&"));
            if (coords != null) return new Place(found.name, coords[0], coords[1], null);
            break;
        }
        return p;
    }
}
