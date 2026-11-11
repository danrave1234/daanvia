package net.drave.atrdiagnostics;

import org.json.JSONObject;
import org.junit.Test;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import static org.junit.Assert.*;

/** The bike's file server: its upload is taken, and nothing is ever handed back. */
public class BikeFtpTest {
    private static int freePort() throws Exception { try (ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); } }

    @Test public void takesTheBikesUploadAndGivesNothingBack() throws Exception {
        InetAddress lo = InetAddress.getByName("127.0.0.1"); int ctrl = freePort(), data = freePort();
        StringBuilder log = new StringBuilder();
        try (BikeFtp ftp = new BikeFtp(lo, lo, ctrl, data, log::append)) {
            Thread t = new Thread(ftp::serve); t.setDaemon(true); t.start();
            try (Socket c = new Socket(lo, ctrl)) {
                BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream())); OutputStream out = c.getOutputStream();
                assertTrue(in.readLine().startsWith("220"));
                out.write("USER carbit_ota_user\r\n".getBytes()); assertTrue(in.readLine().startsWith("331"));
                out.write("PASS x\r\n".getBytes()); assertTrue(in.readLine().startsWith("230"));
                out.write("PASV\r\n".getBytes()); String pasv = in.readLine(); assertTrue(pasv, pasv.contains("(127,0,0,1," + (data >> 8) + "," + (data & 255) + ")"));
                out.write("type i\r\n".getBytes()); assertTrue(in.readLine().startsWith("200"));
                out.write("RETR firmware.bin\r\n".getBytes()); assertTrue(in.readLine().startsWith("550"));
                out.write("STOR abcdefg.5.req\r\n".getBytes()); assertTrue(in.readLine().startsWith("150"));
                try (Socket d = new Socket(lo, data)) { d.getOutputStream().write(new byte[10]); }
                assertTrue(in.readLine().startsWith("226"));
                out.write("QUIT\r\n".getBytes()); assertTrue(in.readLine().startsWith("221"));
            }
        }
        assertTrue(log.toString(), log.toString().contains("abcdefg.5.req, 10 bytes"));
    }
    @Test public void messagesMatchCarbits() throws Exception {
        JSONObject r = new JSONObject(BikeFtp.ready(11025, 11026));
        assertTrue(r.getBoolean("isOk")); assertEquals(11025, r.getInt("dataPort")); assertEquals(11026, r.getInt("ctrlPort")); assertEquals(0, r.getInt("errCode"));
        JSONObject n = new JSONObject(BikeFtp.NO_UPDATES);
        assertEquals(1, n.getInt("code")); assertEquals(0, n.getInt("cnt")); assertEquals(0, n.getJSONArray("data").length());
    }
    @Test(expected = java.io.IOException.class) public void refusesPrivilegedPorts() throws Exception {
        InetAddress lo = InetAddress.getByName("127.0.0.1"); new BikeFtp(lo, lo, 21, 20, s -> {}).close();
    }
}
