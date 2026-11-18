package net.drave.atrdiagnostics;
import org.junit.Test;import static org.junit.Assert.*;
public class AppUpdateTest {
    @Test public void phonesThatBreakInstallSessionsUseTheirOwnInstaller(){
        for(String[] p:new String[][]{{"Xiaomi","Redmi"},{"Xiaomi","POCO"},{"vivo","iQOO"},{"OPPO","OPPO"},{"realme","realme"},{"OnePlus","OnePlus"},{"HUAWEI","HUAWEI"},{"HONOR","HONOR"},{"TECNO MOBILE LIMITED","TECNO"},{"INFINIX MOBILITY LIMITED","Infinix"},{"Meizu","meizu"}})assertTrue(p[0],AppUpdate.ownInstallerFirst(p[0],p[1]));
        for(String[] p:new String[][]{{"Google","google"},{"samsung","samsung"},{"motorola","motorola"},{"Nothing","Nothing"}})assertFalse(p[0],AppUpdate.ownInstallerFirst(p[0],p[1]));}
    @Test public void aFailedSessionGoesToThePhonesOwnInstallerUnlessTheRiderCancelled(){
        int aborted=android.content.pm.PackageInstaller.STATUS_FAILURE_ABORTED;
        assertEquals(AppUpdate.CANCELLED,AppUpdate.afterFailure(aborted,true));                       // the rider said no to the prompt
        assertEquals(AppUpdate.TRY_OWN_INSTALLER,AppUpdate.afterFailure(aborted,false));              // "aborted" with no prompt ever shown: the phone's doing
        assertEquals(AppUpdate.NO_SPACE,AppUpdate.afterFailure(android.content.pm.PackageInstaller.STATUS_FAILURE_STORAGE,true));
        for(int status:new int[]{android.content.pm.PackageInstaller.STATUS_FAILURE,android.content.pm.PackageInstaller.STATUS_FAILURE_BLOCKED,android.content.pm.PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,android.content.pm.PackageInstaller.STATUS_FAILURE_CONFLICT})
            assertEquals(AppUpdate.TRY_OWN_INSTALLER,AppUpdate.afterFailure(status,true));}
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
