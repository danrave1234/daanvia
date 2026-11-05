package net.drave.atrdiagnostics;
import org.junit.Test;import static org.junit.Assert.*;import java.io.*;import java.nio.charset.StandardCharsets;import java.util.List;
/** The pure parts of Daanvia's web requests: the shared Http helper, the map merge's array trimmer and the answer parsers. */
public class NetworkPartsTest {
    // ---- Http
    @Test public void hexAndSha256MatchKnownDigests() throws Exception{
        assertEquals("00ff10",Http.hex(new byte[]{0,(byte)0xff,0x10}));
        File f=File.createTempFile("sha",".txt");write(f,"abc");
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",Http.sha256(f));
        write(f,"");assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",Http.sha256(f));}
    @Test public void finishedDownloadIsRenamedIntoPlace() throws Exception{
        File dir=java.nio.file.Files.createTempDirectory("dl").toFile(),to=new File(dir,"a.zip"),part=Http.part(to);
        assertEquals("a.zip.part",part.getName());
        write(part,"abc");write(to,"old copy");
        Http.finish(part,to,"BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD",Http.sha256(part));
        assertFalse(part.exists());assertEquals("abc",read(to));
        write(part,"no sum");Http.finish(part,to,"",Http.sha256(part));assertEquals("no sum",read(to));}
    @Test public void damagedDownloadLeavesNothingBehind() throws Exception{
        File dir=java.nio.file.Files.createTempDirectory("dl").toFile(),to=new File(dir,"a.zip"),part=Http.part(to);
        write(part,"tampered");
        try{Http.finish(part,to,"ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",Http.sha256(part));fail("damaged file kept");}catch(Http.Damaged expected){}
        assertFalse(part.exists());assertFalse(to.exists());}
    @Test public void readAllLeavesTheStreamUsable() throws Exception{
        assertEquals("Café",Http.readAll(new ByteArrayInputStream("Café".getBytes(StandardCharsets.UTF_8))));}

    // ---- AreaStore.Merger.inner: streamed, so a cell's roads.geojson is never held whole
    @Test public void innerKeepsTheArrayContentsTrimmed() throws Exception{
        for(int chunk:new int[]{1,2,3,5,7,64,1<<16}){
            assertEquals("{\"a\":1},{\"b\":[1,2]}",inner("{\"type\":\"FeatureCollection\",\"features\":[ {\"a\":1},{\"b\":[1,2]} \n]}",false,chunk));
            assertEquals(",{\"a\":1}",inner("[{\"a\":1}]",true,chunk));
            // ']' inside the content, the last ']' split from the rest, and spaces inside kept
            assertEquals("[[1, 2]], [3]",inner("  [ [[1, 2]], [3]  ] \n",false,chunk));
            assertEquals("\"x]y\"",inner("{\"f\":[\"x]y\"]}",false,chunk));}}
    @Test public void emptyArraysWriteNothing() throws Exception{
        for(int chunk:new int[]{1,4,1<<16}){
            StringWriter w=new StringWriter();assertFalse(AreaStore.Merger.inner(new Chunked("{\"features\":[]}",chunk),w,true));assertEquals("",w.toString());
            w=new StringWriter();assertFalse(AreaStore.Merger.inner(new Chunked("[ \n ]",chunk),w,true));assertEquals("",w.toString());
            w=new StringWriter();assertFalse(AreaStore.Merger.inner(new Chunked("no array",chunk),w,true));assertEquals("",w.toString());}}
    @Test public void unclosedArrayIsRefused() throws Exception{
        try{AreaStore.Merger.inner(new StringReader("[{\"a\":1}"),new StringWriter(),false);fail("broken file merged");}catch(IOException expected){}}
    @Test public void longContentPassesThroughWhole() throws Exception{
        StringBuilder b=new StringBuilder("[");for(int i=0;i<30000;i++)b.append(i==0?"":",").append("{\"i\":[").append(i).append("]}");
        String body=b.substring(1);b.append("]}");
        assertEquals(body,inner(b.toString(),false,4093));}

    // ---- answer parsers
    @Test public void photonResultsKeepNameAndBranch() throws Exception{
        List<OnlineSearch.Place> p=OnlineSearch.parse("{\"features\":["
            +"{\"geometry\":{\"coordinates\":[123.9,10.3]},\"properties\":{\"name\":\"Chowking\",\"street\":\"M. Velez Street\",\"district\":\"Guadalupe\",\"city\":\"Cebu City\"}},"
            +"{\"geometry\":{\"coordinates\":[123.8,10.2]},\"properties\":{\"housenumber\":\"12\",\"street\":\"Osmeña Blvd\",\"city\":\"Cebu City\"}},"
            +"{\"geometry\":{\"coordinates\":[1]},\"properties\":{\"name\":\"No point\"}},{\"properties\":{\"name\":\"No geometry\"}}]}");
        assertEquals(2,p.size());
        assertEquals("Chowking",p.get(0).name);assertEquals(10.3,p.get(0).lat,1e-9);assertEquals(123.9,p.get(0).lon,1e-9);
        assertEquals("M. Velez Street, Guadalupe, Cebu City",p.get(0).address);assertEquals("Chowking · M. Velez Street, Cebu City",p.get(0).branch);
        assertEquals("12 Osmeña Blvd",p.get(1).name);
        assertTrue(OnlineSearch.parse("{}").isEmpty());}
    @Test public void liveCheckReadsALotOrItsDeletion() throws Exception{
        LiveCheck.Result r=LiveCheck.parse("{\"elements\":[{\"timestamp\":\"2026-03-04T05:06:07Z\",\"tags\":{\"amenity\":\"motorcycle_parking\",\"name\":\"Lot\"}}]}");
        assertTrue(r.reachable);assertTrue(r.exists);assertEquals("2026-03-04",r.edited);assertEquals("Lot",r.tags.optString("name"));
        r=LiveCheck.parse("{\"elements\":[]}");assertTrue(r.reachable);assertFalse(r.exists);
        assertFalse(LiveCheck.parse("{\"elements\":[{\"tags\":{\"amenity\":\"cafe\"}}]}").exists);}
    @Test public void nearbyStopsSkipPrivateAndOtherKinds() throws Exception{
        String raw="{\"elements\":["
            +"{\"lat\":10.1,\"lon\":123.1,\"tags\":{\"amenity\":\"fuel\",\"name\":\"Petron\"}},"
            +"{\"center\":{\"lat\":10.2,\"lon\":123.2},\"tags\":{\"amenity\":\"fuel\",\"brand\":\"Shell\"}},"
            +"{\"lat\":10.3,\"lon\":123.3,\"tags\":{\"amenity\":\"fuel\",\"access\":\"private\"}},"
            +"{\"lat\":10.4,\"lon\":123.4,\"tags\":{\"amenity\":\"cafe\",\"name\":\"Cafe\"}},"
            +"{\"lat\":10.5,\"lon\":123.5,\"tags\":{\"highway\":\"rest_area\"}},{\"lat\":10.6,\"lon\":123.6}]}";
        List<TripStop> fuel=NearbyStops.parse(raw,true);
        assertEquals(2,fuel.size());assertEquals("Petron",fuel.get(0).name);assertEquals("Shell",fuel.get(1).name);assertEquals(10.2,fuel.get(1).lat,1e-9);
        List<TripStop> rest=NearbyStops.parse(raw,false);
        assertEquals(2,rest.size());assertEquals("Cafe",rest.get(0).name);assertEquals("Rest or food stop",rest.get(1).name);}
    @Test public void openingHoursCommonForms(){
        assertEquals(OpeningHours.State.OPEN,OpeningHours.at("24/7",3,100).state);
        // Wednesday 09:30, Mo-Fr 08:00-20:00
        OpeningHours.Status s=OpeningHours.at("Mo-Fr 08:00-20:00; Sa,Su 10:00-22:00",2,570);assertEquals(OpeningHours.State.OPEN,s.state);assertEquals("Open until 8 PM",s.label);
        s=OpeningHours.at("Mo-Fr 08:00-20:00; Sa,Su 10:00-22:00",4,21*60);assertEquals(OpeningHours.State.CLOSED,s.state);assertEquals("Closed · opens tomorrow 10 AM",s.label);
        // past midnight: Friday 22:00-02:00 is still open early Saturday
        s=OpeningHours.at("Fr 22:00-02:00",5,60);assertEquals(OpeningHours.State.OPEN,s.state);assertEquals("Open until 2 AM",s.label);
        assertEquals(OpeningHours.State.CLOSED,OpeningHours.at("Mo-Sa 08:00-17:00; Su off",6,600).state);
        assertEquals(OpeningHours.State.UNKNOWN,OpeningHours.at("sunrise-sunset",1,600).state);
        assertEquals(OpeningHours.State.UNKNOWN,OpeningHours.at("",1,600).state);
        assertEquals("1:30 PM",OpeningHours.clock(13*60+30));}
    @Test public void housekeepingOnlyClearsLeftoverAreaFolders(){
        assertTrue(Housekeeping.leftover("area-1.building"));assertTrue(Housekeeping.leftover("cebu.unpacking"));assertTrue(Housekeeping.leftover(AreaStore.TRASH+"area-1-123"));
        assertFalse(Housekeeping.leftover("area-1"));}

    private static String inner(String text,boolean comma,int chunk) throws IOException{StringWriter w=new StringWriter();AreaStore.Merger.inner(new Chunked(text,chunk),w,comma);return w.toString();}
    /** Hands the text out a few chars at a time, so '[' and ']' land on every side of a read boundary. */
    private static final class Chunked extends Reader{private final String s;private final int n;private int at;Chunked(String s,int n){this.s=s;this.n=n;}
        public int read(char[] b,int off,int len){if(at>=s.length())return -1;int k=Math.min(Math.min(len,n),s.length()-at);s.getChars(at,at+k,b,off);at+=k;return k;}public void close(){}}
    private static void write(File f,String s) throws IOException{try(FileOutputStream o=new FileOutputStream(f)){o.write(s.getBytes(StandardCharsets.UTF_8));}}
    private static String read(File f) throws IOException{return new String(java.nio.file.Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8);}
}
