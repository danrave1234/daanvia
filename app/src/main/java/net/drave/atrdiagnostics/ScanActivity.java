package net.drave.atrdiagnostics;

import android.content.Intent;
import android.graphics.*;
import android.media.Image;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Size;
import android.view.*;
import android.widget.*;
import androidx.activity.ComponentActivity;
import androidx.camera.core.*;
import androidx.camera.core.Camera;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.ZoomSuggestionOptions;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;
import com.google.zxing.PlanarYUVLuminanceSource;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;

/**
 * Dashboard QR scanner. CameraX keeps the code in focus (continuous autofocus, tap to focus, and a fresh focus on
 * the middle every few seconds), and Google's ML Kit reads it at any angle, zooming in on its own when the code
 * is small. ZXing checks every few frames too, for light-on-dark codes. Everything runs on the phone.
 */
public final class ScanActivity extends ComponentActivity {
    private final ExecutorService analysis = Executors.newSingleThreadExecutor();
    private volatile boolean finished;
    private TextView prompt; private PreviewView preview; private FrameLayout root;
    private Camera camera; private BarcodeScanner scanner; private int frames;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());

    public void onCreate(Bundle state) {
        super.onCreate(state);
        Gabi.Kit k = new Gabi.Kit(this, new Gabi.Themer(Gabi.NIGHT));
        Window w = getWindow(); Gabi.edgeToEdge(w); Gabi.barIcons(w, Gabi.NIGHT); w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root = new FrameLayout(this); root.setBackgroundColor(Color.BLACK);
        preview = new PreviewView(this); preview.setScaleType(PreviewView.ScaleType.FILL_CENTER); preview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        root.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        root.addView(new Finder(k), new FrameLayout.LayoutParams(-1, -1));
        prompt = k.text("Point at the dashboard QR. Tap the screen to focus.", 15, 600, Gabi.Role.INK); prompt.setGravity(Gravity.CENTER);
        prompt.setPadding(k.dp(18), k.dp(14), k.dp(18), k.dp(14)); k.glass(prompt, 20);
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP); pl.leftMargin = pl.rightMargin = k.dp(16); root.addView(prompt, pl);
        LinearLayout actions = k.row(10);
        TextView photo = k.ghost("Use a photo", () -> pickPhoto.launch("image/*")); k.glass(photo, 26);
        TextView cancel = k.ghost("Cancel", this::finish); k.glass(cancel, 26);
        actions.addView(photo, new LinearLayout.LayoutParams(0, -2, 1)); actions.addView(cancel, new LinearLayout.LayoutParams(0, -2, 1));
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM); cl.leftMargin = cl.rightMargin = k.dp(16); root.addView(actions, cl);
        Gabi.insets(root, (l, t, r, b, ime) -> { pl.topMargin = t + k.dp(12); cl.bottomMargin = b + k.dp(16); prompt.requestLayout(); actions.requestLayout(); });
        setContentView(root);
        preview.setOnTouchListener((v, e) -> { if (e.getAction() == MotionEvent.ACTION_UP) { focus(e.getX(), e.getY()); v.performClick(); } return true; });
        start();
    }

    private void start() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();
                ResolutionSelector res = new ResolutionSelector.Builder().setResolutionStrategy(new ResolutionStrategy(new Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build();
                Preview show = new Preview.Builder().build(); show.setSurfaceProvider(preview.getSurfaceProvider());
                ImageAnalysis frames = new ImageAnalysis.Builder().setResolutionSelector(res).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
                frames.setAnalyzer(analysis, this::analyze);
                provider.unbindAll();
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, show, frames);
                scanner = BarcodeScanning.getClient(new BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                    .setZoomSuggestionOptions(new ZoomSuggestionOptions.Builder(this::zoom).setMaxSupportedZoomRatio(maxZoom()).build()).build());
                preview.post(this::refocus);
            } catch (Exception e) { prompt.setText("The camera could not open. Tap Use a photo and choose a picture of the dashboard QR."); }
        }, androidx.core.content.ContextCompat.getMainExecutor(this));
    }

    /** ML Kit on every frame; ZXing on every third, which also reads light-on-dark codes. */
    @androidx.annotation.OptIn(markerClass = ExperimentalGetImage.class)
    private void analyze(ImageProxy frame) {
        Image image = frame.getImage(); BarcodeScanner s = scanner;
        if (finished || image == null || s == null) { frame.close(); return; }
        boolean zxing = ++frames % 3 == 0;
        String backup = zxing ? zxing(frame) : null;
        if (backup != null) { done(backup); frame.close(); return; }
        s.process(InputImage.fromMediaImage(image, frame.getImageInfo().getRotationDegrees()))
            .addOnSuccessListener(analysis, codes -> { for (Barcode b : codes) if (b.getRawValue() != null && !b.getRawValue().isEmpty()) { done(b.getRawValue()); break; } })
            .addOnCompleteListener(analysis, t -> frame.close());
    }
    private static String zxing(ImageProxy frame) {
        try {
            ImageProxy.PlaneProxy y = frame.getPlanes()[0]; ByteBuffer buf = y.getBuffer(); int w = frame.getWidth(), h = frame.getHeight(), stride = y.getRowStride();
            byte[] lum = new byte[w * h]; buf.rewind();
            if (stride == w) buf.get(lum, 0, Math.min(lum.length, buf.remaining()));
            else for (int r = 0; r < h; r++) { buf.position(r * stride); buf.get(lum, r * w, Math.min(w, buf.remaining())); }
            // The middle of the frame first, where the finder square is, then the whole frame.
            int side = Math.min(w, h) * 4 / 5;
            try { return QrDecoder.decode(new PlanarYUVLuminanceSource(lum, w, h, (w - side) / 2, (h - side) / 2, side, side, false)); }
            catch (Exception notInMiddle) { return QrDecoder.decode(new PlanarYUVLuminanceSource(lum, w, h, 0, 0, w, h, false)); }
        } catch (Exception e) { return null; }
    }
    /** A screenshot or photo of the dashboard QR, read the same way as the camera. */
    private final androidx.activity.result.ActivityResultLauncher<String> pickPhoto = registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.GetContent(), uri -> {
        if (uri == null) return;
        prompt.setText("Reading the photo…");
        analysis.execute(() -> {
            String qr = null;
            try {
                android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options(); o.inJustDecodeBounds = true;
                try (java.io.InputStream in = getContentResolver().openInputStream(uri)) { android.graphics.BitmapFactory.decodeStream(in, null, o); }
                android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options(); opts.inSampleSize = 1; int max = Math.max(o.outWidth, o.outHeight); while (max / opts.inSampleSize > 2400) opts.inSampleSize *= 2;
                Bitmap bmp; try (java.io.InputStream in = getContentResolver().openInputStream(uri)) { bmp = android.graphics.BitmapFactory.decodeStream(in, null, opts); }
                if (bmp != null) try { qr = QrDecoder.decodeBitmap(bmp); } finally { bmp.recycle(); }
            } catch (Exception e) { android.util.Log.w("Daanvia", "QR photo could not be read", e); }
            String found = qr;
            if (found != null) done(found);
            else runOnUiThread(() -> prompt.setText("No QR code found in that photo. Try a sharper photo, or point the camera at the dashboard."));
        });
    });
    private void done(String qr) {
        if (finished) return; finished = true;
        runOnUiThread(() -> { root.performHapticFeedback(HapticFeedbackConstants.CONFIRM); setResult(RESULT_OK, new Intent().putExtra("qr", qr)); finish(); });
    }

    // ---------------------------------------------------------------- focus and zoom
    private void focus(float x, float y) {
        if (camera == null) return;
        MeteringPoint p = preview.getMeteringPointFactory().createPoint(x, y);
        camera.getCameraControl().startFocusAndMetering(new FocusMeteringAction.Builder(p, FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE).setAutoCancelDuration(3, TimeUnit.SECONDS).build());
    }
    /** Phones that hunt or settle on the background get a fresh focus on the middle every few seconds. */
    private final Runnable refocusLoop = this::refocus;
    private void refocus() {
        if (finished || camera == null) return;
        focus(preview.getWidth() / 2f, preview.getHeight() / 2f);
        ui.removeCallbacks(refocusLoop); ui.postDelayed(refocusLoop, 3500);
    }
    private boolean zoom(float ratio) { Camera c = camera; if (c == null || finished) return false; c.getCameraControl().setZoomRatio(Math.min(ratio, maxZoom())); return true; }
    private float maxZoom() { Camera c = camera; if (c == null) return 1f; ZoomState z = c.getCameraInfo().getZoomState().getValue(); return z == null ? 1f : Math.min(z.getMaxZoomRatio(), 4f); }

    protected void onPause() { ui.removeCallbacks(refocusLoop); super.onPause(); }
    protected void onResume() { super.onResume(); if (!finished && camera != null) preview.post(this::refocus); }
    protected void onDestroy() { finished = true; ui.removeCallbacksAndMessages(null); if (scanner != null) scanner.close(); analysis.shutdown(); super.onDestroy(); }

    /** Dimmed surround with a clear square, corner brackets and a sweeping scan line. */
    private static final class Finder extends View {
        private final Paint dim = new Paint(), line = new Paint(Paint.ANTI_ALIAS_FLAG), sweep = new Paint(Paint.ANTI_ALIAS_FLAG); private final Path hole = new Path(); private final float d;
        Finder(Gabi.Kit k) { super(k.c); d = k.c.getResources().getDisplayMetrics().density; dim.setColor(0x8C000000); line.setColor(0xFFFFB020); line.setStyle(Paint.Style.STROKE); line.setStrokeWidth(4 * d); line.setStrokeCap(Paint.Cap.ROUND); }
        protected void onDraw(Canvas c) {
            float size = Math.min(getWidth(), getHeight()) * 0.72f, l = (getWidth() - size) / 2, t = (getHeight() - size) / 2, r = l + size, b = t + size, arm = 28 * d, rad = 22 * d;
            hole.reset(); hole.setFillType(Path.FillType.EVEN_ODD); hole.addRect(0, 0, getWidth(), getHeight(), Path.Direction.CW); hole.addRoundRect(l, t, r, b, rad, rad, Path.Direction.CW); c.drawPath(hole, dim);
            float breathe = (float)(Math.sin(SystemClock.uptimeMillis() / 420.0) * 3 * d); float L = l - breathe, T = t - breathe, R = r + breathe, B = b + breathe;
            c.drawLine(L, T + arm, L, T + rad, line); c.drawArc(L, T, L + 2 * rad, T + 2 * rad, 180, 90, false, line); c.drawLine(L + rad, T, L + arm, T, line);
            c.drawLine(R - arm, T, R - rad, T, line); c.drawArc(R - 2 * rad, T, R, T + 2 * rad, 270, 90, false, line); c.drawLine(R, T + rad, R, T + arm, line);
            c.drawLine(R, B - arm, R, B - rad, line); c.drawArc(R - 2 * rad, B - 2 * rad, R, B, 0, 90, false, line); c.drawLine(R - rad, B, R - arm, B, line);
            c.drawLine(L + arm, B, L + rad, B, line); c.drawArc(L, B - 2 * rad, L + 2 * rad, B, 90, 90, false, line); c.drawLine(L, B - rad, L, B - arm, line);
            float f = (SystemClock.uptimeMillis() % 2200) / 2200f, y = t + 12 * d + (size - 24 * d) * (f < 0.5f ? f * 2 : 2 - f * 2);
            sweep.setShader(new LinearGradient(l, 0, r, 0, new int[]{0x00FFB020, 0xCCFFB020, 0x00FFB020}, null, Shader.TileMode.CLAMP)); c.drawRect(l + 14 * d, y - 1 * d, r - 14 * d, y + 1 * d, sweep);
            postInvalidateOnAnimation();
        }
    }
}
