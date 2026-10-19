package net.drave.atrdiagnostics;

import java.io.*;

public final class PxcWriter {
    private final OutputStream output;
    public PxcWriter(OutputStream output){this.output=output;}
    public synchronized void write(byte[] frame)throws IOException {output.write(frame);output.flush();}
    public synchronized void flush()throws IOException {output.flush();}
}
