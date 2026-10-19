package net.drave.atrdiagnostics;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.hardware.Camera;
import android.os.Bundle;
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
    private long lastFrame;
    public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.BLACK);root.setFitsSystemWindows(true);
        prompt=new TextView(this);prompt.setText("Point at the dashboard QR. Keep the whole code visible and avoid glare.");prompt.setTextColor(Color.WHITE);prompt.setTextSize(17);prompt.setPadding(20,24,20,24);root.addView(prompt);
        SurfaceView preview=new SurfaceView(this);root.addView(preview,new LinearLayout.LayoutParams(-1,0,1));
        Button cancel=new Button(this);cancel.setText("Cancel");cancel.setAllCaps(false);cancel.setOnClickListener(v->finish());root.addView(cancel);
        setContentView(root);preview.getHolder().addCallback(this);
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
            camera.setPreviewDisplay(holder);camera.setPreviewCallback(this);camera.startPreview();
        } catch(Exception e){release();prompt.setText("Camera could not open. Go back and choose a QR photo instead.");}
    }
    public void surfaceChanged(SurfaceHolder h,int format,int w,int height) {}
    public void surfaceDestroyed(SurfaceHolder h){release();}
    public void onPreviewFrame(byte[] data,Camera c) {
        if(finished||System.currentTimeMillis()-lastFrame<300||!busy.compareAndSet(false,true))return;
        lastFrame=System.currentTimeMillis();byte[] frame=Arrays.copyOf(data,width*height);int w=width,h=height;
        decode.execute(()->{
            try {
                String qr=QrDecoder.decode(new PlanarYUVLuminanceSource(frame,w,h,0,0,w,h,false));
                if(!finished){finished=true;runOnUiThread(()->{setResult(RESULT_OK,new Intent().putExtra("qr",qr));finish();});}
            } catch(Exception ignored) {} finally {busy.set(false);}
        });
    }
    private void release(){if(camera!=null){camera.setPreviewCallback(null);camera.stopPreview();camera.release();camera=null;}}
    protected void onResume(){super.onResume();finished=false;}
    protected void onPause(){finished=true;release();super.onPause();}
    protected void onDestroy(){decode.shutdownNow();super.onDestroy();}
}
