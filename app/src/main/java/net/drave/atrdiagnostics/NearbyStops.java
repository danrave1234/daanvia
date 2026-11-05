package net.drave.atrdiagnostics;

import android.content.Context;
import org.json.*;
import java.io.IOException;
import java.util.*;

/** Small, user-triggered OSM searches. A bounded local cache remains usable without signal. */
final class NearbyStops {
    static List<TripStop> find(Context context,String kind,double lat,double lon)throws Exception{
        boolean fuel="fuel".equals(kind);String key=(fuel?"fuel":"rest")+":"+Math.round(lat*100)+":"+Math.round(lon*100);
        android.content.SharedPreferences cache=context.getSharedPreferences("nearby-stops",0);
        String saved=cache.getString(key,null);long checked=cache.getLong(key+":at",0);
        if(saved!=null&&System.currentTimeMillis()-checked<86400000L)return nearest(TripStop.parse(saved),lat,lon);
        String around=String.format(Locale.US,"(around:5000,%.3f,%.3f)",lat,lon)/* about 100 m, plenty for a 5 km search */;
        String query="[out:json][timeout:12][maxsize:16777216];("+(fuel?"nwr[amenity=fuel]"+around+";":"nwr[amenity~\"^(cafe|restaurant|fast_food)$\"]"+around+";nwr[highway=rest_area]"+around+";")+");out center 100;";
        try{
            List<TripStop> found=nearest(parse(Http.postForm("https://overpass-api.de/api/interpreter",query,5000,16000,2*1024*1024),fuel),lat,lon);
            // Keep at most twelve small search areas, so travelling never grows this cache without bound.
            if(cache.getAll().size()>24)cache.edit().clear().apply();
            cache.edit().putString(key,TripStop.json(found).toString()).putLong(key+":at",System.currentTimeMillis()).apply();return found;
        }catch(Exception e){if(saved!=null)return nearest(TripStop.parse(saved),lat,lon);throw new IOException("No cached stops here. Connect to search OpenStreetMap, or add a saved place.",e);}
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
