package net.drave.atrdiagnostics;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;

public final class Pxc {
    public static final int PROBE = 0x70000010, ACK = 0x70000011;
    public static final int SELECT = 0x10000, SELECT_ACK = 0x10001, CLIENT_INFO = 0x10010;
    public final int command;
    public final byte[] payload;

    Pxc(int command, byte[] payload) { this.command = command; this.payload = payload; }

    public String text() { return new String(payload, StandardCharsets.UTF_8); }

    public static byte[] encode(int command, String body) { return encode(command, body.getBytes(StandardCharsets.UTF_8)); }

    public static byte[] encode(int command, byte[] data) {
        int length = 16 + data.length;
        return ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(command).putInt(length).putInt(command ^ length).putInt(0).put(data).array();
    }

    public static Pxc read(InputStream stream) throws IOException {
        DataInputStream in = new DataInputStream(stream);
        byte[] header = new byte[16];
        in.readFully(header);
        ByteBuffer b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        int command = b.getInt(), length = b.getInt(), magic = b.getInt();
        b.getInt();
        if (length < 16 || length > 65536 || magic != (command ^ length))
            throw new IOException("Unrecognized or invalid PXC frame");
        byte[] body = new byte[length - 16];
        in.readFully(body);
        return new Pxc(command, body);
    }
}
