package net.drave.atrdiagnostics;
import org.junit.Test;import static org.junit.Assert.*;import java.io.*;import java.util.zip.*;
public class AreaStoreTest {
    private static final String INDEX="{\"version\":2,\"cell_deg\":0.25,\"built\":\"2026-10-19T00:00:00Z\",\"cells\":["
        +"{\"id\":\"c1\",\"box\":[123.75,10.25,124.0,10.5],\"file\":\"cell_123.75_10.25.zip\",\"bytes\":8000000,\"roads_bytes\":20000000,\"label\":\"Cebu City\",\"needs\":[\"base_l1_a.zip\",\"base_l0_a.zip\"]},"
        +"{\"id\":\"c2\",\"box\":[124.0,10.25,124.25,10.5],\"file\":\"cell_124.0_10.25.zip\",\"bytes\":4000000,\"roads_bytes\":9000000,\"label\":\"Mandaue\",\"needs\":[\"base_l1_a.zip\",\"base_l0_a.zip\"]},"
        +"{\"id\":\"c3\",\"box\":[120.75,14.5,121.0,14.75],\"file\":\"cell_120.75_14.5.zip\",\"bytes\":30000000,\"roads_bytes\":70000000,\"label\":\"Manila\",\"needs\":[\"base_l1_b.zip\",\"base_l0_b.zip\"]}],"
        +"\"base\":[{\"file\":\"base_l1_a.zip\",\"bytes\":2000000},{\"file\":\"base_l0_a.zip\",\"bytes\":1000000},{\"file\":\"base_l1_b.zip\",\"bytes\":2000000},{\"file\":\"base_l0_b.zip\",\"bytes\":1000000}]}";
    @Test public void frameSelectsTouchedCellsAndCountsSharedTilesOnce() throws Exception{
        AreaStore.Catalog c=AreaStore.parse(INDEX);
        AreaStore.Selection s=AreaStore.select(c,10.28,123.85,10.40,124.05,10.31,123.89);
        assertEquals(2,s.cells.size());assertEquals(8000000+4000000+2000000+1000000,s.bytes);assertEquals("Cebu City",s.label);assertFalse(s.tooBig());
        assertTrue(s.widthKm>20&&s.widthKm<23);assertTrue(s.heightKm>12&&s.heightKm<14);assertEquals("14 MB",s.sizeLabel());}
    @Test public void seaAndHugeAreasCannotDownload() throws Exception{
        AreaStore.Catalog c=AreaStore.parse(INDEX);
        assertTrue(AreaStore.select(c,11.0,125.0,11.2,125.2,11.1,125.1).cells.isEmpty());
        assertTrue(AreaStore.select(c,14.5,120.8,14.7,120.9,14.6,120.85).tooBig());}
    @Test public void mergedCellsMakeOneValidAreaFolder() throws Exception{
        File dir=java.nio.file.Files.createTempDirectory("area").toFile();
        File z1=zip(dir,"a.zip","valhalla/2/000/1.gph","T1","roads.geojson","{\"type\":\"FeatureCollection\",\"features\":[{\"a\":1}]}","places.json","[{\"name\":\"X\"}]");
        File z2=zip(dir,"b.zip","valhalla/2/000/2.gph","T2","roads.geojson","{\"type\":\"FeatureCollection\",\"features\":[{\"b\":2},{\"c\":3}]}","places.json","[]");
        File out=new File(dir,"area");out.mkdirs();
        AreaStore.Merger m=new AreaStore.Merger(out);m.add(z1);m.add(z2);m.close();
        assertTrue(new File(out,"valhalla/2/000/1.gph").isFile()&&new File(out,"valhalla/2/000/2.gph").isFile());
        assertEquals("{\"type\":\"FeatureCollection\",\"features\":[{\"a\":1},{\"b\":2},{\"c\":3}]}",AreaStore.readAll(new FileInputStream(new File(out,"roads.geojson"))));
        assertEquals("[{\"name\":\"X\"}]",AreaStore.readAll(new FileInputStream(new File(out,"places.json"))));
        assertEquals("[]",AreaStore.readAll(new FileInputStream(new File(out,"lots.json"))));}
    @Test public void zipEscapesAreRejected() throws Exception{
        File dir=java.nio.file.Files.createTempDirectory("area").toFile();File z=zip(dir,"evil.zip","valhalla/../../evil.gph","X");
        File out=new File(dir,"area");out.mkdirs();AreaStore.Merger m=new AreaStore.Merger(out);
        try{m.add(z);fail("escape accepted");}catch(IOException expected){}finally{m.close();}}
    @Test public void catalogKnowsItsGeneration() throws Exception{
        assertEquals("",AreaStore.parse(INDEX).generation);
        AreaStore.Catalog g=AreaStore.parse(INDEX.replace("\"version\":2,","\"version\":2,\"generation\":\"g20261101T000000Z\",\"package_schema\":3,").replace("cell_123.75_10.25.zip","cell_123.75_10.25-3f2a9c1d07e4.zip"));
        assertEquals("g20261101T000000Z",g.generation);assertEquals("cell_123.75_10.25-3f2a9c1d07e4.zip",g.cells.get(0).file);assertEquals("c1",g.cells.get(0).id);}
    @Test public void filesComeFromTheGenerationsOwnRelease() throws Exception{
        AreaStore.Catalog legacy=AreaStore.parse(INDEX),g=AreaStore.parse(INDEX.replace("\"version\":2,","\"version\":2,\"generation\":\"g20261101T000000Z\",\"release\":\"maps-g20261101T000000Z\",").replace("2026-10-19T00:00:00Z","2026-11-01T00:00:00+00:00"));
        assertEquals(AreaStore.BASE,AreaStore.files(AreaStore.BASE,legacy));
        assertEquals("https://github.com/danrave1234/daanvia/releases/download/maps-g20261101T000000Z/",AreaStore.files(AreaStore.BASE,g));
        assertEquals("http://10.0.2.2:8000/maps-g20261101T000000Z/",AreaStore.files("http://10.0.2.2:8000/maps/",g));
        try{AreaStore.files(AreaStore.BASE,AreaStore.parse("{\"release\":\"../evil\"}"));fail("odd release accepted");}catch(IllegalArgumentException expected){}
        // A download only ever moves to a newer index: during the switch the app may briefly read the old one.
        assertTrue(AreaStore.newer(g,legacy));assertFalse(AreaStore.newer(legacy,g));assertFalse(AreaStore.newer(g,g));}
    @Test public void updateWaitsForTheRideToEnd() throws Exception{
        int[] asked={0};java.util.concurrent.atomic.AtomicBoolean stop=new java.util.concurrent.atomic.AtomicBoolean();
        AreaStore.awaitRideEnd(()->++asked[0]<4,stop,1);
        assertEquals(4,asked[0]);
        long t=System.nanoTime();AreaStore.awaitRideEnd(()->false,stop,10_000);assertTrue("no ride, no wait",System.nanoTime()-t<1_000_000_000L);
        stop.set(true);
        try{AreaStore.awaitRideEnd(()->true,stop,1);fail("a cancelled download must not keep waiting");}catch(IOException expected){}}
    @Test public void waitingUpdateSaysWhenItApplies(){
        assertEquals("Ready · updates when the ride ends",new AreaStore.State("Cebu",10,10,true,false,null,true).progressLabel());
        assertEquals("50% · 5 of 10 MB",new AreaStore.State("Cebu",5L<<20,10L<<20,true,false,null).progressLabel());}
    @Test public void swapReplacesAnAreaOrLeavesItAsItWas() throws Exception{
        File dir=java.nio.file.Files.createTempDirectory("swap").toFile();
        File target=new File(dir,"area-1"),fresh=new File(dir,"area-1.building"),aside=new File(dir,"trash-area-1");
        target.mkdirs();write(new File(target,"region.json"),"old");fresh.mkdirs();write(new File(fresh,"region.json"),"new");
        AreaStore.swap(fresh,target,aside);
        assertEquals("new",AreaStore.readAll(new FileInputStream(new File(target,"region.json"))));assertEquals("old",AreaStore.readAll(new FileInputStream(new File(aside,"region.json"))));assertFalse(fresh.exists());
        File missing=new File(dir,"gone.building"),aside2=new File(dir,"trash-2");
        try{AreaStore.swap(missing,target,aside2);fail("swap from nothing");}catch(IOException expected){}
        assertEquals("new",AreaStore.readAll(new FileInputStream(new File(target,"region.json"))));}
    private static void write(File f,String s) throws IOException{try(FileOutputStream o=new FileOutputStream(f)){o.write(s.getBytes("UTF-8"));}}
    private static File zip(File dir,String name,String... pairs) throws IOException{File f=new File(dir,name);
        try(ZipOutputStream z=new ZipOutputStream(new FileOutputStream(f))){for(int i=0;i<pairs.length;i+=2){z.putNextEntry(new ZipEntry(pairs[i]));z.write(pairs[i+1].getBytes("UTF-8"));z.closeEntry();}}return f;}
}
