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
    /** The display stream has no time limit, so end it (and let the app reconnect) when the bike stops talking to it. */
    private final Runnable watchdog=new Runnable(){public void run(){if(!running)return;long now=System.currentTimeMillis();
        if(frames==0&&now-startedAt>45000){log("The display never asked for video within 45 s.");stopSelf();return;}
        if(frames>0&&!controlOpen&&now-lastFrameAt>15000){log("The display stopped asking for video.");stopSelf();return;}
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
            startForeground(17,n,type);
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
        try {
        while(running) {
            Pxc f=Pxc.read(in);
            if(f.command==0x70000000){heartbeatCount++;if(heartbeatCount==1||heartbeatCount%5==0)log("Received and replying to control heartbeat #"+heartbeatCount);}
            if(f.command==0x70000001){acknowledged++;if(acknowledged==1||acknowledged%5==0)log("Dashboard replied to outgoing heartbeat #"+acknowledged+" on "+s.getPort());continue;}
            if((f.command==0x10000||f.command==0x20000)&&keepalive==null) {
                String channel=f.command==0x10000?"CAR_CTRL":"CAR_DATA";
                keepalive=new ControlKeepalive(out,2000,count->{if(running&&(count==1||count%5==0))log("Sent proactive heartbeat #"+count+" on "+channel);},error->{if(running)log("Keepalive write failed on "+channel+": "+error.getClass().getSimpleName());try{s.close();}catch(IOException ignored){}});
                if(!track(keepalive))return;log("Enabled phone-originated keepalive every 2000 ms on "+channel);
            }
            if(f.command==0x10000||f.command==0x20000||f.command==0x10690||f.command==0x70000000)out.write(Pxc.encode(f.command+1,""));
            else if(f.command==Pxc.CLIENT_INFO) {
                JSONObject bike=new JSONObject(f.text());String huid=bike.optString("HUID",bike.optString("huid",""));
                if(huid.isEmpty()||huid.getBytes(StandardCharsets.UTF_8).length>100)throw new IOException("Missing or unsupported dashboard identity field");
                Cipher rsa=Cipher.getInstance("RSA/ECB/PKCS1Padding");rsa.init(Cipher.ENCRYPT_MODE,identity.getPrivate());
                String signed=Base64.encodeToString(rsa.doFinal(huid.getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP);
                JSONObject phone=new JSONObject().put("pxcVersion","1.0.2").put("phoneUUID",UUID.randomUUID().toString())
                    .put("phoneBrand",Build.BRAND).put("phoneModel",Build.MODEL).put("phoneOsVersion",String.valueOf(Build.VERSION.SDK_INT)).put("phoneOs","Android")
                    .put("package",PACKAGE).put("versionCode",6).put("token",0).put("pubkey",Base64.encodeToString(identity.getPublic().getEncoded(),Base64.NO_WRAP))
                    .put("encryptedHUID",signed).put("bluetoothName","ATR QR Connect").put("supportH264IFrame",true).put("supportFunction",0).put("appVersionFingerPrint","DAANVIA-0.4.0-alpha1");
                out.write(Pxc.encode(0x10011,phone.toString()));log("Exchanged client capabilities using this app’s own generated session identity.");
            } else if(f.command==0x103e0) {
                JSONObject request=new JSONObject(f.text());
                out.write(Pxc.encode(0x103e1,""));
                out.write(Pxc.encode(0x201c0,new JSONObject().put("isOk",true).put("errCode",0).put("errMsg","").put("id",request.optString("sn")).put("client_set",request.optString("client_set")).toString()));
            } else if(f.command==0x201c1)log("Dashboard acknowledged the client session check.");
            else if(f.command==0x10780||f.command==0x103a0||f.command==0x10020||f.command==0x10040||f.command==0x10600||f.command==0x10750||f.command==0x10430||f.command==0x10450||f.command==0x10460) {
                out.write(Pxc.encode(f.command+1,""));
            } else if(f.command==0x10630||f.command==0x104a0) {
                out.write(Pxc.encode(f.command+1,""));
                log("Acknowledged ATR-observed control request 0x"+Integer.toHexString(f.command)+" -> 0x"+Integer.toHexString(f.command+1)+"; payload length="+f.payload.length+". Disconnect relevance unverified.");
            } else log("Unimplemented control command 0x"+Integer.toHexString(f.command)+"; payload length="+f.payload.length+"; no guessed reply sent.");
            out.flush();
        }
        } finally {controlOpen=false;if(keepalive!=null)keepalive.close();}
    }
    private void media(Socket s)throws Exception {
        InputStream in=s.getInputStream();OutputStream out=s.getOutputStream();
        while(running) {
            MediaWire f=MediaWire.read(in);
            switch(f.command) {
                case 16: {
                    int[] cfg=f.captureConfig();
                    synchronized(encoderLock) {
                        if(encoder==null) {
                            width=cfg[0];height=cfg[1];encoder=new VideoEncoder(this::log);
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
                    byte[] frame=current.next();MediaWire.writeVideo(out,frame);frames++;bytes+=frame.length;lastFrameAt=System.currentTimeMillis();
                    if(frames==1||frames%30==0)event("frames","Sent "+frames+" H.264 frames ("+bytes+" bytes) in response to dashboard pulls. Rendering is not automatically verified.");
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
        synchronized(sockets){for(Closeable s:sockets)try{s.close();}catch(IOException ignored){}sockets.clear();}
        workers.shutdownNow();
        synchronized(encoderLock){if(encoder!=null){encoder.close();encoder=null;}}
        if(projection!=null){if(projectionCallback!=null)projection.unregisterCallback(projectionCallback);projection.stop();projection=null;}
        sendBroadcast(new Intent(EVENT).setPackage(getPackageName()).putExtra("kind","stopped").putExtra("message","Stream stopped. Frames sent="+frames+", bytes="+bytes+".").putExtra("mode",mode).putExtra("frames",frames).putExtra("bytes",bytes).putExtra("user",userStop));
        stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();
    }
}
