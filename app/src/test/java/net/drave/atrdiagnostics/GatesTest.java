package net.drave.atrdiagnostics;
import org.junit.Test;import static org.junit.Assert.*;import java.util.*;
public class GatesTest {
    private static List<double[]> line(double... latLon){ArrayList<double[]> l=new ArrayList<>();for(int i=0;i<latLon.length;i+=2)l.add(new double[]{latLon[i],latLon[i+1]});return l;}
    @Test public void gatesOnTheRouteAreCountedOncePerEntrance(){
        List<double[]> route=line(14.5400,121.0300,14.5450,121.0300);
        float[] gates={14.5420f,121.0300f, 14.5421f,121.03001f, 14.5440f,121.0301f, 14.5420f,121.0320f};
        // two gates 11 m apart are one entrance; the one 200 m off the road doesn't count
        assertEquals(2,NavSession.gatesOn(route,gates));}
    @Test public void noGatesNoCount(){assertEquals(0,NavSession.gatesOn(line(14.54,121.03,14.55,121.03),new float[0]));}
}
