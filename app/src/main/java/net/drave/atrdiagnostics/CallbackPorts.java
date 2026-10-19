package net.drave.atrdiagnostics;

import java.io.*;
import java.net.*;
import java.util.*;

public final class CallbackPorts {
    public static List<ServerSocket> open(InetAddress local,int... ports)throws IOException {
        List<ServerSocket> listeners=new ArrayList<>();
        try {
            for(int port:ports) {
                ServerSocket socket=new ServerSocket();listeners.add(socket);
                // Reuse closed TCP sessions, while keeping simultaneous listeners exclusive.
                socket.setReuseAddress(true);
                try{socket.bind(new InetSocketAddress(local,port));}
                catch(BindException e){BindException detailed=new BindException("Callback port "+port+" is unavailable (active listener or recently closed session)");detailed.initCause(e);throw detailed;}
                socket.setSoTimeout(1000);
            }
            return listeners;
        }catch(IOException e){for(ServerSocket socket:listeners)try{socket.close();}catch(IOException ignored){}throw e;}
    }
}
