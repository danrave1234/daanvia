package net.drave.atrdiagnostics;
import org.junit.Test;import static org.junit.Assert.*;
import java.io.*;import java.nio.charset.StandardCharsets;import java.nio.file.Files;import java.util.*;
public class RoadTilesTest {
    private static File area() throws IOException {
        File dir = Files.createTempDirectory("area").toFile();
        String roads = "{\"type\":\"FeatureCollection\",\"features\":["
            + "{\"type\":\"Feature\",\"properties\":{\"name\":\"EDSA\",\"kind\":\"primary\"},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[121.03,14.55],[121.04,14.56]]}},"
            + "{\"type\":\"Feature\",\"properties\":{\"name\":\"Jupiter \\\"St\\\"\",\"kind\":\"residential\"},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[121.031,14.551],[121.032,14.552]]}},"
            + "{\"type\":\"Feature\",\"properties\":{\"name\":\"\",\"kind\":\"service\"},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[121.101,14.651],[121.102,14.652]]}}]}";
        Files.write(new File(dir, "roads.geojson").toPath(), roads.getBytes(StandardCharsets.UTF_8));
        return dir;
    }
    private static float[] xy(double... latLon) { float[] f = new float[latLon.length]; for (int i = 0; i < f.length; i++) f[i] = (float)latLon[i]; return f; }

    @Test public void mainRoadsStayWholeAndStreetsAreFiledBySquare() throws Exception {
        File dir = area(); assertFalse(RoadTiles.ready(dir));
        RoadTiles.Writer w = new RoadTiles.Writer(dir);
        w.add("EDSA", "primary", false, false, 0, xy(14.55, 121.03, 14.56, 121.04), 4);
        w.add("Jupiter \"St\"", "residential", true, false, 1, xy(14.551, 121.031, 14.552, 121.032), 4);
        w.add("", "service", false, true, -1, xy(14.651, 121.101, 14.652, 121.102), 4);
        w.finish();
        assertTrue(RoadTiles.ready(dir));
        File tiles = new File(dir, RoadTiles.DIR);
        String major = new String(Files.readAllBytes(new File(tiles, RoadTiles.MAJOR).toPath()), StandardCharsets.UTF_8);
        assertTrue(major.contains("EDSA")); assertFalse(major.contains("Jupiter"));
        org.json.JSONObject near = new org.json.JSONObject(RoadTiles.collection(tiles, 14.54, 121.02, 14.56, 121.04, 50));
        assertEquals(1, near.getJSONArray("features").length());
        org.json.JSONObject props = near.getJSONArray("features").getJSONObject(0).getJSONObject("properties");
        assertEquals("Jupiter \"St\"", props.getString("name"));
        assertEquals("an overpass keeps its bridge and layer for the map", 1, props.getInt("bridge"));
        assertEquals(1, props.getInt("layer"));
        assertFalse(major.contains("\"bridge\"") || major.contains("\"tunnel\""));
        assertEquals("both small streets in the wider box", 2, new org.json.JSONObject(RoadTiles.collection(tiles, 14.54, 121.02, 14.70, 121.20, 500)).getJSONArray("features").length());
        assertNull("too many squares", RoadTiles.collection(tiles, 10, 120, 15, 125, 50));
    }
    @Test public void changedRoadsFileMeansTilesAreRebuilt() throws Exception {
        File dir = area(); RoadTiles.Writer w = new RoadTiles.Writer(dir); w.add("EDSA", "primary", false, false, 0, xy(14.55, 121.03, 14.56, 121.04), 4); w.finish();
        assertTrue(RoadTiles.ready(dir));
        Files.write(new File(dir, "roads.geojson").toPath(), "{\"type\":\"FeatureCollection\",\"features\":[]}".getBytes(StandardCharsets.UTF_8));
        assertFalse(RoadTiles.ready(dir));
    }
    @Test public void indexFindsEachNearbyRoadOnce() {
        RoadTiles.Index index = new RoadTiles.Index();
        NavSession.Road longRoad = new NavSession.Road(xy(14.50, 121.00, 14.60, 121.10), "Long", "primary");
        NavSession.Road far = new NavSession.Road(xy(15.50, 122.00, 15.51, 122.01), "Far", "residential");
        index.add(longRoad); index.add(far);
        List<NavSession.Road> near = index.near(14.54, 121.04, 14.56, 121.06);
        assertEquals(1, near.size()); assertSame(longRoad, near.get(0));
        assertEquals(1, index.near(14.40, 120.90, 14.70, 121.20).size());
    }
}
