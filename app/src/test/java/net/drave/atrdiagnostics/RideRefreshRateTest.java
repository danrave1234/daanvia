package net.drave.atrdiagnostics;
import org.junit.Test;import static org.junit.Assert.*;
/** The ride screen asks fast displays for 60 Hz (RideScreen.rideRefreshRate), and leaves 60 Hz displays alone. */
public class RideRefreshRateTest {
    @Test public void picksSixtyOnAHundredTwentyHertzPhone(){assertEquals(2,RideScreen.sixtyHz(new int[]{1,2},new float[]{120f,60.000004f}));}
    @Test public void picksSixtyOnANinetyHertzPhone(){assertEquals(5,RideScreen.sixtyHz(new int[]{4,5,6},new float[]{90f,59.94f,30f}));}
    @Test public void leavesASixtyHertzPhoneAlone(){assertEquals(0,RideScreen.sixtyHz(new int[]{1},new float[]{60.000004f}));}
    @Test public void noPreferenceWithoutASixtyHertzMode(){assertEquals(0,RideScreen.sixtyHz(new int[]{1,2},new float[]{120f,90f}));}
}
