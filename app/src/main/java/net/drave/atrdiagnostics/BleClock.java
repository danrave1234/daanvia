package net.drave.atrdiagnostics;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Sets the dashboard's clock over Bluetooth, the way Carbit Ride does (see EcBtp). Started once per dashboard session.
 *
 * The QJ's dashboard keeps its own time while the bike is off, and loses it when a phone connects over Wi-Fi: answered
 * or not, it drops to 0:00 (logs and the rider, 22 Oct and 6 Nov). Carbit puts it back by sending the time over
 * Bluetooth unasked (its sendSyncTime, as MOTO-HUB found reading it); waiting to be asked, as this did before, sent
 * nothing, because the dashboard never asks.
 *
 *  1. Scans for {@link #SCAN_MS} for Bluetooth LE devices that advertise one of the serial services EasyConn
 *     dashboards use. The phone's Bluetooth chip filters out everything else.
 *  2. Connects to each one found (at most {@link #MAX_DEVICES}) and listens on its data channel.
 *  3. Answers a time question (EcBtp 0x01 or 0x55) whenever one comes, and sends the time unasked, as Carbit's 0x01
 *     frame, {@link #PUSH_MS} after listening starts and again {@link #REPUSH_MS} later, after the Wi-Fi exchange
 *     that resets the clock is surely over. The frame is the dashboard protocol's own; other serial devices ignore it.
 *
 * Everything is logged for the shared report, without addresses or the contents of non-clock messages (the same link
 * carries the dashboard's Wi-Fi password).
 */
final class BleClock {
    static final long SCAN_MS = 30_000, QUIET_MS = 60_000, PUSH_MS = 3_000, REPUSH_MS = 25_000;
    static final int MAX_DEVICES = 3;
    /** The clock test (Support): an unfiltered scan this long, then the strongest {@link #LAB_DEVICES} devices get every time shape. */
    static final long LAB_SCAN_MS = 12_000, LAB_GAP_MS = 6_000, LAB_MS = 75_000;
    static final int LAB_DEVICES = 5;

    /** The clock test's time shapes, in the order they are written. */
    static final String[] SHAPES = {
        "number, local time (Carbit's own message)",
        "number, local time, a write the dashboard must confirm",
        "number, UTC time",
        "text, local time"};
    /** Bluetooth's own standard services (battery, device info and the like) are never a dashboard channel. */
    static boolean standard(UUID u) {
        return (u.getMostSignificantBits() & 0xFFFFFFFFL) == 0x1000L && u.getLeastSignificantBits() == 0x800000805F9B34FBL && (u.getMostSignificantBits() >>> 32) < 0x2000;
    }
    static String abilities(int p) {
        StringBuilder b = new StringBuilder();
        if ((p & BluetoothGattCharacteristic.PROPERTY_READ) != 0) b.append("read ");
        if ((p & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) b.append("write ");
        if ((p & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) b.append("write-no-reply ");
        if ((p & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) b.append("notify ");
        if ((p & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) b.append("indicate ");
        return b.toString().trim();
    }

    // Serial-over-BLE services and their data characteristics, as used by EasyConn dashboards (MOTO-HUB's notes).
    private static final List<UUID> SERVICES = uuids("00001c00-d102-11e1-9b23-000efb0000b2", "0000474d-0000-1000-8000-00805f9b34fb",
        "00001c00-d102-11e1-9b23-000efb0000c3", "0000474e-0000-1000-8000-00805f9b34fb", "00001c00-d102-11e1-9b23-000efb0000c6",
        "0000ffe0-0000-1000-8000-00805f9b34fb", "0000fff0-0000-1000-8000-00805f9b34fb", "0000b360-d6d8-c7ec-bdf0-eab1bfc6bcbc",
        "00006967-0000-1000-8000-00805f9b34fb");
    private static final List<UUID> CHARACTERISTICS = uuids("00001c0f-d102-11e1-9b23-000efb0000b2", "00004b59-0000-1000-8000-00805f9b34fb",
        "00001c0f-d102-11e1-9b23-000efb0000c6", "0000ffe1-0000-1000-8000-00805f9b34fb", "0000fff1-0000-1000-8000-00805f9b34fb",
        "0000b362-d6d8-c7ec-bdf0-eab1bfc6bcbc", "0000b363-d6d8-c7ec-bdf0-eab1bfc6bcbc", "0000b364-d6d8-c7ec-bdf0-eab1bfc6bcbc");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static List<UUID> uuids(String... s) { List<UUID> l = new ArrayList<>(); for (String x : s) l.add(UUID.fromString(x)); return l; }

    /** Android 12+: Nearby devices (scan and connect). Older: plain Bluetooth plus location, which Daanvia has. */
    static String[] permissions() {
        return Build.VERSION.SDK_INT >= 31 ? new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT} : new String[0];
    }
    static boolean allowed(Context c) {
        for (String p : permissions()) if (c.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        return Build.VERSION.SDK_INT >= 31 || c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private final Context context;
    private final Consumer<String> log;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Link> links = new HashMap<>();
    private final Set<String> seen = new HashSet<>();
    private BluetoothLeScanner scanner;
    private boolean closed, scanning;
    private int found;
    /**
     * The clock test from Support: scans without the service filter (the dashboard may not advertise the ids the filter
     * knows; MOTO-HUB's Voge didn't), logs every device heard, then connects to the strongest few, logs what each offers,
     * and writes the time to each serial-shaped channel in every shape worth trying, {@link #LAB_GAP_MS} apart, so the
     * rider can say which one the bike's clock took. Only ever run by the rider's tap.
     */
    private final boolean lab;
    private final Map<String, ScanResult> heard = new HashMap<>();
    private final Runnable onDone;

    BleClock(Context c, Consumer<String> log) { this(c, log, false, null); }
    BleClock(Context c, Consumer<String> log, boolean lab, Runnable onDone) { context = c.getApplicationContext(); this.log = log; this.lab = lab; this.onDone = onDone; }

    private void log(String s) { log.accept((lab ? "Clock test: " : "Bluetooth clock: ") + s); }

    @SuppressLint("MissingPermission")
    synchronized void start() {
        if (closed || scanning) return;
        if (!allowed(context)) { log("Nearby devices permission is off, so the bike's clock can't be set over Bluetooth. Allow it in Daanvia's app settings."); return; }
        BluetoothManager bm = context.getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = bm == null ? null : bm.getAdapter();
        if (adapter == null) { log("this phone has no Bluetooth."); return; }
        if (!adapter.isEnabled()) { log("Bluetooth is off. Turn it on so Daanvia can set the bike's clock."); return; }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) { log("Bluetooth scanning isn't available right now."); return; }
        List<ScanFilter> filters = new ArrayList<>();
        if (!lab) for (UUID u : SERVICES) filters.add(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(u)).build());
        ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        try { scanner.startScan(filters, settings, scan); scanning = true; }
        catch (Exception e) { log("couldn't start scanning: " + e.getClass().getSimpleName()); return; }
        long scanMs = lab ? LAB_SCAN_MS : SCAN_MS;
        log((lab ? "listening to every Bluetooth device nearby for " : "looking for the dashboard for ") + scanMs / 1000 + " s. Close Carbit Ride first, or it keeps the dashboard's Bluetooth to itself.");
        main.postDelayed(this::endScan, scanMs);
        if (lab) main.postDelayed(() -> { log("finished. Which step's time did the bike's clock show? Note the step number from the lines above, then share the report."); close(); }, LAB_MS);
    }

    @SuppressLint("MissingPermission")
    private synchronized void endScan() {
        if (!scanning) return;
        scanning = false;
        try { scanner.stopScan(scan); } catch (Exception ignored) {}
        if (lab) { tryNearest(); return; }
        if (found == 0 && !closed) log("no dashboard Bluetooth found in " + SCAN_MS / 1000 + " s. Is the bike on, and Carbit Ride closed?");
    }

    /**
     * The clock test: only a device that looks like the dashboard (its name, or a dashboard Bluetooth service) is
     * connected to. It used to try the nearest five whatever they were: other people's phones and watches, which
     * Android then asked to pair with (0.9.26).
     */
    private void tryNearest() {
        if (closed) return;
        List<ScanResult> all = new ArrayList<>(heard.values());
        all.sort((a, b) -> b.getRssi() - a.getRssi());
        if (all.isEmpty()) { log("nothing heard. Is Bluetooth on and the bike switched on?"); return; }
        List<ScanResult> bike = new ArrayList<>();
        for (ScanResult r : all) if (looksLikeDashboard(r)) bike.add(r);
        if (bike.isEmpty()) {
            log("heard " + all.size() + " device" + (all.size() == 1 ? "" : "s") + ", none of them the dashboard. Nothing was connected to. The closest:");
            for (int i = 0; i < Math.min(5, all.size()); i++) log("  " + nameOf(all.get(i)) + ", " + all.get(i).getRssi() + " dBm" + makers(all.get(i)) + ".");
            log("finished. The dashboard isn't sharing its Bluetooth, so its clock can't be set this way. Share the report.");
            close(); return;
        }
        log("heard the dashboard. Watch the bike's clock.");
        for (int i = 0; i < Math.min(LAB_DEVICES, bike.size()); i++) {
            BluetoothDevice d = bike.get(i).getDevice();
            Link l = new Link(d, "dashboard " + (i + 1) + " (" + nameOf(bike.get(i)) + ", " + bike.get(i).getRssi() + " dBm)");
            links.put(d.getAddress(), l); l.open();
        }
    }
    /** A name like the bike's display (SSDQ01-0120, QJ…, EasyConn, Carbit) or one of the dashboard Bluetooth services. */
    static boolean looksLikeDashboard(String name, List<UUID> services) {
        if (name != null) { String n = name.toUpperCase(Locale.ROOT); for (String w : new String[]{"SSDQ", "QJ", "EASYCONN", "CARBIT", "TFT", "DASH"}) if (n.contains(w)) return true; }
        if (services != null) for (UUID u : services) if (SERVICES.contains(u)) return true;
        return false;
    }
    private static boolean looksLikeDashboard(ScanResult r) {
        String name = nameOf(r); List<UUID> ids = new ArrayList<>();
        if (r.getScanRecord() != null && r.getScanRecord().getServiceUuids() != null) for (ParcelUuid p : r.getScanRecord().getServiceUuids()) ids.add(p.getUuid());
        return looksLikeDashboard("no name".equals(name) ? null : name, ids);
    }
    /** The maker codes a device advertises (which company made it), to tell the bike from phones and watches. */
    private static String makers(ScanResult r) {
        if (r.getScanRecord() == null || r.getScanRecord().getManufacturerSpecificData() == null || r.getScanRecord().getManufacturerSpecificData().size() == 0) return "";
        StringBuilder b = new StringBuilder(", maker");
        android.util.SparseArray<byte[]> m = r.getScanRecord().getManufacturerSpecificData();
        for (int i = 0; i < m.size(); i++) b.append(i == 0 ? " " : ", ").append(String.format(Locale.US, "0x%04x", m.keyAt(i)));
        return b.toString();
    }

    @SuppressLint("MissingPermission")
    private static String nameOf(ScanResult r) {
        String name = null; try { name = r.getDevice().getName(); } catch (Exception ignored) {}
        if (name == null && r.getScanRecord() != null) name = r.getScanRecord().getDeviceName();
        return name == null || name.isEmpty() ? "no name" : name;
    }

    /** The clock test: each device heard, logged once with its name, signal and the services it advertises. */
    private synchronized void hear(ScanResult r) {
        BluetoothDevice d = r.getDevice();
        if (closed || d == null) return;
        boolean first = !heard.containsKey(d.getAddress());
        heard.put(d.getAddress(), r);
        if (!first || heard.size() > 40) return;
        List<ParcelUuid> ids = r.getScanRecord() == null ? null : r.getScanRecord().getServiceUuids();
        log("heard " + nameOf(r) + ", " + r.getRssi() + " dBm" + (ids == null || ids.isEmpty() ? "" : ", services " + ids) + ".");
    }

    @SuppressLint("MissingPermission")
    synchronized void close() {
        if (closed) return;
        closed = true; main.removeCallbacksAndMessages(null);
        if (scanning) { scanning = false; try { scanner.stopScan(scan); } catch (Exception ignored) {} }
        for (Link l : links.values()) l.close();
        links.clear();
        if (onDone != null) main.post(onDone);
    }

    private final ScanCallback scan = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult r) { if (lab) hear(r); else connect(r); }
        @Override public void onBatchScanResults(List<ScanResult> rs) { for (ScanResult r : rs) if (lab) hear(r); else connect(r); }
        @Override public void onScanFailed(int code) { log("scan failed (Android error " + code + ")."); synchronized (BleClock.this) { scanning = false; } }
    };

    @SuppressLint("MissingPermission")
    private synchronized void connect(ScanResult r) {
        BluetoothDevice d = r.getDevice();
        if (closed || d == null || seen.contains(d.getAddress())) return;
        // Devices let go of free their place; one found while every place is taken is tried when it shows up again.
        links.values().removeIf(l -> l.done);
        if (links.size() >= MAX_DEVICES) return;
        seen.add(d.getAddress());
        found++;
        String name = null; try { name = d.getName(); } catch (Exception ignored) {}
        if (name == null && r.getScanRecord() != null) name = r.getScanRecord().getDeviceName();
        String label = "device " + found + (name == null || name.isEmpty() ? "" : " (" + name + ")");
        log("found " + label + ", signal " + r.getRssi() + " dBm. Connecting and listening.");
        Link l = new Link(d, label);
        links.put(d.getAddress(), l);
        l.open();
    }

    /** One Bluetooth device being listened to. */
    private final class Link extends BluetoothGattCallback {
        final BluetoothDevice device; final String label;
        BluetoothGatt gatt;
        final ArrayDeque<BluetoothGattDescriptor> subscribe = new ArrayDeque<>();
        final Map<UUID, EcBtp.Assembler> incoming = new HashMap<>();
        boolean spoke, done;
        int answers, other, pushes, busy;
        BluetoothGattCharacteristic data;
        final Runnable quiet = this::giveUpIfQuiet;

        Link(BluetoothDevice d, String label) { device = d; this.label = label; }

        @SuppressLint("MissingPermission")
        void open() {
            try { gatt = device.connectGatt(context, false, this, BluetoothDevice.TRANSPORT_LE); }
            catch (Exception e) { log(label + ": couldn't connect: " + e.getClass().getSimpleName()); close(); return; }
            main.postDelayed(quiet, QUIET_MS);
        }

        @SuppressLint("MissingPermission")
        synchronized void close() {
            if (done) return;
            done = true; main.removeCallbacks(quiet);
            if (gatt != null) { try { gatt.disconnect(); gatt.close(); } catch (Exception ignored) {} }
        }

        private void giveUpIfQuiet() {
            if (spoke || done) return;
            log(label + " sent nothing back in " + QUIET_MS / 1000 + " s" + (pushes > 0 ? " after " + pushes + " time message" + (pushes == 1 ? "" : "s") : "") + ". Let go.");
            close();
        }

        /**
         * The dashboard data characteristic to write to: one of the known ones; in the clock test also the one writable
         * characteristic of a service that isn't one of Bluetooth's standard ones and has something to listen on (the
         * shape of a serial channel). Else none.
         */
        private BluetoothGattCharacteristic writableData(BluetoothGatt g) {
            for (BluetoothGattService s : g.getServices()) {
                if (!SERVICES.contains(s.getUuid())) continue;
                for (BluetoothGattCharacteristic c : s.getCharacteristics()) if (CHARACTERISTICS.contains(c.getUuid()) && writable(c)) return c;
            }
            if (!lab) return null;
            for (BluetoothGattService s : g.getServices()) {
                if (standard(s.getUuid())) continue;
                BluetoothGattCharacteristic w = null; int writes = 0, listens = 0;
                for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                    if (writable(c)) { writes++; w = c; }
                    if ((c.getProperties() & (BluetoothGattCharacteristic.PROPERTY_NOTIFY | BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0) listens++;
                }
                if (writes == 1 && listens >= 1) return w;
            }
            return null;
        }

        /** Sends the time unasked, as Carbit does: the 0x01 frame, epoch milliseconds plus the zone's standard offset. */
        @SuppressLint("MissingPermission")
        @SuppressWarnings("deprecation")
        private synchronized void push(BluetoothGatt g) {
            if (done || data == null) return;
            byte[] frame = EcBtp.syncTimeReply(System.currentTimeMillis(), TimeZone.getDefault());
            int type = (data.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0 ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
            boolean ok;
            try {
                if (Build.VERSION.SDK_INT >= 33) ok = g.writeCharacteristic(data, frame, type) == android.bluetooth.BluetoothStatusCodes.SUCCESS;
                else { data.setWriteType(type); data.setValue(frame); ok = g.writeCharacteristic(data); }
            } catch (Exception e) { ok = false; }
            // Android runs one Bluetooth request at a time: a refused write is tried again, three times at most.
            if (!ok) { if (busy++ < 3) main.postDelayed(() -> push(g), 500); else log(label + ": couldn't send the time (Android refused the write)."); return; }
            busy = 0;
            pushes++;
            log(label + ": sent the phone's time (" + new java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(new java.util.Date()) + ")" + (pushes > 1 ? " again." : ". Check the bike's clock."));
        }

        @SuppressLint("MissingPermission")
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int state) {
            if (done) return;
            if (state == BluetoothProfile.STATE_CONNECTED) {
                log(label + " connected.");
                // Room for the longer time answer in one write; services are looked up once the size is agreed.
                try { if (!g.requestMtu(185)) g.discoverServices(); } catch (Exception e) { log(label + ": " + e.getClass().getSimpleName()); }
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                if (!spoke && status != BluetoothGatt.GATT_SUCCESS) synchronized (BleClock.this) { seen.remove(device.getAddress()); }
                log(label + " disconnected (status " + status + ")" + (answers > 0 ? " after " + answers + " clock answer" + (answers == 1 ? "" : "s") + "." : "."));
                close();
            }
        }

        @SuppressLint("MissingPermission")
        @Override public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            if (done) return;
            try { g.discoverServices(); } catch (Exception e) { log(label + ": " + e.getClass().getSimpleName()); }
        }

        @SuppressLint("MissingPermission")
        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (done) return;
            int listening = 0;
            if (lab) describe(g);
            data = writableData(g);
            for (BluetoothGattService s : g.getServices()) {
                boolean chosen = data != null && data.getService() == s;
                if (!SERVICES.contains(s.getUuid()) && !chosen) continue;
                for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                    int p = c.getProperties();
                    if (!(CHARACTERISTICS.contains(c.getUuid()) || chosen) || (p & (BluetoothGattCharacteristic.PROPERTY_NOTIFY | BluetoothGattCharacteristic.PROPERTY_INDICATE)) == 0) continue;
                    try { g.setCharacteristicNotification(c, true); } catch (Exception ignored) {}
                    BluetoothGattDescriptor d = c.getDescriptor(CCCD);
                    if (d != null) { subscribe.add(d); listening++; }
                }
            }
            if (listening == 0 && data == null) { log(label + " has no dashboard data channel. Let go."); close(); return; }
            log(label + ": listening on " + listening + " data channel" + (listening == 1 ? "" : "s") + (data == null ? "; nothing to write the time to." : ". Sending the time in " + PUSH_MS / 1000 + " s."));
            nextSubscription(g);
            if (data == null) return;
            if (!lab) { main.postDelayed(() -> push(g), PUSH_MS); main.postDelayed(() -> push(g), PUSH_MS + REPUSH_MS); return; }
            log(label + ": writing the time to " + data.getUuid() + ", one shape every " + LAB_GAP_MS / 1000 + " s.");
            for (int i = 0; i < SHAPES.length; i++) { final int step = i; main.postDelayed(() -> labWrite(g, step), PUSH_MS + i * LAB_GAP_MS); }
        }

        /** Every service and characteristic the device offers, for the report (ids and abilities only, never values). */
        private void describe(BluetoothGatt g) {
            StringBuilder b = new StringBuilder();
            for (BluetoothGattService s : g.getServices()) {
                b.append("\n  service ").append(s.getUuid());
                for (BluetoothGattCharacteristic c : s.getCharacteristics()) b.append("\n    ").append(c.getUuid()).append(" ").append(abilities(c.getProperties()));
            }
            log(label + " offers:" + b);
        }

        /** The clock test's step {@code i}: one time shape, written once. */
        @SuppressLint("MissingPermission")
        @SuppressWarnings("deprecation")
        private synchronized void labWrite(BluetoothGatt g, int i) {
            if (done || data == null) return;
            long now = System.currentTimeMillis(); TimeZone z = TimeZone.getDefault();
            byte[] frame = i <= 1 ? EcBtp.syncTimeReply(now, z) : i == 2 ? EcBtp.syncTime(now) : EcBtp.queryTimeReply(now, z);
            int p = data.getProperties();
            boolean confirmed = i == 1 && (p & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 || (p & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) == 0;
            int type = confirmed ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
            boolean ok;
            try {
                if (Build.VERSION.SDK_INT >= 33) ok = g.writeCharacteristic(data, frame, type) == android.bluetooth.BluetoothStatusCodes.SUCCESS;
                else { data.setWriteType(type); data.setValue(frame); ok = g.writeCharacteristic(data); }
            } catch (Exception e) { ok = false; }
            log(label + ": step " + (i + 1) + "/" + SHAPES.length + " at " + new java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(new java.util.Date(now)) + ", " + SHAPES[i] + (ok ? "." : ": Android refused the write."));
        }

        /** Turns on notifications one channel at a time (Android runs one Bluetooth request at a time). */
        @SuppressLint("MissingPermission")
        private void nextSubscription(BluetoothGatt g) {
            BluetoothGattDescriptor d = subscribe.poll();
            if (d == null) return;
            boolean indicate = (d.getCharacteristic().getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0;
            byte[] value = indicate ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
            try {
                if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, value);
                else { d.setValue(value); g.writeDescriptor(d); }
            } catch (Exception e) { log(label + ": " + e.getClass().getSimpleName()); nextSubscription(g); }
        }

        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) { if (!done) nextSubscription(g); }

        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) { received(g, c, value); }
        @SuppressWarnings("deprecation")
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) { if (Build.VERSION.SDK_INT < 33) received(g, c, c.getValue()); }

        private synchronized void received(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            if (done || value == null) return;
            EcBtp.Assembler a = incoming.computeIfAbsent(c.getUuid(), u -> new EcBtp.Assembler());
            for (EcBtp.Frame f = a.add(value); f != null; f = a.add(new byte[0])) {
                if (!spoke) { spoke = true; main.removeCallbacks(quiet); log(label + " speaks the dashboard's Bluetooth protocol."); }
                if (!f.asksForTime()) {
                    // Count and name other messages, never their contents: they include the Wi-Fi password.
                    if (++other <= 10) log(label + " sent message 0x" + String.format(Locale.US, "%02x", f.command & 0xff) + " (" + f.payload.length + " bytes); not a clock question, no reply.");
                    continue;
                }
                write(g, c, f, true);
            }
        }

        /** Answers a time question with the time right now; a write Android refuses is tried once more 300 ms later. */
        @SuppressLint("MissingPermission")
        @SuppressWarnings("deprecation")
        private synchronized void write(BluetoothGatt g, BluetoothGattCharacteristic from, EcBtp.Frame question, boolean first) {
            if (done) return;
            byte[] reply = EcBtp.answer(question, System.currentTimeMillis(), TimeZone.getDefault());
            String what = question.command == EcBtp.SYNC_TIME ? "time as a number" : "time as text";
            BluetoothGattCharacteristic to = writable(from) ? from : null;
            if (to == null) for (BluetoothGattCharacteristic c : from.getService().getCharacteristics()) if (CHARACTERISTICS.contains(c.getUuid()) && writable(c)) { to = c; break; }
            if (to == null) { log(label + " asked for the " + what + " but has no channel to answer on."); return; }
            int type = (to.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
            boolean ok;
            try {
                if (Build.VERSION.SDK_INT >= 33) ok = g.writeCharacteristic(to, reply, type) == android.bluetooth.BluetoothStatusCodes.SUCCESS;
                else { to.setWriteType(type); to.setValue(reply); ok = g.writeCharacteristic(to); }
            } catch (Exception e) { ok = false; }
            // Android runs one Bluetooth request at a time; one refused while another was still going gets one more try.
            if (!ok && first) { main.postDelayed(() -> write(g, from, question, false), 300); return; }
            answers++;
            log(label + " asked for the " + what + (ok ? ". Sent the phone's time: " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(new java.util.Date()) + "." : ". Sending the answer failed."));
        }

        private boolean writable(BluetoothGattCharacteristic c) {
            return (c.getProperties() & (BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0;
        }

        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) log(label + ": the dashboard refused the clock answer (status " + status + ").");
        }
    }
}
