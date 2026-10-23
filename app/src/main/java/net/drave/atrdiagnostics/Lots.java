package net.drave.atrdiagnostics;

import org.json.*;
import java.util.*;

/** Parking lots from lots.json, for listing the parking that belongs to a searched place. Labels use only mapped facts. */
public final class Lots {
    /** A name saved by an older version ("Parking lot for customers · Allson's"), turned around the way {@link Lot#placeName} names it now. */
    public static String renamed(String saved) {
        int dot = saved.lastIndexOf(" · "); if (dot <= 0) return saved;
        String lot = saved.substring(0, dot), venue = saved.substring(dot + 3).trim(), l = lot.toLowerCase(Locale.ROOT);
        if (venue.isEmpty() || !l.contains("parking") || !Lot.generic(lot)) return saved;
        String what = l.contains("motorcycle") ? " motorcycle parking" : " parking";
        return venue + (l.contains("private") ? " private" + what : what + (l.contains("customer") ? " for customers" : ""));
    }
    public static final class Lot {
        public final String id, name, kind, moto, access, hours, fee, charge, capacity, capacityMoto, operator, type; public final double lat, lon;
        public final String source,priceChecked;public final long verifiedAt;public final boolean entrance;
        Lot(JSONObject o) {
            id = o.optString("id"); name = o.optString("name"); kind = o.optString("kind", o.optBoolean("motorcycle")?"moto":"car"); moto = o.optString("moto"); access = o.optString("acc",o.optString("access"));
            hours = o.optString("hrs"); fee = o.optString("fee"); charge = o.optString("chg",o.optString("price_label")); capacity = o.optString("cap"); capacityMoto = o.optString("capm");
            operator = o.optString("op"); type = o.optString("typ"); lat = o.optDouble("lat"); lon = o.optDouble("lon");
            source=o.optString("source","OpenStreetMap");verifiedAt=o.optLong("verified_at",0);priceChecked=o.optString("price_checked");entrance=o.optBoolean("entrance_confirmed",false);
        }
        /** Build from live OpenStreetMap tags, using the same short keys as lots.json. */
        public static Lot fromTags(String id, double lat, double lon, JSONObject t) {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id).put("lat", lat).put("lon", lon).put("name", t.optString("name")).put("kind", "motorcycle_parking".equals(t.optString("amenity")) ? "moto" : "car")
                 .put("moto", t.optString("motorcycle", "motorcycle_parking".equals(t.optString("amenity")) ? "yes" : "")).put("acc", t.optString("access")).put("hrs", t.optString("opening_hours"))
                 .put("fee", t.optString("fee")).put("chg", t.optString("charge")).put("cap", t.optString("capacity")).put("capm", t.optString("capacity:motorcycle"))
                 .put("op", t.optString("operator")).put("typ", t.optString("parking")).put("price_checked",t.optString("check_date:charge"));
            } catch (JSONException ignored) {}
            return new Lot(o);
        }
        public boolean motorcycle() { return "moto".equals(kind) || "yes".equals(moto) || "designated".equals(moto); }
        public boolean noMotorcycles() { return "no".equals(moto); }
        public boolean restricted() { return access.equals("private") || access.equals("no") || access.equals("permit"); }

        /** "Motorcycle parking", "Private parking lot", or the mapped name. */
        public String title() {
            if (!name.isEmpty() && !name.equalsIgnoreCase("parking")) return name;
            String base = motorcycle() && "moto".equals(kind) ? "Motorcycle parking" : type.equals("multi-storey") ? "Parking building" : type.equals("underground") ? "Underground parking"
                : type.equals("street_side") || type.equals("lane") ? "Street parking" : "Parking lot";
            if (restricted()) return "Private " + base.substring(0, 1).toLowerCase(Locale.ROOT) + base.substring(1);
            if (access.equals("customers")) return base + " for customers";
            return base;
        }
        /**
         * The name to ride to or save: the place it belongs to first, so "Parking lot for customers" at Allson's reads
         * "Allson's parking for customers" in Recent, ride history and on the bike, instead of a dozen look-alike "Parking lot"s.
         * A lot with a name of its own keeps it, after the place: "SM City Cebu · North Wing parking".
         */
        public String placeName(String venue) {
            String t = title();
            if (venue == null || venue.trim().isEmpty() || t.toLowerCase(Locale.ROOT).contains(venue.trim().toLowerCase(Locale.ROOT))) return t;
            venue = venue.trim();
            if (!generic(t)) return venue + " · " + t;
            String what = motorcycle() && "moto".equals(kind) ? " motorcycle parking" : " parking";
            return venue + (restricted() ? " private" + what : what + (access.equals("customers") ? " for customers" : ""));
        }
        private static final java.util.Set<String> PLAIN = new java.util.HashSet<>(java.util.Arrays.asList("parking", "lot", "lots", "park", "area", "for", "the", "and", "of",
            "customers", "customer", "customer's", "guest", "guests", "visitor", "visitors", "client", "clients", "employees", "staff", "only", "public", "private", "paid", "pay",
            "free", "car", "cars", "motorcycle", "motorcycles", "motorbike", "bike", "space", "spaces", "building", "underground", "basement", "street", "open", "covered", "multi", "storey", "level"));
        /** Only describes a parking lot ("Parking lot for customers"), names nothing. */
        static boolean generic(String t) {
            for (String w : t.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}']+")) if (!w.isEmpty() && !PLAIN.contains(w)) return false;
            return true;
        }
        /** Short facts for a list row, in the order riders care about. */
        public String summary() {
            ArrayList<String> f = new ArrayList<>();
            if (motorcycle()) f.add("Motorcycles"); else if (noMotorcycles()) f.add("No motorcycles");
            if (!hours.isEmpty()) f.add(OpeningHours.now(hours).label);
            if (!charge.isEmpty()) f.add(charge); else if (fee.equals("yes")) f.add("Paid"); else if (fee.equals("no")) f.add("Free");
            if (restricted()) f.add("Not public"); else if (access.equals("customers")) f.add("Customers");
            return f.isEmpty() ? "Details not mapped yet" : String.join(" · ", f);
        }
    }

    private final List<Lot> lots;
    public Lots(String json) {
        ArrayList<Lot> out = new ArrayList<>();
        try { JSONArray a = new JSONArray(json); for (int i = 0; i < a.length(); i++) out.add(new Lot(a.getJSONObject(i))); } catch (JSONException ignored) {}
        lots = out;
    }
    public int size() { return lots.size(); }

    /** Lots within {@code meters} of a place, motorcycle-friendly and public first, then nearest. */
    public List<Lot> near(double lat, double lon, double meters) {
        ArrayList<Lot> out = new ArrayList<>();
        double dLat = meters / 111195, dLon = meters / (111195 * Math.cos(Math.toRadians(lat)));
        for (Lot l : lots) if (Math.abs(l.lat - lat) <= dLat && Math.abs(l.lon - lon) <= dLon && GeoMath.distance(lat, lon, l.lat, l.lon) <= meters) out.add(l);
        rank(out, lat, lon);
        return out;
    }
    /** Motorcycle parking first, then public before private, then nearest to the place. */
    public static void rank(List<Lot> list, double lat, double lon) {
        list.sort((a, b) -> {
            int ra = (a.motorcycle() ? 0 : 2) + (a.restricted() ? 1 : 0), rb = (b.motorcycle() ? 0 : 2) + (b.restricted() ? 1 : 0);
            if (ra != rb) return Integer.compare(ra, rb);
            int na = a.name.isEmpty() || a.name.equalsIgnoreCase("parking") ? 1 : 0, nb = b.name.isEmpty() || b.name.equalsIgnoreCase("parking") ? 1 : 0;
            return na != nb ? Integer.compare(na, nb) : Double.compare(GeoMath.distance(lat, lon, a.lat, a.lon), GeoMath.distance(lat, lon, b.lat, b.lon));
        });
    }
}
