package net.drave.atrdiagnostics;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.util.TimeZone;
import org.junit.Test;

public class EcBtpTest {
    private static final TimeZone MANILA = TimeZone.getTimeZone("Asia/Manila");
    // 2026-10-22 02:42:07.123 UTC = 10:42:07 in Manila.
    private static final long NOW = 1792636927123L;

    @Test public void framesAreBuiltWithLengthXorAndEnd() {
        byte[] f = EcBtp.build(EcBtp.SYNC_TIME, new byte[0]);
        assertArrayEquals(new byte[]{0x24, 0x01, 0x04, (byte) (0x24 ^ 0x01 ^ 0x04), 0x0A}, f);
        EcBtp.Frame back = EcBtp.parse(f);
        assertNotNull(back);
        assertEquals(EcBtp.SYNC_TIME, back.command);
        assertEquals(0, back.payload.length);
    }

    @Test public void anythingMalformedIsRejected() {
        byte[] good = EcBtp.build(EcBtp.QUERY_TIME, new byte[]{1, 2, 3});
        assertNotNull(EcBtp.parse(good));
        byte[] badXor = good.clone(); badXor[badXor.length - 2] ^= 1;
        byte[] badEnd = good.clone(); badEnd[badEnd.length - 1] = 0;
        byte[] badLen = good.clone(); badLen[2] = 9;
        assertNull(EcBtp.parse(badXor));
        assertNull(EcBtp.parse(badEnd));
        assertNull(EcBtp.parse(badLen));
        assertNull(EcBtp.parse("hello\n".getBytes(StandardCharsets.US_ASCII)));
        assertNull(EcBtp.parse(new byte[]{0x24, 0x01}));
    }

    @Test public void syncTimeIsLittleEndianLocalStandardTime() {
        EcBtp.Frame f = EcBtp.parse(EcBtp.syncTimeReply(NOW, MANILA));
        assertEquals(EcBtp.SYNC_TIME, f.command);
        assertEquals(8, f.payload.length);
        long t = 0; for (int i = 7; i >= 0; i--) t = (t << 8) | (f.payload[i] & 0xff);
        assertEquals(NOW + 8 * 3600_000L, t);
    }

    @Test public void queryTimeIsFormattedLocalTime() {
        EcBtp.Frame f = EcBtp.parse(EcBtp.queryTimeReply(NOW, MANILA));
        assertEquals(EcBtp.QUERY_TIME, f.command);
        assertTrue(new String(f.payload, StandardCharsets.UTF_8).startsWith("22.10.2026 10:42:07:"));
    }

    @Test public void onlyTimeQuestionsGetAnswers() {
        assertNotNull(EcBtp.answer(new EcBtp.Frame(EcBtp.SYNC_TIME, new byte[0]), NOW, MANILA));
        assertNotNull(EcBtp.answer(new EcBtp.Frame(EcBtp.QUERY_TIME, new byte[0]), NOW, MANILA));
        assertNull(EcBtp.answer(new EcBtp.Frame((byte) 0x30, new byte[0]), NOW, MANILA));
    }

    @Test public void splitAndNoisyNotificationsAreJoined() {
        byte[] f = EcBtp.build(EcBtp.QUERY_TIME, new byte[]{9, 9});
        EcBtp.Assembler a = new EcBtp.Assembler();
        assertNull(a.add(new byte[]{0x11, 0x22}));            // noise before the message
        assertNull(a.add(new byte[]{f[0], f[1], f[2]}));
        EcBtp.Frame got = a.add(java.util.Arrays.copyOfRange(f, 3, f.length));
        assertNotNull(got);
        assertEquals(EcBtp.QUERY_TIME, got.command);
        assertEquals(0, a.pending());
    }

    @Test public void aBrokenMessageDoesNotBlockTheNextOne() {
        byte[] bad = EcBtp.build(EcBtp.SYNC_TIME, new byte[]{6}); bad[bad.length - 2] ^= 1;
        byte[] good = EcBtp.build(EcBtp.SYNC_TIME, new byte[0]);
        byte[] both = new byte[bad.length + good.length];
        System.arraycopy(bad, 0, both, 0, bad.length); System.arraycopy(good, 0, both, bad.length, good.length);
        EcBtp.Frame got = new EcBtp.Assembler().add(both);
        assertNotNull(got);
        assertEquals(0, got.payload.length);
    }

    @Test public void aStrayStartByteDoesNotStallTheNextMessage() {
        byte[] good = EcBtp.build(EcBtp.QUERY_TIME, new byte[0]);
        byte[] data = new byte[3 + good.length];
        data[0] = 0x24; data[1] = 0x01; data[2] = 0x40;   // claims a 64-byte message that never comes
        System.arraycopy(good, 0, data, 3, good.length);
        EcBtp.Frame got = new EcBtp.Assembler().add(data);
        assertNotNull(got);
        assertEquals(EcBtp.QUERY_TIME, got.command);
    }
}
