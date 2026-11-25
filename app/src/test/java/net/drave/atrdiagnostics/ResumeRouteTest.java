package net.drave.atrdiagnostics;
import org.junit.Test;import static org.junit.Assert.*;import java.util.concurrent.atomic.AtomicInteger;
/** A ride picked up after the phone closed the app is routed only once the area's roads and a usable fix are in. */
public class ResumeRouteTest {
    @Test public void waitsForTheAreasRoads(){
        // The failure seen after a force-stop: map ready and a sharp fix, but the roads still loading.
        assertFalse(NavSession.resumeCanPlan(true,false,false,1000,8,0));
        assertTrue(NavSession.resumeCanPlan(true,true,false,1000,8,0));}
    @Test public void waitsForTheMapAndAnyRouteInProgress(){
        assertFalse(NavSession.resumeCanPlan(false,true,false,1000,8,0));
        assertFalse(NavSession.resumeCanPlan(true,true,true,1000,8,0));}
    @Test public void needsAFix(){
        assertFalse(NavSession.resumeCanPlan(true,true,false,-1,999,0));
        assertFalse(NavSession.resumeCanPlan(true,true,false,-1,999,NavSession.RESUME_FIX_WAIT_MS*10));}
    @Test public void prefersASharpCurrentFixOverTheLastKnownSpot(){
        // The phone's last known spot, a minute old and drawn rough: not yet.
        assertFalse(NavSession.resumeCanPlan(true,true,false,60_000,150,0));
        assertFalse(NavSession.resumeCanPlan(true,true,false,2000,120,0));
        assertTrue(NavSession.resumeCanPlan(true,true,false,2000,50,0));}
    @Test public void takesARoughFixAfterWaiting(){
        assertTrue(NavSession.resumeCanPlan(true,true,false,60_000,150,NavSession.RESUME_FIX_WAIT_MS));
        // Older than two minutes is never used.
        assertFalse(NavSession.resumeCanPlan(true,true,false,120_000,150,NavSession.RESUME_FIX_WAIT_MS));
        // Waiting never skips the roads.
        assertFalse(NavSession.resumeCanPlan(true,false,false,60_000,150,NavSession.RESUME_FIX_WAIT_MS));}
    @Test public void awaitReturnsOnceReady(){
        AtomicInteger calls=new AtomicInteger();
        assertTrue(NavSession.await(()->calls.incrementAndGet()>=3,5000,1));
        assertEquals(3,calls.get());}
    @Test public void awaitReturnsAtOnceWhenAlreadyReady(){
        long t=System.nanoTime();assertTrue(NavSession.await(()->true,5000,1000));
        assertTrue(System.nanoTime()-t<500_000_000L);}
    @Test public void awaitGivesUpAfterTheTimeout(){
        long t=System.nanoTime();assertFalse(NavSession.await(()->false,50,5));
        long ms=(System.nanoTime()-t)/1_000_000L;assertTrue("waited "+ms+" ms",ms>=50&&ms<2000);}
}
