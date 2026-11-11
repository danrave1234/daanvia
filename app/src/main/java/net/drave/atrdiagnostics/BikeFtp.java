package net.drave.atrdiagnostics;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The small file server the dashboard asks the phone for on every connection (0x104a0, with the two ports to use).
 * With Carbit Ride the bike logs in, uploads an empty "is there an update?" request file, and is told there is none;
 * only after that exchange did the bike's clock come right (Carbit Ride's log of 12 Nov: clock unset at 00:33:52, the
 * exchange at 00:33:54 to 00:33:57, clock right from then on). Daanvia used to acknowledge the request and stop there.
 *
 * This takes the bike's upload and throws it away. It never has a file to give: every download or listing is refused,
 * so the bike is never handed firmware. Only the bike's own address may connect.
 */
final class BikeFtp implements Closeable {
    /** What the phone tells the bike about the server (0x20280), and that there is nothing to update (0x20290). */
    static final int READY = 0x20280, UPLOADED = 0x104c0, RESULT = 0x20290;
    static final String NO_UPDATES = "{\"code\":1,\"cnt\":0,\"data\":[]}";
    static String ready(int dataPort, int ctrlPort) { return "{\"isOk\":true,\"dataPort\":" + dataPort + ",\"ctrlPort\":" + ctrlPort + ",\"errCode\":0}"; }
    private static final int MAX_UPLOAD = 1 << 20;

    private final ServerSocket control, data; private final InetAddress local, bike; private final Consumer<String> log;
    private volatile boolean closed;

    BikeFtp(InetAddress local, InetAddress bike, int ctrlPort, int dataPort, Consumer<String> log) throws IOException {
        if (ctrlPort < 1024 || ctrlPort > 65535 || dataPort < 1024 || dataPort > 65535 || ctrlPort == dataPort) throw new IOException("Unusable ports " + ctrlPort + "/" + dataPort);
        this.local = local; this.bike = bike; this.log = log;
        ServerSocket c = new ServerSocket(); c.setReuseAddress(true); c.bind(new InetSocketAddress(local, ctrlPort), 2);
        ServerSocket d = new ServerSocket();
        try { d.setReuseAddress(true); d.bind(new InetSocketAddress(local, dataPort), 2); d.setSoTimeout(10000); } catch (IOException e) { c.close(); d.close(); throw e; }
        control = c; data = d;
    }

    /** Serve the bike until closed. Blocks: run it on a worker thread. */
    void serve() {
        while (!closed) {
            try (Socket s = control.accept()) {
                if (!s.getInetAddress().equals(bike)) continue;
                s.setSoTimeout(30000); session(s);
            } catch (IOException e) { if (!closed) log.accept("Bike file server: " + e.getClass().getSimpleName()); }
        }
    }

    private void session(Socket s) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8)); OutputStream out = s.getOutputStream();
        say(out, "220 Service ready for new user.");
        String line;
        while (!closed && (line = in.readLine()) != null) {
            String cmd = line.trim(), verb = (cmd.contains(" ") ? cmd.substring(0, cmd.indexOf(' ')) : cmd).toUpperCase(Locale.US), arg = cmd.contains(" ") ? cmd.substring(cmd.indexOf(' ') + 1).trim() : "";
            String reply = reply(verb, arg);
            if (reply != null) { say(out, reply); if (verb.equals("QUIT")) return; continue; }
            // STOR: take the upload on the data port and discard it.
            say(out, "150 File status okay; about to open data connection.");
            int got = 0;
            try (Socket d = data.accept()) {
                if (!d.getInetAddress().equals(bike)) { say(out, "425 Can't open data connection."); continue; }
                d.setSoTimeout(10000); InputStream up = d.getInputStream(); byte[] buf = new byte[4096]; int n;
                while ((n = up.read(buf)) > 0) { got += n; if (got > MAX_UPLOAD) throw new IOException("Upload too large"); }
            } catch (IOException e) { say(out, "426 Data connection error."); continue; }
            say(out, "226 Transfer complete.");
            log.accept("The dashboard uploaded its update request (" + clean(arg) + ", " + got + " bytes); nothing is ever sent back.");
        }
    }

    /** The reply to one command; null for STOR, which needs the data connection. */
    String reply(String verb, String arg) {
        switch (verb) {
            case "USER": return "331 User name okay, need password for " + clean(arg) + ".";
            case "PASS": return "230 User logged in, proceed.";
            case "SYST": return "215 UNIX Type: Apache FtpServer";
            case "PWD": case "XPWD": return "257 \"/\" is current directory.";
            case "CWD": return "250 Directory changed to /";
            case "TYPE": return "200 Command TYPE okay.";
            case "NOOP": return "200 Command NOOP okay.";
            case "PASV": { byte[] a = local.getAddress(); int p = data.getLocalPort();
                return "227 Entering Passive Mode (" + (a[0] & 255) + "," + (a[1] & 255) + "," + (a[2] & 255) + "," + (a[3] & 255) + "," + (p >> 8) + "," + (p & 255) + ")"; }
            case "STOR": return arg.isEmpty() ? "501 Syntax error in parameters or arguments." : null;
            case "QUIT": return "221 Goodbye.";
            // Nothing to give the bike, ever: no firmware comes from Daanvia.
            case "RETR": case "LIST": case "NLST": case "MLSD": case "SIZE": case "MDTM": return "550 No such file or directory.";
            default: return "502 Command " + clean(verb) + " not implemented.";
        }
    }
    private static String clean(String s) { String c = s.replaceAll("[^A-Za-z0-9._-]", "?"); return c.length() > 60 ? c.substring(0, 60) : c; }
    private static void say(OutputStream out, String line) throws IOException { out.write((line + "\r\n").getBytes(StandardCharsets.UTF_8)); out.flush(); }

    @Override public void close() { closed = true; try { control.close(); } catch (IOException ignored) {} try { data.close(); } catch (IOException ignored) {} }
}
