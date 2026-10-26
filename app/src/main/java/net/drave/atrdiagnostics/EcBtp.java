package net.drave.atrdiagnostics;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The dashboard's Bluetooth (BLE) messages, as far as the clock needs them. Carbit Ride sets the QJ/EasyConn TFT clock
 * over Bluetooth, not over Wi-Fi: the Wi-Fi time answer (see DashClock) alone leaves the clock counting up from 00:00.
 * Protocol facts from MOTO-HUB's published notes on Carbit Ride; this is our own implementation.
 *
 * A message is {@code 0x24, command, length, payload…, xor, 0x0A}. The length counts everything except the final
 * 0x0A (payload + 4); the xor covers the start byte, command, length and payload.
 *
 *  - Command 0x01: the dashboard asks for the time as a number. Answer: 8 bytes, little-endian, epoch milliseconds
 *    plus the zone's standard (raw) offset, as Carbit does.
 *  - Command 0x55: the dashboard asks for the time as text. Answer: "dd.MM.yyyy HH:mm:ss:zzz".
 *
 * Daanvia only ever answers one of those two questions, and only after the device sent a well-formed message:
 * the Bluetooth service ids involved are shared with intercoms and other gadgets, so nothing is sent unasked.
 */
final class EcBtp {
    private EcBtp() {}

    static final byte START = 0x24, END = 0x0A;
    static final byte SYNC_TIME = 0x01, QUERY_TIME = 0x55;
    private static final int OVERHEAD = 5, MAX_TEXT = 120;

    /** One message from the dashboard: its command and payload. */
    static final class Frame {
        final byte command; final byte[] payload;
        Frame(byte command, byte[] payload) { this.command = command; this.payload = payload; }
        boolean asksForTime() { return command == SYNC_TIME || command == QUERY_TIME; }
    }

    static byte[] build(byte command, byte[] payload) {
        byte[] f = new byte[payload.length + OVERHEAD];
        f[0] = START; f[1] = command; f[2] = (byte) (payload.length + 4);
        System.arraycopy(payload, 0, f, 3, payload.length);
        f[f.length - 2] = xor(f, 3 + payload.length); f[f.length - 1] = END;
        return f;
    }

    /** The message in {@code b}, or null unless every field checks out (start, length, xor, end). */
    static Frame parse(byte[] b) {
        if (b == null || b.length < OVERHEAD || b[0] != START || b[b.length - 1] != END) return null;
        int payload = (b[2] & 0xff) - 4;
        if (payload < 0 || payload + OVERHEAD != b.length || b[b.length - 2] != xor(b, 3 + payload)) return null;
        byte[] p = new byte[payload]; System.arraycopy(b, 3, p, 0, payload);
        return new Frame(b[1], p);
    }

    private static byte xor(byte[] b, int n) { int x = 0; for (int i = 0; i < n; i++) x ^= b[i] & 0xff; return (byte) x; }

    /** Answer to command 0x01. */
    static byte[] syncTimeReply(long nowMs, TimeZone zone) {
        long t = nowMs + zone.getRawOffset();
        byte[] p = new byte[8]; for (int i = 0; i < 8; i++) p[i] = (byte) (t >>> (8 * i));
        return build(SYNC_TIME, p);
    }

    /** Answer to command 0x55. */
    static byte[] queryTimeReply(long nowMs, TimeZone zone) {
        SimpleDateFormat f = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss:zzz", Locale.US); f.setTimeZone(zone);
        byte[] p = f.format(new Date(nowMs)).getBytes(StandardCharsets.UTF_8);
        if (p.length > MAX_TEXT) { byte[] cut = new byte[MAX_TEXT]; System.arraycopy(p, 0, cut, 0, MAX_TEXT); p = cut; }
        return build(QUERY_TIME, p);
    }

    /** The answer to a time question, or null for anything else. */
    static byte[] answer(Frame f, long nowMs, TimeZone zone) {
        if (f.command == SYNC_TIME) return syncTimeReply(nowMs, zone);
        if (f.command == QUERY_TIME) return queryTimeReply(nowMs, zone);
        return null;
    }

    /**
     * Joins Bluetooth notifications into whole messages: a message may arrive split over several notifications, or
     * after unrelated bytes. Bytes that can't start a message are dropped; a buffer that grows past any possible
     * message is cleared.
     */
    static final class Assembler {
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        /** Adds bytes; returns the message they complete, or null. */
        Frame add(byte[] data) {
            buf.write(data, 0, data.length);
            while (true) {
                byte[] b = buf.toByteArray();
                int start = 0; while (start < b.length && b[start] != START) start++;
                if (start > 0) { buf.reset(); buf.write(b, start, b.length - start); continue; }
                if (b.length < 3) return null;
                int size = (b[2] & 0xff) + 1;
                if (size < OVERHEAD) { drop(b, 1); continue; }
                if (b.length < size) {
                    // Maybe that 0x24 was a stray byte: a complete message further on wins.
                    for (int k = 1; k < b.length; k++) if (b[k] == START && completeAt(b, k)) { drop(b, k); break; }
                    if (buf.size() == b.length) { if (b.length > 512) buf.reset(); return null; }
                    continue;
                }
                byte[] one = new byte[size]; System.arraycopy(b, 0, one, 0, size);
                Frame f = parse(one);
                if (f == null) { drop(b, 1); continue; }
                drop(b, size);
                return f;
            }
        }
        private static boolean completeAt(byte[] b, int k) {
            if (b.length - k < 3) return false;
            int size = (b[k + 2] & 0xff) + 1;
            if (size < OVERHEAD || b.length - k < size) return false;
            byte[] one = new byte[size]; System.arraycopy(b, k, one, 0, size);
            return parse(one) != null;
        }
        private void drop(byte[] b, int n) { buf.reset(); buf.write(b, n, b.length - n); }
        /** Bytes waiting for the rest of a message. */
        int pending() { return buf.size(); }
    }
}
