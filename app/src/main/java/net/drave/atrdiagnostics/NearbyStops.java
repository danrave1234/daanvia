package net.drave.atrdiagnostics;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.net.*;
import java.util.*;

/** Small, user-triggered OSM searches. A bounded local cache remains usable without signal. */
final class NearbyStops {
    static List<TripStop> find(Context context,String kind,double lat,double lon)throws Exception{
        boolean fuel="fuel".equals(kind);String key=(fuel?"fuel":"rest")+":"+Math.round(lat*100)+":"+Math.round(lon*100);
        android.content.SharedPreferences cache=context.getSharedPreferences("nearby-stops",0);
        String saved=cache.getString(key,null);long checked=cache.getLong(key+":at",0);
        if(saved!=null&&System.currentTimeMillis()-checked<86400000L)return nearest(TripStop.parse(saved),lat,lon);
        String around=String.format(Locale.US,"(around:5000,%.6f,%.6f)",lat,lon);
        String query="[out:json][timeout:12][maxsize:16777216];("+(fuel?"nwr[amenity=fuel]"+around+";":"nwr[amenity~\"^(cafe|restaurant|fast_food)$\"]"+around+";nwr[highway=rest_area]"+around+";")+");out center 100;";
        HttpURLConnection connection=(HttpURLConnection)new URL("https://overpass-api.de/api/interpreter").openConnection();
        connection.setConnectTimeout(5000);connection.setReadTimeout(16000);connection.setRequestProperty("User-Agent","Daanvia/"+BuildInfo.VERSION+" (github.com/danrave1234/daanvia)");connection.setRequestMethod("POST");connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type","application/x-www-form-urlencoded; charset=UTF-8");
        try{
            try(OutputStream out=connection.getOutputStream()){out.write(("data="+URLEncoder.encode(query,"UTF-8")).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
            if(connection.getResponseCode()!=200)throw new IOException("Place search is unavailable. Try again later or add a saved stop.");
            ByteArrayOutputStream data=new ByteArrayOutputStream();byte[] bytes=new byte[8192];int n;
            try(InputStream in=connection.getInputStream()){while((n=in.read(bytes))!=-1){if(data.size()+n>2*1024*1024)throw new IOException("Place response is too large");data.write(bytes,0,n);}}
            List<TripStop> found=nearest(parse(data.toString("UTF-8"),fuel),lat,lon);
            // Keep at most twelve small search areas, so travelling never grows this cache without bound.
            if(cache.getAll().size()>24)cache.edit().clear().apply();
            cache.edit().putString(key,TripStop.json(found).toString()).putLong(key+":at",System.currentTimeMillis()).apply();return found;
        }catch(Exception e){if(saved!=null)return nearest(TripStop.parse(saved),lat,lon);throw new IOException("No cached stops here. Connect to search OpenStreetMap, or add a saved place.",e);}finally{connection.disconnect();}
    }
    static List<TripStop> parse(String raw,boolean fuel)throws JSONException{
        JSONArray entries=new JSONObject(raw).getJSONArray("elements");List<TripStop> out=new ArrayList<>();
        for(int i=0;i<entries.length();i++){JSONObject o=entries.getJSONObject(i),tags=o.optJSONObject("tags");if(tags==null)continue;
            String access=tags.optString("access");if(Arrays.asList("no","private","permit").contains(access))continue;
            String amenity=tags.optString("amenity");if(fuel?!"fuel".equals(amenity):!Arrays.asList("cafe","restaurant","fast_food").contains(amenity)&&!"rest_area".equals(tags.optString("highway")))continue;
            JSONObject point=o.has("lat")?o:o.optJSONObject("center");if(point==null)continue;
            String name=tags.optString("name",tags.optString("brand",fuel?"Fuel station":"Rest or food stop"));
            try{out.add(new TripStop(name,point.getDouble("lat"),point.getDouble("lon")));}catch(IllegalArgumentException ignored){}
        }
        return out;
    }
    private static List<TripStop> nearest(List<TripStop> points,double lat,double lon){points.sort(Comparator.comparingDouble(p->GeoMath.distance(lat,lon,p.lat,p.lon)));return new ArrayList<>(points.subList(0,Math.min(8,points.size())));}
}
