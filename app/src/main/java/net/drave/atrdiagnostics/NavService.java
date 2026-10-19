package net.drave.atrdiagnostics;
import android.*;import android.app.*;import android.content.*;import android.content.pm.*;import android.location.*;import android.os.*;import android.speech.tts.TextToSpeech;import java.util.*;
public final class NavService extends Service implements LocationListener {
    private NavSession s;private LocationManager gps;private TextToSpeech voice;private final Handler handler=new Handler(Looper.getMainLooper());private boolean voiceReady;private int spoken=-1,demoIndex;private long lastWarning;private Runnable simulator;
    public IBinder onBind(Intent i){return null;}
    public int onStartCommand(Intent i,int f,int id){
        if(i==null||"stop".equals(i.getAction())){if(s!=null)s.stop();stopService(new Intent(this,StreamService.class));stopSelf();return START_NOT_STICKY;}
        s=NavSession.get(this);boolean demo=i.getBooleanExtra("demo",false);
        try{
            getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("daanvia-nav","Navigation",NotificationManager.IMPORTANCE_LOW));
            PendingIntent open=PendingIntent.getActivity(this,20,new Intent(this,HomeActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            PendingIntent stop=PendingIntent.getService(this,21,new Intent(this,NavService.class).setAction("stop"),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            Notification n=new Notification.Builder(this,"daanvia-nav").setSmallIcon(R.drawable.ic_stat_daanvia).setContentTitle(demo?"Daanvia · demo ride":"Daanvia directions").setContentText("Tap Stop to end the ride").setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,"Stop",stop).build()).build();
            startForeground(22,n,demo?android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE:android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            if(voice==null)voice=new TextToSpeech(this,result->{voiceReady=result==TextToSpeech.SUCCESS;if(voiceReady)voice.setLanguage(Locale.US);});
            if("preview".equals(i.getAction())){subscribe();return START_NOT_STICKY;}
            s.active=true;s.simulation=demo;spoken=-1;
            if(demo){if(gps!=null)gps.removeUpdates(this);startSimulation();}else{if(simulator!=null)handler.removeCallbacks(simulator);subscribe();}
            s.signal();
        }catch(Exception e){s.message("Cannot start navigation: "+e.getMessage());stopSelf();}
        return START_NOT_STICKY;
    }
    private void subscribe(){if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)throw new SecurityException("Precise location required");if(gps==null)gps=getSystemService(LocationManager.class);gps.removeUpdates(this);gps.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000,2,this);s.message("Waiting for GPS · offline region ready");}
    public void onLocationChanged(Location l){if(l.getElapsedRealtimeNanos()>0&&SystemClock.elapsedRealtimeNanos()-l.getElapsedRealtimeNanos()>12000000000L)return;s.update(l.getLatitude(),l.getLongitude(),l.getAccuracy(),l.hasBearing()?l.getBearing():0,false);speak();}
    public void onProviderDisabled(String provider){s.message("GPS is off · enable Location");}public void onProviderEnabled(String provider){}public void onStatusChanged(String p,int status,Bundle b){}
    private void speak(){if(s.active&&voiceReady&&getSharedPreferences("ui",0).getBoolean("voice",true)&&s.accuracy<40&&s.maneuver!=spoken&&s.distanceToTurn<250){spoken=s.maneuver;voice.speak(s.instruction,TextToSpeech.QUEUE_FLUSH,null,"turn-"+spoken);}}
    private void startSimulation(){if(simulator!=null)handler.removeCallbacks(simulator);demoIndex=0;simulator=new Runnable(){public void run(){if(!s.active||!s.simulation)return;if(!s.shape.isEmpty()){double[] p=s.shape.get(Math.min(demoIndex,s.shape.size()-1));double[] next=s.shape.get(Math.min(demoIndex+1,s.shape.size()-1));s.update(p[0],p[1],3,GeoMath.bearing(p,next),true);speak();demoIndex=Math.min(demoIndex+1,s.shape.size()-1);}handler.postDelayed(this,900);}};handler.post(simulator);}
    public void onDestroy(){handler.removeCallbacksAndMessages(null);if(gps!=null)try{gps.removeUpdates(this);}catch(Exception ignored){}if(voice!=null){voice.stop();voice.shutdown();}if(s!=null){s.active=false;s.signal();}stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();}
}
