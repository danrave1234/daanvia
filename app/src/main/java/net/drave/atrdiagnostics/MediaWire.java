package net.drave.atrdiagnostics;

import java.io.*;
import java.nio.*;

public final class MediaWire {
    public final int command,token;
    public final byte[] body;
    private MediaWire(int command,int token,byte[] body){this.command=command;this.token=token;this.body=body;}
    public static MediaWire read(InputStream input)throws IOException {
        DataInputStream in=new DataInputStream(input);byte[] header=new byte[8];in.readFully(header);
        ByteBuffer b=ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        int cmd=b.getShort()&65535,length=b.getShort()&65535,token=b.getInt();
        byte[] body=new byte[length];in.readFully(body);return new MediaWire(cmd,token,body);
    }
    public static byte[] encode(int command,int token,byte[] body) {
        if(body.length>65535)throw new IllegalArgumentException("Media control message too large");
        return ByteBuffer.allocate(8+body.length).order(ByteOrder.LITTLE_ENDIAN).putShort((short)command).putShort((short)body.length).putInt(token).put(body).array();
    }
    public int[] captureConfig()throws IOException {
        if(body.length<30)throw new IOException("Capture configuration truncated");
        ByteBuffer b=ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN);
        int w=b.getShort(0)&65535,h=b.getShort(2)&65535,encoder=b.getInt(8);
        if(w<64||h<64||w>1920||h>1920||(long)w*h>2200000||encoder!=2)throw new IOException("Unsupported capture size or codec");
        return new int[]{w&~15,h&~15,body[29]&255};
    }
    public static byte[] captureReply(int width,int height,int extension) {
        ByteBuffer b=ByteBuffer.allocate(extension==0?8:9).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(2).putShort((short)width).putShort((short)height);
        if(extension!=0)b.put((byte)extension);
        return b.array();
    }
    public static byte[] annexB(byte[] data)throws IOException {
        if(data.length>=4&&data[0]==0&&data[1]==0&&(data[2]==1||(data[2]==0&&data[3]==1)))return data;
        ByteArrayOutputStream out=new ByteArrayOutputStream();ByteBuffer b=ByteBuffer.wrap(data);
        while(b.remaining()>0) {
            if(b.remaining()<4)throw new IOException("Truncated AVC length");
            int n=b.getInt();if(n<=0||n>b.remaining())throw new IOException("Invalid AVC NAL length");
            out.write(new byte[]{0,0,0,1});out.write(data,b.position(),n);b.position(b.position()+n);
        }
        return out.toByteArray();
    }
    public static void writeVideo(OutputStream out,byte[] data)throws IOException {
        if(data.length<1||data.length>4000000)throw new IOException("Invalid encoded frame size");
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(data.length).array());out.write(data);out.flush();
    }
}
