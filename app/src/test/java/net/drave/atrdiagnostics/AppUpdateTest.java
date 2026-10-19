package net.drave.atrdiagnostics;
import org.junit.Test;import static org.junit.Assert.*;
public class AppUpdateTest {
    @Test public void versionsCompareNumerically(){assertTrue(AppUpdate.compare("v0.5.10","0.5.9")>0);assertTrue(AppUpdate.compare("0.5.0","0.5.0-alpha1")>0);assertEquals(0,AppUpdate.compare("v0.5.0","0.5.0"));assertTrue(AppUpdate.compare("0.6","0.5.9")>0);assertTrue(AppUpdate.compare("0.4.0-alpha1","0.5.0")<0);}
    @Test public void picksTheApkForThisPhonesCpu() throws Exception{
        String json="{\"tag_name\":\"v0.5.1\",\"body\":\"Fixes\",\"assets\":[{\"name\":\"daanvia-0.5.1-x86_64.apk\",\"size\":10,\"browser_download_url\":\"https://x/x86\",\"digest\":\"sha256:aa\"},{\"name\":\"daanvia-0.5.1-arm64-v8a.apk\",\"size\":20,\"browser_download_url\":\"https://x/arm\",\"digest\":\"sha256:bb\"},{\"name\":\"areas-index.json\",\"size\":5,\"browser_download_url\":\"https://x/i\"}]}";
        AppUpdate.Release r=AppUpdate.parse(json,new String[]{"arm64-v8a","armeabi-v7a"});
        assertEquals("0.5.1",r.version);assertEquals("https://x/arm",r.apkUrl);assertEquals("bb",r.sha256);assertEquals(20,r.bytes);
        assertNull(AppUpdate.parse(json,new String[]{"armeabi-v7a"}).apkUrl);}
    @Test public void takesTheSingleApkThatRunsOnEveryPhone() throws Exception{
        String json="{\"tag_name\":\"v0.5.2\",\"assets\":[{\"name\":\"daanvia-0.5.2.apk\",\"size\":30,\"browser_download_url\":\"https://x/all\",\"digest\":\"sha256:cc\"}]}";
        AppUpdate.Release r=AppUpdate.parse(json,new String[]{"armeabi-v7a","armeabi"});
        assertEquals("https://x/all",r.apkUrl);assertEquals("daanvia-0.5.2.apk",r.apkName);assertEquals("0.5.2",Housekeeping.versionOf(r.apkName));}
}
