package net.drave.atrdiagnostics;

import java.io.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public final class ControlKeepalive implements Closeable {
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();
    public ControlKeepalive(PxcWriter writer,long intervalMillis,Consumer<Integer> onSent,Consumer<IOException> onError) {
        if(intervalMillis<1)throw new IllegalArgumentException("Invalid keepalive interval");
        timer.scheduleWithFixedDelay(new Runnable(){
            private int sent;
            public void run(){
                try{writer.write(Pxc.encode(0x70000000,""));onSent.accept(++sent);}
                catch(IOException e){timer.shutdown();onError.accept(e);}
            }
        },intervalMillis,intervalMillis,TimeUnit.MILLISECONDS);
    }
    public void close(){timer.shutdownNow();}
}
