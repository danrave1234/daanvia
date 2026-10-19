package net.drave.atrdiagnostics;
import org.junit.Test;import static org.junit.Assert.*;import java.util.*;
public class GeoMathTest {
    @Test public void routePolylineUsesSixDecimalPrecision(){List<double[]> p=GeoMath.decode(encode(new double[][]{{10.3157,123.8854},{10.3162,123.8861},{10.2934,123.9019}}));assertEquals(3,p.size());assertEquals(10.3157,p.get(0)[0],.000001);assertEquals(123.9019,p.get(2)[1],.000001);}
    @Test public void truncatedPolylineCannotBecomeRoute(){try{GeoMath.decode("_");fail("truncated geometry accepted");}catch(IllegalArgumentException expected){}}
    @Test public void distanceProjectsOntoRoadSegment(){assertEquals(0,GeoMath.segmentDistance(10,123.5,new double[]{10,123},new double[]{10,124}),.01);assertTrue(GeoMath.segmentDistance(10.001,123.5,new double[]{10,123},new double[]{10,124})>100);}
    @Test public void footerInsetsDoNotStretchGeometry(){double[] r=GeoMath.safeRect(800,480,0,0,0,.1f);assertEquals(800,r[2]-r[0],.01);assertEquals(432,r[3]-r[1],.01);}
    @Test public void extremeInsetsRemainBounded(){double[] r=GeoMath.safeRect(800,480,5,-1,5,5);assertTrue(r[2]-r[0]>=319);assertTrue(r[3]-r[1]>=311);}
    @Test public void headingDistinguishesEastFromNorth(){assertEquals(90,GeoMath.bearing(new double[]{10,123},new double[]{10,124}),.01);assertEquals(0,GeoMath.bearing(new double[]{10,123},new double[]{11,123}),.01);}
    private static String encode(double[][] points){StringBuilder b=new StringBuilder();long lat=0,lon=0;for(double[] p:points){long a=Math.round(p[0]*1e6),o=Math.round(p[1]*1e6);write(b,a-lat);write(b,o-lon);lat=a;lon=o;}return b.toString();}
    private static void write(StringBuilder b,long v){v=v<0?~(v<<1):v<<1;while(v>=32){b.append((char)((32|(v&31))+63));v>>=5;}b.append((char)(v+63));}
    @Test public void projectionSnapsOntoTheRoadSegment(){double[] p=GeoMath.project(10.0002,123.5,new double[]{10,123},new double[]{10,124});assertEquals(10,p[0],1e-9);assertEquals(123.5,p[1],1e-6);
        double[] end=GeoMath.project(10,125,new double[]{10,123},new double[]{10,124});assertEquals(124,end[1],1e-9);}
}
