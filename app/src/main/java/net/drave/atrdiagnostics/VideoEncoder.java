package net.drave.atrdiagnostics;

import android.media.*;
import android.media.projection.MediaProjection;
import android.hardware.display.*;
import android.opengl.*;
import android.view.Surface;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.concurrent.*;
import java.util.function.Consumer;

public final class VideoEncoder implements Closeable {
    private MediaCodec codec;
    private Surface surface;
    private VirtualDisplay display;
    private volatile boolean running;
    private Thread drain,draw;
    private final ArrayBlockingQueue<byte[]> frames=new ArrayBlockingQueue<>(8);
    private final Consumer<String> log;
    private byte[] headers=new byte[0];
    private int width,height;
    private DashboardRenderer navigation;
    public void setNavigation(NavSession session){navigation=new DashboardRenderer(session);}
    public VideoEncoder(Consumer<String> log){this.log=log;}
    public void start(int w,int h,MediaProjection projection,int dpi)throws Exception {
        width=w;height=h;
        MediaFormat f=MediaFormat.createVideoFormat("video/avc",w,h);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        f.setInteger(MediaFormat.KEY_BIT_RATE,2000000);f.setInteger(MediaFormat.KEY_FRAME_RATE,fps);
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1);
        f.setInteger(MediaFormat.KEY_PROFILE,MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
        f.setInteger(MediaFormat.KEY_LEVEL,MediaCodecInfo.CodecProfileLevel.AVCLevel31);
        f.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER,100000);
        codec=MediaCodec.createEncoderByType("video/avc");
        try{codec.configure(f,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);}catch(Exception e){codec.release();codec=null;throw e;}
        surface=codec.createInputSurface();codec.start();running=true;
        drain=new Thread(this::drain,"atr-avc-output");drain.start();
        if(projection!=null)display=projection.createVirtualDisplay("ATR mirror test",w,h,dpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,surface,null,null);
        else {draw=new Thread(navigation==null?this::pattern:this::navigationFrames,"daanvia-render");draw.start();}
        log.accept("H.264 encoder started: "+w+"x"+h+", "+fps+" fps, "+(navigation!=null?"dedicated navigation scene":projection==null?"animated test pattern":"user-authorized screen sharing")+".");
    }
    private void drain() {
        MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
        try {
            while(running) {
                int i=codec.dequeueOutputBuffer(info,100000);
                if(i==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat format=codec.getOutputFormat();ByteArrayOutputStream out=new ByteArrayOutputStream();
                    for(String key:new String[]{"csd-0","csd-1"})if(format.containsKey(key)) {
                        ByteBuffer b=format.getByteBuffer(key).duplicate();byte[] bytes=new byte[b.remaining()];b.get(bytes);out.write(MediaWire.annexB(bytes));
                    }
                    if(out.size()>0)headers=out.toByteArray();
                } else if(i>=0) {
                    try {
                        if(info.size>0) {
                            ByteBuffer buffer=codec.getOutputBuffer(i);buffer.position(info.offset);buffer.limit(info.offset+info.size);
                            byte[] raw=new byte[info.size];buffer.get(raw);byte[] bytes=MediaWire.annexB(raw);
                            if((info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)!=0)headers=bytes;
                            else {
                                if((info.flags&MediaCodec.BUFFER_FLAG_KEY_FRAME)!=0&&headers.length>0) {
                                    byte[] joined=new byte[headers.length+bytes.length];System.arraycopy(headers,0,joined,0,headers.length);System.arraycopy(bytes,0,joined,headers.length,bytes.length);bytes=joined;
                                }
                                queue(bytes,(info.flags&MediaCodec.BUFFER_FLAG_KEY_FRAME)!=0);
                            }
                        }
                    } finally {codec.releaseOutputBuffer(i,false);}
                }
            }
        }catch(Exception e){if(running)log.accept("Encoder output failed: "+e.getClass().getSimpleName()+". No streaming success can be inferred.");running=false;}
    }
    private void pattern() {
        EGLDisplay egl=EGL14.EGL_NO_DISPLAY;EGLContext context=EGL14.EGL_NO_CONTEXT;EGLSurface window=EGL14.EGL_NO_SURFACE;
        try {
            egl=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);int[] version=new int[2];
            if(!EGL14.eglInitialize(egl,version,0,version,1))throw new IOException("EGL initialize failed");
            int[] attrs={EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,0x3142,1,EGL14.EGL_NONE};
            EGLConfig[] configs=new EGLConfig[1];int[] count=new int[1];
            if(!EGL14.eglChooseConfig(egl,attrs,0,configs,0,1,count,0)||count[0]==0)throw new IOException("No encoder EGL config");
            context=EGL14.eglCreateContext(egl,configs[0],EGL14.EGL_NO_CONTEXT,new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE},0);
            window=EGL14.eglCreateWindowSurface(egl,configs[0],surface,new int[]{EGL14.EGL_NONE},0);
            if(!EGL14.eglMakeCurrent(egl,window,window,context))throw new IOException("EGL surface failed");
            GLES20.glViewport(0,0,width,height);GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
            long start=System.nanoTime();int frame=0;
            float[][] colors={{1,.1f,.1f},{.1f,.9f,.2f},{.1f,.3f,1},{1,.85f,.1f}};
            while(running) {
                for(int x=0;x<4;x++){GLES20.glScissor(x*width/4,0,width/4+1,height);GLES20.glClearColor(colors[x][0],colors[x][1],colors[x][2],1);GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);}
                int size=Math.max(20,height/5),left=(frame*8)%Math.max(1,width-size);
                GLES20.glScissor(left,height/2-size/2,size,size);GLES20.glClearColor(1,1,1,1);GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                EGLExt.eglPresentationTimeANDROID(egl,window,System.nanoTime());
                if(!EGL14.eglSwapBuffers(egl,window))throw new IOException("EGL frame failed");
                frame++;Thread.sleep(66);
            }
        }catch(Exception e){if(running)log.accept("Pattern rendering stopped: "+e.getClass().getSimpleName());}
        finally {
            if(egl!=EGL14.EGL_NO_DISPLAY){EGL14.eglMakeCurrent(egl,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);if(window!=EGL14.EGL_NO_SURFACE)EGL14.eglDestroySurface(egl,window);if(context!=EGL14.EGL_NO_CONTEXT)EGL14.eglDestroyContext(egl,context);EGL14.eglTerminate(egl);}
        }
    }
    private void navigationFrames() {
        if(gpuFrames())return;
        EGLDisplay egl=EGL14.EGL_NO_DISPLAY;EGLContext context=EGL14.EGL_NO_CONTEXT;EGLSurface window=EGL14.EGL_NO_SURFACE;
        android.graphics.Bitmap bitmap=null;
        try {
            egl=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);int[] version=new int[2];
            if(!EGL14.eglInitialize(egl,version,0,version,1))throw new IOException("EGL initialize failed");
            int[] attrs={EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,0x3142,1,EGL14.EGL_NONE};
            EGLConfig[] configs=new EGLConfig[1];int[] count=new int[1];
            if(!EGL14.eglChooseConfig(egl,attrs,0,configs,0,1,count,0)||count[0]==0)throw new IOException("No encoder EGL config");
            context=EGL14.eglCreateContext(egl,configs[0],EGL14.EGL_NO_CONTEXT,new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE},0);
            window=EGL14.eglCreateWindowSurface(egl,configs[0],surface,new int[]{EGL14.EGL_NONE},0);
            if(!EGL14.eglMakeCurrent(egl,window,window,context))throw new IOException("EGL surface failed");
            int vertex=shader(GLES20.GL_VERTEX_SHADER,"attribute vec2 position; attribute vec2 uv; varying vec2 v; void main(){gl_Position=vec4(position,0.,1.);v=uv;}");
            int fragment=shader(GLES20.GL_FRAGMENT_SHADER,"precision mediump float; varying vec2 v; uniform sampler2D image; void main(){gl_FragColor=texture2D(image,v);}");
            int program=GLES20.glCreateProgram();GLES20.glAttachShader(program,vertex);GLES20.glAttachShader(program,fragment);GLES20.glLinkProgram(program);GLES20.glUseProgram(program);
            java.nio.FloatBuffer vertices=java.nio.ByteBuffer.allocateDirect(8*4).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();vertices.put(new float[]{-1,-1,1,-1,-1,1,1,1}).position(0);
            java.nio.FloatBuffer uv=java.nio.ByteBuffer.allocateDirect(8*4).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();uv.put(new float[]{0,1,1,1,0,0,1,0}).position(0);
            int a=GLES20.glGetAttribLocation(program,"position"),b=GLES20.glGetAttribLocation(program,"uv");GLES20.glEnableVertexAttribArray(a);GLES20.glEnableVertexAttribArray(b);GLES20.glVertexAttribPointer(a,2,GLES20.GL_FLOAT,false,0,vertices);GLES20.glVertexAttribPointer(b,2,GLES20.GL_FLOAT,false,0,uv);
            int[] texture=new int[1];GLES20.glGenTextures(1,texture,0);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texture[0]);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
            bitmap=android.graphics.Bitmap.createBitmap(width,height,android.graphics.Bitmap.Config.ARGB_8888);android.graphics.Canvas canvas=new android.graphics.Canvas(bitmap);GLES20.glViewport(0,0,width,height);
            while(running){long frameStart=android.os.SystemClock.uptimeMillis();navigation.draw(canvas,width,height);android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D,0,bitmap,0);GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);EGLExt.eglPresentationTimeANDROID(egl,window,System.nanoTime());if(!EGL14.eglSwapBuffers(egl,window))throw new IOException("Frame submit failed");long spare=1000/fps-(android.os.SystemClock.uptimeMillis()-frameStart);if(spare>0)Thread.sleep(spare);}
        }catch(Exception e){if(running)log.accept("Navigation renderer stopped: "+e.getClass().getSimpleName());running=false;}
        finally{if(bitmap!=null)bitmap.recycle();if(egl!=EGL14.EGL_NO_DISPLAY){EGL14.eglMakeCurrent(egl,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);if(window!=EGL14.EGL_NO_SURFACE)EGL14.eglDestroySurface(egl,window);if(context!=EGL14.EGL_NO_CONTEXT)EGL14.eglDestroyContext(egl,context);EGL14.eglTerminate(egl);}}
    }
    /**
     * The scene drawn by the GPU straight onto the encoder's input, like the phone draws its own screen: no bitmap drawn in
     * software and copied over each frame, which held the bike display at 15 frames a second. Frames are paced on a fixed
     * clock at the rate the bike pulls them (see pace). False when the phone can't draw this way; then software drawing runs.
     */
    private boolean gpuFrames(){
        boolean drew=false;
        try{
            long next=android.os.SystemClock.uptimeMillis();
            while(running){
                android.graphics.Canvas c=surface.lockHardwareCanvas();
                try{navigation.draw(c,width,height);}finally{surface.unlockCanvasAndPost(c);}
                drew=true;drawn++;
                next+=1000/fps;long spare=next-android.os.SystemClock.uptimeMillis();if(spare>0)Thread.sleep(spare);else next=android.os.SystemClock.uptimeMillis();
            }
            return true;
        }catch(Exception e){
            if(!drew&&running){log.accept("GPU drawing unavailable ("+e.getClass().getSimpleName()+"); drawing in software.");return false;}
            if(running)log.accept("Navigation renderer stopped: "+e.getClass().getSimpleName());running=false;return true;
        }
    }
    volatile int drawn;
    private int shader(int type,String source)throws IOException{int shader=GLES20.glCreateShader(type);GLES20.glShaderSource(shader,source);GLES20.glCompileShader(shader);int[] status=new int[1];GLES20.glGetShaderiv(shader,GLES20.GL_COMPILE_STATUS,status,0);if(status[0]==0)throw new IOException("Navigation shader failed");return shader;}
    // The bike pulls frames at its own pace. Frames waiting in the queue are frames the bike shows late, so once more than
    // three are waiting they are dropped and the encoder sends a fresh key frame; a P-frame can't be shown without the ones before it.
    private final Object queueLock=new Object();private boolean waitKey;
    private void queue(byte[] bytes,boolean key){synchronized(queueLock){
        if(waitKey&&!key)return;if(key)waitKey=false;
        if(!frames.offer(bytes))restart();}}
    private void restart(){frames.clear();waitKey=true;try{android.os.Bundle b=new android.os.Bundle();b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME,0);codec.setParameters(b);}catch(Exception ignored){}}
    public byte[] next()throws Exception {
        synchronized(queueLock){if(frames.size()>3)restart();}
        int waiting=frames.size();long asked=System.nanoTime();
        byte[] data=frames.poll(6,TimeUnit.SECONDS);if(data==null)throw new IOException("No encoded frame available within six seconds");
        pace(waiting,(System.nanoTime()-asked)/1_000_000L);return data;
    }

    // The bike pulls frames as fast as it shows them, and says nothing about its refresh rate. When it keeps waiting on us
    // for a frame, it could show more, so frames are drawn faster (up to 30 a second); when finished frames pile up
    // unwanted, slower (down to 10). Checked every 30 pulls; the rate the bike settles on is remembered for next time.
    private volatile int fps=15;private int pulls;private long waited;private int backlog;private java.util.function.IntConsumer onRate;
    /** Start at the rate this bike settled on last time; {@code onRate} hears each change. */
    public void pace(int start,java.util.function.IntConsumer onRate){fps=Math.max(10,Math.min(30,start>0?start:15));this.onRate=onRate;}
    public int fps(){return fps;}
    private void pace(int waiting,long waitMs){
        pulls++;waited+=waitMs;if(waiting>=2)backlog++;
        if(pulls<30)return;
        long avgWait=waited/pulls;int old=fps;
        if(backlog>pulls/3&&fps>10)fps-=5;else if(avgWait>=12&&fps<30)fps+=5;
        pulls=0;waited=0;backlog=0;
        if(fps!=old&&onRate!=null)onRate.accept(fps);
    }
    public void close() {
        running=false;if(draw!=null)draw.interrupt();if(drain!=null)drain.interrupt();
        if(display!=null){display.release();display=null;}
        for(Thread t:new Thread[]{draw,drain})if(t!=null&&t!=Thread.currentThread())try{t.join(800);}catch(InterruptedException e){Thread.currentThread().interrupt();}
        if(codec!=null){try{codec.stop();}catch(Exception ignored){}try{codec.release();}catch(Exception ignored){}codec=null;}
        if(surface!=null){surface.release();surface=null;}frames.clear();
    }
}
