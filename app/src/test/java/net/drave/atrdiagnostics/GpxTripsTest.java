package net.drave.atrdiagnostics;
import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;
public class GpxTripsTest {
    private List<TripStop> parse(String xml)throws Exception{return GpxTrips.read(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));}
    @Test public void roundTripPreservesStopOrderNamesAndCoordinates()throws Exception{
        List<TripStop> stops=Arrays.asList(new TripStop("Fuel & food <east>",10.31,123.88),new TripStop("Finish",10.3,123.91));ByteArrayOutputStream out=new ByteArrayOutputStream();GpxTrips.write(out,"A & B",stops);
        List<TripStop> read=GpxTrips.read(new ByteArrayInputStream(out.toByteArray()));assertEquals(2,read.size());assertEquals(stops.get(0).name,read.get(0).name);assertEquals(123.91,read.get(1).lon,0);
    }
    @Test public void prefersRoutePointsOverUnorderedWaypoints()throws Exception{List<TripStop> p=parse("<gpx><wpt lat='0' lon='0'/><rte><rtept lat='10' lon='120'><name>Route</name></rtept></rte></gpx>");assertEquals(1,p.size());assertEquals("Route",p.get(0).name);}
    @Test public void rejectsExternalEntitiesAndTracksAndInvalidCoordinates()throws Exception{
        for(String bad:new String[]{"<!DOCTYPE gpx [<!ENTITY x SYSTEM 'file:///secret'>]><gpx>&x;</gpx>","<gpx><trk><trkseg><trkpt lat='1' lon='2'/></trkseg></trk></gpx>","<gpx><wpt lat='NaN' lon='2'/></gpx>","<gpx><wpt lat='91' lon='2'/></gpx>"}){try{parse(bad);fail(bad);}catch(Exception expected){}}
    }
    @Test public void rejectsTooManyStops()throws Exception{StringBuilder b=new StringBuilder("<gpx>");for(int i=0;i<=TripStop.MAX;i++)b.append("<wpt lat='1' lon='2'/>");b.append("</gpx>");try{parse(b.toString());fail();}catch(Exception expected){assertTrue(expected.getMessage().contains("20"));}}
    @Test public void nearbySearchExcludesPrivateAndUnrelatedPlaces()throws Exception{
        String json="{\"elements\":[{\"lat\":10,\"lon\":123,\"tags\":{\"amenity\":\"fuel\",\"name\":\"A\"}},{\"lat\":10,\"lon\":123,\"tags\":{\"amenity\":\"fuel\",\"access\":\"private\"}},{\"lat\":10,\"lon\":123,\"tags\":{\"amenity\":\"school\"}}]}";
        assertEquals(1,NearbyStops.parse(json,true).size());assertTrue(NearbyStops.parse(json,false).isEmpty());
    }
}
