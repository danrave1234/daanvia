package net.drave.atrdiagnostics;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A complete route option, independent of UI and navigation progress. */
final class RoutePlan {
    final List<double[]> shape;
    final JSONArray maneuvers;
    final double[] cumulative;
    final int[] legEnds;
    final double meters, seconds;
    final String via;
    final boolean ferry, toll;
    private final String geometryKey;

    private RoutePlan(JSONObject trip) throws JSONException {
        ArrayList<double[]> points = new ArrayList<>();
        JSONArray steps = new JSONArray(), legs = trip.getJSONArray("legs");
        legEnds=new int[legs.length()];
        StringBuilder key = new StringBuilder();
        Map<String, Double> streets = new LinkedHashMap<>();
        for (int n = 0; n < legs.length(); n++) {
            JSONObject leg = legs.getJSONObject(n);
            String encoded = leg.getString("shape");
            List<double[]> line = GeoMath.decode(encoded);
            if (line.size() < 2) throw new JSONException("Empty route leg");
            int skip = !points.isEmpty() && GeoMath.distance(points.get(points.size() - 1)[0],
                points.get(points.size() - 1)[1], line.get(0)[0], line.get(0)[1]) < 2 ? 1 : 0;
            int offset = points.size() - skip;
            points.addAll(line.subList(skip, line.size()));
            legEnds[n]=points.size()-1;
            key.append(encoded).append(';');
            JSONArray ms = leg.getJSONArray("maneuvers");
            for (int i = 0; i < ms.length(); i++) {
                JSONObject m = new JSONObject(ms.getJSONObject(i).toString());
                m.put("begin_shape_index", m.optInt("begin_shape_index") + offset);
                m.put("end_shape_index", m.optInt("end_shape_index") + offset);
                steps.put(m);
                JSONArray names = m.optJSONArray("street_names");
                if (names != null && names.length() > 0) {
                    String name = names.optString(0).trim();
                    if (!name.isEmpty()) streets.put(name, streets.getOrDefault(name, 0d) + m.optDouble("length", 0));
                }
            }
        }
        if (points.size() < 2) throw new JSONException("No route geometry");
        shape = Collections.unmodifiableList(points);
        maneuvers = steps;
        cumulative = new double[points.size()];
        for (int i = 1; i < points.size(); i++) cumulative[i] = cumulative[i - 1]
            + GeoMath.distance(points.get(i - 1)[0], points.get(i - 1)[1], points.get(i)[0], points.get(i)[1]);
        meters = cumulative[cumulative.length - 1];
        JSONObject summary = trip.getJSONObject("summary");
        seconds = summary.getDouble("time");
        if (!Double.isFinite(seconds) || seconds < 0) throw new JSONException("Invalid route time");
        ferry = summary.optBoolean("has_ferry"); toll = summary.optBoolean("has_toll");
        String longest = ""; double length = -1;
        for (Map.Entry<String, Double> e : streets.entrySet()) if (e.getValue() > length) { longest = e.getKey(); length = e.getValue(); }
        via = longest; geometryKey = key.toString();
    }

    static List<RoutePlan> options(JSONObject response) throws JSONException {
        ArrayList<RoutePlan> out = new ArrayList<>();
        out.add(new RoutePlan(response.getJSONObject("trip")));
        JSONArray alternatives = response.optJSONArray("alternates");
        if (alternatives != null) for (int i = 0; i < alternatives.length() && out.size() < 3; i++) {
            try {
                RoutePlan candidate = new RoutePlan(alternatives.getJSONObject(i).getJSONObject("trip"));
                boolean duplicate = false;
                for (RoutePlan plan : out) if (plan.geometryKey.equals(candidate.geometryKey)) { duplicate = true; break; }
                if (!duplicate) out.add(candidate);
            } catch (JSONException | IllegalArgumentException malformedAlternative) { /* Keep the valid primary route. */ }
        }
        return Collections.unmodifiableList(out);
    }
}
