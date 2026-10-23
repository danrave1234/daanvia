package net.drave.atrdiagnostics;
import org.junit.Test;
import static org.junit.Assert.*;
public class LocalCommandsTest {
    @Test public void recognisesTheDocumentedBetaVocabulary(){assertEquals(LocalCommands.Action.REPEAT,LocalCommands.match("Hey Daanvia, repeat the next instruction."));assertEquals(LocalCommands.Action.ETA,LocalCommands.match("How long until I arrive?"));assertEquals(LocalCommands.Action.DISTANCE,LocalCommands.match("How far to go?"));assertEquals(LocalCommands.Action.MUTE,LocalCommands.match("Please mute guidance"));assertEquals(LocalCommands.Action.UNMUTE,LocalCommands.match("Unmute guidance"));}
    @Test public void mutingNeedsItsOwnConfirmation(){assertEquals(LocalCommands.Action.MUTE,LocalCommands.match("quiet"));assertEquals(LocalCommands.Action.CONFIRM_MUTE,LocalCommands.match("confirm mute"));assertEquals(LocalCommands.Action.UNKNOWN,LocalCommands.match("don't mute"));}
    @Test public void stoppingAndConfirmationAreSeparate(){assertEquals(LocalCommands.Action.STOP,LocalCommands.match("End ride"));assertEquals(LocalCommands.Action.CONFIRM_STOP,LocalCommands.match("confirm stop"));assertEquals(LocalCommands.Action.CANCEL,LocalCommands.match("cancel"));}
    @Test public void negationsAndIncidentalWordsNeverTriggerStop(){for(String text:new String[]{"don't end ride","Do not stop navigation","Never stop my ride","I saw a stop sign","turn left then end ride"})assertEquals(text,LocalCommands.Action.UNKNOWN,LocalCommands.match(text));}
    @Test public void trafficRequestsCannotInventARouteOrAnEstimate(){assertEquals(LocalCommands.Action.TRAFFIC,LocalCommands.match("Avoid traffic"));assertEquals(LocalCommands.Action.UNKNOWN,LocalCommands.match("Navigate through the private gate"));}
}
