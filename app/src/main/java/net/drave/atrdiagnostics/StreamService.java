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
    private final ExecutorService workers=Executors.newCachedThreadPool();  // threads only while there is work; idle ones end after a minute
    private static java.security.KeyPair sharedIdentity;
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
    private volatile long lastFrameAt, lastControlAt; private boolean waitingNoted;
    /** Control channels open now: the dashboard opens two (CAR_CTRL and CAR_DATA); the link is up while either is. */
    private final java.util.concurrent.atomic.AtomicInteger controlChannels=new java.util.concurrent.atomic.AtomicInteger();
    private boolean controlOpen(){return controlChannels.get()>0;}
    /** When the video frame now being sent started sending; 0 when none is. A send stuck this long means a stalled link. */
    private volatile long writingSince;
    /** The display stream has no time limit, so end it (and let the app reconnect) when the bike stops talking to it. */
    private final Runnable watchdog=new Runnable(){public void run(){if(!running)return;long now=System.currentTimeMillis();
        if(navigationWake!=null)navigationWake.acquire(30000);  // a timed lock, renewed here: never left held if this service ends without onDestroy
        if(freeRide!=null)freeRide.sync();  // the display's own location feed pauses during a ride (NavService has one) and resumes after
        // Holding the control link open without asking for video means the bike is showing its own screen (the rider left
        // projection, or it came back after the phone closed Daanvia). Starting over every 45 s didn't bring the video back
        // (logs of 1 Nov: four restarts, no video) and gave the dashboard a new session each time, so wait for it instead.
        if(frames==0&&now-startedAt>45000){
            if(!controlOpen()){log("The display never asked for video within 45 s.");stopSelf();return;}
            if(!waitingNoted){waitingNoted=true;log("The bike is connected but showing its own screen; waiting for it to ask for directions.");}
            if(now-lastControlAt>20000){log("The bike went quiet for 20 s; reconnecting.");stopSelf();return;}
        }
        // The rider left the map on the bike (its own screen, with the turn arrows Daanvia keeps sending, see BikeHud). As
        // long as the control link is alive the session stays, as Carbit Ride's does, and the map comes back when the bike
        // asks for video again. It used to end the session here, which took the arrows away with it.
        if(frames>0&&now-lastFrameAt>12000){
            if(!controlOpen()){log("The bike stopped asking for video and its control link closed. Reconnecting.");stopSelf();return;}
            if(now-lastControlAt>20000){log("The bike went quiet for 20 s; reconnecting.");stopSelf();return;}
            if(!ownScreen){ownScreen=true;log("The bike left the map for its own screen; keeping the link so it shows the turn arrows.");}
        }
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
            NotifyChannels.create(this);
            PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,StreamService.class).setAction("stop"),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            PendingIntent open=PendingIntent.getActivity(this,2,new Intent(this,HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            // Buttons that work from the lock screen: Reconnect and Exit (RideActions), and Stop for the display alone.
            Notification.Builder nb=new Notification.Builder(this,NotifyChannels.DISPLAY).setSmallIcon(R.drawable.ic_stat_daanvia).setContentTitle(navigation?"Daanvia is on your bike display":mirror?"Your screen is on your bike display":"Dashboard test pattern")
                .setContentText(navigation?"Reconnect if the bike shows its own screen":"Temporary test • tap Stop to end").setContentIntent(open).setOngoing(true).setVisibility(Notification.VISIBILITY_PUBLIC);
            if(navigation)nb.addAction(new Notification.Action.Builder(null,"Reconnect",RideActions.intent(this,RideActions.RECONNECT,3)).build());
            nb.addAction(new Notification.Action.Builder(null,"Stop",stop).build());
            if(navigation)nb.addAction(new Notification.Action.Builder(null,"Exit",RideActions.intent(this,RideActions.EXIT,4)).build());
            Notification n=nb.build();
            int type=ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
            if(mirror)type|=ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
            // Without a ride the bike display still follows the rider (a free ride), so this service keeps location going
            // itself; NavService takes over during a ride (see Here.feed).
            boolean follow=navigation&&Here.precise(this)&&!NavSession.get(this).active;
            try{startForeground(17,n,follow?type|ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION:type);}
            catch(SecurityException noLocation){follow=false;startForeground(17,n,type);}
            if(follow){freeRide=new Here(this,NavSession.get(this));freeRide.start();}
            if(navigation){navigationWake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Daanvia:display");navigationWake.setReferenceCounted(false);navigationWake.acquire(30000);
                // OPPO/OnePlus/realme close apps ~5 min after the screen goes off unless they play audio (see QuietAudio).
                if(KeepAlive.colorOs()!=null){quiet=new QuietAudio(m->event("log",m));quiet.start(this);}}
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
            running=true;frames=0;bytes=0;startedAt=System.currentTimeMillis();current=this;ownScreen=false;dataOut=null;hudMaxIcon=19;
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
            // One session identity per app run: generating a key pair took noticeable CPU at every reconnect.
            synchronized(StreamService.class){if(sharedIdentity==null){KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA");generator.initialize(1024);sharedIdentity=generator.generateKeyPair();}identity=sharedIdentity;}
            List<ServerSocket> listeners=CallbackPorts.open(local,10922,10921,10920);
            for(ServerSocket s:listeners)if(!track(s)){for(ServerSocket pending:listeners)try{pending.close();}catch(IOException ignored){}return;}
            // The 1 s accept timeout suits Support's short identification probe; for a ride-long session it woke three threads
            // every second for nothing. Closing the sockets (onDestroy) ends a blocking accept just the same.
            for(ServerSocket s:listeners)s.setSoTimeout(0);
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
            workers.execute(()->{try(Socket connection=s){if(control)control(connection);else media(connection);}catch(Exception e){if(running)log((control?"Control":"Media")+" channel #"+channelId+" ended at "+(System.currentTimeMillis()-startedAt)+" ms: "+e.getClass().getSimpleName()+" "+e.getMessage());if(!control&&!controlOpen())stopSelf();}finally{connections.release();}});
        }catch(SocketTimeoutException ignored){}catch(Exception e){if(running)log("Callback listener ended: "+e.getClass().getSimpleName());break;}
    }
    private void control(Socket s)throws Exception {
        InputStream in=s.getInputStream();PxcWriter out=new PxcWriter(s.getOutputStream());
        int heartbeatCount=0,acknowledged=0;
        ControlKeepalive keepalive=null;controlChannels.incrementAndGet();lastControlAt=System.currentTimeMillis();
        // The first time the dashboard sends each kind of request its contents go in the log (identity fields redacted),
        // with the phone's clock, so a shared report shows what the dashboard asks for, e.g. if it expects the time.
        Set<Integer> shown=new HashSet<>();
        log("Control channel open. Phone time "+clock()+".");
        try {
        while(running) {
            Pxc f=Pxc.read(in);lastControlAt=System.currentTimeMillis();
            if(f.command!=0x70000000&&f.command!=0x70000001&&f.command!=Pxc.CLIENT_INFO&&shown.add(f.command)){
                log("Dashboard sent 0x"+Integer.toHexString(f.command)+": "+preview(f.payload));remember(f.command,f.payload);}
            if(f.command==0x70000000){heartbeatCount++;if(heartbeatCount==1)log("Received and replying to control heartbeat #"+heartbeatCount);}
            if(f.command==0x70000001){acknowledged++;if(acknowledged==1)log("Dashboard replied to outgoing heartbeat #"+acknowledged+" on "+s.getPort());continue;}
            if((f.command==0x10000||f.command==0x20000)&&keepalive==null) {
                String channel=f.command==0x10000?"CAR_CTRL":"CAR_DATA";
                keepalive=new ControlKeepalive(out,2000,count->{if(running&&count==1)log("Sent proactive heartbeat #"+count+" on "+channel);},error->{if(running)log("Keepalive write failed on "+channel+": "+error.getClass().getSimpleName());try{s.close();}catch(IOException ignored){}});
                if(!track(keepalive))return;log("Enabled phone-originated keepalive every 2000 ms on "+channel);
            }
            // The data channel is where the phone sends its own messages to the bike (the turn arrows, BikeHud). Before the
            // reply chain below, so that chain's final "unimplemented" line isn't reached for messages answered here.
            if(f.command==0x20000&&dataOut==null){dataOut=out;startHud(out);}
            if(f.command==0x10000||f.command==0x20000||f.command==0x10690||f.command==0x70000000)out.write(Pxc.encode(f.command+1,""));
            else if(f.command==Pxc.CLIENT_INFO) {
                JSONObject bike=new JSONObject(f.text());String huid=bike.optString("HUID",bike.optString("huid",""));
                // The bike's own screen: its panel pixels and the video size it shows, so the scene can be drawn for it.
                int pw=bike.optInt("screenWidth",0),ph=bike.optInt("screenHeight",0),vw=bike.optInt("width",0),vh=bike.optInt("height",0);
                if(pw<=0||ph<=0){pw=vw;ph=vh;}
                NavSession.get(this).screenReport(pw,ph,vw,vh,0,bike.optString("screenType",bike.optString("productType","")));
                log("Dashboard screen: panel "+pw+"x"+ph+", video "+vw+"x"+vh+", type "+bike.optString("screenType","?")+".");
                log("Dashboard info: "+preview(f.payload,2000));remember(f.command,f.payload);
                clockChannel=bike.optString("channel","");clockWithDate=bike.optBoolean("supportSyncCorrectTime",false);
                if(huid.isEmpty()||huid.getBytes(StandardCharsets.UTF_8).length>100)throw new IOException("Missing or unsupported dashboard identity field");
                Cipher rsa=Cipher.getInstance("RSA/ECB/PKCS1Padding");rsa.init(Cipher.ENCRYPT_MODE,identity.getPrivate());
                String signed=Base64.encodeToString(rsa.doFinal(huid.getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP);
                long huTime=bike.optLong("currentHUTime",0),seenAt=SystemClock.elapsedRealtime();
                syncLogged=false;
                JSONObject phone=new JSONObject().put("pxcVersion","1.0.2").put("phoneUUID",phoneId())
                    .put("phoneBrand",Build.BRAND).put("phoneModel",Build.MODEL).put("phoneOsVersion",String.valueOf(Build.VERSION.SDK_INT)).put("phoneOs","Android")
                    .put("package",PACKAGE).put("versionCode",6).put("token",0).put("pubkey",Base64.encodeToString(identity.getPublic().getEncoded(),Base64.NO_WRAP))
                    .put("encryptedHUID",signed).put("bluetoothName","ATR QR Connect").put("supportH264IFrame",true).put("supportFunction",bike.optLong("supportFunction",0)).put("appVersionFingerPrint","DAANVIA-0.4.0-alpha1")
                    // Carbit Ride adds the dashboard's voice engine back to it; it gave the bike the same id on every connection too.
                    .put("speechEngineType",bike.optInt("supportSpeechEngine",2));
                out.write(Pxc.encode(0x10011,phone.toString()));log("Exchanged client capabilities using this app’s own generated session identity.");
                log(DashClock.describe(huTime,System.currentTimeMillis(),TimeZone.getDefault()));
                final PxcWriter clockOut=out;main.postDelayed(()->{if(running)workers.execute(()->pushClock(clockOut,huTime,seenAt));},DashClock.ASK_GRACE_MS);
                // No Bluetooth clock: Carbit Ride never uses Bluetooth; the bike takes its time over this link (0x10450, 0x10430).
            } else if(f.command==0x103e0) {
                JSONObject request=new JSONObject(f.text());
                out.write(Pxc.encode(0x103e1,""));
                out.write(Pxc.encode(0x201c0,new JSONObject().put("isOk",true).put("errCode",0).put("errMsg","").put("id",request.optString("sn")).put("client_set",request.optString("client_set")).toString()));
            } else if(f.command==0x201c1)log("Dashboard acknowledged the client session check.");
            // The dashboard asks the phone for the time; an empty answer left its clock counting up from 00:00 (see DashClock).
            else if(f.command==DashClock.QUERY_TIME) {
                timeAsked=true;sendTime(out);
                log("Told the dashboard the time: "+clock()+".");
            } else if(f.command==DashClock.TIME_SYNC) {
                timeAsked=true;  // answered here; an unasked 0x10451 on top could overwrite a clock this left alone
                out.write(Pxc.encode(DashClock.TIME_SYNC+1,DashClock.timeSyncReply(f.payload,System.currentTimeMillis(),TimeZone.getDefault())));
                // It asks every few seconds: the log gets the first answer, and any change between keeping and setting.
                boolean kept=DashClock.keepsOwnTime(f.payload,System.currentTimeMillis());
                if(!syncLogged||kept!=syncKept){syncLogged=true;syncKept=kept;log((kept?"The dashboard's clock is set; left as it is":"Set the dashboard's clock: "+clock())+". It said: "+DashClock.stampOf(f.payload)+".");}
            }
            // The bike asks where the phone is; Carbit answers with the GPS fix and its time, and the bike's clock comes from it.
            else if(f.command==0x10430){out.write(Pxc.encode(0x10431,locationNow()));if(!locationLogged){locationLogged=true;log("Told the dashboard where the phone is"+(NavSession.get(this).fixAt>0?", with the GPS time.":" (no GPS fix yet)."));}}
            // The bike asks for turn arrows for its own screen (BikeHud).
            else if(f.command==BikeHud.ASK){out.write(Pxc.encode(BikeHud.ASK+1,""));hudMaxIcon=BikeHud.maxIcon(f.text());log("The dashboard asked for turn arrows (it knows "+hudMaxIcon+").");}
            // Replies to the phone's own messages on the data channel: nothing to do.
            else if((f.command>>>16)==2&&(f.command&1)==1){}
            else if(f.command==0x10780||f.command==0x103a0||f.command==0x10020||f.command==0x10750||f.command==0x10460) {
                out.write(Pxc.encode(f.command+1,""));
            } else if(f.command==0x10630||f.command==0x104a0) {
                out.write(Pxc.encode(f.command+1,""));
                log("Acknowledged ATR-observed control request 0x"+Integer.toHexString(f.command)+" -> 0x"+Integer.toHexString(f.command+1)+"; payload length="+f.payload.length+". Disconnect relevance unverified.");
            } else log("Unimplemented control command 0x"+Integer.toHexString(f.command)+"; payload length="+f.payload.length+"; no guessed reply sent.");
            out.flush();
        }
        } finally {controlChannels.decrementAndGet();if(keepalive!=null)keepalive.close();log("Control channel closed. Phone time "+clock()+".");}
    }
    /** From the dashboard's CLIENT_INFO: its model number, and whether it wants a formatted date with the time. */
    private volatile String clockChannel="";private volatile boolean clockWithDate;
    /** The dashboard asked for the time in this session (0x10450); then it is answered, never pushed. */
    private volatile boolean timeAsked;
    /** For the time-sync log lines: whether one was written this session, and whether the last answer kept the dashboard's clock. */
    private volatile boolean syncLogged,syncKept;
    /**
     * Some dashboards don't ask for the time on a reconnect, and a first answer doesn't always stick (logs of 30 Oct: the
     * clock stayed a count since the bike was switched on). So a few seconds after the hello, a dashboard that didn't ask
     * and whose clock is a count, or more than two minutes off, is sent the time anyway, as MOTO-HUB found Carbit Ride does.
     * The bike keeps no time while switched off, so this runs on every connection.
     */
    private void pushClock(PxcWriter out,long huTime,long seenAt){
        if(!running||timeAsked)return;
        long now=System.currentTimeMillis();TimeZone zone=TimeZone.getDefault();
        if(!DashClock.needsTime(huTime,SystemClock.elapsedRealtime()-seenAt,now,zone))return;
        try{sendTime(out);log("The dashboard didn't ask for the time and its clock was wrong; sent the phone's time: "+clock()+".");}
        catch(Exception e){log("Couldn't send the time: "+e.getClass().getSimpleName());}
    }
    /** The phone's time, as the answer to the dashboard's 0x10450 (see DashClock). */
    private void sendTime(PxcWriter out)throws IOException{out.write(Pxc.encode(DashClock.QUERY_TIME+1,DashClock.queryTimeReply(System.currentTimeMillis(),TimeZone.getDefault(),clockWithDate,clockChannel)));}
    private BleClock bleClock;private Here freeRide;private QuietAudio quiet;
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
        // The bike asks for video again: it is back on the map.
        if(ownScreen&&frames>0)log("The bike is back on the map.");ownScreen=false;lastFrameAt=System.currentTimeMillis();
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
    /** This phone's id for the bike, the same on every connection (it was a new random one each time). */
    private String phoneId(){
        SharedPreferences p=getSharedPreferences("saved-bike",0);String id=p.getString("phone_id",null);
        if(id==null){id=UUID.randomUUID().toString();p.edit().putString("phone_id",id).apply();}
        return id;
    }
    // ---------------------------------------------------------------- the bike's own screen (BikeHud)
    private static volatile StreamService current;
    /** The bike shows its own screen with turn arrows instead of the phone's map (0x20170), until switched back. */
    public static volatile boolean ownScreen;
    private volatile PxcWriter dataOut;private volatile int hudMaxIcon=19;private boolean locationLogged;
    /** The phone's last GPS fix for the bike's location question (0x10430), its time taken from when it was seen. */
    private String locationNow(){
        NavSession n=NavSession.get(this);boolean known=n.fixAt>0&&!n.simulation;
        long fixTime=System.currentTimeMillis()-(SystemClock.elapsedRealtime()-n.fixAt);
        return BikeHud.location(known,n.lat,n.lon,n.speedMetersPerSecond,0,n.bearing,known?fixTime:System.currentTimeMillis());
    }
    /** Every second while connected: navigation on or off, and the next turn when it changes (at least every 5 s). */
    private void startHud(PxcWriter out){
        workers.execute(()->{
            Boolean wasNavigating=null;String last="";long sentAt=0;
            try{
                while(running&&dataOut==out){
                    NavSession n=NavSession.get(this);
                    boolean navigating=n.active&&!n.cruise&&n.maneuvers.length()>0;
                    if(wasNavigating==null||navigating!=wasNavigating){
                        out.write(Pxc.encode(BikeHud.NAVI_STATUS,new JSONObject().put("status",navigating).toString()));
                        if(!navigating)out.write(Pxc.encode(BikeHud.INFO,"{\"status\":1}"));
                        if(wasNavigating!=null||navigating)log("Told the dashboard navigation "+(navigating?"started":"ended")+".");
                        wasNavigating=navigating;last="";
                    }
                    if(navigating){
                        String info=BikeHud.info(n.maneuvers,n.maneuver,n.distanceToTurn,n.remaining,n.seconds,n.destination,hudMaxIcon,System.currentTimeMillis(),TimeZone.getDefault());
                        long now=SystemClock.elapsedRealtime();
                        if(!info.equals(last)||now-sentAt>5000){out.write(Pxc.encode(BikeHud.INFO,info));last=info;sentAt=now;}
                    }
                    Thread.sleep(1000);
                }
            }catch(InterruptedException ignored){}catch(Exception e){if(running)log("Turn arrows stopped: "+e.getClass().getSimpleName());}
        });
    }
    /** Switch the bike between its own screen with the arrows ({@code own}) and the phone's map. False when not connected. */
    public static boolean showOwnScreen(boolean own){
        StreamService c=current;PxcWriter out=c==null?null:c.dataOut;if(out==null||!c.running)return false;
        ownScreen=own;
        c.workers.execute(()->{try{out.write(Pxc.encode(own?BikeHud.OWN_SCREEN:BikeHud.PHONE_SCREEN,""));c.log(own?"Showed the bike's own screen with turn arrows.":"Showed the phone's map on the bike again.");}catch(Exception e){c.log("Couldn't switch the bike's screen: "+e.getClass().getSimpleName());}});
        return true;
    }

    public void onDestroy() {
        navigationActive=false;mirroring=false;ownScreen=false;if(current==this)current=null;dataOut=null;
        if(navigationWake!=null&&navigationWake.isHeld())navigationWake.release();
        running=false;main.removeCallbacksAndMessages(null);
        synchronized(this){if(bleClock!=null){bleClock.close();bleClock=null;}}
        if(freeRide!=null){freeRide.stop();freeRide=null;}
        if(quiet!=null){quiet.stop();quiet=null;}
        synchronized(sockets){for(Closeable s:sockets)try{s.close();}catch(IOException ignored){}sockets.clear();}
        workers.shutdownNow();
        // Closing the encoder waits for its threads (up to 1.6 s) and ending screen sharing can take as long: done off the
        // main thread, so the ride's guidance doesn't freeze every time the display link restarts.
        final VideoEncoder enc;synchronized(encoderLock){enc=encoder;encoder=null;}
        final MediaProjection proj=projection;final MediaProjection.Callback cb=projectionCallback;projection=null;projectionCallback=null;
        if(enc!=null||proj!=null)new Thread(()->{if(enc!=null)enc.close();if(proj!=null){if(cb!=null)proj.unregisterCallback(cb);proj.stop();}},"stream-teardown").start();
        // Started only to be stopped (the notification's Stop with nothing streaming): nothing happened to report.
        if(mode!=null)sendBroadcast(new Intent(EVENT).setPackage(getPackageName()).putExtra("kind","stopped").putExtra("message","Stream stopped. Frames sent="+frames+", bytes="+bytes+".").putExtra("mode",mode).putExtra("frames",frames).putExtra("bytes",bytes).putExtra("user",userStop));
        stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();
    }
}
