package net.drave.atrdiagnostics;
import android.*;import android.app.*;import android.content.*;import android.content.pm.*;import android.location.*;import android.os.*;import android.speech.tts.TextToSpeech;import java.util.*;
public final class NavService extends Service {
    private NavSession s;private LocationFeed gps;private TextToSpeech voice;private final Handler handler=new Handler(Looper.getMainLooper());private boolean voiceReady;private final FixFilter filter=new FixFilter();private int spoken=-1,demoIndex;private List<double[]> spokenRoute;private long lastWarning;private Runnable simulator;
    public IBinder onBind(Intent i){return null;}
    @android.annotation.SuppressLint("MissingPermission") // removing location updates; subscribe() checks the permission itself
    public int onStartCommand(Intent i,int f,int id){
        // Restarted by Android after the phone closed Daanvia mid-ride (this service is sticky during real rides).
        if(i==null)return resumeAfterRestart()?START_STICKY:START_NOT_STICKY;
        if("stop".equals(i.getAction())){if(s!=null)s.stop();stopService(new Intent(this,StreamService.class));stopSelf();return START_NOT_STICKY;}
        s=NavSession.get(this);boolean demo=i.getBooleanExtra("demo",false);
        try{
            foreground(demo);
            if(voice==null)voice=new TextToSpeech(this,result->{voiceReady=result==TextToSpeech.SUCCESS;if(voiceReady)voice.setLanguage(Locale.US);});
            if("preview".equals(i.getAction())){subscribe();return START_NOT_STICKY;}
            s.active=true;s.simulation=demo;spoken=-1;filter.reset();
            if(demo){if(gps!=null)gps.stop();startSimulation();}else{if(simulator!=null)handler.removeCallbacks(simulator);subscribe();}
            s.signal();searches=0;handler.removeCallbacks(bikeSearch);handler.postDelayed(bikeSearch,20000);
        }catch(Exception e){s.message("Cannot start navigation: "+e.getMessage());stopSelf();return START_NOT_STICKY;}
        // A real ride asks Android to restart this service if the phone closes the app, so the ride carries on.
        return demo?START_NOT_STICKY:START_STICKY;
    }
    private void foreground(boolean demo){
            getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("daanvia-nav","Navigation",NotificationManager.IMPORTANCE_LOW));
            PendingIntent open=PendingIntent.getActivity(this,20,new Intent(this,HomeActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            PendingIntent stop=PendingIntent.getService(this,21,new Intent(this,NavService.class).setAction("stop"),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            Notification n=new Notification.Builder(this,"daanvia-nav").setSmallIcon(R.drawable.ic_stat_daanvia).setContentTitle(demo?"Daanvia · demo ride":"Daanvia directions").setContentText("Tap Stop to end the ride").setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,"Stop",stop).build()).build();
            startForeground(22,n,demo?android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE:android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
    }
    /**
     * After the phone closed Daanvia mid-ride and Android restarted this service: pick the ride up on its own, with the
     * phone still in the pocket. Wait for the map and a GPS fix, route again to the same place, carry on guiding, and
     * reconnect the bike display. Only for a ride seen alive in the last 30 minutes.
     */
    private boolean resumeAfterRestart(){
        android.content.SharedPreferences u=getSharedPreferences("ui",0);String raw=u.getString("ride",null);
        try{
            if(raw==null)throw new IllegalStateException("no ride");
            org.json.JSONObject r=new org.json.JSONObject(raw);long seen=Math.max(r.optLong("at"),u.getLong("ride_alive",0));
            if(System.currentTimeMillis()-seen>30*60000L)throw new IllegalStateException("ride too old");
            s=NavSession.get(this);foreground(false);
            if(voice==null)voice=new TextToSpeech(this,result->{voiceReady=result==TextToSpeech.SUCCESS;if(voiceReady)voice.setLanguage(Locale.US);});
            subscribe();filter.reset();spoken=-1;
            final double la=r.getDouble("lat"),lo=r.getDouble("lon");final String name=r.optString("name","your destination");
            resumeTries=0;if(resumer!=null)handler.removeCallbacks(resumer);
            resumer=new Runnable(){public void run(){
                if(s.active)return;
                if(++resumeTries>90){s.message("Could not pick up the ride: no GPS or no route");stopSelf();return;}
                boolean fix=s.fixAt>0&&SystemClock.elapsedRealtime()-s.fixAt<15000;
                if(s.ready&&fix&&!s.routing){
                    if(s.shape.isEmpty()||Math.abs(s.destLat-la)>1e-6||Math.abs(s.destLon-lo)>1e-6)s.plan(la,lo,name,false);
                    else{s.active=true;s.simulation=false;spoken=-1;filter.reset();s.message("Picked up your ride to "+name);s.signal();
                        DashboardLink.headless(NavService.this).allowAuto();searches=0;handler.removeCallbacks(bikeSearch);handler.post(bikeSearch);return;}
                }
                handler.postDelayed(this,2000);}};
            handler.post(resumer);return true;
        }catch(Exception e){stopSelf();return false;}
    }
    private Runnable resumer;private int resumeTries;
    /** Google's fused location where the phone has it (GPS steadied by Wi-Fi and the motion sensors), else the phone's own (see LocationFeed). */
    /**
     * While riding, keep looking for the bike display every 20 s, also with the phone in a pocket: a ride started before
     * the bike was switched on, or a display that dropped, connects as soon as the bike is reachable.
     */
    private final Runnable bikeSearch=new Runnable(){public void run(){
        if(s==null||!s.active)return;
        // "Still riding", for picking the ride up if the phone closes the app (see resumeAfterRestart and HomeActivity.offerResume).
        if(!s.simulation)getSharedPreferences("ui",0).edit().putLong("ride_alive",System.currentTimeMillis()).apply();
        DashboardLink link=DashboardLink.existing();
        if(StreamService.navigationActive)searches=0;
        else if(link!=null&&!link.searching()&&getSharedPreferences("ui",0).getBoolean("autoconnect",true)){link.autoNavigation();searches++;}
        // Back off while the bike stays off (see searchGap): each search keeps Wi-Fi scanning.
        handler.postDelayed(this,searchGap(searches));}};
    private int searches;
    /**
     * Wait before the next search for the bike. A search keeps Wi-Fi scanning for up to 45 s, so a bike that stays off
     * shouldn't be looked for back to back: every 20 s for the first three tries (a link that just dropped comes straight
     * back), then 20 s longer each time, up to every 3 minutes. Starting a ride or reconnecting starts over.
     */
    static long searchGap(int searches){return Math.min(180000,20000+Math.max(0,searches-3)*20000L);}
    private void subscribe(){if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)throw new SecurityException("Precise location required");if(gps==null)gps=new LocationFeed(this,this::onLocationChanged,true);gps.stop();if(!gps.start())s.message("GPS is off · enable Location");else s.message("Waiting for GPS · offline region ready");}
    private void onLocationChanged(Location l){if(l.getElapsedRealtimeNanos()>0&&SystemClock.elapsedRealtimeNanos()-l.getElapsedRealtimeNanos()>12000000000L)return;
        // A fix that bounced off buildings would pull the rider off the route and start a reroute (see FixFilter).
        long now=SystemClock.elapsedRealtime(),at=l.getElapsedRealtimeNanos()>0?l.getElapsedRealtimeNanos()/1000000L:now;if(!filter.accept(l.getLatitude(),l.getLongitude(),l.getAccuracy(),at,now))return;
        // Heading from GPS is noise below walking speed; keep the last good one instead of snapping to north.
        boolean moving=l.hasBearing()&&(!l.hasSpeed()||l.getSpeed()>1.4f);
        s.update(l.getLatitude(),l.getLongitude(),l.getAccuracy(),moving?l.getBearing():s.bearing,false);speak();}
    private void speak(){if(s.shape!=spokenRoute){spokenRoute=s.shape;spoken=-1;}if(s.active&&voiceReady&&getSharedPreferences("ui",0).getBoolean("voice",true)&&s.accuracy<40&&s.maneuver!=spoken&&s.distanceToTurn<250){spoken=s.maneuver;voice.speak(s.instruction,TextToSpeech.QUEUE_FLUSH,null,"turn-"+spoken);}}
    private void startSimulation(){if(simulator!=null)handler.removeCallbacks(simulator);demoIndex=0;simulator=new Runnable(){public void run(){if(!s.active||!s.simulation)return;if(!s.shape.isEmpty()){double[] p=s.shape.get(Math.min(demoIndex,s.shape.size()-1));double[] next=s.shape.get(Math.min(demoIndex+1,s.shape.size()-1));s.update(p[0],p[1],3,GeoMath.bearing(p,next),true);speak();demoIndex=Math.min(demoIndex+1,s.shape.size()-1);}handler.postDelayed(this,900);}};handler.post(simulator);}
    @android.annotation.SuppressLint("MissingPermission")
    public void onDestroy(){handler.removeCallbacksAndMessages(null);if(gps!=null)gps.stop();if(voice!=null){voice.stop();voice.shutdown();}if(s!=null){s.active=false;s.simulation=false;s.signal();}stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();}
}
