package net.drave.atrdiagnostics;
import java.io.*;


import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import java.util.*;

public final class QrTest {
    public static void main(String[] paths)throws Exception {
        int checks=0;
        String valid="https://example.test/pair?ssid=qj-test-network&pwd=88888888&auth=WPA2&mac=02%3A00%3A00%3A00%3A00%3A01&modelid=37501&action=8";
        QrProfile p=QrProfile.parse(valid);
        if(!p.ssid.equals("qj-test-network")||!p.mac.equals("02:00:00:00:00:01")||!p.action.equals("8"))throw new AssertionError();checks++;
        for(String bad:new String[]{"https://example.test/download", "javascript:alert(1)",valid+"&ssid=other",valid.replace("88888888","short"),valid.replace("WPA2","WEP"),valid.replace("qj-test-network","%0A")}) {
            try{QrProfile.parse(bad);throw new AssertionError("Invalid QR accepted");}catch(Exception expected){checks++;}
        }
        System.out.println(checks+" QR parameter checks passed.");
    }
}
