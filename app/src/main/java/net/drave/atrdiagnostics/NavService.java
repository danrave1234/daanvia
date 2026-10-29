package net.drave.atrdiagnostics;
import android.*;import android.app.*;import android.content.*;import android.content.pm.*;import android.location.*;import android.os.*;import java.util.*;
public final class NavService extends Service {
    private NavSession s;private LocationFeed gps;private VoiceOutput voice;private final Handler handler=new Handler(Looper.getMainLooper());private final GuidancePrompts prompts=new GuidancePrompts();private final FixFilter filter=new FixFilter();private int demoIndex;private List<double[]> spokenRoute;private Runnable simulator;
    public IBinder onBind(Intent i){return null;}
    private RideMetrics metrics;
    private final Runnable measure=new Runnable(){public void run(){if(s==null||!s.active)return;if(metrics==null)metrics=new RideMetrics(NavService.this);metrics.sample(s);handler.postDelayed(this,30000);}};
    @android.annotation.SuppressLint("MissingPermission") // removing location updates; subscribe() checks the permission itself
    public int onStartCommand(Intent i,int f,int id){
        if(finishing!=null){handler.removeCallbacks(finishing);finishing=null;}
        // Restarted by Android after the phone closed Daanvia mid-ride (this service is sticky during real rides).
        if(i==null)return resumeAfterRestart()?START_STICKY:START_NOT_STICKY;
        if("stop".equals(i.getAction())){if(s!=null)s.stop();stopService(new Intent(this,StreamService.class));stopSelf();return START_NOT_STICKY;}
        s=NavSession.get(this);boolean demo=i.getBooleanExtra("demo",false);
        if("repeat".equals(i.getAction())||"audio_stop".equals(i.getAction())){
            if(!s.active){stopSelf();return START_NOT_STICKY;}
            ensureVoice();if("repeat".equals(i.getAction()))speakCurrent();else voice.stop();return s.simulation?START_NOT_STICKY:START_STICKY;
        }
        try{
            foreground(demo);
            ensureVoice();
            s.active=true;s.simulation=demo;prompts.reset();filter.reset();
            metrics=new RideMetrics(this);handler.removeCallbacks(measure);handler.post(measure);
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
            PendingIntent repeat=PendingIntent.getService(this,23,new Intent(this,NavService.class).setAction("repeat"),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            PendingIntent commands=PendingIntent.getActivity(this,24,new Intent(this,VoiceCommandsActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            Notification n=new Notification.Builder(this,"daanvia-nav").setSmallIcon(R.drawable.ic_stat_daanvia).setContentTitle(demo?"Daanvia · demo ride":"Daanvia directions").setContentText("Tap to return to directions").setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,"Repeat",repeat).build()).addAction(new Notification.Action.Builder(null,"Commands",commands).build()).addAction(new Notification.Action.Builder(null,"Stop",stop).build()).build();
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
            ensureVoice();
            subscribe();filter.reset();
            final double la=r.getDouble("lat"),lo=r.getDouble("lon");final String name=r.optString("name","your destination");
            resumeTries=0;if(resumer!=null)handler.removeCallbacks(resumer);
            resumer=new Runnable(){public void run(){
                if(s.active)return;
                if(++resumeTries>90){s.message("Could not pick up the ride: no GPS or no route");stopSelf();return;}
                boolean fix=s.fixAt>0&&SystemClock.elapsedRealtime()-s.fixAt<15000;
                if(s.ready&&fix&&!s.routing){
                    if(s.shape.isEmpty()||Math.abs(s.destLat-la)>1e-6||Math.abs(s.destLon-lo)>1e-6){try{org.json.JSONArray stops=r.optJSONArray("stops"),via=r.optJSONArray("via");if(via!=null&&via.length()==2)s.resumeVia(new double[]{via.getDouble(0),via.getDouble(1)});if(stops!=null&&stops.length()>0)s.planTrip(TripStop.parse(stops.toString()),name);else s.plan(la,lo,name,false);}catch(Exception badTrip){s.message("Saved trip could not be restored");stopSelf();return;}}
                    else{s.active=true;s.simulation=false;prompts.reset();filter.reset();handler.post(measure);s.message("Picked up your ride to "+name);s.signal();
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
        if(!s.simulation){android.content.SharedPreferences prefs=getSharedPreferences("ui",0);android.content.SharedPreferences.Editor edit=prefs.edit().putLong("ride_alive",System.currentTimeMillis());try{String raw=prefs.getString("ride",null);if(raw!=null){org.json.JSONObject ride=new org.json.JSONObject(raw);ride.put("stops",TripStop.json(s.remainingStops()));double[] via=s.chosenVia();if(via!=null)ride.put("via",new org.json.JSONArray().put(via[0]).put(via[1]));else ride.remove("via");edit.putString("ride",ride.toString());}}catch(Exception ignored){}edit.apply();}
        DashboardLink link=DashboardLink.existing();
        if(StreamService.navigationActive)searches=0;
        else if(link!=null&&!link.searching()){link.autoNavigation();searches++;}
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
        s.speedMetersPerSecond=l.hasSpeed()?Math.max(0,l.getSpeed()):0;s.update(l.getLatitude(),l.getLongitude(),l.getAccuracy(),moving?l.getBearing():s.bearing,false);speak();checkArrival(l);}
    /**
     * Arriving ends the ride on its own, so nobody has to tap End: once the rider has stopped near the arrival spot for
     * 20 s (parked), has ridden 250 m past it, or 5 minutes after arriving. Measured from where the arrival was noticed,
     * not the pin, which can sit far inside a campus or mall.
     */
    private long arrivedAt,stoppedSince;private double arrivedLat,arrivedLon;private Runnable finishing;
    private void checkArrival(Location l){
        if(!s.active||s.simulation||!s.arrived){arrivedAt=stoppedSince=0;return;}
        long now=SystemClock.elapsedRealtime();
        if(arrivedAt==0){arrivedAt=now;arrivedLat=l.getLatitude();arrivedLon=l.getLongitude();}
        double away=GeoMath.distance(l.getLatitude(),l.getLongitude(),arrivedLat,arrivedLon);
        if(s.speedMetersPerSecond>=1.5||away>150)stoppedSince=0;else if(stoppedSince==0)stoppedSince=now;
        if(arrivalDone(stoppedSince>0?now-stoppedSince:0,away,now-arrivedAt))finishRide();
    }
    /** Parked for 20 s, ridden 250 m on, or 5 minutes since arriving. */
    static boolean arrivalDone(long stoppedMs,double metresAway,long sinceArrivalMs){return stoppedMs>=20000||metresAway>250||sinceArrivalMs>=300000;}
    private void finishRide(){
        arrivedAt=stoppedSince=0;String name=s.destination;
        if(voice!=null&&voice.ready()&&getSharedPreferences("ui",0).getBoolean("voice",true))voice.speak("You have arrived.");
        s.finished=name.isEmpty()?"your destination":name;s.stop();if(gps!=null)gps.stop();
        // A moment for the voice to finish before the service (and its voice) closes.
        finishing=this::stopSelf;handler.postDelayed(finishing,4000);
    }
    private void ensureVoice(){if(voice==null)voice=new VoiceOutput(this,message->{if(voice!=null&&!voice.ready())s.message(message);else s.signal();});}
    private void speakCurrent(){if(voice!=null&&s.active)voice.speak(s.arrived?"Destination nearby":GuidancePrompts.distance(s.distanceToTurn)+". "+s.instruction);}
    private void speak(){
        if(s.shape!=spokenRoute){spokenRoute=s.shape;prompts.reset();}
        if(!s.active||voice==null||!voice.ready()||SystemClock.elapsedRealtime()<s.commandAudioUntil||!getSharedPreferences("ui",0).getBoolean("voice",true))return;
        long now=SystemClock.elapsedRealtime();int stage=prompts.next(s.maneuver,s.distanceToTurn,s.speedMetersPerSecond,s.accuracy,s.arrived,now);
        if(stage==GuidancePrompts.NONE)return;
        org.json.JSONObject maneuver=s.maneuvers.optJSONObject(s.maneuver);
        String instruction=maneuver==null?s.instruction:maneuver.optString("verbal_pre_transition_instruction",s.instruction);
        String text=stage==GuidancePrompts.ARRIVAL?"You are near your destination.":stage==GuidancePrompts.TURN?instruction:"In "+GuidancePrompts.distance(s.distanceToTurn)+", "+instruction;
        // An overpass and the road beside it look the same on the map and often share a name: say which one.
        String structure=maneuver==null?"":maneuver.optString("structure","");
        if(stage!=GuidancePrompts.ARRIVAL&&!structure.isEmpty())text+=" Take the "+structure+".";
        if(voice.speak(text))prompts.spoken(stage,now);
    }
    private void startSimulation(){if(simulator!=null)handler.removeCallbacks(simulator);demoIndex=0;simulator=new Runnable(){public void run(){if(!s.active||!s.simulation)return;if(!s.shape.isEmpty()){double[] p=s.shape.get(Math.min(demoIndex,s.shape.size()-1));double[] next=s.shape.get(Math.min(demoIndex+1,s.shape.size()-1));s.speedMetersPerSecond=8;s.update(p[0],p[1],3,GeoMath.bearing(p,next),true);speak();demoIndex=Math.min(demoIndex+1,s.shape.size()-1);}handler.postDelayed(this,900);}};handler.post(simulator);}
    @android.annotation.SuppressLint("MissingPermission")
    public void onDestroy(){
        // The ride's battery, heat and display numbers go with the log, sent now that the ride is over.
        if(metrics!=null&&s!=null&&!s.simulation)LogUpload.add(this,new java.text.SimpleDateFormat("HH:mm:ss",java.util.Locale.US).format(new java.util.Date())+"  Ride diagnostics: "+RideMetrics.forReport(this)+"\n");
        LogUpload.now(this);
        handler.removeCallbacksAndMessages(null);if(gps!=null)gps.stop();if(voice!=null)voice.close();if(s!=null){s.active=false;s.simulation=false;s.signal();}stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();}
}
