package net.drave.atrdiagnostics;

import org.json.*;
import java.util.*;

/** Ordered stops, independent of the route calculated between them. */
final class TripStop {
    static final int MAX=20;
    final String name;final double lat,lon;
    TripStop(String name,double lat,double lon){
        if(!Double.isFinite(lat)||!Double.isFinite(lon)||Math.abs(lat)>90||Math.abs(lon)>180)throw new IllegalArgumentException("Invalid stop coordinates");
        this.name=name==null||name.trim().isEmpty()?"Stop":name.trim();this.lat=lat;this.lon=lon;
    }
    JSONObject json()throws JSONException{return new JSONObject().put("name",name).put("lat",lat).put("lon",lon).put("type","break");}
    static JSONArray json(List<TripStop> stops)throws JSONException{JSONArray a=new JSONArray();for(TripStop stop:stops)a.put(stop.json());return a;}
    static List<TripStop> parse(String raw)throws JSONException{
        JSONArray a=new JSONArray(raw);if(a.length()>MAX)throw new IllegalArgumentException("Use at most "+MAX+" stops");
        List<TripStop> out=new ArrayList<>();for(int i=0;i<a.length();i++){JSONObject o=a.getJSONObject(i);out.add(new TripStop(o.optString("name","Stop "+(i+1)),o.getDouble("lat"),o.getDouble("lon")));}return out;
    }
}
