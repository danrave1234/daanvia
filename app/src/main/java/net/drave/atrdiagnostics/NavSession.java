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
    public volatile boolean ready,active,simulation,calibration,routing;
    public volatile String status="Preparing offline Metro Cebu…",instruction="Choose a destination",destination="";
    public volatile double lat=10.3157,lon=123.8854,destLat,destLon,remaining,seconds,distanceToTurn;
    public volatile float accuracy=999,bearing;
    public volatile long fixAt;
    public volatile List<double[]> shape=Collections.emptyList();
    public volatile JSONArray parking=new JSONArray(),places=new JSONArray();
    public volatile List<Road> roads=Collections.emptyList();
    public volatile List<JSONObject> nearbyParking=Collections.emptyList();
    public volatile JSONArray maneuvers=new JSONArray();
    public volatile int maneuver,progress;
    public volatile double[] cumulative=new double[0];private double routeMeters,routeSeconds;
    private Valhalla engine;private long lastReroute;private int offRoute,requestId;
    public volatile String roadsJson="{\"type\":\"FeatureCollection\",\"features\":[]}";
    public double minLat,maxLat,minLon,maxLon;
    public float insetLeft,insetTop,insetRight,insetBottom,textScale=1;public String layout="Map focus";
    public static final class Road {public final double[][] points;public final String name,kind;public double minLat=90,maxLat=-90,minLon=180,maxLon=-180;Road(double[][] p,String n,String k){points=p;name=n;kind=k;for(double[] a:p){minLat=Math.min(minLat,a[0]);maxLat=Math.max(maxLat,a[0]);minLon=Math.min(minLon,a[1]);maxLon=Math.max(maxLon,a[1]);}}}
    private NavSession(Context c){context=c;loadFit();worker.execute(this::load);}
    public void signal(){context.sendBroadcast(new Intent(EVENT).setPackage(context.getPackageName()));}
    private final ArrayDeque<String> logs=new ArrayDeque<>();
    public void message(String text){status=text;String clean=destination.isEmpty()?text:text.replace(destination,"[destination]");clean=clean.replaceAll("[-+]?[0-9]{1,3}\\.[0-9]{4,}","[coordinate]");synchronized(logs){if(logs.size()>=150)logs.removeFirst();logs.addLast(new java.text.SimpleDateFormat("HH:mm:ss",java.util.Locale.US).format(new java.util.Date())+" "+clean);}signal();}
    public String report(){try{JSONObject j=new JSONObject().put("app","Daanvia 0.4.0-alpha1").put("region_ready",ready).put("navigation_active",active).put("simulation",simulation).put("route_points",shape.size()).put("gps_age_seconds",fixAt==0?-1:(SystemClock.elapsedRealtime()-fixAt)/1000).put("layout",layout).put("location_and_identifiers","omitted");synchronized(logs){j.put("log",new JSONArray(logs));}return j.toString(2);}catch(Exception e){return "Report unavailable";}}
    private String asset(String p)throws Exception{try(InputStream in=context.getAssets().open(p)){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toString("UTF-8");}}
    private void load(){try{
        File dir=new File(context.getFilesDir(),"cebu-v1");if(!dir.exists()&&!dir.mkdirs())throw new IOException("Cannot create offline storage");
        File tiles=new File(dir,"valhalla_tiles.tar");
        if(!tiles.exists()){File temp=new File(dir,"tiles.part");try(InputStream in=context.getAssets().open("cebu/valhalla_tiles.tar");FileOutputStream out=new FileOutputStream(temp)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);out.getFD().sync();}if(!temp.renameTo(tiles))throw new IOException("Cannot install region");}
        JSONObject region=new JSONObject(asset("cebu/region.json"));JSONArray box=region.getJSONArray("bounds");minLon=box.getDouble(0);minLat=box.getDouble(1);maxLon=box.getDouble(2);maxLat=box.getDouble(3);
        roadsJson=asset("cebu/roads.geojson");ArrayList<Road> list=new ArrayList<>();
        try(android.util.JsonReader reader=new android.util.JsonReader(new StringReader(roadsJson))){
            reader.beginObject();while(reader.hasNext()){String key=reader.nextName();if(!"features".equals(key)){reader.skipValue();continue;}reader.beginArray();while(reader.hasNext()){
                String name="",kind="";ArrayList<double[]> points=new ArrayList<>();reader.beginObject();while(reader.hasNext()){String field=reader.nextName();
                    if("properties".equals(field)){reader.beginObject();while(reader.hasNext()){String property=reader.nextName();if("name".equals(property))name=reader.nextString();else if("kind".equals(property))kind=reader.nextString();else reader.skipValue();}reader.endObject();}
                    else if("geometry".equals(field)){reader.beginObject();while(reader.hasNext()){if(!"coordinates".equals(reader.nextName())){reader.skipValue();continue;}reader.beginArray();while(reader.hasNext()){reader.beginArray();double longitude=reader.nextDouble(),latitude=reader.nextDouble();while(reader.hasNext())reader.skipValue();reader.endArray();points.add(new double[]{latitude,longitude});}reader.endArray();}reader.endObject();}
                    else reader.skipValue();
                }reader.endObject();list.add(new Road(points.toArray(new double[0][]),name,kind));
            }reader.endArray();}reader.endObject();
        }
        roads=Collections.unmodifiableList(list);places=new JSONArray(asset("cebu/places.json"));parking=new JSONArray(asset("cebu/parking.json"));mergeSavedParking();
        JSONObject cfg=new JSONObject(asset("cebu/valhalla-template.json"));cfg.getJSONObject("mjolnir").put("tile_extract",tiles.getAbsolutePath()).put("tile_dir",dir.getAbsolutePath()).remove("tile_url");
        File config=new File(dir,"valhalla.json");try(FileOutputStream out=new FileOutputStream(config)){out.write(cfg.toString().getBytes("UTF-8"));}
        engine=new Valhalla(config.getAbsolutePath());ready=true;message("Metro Cebu offline · choose a destination");
    }catch(Throwable e){message("Offline region could not load: "+e.getClass().getSimpleName()+". Open Support.");android.util.Log.e("Daanvia","Offline load failed",e);}}
    public boolean inRegion(double a,double b){return a>=minLat&&a<=maxLat&&b>=minLon&&b<=maxLon;}
    public void plan(double a,double b,String name,boolean demo){
        if(!ready){message("Wait for the offline region to finish loading");return;}
        if(routing){message("Route calculation in progress · wait before changing destination");return;}
        if(!inRegion(a,b)||!inRegion(lat,lon)){message("Outside the bundled Metro Cebu region");return;}
        simulation=demo;destLat=a;destLon=b;destination=name;refreshParking();route();
    }
    private synchronized void route(){if(routing)return;final int token=++requestId;routing=true;message("Calculating on this phone…");final double a=lat,b=lon,c=destLat,d=destLon;
        worker.execute(()->{try{
            JSONObject req=new JSONObject().put("locations",new JSONArray().put(new JSONObject().put("lat",a).put("lon",b)).put(new JSONObject().put("lat",c).put("lon",d)))
                .put("costing","motor_scooter").put("units","kilometers").put("language","en-US")
                .put("costing_options",new JSONObject().put("motor_scooter",new JSONObject().put("use_highways",0).put("use_tolls",0).put("top_speed",80)));
            JSONObject response=new JSONObject(engine.routeRaw(req.toString()));JSONObject trip=response.getJSONObject("trip");JSONArray legs=trip.getJSONArray("legs");JSONObject leg=legs.getJSONObject(0);
            List<double[]> route=GeoMath.decode(leg.getString("shape"));if(route.size()<2)throw new IOException("Empty route");
            synchronized(this){if(token!=requestId)return;shape=Collections.unmodifiableList(route);maneuvers=leg.getJSONArray("maneuvers");maneuver=0;progress=0;double[] prefix=new double[route.size()];for(int j=1;j<route.size();j++)prefix[j]=prefix[j-1]+GeoMath.distance(route.get(j-1)[0],route.get(j-1)[1],route.get(j)[0],route.get(j)[1]);cumulative=prefix;routeMeters=prefix[prefix.length-1];remaining=routeMeters;routeSeconds=trip.getJSONObject("summary").getDouble("time");seconds=routeSeconds;instruction="Route ready";}
            message((simulation?"SIMULATION · ":"Offline route ready · ")+destination);
        }catch(Throwable e){synchronized(this){if(token!=requestId)return;shape=Collections.emptyList();}message("No offline route: "+e.getMessage());}finally{routing=false;signal();}});
    }
    public synchronized void stop(){active=false;simulation=false;fixAt=0;accuracy=999;requestId++;shape=Collections.emptyList();instruction="Choose a destination";message("Ride ended · tracking stopped");}
    public synchronized void update(double a,double b,float acc,float direction,boolean demo){
        accuracy=acc;if(acc>50&&!demo){signal();return;}lat=a;lon=b;bearing=direction;fixAt=SystemClock.elapsedRealtime();
        List<double[]> route=shape;if(!active||route.size()<2){signal();return;}
        double best=Double.MAX_VALUE;int nearest=progress;
        for(int i=Math.max(0,progress-8);i<route.size()-1;i++){double distance=GeoMath.segmentDistance(a,b,route.get(i),route.get(i+1));if(distance<best){best=distance;nearest=i;}}
        if(best<Math.max(45,acc)){progress=Math.max(progress,nearest);offRoute=0;}else if(acc<35&&best>65){offRoute++;if(!demo&&offRoute>=3&&!routing&&SystemClock.elapsedRealtime()-lastReroute>12000){lastReroute=SystemClock.elapsedRealtime();offRoute=0;route();}}
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
