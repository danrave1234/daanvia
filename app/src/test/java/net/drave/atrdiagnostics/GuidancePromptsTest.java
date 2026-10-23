package net.drave.atrdiagnostics;
import org.junit.Test;
import static org.junit.Assert.*;

public class GuidancePromptsTest {
    @Test public void fasterTravelGetsEarlierWarning(){
        assertEquals(GuidancePrompts.NONE,new GuidancePrompts().next(1,500,5,8,false,10000));
        assertEquals(GuidancePrompts.ADVANCE,new GuidancePrompts().next(1,500,16,8,false,10000));
    }
    @Test public void stagesProgressWithoutRepeatingOnGpsJitter(){
        GuidancePrompts p=new GuidancePrompts();int early=p.next(1,200,5,8,false,10000);p.spoken(early,10000);
        assertEquals(0,p.next(1,205,5,8,false,20000));
        int prepare=p.next(1,60,5,8,false,21000);assertEquals(GuidancePrompts.PREPARE,prepare);p.spoken(prepare,21000);
        assertEquals(0,p.next(1,100,5,8,false,30000));
        assertEquals(GuidancePrompts.TURN,p.next(1,15,5,8,false,31000));
    }
    @Test public void startingNearTurnSkipsObsoleteAdvancePrompt(){
        GuidancePrompts p=new GuidancePrompts();assertEquals(GuidancePrompts.TURN,p.next(1,15,5,8,false,10000));
        p.spoken(GuidancePrompts.TURN,10000);assertEquals(0,p.next(1,40,5,8,false,20000));
        assertEquals(GuidancePrompts.PREPARE,p.next(2,60,5,8,false,20000));
    }
    @Test public void poorFixDoesNotConsumePromptAndArrivalSpeaksOnce(){
        GuidancePrompts p=new GuidancePrompts();assertEquals(0,p.next(1,50,10,80,false,10000));
        assertEquals(GuidancePrompts.PREPARE,p.next(1,50,10,8,false,10000));
        assertEquals(GuidancePrompts.ARRIVAL,p.next(1,0,0,8,true,20000));p.spoken(GuidancePrompts.ARRIVAL,20000);
        assertEquals(0,p.next(1,0,0,8,true,30000));p.reset();assertEquals(GuidancePrompts.ARRIVAL,p.next(1,0,0,8,true,30000));
    }
    @Test public void focusDeniedPromptCanRetryWithoutBeingLost(){
        GuidancePrompts p=new GuidancePrompts();assertEquals(GuidancePrompts.TURN,p.next(1,15,5,8,false,10000));
        assertEquals(GuidancePrompts.TURN,p.next(1,14,5,8,false,11000));
    }
}
