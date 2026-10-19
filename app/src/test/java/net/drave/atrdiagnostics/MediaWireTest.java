package net.drave.atrdiagnostics;

import java.io.*;
import java.nio.*;
import java.util.*;

public final class MediaWireTest {
    private static int checks;
    private static void check(boolean value){checks++;if(!value)throw new AssertionError("Media check "+checks);}
    private static void rejects(byte[] data)throws Exception {
        try{MediaWire.read(new ByteArrayInputStream(data));throw new AssertionError("Truncated media message accepted");}catch(IOException expected){checks++;}
    }
    private static MediaWire config(int w,int h,int codec)throws Exception {
        byte[] body=ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN).putShort((short)w).putShort((short)h).putInt(15).putInt(codec).array();
        return MediaWire.read(new ByteArrayInputStream(MediaWire.encode(16,123,body)));
    }
    public static void main(String[] args)throws Exception {
        byte[] payload={1,2,3,4};byte[] wire=MediaWire.encode(114,-7,payload);
        MediaWire frame=MediaWire.read(new ByteArrayInputStream(wire));
        check(frame.command==114);check(frame.token==-7);check(Arrays.equals(frame.body,payload));
        InputStream chunks=new FilterInputStream(new ByteArrayInputStream(wire)){
            public int read(byte[] b,int o,int l)throws IOException{return super.read(b,o,Math.min(l,1));}
        };
        check(MediaWire.read(chunks).body.length==4);rejects(Arrays.copyOf(wire,7));rejects(Arrays.copyOf(wire,wire.length-1));
        int[] dimensions=config(800,386,2).captureConfig();check(dimensions[0]==800&&dimensions[1]==384);
        check(MediaWire.captureReply(800,480,0).length==8);
        byte[] extended=MediaWire.captureReply(800,480,1);check(extended.length==9&&extended[8]==1);
        ByteBuffer capture=ByteBuffer.wrap(extended).order(ByteOrder.LITTLE_ENDIAN);check(capture.getInt()==2&&(capture.getShort()&65535)==800&&(capture.getShort()&65535)==480);
        for(int[] bad:new int[][]{{1,480,2},{800,1,2},{2000,480,2},{1920,1920,2},{800,480,3}}){
            try{config(bad[0],bad[1],bad[2]).captureConfig();throw new AssertionError("Unsafe config accepted");}catch(IOException expected){checks++;}
        }
        byte[] avcc={0,0,0,2,0x67,0x42,0,0,0,2,0x68,0x11};
        byte[] annex={0,0,0,1,0x67,0x42,0,0,0,1,0x68,0x11};
        check(Arrays.equals(MediaWire.annexB(avcc),annex));check(Arrays.equals(MediaWire.annexB(annex),annex));
        for(byte[] bad:new byte[][]{{0,0},{0,0,0,9,1},{-1,-1,-1,-1}}){
            try{MediaWire.annexB(bad);throw new AssertionError("Invalid NAL accepted");}catch(IOException expected){checks++;}
        }
        ByteArrayOutputStream stream=new ByteArrayOutputStream();MediaWire.writeVideo(stream,annex);
        byte[] packet=stream.toByteArray();check(ByteBuffer.wrap(packet,0,4).order(ByteOrder.LITTLE_ENDIAN).getInt()==annex.length);
        check(Arrays.equals(Arrays.copyOfRange(packet,4,packet.length),annex));
        try{MediaWire.writeVideo(stream,new byte[0]);throw new AssertionError();}catch(IOException expected){checks++;}
        System.out.println(checks+" media framing/configuration checks passed");
    }
}
