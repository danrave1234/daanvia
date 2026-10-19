package net.drave.atrdiagnostics;
import org.junit.Test;import static org.junit.Assert.*;
public class HousekeepingTest {
    @Test public void updateApkNamesParseBackToVersions(){
        assertEquals("0.5.1",Housekeeping.versionOf("daanvia-0.5.1-arm64-v8a.apk"));assertEquals("0.6.0",Housekeeping.versionOf("daanvia-0.6.0-x86_64.apk"));
        assertTrue(AppUpdate.compare(Housekeeping.versionOf("daanvia-0.5.0-arm64-v8a.apk"),BuildInfo.VERSION)<=0);}
}
