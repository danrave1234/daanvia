package net.drave.atrdiagnostics;
import org.junit.Test;import static org.junit.Assert.*;import java.util.*;
public class RegionPackTest {
    // Same boxes as tools/packs.json: [minLon,minLat,maxLon,maxLat].
    private static NavSession.Region pack(String id,double[]... areas){double[] b={180,90,-180,-90};for(double[] a:areas){b[0]=Math.min(b[0],a[0]);b[1]=Math.min(b[1],a[1]);b[2]=Math.max(b[2],a[2]);b[3]=Math.max(b[3],a[3]);}return new NavSession.Region(id,id,b,areas,null,null);}
    private static final NavSession.Region CEBU=pack("cebu",new double[]{123.60,10.10,124.08,10.62}),NCR=pack("ncr",new double[]{120.85,14.25,121.25,14.85}),
        LUZON=pack("luzon",new double[]{119.50,12.10,124.50,21.30},new double[]{116.90,7.50,121.20,12.50},new double[]{122.60,11.70,124.30,12.60}),
        VISAYAS=pack("visayas",new double[]{121.70,9.00,126.20,12.70}),MINDANAO=pack("mindanao",new double[]{119.20,4.40,126.70,9.95},new double[]{125.30,9.90,126.30,10.60});
    private static final List<NavSession.Region> ALL=Arrays.asList(LUZON,VISAYAS,MINDANAO,NCR,CEBU);
    @Test public void cityPackWinsOverItsIslandPack(){assertSame(NCR,NavSession.Region.pick(ALL,14.5826,120.9787,14.5547,121.0244));assertSame(CEBU,NavSession.Region.pick(ALL,10.3157,123.8854,10.2934,123.9019));}
    @Test public void tripLeavingTheCityUsesTheIslandPack(){assertSame(LUZON,NavSession.Region.pick(ALL,14.5995,120.9842,16.4023,120.5960));assertSame(VISAYAS,NavSession.Region.pick(ALL,10.3157,123.8854,10.7202,122.5621));}
    @Test public void tripAcrossPacksIsRejected(){assertNull(NavSession.Region.pick(ALL,10.3157,123.8854,7.0731,125.6128));assertNull(NavSession.Region.pick(Arrays.asList(CEBU),10.3157,123.8854,14.5995,120.9842));}
    @Test public void secondAreaOfAPackCounts(){assertTrue(LUZON.contains(9.7392,118.7353));assertSame(LUZON,NavSession.Region.at(ALL,9.7392,118.7353));assertTrue(MINDANAO.contains(10.2,125.6));assertSame(MINDANAO,NavSession.Region.at(ALL,8.0,125.0));}
    @Test public void overlapGoesToTheSmallerPack(){assertSame(VISAYAS,NavSession.Region.at(ALL,10.2,125.6));}
    @Test public void gapBetweenAreasIsOutside(){assertFalse(LUZON.contains(11.0,123.5));assertFalse(MINDANAO.contains(10.3,123.9));assertNull(NavSession.Region.at(ALL,18.0,128.0));}
    @Test public void packWithoutAreasUsesItsBounds(){NavSession.Region r=new NavSession.Region("x","X",new double[]{123.6,10.1,124.08,10.62},null,null,null);assertTrue(r.contains(10.3,123.9));assertFalse(r.contains(10.7,123.9));assertEquals(1,r.areas.length);}
    @Test public void equalPacksKeepTheFirstListed(){NavSession.Region download=pack("download",new double[]{123.60,10.10,124.08,10.62});assertSame(download,NavSession.Region.at(Arrays.asList(download,CEBU),10.3,123.9));}
}
