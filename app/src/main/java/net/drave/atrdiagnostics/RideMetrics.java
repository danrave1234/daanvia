package net.drave.atrdiagnostics;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded, local diagnostics. Never records a coordinate, destination or device identifier. */
final class RideMetrics {
    private final Context context;
    private final long started=SystemClock.elapsedRealtime();
    private final JSONArray samples=new JSONArray();
    private int displayConnections;
    private boolean wasConnected;
    /**
     * A new ride's diagnostics start empty; the last ride's are kept as "previous", so a ride picked up again after the
     * phone closed the app still has what happened before the close.
     */
    RideMetrics(Context c){context=c.getApplicationContext();
        android.content.SharedPreferences p=context.getSharedPreferences("ride-metrics",0);String last=p.getString("latest",null);
        // Only a real ride is worth keeping; a demo ride's samples are simply dropped.
        boolean real=false;try{real=last!=null&&!new JSONObject(last).optBoolean("simulation",true);}catch(Exception ignored){}
        if(real)p.edit().putString("previous",last).remove("latest").apply();else if(last!=null)p.edit().remove("latest").apply();}
    void sample(NavSession s){
        try{
            Intent battery=context.registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            PowerManager power=context.getSystemService(PowerManager.class);
            boolean connected=StreamService.navigationActive;
            if(connected&&!wasConnected)displayConnections++;wasConnected=connected;
            JSONObject row=new JSONObject().put("elapsed_s",(SystemClock.elapsedRealtime()-started)/1000)
                .put("screen_on",power!=null&&power.isInteractive()).put("thermal_status",power==null?-1:power.getCurrentThermalStatus())
                .put("display_connected",connected).put("display_connections",displayConnections)
                .put("gps_age_s",s.fixAt==0?-1:(SystemClock.elapsedRealtime()-s.fixAt)/1000)
                .put("gps_accuracy_m",s.accuracy).put("rerouting",s.routing);
            if(battery!=null){int level=battery.getIntExtra(BatteryManager.EXTRA_LEVEL,-1),scale=battery.getIntExtra(BatteryManager.EXTRA_SCALE,100);
                row.put("battery_percent",level<0||scale<=0?-1:100.0*level/scale).put("battery_temperature_c",battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,0)/10.0)
                    .put("plugged",battery.getIntExtra(BatteryManager.EXTRA_PLUGGED,0)!=0);}
            if(samples.length()>=720)samples.remove(0);samples.put(row);
            JSONObject report=new JSONObject().put("simulation",s.simulation)
                .put("emulator",Build.FINGERPRINT.contains("generic")||Build.MODEL.contains("Emulator")||Build.HARDWARE.contains("ranchu"))
                .put("sample_interval_s",30).put("samples",samples).put("location_and_identifiers","omitted");
            context.getSharedPreferences("ride-metrics",0).edit().putString("latest",report.toString()).apply();
        }catch(Exception e){android.util.Log.w("Daanvia","Ride diagnostics unavailable",e);}
    }
    static JSONObject latest(Context c){return read(c,"latest");}
    private static JSONObject read(Context c,String key){try{return new JSONObject(c.getSharedPreferences("ride-metrics",0).getString(key,"{}"));}catch(Exception e){return new JSONObject();}}
    /**
     * For a shared report: this ride and the one before it, each thinned to at most 60 evenly spaced samples (the first
     * and the last kept), so a long ride's report stays small enough to share.
     */
    static JSONObject forReport(Context c){
        JSONObject out=new JSONObject();
        try{out.put("latest",thin(read(c,"latest"),60)).put("previous",thin(read(c,"previous"),60));}catch(Exception ignored){}
        return out;
    }
    static JSONObject thin(JSONObject ride,int max)throws org.json.JSONException{
        JSONArray all=ride.optJSONArray("samples");if(all==null||all.length()<=max)return ride;
        JSONArray kept=new JSONArray();int n=all.length();
        for(int i=0;i<max;i++)kept.put(all.get((int)Math.round(i*(n-1)/(double)(max-1))));
        JSONObject copy=new JSONObject(ride.toString());copy.put("samples",kept).put("samples_total",n);return copy;
    }
}
