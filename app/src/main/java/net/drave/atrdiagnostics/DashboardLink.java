package net.drave.atrdiagnostics;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.location.LocationManager;
import android.net.*;
import android.net.nsd.*;
import android.net.wifi.*;
import android.net.wifi.p2p.*;
import android.os.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Dashboard discovery, pairing, identification and stream start. One instance serves the whole app: screens
 * {@link #attach} to show its status and {@link #detach} when they close, so switching apps or screens never
 * tears down the Wi-Fi Direct channel or the dashboard Wi-Fi while the bike display is live. If the display
 * drops without the rider stopping it, the link reconnects on its own.
 */
public final class DashboardLink {
    public interface Host {
        void onStatus(String status);
        void onLog();
        void onChanged();
        void requestPermissions(String[] permissions, int requestCode);
        void onPermissionsMissing();
        void onNavigationStarted();
    }

    public static final int PERMISSIONS = 10;
    public static final int MODE_NAVIGATION = 3, MODE_PATTERN = 1, MODE_MIRROR = 2;

    private final Context context;
    private final List<Host> hosts = new ArrayList<>();
    /** Forwards to the attached screens; permission prompts and "display started" go to the screen on top. */
    private final Host ui = new Host() {
        private List<Host> all() { synchronized (hosts) { return new ArrayList<>(hosts); } }
        private Host top() { synchronized (hosts) { return hosts.isEmpty() ? null : hosts.get(0); } }
        public void onStatus(String status) { for (Host h : all()) h.onStatus(status); }
        public void onLog() { for (Host h : all()) h.onLog(); }
        public void onChanged() { for (Host h : all()) h.onChanged(); }
        public void requestPermissions(String[] permissions, int requestCode) { Host h = top(); if (h != null) h.requestPermissions(permissions, requestCode); else log("Open Daanvia to allow Nearby devices and location."); }
        public void onPermissionsMissing() { Host h = top(); if (h != null) h.onPermissionsMissing(); }
        public void onNavigationStarted() { Host h = top(); if (h != null) h.onNavigationStarted(); }
    };
    private static DashboardLink shared;
    /** The app's one link, created on first use; the screen becomes the top host. */
    public static synchronized DashboardLink attach(Context screen, Host host) {
        if (shared == null) shared = new DashboardLink(screen.getApplicationContext());
        synchronized (shared.hosts) { shared.hosts.remove(host); shared.hosts.add(0, host); }
        return shared;
    }
    /** A screen is closing: stop sending it updates. The connection itself stays. */
    public void detach(Host host) { synchronized (hosts) { hosts.remove(host); } }
    /** The app is ending the stream itself (Stop, a QR rescan), so its end is not a drop. Cleared when the next stream starts. */
    private boolean expectStop;
    /** The rider stopped the display: nothing connects on its own until they connect again or start a ride. */
    private boolean stoppedByUser;
    /** Reconnecting after a drop keeps going until this time (elapsedRealtime); streams that fail within seconds count as quick drops. */
    private long reconnectUntil, streamStartedAt; private int quickDrops;
    /** Try again after an unexpected drop, while auto-connect is on: a new search whenever the last one has ended, for five minutes. */
    private final Runnable reconnect = this::reconnectNow;
    private void reconnectNow() {
        if (StreamService.navigationActive || stoppedByUser || !profileReady || !hasPermissions()) return;
        if (!context.getSharedPreferences("ui", 0).getBoolean("autoconnect", true) || SystemClock.elapsedRealtime() > reconnectUntil) return;
        if (!running) { log("Reconnecting to the bike display."); autoNavigation(); }
        main.postDelayed(reconnect, 8000);
    }
    /** A new ride lets auto-connect run again after the rider stopped the display. */
    public void allowAuto() { stoppedByUser = false; }
    private String SSID = "", MAC = "", password = "", model = "", qrAction = "";
    private boolean profileReady;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(4);
    private final StringBuilder report = new StringBuilder();
    private final Set<String> tested = Collections.synchronizedSet(new HashSet<>());
    private final List<Closeable> sockets = Collections.synchronizedList(new ArrayList<>());
    private final AtomicBoolean testing = new AtomicBoolean(false);
    private final ConnectivityManager cm;
    private final WifiManager wifi;
    private final WifiP2pManager p2p;
    private WifiP2pManager.Channel channel;
    private final NsdManager nsd;
    private NsdManager.DiscoveryListener discovery;
    private ConnectivityManager.NetworkCallback apCallback;
    private WifiManager.MulticastLock multicast;
    private boolean identificationAccepted, serviceActive, sawFrames;
    private int pendingStream;
    private String lastStreamMode = "";
    private Intent captureConsent;
    private final Map<String,String> visualEvidence = new LinkedHashMap<>();
    private final Map<String,Long> frameEvidence = new LinkedHashMap<>();
    private volatile boolean running, active, connecting;
    private boolean ownDiscovery;
    private volatile int generation;
    private Runnable deadline;
    private String verdict = "Scan a QR code to begin";
    private String trustedInterface;
    private String trustedOwner;
    private final List<NsdServiceInfo> services = new ArrayList<>();
    private boolean resolving;
    private boolean requestedActive;
    private boolean destroyed;

    private final BroadcastReceiver streamReceiver = new BroadcastReceiver() {
        public void onReceive(Context c, Intent event) {
            String kind = event.getStringExtra("kind"), message = event.getStringExtra("message");
            if (message != null) log(message);
            if ("frames".equals(kind)) {
                sawFrames = true; ui.onChanged();
                String mode = event.getStringExtra("mode"); frameEvidence.put(mode, event.getLongExtra("frames", 0));
                if (!visualEvidence.containsKey(mode)) label("Video sent • check the motorcycle screen");
            } else if ("negotiated".equals(kind)) label("Video format negotiated • sending…");
            else if ("stopped".equals(kind)) {
                frameEvidence.put(event.getStringExtra("mode"), event.getLongExtra("frames", 0));
                boolean user = event.getBooleanExtra("user", false); if (user) stoppedByUser = true;
                boolean dropped = "navigation".equals(event.getStringExtra("mode")) && !expectStop && !stoppedByUser;
                expectStop = false;
                serviceActive = false; cleanup();
                if (dropped) dropped();
                else label(visualEvidence.containsKey(lastStreamMode) ? "User reported: " + visualEvidence.get(lastStreamMode) : sawFrames ? "Test ended • confirm what appeared" : "No video confirmed • share report");
                ui.onChanged();
            }
        }
    };
    /** The display stream ended on its own. Reconnect for five minutes, unless it keeps failing within seconds of starting. */
    private void dropped() {
        long now = SystemClock.elapsedRealtime(); boolean quick = now - streamStartedAt < 20000;
        quickDrops = quick ? quickDrops + 1 : 0;
        NavSession s = NavSession.get(context);
        if (quickDrops >= 4) {
            reconnectUntil = 0; label("Bike display keeps dropping • tap Connect to try again");
            if (s.active) s.message("Bike display keeps dropping · open Daanvia to connect again");
            return;
        }
        if (!quick || reconnectUntil < now) reconnectUntil = now + 5 * 60_000L;
        label("Bike display dropped • reconnecting…"); if (s.active) s.message("Bike display dropped · reconnecting");
        main.removeCallbacks(reconnect); main.postDelayed(reconnect, quick ? 8000 : 3000);
    }
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        public void onReceive(Context c, Intent i) {
            if (!running) return;
            if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(i.getAction())) peers();
            if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(i.getAction())) group();
        }
    };

    private DashboardLink(Context context) {
        this.context = context;
        cm = (ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        wifi = (WifiManager)context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        p2p = (WifiP2pManager)context.getSystemService(Context.WIFI_P2P_SERVICE);
        nsd = (NsdManager)context.getSystemService(Context.NSD_SERVICE);
        if (p2p != null) channel = p2p.initialize(context, context.getMainLooper(), () -> { log("Wi-Fi Direct channel closed."); channel = p2p.initialize(context, context.getMainLooper(), null); });
        IntentFilter f = new IntentFilter();
        f.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        f.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED);
        else context.registerReceiver(receiver, f);
        log("Daanvia " + BuildInfo.VERSION + " • Android " + Build.VERSION.RELEASE + " / " + Build.MANUFACTURER + " " + Build.MODEL);
        log("Scan your dashboard QR or choose its photo. QR text is decoded locally and its URL is never opened.");
        androidx.core.content.ContextCompat.registerReceiver(context, streamReceiver, new IntentFilter(StreamService.EVENT), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
        log("Tests are local. No firmware or engine access. Screen mirroring requires Android’s sharing consent and stops automatically after 90 seconds.");
        String saved = context.getSharedPreferences("saved-bike", 0).getString("qr", "");
        if (!saved.isEmpty()) acceptQr(saved);
    }

    // ---- state the screens read ----
    public boolean profileReady() { return profileReady; }
    public boolean identificationAccepted() { return identificationAccepted; }
    public boolean sawFrames() { return sawFrames; }
    public boolean serviceActive() { return serviceActive; }
    public boolean searching() { return running; }
    public String lastStreamMode() { return lastStreamMode; }
    public String model() { return model; }
    public String status() { return verdict; }
    public String reportText() { synchronized(report) { return report.toString(); } }

    // ---- actions ----
    /** Same as the old "Send Daanvia to dashboard" button. */
    public void sendNavigation() {
        if (!profileReady) { log("Scan your dashboard QR first"); return; }
        stoppedByUser = false; quickDrops = 0; quiet = false; pendingStream = MODE_NAVIGATION; permissions(true);
    }
    /**
     * A background attempt (auto-connect, reconnect): Wi-Fi Direct only, never Android's "connect to device" Wi-Fi sheet,
     * which would otherwise pop up every time the bike is off. Does nothing while Wi-Fi is off.
     */
    public void autoNavigation() {
        if (!profileReady || stoppedByUser || wifi == null || !wifi.isWifiEnabled() || !hasPermissions()) return;
        quiet = true; pendingStream = MODE_NAVIGATION; start(true);
    }
    private boolean quiet;
    /** Ask for Nearby devices once for auto-connect; once allowed it searches the quiet way. */
    public void askPermissions() { if (!profileReady) return; quiet = true; pendingStream = MODE_NAVIGATION; permissions(true); }
    /** Same as the old "Test connection" button. */
    public void testConnection() {
        if (!profileReady) { log("Scan your dashboard QR first."); return; }
        stoppedByUser = false; quiet = false; pendingStream = 0; permissions(true);
    }
    /** The rider stops the search or the display. */
    public void stop() {
        stoppedByUser = true; expectStop = true; main.removeCallbacks(reconnect);
        context.stopService(new Intent(context, StreamService.class)); serviceActive = false; finishRun("Stopped");
    }
    /** Stop looking for the dashboard without touching a stream that is already running. */
    public void pauseSearch() { if (running && !serviceActive) { cleanup(); label(profileReady ? "Not connected" : verdict); ui.onChanged(); } }
    /** Release discovery once the display is live, as the old setup screen did when it closed after connecting. */
    public void settle() { if (running) cleanup(); }
    /** Called before the QR camera or photo picker opens. */
    public void resetForScan() { expectStop = true; main.removeCallbacks(reconnect); context.stopService(new Intent(context, StreamService.class)); cleanup(); }

    private void log(String s) {
        String line = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + "  " + clean(s) + "\n";
        synchronized(report) { if (report.length() < 150000) report.append(line); }
        main.post(() -> { if (!destroyed) ui.onLog(); });
    }
    private String clean(String s) {
        return s.replaceAll("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}", "[device address redacted]")
            .replaceAll("(?i)(pwd|password|passphrase|token|huid|uuid|serial|sn)[\\\"'\\s:=]+[^,}\\s]+", "$1=[redacted]");
    }
    private void label(String s) { verdict = s; main.post(() -> { if (!destroyed) ui.onStatus(s); }); }
    public void note(String s) { log(s); }
    public void setStatus(String s) { label(s); }

    public boolean hasPermissions() {
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED);
    }

    public void decodePhoto(Uri image) {
        label("Reading QR photo…");
        io.execute(() -> {
            try {
                BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
                try (InputStream in = context.getContentResolver().openInputStream(image)) { BitmapFactory.decodeStream(in, null, bounds); }
                BitmapFactory.Options opts = new BitmapFactory.Options();
                int max = Math.max(bounds.outWidth, bounds.outHeight);
                opts.inSampleSize = 1;
                while (max / opts.inSampleSize > 2400) opts.inSampleSize *= 2;
                Bitmap bmp;
                try (InputStream in = context.getContentResolver().openInputStream(image)) { bmp = BitmapFactory.decodeStream(in, null, opts); }
                if (bmp == null) throw new IOException("Image could not be read");
                String value;
                try { value = QrDecoder.decodeBitmap(bmp); } finally { bmp.recycle(); }
                main.post(() -> acceptQr(value));
            } catch (Exception e) { log("Could not decode this photo. Try live scanning or a closer, glare-free photo."); label("QR not decoded • try another photo"); }
        });
    }

    public void acceptQr(String raw) {
        profileReady = false; identificationAccepted = false; visualEvidence.clear(); frameEvidence.clear(); sawFrames = false;
        try {
            QrProfile profile = QrProfile.parse(raw);
            SSID = profile.ssid; MAC = profile.mac; password = profile.password; model = profile.model; qrAction = profile.action;
            android.content.SharedPreferences bike = context.getSharedPreferences("saved-bike", 0);
            // A different bike hasn't approved its Wi-Fi network yet.
            if (!raw.equals(bike.getString("qr", ""))) bike.edit().putString("qr", raw).remove("ap_ok").apply(); NavSession.get(context).loadFit();
            profileReady = true;
            log("QR decoded. Dashboard network: " + SSID + "; security: " + profile.security + "; model: " + model + "; connection flag: " + qrAction + ". Password is hidden.");
            label("QR decoded • tap Test connection");
        } catch (Exception e) { log("This QR does not contain supported dashboard pairing details. Scan the connection QR, not just an app-download code."); label("Pairing details missing"); }
        ui.onChanged();
    }

    private void permissions(boolean mode) {
        requestedActive = mode;
        if (!hasPermissions()) {
            ArrayList<String> p = new ArrayList<>();
            p.add(Manifest.permission.ACCESS_COARSE_LOCATION); p.add(Manifest.permission.ACCESS_FINE_LOCATION);
            if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            ui.requestPermissions(p.toArray(new String[0]), PERMISSIONS);
        } else start(mode);
    }
    /** Forward the screen's onRequestPermissionsResult for {@link #PERMISSIONS}. */
    public void onPermissionsResult() {
        if (hasPermissions()) start(requestedActive);
        else {
            log("Permissions missing. Allow Nearby devices and Precise location in app settings, then retry. No GPS positions are collected.");
            ui.onPermissionsMissing();
        }
    }

    private void start(boolean mode) {
        // A search or test now would cut the live display off when it times out.
        if (serviceActive) { log("The bike display is already connected. Stop it first to run another test."); ui.onChanged(); return; }
        cleanup(); running = true; active = mode; connecting = false; tested.clear();
        if (pendingStream == 0) identificationAccepted = false;
        ui.onChanged();
        trustedInterface = null; trustedOwner = null;
        log("\n--- " + (mode ? "ACTIVE IDENTIFICATION" : "WORKING CONNECTION INSPECTION") + " ---");
        log(mode ? "Only the QR-matched dashboard will receive the bounded identification exchange." : "Inspection only: no projection probe or new pairing request.");
        if (wifi == null || !wifi.isWifiEnabled()) { log("Wi-Fi is off. Enable Wi-Fi and rerun."); finishRun("Wi-Fi is off"); return; }
        LocationManager lm = (LocationManager)context.getSystemService(Context.LOCATION_SERVICE);
        if (lm != null && !lm.isLocationEnabled()) log("Location switch is OFF. Android may block peer discovery; enable it and retry if nothing appears.");
        label(mode ? "Looking for your dashboard…" : "Inspecting connection…");
        snapshot(); startNsd(); group();
        if (mode && p2p != null && channel != null) {
            ownDiscovery = true;
            try { p2p.discoverPeers(channel, action("Wi-Fi Direct discovery", null)); } catch (SecurityException e) { finishRun("Nearby device permission changed · retry setup"); return; }
            peers();
        }
        int g = generation;
        // If P2P cannot establish the target, try the QR's AP credentials once. Background attempts only do this for a bike whose
        // network the rider already approved, which Android then joins without asking again.
        boolean apApproved = context.getSharedPreferences("saved-bike", 0).getBoolean("ap_ok", false);
        main.postDelayed(() -> {
            if (running && g == generation && (!quiet || apApproved) && tested.isEmpty() && trustedInterface == null && !password.isEmpty()) joinAp();
        }, 18000);
        deadline = () -> {
            if (running && g == generation) {
                if (tested.isEmpty()) log("Could not establish a verified dashboard endpoint. This is inconclusive. Check the bike’s QR page is open and Carbit is fully closed, then retry.");
                finishRun(tested.isEmpty() ? "No connection confirmed • share report" : verdict);
            }
        };
        main.postDelayed(deadline, mode ? 45000 : 12000);
    }

    private boolean matches(String value) {
        if (value == null) return false;
        String v = value.toLowerCase(Locale.US).replace("\"", "");
        return !v.isEmpty() && ((!SSID.isEmpty() && (v.equals(SSID.toLowerCase(Locale.US)) || v.endsWith("-" + SSID.toLowerCase(Locale.US)))) || (!MAC.isEmpty() && v.equals(MAC.toLowerCase(Locale.US))));
    }
    private WifiP2pManager.ActionListener action(String name, Runnable success) {
        int g = generation;
        return new WifiP2pManager.ActionListener() {
            public void onSuccess() { if (running && g == generation) { log(name + " accepted by Android."); if (success != null) success.run(); } }
            public void onFailure(int reason) { if (running && g == generation) { connecting = false; log(name + " failed: " + (reason == 2 ? "BUSY" : reason == 1 ? "UNSUPPORTED" : "ERROR " + reason)); } }
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
                log("Wi-Fi interface=" + lp.getInterfaceName() + ", network=" + n + ", SSID=" + (matches(ssid) ? SSID : "[other network or hidden]") + ", Internet=" + cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
                for (LinkAddress a : lp.getLinkAddresses()) if (a.getAddress() instanceof Inet4Address) log("  Phone IPv4=" + a);
                if (matches(ssid)) {
                    trustedInterface = lp.getInterfaceName();
                    for (RouteInfo r : lp.getRoutes()) {
                        InetAddress a = r.getGateway();
                        if (a instanceof Inet4Address && !a.isAnyLocalAddress()) target(a, trustedInterface, n, "QR-matched Wi-Fi gateway");
                    }
                }
            }
            Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
            while (all != null && all.hasMoreElements()) {
                NetworkInterface f = all.nextElement();
                if (!f.isUp() || f.isLoopback()) continue;
                for (InterfaceAddress a : f.getInterfaceAddresses()) if (a.getAddress() instanceof Inet4Address)
                    log("Interface " + f.getName() + ": " + a.getAddress().getHostAddress() + "/" + a.getNetworkPrefixLength());
            }
        } catch (Exception e) { log("Network snapshot: " + e.getClass().getSimpleName() + " " + e.getMessage()); }
    }
    private void group() {
        if (!running || p2p == null || channel == null) return;
        int g = generation;
        try {
            p2p.requestGroupInfo(channel, grp -> {
                if (!running || g != generation) return;
                if (grp == null) { log("No Wi-Fi Direct group visible to this app."); return; }
                WifiP2pDevice owner = grp.getOwner();
                boolean match = matches(grp.getNetworkName()) || (owner != null && (matches(owner.deviceName) || matches(owner.deviceAddress)));
                if (!match) for (WifiP2pDevice d : grp.getClientList()) if (matches(d.deviceName) || matches(d.deviceAddress)) match = true;
                log("Wi-Fi Direct group found; interface=" + grp.getInterface() + ", phoneIsOwner=" + grp.isGroupOwner() + ", matchesQR=" + match);
                if (!match) { log("Group identity unconfirmed; no probe sent to it."); return; }
                connecting = false;
                trustedInterface = grp.getInterface();
                p2p.requestConnectionInfo(channel, info -> {
                    if (!running || g != generation || info == null || !info.groupFormed) return;
                    if (!info.isGroupOwner && info.groupOwnerAddress != null) {
                        trustedOwner = info.groupOwnerAddress.getHostAddress();
                        target(info.groupOwnerAddress, grp.getInterface(), null, "QR-matched Wi-Fi Direct group owner");
                    } else log("Phone owns the matched group. Waiting for dashboard service discovery; not guessing client addresses.");
                });
            });
        } catch (SecurityException e) { log("Android blocked Wi-Fi Direct group access. Check permissions."); }
    }
    private void peers() {
        if (!running || !active || p2p == null || channel == null) return;
        int g = generation;
        try { p2p.requestPeers(channel, list -> {
            if (!running || g != generation) return;
            log("Wi-Fi Direct peers visible: " + list.getDeviceList().size());
            if (connecting || trustedInterface != null) return;
            for (WifiP2pDevice d : list.getDeviceList()) if (matches(d.deviceName) || matches(d.deviceAddress)) {
                connecting = true;
                log("QR-matched dashboard peer found. Requesting connection; accept Android’s prompt if shown.");
                WifiP2pConfig cfg = new WifiP2pConfig(); cfg.deviceAddress = d.deviceAddress;
                cfg.wps.setup = WpsInfo.PBC; cfg.groupOwnerIntent = 0;
                p2p.connect(channel, cfg, action("Dashboard pairing", () -> main.postDelayed(this::group, 2000)));
                return;
            }
        }); } catch (SecurityException e) { log("Peer scan blocked by Android permissions."); }
    }

    private void startNsd() {
        if (nsd == null) return;
        try {
            multicast = wifi.createMulticastLock("atr-discovery"); multicast.setReferenceCounted(false); multicast.acquire();
            int g = generation;
            discovery = new NsdManager.DiscoveryListener() {
                public void onDiscoveryStarted(String t) { if (running && g == generation) log("Listening for EasyConn service advertisements."); }
                public void onDiscoveryStopped(String t) {}
                public void onStartDiscoveryFailed(String t, int e) { if (running && g == generation) log("Service discovery unavailable: " + e); }
                public void onStopDiscoveryFailed(String t, int e) {}
                public void onServiceLost(NsdServiceInfo s) {}
                public void onServiceFound(NsdServiceInfo s) {
                    if (!running || g != generation) return;
                    if (services.size() < 12) { services.add(s); resolveNext(g); }
                }
            };
            nsd.discoverServices("_EasyConn._tcp.", NsdManager.PROTOCOL_DNS_SD, discovery);
        } catch (Exception e) { log("Service discovery: " + e.getClass().getSimpleName()); }
    }
    private void resolveNext(int g) {
        if (resolving || services.isEmpty() || !running || g != generation) return;
        resolving = true; NsdServiceInfo s = services.remove(0);
        nsd.resolveService(s, new NsdManager.ResolveListener() {
            public void onResolveFailed(NsdServiceInfo x, int e) { if (g != generation) return; resolving = false; log("Service resolution failed: " + e); resolveNext(g); }
            public void onServiceResolved(NsdServiceInfo x) {
                if (!running || g != generation) return;
                resolving = false;
                InetAddress host = x.getHost();
                log("EasyConn service resolved at " + (host == null ? "?" : host.getHostAddress()) + ":" + x.getPort());
                String name = x.getServiceName();
                Map<String,byte[]> attrs = x.getAttributes();
                byte[] hu = attrs.get("huname");
                boolean named = matches(name) || (hu != null && matches(new String(hu, StandardCharsets.UTF_8)));
                // A service is only probed after matching the QR or the verified P2P owner.
                String iface = host == null ? null : interfaceFor(host);
                boolean onTrustedLink = iface != null && iface.equals(trustedInterface);
                if (host != null && (named || onTrustedLink || (trustedOwner != null && trustedOwner.equals(host.getHostAddress())))) {
                    if (iface != null) target(host, iface, null, "matched EasyConn service", x.getPort());
                } else log("Service identity does not match the target yet; discovery recorded, no probe sent.");
                resolveNext(g);
            }
        });
    }
    private String interfaceFor(InetAddress host) {
        try {
            Enumeration<NetworkInterface> n = NetworkInterface.getNetworkInterfaces();
            while (n != null && n.hasMoreElements()) {
                NetworkInterface f = n.nextElement();
                for (InterfaceAddress a : f.getInterfaceAddresses()) if (sameSubnet(a, host)) return f.getName();
            }
        } catch (Exception ignored) {}
        return null;
    }
    private boolean sameSubnet(InterfaceAddress local, InetAddress peer) {
        byte[] a = local.getAddress().getAddress(), b = peer.getAddress();
        int bits = local.getNetworkPrefixLength();
        if (a.length != 4 || b.length != 4 || bits < 8 || bits > 32) return false;
        for (int i = 0; i < 4; i++) { int mask = bits >= 8 ? 255 : bits <= 0 ? 0 : 255 << (8 - bits) & 255; if ((a[i] & mask) != (b[i] & mask)) return false; bits -= 8; }
        return !peer.equals(local.getAddress());
    }
    private void target(InetAddress host, String iface, Network network, String evidence) { target(host, iface, network, evidence, 10930); }
    private void target(InetAddress host, String iface, Network network, String evidence, int port) {
        if (serviceActive) return;
        if (!running || host == null || port < 1 || port > 65535 || !(host instanceof Inet4Address) || host.isLoopbackAddress() || host.isAnyLocalAddress() || host.isMulticastAddress()) return;
        label("Dashboard network found • checking protocol…");
        log("Target evidence: " + evidence + "; " + host.getHostAddress() + ":" + port + " via " + iface);
        if (!active) { label("Connection identified • share report"); log("Inspection captured the target. Close Carbit and use Connect & identify for the protocol test."); return; }
        if (!testing.compareAndSet(false, true)) return;
        String key = host.getHostAddress() + ":" + port;
        if (!tested.add(key)) { testing.set(false); return; }
        int g = generation;
        if (pendingStream > 0) {
            int selected = pendingStream; Intent consent = captureConsent;
            io.execute(() -> {
                try {
                    String local = localFor(iface, host).getHostAddress();
                    main.post(() -> {
                        if (!running || g != generation) return;
                        serviceActive = true; sawFrames = false;
                        lastStreamMode = selected == MODE_NAVIGATION ? "navigation" : selected == MODE_PATTERN ? "pattern" : "mirror"; visualEvidence.remove(lastStreamMode); frameEvidence.remove(lastStreamMode); pendingStream = 0; captureConsent = null;
                        ui.onChanged();
                        if (deadline != null) main.removeCallbacks(deadline);
                        Intent service = new Intent(context, StreamService.class).putExtra("host", host.getHostAddress()).putExtra("local", local).putExtra("port", port)
                            .putExtra("mode", lastStreamMode).putExtra("network", network == null ? 0 : network.getNetworkHandle());
                        if (consent != null) service.putExtra("consent", consent);
                        expectStop = false; streamStartedAt = SystemClock.elapsedRealtime();
                        try { context.startForegroundService(service); if (selected == MODE_NAVIGATION) { main.removeCallbacks(reconnect); settle(); ui.onNavigationStarted(); } }
                        catch (Exception e) {
                            // Android 12+ refuses to start it from the background; the next search after Daanvia is opened connects.
                            serviceActive = false; log(e.getClass().getSimpleName());
                            finishRun(Build.VERSION.SDK_INT >= 31 && e instanceof android.app.ForegroundServiceStartNotAllowedException ? "Open Daanvia to reconnect the bike display" : "Could not start stream test");
                        }
                    });
                } catch (Exception e) { log("Cannot start video: " + e.getClass().getSimpleName()); testing.set(false); }
            });
        } else io.execute(() -> { try { identify(host, iface, network, port, g); } finally { if (g == generation) testing.set(false); } });
    }
    private InetAddress localFor(String iface, InetAddress peer) throws Exception {
        NetworkInterface n = NetworkInterface.getByName(iface);
        if (n != null) for (InterfaceAddress a : n.getInterfaceAddresses()) if (sameSubnet(a, peer)) return a.getAddress();
        throw new IOException("No local IPv4 on the target interface");
    }
    private void identify(InetAddress host, String iface, Network network, int port, int g) {
        List<ServerSocket> listeners = new ArrayList<>();
        boolean accepted = false;
        try {
            if (!running || g != generation) return;
            InetAddress local = localFor(iface, host);
            listeners = CallbackPorts.open(local, 10922, 10921, 10920);
            sockets.addAll(listeners);
            if (!running || g != generation) return;
            log("Callback listeners ready on dashboard interface. Sending one PXC identification probe with this app’s own package identity.");
            label("Testing identification handshake…");
            try (Socket s = new Socket()) {
                sockets.add(s); if (network != null) network.bindSocket(s);
                s.bind(new InetSocketAddress(local, 0)); s.connect(new InetSocketAddress(host, port), 2500); s.setSoTimeout(3500);
                if (!running || g != generation) return;
                log("TCP endpoint reachable. This alone does not confirm compatibility.");
                s.getOutputStream().write(Pxc.encode(Pxc.PROBE, "{\"phoneType\":\"Android\",\"packageName\":\"net.drave.atrdiagnostics\"}"));
                Pxc reply = Pxc.read(s.getInputStream());
                if (reply.command != Pxc.ACK) { log("Unexpected PXC reply command: " + Integer.toHexString(reply.command) + ". Protocol exchange incomplete."); return; }
                JSONObject body = new JSONObject(reply.text());
                if (!body.optBoolean("status", false)) { log("PXC identification probe REJECTED. App identity, authorization or protocol differences may be responsible; inconclusive for replacement."); label("Probe rejected • share report"); return; }
                accepted = true; identificationAccepted = true;
                log("PXC identification probe ACCEPTED. Waiting briefly for dashboard CLIENT_INFO."); label("Probe accepted • reading capabilities…");
            }
            long until = System.currentTimeMillis() + 10000;
            ServerSocket control = listeners.get(0);
            while (running && g == generation && System.currentTimeMillis() < until) {
                try (Socket s = control.accept()) {
                    sockets.add(s);
                    if (!s.getInetAddress().equals(host)) { log("Ignoring callback from a different address."); continue; }
                    s.setSoTimeout(3000);
                    for (int i = 0; i < 5 && running && g == generation; i++) {
                        Pxc frame = Pxc.read(s.getInputStream());
                        if (frame.command == Pxc.SELECT) s.getOutputStream().write(Pxc.encode(Pxc.SELECT_ACK, ""));
                        else if (frame.command == Pxc.CLIENT_INFO) {
                            JSONObject info = new JSONObject(frame.text());
                            log("Dashboard CLIENT_INFO received. Capability fields:");
                            for (String k : new String[]{"pxcVersion","sdkVersion","screenType","productType","mirrorMode","supportScreenMirroring","supportFunction","supportConnect","enableSockServerAuth","socketTimeoutPeriodWifi","width","height","screenWidth","screenHeight"})
                                if (info.has(k)) log("  " + k + "=" + info.opt(k));
                            log("Identification complete. Stopped before authentication, media negotiation or video streaming. Replacement projection is still untested.");
                            label("Dashboard identified • share report"); return;
                        } else { log("Callback command 0x" + Integer.toHexString(frame.command) + " (payload omitted)."); break; }
                    }
                } catch (SocketTimeoutException ignored) {}
            }
            log("Probe accepted, but CLIENT_INFO was not received within 10 seconds. Full handshake and projection remain unverified."); label("Partial response • share report");
        } catch (BindException e) {
            log(e.getMessage() + ". A previous diagnostic session or another projection app may own it; this does not identify Carbit as the cause."); label("Projection app conflict • share report");
        } catch (Exception e) {
            if (running && g == generation) { log("Identification incomplete: " + e.getClass().getSimpleName() + " " + e.getMessage() + ". This is not proof of incompatibility."); label("Test inconclusive • share report"); }
        } finally {
            for (ServerSocket s : listeners) try { s.close(); } catch (IOException ignored) {}
            if (accepted) main.post(() -> { if (running && g == generation) { ui.onChanged(); log("Identification sockets released. Video tests are ready."); } });
        }
    }

    private void joinAp() {
        log("Optional fallback: requesting the exact QR SSID. This will only work if the dashboard exposes a normal Wi-Fi access point.");
        int g = generation;
        if (apCallback != null) { try { cm.unregisterNetworkCallback(apCallback); } catch (Exception ignored) {} apCallback = null; }
        try {
            WifiNetworkSpecifier spec = new WifiNetworkSpecifier.Builder().setSsid(SSID).setWpa2Passphrase(password).build();
            NetworkRequest request = new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(spec).build();
            apCallback = new ConnectivityManager.NetworkCallback() {
                public void onAvailable(Network n) {
                    if (!running || g != generation) return;
                    log("Android supplied the QR-requested Wi-Fi network."); context.getSharedPreferences("saved-bike", 0).edit().putBoolean("ap_ok", true).apply();
                    LinkProperties lp = cm.getLinkProperties(n); if (lp != null) apTarget(n, lp, g);
                }
                public void onLinkPropertiesChanged(Network n, LinkProperties lp) { if (running && g == generation) apTarget(n, lp, g); }
                public void onUnavailable() { if (running && g == generation) log("QR Wi-Fi request unavailable or declined. The dashboard may require Wi-Fi Direct instead."); }
            };
            cm.requestNetwork(request, apCallback, 25000);
        } catch (Exception e) { log("QR Wi-Fi request: " + e.getClass().getSimpleName() + " " + e.getMessage()); }
    }
    private void apTarget(Network n, LinkProperties lp, int g) {
        if (!running || g != generation) return;
        trustedInterface = lp.getInterfaceName();
        for (RouteInfo r : lp.getRoutes()) if (r.getGateway() instanceof Inet4Address && !r.getGateway().isAnyLocalAddress())
            target(r.getGateway(), lp.getInterfaceName(), n, "Android QR-specific network request");
    }
    /** The search or test ends. A display already streaming is left alone, and so is reconnecting after a drop. */
    private void finishRun(String message) { if (running) log(message + ". Report remains available."); cleanup(); label(message); ui.onChanged(); }
    private void cleanup() {
        running = false; generation++;
        if (ownDiscovery && p2p != null && channel != null) {
            try { p2p.stopPeerDiscovery(channel, null); } catch (Exception ignored) {}
            ownDiscovery = false;
        }
        if (connecting && p2p != null && channel != null) {
            try { p2p.cancelConnect(channel, null); } catch (Exception ignored) {}
            connecting = false;
        }
        if (deadline != null) main.removeCallbacks(deadline);
        synchronized(sockets) { for (Closeable s : sockets) try { s.close(); } catch (IOException ignored) {} sockets.clear(); }
        testing.set(false);
        if (discovery != null) { try { nsd.stopServiceDiscovery(discovery); } catch (Exception ignored) {} discovery = null; }
        services.clear(); resolving = false;
        if (multicast != null && multicast.isHeld()) multicast.release(); multicast = null;
        // The dashboard Wi-Fi stays requested while the display streams; releasing it would let Android drop the network.
        if (apCallback != null && !serviceActive) { try { cm.unregisterNetworkCallback(apCallback); } catch (Exception ignored) {} apCallback = null; }
        // Do not remove a group: it may belong to the user's existing Carbit session.
    }

    // ---- support tools ----
    public void requestStream(int kind, Intent consent) {
        if (!identificationAccepted) { log("Run Test connection first. Video tests unlock after an accepted identification probe."); return; }
        if (serviceActive) { log("Stop the current stream test first."); return; }
        quiet = false; pendingStream = kind; captureConsent = consent;
        log(kind == MODE_PATTERN ? "Starting a short animated color-bar test after reconnecting." : "Screen sharing authorized. Reconnecting for a 90-second mirror test; open your navigation app once frames are sent.");
        permissions(true);
    }
    public String confirmQuestion() {
        return "pattern".equals(lastStreamMode) ? "On the MOTORCYCLE screen, did you see four colored bars and a moving white square?" : "On the MOTORCYCLE screen, did the phone or navigation app visibly update when you interacted with it?";
    }
    public String[] confirmChoices() {
        return "pattern".equals(lastStreamMode) ? new String[]{"Yes, moving pattern visible","No / blank / frozen"} : new String[]{"Navigation app visibly updating","Phone screen visibly updating","No / blank / frozen"};
    }
    public void confirmVideo(int which) {
        if (!sawFrames) return;
        String[] choices = confirmChoices();
        log("USER-REPORTED VISUAL RESULT (" + lastStreamMode + "): " + choices[which] + ". This is human confirmation, not an automatic rendering measurement.");
        visualEvidence.put(lastStreamMode, choices[which]);
        label(which == choices.length - 1 ? "Visible output not confirmed" : "User confirmed: " + choices[which]);
    }
    /** Writes the support report and returns its content URI. */
    public Uri writeReport() throws Exception {
        JSONObject j = new JSONObject(); j.put("app", "Daanvia " + BuildInfo.VERSION); j.put("target", "Scanned dashboard / model=" + model + " / action=" + qrAction);
        j.put("note", "Network, handshake and video transport evidence are separate from user-confirmed visible output. Credentials and raw device IDs excluded.");
        j.put("identification_probe_accepted", identificationAccepted);
        j.put("frames_sent_by_test", new JSONObject(frameEvidence));
        j.put("user_reported_visible_output", new JSONObject(visualEvidence));
        synchronized(report) { j.put("log", report.toString()); }
        File out = new File(context.getCacheDir(), "atr-report.json");
        try (FileOutputStream f = new FileOutputStream(out)) { f.write(j.toString(2).getBytes(StandardCharsets.UTF_8)); }
        return Uri.parse("content://net.drave.atrdiagnostics.report/atr-report.json");
    }


}
