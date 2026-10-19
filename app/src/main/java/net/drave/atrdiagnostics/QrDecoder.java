package net.drave.atrdiagnostics;

import android.graphics.Bitmap;
import com.google.zxing.*;

public final class QrDecoder {
    public static String decode(LuminanceSource source) throws NotFoundException {
        return QrScan.decode(source);
    }
    public static String decodeBitmap(Bitmap bitmap) throws NotFoundException {
        int w=bitmap.getWidth(),h=bitmap.getHeight(); int[] pixels=new int[w*h];
        bitmap.getPixels(pixels,0,w,0,0,w,h);
        return QrScan.decodeImage(w,h,pixels);
    }
}
