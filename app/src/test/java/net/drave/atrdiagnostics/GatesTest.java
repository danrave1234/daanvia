package net.drave.atrdiagnostics;
import org.junit.Test;import static org.junit.Assert.*;import java.util.*;
public class GatesTest {
    private static List<double[]> line(double... latLon){ArrayList<double[]> l=new ArrayList<>();for(int i=0;i<latLon.length;i+=2)l.add(new double[]{latLon[i],latLon[i+1]});return l;}
    @Test public void gatesOnTheRouteAreCountedOncePerEntrance(){
        List<double[]> route=line(14.5400,121.0300,14.5420,121.0300,14.5421,121.0300,14.5440,121.0300,14.5450,121.0300);
        float[] gates={14.5420f,121.0300f, 14.5421f,121.03001f, 14.5440f,121.0301f, 14.5420f,121.0320f};
        // Two points at one entrance count once. A gate 11 m beside the road is not crossed.
        assertEquals(1,NavSession.gatesOn(route,gates));}
    @Test public void publicRoadWithTwelveNearbyDrivewayGatesHasNoCrossings(){
        List<double[]> route=new ArrayList<>();float[] gates=new float[24];
        route.add(new double[]{14.54,121.03});
        for(int i=0;i<12;i++){double lat=14.5402+i*.0002;route.add(new double[]{lat,121.03});gates[i*2]=(float)lat;gates[i*2+1]=(float)(121.03+(4+i%8)/107600d);}
        route.add(new double[]{14.543,121.03});assertEquals(0,NavSession.gatesOn(route,gates));}
    @Test public void gateBesideSimplifiedSegmentIsNotAssumedToBeCrossed(){
        assertEquals(0,NavSession.gatesOn(line(14.54,121.03,14.545,121.03),new float[]{14.542f,121.03f}));}
    @Test public void approachingGateOrTurningBackDoesNotCountAsEntering(){
        float[] gate={14.542f,121.03f};
        assertEquals(0,NavSession.gatesOn(line(14.54,121.03,14.541,121.03,14.542,121.03),gate));
        assertEquals(0,NavSession.gatesOn(line(14.54,121.03,14.542,121.03,14.54,121.03),gate));}
    @Test public void distinctGatesActuallyTraversedAreCounted(){
        assertEquals(2,NavSession.gatesOn(line(14.54,121.03,14.541,121.03,14.544,121.03,14.545,121.03),new float[]{14.541f,121.03f,14.544f,121.03f}));}
    @Test public void gateWhereTheRouteTurnsIntoTheSubdivisionCounts(){
        // Along the main road heading north, then a right-angle turn east through the gate at the subdivision's mouth.
        assertEquals(1,NavSession.gatesOn(line(14.540,121.030,14.542,121.030,14.542,121.032,14.542,121.034),new float[]{14.542f,121.030f}));}
    @Test public void farAwayGatesAreIgnoredQuickly(){
        float[] gates=new float[2000];for(int i=0;i<1000;i++){gates[i*2]=10.3f+i*0.0001f;gates[i*2+1]=123.9f;}
        assertEquals(0,NavSession.gatesOn(line(14.54,121.03,14.541,121.03,14.542,121.03),gates));}
    @Test public void noGatesNoCount(){assertEquals(0,NavSession.gatesOn(line(14.54,121.03,14.55,121.03),new float[0]));}
}
