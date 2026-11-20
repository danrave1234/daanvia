package net.drave.atrdiagnostics;
import org.junit.Test;import static org.junit.Assert.*;
/** Back from a dropped link, the bike sits on its own screen: when Daanvia asks it for the map (StreamService watchdog). */
public class AskForMapTest {
    private static final long NEVER=Long.MAX_VALUE/2;
    @Test public void asksFiveSecondsIntoASessionWithNoVideo(){
        assertFalse(StreamService.askForMap(0,true,false,true,4000,0,NEVER));    // the bike usually asks within a second
        assertTrue(StreamService.askForMap(0,true,false,true,5000,0,NEVER));
    }
    @Test public void asksTwiceAtMostTwentySecondsApart(){
        assertFalse(StreamService.askForMap(0,true,false,true,15000,1,10000));
        assertTrue(StreamService.askForMap(0,true,false,true,25000,1,20000));
        assertFalse(StreamService.askForMap(0,true,false,true,60000,2,NEVER));
    }
    @Test public void leavesTheBikeAloneOtherwise(){
        assertFalse(StreamService.askForMap(12,true,false,true,9000,0,NEVER));   // video is flowing
        assertFalse(StreamService.askForMap(0,true,true,true,9000,0,NEVER));     // the rider chose the arrows
        assertFalse(StreamService.askForMap(0,false,false,true,9000,0,NEVER));   // a test pattern or mirroring, not directions
        assertFalse(StreamService.askForMap(0,true,false,false,9000,0,NEVER));   // no data channel to ask on yet
    }
}
