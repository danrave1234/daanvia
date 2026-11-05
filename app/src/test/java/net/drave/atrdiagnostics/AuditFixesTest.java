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

    @Test public void aResetDashboardDateIsReplaced() {
        long now = 1793000000000L;  // October 2026
        byte[] req = new byte[45];
        byte[] old = "2010-01-01 00:01:00.000000000".getBytes(StandardCharsets.US_ASCII); System.arraycopy(old, 0, req, 16, old.length);
        assertFalse(DashClock.keepsOwnTime(req, now));
        String sent = new String(DashClock.timeSyncReply(req, now, TimeZone.getTimeZone("Asia/Manila")), 16, 4, StandardCharsets.US_ASCII);
        assertEquals("2026", sent);
    }
}
