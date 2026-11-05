package net.drave.atrdiagnostics;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.util.TimeZone;
import static org.junit.Assert.*;

/** The privacy and safety checks added in the November audit. */
public class AuditFixesTest {
    @Test public void logsHideTheBikesWifiNameAndCoordinates() {
        String line = LogUpload.scrub("19:48:29  QR decoded. Dashboard network: qj-5G-9c26; security: WPA; at 10.31234,123.88912\n");
        assertFalse(line.contains("qj-5G-9c26"));
        assertTrue(line.contains("network: [hidden]; security: WPA"));
        assertFalse(line.contains("10.31234"));
    }

    @Test public void onlyGoogleMapLinksAreFollowed() {
        assertTrue(ShareLinks.google("https://maps.app.goo.gl/AbC123xyz"));
        assertTrue(ShareLinks.google("https://www.google.com/maps/place/x"));
        assertTrue(ShareLinks.google("https://www.google.com.ph/maps/place/x"));
        assertTrue(ShareLinks.google("https://consent.google.com/m?continue=x"));
        assertFalse(ShareLinks.google("https://evil.example/google.com"));
        assertFalse(ShareLinks.google("https://google.com.evil.example/"));
        assertFalse(ShareLinks.google("not a url"));
    }

    @Test public void mapIndexFileNamesMustBePlain() throws Exception {
        assertEquals("cell_109.00_18.00-1b2b806d3ab0.zip", AreaStore.safeName("cell_109.00_18.00-1b2b806d3ab0.zip"));
        for (String bad : new String[]{"../shared_prefs/ui.zip", "a/b.zip", ".zip", "cell.txt"})
            try { AreaStore.safeName(bad); fail(bad); } catch (org.json.JSONException expected) {}
    }

    @Test public void headingMath() {
        assertEquals(20f, GeoMath.turn(350f, 10f), 1e-4);
        assertEquals(-20f, GeoMath.turn(10f, 350f), 1e-4);
        assertEquals(180.0, GeoMath.angleBetween(0, 180), 1e-9);
        assertEquals(20.0, GeoMath.angleBetween(350, 10), 1e-9);
        assertEquals(350.0, GeoMath.normalise(-10), 1e-9);
        assertEquals(10.0, GeoMath.normalise(730), 1e-9);
    }

    @Test public void bikeSearchStaysQuickForTenMinutesThenEasesOff() {
        assertEquals(10000, NavService.searchGap(0));
        assertEquals(10000, NavService.searchGap(12));
        assertEquals(20000, NavService.searchGap(13));
        assertEquals(60000, NavService.searchGap(100));
    }

    @Test public void aResetDashboardDateIsReplaced() {
        long now = 1793000000000L;  // October 2026
        byte[] req = new byte[45];
        byte[] old = "2010-01-01 00:01:00.000000000".getBytes(StandardCharsets.US_ASCII); System.arraycopy(old, 0, req, 16, old.length);
        assertFalse(DashClock.keepsOwnTime(req, now));
        String sent = new String(DashClock.timeSyncReply(req, now, TimeZone.getTimeZone("Asia/Manila")), 16, 4, StandardCharsets.US_ASCII);
        assertEquals("2026", sent);
    }

    @Test public void timeSyncLogShowsTheDashboardsTime() {
        byte[] req = new byte[45];
        byte[] t = "2000-01-01 00:00:34.000000000".getBytes(StandardCharsets.US_ASCII); System.arraycopy(t, 0, req, 16, t.length);
        assertEquals("2000-01-01 00:00:34.000000000", DashClock.stampOf(req));
        assertEquals("(no time)", DashClock.stampOf(new byte[16]));
    }
}
