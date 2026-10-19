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
    private static File zip(File dir,String name,String... pairs) throws IOException{File f=new File(dir,name);
        try(ZipOutputStream z=new ZipOutputStream(new FileOutputStream(f))){for(int i=0;i<pairs.length;i+=2){z.putNextEntry(new ZipEntry(pairs[i]));z.write(pairs[i+1].getBytes("UTF-8"));z.closeEntry();}}return f;}
}
