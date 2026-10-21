package net.drave.atrdiagnostics;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class RequestPreviewTest {
    @Test public void textIsShownWithIdentityRedacted() {
        String p = StreamService.preview("{\"HUID\":\"ABC123\",\"sn\":\"77\",\"time\":1700000000,\"screenWidth\":800}".getBytes(StandardCharsets.UTF_8));
        assertFalse(p, p.contains("ABC123"));
        assertFalse(p, p.contains("\"77\""));
        assertTrue(p, p.contains("\"time\":1700000000"));
        assertTrue(p, p.contains("screenWidth"));
    }

    @Test public void binaryIsShownAsHex() {
        String p = StreamService.preview(new byte[]{0, 1, 2, (byte)0xff, 0, 0, 0, 0});
        assertTrue(p, p.contains("hex 000102ff"));
    }

    @Test public void emptyIsSaidPlainly() { assertEquals("(empty)", StreamService.preview(new byte[0])); }
}
