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
 *  1. Scans for {@link #SCAN_MS} for Bluetooth LE devices that advertise one of the serial services EasyConn
 *     dashboards use. The phone's Bluetooth chip filters out everything else.
 *  2. Connects to each one found (at most {@link #MAX_DEVICES}), and only listens.
 *  3. Answers only a well-formed time question (EcBtp 0x01 or 0x55). A device that sends nothing valid within
 *     {@link #QUIET_MS} is let go without ever being written to, so an intercom or tyre sensor on the same
 *     service ids never hears from Daanvia.
 *
 * Everything is logged for the shared report, without addresses or the contents of non-clock messages (the same link
 * carries the dashboard's Wi-Fi password).
 */
final class BleClock {
    static final long SCAN_MS = 30_000, QUIET_MS = 60_000;
    static final int MAX_DEVICES = 3;

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

    BleClock(Context c, Consumer<String> log) { context = c.getApplicationContext(); this.log = log; }

    private void log(String s) { log.accept("Bluetooth clock: " + s); }

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
        for (UUID u : SERVICES) filters.add(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(u)).build());
        ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        try { scanner.startScan(filters, settings, scan); scanning = true; }
        catch (Exception e) { log("couldn't start scanning: " + e.getClass().getSimpleName()); return; }
        log("looking for the dashboard for " + SCAN_MS / 1000 + " s. Close Carbit Ride first, or it keeps the dashboard's Bluetooth to itself.");
        main.postDelayed(this::endScan, SCAN_MS);
    }

    @SuppressLint("MissingPermission")
    private synchronized void endScan() {
        if (!scanning) return;
        scanning = false;
        try { scanner.stopScan(scan); } catch (Exception ignored) {}
        if (found == 0 && !closed) log("no dashboard Bluetooth found in " + SCAN_MS / 1000 + " s. Is the bike on, and Carbit Ride closed?");
    }

    @SuppressLint("MissingPermission")
    synchronized void close() {
        if (closed) return;
        closed = true; main.removeCallbacksAndMessages(null);
        if (scanning) { scanning = false; try { scanner.stopScan(scan); } catch (Exception ignored) {} }
        for (Link l : links.values()) l.close();
        links.clear();
    }

    private final ScanCallback scan = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult r) { connect(r); }
        @Override public void onBatchScanResults(List<ScanResult> rs) { for (ScanResult r : rs) connect(r); }
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
        int answers, other;
        final Runnable quiet = this::giveUpIfQuiet;

        Link(BluetoothDevice d, String label) { device = d; this.label = label; }

        @SuppressLint("MissingPermission")
        void open() {
            try { gatt = device.connectGatt(context, false, this, BluetoothDevice.TRANSPORT_LE); }
            catch (Exception e) { log(label + ": couldn't connect: " + e.getClass().getSimpleName()); return; }
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
            log(label + " sent nothing Daanvia understands in " + QUIET_MS / 1000 + " s, so it isn't the dashboard. Let go without sending anything.");
            close();
        }

        @SuppressLint("MissingPermission")
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int state) {
            if (done) return;
            if (state == BluetoothProfile.STATE_CONNECTED) {
                log(label + " connected.");
                // Room for the longer time answer in one write; services are looked up once the size is agreed.
                try { if (!g.requestMtu(185)) g.discoverServices(); } catch (Exception e) { log(label + ": " + e.getClass().getSimpleName()); }
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
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
            for (BluetoothGattService s : g.getServices()) {
                if (!SERVICES.contains(s.getUuid())) continue;
                for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                    int p = c.getProperties();
                    if (!CHARACTERISTICS.contains(c.getUuid()) || (p & (BluetoothGattCharacteristic.PROPERTY_NOTIFY | BluetoothGattCharacteristic.PROPERTY_INDICATE)) == 0) continue;
                    try { g.setCharacteristicNotification(c, true); } catch (Exception ignored) {}
                    BluetoothGattDescriptor d = c.getDescriptor(CCCD);
                    if (d != null) { subscribe.add(d); listening++; }
                }
            }
            if (listening == 0) { log(label + " has no dashboard data channel. Let go."); close(); return; }
            log(label + ": listening on " + listening + " data channel" + (listening == 1 ? "" : "s") + ". Waiting for the dashboard to ask the time.");
            nextSubscription(g);
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
