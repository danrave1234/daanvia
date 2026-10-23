package net.drave.atrdiagnostics;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

public class CombinedGraphTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private NavSession.Region area(String id,String gen,double west,double east)throws Exception{
        File dir=temp.newFolder(id);assertTrue(new File(dir,"valhalla").mkdir());
        Files.write(new File(dir,"region.json").toPath(),"{}".getBytes());
        return new NavSession.Region(id,id,new double[]{west,0,east,1},null,null,dir,gen,Collections.emptyList());
    }
    @Test public void joinsCompatibleAreasWithoutPretendingGapIsCovered()throws Exception{
        NavSession.Region a=area("a","g1",0,1),b=area("b","g1",2,3);
        NavSession.Region r=CombinedGraph.choose(temp.newFolder("cache"),Arrays.asList(a,b),.5,.5,.5,2.5);
        assertNotNull(r);assertEquals(2,r.parts.size());assertFalse(r.contains(.5,1.5));
    }
    @Test public void generationsAndUnknownLegacyBuildsNeverMix()throws Exception{
        NavSession.Region a=area("a","g1",0,1),b=area("b","g2",1,2),legacy=area("old","",1,2);
        assertNull(CombinedGraph.choose(temp.getRoot(),Arrays.asList(a,b,legacy),.5,.5,.5,1.5));
    }
    @Test public void mergesIdenticalTilesAndRejectsConflicts()throws Exception{
        File source=temp.newFolder("source"),target=temp.newFolder("target");
        Files.write(new File(source,"tile").toPath(),new byte[]{1,2,3});
        CombinedGraph.merge(source,target);assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(new File(target,"tile").toPath()));
        File second=temp.newFolder("second");Files.write(new File(second,"tile").toPath(),new byte[]{1,2,3});CombinedGraph.merge(second,target);
        Files.write(new File(second,"tile").toPath(),new byte[]{1,3,3});
        try{CombinedGraph.merge(second,target);fail("Conflicting graph tiles must never overwrite one another");}catch(IOException expected){assertTrue(expected.getMessage().contains("conflicting"));}
        assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(new File(target,"tile").toPath()));
    }
}
