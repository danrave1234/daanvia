package net.drave.atrdiagnostics;

import android.app.Activity;
import android.content.Intent;
import android.graphics.*;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import com.google.zxing.PlanarYUVLuminanceSource;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressWarnings("deprecation")
public final class ScanActivity extends Activity implements SurfaceHolder.Callback,Camera.PreviewCallback {
    private Camera camera;
    private final ExecutorService decode=Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy=new AtomicBoolean();
    private volatile boolean finished;
    private int width,height;
    private TextView prompt;
    private SurfaceView preview;
    private FrameLayout root;
    private long lastFrame;
    public void onCreate(Bundle state) {
        super.onCreate(state);
        Gabi.Kit k=new Gabi.Kit(this,new Gabi.Themer(Gabi.NIGHT));
        Window w=getWindow();Gabi.edgeToEdge(w);Gabi.barIcons(w,Gabi.NIGHT);
        root=new FrameLayout(this);root.setBackgroundColor(Color.BLACK);
        preview=new SurfaceView(this);root.addView(preview,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));
        root.addView(new Finder(k),new FrameLayout.LayoutParams(-1,-1));
        prompt=k.text("Point at the dashboard QR. Keep the whole code visible and avoid glare.",15,600,Gabi.Role.INK);prompt.setGravity(Gravity.CENTER);
        prompt.setPadding(k.dp(18),k.dp(14),k.dp(18),k.dp(14));k.glass(prompt,20);
        FrameLayout.LayoutParams pl=new FrameLayout.LayoutParams(-1,-2,Gravity.TOP);pl.leftMargin=pl.rightMargin=k.dp(16);root.addView(prompt,pl);
        TextView cancel=k.ghost("Cancel",this::finish);k.glass(cancel,26);
        FrameLayout.LayoutParams cl=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);cl.leftMargin=cl.rightMargin=k.dp(16);root.addView(cancel,cl);
        Gabi.insets(root,(l,t,r,b,ime)->{pl.topMargin=t+k.dp(12);cl.bottomMargin=b+k.dp(16);prompt.requestLayout();cancel.requestLayout();});
        setContentView(root);preview.getHolder().addCallback(this);
    }
    /** Dimmed surround with a clear square, corner brackets and a sweeping scan line. */
    private static final class Finder extends View {
        private final Paint dim=new Paint(),line=new Paint(Paint.ANTI_ALIAS_FLAG),sweep=new Paint(Paint.ANTI_ALIAS_FLAG);private final Path hole=new Path();private final float d;
        Finder(Gabi.Kit k){super(k.c);d=k.c.getResources().getDisplayMetrics().density;dim.setColor(0xA6000000);line.setColor(0xFFFFB020);line.setStyle(Paint.Style.STROKE);line.setStrokeWidth(4*d);line.setStrokeCap(Paint.Cap.ROUND);}
        protected void onDraw(Canvas c){
            float size=Math.min(getWidth(),getHeight())*0.68f,l=(getWidth()-size)/2,t=(getHeight()-size)/2-24*d,r=l+size,b=t+size,arm=28*d,rad=22*d;
            hole.reset();hole.setFillType(Path.FillType.EVEN_ODD);hole.addRect(0,0,getWidth(),getHeight(),Path.Direction.CW);hole.addRoundRect(l,t,r,b,rad,rad,Path.Direction.CW);c.drawPath(hole,dim);
            float breathe=(float)(Math.sin(SystemClock.uptimeMillis()/420.0)*3*d);float L=l-breathe,T=t-breathe,R=r+breathe,B=b+breathe;
            c.drawLine(L,T+arm,L,T+rad,line);c.drawArc(L,T,L+2*rad,T+2*rad,180,90,false,line);c.drawLine(L+rad,T,L+arm,T,line);
            c.drawLine(R-arm,T,R-rad,T,line);c.drawArc(R-2*rad,T,R,T+2*rad,270,90,false,line);c.drawLine(R,T+rad,R,T+arm,line);
            c.drawLine(R,B-arm,R,B-rad,line);c.drawArc(R-2*rad,B-2*rad,R,B,0,90,false,line);c.drawLine(R-rad,B,R-arm,B,line);
            c.drawLine(L+arm,B,L+rad,B,line);c.drawArc(L,B-2*rad,L+2*rad,B,90,90,false,line);c.drawLine(L,B-rad,L,B-arm,line);
            float f=(SystemClock.uptimeMillis()%2200)/2200f,y=t+12*d+(size-24*d)*(f<0.5f?f*2:2-f*2);
            sweep.setShader(new LinearGradient(l,0,r,0,new int[]{0x00FFB020,0xCCFFB020,0x00FFB020},null,Shader.TileMode.CLAMP));c.drawRect(l+14*d,y-1*d,r-14*d,y+1*d,sweep);
            postInvalidateOnAnimation();
        }
    }
    public void surfaceCreated(SurfaceHolder holder) {
        try {
            int id=-1; Camera.CameraInfo info=new Camera.CameraInfo();
            for(int i=0;i<Camera.getNumberOfCameras();i++){Camera.getCameraInfo(i,info);if(info.facing==Camera.CameraInfo.CAMERA_FACING_BACK){id=i;break;}}
            if(id<0)throw new IllegalStateException("No rear camera available");
            camera=Camera.open(id);Camera.Parameters p=camera.getParameters();
            List<Camera.Size> sizes=p.getSupportedPreviewSizes();
            Camera.Size best=sizes.get(0);int score=Integer.MAX_VALUE;
            for(Camera.Size s:sizes){int delta=Math.abs(s.width*s.height-1280*720);if(delta<score){score=delta;best=s;}}
            width=best.width;height=best.height;p.setPreviewSize(width,height);p.setPreviewFormat(android.graphics.ImageFormat.NV21);
            if(p.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE))p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            camera.setParameters(p);
            Camera.getCameraInfo(id,info);camera.setDisplayOrientation(info.orientation);
            fill(info.orientation%180==0?width:height,info.orientation%180==0?height:width);
            camera.setPreviewDisplay(holder);camera.setPreviewCallback(this);camera.startPreview();
        } catch(Exception e){release();prompt.setText("Camera could not open. Go back and choose a QR photo instead.");}
    }
    /** Size the preview to cover the screen without stretching. */
    private void fill(int w,int h){root.post(()->{float scale=Math.max(root.getWidth()/(float)w,root.getHeight()/(float)h);preview.setLayoutParams(new FrameLayout.LayoutParams(Math.round(w*scale),Math.round(h*scale),Gravity.CENTER));});}
    public void surfaceChanged(SurfaceHolder h,int format,int w,int height) {}
    public void surfaceDestroyed(SurfaceHolder h){release();}
    public void onPreviewFrame(byte[] data,Camera c) {
        if(finished||System.currentTimeMillis()-lastFrame<300||!busy.compareAndSet(false,true))return;
        lastFrame=System.currentTimeMillis();byte[] frame=Arrays.copyOf(data,width*height);int w=width,h=height;
        decode.execute(()->{
            try {
                String qr=QrDecoder.decode(new PlanarYUVLuminanceSource(frame,w,h,0,0,w,h,false));
                if(!finished){finished=true;runOnUiThread(()->{root.performHapticFeedback(HapticFeedbackConstants.CONFIRM);setResult(RESULT_OK,new Intent().putExtra("qr",qr));finish();});}
            } catch(Exception ignored) {} finally {busy.set(false);}
        });
    }
    private void release(){if(camera!=null){camera.setPreviewCallback(null);camera.stopPreview();camera.release();camera=null;}}
    protected void onResume(){super.onResume();finished=false;}
    protected void onPause(){finished=true;release();super.onPause();}
    protected void onDestroy(){decode.shutdownNow();super.onDestroy();}
}
