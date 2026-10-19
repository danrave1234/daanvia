package net.drave.atrdiagnostics;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class QrProfile {
    public final String ssid, password, mac, model, action, security;
    private QrProfile(Map<String,String> q) {
        ssid=q.getOrDefault("ssid",""); password=q.getOrDefault("pwd","");
        mac=q.getOrDefault("mac",""); model=q.getOrDefault("modelid","unknown");
        action=q.getOrDefault("action","unknown"); security=q.getOrDefault("auth","WPA2");
        if(ssid.trim().isEmpty()||ssid.getBytes(StandardCharsets.UTF_8).length>32||ssid.matches(".*[\\r\\n\\x00].*"))throw new IllegalArgumentException("SSID missing or invalid");
        if(password.length()<8||password.length()>63)throw new IllegalArgumentException("Unsupported password format");
        if(!mac.isEmpty()&&!mac.matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}"))throw new IllegalArgumentException("Invalid device address");
        String sec=security.toLowerCase(Locale.US);
        if(!(sec.equals("wpa2")||sec.equals("wpa2-psk")||sec.equals("wpa")))throw new IllegalArgumentException("Unsupported network security");
    }
    public static QrProfile parse(String raw) throws Exception {
        if(raw==null||raw.length()>8192)throw new IllegalArgumentException("Missing or oversized QR");
        URI uri=new URI(raw.trim());
        if(!"http".equalsIgnoreCase(uri.getScheme())&&!"https".equalsIgnoreCase(uri.getScheme()))throw new IllegalArgumentException("Expected dashboard pairing URL");
        Map<String,String> params=new HashMap<>();
        if(uri.getRawQuery()!=null)for(String part:uri.getRawQuery().split("&")) {
            String[] pair=part.split("=",2);
            String key=URLDecoder.decode(pair[0],"UTF-8");
            if(params.containsKey(key))throw new IllegalArgumentException("Duplicate QR parameter");
            params.put(key,pair.length==2?URLDecoder.decode(pair[1],"UTF-8"):"");
        }
        return new QrProfile(params);
    }
}
