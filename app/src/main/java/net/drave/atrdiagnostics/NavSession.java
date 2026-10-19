package net.drave.atrdiagnostics;
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
    public volatile float accuracy=999,bearing;
    /** Where to draw the rider: on the route line while following it (snapped, heading along the road), else the raw fix. Routing always uses the raw fix. */
    public volatile double viewLat=10.3157,viewLon=123.8854;public volatile float viewBearing;public volatile boolean snapped;
    public volatile long fixAt;
    public volatile List<double[]> shape=Collections.emptyList();
    public volatile JSONArray parking=new JSONArray(),places=new JSONArray();
    public volatile List<Road> roads=Collections.emptyList();
    public volatile List<JSONObject> nearbyParking=Collections.emptyList();
    public volatile JSONArray maneuvers=new JSONArray();
    public volatile int maneuver,progress;
    public volatile double[] cumulative=new double[0];private double routeMeters,routeSeconds;
    private final HashMap<String,Valhalla> engines=new HashMap<>();private volatile Region routeRegion,shown;private long lastReroute;private int offRoute,requestId;
    /** Every installed offline pack, best source first; the map, search and parking data come from {@link #regionId}. */
    public volatile List<Region> regions=Collections.emptyList();public volatile String regionId="";public volatile int regionVersion;
    public volatile String roadsJson="{\"type\":\"FeatureCollection\",\"features\":[]}";
    public double minLat,maxLat,minLon,maxLon;
    public volatile String regionName="Offline region";public volatile double[] demoFrom={10.3157,123.8854},demoTo={10.2934,123.9019};public volatile String demoName="Sample route";
    public volatile double[] home;public double homeLat(){return home!=null?home[0]:ready?(minLat+maxLat)/2:lat;}public double homeLon(){return home!=null?home[1]:ready?(minLon+maxLon)/2:lon;}
    public float insetLeft,insetTop,insetRight,insetBottom,textScale=1;public String layout="Map focus";
    public static final class Road {public final double[][] points;public final String name,kind;public double minLat=90,maxLat=-90,minLon=180,maxLon=-180;Road(double[][] p,String n,String k){points=p;name=n;kind=k;for(double[] a:p){minLat=Math.min(minLat,a[0]);maxLat=Math.max(maxLat,a[0]);minLon=Math.min(minLon,a[1]);maxLon=Math.max(maxLon,a[1]);}}}
    /** One offline pack: region.json plus its graph and data, either bundled under assets/ or unpacked in a directory. Areas are [minLon,minLat,maxLon,maxLat] boxes. */
    public static final class Region {
        public final String id,name;public final double[] bounds;public final double[][] areas;final String asset;final File dir;
        Region(String id,String name,double[] bounds,double[][] areas,String asset,File dir){this.id=id;this.name=name;this.bounds=bounds;this.areas=areas==null||areas.length==0?new double[][]{bounds}:areas;this.asset=asset;this.dir=dir;}
        public boolean contains(double lat,double lon){for(double[] a:areas)if(lon>=a[0]&&lon<=a[2]&&lat>=a[1]&&lat<=a[3])return true;return false;}
        double size(){double s=0;for(double[] a:areas)s+=(a[2]-a[0])*(a[3]-a[1]);return s;}
        /** The smallest pack holding both points (a city pack beats its island pack), or null when no single pack can route between them. */
        public static Region pick(List<Region> list,double aLat,double aLon,double bLat,double bLon){Region best=null;for(Region r:list)if(r.contains(aLat,aLon)&&r.contains(bLat,bLon)&&(best==null||r.size()<best.size()))best=r;return best;}
        public static Region at(List<Region> list,double lat,double lon){return pick(list,lat,lon,lat,lon);}
    }
    private NavSession(Context c){context=c;loadFit();worker.execute(this::load);}
    public void signal(){context.sendBroadcast(new Intent(EVENT).setPackage(context.getPackageName()));}
    private final ArrayDeque<String> logs=new ArrayDeque<>();
    public void message(String text){status=text;String clean=destination.isEmpty()?text:text.replace(destination,"[destination]");clean=clean.replaceAll("[-+]?[0-9]{1,3}\\.[0-9]{4,}","[coordinate]");synchronized(logs){if(logs.size()>=150)logs.removeFirst();logs.addLast(new java.text.SimpleDateFormat("HH:mm:ss",java.util.Locale.US).format(new java.util.Date())+" "+clean);}signal();}
    public String report(){try{JSONObject j=new JSONObject().put("app","Daanvia "+BuildInfo.VERSION).put("region_ready",ready).put("navigation_active",active).put("simulation",simulation).put("route_points",shape.size()).put("gps_age_seconds",fixAt==0?-1:(SystemClock.elapsedRealtime()-fixAt)/1000).put("offline_region",regionId).put("installed_regions",regions.size()).put("layout",layout).put("location_and_identifiers","omitted");synchronized(logs){j.put("log",new JSONArray(logs));}return j.toString(2);}catch(Exception e){return "Report unavailable";}}
    private String asset(String p)throws Exception{return read(context.getAssets().open(p));}
    private static String read(InputStream stream)throws IOException{try(InputStream in=stream){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toString("UTF-8");}}
    private String text(Region r,String file)throws Exception{return r.asset!=null?asset(r.asset+"/"+file):read(new FileInputStream(new File(r.dir,file)));}
    /** Reads a data file such as "lots.json" from the pack now shown ({@link #regionId}); FileNotFoundException when that pack has none. Off the UI thread; reload when {@link #regionVersion} changes. */
    public String regionText(String file)throws Exception{Region r=shown;if(r==null)throw new FileNotFoundException(file);return text(r,file);}
    private static Region region(JSONObject j,String asset,File dir)throws JSONException{
        JSONArray b=j.getJSONArray("bounds");double[] bounds={b.getDouble(0),b.getDouble(1),b.getDouble(2),b.getDouble(3)};JSONArray a=j.optJSONArray("areas");double[][] areas=null;
        if(a!=null){areas=new double[a.length()][];for(int i=0;i<a.length();i++){JSONArray x=a.getJSONArray(i);areas[i]=new double[]{x.getDouble(0),x.getDouble(1),x.getDouble(2),x.getDouble(3)};}}
        return new Region(j.getString("id"),j.optString("display_name",j.optString("name",j.getString("id"))),bounds,areas,asset,dir);}
    /** Packs unpacked in app storage (downloaded, or copied from a Play asset pack), then packs copied over USB to Android/data/…/files/regions, then the APK's own. The first copy of an id wins. */
    private List<Region> discover(){ArrayList<Region> list=new ArrayList<>();HashSet<String> ids=new HashSet<>();
        for(File base:new File[]{new File(context.getFilesDir(),"regions"),context.getExternalFilesDir("regions")}){File[] dirs=base==null?null:base.listFiles();if(dirs==null)continue;Arrays.sort(dirs);
            for(File d:dirs)try{if(new File(d,"valhalla_tiles.tar").isFile()||new File(d,"valhalla").isDirectory())add(list,ids,region(new JSONObject(read(new FileInputStream(new File(d,"region.json")))),null,d));}catch(Exception e){android.util.Log.w("Daanvia","Skipping offline pack "+d.getName(),e);}}
        try{String[] bundled=context.getAssets().list("regions");if(bundled!=null){Arrays.sort(bundled);for(String n:bundled)add(list,ids,region(new JSONObject(asset("regions/"+n+"/region.json")),"regions/"+n,null));}}catch(Exception e){android.util.Log.w("Daanvia","Bundled packs unreadable",e);}
        try{add(list,ids,region(new JSONObject(asset("cebu/region.json")),"cebu",null));}catch(Exception ignored){}
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
        JSONObject region=new JSONObject(text(r,"region.json"));String json=text(r,"roads.geojson");ArrayList<Road> list=new ArrayList<>();
        try(android.util.JsonReader reader=new android.util.JsonReader(new StringReader(json))){
            reader.beginObject();while(reader.hasNext()){String key=reader.nextName();if(!"features".equals(key)){reader.skipValue();continue;}reader.beginArray();while(reader.hasNext()){
                String name="",kind="";ArrayList<double[]> points=new ArrayList<>();reader.beginObject();while(reader.hasNext()){String field=reader.nextName();
                    if("properties".equals(field)){reader.beginObject();while(reader.hasNext()){String property=reader.nextName();if("name".equals(property))name=reader.nextString();else if("kind".equals(property))kind=reader.nextString();else reader.skipValue();}reader.endObject();}
                    else if("geometry".equals(field)){reader.beginObject();while(reader.hasNext()){if(!"coordinates".equals(reader.nextName())){reader.skipValue();continue;}reader.beginArray();while(reader.hasNext()){reader.beginArray();double longitude=reader.nextDouble(),latitude=reader.nextDouble();while(reader.hasNext())reader.skipValue();reader.endArray();points.add(new double[]{latitude,longitude});}reader.endArray();}reader.endObject();}
                    else reader.skipValue();
                }reader.endObject();list.add(new Road(points.toArray(new double[0][]),name,kind));
            }reader.endArray();}reader.endObject();
        }
        JSONArray placeList=new JSONArray(text(r,"places.json")),parkingList=new JSONArray(text(r,"parking.json"));
        minLon=r.bounds[0];minLat=r.bounds[1];maxLon=r.bounds[2];maxLat=r.bounds[3];regionName=r.name;
        JSONObject demo=region.optJSONObject("demo");if(demo!=null){JSONArray f=demo.getJSONArray("from"),t=demo.getJSONArray("to");demoFrom=new double[]{f.getDouble(0),f.getDouble(1)};demoTo=new double[]{t.getDouble(0),t.getDouble(1)};demoName=demo.optString("name",demoName);}JSONArray hc=region.optJSONArray("home");home=hc!=null?new double[]{hc.getDouble(0),hc.getDouble(1)}:null;
        roadsJson=json;roads=Collections.unmodifiableList(list);places=placeList;parking=parkingList;mergeSavedParking();
        shown=r;regionId=r.id;regionVersion++;context.getSharedPreferences("offline-region",0).edit().putString("shown",r.id).apply();
    }
    /** Worker thread only. A bundled graph is copied out of the APK once (again when its bundled size changes); an unpacked pack's graph is read in place. */
    private Valhalla engine(Region r)throws Exception{Valhalla e=engines.get(r.id);if(e!=null)return e;
        File dir=new File(context.getFilesDir(),r.asset==null?"engines/"+r.id:"cebu".equals(r.asset)?"cebu-v1":"region-"+r.id);if(!dir.exists()&&!dir.mkdirs())throw new IOException("Cannot create offline storage");
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
        e=new Valhalla(config.getAbsolutePath());engines.put(r.id,e);return e;}
    /** Look for installed packs again, after a download or removal. A ride in progress keeps its pack. */
    public void reload(){reload(null);}
    /** Re-scan installed areas and show {@code prefer} if given (a fresh download), else the best one for where the rider is. */
    public void reload(String prefer){worker.execute(()->{if(active)return;preferred=prefer;load();preferred=null;});}
    private volatile String preferred;
    /** Show the downloaded area at a map position the rider is looking at, so panning into another area swaps its roads, search and parking in. One area is held in memory at a time. */
    public void showAt(double lat,double lon){if(!ready||active||routing)return;Region r=Region.at(regions,lat,lon);if(r==null||r==shown)return;
        worker.execute(()->{if(active||r==shown)return;try{display(r);routeRegion=r;message(regionName+" offline · choose a destination");}catch(Throwable e){android.util.Log.e("Daanvia","Area switch failed",e);}});}
    /** Drop a removed pack's routing engine so its files are no longer held. Worker thread. */
    public void forget(String id){worker.execute(()->engines.remove(id));}
    private void load(){try{
        List<Region> found=discover();regions=Collections.unmodifiableList(found);
        if(found.isEmpty()){ready=false;noMap=true;shown=null;regionId="";regionVersion++;roads=Collections.emptyList();places=new JSONArray();parking=new JSONArray();roadsJson="{\"type\":\"FeatureCollection\",\"features\":[]}";message("No map installed");return;}
        noMap=false;
        Region first=null;if(preferred!=null)for(Region r:found)if(r.id.equals(preferred))first=r;if(first==null){android.content.SharedPreferences cam=context.getSharedPreferences("ui",0);if(cam.contains("cam_lat"))first=Region.at(found,cam.getFloat("cam_lat",0),cam.getFloat("cam_lon",0));}if(first==null)first=choose(found);display(first);routeRegion=first;engine(first);ready=true;message(regionName+" offline · choose a destination");
    }catch(Throwable e){message("Offline region could not load: "+e.getClass().getSimpleName()+". Open Support.");android.util.Log.e("Daanvia","Offline load failed",e);}}
    public boolean inRegion(double a,double b){for(Region r:regions)if(r.contains(a,b))return true;return false;}
    public void plan(double a,double b,String name,boolean demo){
        if(!ready){message("Wait for the offline region to finish loading");return;}
        if(routing){message("Route calculation in progress · wait before changing destination");return;}
        final Region r=Region.pick(regions,lat,lon,a,b);
        if(r==null){message(inRegion(a,b)&&inRegion(lat,lon)?"Start and destination are in different offline regions":regions.size()>1?"Outside the installed offline regions":"Outside the installed offline region ("+regionName+")");return;}
        simulation=demo;destLat=a;destLon=b;destination=name;routeRegion=r;
        if(r!=shown)worker.execute(()->{try{display(r);}catch(Throwable e){android.util.Log.e("Daanvia","Region switch failed",e);}refreshParking();signal();});else refreshParking();
        route();
    }
    private synchronized void route(){if(routing)return;final int token=++requestId;routing=true;message("Calculating on this phone…");final double a=lat,b=lon,c=destLat,d=destLon;final Region region=routeRegion;
        worker.execute(()->{try{
            JSONObject req=new JSONObject().put("locations",new JSONArray().put(new JSONObject().put("lat",a).put("lon",b)).put(new JSONObject().put("lat",c).put("lon",d)))
                .put("costing","motor_scooter").put("units","kilometers").put("language","en-US")
                .put("costing_options",new JSONObject().put("motor_scooter",new JSONObject().put("use_highways",0).put("use_tolls",0).put("top_speed",80)));
            JSONObject response=new JSONObject(engine(region).routeRaw(req.toString()));JSONObject trip=response.getJSONObject("trip");JSONArray legs=trip.getJSONArray("legs");JSONObject leg=legs.getJSONObject(0);
            List<double[]> route=GeoMath.decode(leg.getString("shape"));if(route.size()<2)throw new IOException("Empty route");
            synchronized(this){if(token!=requestId)return;shape=Collections.unmodifiableList(route);maneuvers=leg.getJSONArray("maneuvers");maneuver=0;progress=0;double[] prefix=new double[route.size()];for(int j=1;j<route.size();j++)prefix[j]=prefix[j-1]+GeoMath.distance(route.get(j-1)[0],route.get(j-1)[1],route.get(j)[0],route.get(j)[1]);cumulative=prefix;routeMeters=prefix[prefix.length-1];remaining=routeMeters;routeSeconds=trip.getJSONObject("summary").getDouble("time");seconds=routeSeconds;instruction="Route ready";}
            message((simulation?"SIMULATION · ":"Offline route ready · ")+destination);
        }catch(Throwable e){synchronized(this){if(token!=requestId)return;shape=Collections.emptyList();}message("No offline route: "+e.getMessage());}finally{routing=false;signal();}});
    }
    public synchronized void stop(){active=false;simulation=false;fixAt=0;accuracy=999;requestId++;shape=Collections.emptyList();instruction="Choose a destination";message("Ride ended · tracking stopped");}
    public synchronized void update(double a,double b,float acc,float direction,boolean demo){
        accuracy=acc;if(acc>50&&!demo){signal();return;}lat=a;lon=b;bearing=direction;fixAt=SystemClock.elapsedRealtime();
        viewLat=a;viewLon=b;viewBearing=direction;snapped=false;
        List<double[]> route=shape;if(!active||route.size()<2){signal();return;}
        double best=Double.MAX_VALUE;int nearest=progress;
        for(int i=Math.max(0,progress-8);i<route.size()-1;i++){double distance=GeoMath.segmentDistance(a,b,route.get(i),route.get(i+1));if(distance<best){best=distance;nearest=i;}}
        if(best<Math.max(45,acc)){progress=Math.max(progress,nearest);offRoute=0;}
        // Draw the rider on the road being followed, pointing along it, like car navigation does; GPS scatter stays hidden.
        if(best<Math.min(45,Math.max(25,acc))){double[] p1=route.get(nearest),p2=route.get(Math.min(nearest+1,route.size()-1));double[] on=GeoMath.project(a,b,p1,p2);viewLat=on[0];viewLon=on[1];if(p1!=p2)viewBearing=GeoMath.bearing(p1,p2);snapped=true;}else if(acc<35&&best>65){offRoute++;if(!demo&&offRoute>=3&&!routing&&SystemClock.elapsedRealtime()-lastReroute>12000){lastReroute=SystemClock.elapsedRealtime();offRoute=0;route();}}
        double rem=GeoMath.distance(a,b,route.get(Math.min(progress+1,route.size()-1))[0],route.get(Math.min(progress+1,route.size()-1))[1]);
        for(int i=progress+1;i<route.size()-1;i++)rem+=GeoMath.distance(route.get(i)[0],route.get(i)[1],route.get(i+1)[0],route.get(i+1)[1]);remaining=rem;seconds=routeMeters>0?routeSeconds*remaining/routeMeters:0;
        try{
            int next=0;for(int i=0;i<maneuvers.length();i++)if(maneuvers.getJSONObject(i).optInt("begin_shape_index")>progress){next=i;break;}
            if(next==0)next=Math.max(0,maneuvers.length()-1);maneuver=next;JSONObject m=maneuvers.getJSONObject(next);int at=Math.min(route.size()-1,m.optInt("begin_shape_index"));double[] prefix=cumulative;int ahead=Math.min(progress+1,route.size()-1);distanceToTurn=at>=ahead&&prefix.length==route.size()?GeoMath.distance(a,b,route.get(ahead)[0],route.get(ahead)[1])+prefix[at]-prefix[ahead]:0;instruction=m.optString("instruction","Continue");
            if(rem<35&&GeoMath.distance(a,b,destLat,destLon)<45){instruction="Destination nearby";message((demo?"SIMULATION · ":"")+"Destination nearby");}
        }catch(JSONException ignored){}signal();
    }
    private void refreshParking(){ArrayList<JSONObject> list=new ArrayList<>();for(int i=0;i<parking.length();i++){JSONObject p=parking.optJSONObject(i);if(p!=null&&GeoMath.distance(p.optDouble("lat"),p.optDouble("lon"),destLat,destLon)<=700)list.add(p);}list.sort((a,b)->Double.compare(GeoMath.distance(a.optDouble("lat"),a.optDouble("lon"),destLat,destLon),GeoMath.distance(b.optDouble("lat"),b.optDouble("lon"),destLat,destLon)));nearbyParking=Collections.unmodifiableList(list);}
    private void mergeSavedParking(){try{JSONArray saved=new JSONArray(context.getSharedPreferences("parking-local",0).getString("records","[]"));for(int i=0;i<saved.length();i++)parking.put(saved.getJSONObject(i));}catch(JSONException ignored){}}
    public synchronized void saveParking(double a,double b,String name,String amount,String basis)throws Exception{
        JSONObject lot=new JSONObject().put("id",UUID.randomUUID().toString()).put("lat",a).put("lon",b).put("name",name).put("source","Owner-entered").put("motorcycle",true).put("access","yes");
        if(!amount.trim().isEmpty()){java.math.BigDecimal price=new java.math.BigDecimal(amount.trim());if(price.signum()<0||price.compareTo(new java.math.BigDecimal("100000"))>0)throw new IllegalArgumentException("Invalid parking fee");lot.put("price_label","₱"+price.stripTrailingZeros().toPlainString()+" / "+basis).put("verified_at",System.currentTimeMillis());}
        JSONArray saved=new JSONArray(context.getSharedPreferences("parking-local",0).getString("records","[]"));saved.put(lot);context.getSharedPreferences("parking-local",0).edit().putString("records",saved.toString()).apply();parking.put(lot);refreshParking();signal();
    }
    private String fitStore(){String raw=context.getSharedPreferences("saved-bike",0).getString("qr","");try{QrProfile p=QrProfile.parse(raw);return "display-fit-"+Integer.toHexString((p.mac+"/"+p.ssid+"/"+p.model).hashCode());}catch(Exception e){return "display-fit-generic";}}
    public void loadFit(){android.content.SharedPreferences p=context.getSharedPreferences(fitStore(),0);insetLeft=p.getFloat("left",0);insetTop=p.getFloat("top",0);insetRight=p.getFloat("right",0);insetBottom=p.getFloat("bottom",0);textScale=p.getFloat("text",1);layout=p.getString("layout","Map focus");}
    public void saveFit(){context.getSharedPreferences(fitStore(),0).edit().putFloat("left",insetLeft).putFloat("top",insetTop).putFloat("right",insetRight).putFloat("bottom",insetBottom).putFloat("text",textScale).putString("layout",layout).apply();signal();}
    public String routeJson(){try{JSONArray points=new JSONArray();for(double[] p:shape)points.put(new JSONArray().put(p[1]).put(p[0]));return new JSONObject().put("type","Feature").put("properties",new JSONObject()).put("geometry",new JSONObject().put("type","LineString").put("coordinates",points)).toString();}catch(Exception e){return "{\"type\":\"FeatureCollection\",\"features\":[]}";}}
}
