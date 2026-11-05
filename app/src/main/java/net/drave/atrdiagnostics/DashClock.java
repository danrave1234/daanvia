package net.drave.atrdiagnostics;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The dashboard's clock. The TFT has no clock source of its own: when the phone connects it asks what time it is, and
 * if the answer is empty it counts up from 00:00 (the "12 AM, now 0:40" a rider saw after every Daanvia ride). Carbit
 * Ride answers both of the dashboard's time questions; these are the same answers, worked out from the formats that
 * open projects documented after reading Carbit Ride (MOTO-HUB and ridedaemon-lib notes).
 *
 *  - PXC 0x10450 ("query time") is answered with 0x10451 and JSON: epoch milliseconds, local time as milliseconds,
 *    the zone id, and, only for a dashboard that says it supports it, a formatted date.
 *  - PXC 0x10600 ("time sync") is answered with 0x10601 and 45 bytes: the request's first 16 bytes echoed back, then a
 *    29-byte timestamp. A dashboard that already sent a real time of its own gets it handed back untouched.
 */
final class DashClock {
    private DashClock() {}

    static final int QUERY_TIME = 0x10450, TIME_SYNC = 0x10600;
    static final int SYNC_REPLY_SIZE = 45, ECHO_BYTES = 16, STAMP_BYTES = 29;

    /** Body of the 0x10451 reply. {@code withDate}: the dashboard's CLIENT_INFO said supportSyncCorrectTime. */
    static String queryTimeReply(long nowMs, TimeZone zone, boolean withDate, String channel) {
        try {
            JSONObject j = new JSONObject();
            j.put("time", nowMs);
            j.put("currentTime", nowMs + zone.getOffset(nowMs));
            j.put("currentTimeZone", zone.getID());
            if (withDate) {
                // Two head-unit models (by their model number, "channel" in CLIENT_INFO) want colons in the date too.
                boolean colons = "21312".equals(channel) || "21313".equals(channel);
                SimpleDateFormat f = new SimpleDateFormat(colons ? "dd:MM:yyyy HH:mm:ss:SSS" : "dd.MM.yyyy HH:mm:ss:SSS", Locale.US);
                f.setTimeZone(zone);
                j.put("dateTime", f.format(new Date(nowMs)));
            }
            return j.toString();
        } catch (JSONException e) { return ""; }
    }

    /** How long after the hello a dashboard gets to ask for the time itself before it is sent unasked. */
    static final long ASK_GRACE_MS = 5000;
    /** Below this, currentHUTime is milliseconds since the bike was switched on, not a date (1e11 ms is 1973). */
    static final long COUNTER_LIMIT = 100_000_000_000L;
    /** More than this from the phone's local time counts as wrong. */
    static final long TOLERANCE_MS = 120_000;

    /**
     * Whether the dashboard's clock needs the phone's time: currentHUTime (from its hello, {@code sinceMs} ago) is a count
     * since switch-on, or more than two minutes from local time. The dashboard keeps local time as if it were UTC (what
     * "currentTime" in the 0x10451 answer says), so that is what it's compared with.
     */
    static boolean needsTime(long huTime, long sinceMs, long nowMs, TimeZone zone) {
        if (huTime < COUNTER_LIMIT) return true;
        return Math.abs(huTime + sinceMs - (nowMs + zone.getOffset(nowMs))) > TOLERANCE_MS;
    }
    /** For the log: what the dashboard's clock said in its hello. */
    static String describe(long huTime, long nowMs, TimeZone zone) {
        if (huTime <= 0) return "Dashboard clock: not reported.";
        if (huTime < COUNTER_LIMIT) return "Dashboard clock: not set (counting " + Math.round(huTime / 1000.0) + " s since the bike was switched on).";
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US); f.setTimeZone(TimeZone.getTimeZone("UTC"));
        long off = huTime - (nowMs + zone.getOffset(nowMs));
        return "Dashboard clock: " + f.format(new Date(huTime)) + (Math.abs(off) <= TOLERANCE_MS ? " (matches the phone)." : " (" + (off > 0 ? "ahead" : "behind") + " by " + Math.round(Math.abs(off) / 60000.0) + " min).");
    }

    /** For the log: the time a 0x10600 request carried. */
    static String stampOf(byte[] request) {
        if (request == null || request.length <= ECHO_BYTES) return "(no time)";
        String t = new String(request, ECHO_BYTES, Math.min(STAMP_BYTES, request.length - ECHO_BYTES), StandardCharsets.ISO_8859_1).replaceAll("[\\x00 ]+$", "");
        return t.matches("[\\x20-\\x7e]+") ? t : "(" + (request.length - ECHO_BYTES) + " bytes)";
    }

    /** Body of the 0x10601 reply to a 0x10600 request. */
    static byte[] timeSyncReply(byte[] request, long nowMs, TimeZone zone) {
        byte[] out = new byte[SYNC_REPLY_SIZE];
        if (request != null && request.length >= ECHO_BYTES) System.arraycopy(request, 0, out, 0, Math.min(request.length, SYNC_REPLY_SIZE));
        else ByteBuffer.wrap(out, 0, ECHO_BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(0xfffffffe).putInt(0).putInt(1).putInt(0);
        if (keepsOwnTime(request, nowMs)) return out;
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US); f.setTimeZone(zone);
        byte[] stamp = (f.format(new Date(nowMs)) + "000000").getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(stamp, 0, out, ECHO_BYTES, Math.min(stamp.length, STAMP_BYTES));
        return out;
    }

    /**
     * The dashboard sent a time of its own: printable text of at least 10 characters after the 16-byte header, not the
     * 1970 of a clock that was never set, nor a reset date years back (2000, 2010). Hand that back as it came; dashboards format it in ways a strict check rejects,
     * and replacing a good clock put some hours out.
     */
    static boolean keepsOwnTime(byte[] request, long nowMs) {
        if (request == null || request.length < ECHO_BYTES + 10) return false;
        int end = Math.min(request.length, ECHO_BYTES + STAMP_BYTES);
        String stamp = new String(request, ECHO_BYTES, end - ECHO_BYTES, StandardCharsets.ISO_8859_1).replaceAll("[\\x00 ]+$", "");
        if (stamp.trim().length() < 10) return false;
        for (int i = 0; i < stamp.length(); i++) { char c = stamp.charAt(i); if (c < 0x20 || c > 0x7e) return false; }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{4})-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}").matcher(stamp);
        if (m.find()) { int year = Integer.parseInt(m.group(1)); java.util.Calendar now = java.util.Calendar.getInstance(); now.setTimeInMillis(nowMs); if (year < now.get(java.util.Calendar.YEAR) - 1) return false; }
        return true;
    }
}
