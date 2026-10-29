package net.drave.atrdiagnostics;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.media.projection.*;
import android.net.Network;
import android.os.*;
import android.util.Base64;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import javax.crypto.Cipher;
import java.util.*;
import java.util.concurrent.*;

public final class StreamService extends Service {
    public static volatile boolean navigationActive;
    /** The phone's own screen is on the bike display (see DashboardLink.mirror). */
    public static volatile boolean mirroring;
    private boolean userStop;
    public static final String EVENT="net.drave.atrdiagnostics.STREAM_EVENT";
    private static final String PACKAGE="net.drave.atrdiagnostics";
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService workers=Executors.newFixedThreadPool(10);
    private final List<Closeable> sockets=Collections.synchronizedList(new ArrayList<>());
    private final Semaphore connections=new Semaphore(6);
    private volatile boolean running;
    private volatile long frames,bytes;
    private final Object encoderLock=new Object();
    private VideoEncoder encoder;
    private MediaProjection projection;
    private MediaProjection.Callback projectionCallback;
    private KeyPair identity;
    private InetAddress target,local;
    private String mode;
    private Network network;
    private int width,height;
    private long startedAt;
    private final java.util.concurrent.atomic.AtomicInteger channelIds=new java.util.concurrent.atomic.AtomicInteger();
    private Runnable deadline;
    private PowerManager.WakeLock navigationWake;
    private volatile long lastFrameAt; private volatile boolean controlOpen;
    /** When the video frame now being sent started sending; 0 when none is. A send stuck this long means a stalled link. */
    private volatile long writingSince;
    /** The display stream has no time limit, so end it (and let the app reconnect) when the bike stops talking to it. */
    private final Runnable watchdog=new Runnable(){public void run(){if(!running)return;long now=System.currentTimeMillis();
        if(frames==0&&now-startedAt>45000){log("The display never asked for video within 45 s.");stopSelf();return;}
        // The bike can go back to its speedometer and stop asking for video while still holding the control connection open,
        // so a quiet video link alone ends the session; auto-connect then brings the directions back.
        if(frames>0&&now-lastFrameAt>12000){log("The bike stopped asking for video ("+(controlOpen?"its control link was still open":"its control link had closed")+"). Reconnecting.");stopSelf();return;}
        // A video send that never finishes (the Wi-Fi link stalled) can leave the control channel looking healthy, so it is
        // caught on its own; ending the session lets auto-connect start a fresh one.
        long w=writingSince;if(w>0&&now-w>15000){log("Sending video to the display has been stuck for 15 s; restarting the connection.");stopSelf();return;}
        main.postDelayed(this,5000);}};
    public IBinder onBind(Intent i){return null;}
    private void event(String kind,String message) {
        sendBroadcast(new Intent(EVENT).setPackage(getPackageName()).putExtra("kind",kind).putExtra("message",message).putExtra("mode",mode).putExtra("frames",frames).putExtra("bytes",bytes));
    }
    private void log(String s){event("log",s);if("navigation".equals(mode)&&s.contains("failed"))NavSession.get(this).message("Display: "+s);}
    public int onStartCommand(Intent intent,int flags,int id) {
        if(intent==null){stopSelf();return START_NOT_STICKY;}
        if("stop".equals(intent.getAction())){userStop=true;stopSelf();return START_NOT_STICKY;}
        if(running){log("A stream test is already running. Stop it before starting another.");return START_NOT_STICKY;}
        try {
            mode=intent.getStringExtra("mode");
            boolean mirror="mirror".equals(mode);
            boolean navigation="navigation".equals(mode);navigationActive=navigation;mirroring=mirror;
            NotificationManager notifications=getSystemService(NotificationManager.class);
            notifications.createNotificationChannel(new NotificationChannel("atr-stream","Dashboard stream test",NotificationManager.IMPORTANCE_LOW));
            PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,StreamService.class).setAction("stop"),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            PendingIntent open=PendingIntent.getActivity(this,2,new Intent(this,HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            Notification n=new Notification.Builder(this,"atr-stream").setSmallIcon(R.drawable.ic_stat_daanvia).setContentTitle(navigation?"Daanvia is on your bike display":mirror?"Your screen is on your bike display":"Dashboard test pattern")
                .setContentText(navigation?"Tap Stop to disconnect":"Temporary test • tap Stop to end").setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,"Stop",stop).build()).build();
            int type=ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
            if(mirror)type|=ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
            // Without a ride the bike display still follows the rider (a free ride), so this service keeps location going
            // itself; NavService takes over during a ride (see Here.feed).
            boolean follow=navigation&&Here.precise(this);
            try{startForeground(17,n,follow?type|ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION:type);}
            catch(SecurityException noLocation){follow=false;startForeground(17,n,type);}
            if(follow){freeRide=new Here(this,NavSession.get(this));freeRide.start();}
            if(navigation){navigationWake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Daanvia:display");navigationWake.acquire();}
            if(mirror) {
                Intent consent=intent.getParcelableExtra("consent");
                if(consent==null)throw new IOException("Fresh screen-sharing permission is required");
                projection=getSystemService(MediaProjectionManager.class).getMediaProjection(Activity.RESULT_OK,consent);
                projectionCallback=new MediaProjection.Callback(){public void onStop(){log("Android screen sharing was stopped.");stopSelf();}};
                projection.registerCallback(projectionCallback,main);
            }
            target=InetAddress.getByName(intent.getStringExtra("host"));local=InetAddress.getByName(intent.getStringExtra("local"));
            long handle=intent.getLongExtra("network",0);if(handle!=0)network=Network.fromNetworkHandle(handle);
            int port=intent.getIntExtra("port",10930);
            running=true;frames=0;bytes=0;startedAt=System.currentTimeMillis();
            event("started","Starting "+(navigation?"dedicated Daanvia scene":mirror?"screen mirroring":"animated color-pattern test")+". A successful socket write is not proof of visible video.");
            int duration=mirror?90000:45000;boolean keep=intent.getBooleanExtra("keep",false);
            // Support's tests stop on their own; mirroring the rider started from the map runs until they stop it.
            deadline=()->{log("Test time limit reached.");stopSelf();};if(!navigation&&!keep)main.postDelayed(deadline,duration);else{lastFrameAt=startedAt;main.postDelayed(watchdog,5000);}
            workers.execute(()->setup(port));
        } catch(Exception e){log("Could not start stream test: "+e.getClass().getSimpleName()+" "+e.getMessage());stopSelf();}
        return START_NOT_STICKY;
    }
    private void setup(int port) {
        try {
            if(!running)return;
            KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA");generator.initialize(1024);identity=generator.generateKeyPair();
            List<ServerSocket> listeners=CallbackPorts.open(local,10922,10921,10920);
            for(ServerSocket s:listeners)if(!track(s)){for(ServerSocket pending:listeners)try{pending.close();}catch(IOException ignored){}return;}
            log("Callback ports ready with TCP session reuse enabled.");
            for(int i=0;i<listeners.size();i++){ServerSocket listener=listeners.get(i);boolean control=i==0;workers.execute(()->accept(listener,control));}
            try(Socket probe=new Socket()) {
                if(!track(probe))return;if(network!=null)network.bindSocket(probe);
                probe.bind(new InetSocketAddress(local,0));probe.connect(new InetSocketAddress(target,port),3000);probe.setSoTimeout(4000);
                probe.getOutputStream().write(Pxc.encode(Pxc.PROBE,new JSONObject().put("phoneType","Android").put("packageName",PACKAGE).toString()));
                Pxc reply=Pxc.read(probe.getInputStream());
                if(reply.command!=Pxc.ACK||!new JSONObject(reply.text()).optBoolean("status",false))throw new IOException("Dashboard rejected this app’s session probe");
                log("Stream session probe accepted. Waiting for capability/media negotiation.");
            }
        }catch(Exception e){if(running){log("Stream setup failed: "+e.getClass().getSimpleName()+" "+e.getMessage());stopSelf();}}
    }
    private boolean track(Closeable socket)throws IOException {
        synchronized(sockets){if(!running){socket.close();return false;}sockets.add(socket);return true;}
    }
    private void accept(ServerSocket server,boolean control) {
        while(running)try {
            Socket s=server.accept();
            if(!s.getInetAddress().equals(target)||!connections.tryAcquire()){s.close();continue;}
            if(!track(s)){connections.release();continue;}s.setSoTimeout(control?0:12000);
            int channelId=channelIds.incrementAndGet();log("Opened "+(control?"control":"media")+" channel #"+channelId+" on local port "+s.getLocalPort());
            workers.execute(()->{try(Socket connection=s){if(control)control(connection);else media(connection);}catch(Exception e){if(running)log((control?"Control":"Media")+" channel #"+channelId+" ended at "+(System.currentTimeMillis()-startedAt)+" ms: "+e.getClass().getSimpleName()+" "+e.getMessage());if(!control)stopSelf();}finally{connections.release();}});
        }catch(SocketTimeoutException ignored){}catch(Exception e){if(running)log("Callback listener ended: "+e.getClass().getSimpleName());break;}
    }
    private void control(Socket s)throws Exception {
        InputStream in=s.getInputStream();PxcWriter out=new PxcWriter(s.getOutputStream());
        int heartbeatCount=0,acknowledged=0;
        ControlKeepalive keepalive=null;controlOpen=true;
        // The first time the dashboard sends each kind of request its contents go in the log (identity fields redacted),
        // with the phone's clock, so a shared report shows what the dashboard asks for, e.g. if it expects the time.
        Set<Integer> shown=new HashSet<>();
        log("Control channel open. Phone time "+clock()+".");
        try {
        while(running) {
            Pxc f=Pxc.read(in);
            if(f.command!=0x70000000&&f.command!=0x70000001&&f.command!=Pxc.CLIENT_INFO&&shown.add(f.command)){
                log("Dashboard sent 0x"+Integer.toHexString(f.command)+": "+preview(f.payload));remember(f.command,f.payload);}
            if(f.command==0x70000000){heartbeatCount++;if(heartbeatCount==1)log("Received and replying to control heartbeat #"+heartbeatCount);}
            if(f.command==0x70000001){acknowledged++;if(acknowledged==1)log("Dashboard replied to outgoing heartbeat #"+acknowledged+" on "+s.getPort());continue;}
            if((f.command==0x10000||f.command==0x20000)&&keepalive==null) {
                String channel=f.command==0x10000?"CAR_CTRL":"CAR_DATA";
                keepalive=new ControlKeepalive(out,2000,count->{if(running&&count==1)log("Sent proactive heartbeat #"+count+" on "+channel);},error->{if(running)log("Keepalive write failed on "+channel+": "+error.getClass().getSimpleName());try{s.close();}catch(IOException ignored){}});
                if(!track(keepalive))return;log("Enabled phone-originated keepalive every 2000 ms on "+channel);
            }
            if(f.command==0x10000||f.command==0x20000||f.command==0x10690||f.command==0x70000000)out.write(Pxc.encode(f.command+1,""));
            else if(f.command==Pxc.CLIENT_INFO) {
                JSONObject bike=new JSONObject(f.text());String huid=bike.optString("HUID",bike.optString("huid",""));
                // The bike's own screen: its panel pixels and the video size it shows, so the scene can be drawn for it.
                int pw=bike.optInt("screenWidth",0),ph=bike.optInt("screenHeight",0),vw=bike.optInt("width",0),vh=bike.optInt("height",0);
                if(pw<=0||ph<=0){pw=vw;ph=vh;}
                NavSession.get(this).screenReport(pw,ph,vw,vh,0,bike.optString("screenType",bike.optString("productType","")));
                log("Dashboard screen: panel "+pw+"x"+ph+", video "+vw+"x"+vh+", type "+bike.optString("screenType","?")+".");
                log("Dashboard info: "+preview(f.payload));remember(f.command,f.payload);
                clockChannel=bike.optString("channel","");clockWithDate=bike.optBoolean("supportSyncCorrectTime",false);
                if(huid.isEmpty()||huid.getBytes(StandardCharsets.UTF_8).length>100)throw new IOException("Missing or unsupported dashboard identity field");
                Cipher rsa=Cipher.getInstance("RSA/ECB/PKCS1Padding");rsa.init(Cipher.ENCRYPT_MODE,identity.getPrivate());
                String signed=Base64.encodeToString(rsa.doFinal(huid.getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP);
                JSONObject phone=new JSONObject().put("pxcVersion","1.0.2").put("phoneUUID",UUID.randomUUID().toString())
                    .put("phoneBrand",Build.BRAND).put("phoneModel",Build.MODEL).put("phoneOsVersion",String.valueOf(Build.VERSION.SDK_INT)).put("phoneOs","Android")
                    .put("package",PACKAGE).put("versionCode",6).put("token",0).put("pubkey",Base64.encodeToString(identity.getPublic().getEncoded(),Base64.NO_WRAP))
                    .put("encryptedHUID",signed).put("bluetoothName","ATR QR Connect").put("supportH264IFrame",true).put("supportFunction",0).put("appVersionFingerPrint","DAANVIA-0.4.0-alpha1");
                out.write(Pxc.encode(0x10011,phone.toString()));log("Exchanged client capabilities using this app’s own generated session identity.");
                // The Wi-Fi time answer alone leaves the QJ clock at 00:00; Carbit sets it over Bluetooth (see BleClock).
                synchronized(this){if(bleClock==null&&running){bleClock=new BleClock(this,m->event("log",m));main.post(bleClock::start);}}
            } else if(f.command==0x103e0) {
                JSONObject request=new JSONObject(f.text());
                out.write(Pxc.encode(0x103e1,""));
                out.write(Pxc.encode(0x201c0,new JSONObject().put("isOk",true).put("errCode",0).put("errMsg","").put("id",request.optString("sn")).put("client_set",request.optString("client_set")).toString()));
            } else if(f.command==0x201c1)log("Dashboard acknowledged the client session check.");
            // The dashboard asks the phone for the time; an empty answer left its clock counting up from 00:00 (see DashClock).
            else if(f.command==DashClock.QUERY_TIME) {
                long now=System.currentTimeMillis();TimeZone zone=TimeZone.getDefault();
                out.write(Pxc.encode(DashClock.QUERY_TIME+1,DashClock.queryTimeReply(now,zone,clockWithDate,clockChannel)));
                log("Told the dashboard the time: "+clock()+".");
            } else if(f.command==DashClock.TIME_SYNC) {
                out.write(Pxc.encode(DashClock.TIME_SYNC+1,DashClock.timeSyncReply(f.payload,System.currentTimeMillis(),TimeZone.getDefault())));
                log(DashClock.keepsOwnTime(f.payload)?"The dashboard's clock is already set; left as it is.":"Set the dashboard's clock: "+clock()+".");
            }
            else if(f.command==0x10780||f.command==0x103a0||f.command==0x10020||f.command==0x10040||f.command==0x10750||f.command==0x10430||f.command==0x10460) {
                out.write(Pxc.encode(f.command+1,""));
            } else if(f.command==0x10630||f.command==0x104a0) {
                out.write(Pxc.encode(f.command+1,""));
                log("Acknowledged ATR-observed control request 0x"+Integer.toHexString(f.command)+" -> 0x"+Integer.toHexString(f.command+1)+"; payload length="+f.payload.length+". Disconnect relevance unverified.");
            } else log("Unimplemented control command 0x"+Integer.toHexString(f.command)+"; payload length="+f.payload.length+"; no guessed reply sent.");
            out.flush();
        }
        } finally {controlOpen=false;if(keepalive!=null)keepalive.close();log("Control channel closed. Phone time "+clock()+".");}
    }
    /** From the dashboard's CLIENT_INFO: its model number, and whether it wants a formatted date with the time. */
    private volatile String clockChannel="";private volatile boolean clockWithDate;
    private BleClock bleClock;private Here freeRide;
    private static String clock(){return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z",Locale.US).format(new Date())+" "+TimeZone.getDefault().getID();}
    /** A request's contents for the log: text when it reads as text, else the first bytes in hex. Identity fields redacted. */
    static String preview(byte[] b){return preview(b,600);}
    /**
     * What the dashboard sent, kept for Support → Copy bike details: its hello (in full) and the latest of each request,
     * short enough to paste into a chat in one piece.
     */
    private void remember(int command,byte[] payload){
        try{android.content.SharedPreferences p=getSharedPreferences("bike-info",0);JSONObject r=new JSONObject(p.getString("requests","{}"));
            r.put(command==Pxc.CLIENT_INFO?"hello":"0x"+Integer.toHexString(command),preview(payload,command==Pxc.CLIENT_INFO?6000:600));r.put("recorded",clock());
            p.edit().putString("requests",r.toString()).apply();}catch(Exception ignored){}
    }
    static String preview(byte[] b,int max){
        if(b.length==0)return "(empty)";
        String t=new String(b,0,Math.min(b.length,max),StandardCharsets.UTF_8);int odd=0;
        for(int i=0;i<t.length();i++){char ch=t.charAt(i);if(ch<32&&ch!='\n'&&ch!='\r'&&ch!='\t')odd++;}
        if(odd>t.length()/10){StringBuilder h=new StringBuilder();for(int i=0;i<Math.min(b.length,64);i++)h.append(String.format(Locale.US,"%02x",b[i]&0xff));return b.length+" bytes, hex "+h+(b.length>64?"…":"");}
        t=t.replaceAll("(?i)(\"(?:huid|sn|uuid|imei|mac|serial|vin|id)[a-z]*\"\\s*:\\s*\")[^\"]*","$1[redacted]");
        return b.length+" bytes: "+t.replace('\n',' ').replace('\r',' ')+(b.length>max?"…":"");
    }
    private void media(Socket s)throws Exception {
        InputStream in=s.getInputStream();OutputStream out=s.getOutputStream();
        while(running) {
            MediaWire f=MediaWire.read(in);
            switch(f.command) {
                case 16: {
                    int[] cfg=f.captureConfig();
                    synchronized(encoderLock) {
                        if(!running)throw new IOException("Stream stopped");  // never a new encoder after onDestroy
                        if(encoder==null) {
                            width=cfg[0];height=cfg[1];encoder=new VideoEncoder(this::log);
                            NavSession nav=NavSession.get(this);nav.screenReport(0,0,width,height,0,null);
                            PowerManager power=getSystemService(PowerManager.class);encoder.heat(power::getCurrentThermalStatus);
                            encoder.pace(nav.bikeFps,rate->{nav.screenReport(0,0,0,0,rate,null);log("Bike pulls about "+rate+" frames a second; drawing at that rate.");});
                            if("navigation".equals(mode))encoder.setNavigation(NavSession.get(this));
                            try{encoder.start(width,height,projection,getResources().getConfiguration().densityDpi);}
                            catch(Exception e){encoder.close();encoder=null;throw e;}
                            event("negotiated","Dashboard requested H.264 "+width+"x"+height+". Encoder initialized; visible output still needs confirmation.");
                        }else if(width!=cfg[0]||height!=cfg[1])throw new IOException("Display changed resolution; restart this test");
                    }
                    byte[] response=MediaWire.captureReply(width,height,cfg[2]);
                    out.write(MediaWire.encode(17,f.token,response));
                    log("Capture reply sent: extension="+cfg[2]+", payload bytes="+response.length);break;
                }
                case 48:out.write(MediaWire.encode(49,f.token,ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(1).putInt(1).array()));break;
                case 64:out.write(MediaWire.encode(65,f.token,new byte[0]));break;
                case 96:out.write(MediaWire.encode(97,f.token,"{\"state\":0}".getBytes(StandardCharsets.UTF_8)));break;
                case 112:out.write(MediaWire.encode(113,f.token,new byte[0]));log("Dashboard requested video start.");break;
                case 114: {
                    VideoEncoder current; synchronized(encoderLock){current=encoder;}
                    if(current==null)throw new IOException("Dashboard requested frames before configuration");
                    byte[] frame=current.next();writingSince=System.currentTimeMillis();MediaWire.writeVideo(out,frame);writingSince=0;frames++;bytes+=frame.length;lastFrameAt=System.currentTimeMillis();
                    if(frames==1||frames%1800==0)event("frames","Sent "+frames+" H.264 frames ("+bytes+" bytes) in response to dashboard pulls. Rendering is not automatically verified.");
                    break;
                }
                default:throw new IOException("Unsupported media command "+f.command);
            }
            out.flush();
        }
    }
    public void onDestroy() {
        navigationActive=false;mirroring=false;
        if(navigationWake!=null&&navigationWake.isHeld())navigationWake.release();
        running=false;if(deadline!=null)main.removeCallbacks(deadline);main.removeCallbacks(watchdog);
        synchronized(this){if(bleClock!=null){bleClock.close();bleClock=null;}}
        if(freeRide!=null){freeRide.stop();freeRide=null;}
        synchronized(sockets){for(Closeable s:sockets)try{s.close();}catch(IOException ignored){}sockets.clear();}
        workers.shutdownNow();
        synchronized(encoderLock){if(encoder!=null){encoder.close();encoder=null;}}
        if(projection!=null){if(projectionCallback!=null)projection.unregisterCallback(projectionCallback);projection.stop();projection=null;}
        sendBroadcast(new Intent(EVENT).setPackage(getPackageName()).putExtra("kind","stopped").putExtra("message","Stream stopped. Frames sent="+frames+", bytes="+bytes+".").putExtra("mode",mode).putExtra("frames",frames).putExtra("bytes",bytes).putExtra("user",userStop));
        stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();
    }
}
