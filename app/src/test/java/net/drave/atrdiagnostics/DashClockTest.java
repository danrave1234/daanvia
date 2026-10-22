package net.drave.atrdiagnostics;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.TimeZone;
import org.json.JSONObject;
import org.junit.Test;

public class DashClockTest {
    private static final TimeZone MANILA = TimeZone.getTimeZone("Asia/Manila");
    // 2026-10-22 02:42:07.123 UTC = 10:42:07.123 in Manila (UTC+8).
    private static final long NOW = 1792636927123L;

    @Test public void queryTimeAnswersWithEpochLocalAndZone() throws Exception {
        JSONObject j = new JSONObject(DashClock.queryTimeReply(NOW, MANILA, false, "37501"));
        assertEquals(NOW, j.getLong("time"));
        assertEquals(NOW + 8 * 3600_000L, j.getLong("currentTime"));
        assertEquals("Asia/Manila", j.getString("currentTimeZone"));
        assertFalse("no formatted date unless the dashboard asks for it", j.has("dateTime"));
    }

    @Test public void formattedDateOnlyWhenSupported() throws Exception {
        assertEquals("22.10.2026 10:42:07:123", new JSONObject(DashClock.queryTimeReply(NOW, MANILA, true, "37501")).getString("dateTime"));
        assertEquals("22:10:2026 10:42:07:123", new JSONObject(DashClock.queryTimeReply(NOW, MANILA, true, "21312")).getString("dateTime"));
    }

    @Test public void emptyTimeSyncRequestGetsFillerAndPhoneTime() {
        byte[] out = DashClock.timeSyncReply(new byte[0], NOW, MANILA);
        assertEquals(45, out.length);
        assertArrayEquals(new byte[]{(byte)0xfe, (byte)0xff, (byte)0xff, (byte)0xff, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0}, Arrays.copyOf(out, 16));
        assertEquals("2026-10-22 10:42:07.123000000", new String(out, 16, 29, StandardCharsets.US_ASCII));
    }

    @Test public void aRealDashboardTimeIsHandedBack() {
        byte[] req = new byte[45]; for (int i = 0; i < 16; i++) req[i] = (byte)(i + 1);
        byte[] own = "2026-10-22T10:40:00.5".getBytes(StandardCharsets.US_ASCII); System.arraycopy(own, 0, req, 16, own.length);
        byte[] out = DashClock.timeSyncReply(req, NOW, MANILA);
        assertArrayEquals(req, out);
    }

    @Test public void anUnsetDashboardClockIsReplaced() {
        byte[] req = new byte[45]; for (int i = 0; i < 16; i++) req[i] = 7;
        byte[] epoch = "1970-01-01 00:03:12.000000000".getBytes(StandardCharsets.US_ASCII); System.arraycopy(epoch, 0, req, 16, epoch.length);
        byte[] out = DashClock.timeSyncReply(req, NOW, MANILA);
        assertEquals(7, out[0]);
        assertEquals("2026-10-22 10:42:07.123000000", new String(out, 16, 29, StandardCharsets.US_ASCII));
    }
}
