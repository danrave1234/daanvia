package net.drave.atrdiagnostics;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.location.LocationManager;
import android.media.projection.MediaProjectionManager;
import android.net.*;
import android.net.nsd.*;
import android.net.wifi.*;
import android.net.wifi.p2p.*;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends Activity {
    private String SSID = "", MAC = "", password = "", model = "", qrAction = "";
    private boolean profileReady;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(4);
    private final StringBuilder report = new StringBuilder();
    private final Set<String> tested = Collections.synchronizedSet(new HashSet<>());
    private final List<Closeable> sockets = Collections.synchronizedList(new ArrayList<>());
    private final AtomicBoolean testing = new AtomicBoolean(false);
    private ConnectivityManager cm;
    private WifiManager wifi;
    private WifiP2pManager p2p;
    private WifiP2pManager.Channel channel;
    private NsdManager nsd;
    private NsdManager.DiscoveryListener discovery;
    private ConnectivityManager.NetworkCallback apCallback;
    private WifiManager.MulticastLock multicast;
    private TextView logView, status;
    private ScrollView scroll;
    private Button observe, diagnose, fallback, share, stop, pattern, mirror, confirm;
    private boolean identificationAccepted, serviceActive, sawFrames;
    private int pendingStream;
    private String lastStreamMode="";
    private Intent captureConsent;
    private final Map<String,String> visualEvidence=new LinkedHashMap<>();
    private final Map<String,Long> frameEvidence=new LinkedHashMap<>();
    private final BroadcastReceiver streamReceiver=new BroadcastReceiver(){
        public void onReceive(Context c,Intent event){
            String kind=event.getStringExtra("kind"),message=event.getStringExtra("message");
            if(message!=null)log(message);
            if("frames".equals(kind)) {
                sawFrames=true;confirm.setEnabled(true);
                String mode=event.getStringExtra("mode");frameEvidence.put(mode,event.getLongExtra("frames",0));
                if(!visualEvidence.containsKey(mode))label("Video sent • check the motorcycle screen");
            } else if("negotiated".equals(kind))label("Video format negotiated • sending…");
            else if("stopped".equals(kind)) {
                frameEvidence.put(event.getStringExtra("mode"),event.getLongExtra("frames",0));
                serviceActive=false;cleanup();
                label(visualEvidence.containsKey(lastStreamMode)?"User reported: "+visualEvidence.get(lastStreamMode):sawFrames?"Test ended • confirm what appeared":"No video confirmed • share report");
            }
        }
    };
    private volatile boolean running, active, connecting;
    private boolean ownDiscovery;
    private volatile int generation;
    private Runnable deadline;
    private String verdict;
    private String trustedInterface;
    private String trustedOwner;
    private final List<NsdServiceInfo> services = new ArrayList<>();
    private boolean resolving;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        public void onReceive(Context c, Intent i) {
            if (!running) return;
            if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(i.getAction())) peers();
            if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(i.getAction())) group();
        }
    };

    public void onCreate(Bundle state) {
        super.onCreate(state);
        cm = (ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
        wifi = (WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
        p2p = (WifiP2pManager)getSystemService(WIFI_P2P_SERVICE);
        nsd = (NsdManager)getSystemService(NSD_SERVICE);
        if (p2p != null) channel = p2p.initialize(this, getMainLooper(), () -> log("Wi-Fi Direct channel closed. Reopen the app."));
        buildUi();
        IntentFilter f = new IntentFilter();
        f.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        f.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(receiver, f);
        log("Daanvia 0.4.0-alpha1 • Android " + Build.VERSION.RELEASE + " / " + Build.MANUFACTURER + " " + Build.MODEL);
        log("Scan your dashboard QR or choose its photo. QR text is decoded locally and its URL is never opened.");
        IntentFilter events=new IntentFilter(StreamService.EVENT);
        androidx.core.content.ContextCompat.registerReceiver(this,streamReceiver,events,androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
        log("Tests are local. No firmware or engine access. Screen mirroring requires Android’s sharing consent and stops automatically after 90 seconds.");
        String saved=getSharedPreferences("saved-bike",0).getString("qr","");if(!saved.isEmpty())acceptQr(saved);
        if(getIntent().getBooleanExtra("navigation_connect",false)&&profileReady){pendingStream=3;permissions(true);}
    }

    private int dp(int n) { return (int)(n * getResources().getDisplayMetrics().density); }
    private TextView text(String s, int size, int color) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(color);
        t.setPadding(0, dp(5), 0, dp(5)); return t;
    }
    private Button button(LinearLayout parent, String label, Runnable action) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false);
        parent.addView(b, new LinearLayout.LayoutParams(-1, dp(52)));
        b.setOnClickListener(v -> action.run()); return b;
    }
    private void buildUi() {
        getWindow().setStatusBarColor(Color.rgb(14,22,32));
        getWindow().setNavigationBarColor(Color.rgb(14,22,32));
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20),dp(18),dp(20),dp(12)); root.setBackgroundColor(Color.rgb(14,22,32));
        root.addView(text("Daanvia · bike setup", 27, Color.WHITE));
        root.addView(text("Scan your dashboard. Test a direct connection.", 14, Color.rgb(145,172,196)));
        root.addView(text("Park first. Close Carbit Ride and keep the dashboard’s Android QR screen open. Wi-Fi ON, hotspot OFF.", 14, Color.LTGRAY));
        status = text("Scan a QR code to begin",16,Color.rgb(93,225,179)); root.addView(status);
        observe = button(root, "1  Scan dashboard QR", () -> scanCamera());
        fallback = button(root,"Choose QR photo instead", () -> {
            stopService(new Intent(this,StreamService.class));cleanup();
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE),31);
        });
        diagnose = button(root, "2  Test connection", () -> {
            if(!profileReady) { log("Scan your dashboard QR first."); return; }
            pendingStream=0; permissions(true);
        });
        diagnose.setEnabled(false);
        button(root,"Send Daanvia to dashboard",()->{if(!profileReady){log("Scan your dashboard QR first");return;}pendingStream=3;permissions(true);});
        pattern=button(root,"3  Send animated test pattern",()->requestStream(1,null));
        mirror=button(root,"4  Mirror phone / navigation",()->{
            if(!identificationAccepted){log("Complete Test connection first.");return;}
            if(serviceActive){log("Stop the current stream test first.");return;}
            startActivityForResult(getSystemService(MediaProjectionManager.class).createScreenCaptureIntent(),40);
        });
        confirm=button(root,"Confirm what appeared on the bike",()->confirmVideo());
        pattern.setEnabled(false);mirror.setEnabled(false);confirm.setEnabled(false);
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); root.addView(row);
        stop = new Button(this); stop.setText("Stop"); stop.setAllCaps(false); row.addView(stop,new LinearLayout.LayoutParams(0,dp(48),1)); stop.setOnClickListener(v -> finishRun("Stopped"));
        share = new Button(this); share.setText("Share report"); share.setAllCaps(false); row.addView(share,new LinearLayout.LayoutParams(0,dp(48),1)); share.setOnClickListener(v -> shareReport());
        scroll = new ScrollView(this); logView = text("",12,Color.rgb(188,208,225));
        logView.setTypeface(android.graphics.Typeface.MONOSPACE); logView.setTextIsSelectable(true);
        scroll.addView(logView); root.addView(scroll,new LinearLayout.LayoutParams(-1,dp(180)));
        ScrollView page=new ScrollView(this);page.setFitsSystemWindows(true);page.addView(root);setContentView(page);
    }

    private void log(String s) {
        String line = new SimpleDateFormat("HH:mm:ss",Locale.US).format(new Date()) + "  " + clean(s) + "\n";
        synchronized(report) { if (report.length() < 150000) report.append(line); }
        main.post(() -> { if (!isDestroyed()) { synchronized(report) { logView.setText(report.toString()); } scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN)); }});
    }
    private String clean(String s) {
        return s.replaceAll("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}","[device address redacted]")
            .replaceAll("(?i)(pwd|password|passphrase|token|huid|uuid|serial|sn)[\\\"'\\s:=]+[^,}\\s]+", "$1=[redacted]");
    }
    private void label(String s) { verdict=s; main.post(() -> status.setText(s)); }
    private boolean hasPermissions() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED);
    }
    private void scanCamera() {
        stopService(new Intent(this,StreamService.class));cleanup();
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.CAMERA},20);
        else startActivityForResult(new Intent(this,ScanActivity.class),30);
    }
    protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==40){
            if(result==RESULT_OK&&data!=null)requestStream(2,data);
            else log("Screen sharing declined. Nothing was captured.");
            return;
        }
        if(result!=RESULT_OK||data==null)return;
        if(request==30) acceptQr(data.getStringExtra("qr"));
        if(request==31&&data.getData()!=null) {
            Uri image=data.getData(); label("Reading QR photo…");
            io.execute(()-> {
                try {
                    BitmapFactory.Options bounds=new BitmapFactory.Options(); bounds.inJustDecodeBounds=true;
                    try(InputStream in=getContentResolver().openInputStream(image)){BitmapFactory.decodeStream(in,null,bounds);}
                    BitmapFactory.Options opts=new BitmapFactory.Options();
                    int max=Math.max(bounds.outWidth,bounds.outHeight);
                    opts.inSampleSize=1;
                    while(max/opts.inSampleSize>2400)opts.inSampleSize*=2;
                    Bitmap bmp;
                    try(InputStream in=getContentResolver().openInputStream(image)){bmp=BitmapFactory.decodeStream(in,null,opts);}
                    if(bmp==null)throw new IOException("Image could not be read");
                    String value;
                    try { value=QrDecoder.decodeBitmap(bmp); } finally { bmp.recycle(); }
                    main.post(()->acceptQr(value));
                } catch(Exception e){ log("Could not decode this photo. Try live scanning or a closer, glare-free photo."); label("QR not decoded • try another photo"); }
            });
        }
    }
    private void acceptQr(String raw) {
        profileReady=false; diagnose.setEnabled(false);identificationAccepted=false;visualEvidence.clear();frameEvidence.clear();
        pattern.setEnabled(false);mirror.setEnabled(false);confirm.setEnabled(false);
        try {
            QrProfile profile=QrProfile.parse(raw);
            SSID=profile.ssid; MAC=profile.mac; password=profile.password; model=profile.model; qrAction=profile.action;
            getSharedPreferences("saved-bike",0).edit().putString("qr",raw).apply();NavSession.get(this).loadFit();
            profileReady=true; diagnose.setEnabled(true);
            log("QR decoded. Dashboard network: "+SSID+"; security: "+profile.security+"; model: "+model+"; connection flag: "+qrAction+". Password is hidden.");
            label("QR decoded • tap Test connection");
        } catch(Exception e){log("This QR does not contain supported dashboard pairing details. Scan the connection QR, not just an app-download code.");label("Pairing details missing");}
    }
    private boolean requestedActive;
    private void permissions(boolean mode) {
        requestedActive = mode;
        if (!hasPermissions()) {
            ArrayList<String> p = new ArrayList<>();
            p.add(Manifest.permission.ACCESS_COARSE_LOCATION); p.add(Manifest.permission.ACCESS_FINE_LOCATION);
            if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            requestPermissions(p.toArray(new String[0]), 10);
        } else start(mode);
    }
    public void onRequestPermissionsResult(int r, String[] p, int[] results) {
        super.onRequestPermissionsResult(r,p,results);
        if(r==20) {
            if(checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)scanCamera();
            else log("Camera access denied. You can choose a QR photo instead.");
        }
        if (r == 10) {
            if (hasPermissions()) start(requestedActive);
            else {
                log("Permissions missing. Allow Nearby devices and Precise location in app settings, then retry. No GPS positions are collected.");
                new AlertDialog.Builder(this).setTitle("Permissions needed").setMessage("Android needs Nearby devices and precise location access for these Wi-Fi checks. The app does not request GPS coordinates.")
                    .setPositiveButton("App settings",(d,w)->startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())))).setNegativeButton("Close",null).show();
            }
        }
    }

    private void start(boolean mode) {
        cleanup(); running = true; active = mode; connecting = false; tested.clear();
        if(pendingStream==0){identificationAccepted=false;pattern.setEnabled(false);mirror.setEnabled(false);}
        trustedInterface = null; trustedOwner = null;
        log("\n--- " + (mode ? "ACTIVE IDENTIFICATION" : "WORKING CONNECTION INSPECTION") + " ---");
        log(mode ? "Only the QR-matched dashboard will receive the bounded identification exchange." : "Inspection only: no projection probe or new pairing request.");
        if (wifi == null || !wifi.isWifiEnabled()) { log("Wi-Fi is off. Enable Wi-Fi and rerun."); finishRun("Wi-Fi is off"); return; }
        LocationManager lm = (LocationManager)getSystemService(LOCATION_SERVICE);
        if (lm != null && !lm.isLocationEnabled()) log("Location switch is OFF. Android may block peer discovery; enable it and retry if nothing appears.");
        label(mode ? "Looking for your dashboard…" : "Inspecting connection…");
        snapshot(); startNsd(); group();
        if (mode && p2p != null && channel != null) {
            ownDiscovery=true;
            try{p2p.discoverPeers(channel, action("Wi-Fi Direct discovery",null));}catch(SecurityException e){finishRun("Nearby device permission changed · retry setup");return;}
            peers();
        }
        int g = generation;
        // If P2P cannot establish the target, try the QR's AP credentials once.
        main.postDelayed(()-> {
            if(running&&g==generation&&tested.isEmpty()&&trustedInterface==null&&!password.isEmpty())joinAp();
        },18000);
        deadline = () -> {
            if (running && g == generation) {
                if (tested.isEmpty()) log("Could not establish a verified dashboard endpoint. This is inconclusive. Check the bike’s QR page is open and Carbit is fully closed, then retry.");
                finishRun(tested.isEmpty()?"No connection confirmed • share report":verdict);
            }
        };
        main.postDelayed(deadline,mode ? 45000 : 12000);
    }

    private boolean matches(String value) {
        if (value == null) return false;
        String v = value.toLowerCase(Locale.US).replace("\"","");
        return !v.isEmpty() && ((!SSID.isEmpty() && (v.equals(SSID.toLowerCase(Locale.US)) || v.endsWith("-"+SSID.toLowerCase(Locale.US)))) || (!MAC.isEmpty() && v.equals(MAC.toLowerCase(Locale.US))));
    }
    private WifiP2pManager.ActionListener action(String name, Runnable success) {
        int g = generation;
        return new WifiP2pManager.ActionListener() {
            public void onSuccess() { if (running && g == generation) { log(name + " accepted by Android."); if(success != null) success.run(); }}
            public void onFailure(int reason) { if(running && g == generation) { connecting=false; log(name+" failed: "+(reason==2?"BUSY":reason==1?"UNSUPPORTED":"ERROR "+reason)); }}
        };
    }
    private void snapshot() {
        try {
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities cap = cm.getNetworkCapabilities(n); LinkProperties lp = cm.getLinkProperties(n);
                if (cap == null || lp == null) continue;
                if (!cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue;
                WifiInfo wi = cap.getTransportInfo() instanceof WifiInfo ? (WifiInfo)cap.getTransportInfo() : null;
                String ssid = wi == null ? "<hidden by Android>" : wi.getSSID();
                log("Wi-Fi interface="+lp.getInterfaceName()+", network="+n+", SSID="+(matches(ssid)?SSID:"[other network or hidden]")+", Internet="+cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
                for (LinkAddress a : lp.getLinkAddresses()) if (a.getAddress() instanceof Inet4Address) log("  Phone IPv4="+a);
                if (matches(ssid)) {
                    trustedInterface = lp.getInterfaceName();
                    for (RouteInfo r : lp.getRoutes()) {
                        InetAddress a = r.getGateway();
                        if (a instanceof Inet4Address && !a.isAnyLocalAddress()) target(a, trustedInterface,n,"QR-matched Wi-Fi gateway");
                    }
                }
            }
            Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
            while(all != null && all.hasMoreElements()) {
                NetworkInterface f = all.nextElement();
                if (!f.isUp() || f.isLoopback()) continue;
                for (InterfaceAddress a : f.getInterfaceAddresses()) if(a.getAddress() instanceof Inet4Address)
                    log("Interface "+f.getName()+": "+a.getAddress().getHostAddress()+"/"+a.getNetworkPrefixLength());
            }
        } catch(Exception e) { log("Network snapshot: "+e.getClass().getSimpleName()+" "+e.getMessage()); }
    }
    private void group() {
        if (!running || p2p == null || channel == null) return;
        int g = generation;
        try {
            p2p.requestGroupInfo(channel, grp -> {
                if (!running || g != generation) return;
                if (grp == null) { log("No Wi-Fi Direct group visible to this app."); return; }
                WifiP2pDevice owner = grp.getOwner();
                boolean match = matches(grp.getNetworkName()) || (owner != null && (matches(owner.deviceName)||matches(owner.deviceAddress)));
                if (!match) for(WifiP2pDevice d:grp.getClientList()) if(matches(d.deviceName)||matches(d.deviceAddress)) match=true;
                log("Wi-Fi Direct group found; interface="+grp.getInterface()+", phoneIsOwner="+grp.isGroupOwner()+", matchesQR="+match);
                if (!match) { log("Group identity unconfirmed; no probe sent to it."); return; }
                connecting=false;
                trustedInterface = grp.getInterface();
                p2p.requestConnectionInfo(channel, info -> {
                    if (!running || g != generation || info == null || !info.groupFormed) return;
                    if (!info.isGroupOwner && info.groupOwnerAddress != null) {
                        trustedOwner = info.groupOwnerAddress.getHostAddress();
                        target(info.groupOwnerAddress,grp.getInterface(),null,"QR-matched Wi-Fi Direct group owner");
                    } else log("Phone owns the matched group. Waiting for dashboard service discovery; not guessing client addresses.");
                });
            });
        } catch(SecurityException e) { log("Android blocked Wi-Fi Direct group access. Check permissions."); }
    }
    private void peers() {
        if (!running || !active || p2p == null || channel == null) return;
        int g = generation;
        try { p2p.requestPeers(channel, list -> {
            if (!running || g != generation) return;
            log("Wi-Fi Direct peers visible: "+list.getDeviceList().size());
            if (connecting || trustedInterface != null) return;
            for(WifiP2pDevice d:list.getDeviceList()) if(matches(d.deviceName)||matches(d.deviceAddress)) {
                connecting=true;
                log("QR-matched dashboard peer found. Requesting connection; accept Android’s prompt if shown.");
                WifiP2pConfig cfg = new WifiP2pConfig(); cfg.deviceAddress=d.deviceAddress;
                cfg.wps.setup=WpsInfo.PBC; cfg.groupOwnerIntent=0;
                p2p.connect(channel,cfg,action("Dashboard pairing",()->main.postDelayed(this::group,2000)));
                return;
            }
        }); } catch(SecurityException e) { log("Peer scan blocked by Android permissions."); }
    }

    private void startNsd() {
        if(nsd == null) return;
        try {
            multicast=wifi.createMulticastLock("atr-discovery"); multicast.setReferenceCounted(false); multicast.acquire();
            int g=generation;
            discovery=new NsdManager.DiscoveryListener() {
                public void onDiscoveryStarted(String t) { if(running&&g==generation) log("Listening for EasyConn service advertisements."); }
                public void onDiscoveryStopped(String t) {}
                public void onStartDiscoveryFailed(String t,int e) { if(running&&g==generation) log("Service discovery unavailable: "+e); }
                public void onStopDiscoveryFailed(String t,int e) {}
                public void onServiceLost(NsdServiceInfo s) {}
                public void onServiceFound(NsdServiceInfo s) {
                    if(!running||g!=generation) return;
                    if(services.size()<12) { services.add(s); resolveNext(g); }
                }
            };
            nsd.discoverServices("_EasyConn._tcp.",NsdManager.PROTOCOL_DNS_SD,discovery);
        } catch(Exception e) { log("Service discovery: "+e.getClass().getSimpleName()); }
    }
    private void resolveNext(int g) {
        if(resolving||services.isEmpty()||!running||g!=generation) return;
        resolving=true; NsdServiceInfo s=services.remove(0);
        nsd.resolveService(s,new NsdManager.ResolveListener() {
            public void onResolveFailed(NsdServiceInfo x,int e) { if(g!=generation)return; resolving=false; log("Service resolution failed: "+e); resolveNext(g); }
            public void onServiceResolved(NsdServiceInfo x) {
                if(!running||g!=generation)return;
                resolving=false;
                InetAddress host=x.getHost();
                log("EasyConn service resolved at "+(host==null?"?":host.getHostAddress())+":"+x.getPort());
                String name=x.getServiceName();
                Map<String,byte[]> attrs=x.getAttributes();
                byte[] hu=attrs.get("huname");
                boolean named=matches(name)||(hu!=null&&matches(new String(hu,StandardCharsets.UTF_8)));
                // A service is only probed after matching the QR or the verified P2P owner.
                String iface=host==null?null:interfaceFor(host);
                boolean onTrustedLink=iface!=null&&iface.equals(trustedInterface);
                if(host!=null&&(named||onTrustedLink||(trustedOwner!=null&&trustedOwner.equals(host.getHostAddress())))) {
                    if(iface!=null) target(host,iface,null,"matched EasyConn service",x.getPort());
                } else log("Service identity does not match the target yet; discovery recorded, no probe sent.");
                resolveNext(g);
            }
        });
    }
    private String interfaceFor(InetAddress host) {
        try {
            Enumeration<NetworkInterface> n=NetworkInterface.getNetworkInterfaces();
            while(n!=null&&n.hasMoreElements()) {
                NetworkInterface f=n.nextElement();
                for(InterfaceAddress a:f.getInterfaceAddresses()) if(sameSubnet(a,host)) return f.getName();
            }
        } catch(Exception ignored) {}
        return null;
    }
    private boolean sameSubnet(InterfaceAddress local, InetAddress peer) {
        byte[] a=local.getAddress().getAddress(),b=peer.getAddress();
        int bits=local.getNetworkPrefixLength();
        if(a.length!=4||b.length!=4||bits<8||bits>32) return false;
        for(int i=0;i<4;i++) { int mask=bits>=8?255:bits<=0?0:255<<(8-bits)&255; if((a[i]&mask)!=(b[i]&mask))return false; bits-=8; }
        return !peer.equals(local.getAddress());
    }
    private void target(InetAddress host,String iface,Network network,String evidence) { target(host,iface,network,evidence,10930); }
    private void target(InetAddress host,String iface,Network network,String evidence,int port) {
        if(serviceActive)return;
        if(!running||host==null||port<1||port>65535||!(host instanceof Inet4Address)||host.isLoopbackAddress()||host.isAnyLocalAddress()||host.isMulticastAddress())return;
        label("Dashboard network found • checking protocol…");
        log("Target evidence: "+evidence+"; "+host.getHostAddress()+":"+port+" via "+iface);
        if(!active) { label("Connection identified • share report"); log("Inspection captured the target. Close Carbit and use Connect & identify for the protocol test."); return; }
        if(!testing.compareAndSet(false,true)) return;
        String key=host.getHostAddress()+":"+port;
        if(!tested.add(key)) { testing.set(false); return; }
        int g=generation;
        if(pendingStream>0) {
            int selected=pendingStream;Intent consent=captureConsent;
            io.execute(()->{
                try {
                    String local=localFor(iface,host).getHostAddress();
                    main.post(()->{
                        if(!running||g!=generation)return;
                        serviceActive=true;sawFrames=false;confirm.setEnabled(false);
                        lastStreamMode=selected==3?"navigation":selected==1?"pattern":"mirror";visualEvidence.remove(lastStreamMode);frameEvidence.remove(lastStreamMode);pendingStream=0;captureConsent=null;
                        if(deadline!=null)main.removeCallbacks(deadline);
                        Intent service=new Intent(this,StreamService.class).putExtra("host",host.getHostAddress()).putExtra("local",local).putExtra("port",port)
                            .putExtra("mode",lastStreamMode).putExtra("network",network==null?0:network.getNetworkHandle());
                        if(consent!=null)service.putExtra("consent",consent);
                        try{startForegroundService(service);if(selected==3)startActivity(new Intent(this,NavActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP));}catch(Exception e){serviceActive=false;finishRun("Could not start stream test");log(e.getClass().getSimpleName());}
                    });
                }catch(Exception e){log("Cannot start video: "+e.getClass().getSimpleName());testing.set(false);}
            });
        } else io.execute(()-> { try { identify(host,iface,network,port,g); } finally { if(g==generation)testing.set(false); }});
    }
    private InetAddress localFor(String iface,InetAddress peer) throws Exception {
        NetworkInterface n=NetworkInterface.getByName(iface);
        if(n!=null)for(InterfaceAddress a:n.getInterfaceAddresses())if(sameSubnet(a,peer))return a.getAddress();
        throw new IOException("No local IPv4 on the target interface");
    }
    private void identify(InetAddress host,String iface,Network network,int port,int g) {
        List<ServerSocket> listeners=new ArrayList<>();
        boolean accepted=false;
        try {
            if(!running||g!=generation)return;
            InetAddress local=localFor(iface,host);
            listeners=CallbackPorts.open(local,10922,10921,10920);
            sockets.addAll(listeners);
            if(!running||g!=generation)return;
            log("Callback listeners ready on dashboard interface. Sending one PXC identification probe with this app’s own package identity.");
            label("Testing identification handshake…");
            try(Socket s=new Socket()) {
                sockets.add(s); if(network!=null)network.bindSocket(s);
                s.bind(new InetSocketAddress(local,0)); s.connect(new InetSocketAddress(host,port),2500); s.setSoTimeout(3500);
                if(!running||g!=generation)return;
                log("TCP endpoint reachable. This alone does not confirm compatibility.");
                s.getOutputStream().write(Pxc.encode(Pxc.PROBE,"{\"phoneType\":\"Android\",\"packageName\":\"net.drave.atrdiagnostics\"}"));
                Pxc reply=Pxc.read(s.getInputStream());
                if(reply.command!=Pxc.ACK) { log("Unexpected PXC reply command: "+Integer.toHexString(reply.command)+". Protocol exchange incomplete."); return; }
                JSONObject body=new JSONObject(reply.text());
                if(!body.optBoolean("status",false)) { log("PXC identification probe REJECTED. App identity, authorization or protocol differences may be responsible; inconclusive for replacement."); label("Probe rejected • share report"); return; }
                accepted=true;identificationAccepted=true;
                log("PXC identification probe ACCEPTED. Waiting briefly for dashboard CLIENT_INFO."); label("Probe accepted • reading capabilities…");
            }
            long until=System.currentTimeMillis()+10000;
            ServerSocket control=listeners.get(0);
            while(running&&g==generation&&System.currentTimeMillis()<until) {
                try(Socket s=control.accept()) {
                    sockets.add(s);
                    if(!s.getInetAddress().equals(host)) { log("Ignoring callback from a different address."); continue; }
                    s.setSoTimeout(3000);
                    for(int i=0;i<5&&running&&g==generation;i++) {
                        Pxc frame=Pxc.read(s.getInputStream());
                        if(frame.command==Pxc.SELECT) s.getOutputStream().write(Pxc.encode(Pxc.SELECT_ACK,""));
                        else if(frame.command==Pxc.CLIENT_INFO) {
                            JSONObject info=new JSONObject(frame.text());
                            log("Dashboard CLIENT_INFO received. Capability fields:");
                            for(String k:new String[]{"pxcVersion","sdkVersion","screenType","productType","mirrorMode","supportScreenMirroring","supportFunction","supportConnect","enableSockServerAuth","socketTimeoutPeriodWifi","width","height","screenWidth","screenHeight"})
                                if(info.has(k))log("  "+k+"="+info.opt(k));
                            log("Identification complete. Stopped before authentication, media negotiation or video streaming. Replacement projection is still untested.");
                            label("Dashboard identified • share report"); return;
                        } else { log("Callback command 0x"+Integer.toHexString(frame.command)+" (payload omitted)."); break; }
                    }
                } catch(SocketTimeoutException ignored) {}
            }
            log("Probe accepted, but CLIENT_INFO was not received within 10 seconds. Full handshake and projection remain unverified."); label("Partial response • share report");
        } catch(BindException e) {
            log(e.getMessage()+". A previous diagnostic session or another projection app may own it; this does not identify Carbit as the cause."); label("Projection app conflict • share report");
        } catch(Exception e) {
            if(running&&g==generation) { log("Identification incomplete: "+e.getClass().getSimpleName()+" "+e.getMessage()+". This is not proof of incompatibility."); label("Test inconclusive • share report"); }
        } finally {
            for(ServerSocket s:listeners)try{s.close();}catch(IOException ignored){}
            if(accepted)main.post(()->{if(running&&g==generation){pattern.setEnabled(true);mirror.setEnabled(true);log("Identification sockets released. Video tests are ready.");}});
        }
    }

    private void joinAp() {
        log("Optional fallback: requesting the exact QR SSID. This will only work if the dashboard exposes a normal Wi-Fi access point.");
        int g=generation;
        try {
            WifiNetworkSpecifier spec=new WifiNetworkSpecifier.Builder().setSsid(SSID).setWpa2Passphrase(password).build();
            NetworkRequest request=new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(spec).build();
            apCallback=new ConnectivityManager.NetworkCallback() {
                public void onAvailable(Network n) {
                    if(!running||g!=generation)return;
                    log("Android supplied the QR-requested Wi-Fi network.");
                    LinkProperties lp=cm.getLinkProperties(n); if(lp!=null) apTarget(n,lp,g);
                }
                public void onLinkPropertiesChanged(Network n,LinkProperties lp) { if(running&&g==generation)apTarget(n,lp,g); }
                public void onUnavailable() { if(running&&g==generation)log("QR Wi-Fi request unavailable or declined. The dashboard may require Wi-Fi Direct instead."); }
            };
            cm.requestNetwork(request,apCallback,25000);
        } catch(Exception e) { log("QR Wi-Fi request: "+e.getClass().getSimpleName()+" "+e.getMessage()); }
    }
    private void apTarget(Network n,LinkProperties lp,int g) {
        if(!running||g!=generation)return;
        trustedInterface=lp.getInterfaceName();
        for(RouteInfo r:lp.getRoutes()) if(r.getGateway() instanceof Inet4Address&&!r.getGateway().isAnyLocalAddress())
            target(r.getGateway(),lp.getInterfaceName(),n,"Android QR-specific network request");
    }
    private void finishRun(String message) { stopService(new Intent(this,StreamService.class));serviceActive=false; if(running)log(message+". Report remains available."); cleanup(); label(message); }
    private void cleanup() {
        running=false; generation++;
        if(ownDiscovery&&p2p!=null&&channel!=null) {
            try{p2p.stopPeerDiscovery(channel,null);}catch(Exception ignored){}
            ownDiscovery=false;
        }
        if(connecting&&p2p!=null&&channel!=null) {
            try{p2p.cancelConnect(channel,null);}catch(Exception ignored){}
            connecting=false;
        }
        if(deadline!=null)main.removeCallbacks(deadline);
        synchronized(sockets) { for(Closeable s:sockets)try{s.close();}catch(IOException ignored){} sockets.clear(); }
        testing.set(false);
        if(discovery!=null) { try{nsd.stopServiceDiscovery(discovery);}catch(Exception ignored){} discovery=null; }
        services.clear(); resolving=false;
        if(multicast!=null&&multicast.isHeld())multicast.release(); multicast=null;
        if(apCallback!=null) { try{cm.unregisterNetworkCallback(apCallback);}catch(Exception ignored){} apCallback=null; }
        // Do not remove a group: it may belong to the user's existing Carbit session.
    }
    private void requestStream(int kind,Intent consent) {
        if(!identificationAccepted){log("Run Test connection first. Video tests unlock after an accepted identification probe.");return;}
        if(serviceActive){log("Stop the current stream test first.");return;}
        pendingStream=kind;captureConsent=consent;
        log(kind==1?"Starting a short animated color-bar test after reconnecting.":"Screen sharing authorized. Reconnecting for a 90-second mirror test; open your navigation app once frames are sent.");
        permissions(true);
    }
    private void confirmVideo() {
        if(!sawFrames)return;
        String question="pattern".equals(lastStreamMode)?"On the MOTORCYCLE screen, did you see four colored bars and a moving white square?":"On the MOTORCYCLE screen, did the phone or navigation app visibly update when you interacted with it?";
        String[] choices="pattern".equals(lastStreamMode)?new String[]{"Yes, moving pattern visible","No / blank / frozen"}:new String[]{"Navigation app visibly updating","Phone screen visibly updating","No / blank / frozen"};
        new AlertDialog.Builder(this).setTitle(question).setItems(choices,(dialog,which)->{
            log("USER-REPORTED VISUAL RESULT ("+lastStreamMode+"): "+choices[which]+". This is human confirmation, not an automatic rendering measurement.");
            visualEvidence.put(lastStreamMode,choices[which]);
            label(which==choices.length-1?"Visible output not confirmed":"User confirmed: "+choices[which]);
        }).show();

    }
    private void shareReport() {
        try {
            JSONObject j=new JSONObject(); j.put("app","Daanvia 0.4.0-alpha1"); j.put("target","Scanned dashboard / model="+model+" / action="+qrAction);
            j.put("note","Network, handshake and video transport evidence are separate from user-confirmed visible output. Credentials and raw device IDs excluded.");
            j.put("identification_probe_accepted",identificationAccepted);
            j.put("frames_sent_by_test",new JSONObject(frameEvidence));
            j.put("user_reported_visible_output",new JSONObject(visualEvidence));
            synchronized(report) { j.put("log",report.toString()); }
            File out=new File(getCacheDir(),"atr-report.json");
            try(FileOutputStream f=new FileOutputStream(out)) { f.write(j.toString(2).getBytes(StandardCharsets.UTF_8)); }
            Uri uri=Uri.parse("content://net.drave.atrdiagnostics.report/atr-report.json");
            Intent send=new Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            send.setClipData(ClipData.newRawUri("ATR diagnostic report",uri)); startActivity(Intent.createChooser(send,"Send report back to ChatGPT"));
        } catch(Exception e) { log("Could not share report: "+e.getMessage()); }
    }
    protected void onDestroy() {
        if(!StreamService.navigationActive&&!"navigation".equals(lastStreamMode))stopService(new Intent(this,StreamService.class));cleanup();try{unregisterReceiver(streamReceiver);}catch(Exception ignored){} try{unregisterReceiver(receiver);}catch(Exception ignored){}
        if(channel!=null)channel.close(); io.shutdownNow(); super.onDestroy();
    }
}
