package net.drave.atrdiagnostics;

import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import java.util.*;

public final class QrScan {
    public static String decode(LuminanceSource source) throws NotFoundException {
        Map<DecodeHintType,Object> hints=new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER,Boolean.TRUE);
        for(int invert=0;invert<2;invert++) {
            try{return new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(invert==0?source:source.invert())),hints).getText();}
            catch(ReaderException ignored){}
        }
        throw NotFoundException.getNotFoundInstance();
    }
    public static String decodeImage(int width,int height,int[] pixels) throws NotFoundException {
        RGBLuminanceSource source=new RGBLuminanceSource(width,height,pixels);
        try{return decode(source);}catch(NotFoundException ignored){}
        // Overlapping tiles reduce the effect of glare and dark areas away from a small QR.
        for(int[] tile:new int[][]{{1,0,2,2},{0,0,2,2},{1,1,2,2},{0,1,2,2},{1,0,2,3},{0,0,2,3}}) {
            int x=width*tile[0]/3,y=height*tile[1]/3,w=width*tile[2]/3,h=height*tile[3]/3;
            w=Math.min(w,width-x);h=Math.min(h,height-y);
            if(w<=0||h<=0)continue;
            try{return decode(source.crop(x,y,w,h));}catch(NotFoundException ignored){}
        }
        throw NotFoundException.getNotFoundInstance();
    }
}
