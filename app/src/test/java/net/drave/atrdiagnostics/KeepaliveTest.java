package net.drave.atrdiagnostics;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class KeepaliveTest {
    public static void main(String[] args)throws Exception {
        ExecutorService jobs=Executors.newFixedThreadPool(6);
        List<ControlKeepalive> beats=new ArrayList<>();List<Socket> phones=new ArrayList<>();List<ServerSocket> servers=new ArrayList<>();
        try {
            List<Future<Integer>> results=new ArrayList<>();
            for(int channel=0;channel<2;channel++) {
                ServerSocket server=new ServerSocket(0,1,InetAddress.getLoopbackAddress());servers.add(server);
                results.add(jobs.submit(()->{
                    try(Socket dashboard=server.accept()) {
                        dashboard.setSoTimeout(4500);int received=0;
                        while(received<4) {
                            Pxc frame=Pxc.read(dashboard.getInputStream());
                            if(frame.command!=0x70000000||frame.payload.length!=0)throw new AssertionError("Invalid keepalive");
                            dashboard.getOutputStream().write(Pxc.encode(0x70000001,""));received++;
                        }
                        return received;
                    }
                }));
                Socket phone=new Socket(InetAddress.getLoopbackAddress(),server.getLocalPort());phones.add(phone);
                beats.add(new ControlKeepalive(new PxcWriter(phone.getOutputStream()),2000,n->{},e->{}));
            }
            // Both sockets must survive beyond the ATR's observed 7.5-second cutoff.
            for(Future<Integer> result:results)if(result.get(12,TimeUnit.SECONDS)!=4)throw new AssertionError("Idle channel timed out");
            for(ControlKeepalive beat:beats)beat.close();

            ByteArrayOutputStream bytes=new ByteArrayOutputStream();PxcWriter writer=new PxcWriter(bytes);
            Future<?> a=jobs.submit(()->{try{for(int i=0;i<200;i++)writer.write(Pxc.encode(0x70000000,""));}catch(IOException e){throw new RuntimeException(e);}});
            Future<?> b=jobs.submit(()->{try{for(int i=0;i<200;i++)writer.write(Pxc.encode(0x10011,"{\"field\":\"metadata\"}"));}catch(IOException e){throw new RuntimeException(e);}});
            a.get();b.get();ByteArrayInputStream input=new ByteArrayInputStream(bytes.toByteArray());int count=0;
            while(input.available()>0){Pxc.read(input);count++;}if(count!=400)throw new AssertionError("Interleaved PXC frames");

            AtomicInteger ticks=new AtomicInteger();CountDownLatch sent=new CountDownLatch(1);
            ControlKeepalive stopped=new ControlKeepalive(new PxcWriter(new ByteArrayOutputStream()),20,n->{ticks.incrementAndGet();sent.countDown();},e->{});
            if(!sent.await(2,TimeUnit.SECONDS))throw new AssertionError("Timer never fired");stopped.close();Thread.sleep(40);int before=ticks.get();Thread.sleep(100);if(ticks.get()!=before)throw new AssertionError("Keepalive survived stop");

            CountDownLatch failed=new CountDownLatch(1);AtomicInteger errors=new AtomicInteger();
            ControlKeepalive broken=new ControlKeepalive(new PxcWriter(new OutputStream(){public void write(int b)throws IOException{throw new IOException("simulated peer closure");}}),20,n->{},e->{errors.incrementAndGet();failed.countDown();});
            if(!failed.await(2,TimeUnit.SECONDS))throw new AssertionError("Missing failure callback");Thread.sleep(100);broken.close();if(errors.get()!=1)throw new AssertionError("Failed writer kept retrying");
            System.out.println("5 keepalive/writer checks passed: dual idle channels beyond 8 seconds, concurrent frame integrity, cancellation, failure termination");
        }finally{for(ControlKeepalive beat:beats)beat.close();for(Socket s:phones)s.close();for(ServerSocket s:servers)s.close();jobs.shutdownNow();}
    }
}
