package net.drave.atrdiagnostics;
import android.net.Uri;
import android.content.*;
import android.os.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import com.valhalla.valhalla.Valhalla;
public final class NavSession {
    public static final String EVENT="net.drave.atrdiagnostics.NAV_EVENT";
    private static NavSession instance;
    public static synchronized NavSession get(Context c){if(instance==null)instance=new NavSession(c.getApplicationContext());return instance;}
    public final Context context;
    public final ExecutorService worker=Executors.newSingleThreadExecutor();
    public volatile boolean ready,active,simulation,calibration,routing,noMap;
    public volatile String status="Preparing offline maps…",instruction="Choose a destination",destination="";
    public volatile double lat=10.3157,lon=123.8854,destLat,destLon,remaining,seconds,distanceToTurn;
    public volatile float accuracy=999,bearing,speedMetersPerSecond;
    /** Where to draw the rider: on the route line while following it (snapped, heading along the road), else the raw fix. Routing always uses the raw fix. */
    public volatile double viewLat=10.3157,viewLon=123.8854;public volatile float viewBearing;public volatile boolean snapped;
    public volatile long fixAt;
    /** Briefly yield turn announcements to a local command's microphone or reply. */
    public volatile long commandAudioUntil;
    public volatile List<double[]> shape=Collections.emptyList();
    public volatile List<RoutePlan> routeChoices=Collections.emptyList();
    public volatile int selectedRoute;
    public volatile JSONArray parking=new JSONArray();public volatile Places places=Places.EMPTY;
    /** Road lines near any point, for the bike display. Empty until the area's roads are read. */
    public volatile RoadTiles.Index roadIndex=new RoadTiles.Index();
    /** The area's street squares for the map (see RoadTiles); null until built. Bumps roadsVersion when they change. */
    public volatile File roadTiles;public volatile int roadsVersion;
    public volatile List<JSONObject> nearbyParking=Collections.emptyList();
    public volatile JSONArray maneuvers=new JSONArray();
    public volatile int maneuver,progress;
    public volatile double[] cumulative=new double[0];private double routeMeters,routeSeconds;
    /** Seconds from the route's start to each of its points, from the router's time for each stretch (see sectionTimes). */
    private volatile double[] cumTime=new double[0];
    /**
     * Seconds from the start of the route to each of its points. Each maneuver's time (the router's own estimate for that
     * stretch: slow streets slow, fast roads fast) is spread over its points by distance. Without maneuver times, the
     * route's total time is spread evenly.
     */
    static double[] sectionTimes(double[] prefix,JSONArray maneuvers,double total){
        int n=prefix.length;double[] seg=new double[n];
        for(int m=0;m<maneuvers.length();m++){JSONObject o=maneuvers.optJSONObject(m);if(o==null)continue;
            int b=Math.max(0,Math.min(n-1,o.optInt("begin_shape_index",0))),e=Math.max(b,Math.min(n-1,o.optInt("end_shape_index",b)));double t=o.optDouble("time",0),len=prefix[e]-prefix[b];
            for(int j=b+1;j<=e;j++)seg[j]+=len>0?t*(prefix[j]-prefix[j-1])/len:t/(e-b);}
        double[] ct=new double[n];for(int j=1;j<n;j++)ct[j]=ct[j-1]+seg[j];
        if(n>1&&ct[n-1]<=0)for(int j=1;j<n;j++)ct[j]=total*prefix[j]/Math.max(1,prefix[n-1]);
        return ct;}
    private final HashMap<String,Valhalla> engines=new HashMap<>();private volatile Region routeRegion,shown;private long lastReroute,savedFixAt=-600000;private int offRoute,requestId;
    /** The rider reached the destination on this route: no more rerouting, and the arrival is announced once. */
    public volatile boolean arrived;
    /** A call or message shown on the bike display until popupUntil (elapsedRealtime), see RideNotifications. */
    public volatile String popupApp="",popupTitle="",popupText="",popupKey="";public volatile boolean popupCall;public volatile long popupUntil;
    void popup(String app,String title,String text,boolean call,String key,long until){popupApp=app;popupTitle=title;popupText=text;popupCall=call;popupKey=key;popupUntil=until;signal();}
    /** Set when a ride ended on its own at the destination (see NavService.checkArrival); the map screen says so once. */
    public volatile String finished;
    /** Every installed offline pack, best source first; the map, search and parking data come from {@link #regionId}. */
    public volatile List<Region> regions=Collections.emptyList();public volatile String regionId="";public volatile int regionVersion;
    /** Where the map reads the area's roads (file:// or asset://); MapLibre parses the file itself, off the main thread. Null without a map. */
    /** The map's main-roads file (file:// or asset://), or null while the area's road tiles are being built. */
    public volatile String roadsUri;
    public double minLat,maxLat,minLon,maxLon;
    public volatile String regionName="Offline region";public volatile double[] demoFrom={10.3157,123.8854},demoTo={10.2934,123.9019};public volatile String demoName="Sample route";
    public volatile double[] home;public double homeLat(){return home!=null?home[0]:ready?(minLat+maxLat)/2:lat;}public double homeLon(){return home!=null?home[1]:ready?(minLon+maxLon)/2:lon;}
    public float insetLeft,insetTop,insetRight,insetBottom,textScale=1;public String layout="Map focus";
    /** The bike display's cards and arrow, as fractions of its visible area (x, y, width, height), saved per bike so they can sit clear of that bike's own gauges. */
    public static final float[] DEFAULT_TURN={0.02f,0.03f,0.34f,0.57f},DEFAULT_TRIP={0.02f,0.63f,0.34f,0.33f};public static final float DEFAULT_ARROW_X=0.68f,DEFAULT_ARROW_Y=0.72f;
    public volatile float[] turnCard=DEFAULT_TURN.clone(),tripCard=DEFAULT_TRIP.clone();public volatile float arrowX=DEFAULT_ARROW_X,arrowY=DEFAULT_ARROW_Y;
    /** True while the layout editor is open: the display shows sample directions and outlines its cards. */
    public volatile boolean layoutEditing;
    public void resetLayout(){turnCard=DEFAULT_TURN.clone();tripCard=DEFAULT_TRIP.clone();arrowX=DEFAULT_ARROW_X;arrowY=DEFAULT_ARROW_Y;}
    static String cards(float[] t,float[] r,float ax,float ay){StringBuilder b=new StringBuilder();for(float v:t)b.append(v).append(',');for(float v:r)b.append(v).append(',');return b.append(ax).append(',').append(ay).toString();}
    void cards(String saved){resetLayout();if(saved==null||saved.isEmpty())return;try{String[] v=saved.split(",");if(v.length!=10)return;float[] n=new float[10];for(int i=0;i<10;i++)n[i]=Float.parseFloat(v[i]);turnCard=new float[]{n[0],n[1],n[2],n[3]};tripCard=new float[]{n[4],n[5],n[6],n[7]};arrowX=n[8];arrowY=n[9];}catch(NumberFormatException e){resetLayout();}}
    /** A road line for the bike display, its points packed as lat,lon floats (about 1 m precision) to keep a large area in memory. */
    /** A road line; bridge / tunnel / layer come from the map data (package schema 4) and draw overpasses above the roads they cross. */
    public static final class Road {private final float[] xy;public final String name,kind;public final boolean bridge,tunnel;public final int layer;public double minLat=90,maxLat=-90,minLon=180,maxLon=-180;
        Road(float[] xy,String n,String k){this(xy,n,k,false,false,0);}
        Road(float[] xy,String n,String k,boolean bridge,boolean tunnel,int layer){this.xy=xy;name=n;kind=k;this.bridge=bridge;this.tunnel=tunnel;this.layer=layer;for(int i=0;i+1<xy.length;i+=2){minLat=Math.min(minLat,xy[i]);maxLat=Math.max(maxLat,xy[i]);minLon=Math.min(minLon,xy[i+1]);maxLon=Math.max(maxLon,xy[i+1]);}}
        public int size(){return xy.length/2;} public double lat(int i){return xy[i*2];} public double lon(int i){return xy[i*2+1];}}
    /** One offline pack: region.json plus its graph and data, either bundled under assets/ or unpacked in a directory. Areas are [minLon,minLat,maxLon,maxLat] boxes. */
    /** The area's place names for search, held as plain arrays rather than JSON objects. */
    public static final class Places {
        public static final Places EMPTY = new Places(new ArrayList<>(), new double[0], new double[0]);
        public final List<String> names; public final double[] lat, lon;
        Places(List<String> names, double[] lat, double[] lon) { this.names = names; this.lat = lat; this.lon = lon; }
        public int size() { return names.size(); }
    }
    public static final class Region {
        public final String id,name;public final double[] bounds;public final double[][] areas;final String asset;final File dir;final String generation;final List<Region> parts;
        Region(String id,String name,double[] bounds,double[][] areas,String asset,File dir){this(id,name,bounds,areas,asset,dir,"",Collections.emptyList());}
        Region(String id,String name,double[] bounds,double[][] areas,String asset,File dir,String generation,List<Region> parts){this.id=id;this.name=name;this.bounds=bounds;this.areas=areas==null||areas.length==0?new double[][]{bounds}:areas;this.asset=asset;this.dir=dir;this.generation=generation;this.parts=Collections.unmodifiableList(new ArrayList<>(parts));}
        public boolean contains(double lat,double lon){for(double[] a:areas)if(lon>=a[0]&&lon<=a[2]&&lat>=a[1]&&lat<=a[3])return true;return false;}
        double size(){double s=0;for(double[] a:areas)s+=(a[2]-a[0])*(a[3]-a[1]);return s;}
        /** The smallest pack holding both points (a city pack beats its island pack), or null when no single pack can route between them. */
        public static Region pick(List<Region> list,double aLat,double aLon,double bLat,double bLon){Region best=null;for(Region r:list)if(r.contains(aLat,aLon)&&r.contains(bLat,bLon)&&(best==null||r.size()<best.size()))best=r;return best;}
        public static Region at(List<Region> list,double lat,double lon){return pick(list,lat,lon,lat,lon);}
    }
    /** Fixed general allowance for offline estimates; never adjusted using personal riding history. */
    static final double OFFLINE_TIME_ALLOWANCE=1.5;
    private NavSession(Context c){context=c;
        // Remove timing calibration saved by earlier builds; preserve all other preferences.
        android.content.SharedPreferences prefs=c.getSharedPreferences("ui",0);
        android.content.SharedPreferences.Editor cleanup=prefs.edit().remove("pace");
        for(String key:prefs.getAll().keySet())if(key.startsWith("pace_v2_"))cleanup.remove(key);
        cleanup.apply();
        loadFit();worker.execute(()->{
        // Before any native engine opens: discard derived joins from previous processes. Installed maps stay intact.
        // Merged routing tiles for trips across areas: keep the newest finished one (its name already changes whenever an
        // area is updated, so it can't go stale) and clear the rest, instead of rebuilding after every start.
        File[] merged=new File(context.getFilesDir(),"combined-graphs").listFiles(File::isDirectory);
        if(merged!=null){Arrays.sort(merged,(x,y)->Long.compare(y.lastModified(),x.lastModified()));boolean kept=false;
            for(File m:merged){if(!kept&&new File(m,"ready").isFile()){kept=true;continue;}AreaStore.delete(m);}}
        File[] previous=new File(context.getFilesDir(),"engines").listFiles((dir,name)->name.startsWith("combined-"));
        if(previous!=null)for(File dir:previous)AreaStore.delete(dir);
        load();
    });}
    public void signal(){context.sendBroadcast(new Intent(EVENT).setPackage(context.getPackageName()));}
    private final ArrayDeque<String> logs=new ArrayDeque<>();
    public void message(String text){status=text;String clean=destination.isEmpty()?text:text.replace(destination,"[destination]");clean=clean.replaceAll("[-+]?[0-9]{1,3}\\.[0-9]{4,}","[coordinate]");String line=new java.text.SimpleDateFormat("HH:mm:ss",java.util.Locale.US).format(new java.util.Date())+" "+clean;synchronized(logs){if(logs.size()>=150)logs.removeFirst();logs.addLast(line);}LogUpload.add(context,line.replaceFirst(" ","  Ride: ")+"\n");signal();}
    public String report(){try{JSONObject j=new JSONObject().put("app","Daanvia "+BuildInfo.VERSION).put("region_ready",ready).put("navigation_active",active).put("simulation",simulation).put("route_points",shape.size()).put("gps_age_seconds",fixAt==0?-1:(SystemClock.elapsedRealtime()-fixAt)/1000).put("offline_region",regionId).put("installed_regions",regions.size()).put("layout",layout).put("location_and_identifiers","omitted").put("ride_metrics",RideMetrics.forReport(context));synchronized(logs){j.put("log",new JSONArray(logs));}return j.toString(2);}catch(Exception e){return "Report unavailable";}}
    private String asset(String p)throws Exception{return read(context.getAssets().open(p));}
    private static String read(InputStream stream)throws IOException{try(InputStream in=stream){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toString("UTF-8");}}
    private String text(Region r,String file)throws Exception{return r.asset!=null?asset(r.asset+"/"+file):read(new FileInputStream(new File(r.dir,file)));}
    /** A buffered reader over an area file, so large files stream instead of being copied into memory. */
    private android.util.JsonReader json(Region r,String file)throws Exception{InputStream in=r.asset!=null?context.getAssets().open(r.asset+"/"+file):new FileInputStream(new File(r.dir,file));return new android.util.JsonReader(new InputStreamReader(new BufferedInputStream(in,1<<16),"UTF-8"));}
    private static String uri(Region r,String file){return r.asset!=null?"asset://"+r.asset+"/"+file:Uri.fromFile(new File(r.dir,file)).toString();}
    /** Reads a data file such as "lots.json" from the pack now shown ({@link #regionId}); FileNotFoundException when that pack has none. Off the UI thread; reload when {@link #regionVersion} changes. */
    public String regionText(String file)throws Exception{Region r=shown;if(r==null)throw new FileNotFoundException(file);return text(r,file);}
    private static Region region(JSONObject j,String asset,File dir)throws JSONException{
        JSONArray b=j.getJSONArray("bounds");double[] bounds={b.getDouble(0),b.getDouble(1),b.getDouble(2),b.getDouble(3)};JSONArray a=j.optJSONArray("areas");double[][] areas=null;
        if(a!=null){areas=new double[a.length()][];for(int i=0;i<a.length();i++){JSONArray x=a.getJSONArray(i);areas[i]=new double[]{x.getDouble(0),x.getDouble(1),x.getDouble(2),x.getDouble(3)};}}
        return new Region(j.getString("id"),j.optString("display_name",j.optString("name",j.getString("id"))),bounds,areas,asset,dir,j.optString("generation"),Collections.emptyList());}
    /** Packs unpacked in app storage (downloaded, or copied from a Play asset pack), then packs copied over USB to Android/data/…/files/regions, then the APK's own. The first copy of an id wins. */
    private List<Region> discover(){ArrayList<Region> list=new ArrayList<>();HashSet<String> ids=new HashSet<>();
        for(File base:new File[]{new File(context.getFilesDir(),"regions"),context.getExternalFilesDir("regions")}){File[] dirs=base==null?null:base.listFiles();if(dirs==null)continue;Arrays.sort(dirs);
            for(File d:dirs)try{if(d.getName().startsWith(AreaStore.TRASH)||d.getName().endsWith(".building"))continue;if(new File(d,"valhalla_tiles.tar").isFile()||new File(d,"valhalla").isDirectory())add(list,ids,region(new JSONObject(read(new FileInputStream(new File(d,"region.json")))),null,d));}catch(Exception e){android.util.Log.w("Daanvia","Skipping offline pack "+d.getName(),e);}}
        try{String[] bundled=context.getAssets().list("regions");if(bundled!=null){Arrays.sort(bundled);for(String n:bundled)add(list,ids,region(new JSONObject(asset("regions/"+n+"/region.json")),"regions/"+n,null));}}catch(Exception e){android.util.Log.w("Daanvia","Bundled packs unreadable",e);}
        return list;}
    private static void add(List<Region> list,Set<String> ids,Region r){if(ids.add(r.id))list.add(r);}
    /** Opens on the pack the phone was last located in, else the one shown last time, else the first. */
    private Region choose(List<Region> list){
        try{if(context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)==android.content.pm.PackageManager.PERMISSION_GRANTED||context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)==android.content.pm.PackageManager.PERMISSION_GRANTED){
            android.location.LocationManager m=(android.location.LocationManager)context.getSystemService(Context.LOCATION_SERVICE);android.location.Location best=null;
            for(String p:m.getProviders(true)){android.location.Location l=m.getLastKnownLocation(p);if(l!=null&&(best==null||l.getTime()>best.getTime()))best=l;}
            if(best!=null){Region r=Region.at(list,best.getLatitude(),best.getLongitude());if(r!=null)return r;}}}catch(RuntimeException ignored){}
        String last=context.getSharedPreferences("offline-region",0).getString("shown","");for(Region r:list)if(r.id.equals(last))return r;return list.get(0);}
    /** Loads a pack's map, search and parking data as the shown region. Worker thread only. */
    private void display(Region r)throws Exception{
        JSONObject region=new JSONObject(text(r,"region.json"));
        Places placeList=places(r);JSONArray parkingList=new JSONArray(text(r,"parking.json"));
        minLon=r.bounds[0];minLat=r.bounds[1];maxLon=r.bounds[2];maxLat=r.bounds[3];regionName=r.name;
        JSONObject demo=region.optJSONObject("demo");if(demo!=null){JSONArray f=demo.getJSONArray("from"),t=demo.getJSONArray("to");demoFrom=new double[]{f.getDouble(0),f.getDouble(1)};demoTo=new double[]{t.getDouble(0),t.getDouble(1)};demoName=demo.optString("name",demoName);}JSONArray hc=region.optJSONArray("home");home=hc!=null?new double[]{hc.getDouble(0),hc.getDouble(1)}:null;
        if(r.dir!=null&&RoadTiles.ready(r.dir)){roadsUri=Uri.fromFile(new File(new File(r.dir,RoadTiles.DIR),RoadTiles.MAJOR)).toString();roadTiles=new File(r.dir,RoadTiles.DIR);}else if(r.dir!=null){roadsUri=null;roadTiles=null;}else{roadsUri=uri(r,"roads.geojson");roadTiles=null;}roadsVersion++;roadIndex=new RoadTiles.Index();places=placeList;parking=parkingList;mergeSavedParking();
        shown=r;regionId=r.id;regionVersion++;context.getSharedPreferences("offline-region",0).edit().putString("shown",r.id).apply();
    }
    /** Place names and positions, streamed from places.json. */
    private Places places(Region r)throws Exception{
        ArrayList<String> names=new ArrayList<>();double[] la=new double[1024],lo=new double[1024];int n=0;
        try(android.util.JsonReader reader=json(r,"places.json")){
            reader.beginArray();while(reader.hasNext()){String name=null;double a=0,b=0;reader.beginObject();while(reader.hasNext()){String k=reader.nextName();if("name".equals(k))name=reader.nextString();else if("lat".equals(k))a=reader.nextDouble();else if("lon".equals(k))b=reader.nextDouble();else reader.skipValue();}reader.endObject();
                if(name==null)continue;if(n==la.length){la=Arrays.copyOf(la,n*2);lo=Arrays.copyOf(lo,n*2);}names.add(name);la[n]=a;lo[n]=b;n++;}
            reader.endArray();
        }
        return new Places(names,Arrays.copyOf(la,n),Arrays.copyOf(lo,n));
    }
    /** The road lines the bike display draws, streamed from roads.geojson after the map is already usable. Worker thread. */
    private int lastNumber;
    /** A 0/1 (or true/false) property; for "layer" the number itself is kept in lastNumber. */
    private boolean flag(android.util.JsonReader reader)throws IOException{
        switch(reader.peek()){case BOOLEAN:lastNumber=reader.nextBoolean()?1:0;break;case NUMBER:lastNumber=reader.nextInt();break;
            case STRING:try{lastNumber=Integer.parseInt(reader.nextString().trim());}catch(NumberFormatException e){lastNumber=1;}break;default:reader.skipValue();lastNumber=0;}
        return lastNumber!=0;}
    private void loadRoads(Region r){
        try{RoadTiles.Index index=new RoadTiles.Index();RoadTiles.Writer tiles=r.dir!=null&&!RoadTiles.ready(r.dir)?new RoadTiles.Writer(r.dir):null;
            try(android.util.JsonReader reader=json(r,"roads.geojson")){
                reader.beginObject();while(reader.hasNext()){String key=reader.nextName();if(!"features".equals(key)){reader.skipValue();continue;}reader.beginArray();while(reader.hasNext()){
                    String name="",kind="";boolean bridge=false,tunnel=false;int layer=0;float[] pts=new float[64];int np=0;reader.beginObject();while(reader.hasNext()){String field=reader.nextName();
                        if("properties".equals(field)){reader.beginObject();while(reader.hasNext()){String property=reader.nextName();if("name".equals(property))name=reader.nextString();else if("kind".equals(property))kind=reader.nextString();else if("bridge".equals(property))bridge=flag(reader);else if("tunnel".equals(property))tunnel=flag(reader);else if("layer".equals(property))layer=flag(reader)?lastNumber:0;else reader.skipValue();}reader.endObject();}
                        else if("geometry".equals(field)){reader.beginObject();while(reader.hasNext()){if(!"coordinates".equals(reader.nextName())){reader.skipValue();continue;}reader.beginArray();while(reader.hasNext()){reader.beginArray();double longitude=reader.nextDouble(),latitude=reader.nextDouble();while(reader.hasNext())reader.skipValue();reader.endArray();if(np+2>pts.length)pts=Arrays.copyOf(pts,pts.length*2);pts[np++]=(float)latitude;pts[np++]=(float)longitude;}reader.endArray();}reader.endObject();}
                        else reader.skipValue();
                    }reader.endObject();index.add(new Road(Arrays.copyOf(pts,np),name,kind,bridge,tunnel,layer));if(tiles!=null)tiles.add(name,kind,bridge,tunnel,layer,pts,np);
                }reader.endArray();}reader.endObject();
            }
            if(shown==r)roadIndex=index;
            if(tiles!=null){tiles.finish();if(shown==r){roadsUri=Uri.fromFile(new File(new File(r.dir,RoadTiles.DIR),RoadTiles.MAJOR)).toString();roadTiles=new File(r.dir,RoadTiles.DIR);roadsVersion++;signal();}}
        }catch(Throwable e){android.util.Log.w("Daanvia","Area roads unavailable",e);}
    }
    /** Worker thread only. A bundled graph is copied out of the APK once (again when its bundled size changes); an unpacked pack's graph is read in place. */
    private Valhalla engine(Region r)throws Exception{Valhalla e=engines.get(r.id);if(e!=null)return e;
        CombinedGraph.prepare(r);
        File dir=new File(context.getFilesDir(),r.asset==null?"engines/"+r.id:"region-"+r.id);if(!dir.exists()&&!dir.mkdirs())throw new IOException("Cannot create offline storage");
        File tiles=r.asset==null?new File(r.dir,"valhalla_tiles.tar"):new File(dir,"valhalla_tiles.tar");
        if(r.asset!=null){JSONObject files=new JSONObject(text(r,"region.json")).optJSONObject("files");JSONObject spec=files==null?null:files.optJSONObject("valhalla_tiles.tar");long want=spec==null?-1:spec.optLong("bytes",-1);
            if(!tiles.exists()||want>0&&tiles.length()!=want){File temp=new File(dir,"tiles.part");try(InputStream in=context.getAssets().open(r.asset+"/valhalla_tiles.tar");FileOutputStream out=new FileOutputStream(temp)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);out.getFD().sync();}if(tiles.exists()&&!tiles.delete()||!temp.renameTo(tiles))throw new IOException("Cannot install region");}}
        String template;try{template=text(r,"valhalla-template.json");}catch(FileNotFoundException missing){template=asset("valhalla-template.json");}
        JSONObject cfg=new JSONObject(template);
        // A downloaded area is a folder of individual graph tiles merged from its cells; packs are a single tar.
        File tileDir=r.asset==null?new File(r.dir,"valhalla"):null;
        if(tileDir!=null&&tileDir.isDirectory()&&!tiles.isFile())cfg.getJSONObject("mjolnir").put("tile_dir",tileDir.getAbsolutePath()).remove("tile_extract");
        else cfg.getJSONObject("mjolnir").put("tile_extract",tiles.getAbsolutePath()).put("tile_dir",dir.getAbsolutePath());
        cfg.getJSONObject("mjolnir").remove("tile_url");
        File config=new File(dir,"valhalla.json");try(FileOutputStream out=new FileOutputStream(config)){out.write(cfg.toString().getBytes("UTF-8"));}
        // Only one merged engine at a time: a new one replaces the last instead of piling up.
        if(r.id.startsWith("combined-"))engines.keySet().removeIf(id->id.startsWith("combined-")&&!id.equals(r.id));
        e=new Valhalla(config.getAbsolutePath());engines.put(r.id,e);return e;}
    /** Look for installed packs again, after a download or removal. A ride in progress keeps its pack. */
    public void reload(){reload(null);}
    /** Re-scan installed areas and show {@code prefer} if given (a fresh download), else the best one for where the rider is. */
    public void reload(String prefer){worker.execute(()->{if(active){reloadAfterRide=true;return;}preferred=prefer;load();preferred=null;});}
    /** An area changed during a ride; it is read again once the ride ends. */
    private volatile boolean reloadAfterRide;
    private volatile String preferred;
    /** Show the downloaded area at a map position the rider is looking at, so panning into another area swaps its roads, search and parking in. One area is held in memory at a time. */
    public void showAt(double lat,double lon){if(!ready||active||routing)return;Region r=Region.at(regions,lat,lon);if(r==null||r==shown)return;
        worker.execute(()->{if(active||r==shown)return;try{display(r);routeRegion=r;message(regionName+" offline · choose a destination");loadRoads(r);}catch(Throwable e){android.util.Log.e("Daanvia","Area switch failed",e);}});}
    /** Drop a removed pack's routing engine so its files are no longer held. Worker thread. */
    public void forget(String id){worker.execute(()->engines.remove(id));}
    private void load(){try{
        List<Region> found=discover();regions=Collections.unmodifiableList(found);
        if(found.isEmpty()){ready=false;noMap=true;shown=null;regionId="";regionVersion++;roadIndex=new RoadTiles.Index();roadTiles=null;roadsVersion++;places=Places.EMPTY;parking=new JSONArray();roadsUri=null;message("No map installed");return;}
        noMap=false;
        Region first=null;if(preferred!=null)for(Region r:found)if(r.id.equals(preferred))first=r;if(first==null){android.content.SharedPreferences cam=context.getSharedPreferences("ui",0);if(cam.contains("cam_lat"))first=Region.at(found,cam.getFloat("cam_lat",0),cam.getFloat("cam_lon",0));}if(first==null)first=choose(found);display(first);routeRegion=first;engine(first);ready=true;message(regionName+" offline · choose a destination");loadRoads(first);
    }catch(Throwable e){message("Offline region could not load: "+e.getClass().getSimpleName()+". Open Support.");android.util.Log.e("Daanvia","Offline load failed",e);}}
    public boolean inRegion(double a,double b){for(Region r:regions)if(r.contains(a,b))return true;return false;}
    volatile List<TripStop> tripStops=Collections.emptyList();
    private int passedStops;private int[] legEnds=new int[0];
    public synchronized List<TripStop> remainingStops(){return new ArrayList<>(tripStops.subList(Math.min(passedStops,tripStops.size()),tripStops.size()));}
    public void plan(double a,double b,String name,boolean demo){
        plan(a,b,name,demo,Collections.emptyList());
    }
    void planTrip(List<TripStop> stops,String name){
        if(stops.isEmpty()||stops.size()>TripStop.MAX){message("Choose between 1 and "+TripStop.MAX+" stops");return;}
        TripStop end=stops.get(stops.size()-1);plan(end.lat,end.lon,name,false,new ArrayList<>(stops));
    }
    static final class Detour {final TripStop stop;final double meters,seconds;Detour(TripStop stop,double meters,double seconds){this.stop=stop;this.meters=meters;this.seconds=seconds;}}
    void detours(List<TripStop> original,List<TripStop> candidates,java.util.function.Consumer<List<Detour>> callback){
        final double fromLat=lat,fromLon=lon;final double factor=OFFLINE_TIME_ALLOWANCE;
        worker.execute(()->{List<Detour> found=new ArrayList<>();try{
            RoutePlan base=estimate(original,fromLat,fromLon);
            for(TripStop candidate:candidates){if(active)break;try{List<TripStop> via=new ArrayList<>(original);via.add(Math.max(0,via.size()-1),candidate);RoutePlan route=estimate(via,fromLat,fromLon);found.add(new Detour(candidate,Math.max(0,route.meters-base.meters),Math.max(0,(route.seconds-base.seconds)*factor)));}catch(Exception unreachable){}}
            found.sort(java.util.Comparator.comparingDouble(d->d.seconds));
        }catch(Exception unavailable){}callback.accept(found);});
    }
    private RoutePlan estimate(List<TripStop> stops,double fromLat,double fromLon)throws Exception{
        if(stops.isEmpty())throw new IllegalArgumentException("Choose a destination");TripStop end=stops.get(stops.size()-1);
        Region region=Region.pick(regions,fromLat,fromLon,end.lat,end.lon);
        if(region!=null)for(TripStop stop:stops)if(!region.contains(stop.lat,stop.lon)){region=null;break;}
        if(region==null)region=CombinedGraph.choose(new File(context.getFilesDir(),"combined-graphs"),regions,fromLat,fromLon,end.lat,end.lon);
        if(region==null)throw new IOException("Missing offline coverage");
        JSONArray locations=new JSONArray().put(new JSONObject().put("lat",fromLat).put("lon",fromLon).put("type","break"));
        for(TripStop stop:stops){if(!region.contains(stop.lat,stop.lon))throw new IOException("Stop outside offline coverage");locations.put(stop.json());}
        return RoutePlan.options(new JSONObject(engine(region).routeRaw(RouteRequest.through(locations,false).toString()))).get(0);
    }
    private void plan(double a,double b,String name,boolean demo,List<TripStop> stops){
        if(!ready){message("Wait for the offline region to finish loading");return;}
        if(routing){message("Route calculation in progress · wait before changing destination");return;}
        Region picked=Region.pick(regions,lat,lon,a,b);
        if(picked!=null)for(TripStop stop:stops)if(!picked.contains(stop.lat,stop.lon)){picked=null;break;}
        if(picked==null)picked=CombinedGraph.choose(new File(context.getFilesDir(),"combined-graphs"),regions,lat,lon,a,b);
        if(picked!=null)for(TripStop stop:stops)if(!picked.contains(stop.lat,stop.lon)){picked=null;break;}
        final Region r=picked;
        if(r==null){nextVia=null;followVia=null;shape=Collections.emptyList();routeChoices=Collections.emptyList();message(inRegion(a,b)&&inRegion(lat,lon)?"Start and destination need compatible offline maps. Update both areas to the same map build.":"Outside the installed offline regions. Download the start, destination and connecting areas.");return;}
        simulation=demo;destLat=a;destLon=b;destination=name;routeRegion=r;followVia=nextVia;  // a new destination drops an old resume point
        tripStops=Collections.unmodifiableList(stops);passedStops=0;
        final Region visible=r.parts.isEmpty()?r:Region.at(regions,a,b);
        if(visible!=null&&visible!=shown)worker.execute(()->{try{display(visible);}catch(Throwable e){android.util.Log.e("Daanvia","Region switch failed",e);}refreshParking();signal();loadRoads(visible);});else refreshParking();
        shape=Collections.emptyList();routeChoices=Collections.emptyList();route();
    }
    private synchronized void route(){if(routing)return;final int token=++requestId;routing=true;message("Calculating on this phone…");final double a=lat,b=lon,c=destLat,d=destLon;final Region region=routeRegion;final List<TripStop> stops=remainingStops();
        final double[] via=nextVia;nextVia=null;
        worker.execute(()->{boolean again=false;try{
            // A spot the router can't reach (a parking lot behind a private or customers-only road, the rider inside a gated
            // lot) would leave no route at all: try again from and to the nearest public road beside it, and only as the
            // very last resort with access rules off (the route card then says the place is inside a private subdivision).
            JSONObject response=null;boolean fellBack=false,usedPrivate=false;
            // A ride picked up after the phone closed the app goes through a point ahead on the route the rider chose.
            if(via!=null)try{response=request(region,a,b,via,stops,c,d,false);}catch(Throwable viaFailed){android.util.Log.w("Daanvia","Route through the chosen road failed",viaFailed);}
            if(response==null&&stops.size()>1){
                try{response=request(region,a,b,null,stops,c,d,false);}
                catch(Throwable first){fellBack=true;
                    try{response=request(region,a,b,null,toRoads(stops),c,d,false);}
                    catch(Throwable second){response=request(region,a,b,null,stops,c,d,true);usedPrivate=true;}}
            }else if(response==null){
                try{response=routeBetween(region,a,b,c,d);}
                catch(Throwable first){fellBack=true;fallbackPrivate=false;response=fallback(region,a,b,c,d,first);usedPrivate=fallbackPrivate;}
            }
            List<RoutePlan> options=RoutePlan.options(response);
            // Gates are counted here, on the worker, for every option: tapping a route choice then needs no work.
            float[] xy=gates(region);int[] counts=new int[options.size()];for(int i=0;i<counts.length;i++)counts[i]=gatesOn(options.get(i).shape,xy);
            for(RoutePlan option:options)structures(region,option);
            synchronized(this){if(token!=requestId)return;
                // A stop passed while this was calculating: the route still goes back to it, so calculate again.
                if(!stops.equals(remainingStops())){again=true;return;}
                tripStops=Collections.unmodifiableList(stops);passedStops=0;routeChoices=options;routeGates=counts;selectedRoute=0;lastFellBack=fellBack;lastPrivate=usedPrivate;applyRoute(options.get(0),counts[0]);}
            message((simulation?"SIMULATION · ":"Offline route ready · ")+destination);
        // A failed reroute keeps the old route, so guidance goes on and the next reroute is tried 12 s later.
        }catch(Throwable e){boolean riding;synchronized(this){if(token!=requestId)return;riding=active&&shape.size()>1;if(!riding){shape=Collections.emptyList();routeChoices=Collections.emptyList();}}message(riding?"Couldn't find a new route here · keeping the last one":"No offline route: "+e.getMessage());}finally{synchronized(this){if(token==requestId)routing=false;}if(again)route();signal();}});
    }
    /**
     * Marks the turns that go up onto an overpass or down into an underpass ("structure" in the maneuver), from the road
     * graph's bridge and tunnel flags. On the map an overpass and the road beside it are drawn on top of each other and
     * often share a name, so "keep left" alone was easy to miss; the turn card and the voice now name it.
     */
    private void structures(Region region,RoutePlan plan){
        if(plan.shape.size()<2||plan.shape.size()>30000)return;
        try{
            JSONArray points=new JSONArray();for(double[] p:plan.shape)points.put(new JSONObject().put("lat",p[0]).put("lon",p[1]));
            JSONObject req=new JSONObject().put("shape",points).put("costing","motor_scooter").put("shape_match","edge_walk")
                .put("filters",new JSONObject().put("action","include").put("attributes",new JSONArray().put("edge.bridge").put("edge.tunnel").put("edge.begin_shape_index").put("edge.end_shape_index")));
            JSONArray edges=new JSONObject(engine(region).traceAttributesRaw(req.toString())).optJSONArray("edges");
            if(edges==null)return;
            for(int i=0;i<plan.maneuvers.length();i++){JSONObject m=plan.maneuvers.getJSONObject(i);
                if(!Maneuvers.fork(m.optInt("type")))continue;int at=m.optInt("begin_shape_index",-1);if(at<1||at>=plan.cumulative.length)continue;
                // Up to the next turn, and at most 300 m on.
                int limit=i+1<plan.maneuvers.length()?plan.maneuvers.getJSONObject(i+1).optInt("begin_shape_index",at):plan.shape.size()-1;
                while(limit>at&&plan.cumulative[Math.min(limit,plan.cumulative.length-1)]-plan.cumulative[at]>300)limit--;
                String kind=structureAt(edges,at,limit);if(kind!=null)m.put("structure",kind);}
        }catch(Throwable e){android.util.Log.w("Daanvia","Overpass check skipped",e);}
    }
    /**
     * "overpass" or "underpass" when, from the turn at shape point {@code at} up to {@code limit}, the route goes onto a
     * bridge or into a tunnel it wasn't on before the turn; else null. A ramp up to a flyover usually starts at ground
     * level, so the stretch after the turn counts, not just its first road.
     */
    static String structureAt(JSONArray edges,int at,int limit){
        if(at<1)return null;JSONObject before=null;
        for(int i=0;i<edges.length();i++){JSONObject e=edges.optJSONObject(i);if(e!=null&&e.optInt("begin_shape_index",-1)<at&&at<=e.optInt("end_shape_index",-1))before=e;}
        boolean wasBridge=before!=null&&before.optBoolean("bridge"),wasTunnel=before!=null&&before.optBoolean("tunnel");
        for(int i=0;i<edges.length();i++){JSONObject e=edges.optJSONObject(i);if(e==null)continue;int b=e.optInt("begin_shape_index",-1);
            if(b<at||b>limit)continue;
            if(e.optBoolean("bridge")&&!wasBridge)return "overpass";
            if(e.optBoolean("tunnel")&&!wasTunnel)return "underpass";}
        return null;
    }
    /** A route from the rider through an optional pass-through point and the stops (or to the destination). */
    private JSONObject request(Region region,double a,double b,double[] via,List<TripStop> stops,double c,double d,boolean anyAccess)throws Exception{
        JSONArray locations=new JSONArray().put(new JSONObject().put("lat",a).put("lon",b).put("type","break"));
        if(via!=null)locations.put(new JSONObject().put("lat",via[0]).put("lon",via[1]).put("type","through"));
        if(stops.isEmpty())locations.put(new JSONObject().put("lat",c).put("lon",d).put("type","break"));else for(TripStop stop:stops)locations.put(stop.json());
        return avoiding(region,RouteRequest.through(locations,false,anyAccess),a,b);}
    /** Each stop moved to the nearest public road within 300 m, keeping its name; a stop with none stays where it is. */
    private List<TripStop> toRoads(List<TripStop> stops){List<TripStop> out=new ArrayList<>();
        for(TripStop stop:stops){List<double[]> near=nearestRoads(stop.lat,stop.lon,1,300);out.add(near.isEmpty()?stop:new TripStop(stop.name,near.get(0)[0],near.get(0)[1]));}return out;}
    private volatile double[] nextVia;private boolean lastFellBack,lastPrivate,fallbackPrivate;private volatile int[] routeGates=new int[0];
    /** For picking a ride up again: route the next calculation through this point (see chosenVia). */
    void resumeVia(double[] via){nextVia=via;followVia=via;}
    /** The chosen-route point a resumed ride was routed through, kept until the rider passes it. */
    private volatile double[] followVia;
    /**
     * While riding a route the rider chose over the quickest one: a point ahead on it (halfway along what's left, at most
     * 5 km on), so a ride picked up after the phone closed the app follows the same roads. Null on the quickest route or
     * near the end.
     */
    public synchronized double[] chosenVia(){
        List<double[]> route=shape;double[] cum=cumulative;if(route.size()<2||cum.length!=route.size())return null;
        int from=Math.min(progress,route.size()-1);
        // After a resume the route is the only option, so keep the point it was routed through until the rider passes it.
        if(selectedRoute<=0){double[] v=followVia;if(v==null||routeChoices.size()>1)return null;
            int near=from;double best=Double.MAX_VALUE;for(int i=from;i<route.size();i++){double d=GeoMath.distance(v[0],v[1],route.get(i)[0],route.get(i)[1]);if(d<best){best=d;near=i;}}
            if(best>300||near<=from+1){followVia=null;return null;}return v;}
        // The point ahead on the chosen route farthest from the quickest one: where they really differ (they often share
        // the first stretch, and a point there would bring the quickest route back).
        List<double[]> quickest=routeChoices.isEmpty()?Collections.emptyList():routeChoices.get(0).shape;if(quickest.isEmpty())return null;
        int stepA=Math.max(1,(route.size()-from)/120),stepB=Math.max(1,quickest.size()/300);double far=0;double[] pick=null;
        for(int i=from;i<route.size();i+=stepA){double[] p=route.get(i);double near=Double.MAX_VALUE;
            for(int j=0;j<quickest.size();j+=stepB){double d=GeoMath.distance(p[0],p[1],quickest.get(j)[0],quickest.get(j)[1]);if(d<near)near=d;}
            if(near>far){far=near;pick=p;}}
        return far>150?pick:null;
    }
    private JSONObject routeBetween(Region region,double a,double b,double c,double d)throws Exception{
        return avoiding(region,RouteRequest.between(a,b,c,d,!active),a,b);}
    /**
     * Runs a route request keeping away from roads the rider marked blocked (RoadBlocks). If that can't work (a block
     * right at the start or the destination), the same request runs without them.
     */
    private JSONObject avoiding(Region region,JSONObject req,double a,double b)throws Exception{
        JSONArray blocks=RoadBlocks.near(context,a,b,80);
        if(blocks.length()>0)try{JSONObject response=new JSONObject(engine(region).routeRaw(new JSONObject(req.toString()).put("exclude_locations",blocks).toString()));response.getJSONObject("trip");return response;}
        catch(Throwable withBlocks){android.util.Log.w("Daanvia","Route around blocked roads failed",withBlocks);}
        JSONObject response=new JSONObject(engine(region).routeRaw(req.toString()));response.getJSONObject("trip");return response;}
    /**
     * The rider says the road ahead is blocked: the route's next stretch (about 25 m on, past the junction they're at) is
     * remembered as blocked, and a new route is found from where they are, which may mean turning back.
     */
    public void blockAhead(){
        double la,lo;
        synchronized(this){
            if(!active||shape.size()<2){la=lat;lo=lon;}
            else{int i=Math.min(progress,shape.size()-1);double[] cum=cumulative;
                if(cum.length==shape.size()){double want=cum[i]+25;while(i<shape.size()-1&&cum[i]<want)i++;}
                la=shape.get(i)[0];lo=shape.get(i)[1];}
            lastReroute=SystemClock.elapsedRealtime();offRoute=0;
        }
        RoadBlocks.add(context,la,lo);
        message("Road marked as blocked · finding another way");
        route();
    }
    /** Preview changes update the exact geometry and maneuvers used by phone, voice and TFT. */
    public synchronized void selectRoute(int index){
        if(active||routing||index<0||index>=routeChoices.size()||index==selectedRoute)return;
        selectedRoute=index;int[] counts=routeGates;applyRoute(routeChoices.get(index),index<counts.length?counts[index]:0);signal();
    }
    private void applyRoute(RoutePlan plan,int gateCount){
        shape=plan.shape;maneuvers=plan.maneuvers;cumulative=plan.cumulative;
        legEnds=plan.legEnds;
        cumTime=sectionTimes(cumulative,maneuvers,plan.seconds);maneuver=0;progress=0;arrived=false;offRoute=0;
        routeMeters=plan.meters;remaining=routeMeters;routeSeconds=plan.seconds;seconds=routeSeconds*OFFLINE_TIME_ALLOWANCE;
        instruction="Route ready";gates=gateCount;privateRoad=lastPrivate;
        double[] first=shape.get(0),end=shape.get(shape.size()-1);
        // Only a fallback route can stop short of the place or start away from the rider; a big building or campus whose
        // pin is far from any road is a normal route and gets no warning.
        endsShort=lastFellBack&&!lastPrivate?GeoMath.distance(end[0],end[1],destLat,destLon):0;
        startsShort=lastFellBack?GeoMath.distance(first[0],first[1],lat,lon):0;
        coverageGap=null;for(double[] point:shape)if(!inRegion(point[0],point[1])){coverageGap=point;break;}
    }
    /**
     * The router couldn't reach the exact spot: it lay on a scrap of road it can't get to, typically a private lane or a
     * customers-only drive beside the place (the router then reports "search exhausted"). Try the nearest few streets
     * around it instead, named streets first (they are rarely private), then from the nearest street to the rider, then
     * the nearest road within 1.5 km that the router can reach (the route then stops short and the route card says how
     * far). Access checks stay enabled for every attempt. An inaccessible endpoint never disables route access rules.
     */
    private JSONObject fallback(Region region,double a,double b,double c,double d,Throwable first)throws Throwable{
        for(double[] to:nearestRoads(c,d,4,300))try{return routeBetween(region,a,b,to[0],to[1]);}catch(Throwable ignored){}
        List<double[]> froms=nearestRoads(a,b,2,300),tos=nearestRoads(c,d,2,300);
        for(double[] from:froms)for(double[] to:tos)try{return routeBetween(region,from[0],from[1],to[0],to[1]);}catch(Throwable ignored){}
        for(double[] to:nearestRoads(c,d,12,1500)){if(to[2]<260)continue;try{return routeBetween(region,a,b,to[0],to[1]);}catch(Throwable ignored){}}
        // Last resort: nothing public reaches the place (a home deep inside a gated village), so ignore access rules.
        try{JSONObject r=request(region,a,b,null,Collections.emptyList(),c,d,true);fallbackPrivate=true;return r;}catch(Throwable ignored){}
        throw first;}
    private volatile Region gatesFor;private volatile float[] gateXY=new float[0];
    /** The area's gate points (gates.json: gates, lift gates and guardhouse barriers that aren't open to all), as lat,lon pairs. Worker thread. */
    private float[] gates(Region r){if(r==gatesFor)return gateXY;float[] xy=new float[0];
        try{JSONArray a=new JSONArray();for(Region part:r.parts.isEmpty()?Collections.singletonList(r):r.parts){try{JSONArray entries=new JSONArray(text(part,"gates.json"));for(int i=0;i<entries.length();i++)a.put(entries.get(i));}catch(FileNotFoundException ignored){}}xy=new float[a.length()*2];for(int i=0;i<a.length();i++){JSONObject g=a.getJSONObject(i);xy[i*2]=(float)g.getDouble("lat");xy[i*2+1]=(float)g.getDouble("lon");}}
        catch(Exception ignored){}  // areas downloaded before gates were mapped have none
        gatesFor=r;gateXY=xy;return xy;}
    /** Conservative geometry matches, not a count of guardhouses. A nearby driveway gate is not a route crossing.
     * Require a gate at an interior router vertex (2 m allows stored coordinate rounding), with the path passing
     * through it rather than merely ending or turning back there. Duplicate points at one entrance count once. */
    static int gatesOn(List<double[]> route,float[] xy){
        if(route.size()<3||xy.length==0)return 0;
        double minLat=90,maxLat=-90,minLon=180,maxLon=-180;
        for(double[] p:route){minLat=Math.min(minLat,p[0]);maxLat=Math.max(maxLat,p[0]);minLon=Math.min(minLon,p[1]);maxLon=Math.max(maxLon,p[1]);}
        double pad=0.0003;ArrayList<double[]> found=new ArrayList<>();
        for(int i=0;i+1<xy.length;i+=2){
            double la=xy[i],lo=xy[i+1];if(!Double.isFinite(la)||!Double.isFinite(lo))continue;
            if(la<minLat-pad||la>maxLat+pad||lo<minLon-pad||lo>maxLon+pad)continue;
            boolean crossing=false;
            for(int j=1;j+1<route.size()&&!crossing;j++){
                double[] at=route.get(j);if(GeoMath.distance(la,lo,at[0],at[1])>2)continue;
                int before=j-1,after=j+1;
                while(before>=0&&GeoMath.distance(la,lo,route.get(before)[0],route.get(before)[1])<6)before--;
                while(after<route.size()&&GeoMath.distance(la,lo,route.get(after)[0],route.get(after)[1])<6)after++;
                if(before<0||after>=route.size())continue;
                double[] a=route.get(before),b=route.get(after);double k=Math.cos(Math.toRadians(la));
                // Through the gate, straight on or turning (up to about 150 degrees, as into a subdivision's mouth), but
                // not in and back out the same way.
                double ax=(a[1]-lo)*k,ay=a[0]-la,bx=(b[1]-lo)*k,by=b[0]-la,na=Math.hypot(ax,ay),nb=Math.hypot(bx,by);
                crossing=na>0&&nb>0&&(ax*bx+ay*by)/(na*nb)<0.87;
            }
            if(!crossing)continue;
            boolean seen=false;for(double[] f:found)if(GeoMath.distance(f[0],f[1],la,lo)<40){seen=true;break;}
            if(!seen)found.add(new double[]{la,lo});
        }
        return found.size();
    }
    /** Possible mapped gate crossings on the chosen route; map points cannot establish staffed guardhouses. */
    public volatile int gates;public volatile boolean privateRoad;
    /** How far from the destination the route stops, in metres, when the router couldn't reach the place itself. */
    public volatile double endsShort,startsShort;
    public volatile double[] coverageGap;
    private volatile boolean displayLoading;
    private void followArea(double lat,double lon){
        if(displayLoading)return;Region next=Region.at(regions,lat,lon);if(next==null||next==shown)return;
        displayLoading=true;worker.execute(()->{try{if(active){display(next);loadRoads(next);signal();}}catch(Exception e){android.util.Log.w("Daanvia","Could not show next area",e);}finally{displayLoading=false;}});
    }
    /** The closest point on each of the nearest public-looking streets within {@code radius} m, nearest first, a named street counting 40 m closer. */
    private List<double[]> nearestRoads(double lat,double lon,int max,double radius){
        double pad=radius/111000.0,padLon=pad/Math.max(0.2,Math.cos(Math.toRadians(lat)));ArrayList<double[]> found=new ArrayList<>();
        for(Road r:roadIndex.near(lat-pad,lon-padLon,lat+pad,lon+padLon)){
            if(MINOR.contains(r.kind))continue;double bestD=radius;double[] best=null;
            for(int i=0;i+1<r.size();i++){double[] p={r.lat(i),r.lon(i)},q={r.lat(i+1),r.lon(i+1)};double dist=GeoMath.segmentDistance(lat,lon,p,q);if(dist<bestD){bestD=dist;best=GeoMath.project(lat,lon,p,q);}}
            if(best!=null)found.add(new double[]{best[0],best[1],bestD-(r.name.isEmpty()?0:40)});}
        found.sort((x,y)->Double.compare(x[2],y[2]));
        return found.size()>max?new ArrayList<>(found.subList(0,max)):found;}
    private static final Set<String> MINOR=new HashSet<>(Arrays.asList("service","track","footway","path","steps","pedestrian","cycleway","corridor","bridleway","platform"));
    public synchronized void stop(){nextVia=null;followVia=null;active=false;simulation=false;arrived=false;fixAt=0;accuracy=999;requestId++;routing=false;shape=Collections.emptyList();routeChoices=Collections.emptyList();context.getSharedPreferences("ui",0).edit().remove("ride").apply();instruction="Choose a destination";message("Ride ended · tracking stopped");if(reloadAfterRide){reloadAfterRide=false;reload();}}
    public void update(double a,double b,float acc,float direction,boolean demo){update(a,b,acc,direction,demo,SystemClock.elapsedRealtime());}
    /** {@code takenAt} is when the fix was taken (elapsedRealtime). Outside a ride any fix places the rider, however rough; during a ride only sharp ones count. */
    public synchronized void update(double a,double b,float acc,float direction,boolean demo,long takenAt){
        accuracy=acc;if(acc>50&&!demo&&active){signal();return;}lat=a;lon=b;bearing=direction;fixAt=Math.max(1,takenAt);
        viewLat=a;viewLon=b;viewBearing=direction;snapped=false;
        // Remembered so the day/night look matches the rider's sky before the next fix (see Gabi.resolve).
        if(!demo&&SystemClock.elapsedRealtime()-savedFixAt>600000){savedFixAt=SystemClock.elapsedRealtime();context.getSharedPreferences("ui",0).edit().putFloat("fix_lat",(float)a).putFloat("fix_lon",(float)b).apply();}
        List<double[]> route=shape;if(!active||route.size()<2){signal();return;}
        followArea(a,b);
        // Match the rider to the route a few hundred metres ahead at most, and count a stretch running against the rider's
        // heading as farther away: on a divided road the lanes back from a U-turn slot lie metres away, and matching them
        // would jump past the U-turn.
        double best=Double.MAX_VALUE,score=Double.MAX_VALUE;int nearest=progress;double[] cum=cumulative;boolean measured=cum.length==route.size();
        double reach=measured?cum[Math.min(progress,cum.length-1)]+400:Double.MAX_VALUE;
        for(int i=Math.max(0,progress-8);i<route.size()-1;i++){if(measured&&cum[i]>reach)break;double[] p=route.get(i),q=route.get(i+1);double distance=GeoMath.segmentDistance(a,b,p,q);
            double sc=distance+(Math.abs(((GeoMath.bearing(p,q)-direction+540)%360)-180)>100?25:0);if(sc<score){score=sc;best=distance;nearest=i;}}
        if(best<Math.max(45,acc)){progress=Math.max(progress,nearest);offRoute=0;}
        // A stop is passed once the rider reaches its point on the road (the end of its leg), however far the pin sits
        // from the road (inside a mall or a big lot).
        while(passedStops<tripStops.size()-1&&passedStops<legEnds.length&&progress>=legEnds[passedStops]-1){
            int end=Math.min(legEnds[passedStops],route.size()-1);double[] at=route.get(end);
            if(progress<legEnds[passedStops]&&GeoMath.distance(a,b,at[0],at[1])>45)break;passedStops++;
        }
        // Draw the rider on the road being followed, pointing along it, like car navigation does; GPS scatter stays hidden.
        if(best<Math.min(45,Math.max(25,acc))){double[] p1=route.get(nearest),p2=route.get(Math.min(nearest+1,route.size()-1));double[] on=GeoMath.project(a,b,p1,p2);viewLat=on[0];viewLon=on[1];if(p1!=p2)viewBearing=GeoMath.bearing(p1,p2);snapped=true;}else if(acc<35&&best>65&&!arrived&&GeoMath.distance(a,b,destLat,destLon)>150){offRoute++;if(!demo&&offRoute>=3&&!routing&&SystemClock.elapsedRealtime()-lastReroute>12000){lastReroute=SystemClock.elapsedRealtime();offRoute=0;route();}}
        // Distance and time left: to the next route point, then the rest from the running totals. The time left adds up
        // the router's own times for the stretches still ahead, so a slow city stretch near the end counts as slow.
        int nxt=Math.min(progress+1,route.size()-1);double toNext=GeoMath.distance(a,b,route.get(nxt)[0],route.get(nxt)[1]);double[] ct=cumTime;double rem,remSecs;
        if(measured&&ct.length==route.size()){rem=toNext+cum[cum.length-1]-cum[nxt];int prev=Math.max(0,nxt-1);double segLen=cum[nxt]-cum[prev];
            remSecs=ct[ct.length-1]-ct[nxt]+(segLen>0?toNext*(ct[nxt]-ct[prev])/segLen:0);}
        else{rem=toNext;for(int i=nxt;i<route.size()-1;i++)rem+=GeoMath.distance(route.get(i)[0],route.get(i)[1],route.get(i+1)[0],route.get(i+1)[1]);remSecs=routeMeters>0?routeSeconds*rem/routeMeters:0;}
        remaining=rem;seconds=remSecs*OFFLINE_TIME_ALLOWANCE;
        try{
            int next=0;for(int i=0;i<maneuvers.length();i++)if(maneuvers.getJSONObject(i).optInt("begin_shape_index")>progress){next=i;break;}
            if(next==0)next=Math.max(0,maneuvers.length()-1);maneuver=next;JSONObject m=maneuvers.getJSONObject(next);int at=Math.min(route.size()-1,m.optInt("begin_shape_index"));double[] prefix=cumulative;int ahead=Math.min(progress+1,route.size()-1);distanceToTurn=at>=ahead&&prefix.length==route.size()?GeoMath.distance(a,b,route.get(ahead)[0],route.get(ahead)[1])+prefix[at]-prefix[ahead]:0;instruction=m.optString("instruction","Continue");
            // Near the picked point, or at the route's end where the road stops short of a pin inside a campus or mall.
            double[] end=route.get(route.size()-1);
            if(arrived||rem<35&&(GeoMath.distance(a,b,destLat,destLon)<45||GeoMath.distance(a,b,end[0],end[1])<30)){instruction="Destination nearby";
                if(!arrived){arrived=true;context.getSharedPreferences("ui",0).edit().remove("ride").apply();message((demo?"SIMULATION · ":"")+"Destination nearby");}}
        }catch(JSONException ignored){}signal();
    }
    private void refreshParking(){ArrayList<JSONObject> list=new ArrayList<>();for(int i=0;i<parking.length();i++){JSONObject p=parking.optJSONObject(i);if(p!=null&&GeoMath.distance(p.optDouble("lat"),p.optDouble("lon"),destLat,destLon)<=350)list.add(p);}list.sort((a,b)->Double.compare(GeoMath.distance(a.optDouble("lat"),a.optDouble("lon"),destLat,destLon),GeoMath.distance(b.optDouble("lat"),b.optDouble("lon"),destLat,destLon)));nearbyParking=Collections.unmodifiableList(list.size()>8?new ArrayList<>(list.subList(0,8)):list);}
    private void mergeSavedParking(){try{JSONArray saved=new JSONArray(context.getSharedPreferences("parking-local",0).getString("records","[]"));for(int i=0;i<saved.length();i++)parking.put(saved.getJSONObject(i));}catch(JSONException ignored){}}
    public synchronized void saveParking(double a,double b,String name,String amount,String basis,boolean entrance)throws Exception{
        JSONObject lot=new JSONObject().put("id",UUID.randomUUID().toString()).put("lat",a).put("lon",b).put("name",name).put("source","Owner-entered").put("motorcycle",true).put("entrance_confirmed",entrance);
        if(!amount.trim().isEmpty()){java.math.BigDecimal price=new java.math.BigDecimal(amount.trim());if(price.signum()<0||price.compareTo(new java.math.BigDecimal("100000"))>0)throw new IllegalArgumentException("Invalid parking fee");lot.put("price_label","₱"+price.stripTrailingZeros().toPlainString()+" / "+basis).put("verified_at",System.currentTimeMillis());}
        JSONArray saved=new JSONArray(context.getSharedPreferences("parking-local",0).getString("records","[]"));saved.put(lot);context.getSharedPreferences("parking-local",0).edit().putString("records",saved.toString()).apply();parking.put(lot);refreshParking();signal();
    }
    private String fitStore(){String raw=context.getSharedPreferences("saved-bike",0).getString("qr","");try{QrProfile p=QrProfile.parse(raw);return "display-fit-"+Integer.toHexString((p.mac+"/"+p.ssid+"/"+p.model).hashCode());}catch(Exception e){return "display-fit-generic";}}
    /**
     * What the bike told us about its screen, kept per bike: the panel's own pixels (CLIENT_INFO screenWidth/Height), the
     * video size it asks for, the frame rate it actually pulls, and its screen type. 0 / "" until a bike has connected.
     */
    public volatile int panelW,panelH,videoW,videoH,bikeFps;public volatile String screenType="";
    /** The panel's width÷height when it differs from the video's and correcting it is on; 0 to draw the video as it is. */
    /** The bike display shows only the turn and trip cards, no moving map (Settings › Bike display), to save battery. */
    public boolean directionsOnly(){return context.getSharedPreferences("ui",0).getBoolean("directionsonly",false);}
    public float panelAspect(){return panelW>0&&panelH>0&&context.getSharedPreferences("ui",0).getBoolean("matchshape",true)?(float)panelW/panelH:0;}
    /** Record what the bike reported (values ≤ 0 or null leave what is known). */
    public void screenReport(int pw,int ph,int vw,int vh,int fps,String type){
        if(pw>0&&ph>0){panelW=pw;panelH=ph;}if(vw>0&&vh>0){videoW=vw;videoH=vh;}if(fps>0)bikeFps=fps;if(type!=null&&!type.isEmpty())screenType=type;
        context.getSharedPreferences(fitStore(),0).edit().putString("screen",panelW+","+panelH+","+videoW+","+videoH+","+bikeFps+","+screenType.replace(",",";")).apply();signal();}
    private void loadScreen(android.content.SharedPreferences p){panelW=panelH=videoW=videoH=bikeFps=0;screenType="";
        String[] v=p.getString("screen","").split(",",-1);if(v.length<6)return;
        try{panelW=Integer.parseInt(v[0]);panelH=Integer.parseInt(v[1]);videoW=Integer.parseInt(v[2]);videoH=Integer.parseInt(v[3]);bikeFps=Integer.parseInt(v[4]);screenType=v[5];}catch(NumberFormatException ignored){}}
    public void loadFit(){android.content.SharedPreferences p=context.getSharedPreferences(fitStore(),0);insetLeft=p.getFloat("left",0);insetTop=p.getFloat("top",0);insetRight=p.getFloat("right",0);insetBottom=p.getFloat("bottom",0);textScale=p.getFloat("text",1);layout=p.getString("layout","Map focus");cards(p.getString("cards",""));loadScreen(p);}
    public void saveFit(){context.getSharedPreferences(fitStore(),0).edit().putFloat("left",insetLeft).putFloat("top",insetTop).putFloat("right",insetRight).putFloat("bottom",insetBottom).putFloat("text",textScale).putString("layout",layout).putString("cards",cards(turnCard,tripCard,arrowX,arrowY)).apply();signal();}
}
