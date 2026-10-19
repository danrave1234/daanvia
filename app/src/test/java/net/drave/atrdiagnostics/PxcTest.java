package net.drave.atrdiagnostics;

import java.io.*;
import java.nio.*;
import java.util.Arrays;

public final class PxcTest {
    private static int checks;
    private static void check(boolean value) {
        checks++; if(!value)throw new AssertionError("Check "+checks);
    }
    private static void reject(byte[] bytes) throws Exception {
        try { Pxc.read(new ByteArrayInputStream(bytes)); throw new AssertionError("Accepted invalid input"); }
        catch(IOException expected) { checks++; }
    }
    public static void main(String[] args) throws Exception {
        byte[] encoded=Pxc.encode(Pxc.ACK,"{\"status\":true}");
        Pxc valid=Pxc.read(new ByteArrayInputStream(encoded));
        check(valid.command==Pxc.ACK); check(valid.text().equals("{\"status\":true}"));
        // Framing must work with fragmented socket reads, not just complete buffers.
        InputStream fragments=new FilterInputStream(new ByteArrayInputStream(encoded)) {
            public int read(byte[] b,int o,int l)throws IOException{return super.read(b,o,Math.min(l,1));}
        };
        check(Pxc.read(fragments).command==Pxc.ACK);
        check(Pxc.read(new ByteArrayInputStream(Pxc.encode(Pxc.SELECT,""))).payload.length==0);
        byte[] bad=encoded.clone(); bad[8]^=1; reject(bad);
        reject(Arrays.copyOf(encoded,15)); reject(Arrays.copyOf(encoded,encoded.length-1));
        for(int length:new int[]{-1,0,15,65537,Integer.MAX_VALUE}) {
            byte[] head=ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(Pxc.ACK).putInt(length).putInt(Pxc.ACK^length).putInt(0).array();
            reject(head);
        }
        check(Pxc.read(new ByteArrayInputStream(Pxc.encode(123,"é"))).text().equals("é"));
        System.out.println(checks+" protocol checks passed");
    }
}
