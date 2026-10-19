package net.drave.atrdiagnostics;
import org.junit.Test;import static org.junit.Assert.*;import java.util.*;
public class ParkingSearchTest {
    private static final int MON=0,SAT=5,SUN=6;
    @Test public void roundTheClock(){assertEquals(OpeningHours.State.OPEN,OpeningHours.at("24/7",MON,600).state);assertEquals("Open 24 hours",OpeningHours.at("24/7",SUN,0).label);}
    @Test public void weekdayAndWeekendRules(){String h="Mo-Fr 08:00-20:00; Sa,Su 10:00-22:00";
        assertEquals("Open until 8 PM",OpeningHours.at(h,MON,9*60).label);
        assertEquals("Closed · opens 10 AM",OpeningHours.at(h,SAT,9*60).label);
        assertEquals("Closed · opens tomorrow 8 AM",OpeningHours.at(h,MON,21*60).label);}
    @Test public void laterRuleOverridesAndOffDays(){String h="Mo-Su 07:00-23:00; Su off";assertEquals(OpeningHours.State.CLOSED,OpeningHours.at(h,SUN,12*60).state);assertEquals(OpeningHours.State.OPEN,OpeningHours.at(h,SAT,12*60).state);}
    @Test public void pastMidnightAndSplitShifts(){assertEquals("Open until 2 AM",OpeningHours.at("18:00-02:00",MON,23*60).label);assertEquals(OpeningHours.State.OPEN,OpeningHours.at("18:00-02:00",SAT,60).state);
        assertEquals(OpeningHours.State.CLOSED,OpeningHours.at("08:00-12:00,13:00-17:00",MON,12*60+30).state);assertEquals("Open until 5 PM",OpeningHours.at("08:00-12:00,13:00-17:00",MON,14*60).label);}
    @Test public void unreadableHoursAreNotGuessed(){assertEquals(OpeningHours.State.UNKNOWN,OpeningHours.at("sunrise-sunset",MON,600).state);assertEquals("Hours not listed",OpeningHours.at("",MON,600).label);}
    @Test public void lotsNearAPlaceListMotorcycleAndPublicFirst(){
        Lots l=new Lots("[{\"id\":\"w1\",\"lat\":10.3130,\"lon\":123.9168,\"kind\":\"car\",\"acc\":\"private\"},{\"id\":\"n2\",\"lat\":10.3129,\"lon\":123.9170,\"kind\":\"moto\"},{\"id\":\"w3\",\"lat\":10.3110,\"lon\":123.9175,\"kind\":\"car\",\"acc\":\"customers\",\"typ\":\"multi-storey\"},{\"id\":\"w4\",\"lat\":10.33,\"lon\":123.95,\"kind\":\"car\"}]");
        List<Lots.Lot> near=l.near(10.3127172,123.9165048,300);
        assertEquals(3,near.size());assertEquals("Motorcycle parking",near.get(0).title());assertEquals("Parking building for customers",near.get(1).title());assertEquals("Private parking lot",near.get(2).title());
        assertEquals("Motorcycles",near.get(0).summary());assertTrue(near.get(2).summary().contains("Not public"));}
    @Test public void liveCheckReadsStillMappedAndRemoved() throws Exception{
        LiveCheck.Result r=LiveCheck.parse("{\"elements\":[{\"type\":\"way\",\"id\":1,\"timestamp\":\"2024-03-02T10:00:00Z\",\"tags\":{\"amenity\":\"parking\",\"opening_hours\":\"24/7\"}}]}");
        assertTrue(r.exists);assertEquals("2024-03-02",r.edited);assertEquals("24/7",Lots.Lot.fromTags("w1",10,123,r.tags).hours);
        assertFalse(LiveCheck.parse("{\"elements\":[]}").exists);}
    @Test public void subPlacesGroupUnderTheMallEvenWhenRankedFirst(){
        String[] n={"SM City Cebu PUJ Terminal","Ayala Center Cebu","SM City Cebu","Botejyu SM City Cebu"};double[] la={10.3106,10.318,10.3127,10.3120},lo={123.9181,123.905,123.9165,123.9170};
        int[] parent=SearchIndex.parents(n,la,lo);assertEquals(2,parent[0]);assertEquals(-1,parent[1]);assertEquals(-1,parent[2]);assertEquals(2,parent[3]);
        assertArrayEquals(new int[]{2,0,3,1},SearchIndex.grouped(parent));}
    @Test public void parkingIsNamedAfterItsPlace(){
        Lots l=new Lots("[{\"id\":\"a\",\"lat\":10.31,\"lon\":123.91,\"kind\":\"car\",\"acc\":\"customers\"},{\"id\":\"b\",\"lat\":10.31,\"lon\":123.91,\"kind\":\"moto\"},{\"id\":\"c\",\"lat\":10.31,\"lon\":123.91,\"name\":\"North Wing Parking\"},{\"id\":\"d\",\"lat\":10.31,\"lon\":123.91,\"name\":\"SM City Cebu Parking\"},{\"id\":\"e\",\"lat\":10.31,\"lon\":123.91,\"acc\":\"private\"}]");
        List<Lots.Lot> all=l.near(10.31,123.91,50);Map<String,Lots.Lot> by=new HashMap<>();for(Lots.Lot x:all)by.put(x.id,x);
        assertEquals("Allson's parking for customers",by.get("a").placeName("Allson's"));
        assertEquals("Allson's motorcycle parking",by.get("b").placeName("Allson's"));
        assertEquals("SM City Cebu · North Wing Parking",by.get("c").placeName("SM City Cebu"));
        assertEquals("SM City Cebu Parking",by.get("d").placeName("SM City Cebu"));
        assertEquals("Allson's private parking",by.get("e").placeName("Allson's"));
        assertEquals("Parking lot for customers",by.get("a").placeName(null));
        assertEquals("Allson's parking for customers",Lots.renamed("Parking lot for customers · Allson's"));
        assertEquals("Chowking",Lots.renamed("Chowking"));
        assertEquals("SM City Cebu · North Wing Parking",Lots.renamed("SM City Cebu · North Wing Parking"));}
}
