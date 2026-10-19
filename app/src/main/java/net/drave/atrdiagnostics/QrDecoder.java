package net.drave.atrdiagnostics;

import android.graphics.Bitmap;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;
import com.google.zxing.*;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class QrDecoder {
    public static String decode(LuminanceSource source) throws NotFoundException {
        return QrScan.decode(source);
    }
    /** A QR photo: ML Kit first (any angle, blur, glare), then ZXing, which also reads light-on-dark codes. Background thread only. */
    public static String decodeBitmap(Bitmap bitmap) throws NotFoundException {
        BarcodeScanner scanner = BarcodeScanning.getClient(new BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build());
        try {
            List<Barcode> codes = Tasks.await(scanner.process(InputImage.fromBitmap(bitmap, 0)), 10, TimeUnit.SECONDS);
            for (Barcode b : codes) if (b.getRawValue() != null && !b.getRawValue().isEmpty()) { android.util.Log.i("Daanvia", "QR photo read by ML Kit"); return b.getRawValue(); }
            android.util.Log.i("Daanvia", "ML Kit found no QR in the photo");
        } catch (Exception mlKitUnavailable) {
            android.util.Log.w("Daanvia", "ML Kit unavailable, using ZXing", mlKitUnavailable);
        } finally { scanner.close(); }
        int w=bitmap.getWidth(),h=bitmap.getHeight(); int[] pixels=new int[w*h];
        bitmap.getPixels(pixels,0,w,0,0,w,h);
        return QrScan.decodeImage(w,h,pixels);
    }
}
