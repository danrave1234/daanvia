package net.drave.atrdiagnostics;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public final class CallbackPortsTest {
    public static void main(String[] args)throws Exception {
        InetAddress local=InetAddress.getLoopbackAddress();
        List<ServerSocket> first=CallbackPorts.open(local,0);ServerSocket server=first.get(0);int port=server.getLocalPort();
        ExecutorService worker=Executors.newSingleThreadExecutor();
        Future<?> exchange=worker.submit(()->{
            try(Socket accepted=server.accept()) {accepted.getOutputStream().write(7);}
            catch(IOException e){throw new RuntimeException(e);}
        });
        try(Socket client=new Socket(local,port)){if(client.getInputStream().read()!=7||client.getInputStream().read()!=-1)throw new AssertionError();}
        exchange.get(3,TimeUnit.SECONDS);server.close();
        List<ServerSocket> rebound=CallbackPorts.open(local,port);
        try{CallbackPorts.open(local,port);throw new AssertionError("Allowed a simultaneous listener");}
        catch(BindException expected){if(!expected.getMessage().contains(String.valueOf(port)))throw new AssertionError();}
        int candidate;
        try(ServerSocket free=new ServerSocket(0,1,local)){candidate=free.getLocalPort();}
        try{CallbackPorts.open(local,candidate,port);throw new AssertionError("Expected partial acquisition to fail");}
        catch(BindException expected){}
        List<ServerSocket> rolledBack=CallbackPorts.open(local,candidate);
        for(ServerSocket s:rolledBack)s.close();for(ServerSocket s:rebound)s.close();worker.shutdownNow();
        System.out.println("4 callback lifecycle checks passed: immediate reuse, listener exclusion, exact-port error, partial rollback");
    }
}
