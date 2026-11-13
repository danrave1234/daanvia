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
        if(i==null){running=this;return resumeAfterRestart()?START_STICKY:START_NOT_STICKY;}
        // The display is stopped through its link, which then knows the rider meant it (not a drop to reconnect from).
        if("stop".equals(i.getAction())){if(s!=null)s.stop();DashboardLink link=DashboardLink.existing();if(link!=null)link.stop();else stopService(new Intent(this,StreamService.class));stopSelf();return START_NOT_STICKY;}
        running=this;s=NavSession.get(this);boolean demo=i.getBooleanExtra("demo",false);demoRide=demo;
        if("repeat".equals(i.getAction())||"audio_stop".equals(i.getAction())){
            if(!s.active){stopSelf();return START_NOT_STICKY;}
            ensureVoice();if("repeat".equals(i.getAction()))speakCurrent();else voice.stop();return s.simulation?START_NOT_STICKY:START_STICKY;
        }
        try{
            foreground(demo);
            ensureVoice();
            if(!demo)logStatus();
            s.active=true;s.simulation=demo;prompts.reset();filter.reset();
            metrics=new RideMetrics(this);handler.removeCallbacks(measure);handler.post(measure);
            if(demo){if(gps!=null)gps.stop();startSimulation();}else{if(simulator!=null)handler.removeCallbacks(simulator);subscribe();}
            s.signal();searches=0;handler.removeCallbacks(bikeSearch);handler.postDelayed(bikeSearch,20000);
        }catch(Exception e){s.message("Cannot start navigation: "+e.getMessage());stopSelf();return START_NOT_STICKY;}
        // A real ride asks Android to restart this service if the phone closes the app, so the ride carries on.
        return demo?START_NOT_STICKY:START_STICKY;
    }
    private void foreground(boolean demo){
            NotifyChannels.create(this);
            shownStatus=null;Notification n=build(demo,back());
            startForeground(22,n,demo?android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE:android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            // OPPO/OnePlus/realme close apps ~5 min after the screen goes off unless they play audio (see QuietAudio): for
            // the whole ride, not only while the bike display is connected.
            if(!demo&&KeepAlive.colorOs()!=null){if(quiet==null)quiet=new QuietAudio(m->LogUpload.add(this,stamp()+m+"\n"));quiet.start(this);}
    }
    private Notification build(boolean demo,String text){
        PendingIntent open=PendingIntent.getActivity(this,20,new Intent(this,HomeActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent commands=PendingIntent.getActivity(this,24,new Intent(this,VoiceCommandsActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,NotifyChannels.RIDE).setSmallIcon(R.drawable.ic_stat_daanvia).setContentTitle(demo?"Daanvia · demo ride":"Daanvia directions").setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PUBLIC)
            // Work from the lock screen: Reconnect bike and Exit run in the background; Commands opens over the lock screen.
            .addAction(new Notification.Action.Builder(null,"Reconnect bike",RideActions.intent(this,RideActions.RECONNECT,25)).build()).addAction(new Notification.Action.Builder(null,"Commands",commands).build()).addAction(new Notification.Action.Builder(null,"Exit",RideActions.intent(this,RideActions.EXIT,26)).build()).build();
    }
    /** The notification's default line. */
    private String back(){return "Tap to return to directions";}
    private QuietAudio quiet;
    private static String stamp(){return new java.text.SimpleDateFormat("HH:mm:ss",java.util.Locale.US).format(new java.util.Date())+"  ";}
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
            // A free ride kept by 0.9.21 or older: there is nothing to pick up (the bike display follows along without a ride).
            if(r.optBoolean("cruise"))throw new IllegalStateException("free ride");
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
                    else{logStatus();s.active=true;s.simulation=false;prompts.reset();filter.reset();handler.removeCallbacks(measure);handler.post(measure);s.message("Picked up your ride to "+name);s.signal();
                        DashboardLink.headless(NavService.this).allowAuto();searches=0;handler.removeCallbacks(bikeSearch);handler.post(bikeSearch);return;}
                }
                handler.postDelayed(this,2000);}};
            handler.post(resumer);return true;
        }catch(Exception e){stopSelf();return false;}
    }
    private Runnable resumer;private int resumeTries;
    /**
     * While riding, keep looking for the bike display (10 s apart at first, see searchGap; the first search waits 20 s for
     * the connect the ride screen starts itself), also with the phone in a pocket: a ride started before the bike was
     * switched on, or a display that dropped, connects as soon as the bike is reachable.
     */
    private final Runnable bikeSearch=new Runnable(){public void run(){
        if(s==null||!s.active)return;
        // "Still riding", for picking the ride up if the phone closes the app (see resumeAfterRestart and HomeActivity.offerResume).
        // Once a minute is plenty for a 30-minute window, and spares rewriting the prefs file every search.
        if(!s.simulation&&SystemClock.elapsedRealtime()-aliveSavedAt>=60000){aliveSavedAt=SystemClock.elapsedRealtime();android.content.SharedPreferences prefs=getSharedPreferences("ui",0);android.content.SharedPreferences.Editor edit=prefs.edit().putLong("ride_alive",System.currentTimeMillis());try{String raw=prefs.getString("ride",null);if(raw!=null){org.json.JSONObject ride=new org.json.JSONObject(raw);ride.put("stops",TripStop.json(s.remainingStops()));double[] via=s.chosenVia();if(via!=null)ride.put("via",new org.json.JSONArray().put(via[0]).put(via[1]));else ride.remove("via");edit.putString("ride",ride.toString());}}catch(Exception ignored){}edit.apply();}
        DashboardLink link=DashboardLink.existing();
        if(StreamService.navigationActive)searches=0;
        else if(link!=null&&!link.searching()){link.autoNavigation();searches++;}
        // Back off while the bike stays off (see searchGap): each search keeps Wi-Fi scanning.
        handler.postDelayed(this,searchGap(searches));}};
    private int searches;private long aliveSavedAt=-60000;
    /**
     * Wait before the next search for the bike. A search keeps Wi-Fi scanning for up to 45 s, so a bike that stays off
     * shouldn't be looked for back to back forever: 10 s apart for the first dozen tries (about ten minutes, a stop with the
     * bike switched off: it is found soon after it is switched on again), then 10 s longer each time, up to a minute (the
     * old three minutes left riders reconnecting by hand). Starting a ride or tapping Reconnect starts over.
     */
    static long searchGap(int searches){return Math.min(60000,10000+Math.max(0,searches-12)*10000L);}
    private static NavService running;
    /** The rider tapped Reconnect: look for the bike at the short pace again. */
    static void searchAgain(){NavService n=running;if(n==null)return;n.searches=0;n.handler.removeCallbacks(n.bikeSearch);n.handler.postDelayed(n.bikeSearch,50000);}
    /** The bike display's state, as the ride notification's text, so a tap on Reconnect visibly does something. */
    static void status(String text){NavService n=running;if(n==null||n.s==null||!n.s.active||text==null)return;
        String shown=StreamService.navigationActive?n.back():text;if(shown.equals(n.shownStatus))return;n.shownStatus=shown;
        try{n.getSystemService(NotificationManager.class).notify(22,n.build(n.demoRide,shown));}catch(Exception ignored){}}
    private String shownStatus;
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
    /** This ride is a demo: its numbers are not uploaded (s.simulation is already cleared when the service ends). */
    private boolean demoRide;
    /** Into the uploaded log only (not the ride status on screen): whether the phone may close Daanvia mid-ride. */
    private void logStatus(){LogUpload.add(this,stamp()+KeepAlive.status(this)+"\n");}
    @android.annotation.SuppressLint("MissingPermission")
    public void onDestroy(){
        if(running==this)running=null;
        // The ride's battery, heat and display numbers go with the log, sent now that the ride is over.
        if(metrics!=null&&s!=null)metrics.flush(s);
        if(metrics!=null&&!demoRide)LogUpload.add(this,stamp()+"Ride diagnostics: "+RideMetrics.forReport(this).optJSONObject("latest")+"\n");
        LogUpload.now(this);
        handler.removeCallbacksAndMessages(null);if(gps!=null)gps.stop();if(voice!=null)voice.close();if(quiet!=null){quiet.stop();quiet=null;}if(s!=null){s.active=false;s.simulation=false;s.signal();}stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();}
}
