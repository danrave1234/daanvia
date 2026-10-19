package net.drave.atrdiagnostics;
import org.junit.Test;import static org.junit.Assert.*;import java.util.*;
public class BranchesTest {
    private static NavSession.Road road(String name,String kind,double... latLon){float[] xy=new float[latLon.length];for(int i=0;i<latLon.length;i++)xy[i]=(float)latLon[i];return new NavSession.Road(xy,name,kind);}
    private static final SearchIndex TOWNS=new SearchIndex(Arrays.asList("Makati","Pasig"),new double[]{14.5547,14.5764},new double[]{121.0244,121.0851});
    @Test public void chainBranchIsNamedAfterItsRoadAndTown(){
        RoadTiles.Index roads=new RoadTiles.Index();
        roads.add(road("EDSA","trunk",14.5660,121.0440,14.5690,121.0480));
        roads.add(road("Jollibee Drive-Thru","service",14.56745,121.0459,14.56755,121.0461));
        roads.add(road("","residential",14.5674,121.0458,14.5676,121.0462));
        assertEquals("Jollibee · EDSA, Makati",Branches.label("Jollibee",14.5675,121.0460,roads,TOWNS));}
    @Test public void farFromRoadsTheTownStillTellsItApart(){
        assertEquals("Jollibee · Pasig",Branches.label("Jollibee",14.5770,121.0850,new RoadTiles.Index(),TOWNS));
        assertEquals("Jollibee",Branches.label("Jollibee",10.3,123.9,new RoadTiles.Index(),TOWNS));}
    @Test public void indexCountsBranchesOfAChain(){
        SearchIndex idx=new SearchIndex(Arrays.asList("Jollibee","Jollibee","SM City Cebu"),new double[]{14.55,14.58,10.31},new double[]{121.02,121.05,123.91});
        assertEquals(2,idx.copies("jollibee"));assertEquals(1,idx.copies("SM City Cebu"));}
}
