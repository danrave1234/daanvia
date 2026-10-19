package net.drave.atrdiagnostics;
import org.junit.Test;import static org.junit.Assert.*;import java.util.*;
public class RideUiTest {
    private static long utc(int y,int mo,int d,int h,int mi){Calendar c=Calendar.getInstance(TimeZone.getTimeZone("UTC"));c.clear();c.set(y,mo-1,d,h,mi);return c.getTimeInMillis();}
    @Test public void cebuNoonIsDayAndLateEveningIsNight(){assertFalse(SunClock.isNight(10.3,123.9,utc(2026,10,19,4,0)));assertTrue(SunClock.isNight(10.3,123.9,utc(2026,10,19,14,0)));}
    @Test public void sunsetFlipsAroundSixInManila(){assertFalse(SunClock.isNight(14.6,121.0,utc(2026,10,19,9,20)));assertTrue(SunClock.isNight(14.6,121.0,utc(2026,10,19,10,30)));}
    @Test public void davaoMorningIsDay(){assertFalse(SunClock.isNight(7.07,125.6,utc(2026,6,1,0,30)));}
    @Test public void searchPrefersNamePrefixThenNearest(){
        SearchIndex i=new SearchIndex(Arrays.asList("Wealthbank Ayala","Ayala Center Cebu","Seda Ayala Malls","Ayala Center Cebu","Carbon Market"),new double[]{10.32,10.318,10.33,10.0,10.29},new double[]{123.90,123.905,123.905,123.0,123.90});
        int[] r=i.query("ayala",10.3157,123.8854,10);
        assertEquals(4,r.length);assertEquals("Ayala Center Cebu",i.names[r[0]]);assertEquals(10.318,i.lat[r[0]],1e-9);
        assertEquals("Ayala Center Cebu",i.names[r[1]]);assertEquals(10.0,i.lat[r[1]],1e-9);assertEquals("Wealthbank Ayala",i.names[r[2]]);
    }
    @Test public void searchKeepsEveryBranchButMergesTheSameSpot(){
        SearchIndex i=new SearchIndex(Arrays.asList("Chowking","Chowking","Chowking"),new double[]{10.30,10.30005,10.35},new double[]{123.89,123.89,123.89});
        assertEquals(2,i.query("chowking",10.3,123.89,10).length);
    }
    @Test public void searchFindsAPlaceInTheAreaNamed(){
        SearchIndex i=new SearchIndex(Arrays.asList("Guadalupe","Chowking","Chowking","Chowking","Puregold Guadalupe"),
            new double[]{10.32288,10.31609,10.2981,10.3396,10.3237},new double[]{123.88394,123.88561,123.8955,123.9249,123.8837});
        List<SearchIndex.Hit> h=i.search("chowking guadalupe",10.3,123.9,10);
        assertEquals(1,h.size());assertEquals("Chowking",i.names[h.get(0).i]);assertEquals(10.31609,i.lat[h.get(0).i],1e-9);assertEquals("Guadalupe",h.get(0).near);
        assertEquals(1,i.search("guadalupe chowking",10.3,123.9,10).size());
        assertEquals("Puregold Guadalupe",i.names[i.search("puregold guadalupe",10.3,123.9,10).get(0).i]);
    }
    @Test public void onlineResultsReadNameAndStreetLine() throws Exception{
        String json="{\"features\":[{\"geometry\":{\"coordinates\":[123.8856107,10.3160895]},\"properties\":{\"name\":\"Chowking\",\"street\":\"M. Velez Street\",\"district\":\"Guadalupe\",\"city\":\"Cebu\"}},"
            +"{\"geometry\":{\"coordinates\":[121.0,14.5]},\"properties\":{\"street\":\"Ayala Avenue\",\"housenumber\":\"6750\",\"city\":\"Makati\"}}]}";
        List<OnlineSearch.Place> p=OnlineSearch.parse(json);
        assertEquals(2,p.size());assertEquals("Chowking",p.get(0).name);assertEquals("M. Velez Street, Guadalupe, Cebu",p.get(0).address);assertEquals(10.3160895,p.get(0).lat,1e-9);
        assertEquals("6750 Ayala Avenue",p.get(1).name);assertEquals("Makati",p.get(1).address);
    }
    @Test public void roughFixesWaitBeforeReplacingSharpOnes(){
        assertTrue(Here.better(500,1000,999,0,1000));
        assertFalse(Here.better(800,2000,8,1000,3000));
        assertTrue(Here.better(800,12000,8,1000,12000));
        assertTrue(Here.better(10,2000,25,1000,2000));
        assertFalse(Here.better(5,500,25,1000,2000));
    }
    @Test public void searchNeedsEveryWordAndIgnoresAccents(){
        SearchIndex i=new SearchIndex(Arrays.asList("Osmeña Boulevard","Osmena Circle","Fuente Pension House"),new double[]{10,10,10},new double[]{123,123,123});
        assertEquals(2,i.query("osmena",10,123,10).length);assertEquals(1,i.query("osmena boul",10,123,10).length);assertEquals(0,i.query("",10,123,10).length);
    }
    @Test public void maneuverTypesMapToTurnIcons(){
        assertEquals(IconDrawable.RIGHT,Maneuvers.icon(10));assertEquals(IconDrawable.LEFT,Maneuvers.icon(15));assertEquals(IconDrawable.FLAG,Maneuvers.icon(4));
        assertEquals(IconDrawable.SLIGHT_RIGHT,Maneuvers.icon(9));assertEquals(IconDrawable.UTURN,Maneuvers.icon(12));assertEquals(IconDrawable.ROUNDABOUT,Maneuvers.icon(26));assertEquals(IconDrawable.STRAIGHT,Maneuvers.icon(8));
    }
    @Test public void distanceAndTimeLabelsMatchTheDashboardRounding(){assertEquals("350 m",Gabi.distance(347));assertEquals("1.2 km",Gabi.distance(1234));assertEquals("16 min",Gabi.minutes(960));assertEquals("1 h 5 min",Gabi.minutes(3900));}
}
