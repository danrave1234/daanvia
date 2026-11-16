package net.drave.atrdiagnostics;

import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** The arrow on the route line or at the real spot, the way onto the route, and when to reroute (RouteSnap). */
public class RouteSnapTest {
    private static final double NONE = Double.NaN;

    // ---------------------------------------------------------------- arrow on the line or at the real spot
    @Test public void ridingAlongTheRoadPutsTheArrowOnIt() {
        RouteSnap s = new RouteSnap();
        assertTrue("curb lane, riding along", s.update(9, 5, 6, 10));
    }
    @Test public void stoppedAtAPumpTwentyMetresOffStaysAtTheRealSpot() {
        RouteSnap s = new RouteSnap();
        for (int i = 0; i < 10; i++) assertFalse(s.update(20, NONE, 5, 0));
    }
    @Test public void aPumpFifteenMetresOffIsNotTheRoadWhenStill() {
        RouteSnap s = new RouteSnap();
        for (int i = 0; i < 5; i++) assertFalse(s.update(15, NONE, 8, 0.3f));
    }
    @Test public void stoppedRightOnTheLineSnapsAtOnce() {
        RouteSnap s = new RouteSnap();
        assertTrue(s.update(3, NONE, 5, 0));
    }
    @Test public void turningIntoAStationLeavesTheLineAfterTwoFixes() {
        RouteSnap s = new RouteSnap();
        assertTrue(s.update(2, 0, 5, 9));
        // Slowing and turning in: 12 m off, heading 70 degrees off the road.
        assertTrue("one fix is not enough", s.update(12, 70, 5, 3));
        assertFalse(s.update(16, 80, 5, 2));
    }
    @Test public void farOffLeavesTheLineAtOnce() {
        RouteSnap s = new RouteSnap();
        assertTrue(s.update(2, 0, 5, 9));
        assertFalse(s.update(40, NONE, 5, 0));
    }
    @Test public void aSingleBouncedFixDoesNotMoveTheArrow() {
        RouteSnap s = new RouteSnap();
        assertTrue(s.update(4, 0, 5, 9));
        assertTrue("one wide fix", s.update(25, 10, 5, 9));
        assertTrue("back on the road", s.update(5, 0, 5, 9));
        assertTrue(s.update(25, 10, 5, 9));
    }
    @Test public void stoppedAtALightInTheCurbLaneStaysOnTheLine() {
        RouteSnap s = new RouteSnap();
        assertTrue(s.update(6, 0, 5, 8));
        for (int i = 0; i < 20; i++) assertTrue(s.update(11, NONE, 6, 0));
    }
    @Test public void sharpCornerOnTheLineKeepsTheArrow() {
        RouteSnap s = new RouteSnap();
        assertTrue(s.update(2, 0, 5, 8));
        assertTrue(s.update(4, 80, 5, 6));
        assertTrue(s.update(3, 70, 5, 6));
    }
    @Test public void ridingTheOtherWayAlongTheLineShowsTheRealHeading() {
        RouteSnap s = new RouteSnap();
        assertTrue(s.update(2, 0, 5, 8));
        s.update(3, 175, 5, 8);
        assertFalse(s.update(3, 178, 5, 8));
    }
    @Test public void aServiceRoadBesideTheRouteIsNotTheRoute() {
        RouteSnap s = new RouteSnap();
        for (int i = 0; i < 5; i++) assertFalse("parallel service road 22 m off", s.update(22, 5, 5, 8));
    }
    @Test public void leavingTheStationOntoTheRoadSnapsBack() {
        RouteSnap s = new RouteSnap();
        assertFalse(s.update(20, NONE, 5, 0));
        assertFalse("crossing the forecourt towards the road", s.update(12, 90, 5, 3));
        s.update(5, 20, 5, 5);
        assertTrue("on the road, riding its way", s.update(4, 10, 5, 7));
    }
    @Test public void roughFixesChangeNothing() {
        RouteSnap s = new RouteSnap();
        assertTrue(s.update(3, 0, 5, 9));
        assertTrue(s.update(30, NONE, 80, 0));
        assertTrue(s.update(30, NONE, 80, 0));
    }

    // ---------------------------------------------------------------- the way onto the route
    @Test public void stationOnTheRightOfTheRouteMeansTurnRightOut() {
        // Route heading north; the station east of the road, so the rider faces west to leave.
        assertEquals(10, RouteSnap.joinType(270, 0));
    }
    @Test public void stationOnTheLeftMeansTurnLeftOut() {
        assertEquals(15, RouteSnap.joinType(90, 0));
    }
    @Test public void aDrivewayAlongTheRoadJoinsStraight() {
        assertEquals(8, RouteSnap.joinType(20, 0));
    }
    @Test public void aDrivewayAngledBackIsASharpTurn() {
        assertEquals(11, RouteSnap.joinType(200, 0));
        assertEquals(14, RouteSnap.joinType(160, 0));
    }
    @Test public void aWobblingFixKeepsTheJoinTurn() {
        // 28 degrees is "join"; the rider's fix wobbles to 35 (a "right" turn): keep saying the same.
        assertEquals(8, RouteSnap.joinType(332, 0, 8));
        assertEquals(8, RouteSnap.joinType(325, 0, 8));
        assertEquals("clearly past: change", 10, RouteSnap.joinType(310, 0, 8));
        assertEquals(10, RouteSnap.joinType(325, 0, 0));
    }
    @Test public void joinWords() {
        assertEquals("Turn left onto D.M. Cortes Avenue", RouteSnap.joinText(15, "D.M. Cortes Avenue"));
        assertEquals("Turn right", RouteSnap.joinText(10, ""));
        assertEquals("Join Osmeña Boulevard", RouteSnap.joinText(8, "Osmeña Boulevard"));
    }
    @Test public void showsTheWayOntoTheRouteFromAPump() {
        assertTrue(RouteSnap.joining(false, 20, NONE, NONE, 5, 0));
        assertTrue("walking the bike out", RouteSnap.joining(false, 18, 90, 10, 5, 1.2f));
    }
    @Test public void notWhenOnTheRouteOrRidingBesideIt() {
        assertFalse(RouteSnap.joining(true, 20, NONE, NONE, 5, 0));
        assertFalse("8 m off is the road", RouteSnap.joining(false, 8, NONE, NONE, 5, 0));
        assertFalse("service road beside the route", RouteSnap.joining(false, 20, 10, 90, 5, 8));
        assertFalse("riding away into a side street", RouteSnap.joining(false, 30, 90, 170, 5, 8));
        assertFalse("rough fix", RouteSnap.joining(false, 30, NONE, NONE, 60, 0));
    }
    @Test public void theJoinGoesInFrontOfTheNextTurn() throws Exception {
        JSONArray turns = new JSONArray()
            .put(new JSONObject().put("type", 3).put("begin_shape_index", 0).put("street_names", new JSONArray().put("840").put("D.M. Cortes Avenue")))
            .put(new JSONObject().put("type", 10).put("begin_shape_index", 4).put("instruction", "Turn right onto Don Andres Soriano Avenue."))
            .put(new JSONObject().put("type", 4).put("begin_shape_index", 9));
        String street = RouteSnap.street(turns.getJSONObject(RouteSnap.turnAt(turns, 2)));
        assertEquals("the name, not the route number", "D.M. Cortes Avenue", street);
        JSONArray shown = RouteSnap.withJoin(turns, 1, 15, street, 0);
        assertEquals(4, shown.length());
        assertEquals("same index as the next turn", 15, shown.getJSONObject(1).getInt("type"));
        assertEquals("Turn left onto D.M. Cortes Avenue.", shown.getJSONObject(1).getString("verbal_pre_transition_instruction"));
        assertEquals("then the next turn", 10, shown.getJSONObject(2).getInt("type"));
        assertEquals("D.M. Cortes Avenue", Maneuvers.road(shown.getJSONObject(1)));
        assertEquals(3, turns.length());
    }
    @Test public void turnAtFindsTheStretch() throws Exception {
        JSONArray turns = new JSONArray().put(new JSONObject().put("begin_shape_index", 0)).put(new JSONObject().put("begin_shape_index", 5)).put(new JSONObject().put("begin_shape_index", 8));
        assertEquals(0, RouteSnap.turnAt(turns, 4));
        assertEquals(1, RouteSnap.turnAt(turns, 5));
        assertEquals(2, RouteSnap.turnAt(turns, 30));
    }
    @Test public void roadBearingLooksAheadPastKinks() {
        List<double[]> route = Arrays.asList(new double[]{10.30000, 123.90000}, new double[]{10.30001, 123.90000}, new double[]{10.30100, 123.90000});
        double b = RouteSnap.roadBearing(route, 0, route.get(0));
        assertEquals(0, b, 1);
    }

    // ---------------------------------------------------------------- off the route
    @Test public void turnedIntoASideStreetReroutesAfterTwoFixes() {
        assertEquals(2, RouteSnap.offRouteFixes(25, 80, 6, 7, true, 0));
    }
    @Test public void farOffReroutesAfterThree() {
        assertEquals(3, RouteSnap.offRouteFixes(70, 20, 6, 7, true, 0));
    }
    @Test public void waitingAtAPumpNeverReroutes() {
        assertEquals(0, RouteSnap.offRouteFixes(30, NONE, 5, 0, true, 0));
        assertEquals("a big station, standing still", 0, RouteSnap.offRouteFixes(80, NONE, 5, 0, true, 0));
    }
    @Test public void aRouteThatStartedSeventyMetresAwayDoesNotRerouteToItself() {
        assertEquals(0, RouteSnap.offRouteFixes(72, NONE, 5, 1.2f, false, 70));
        assertEquals("riding away from it", 3, RouteSnap.offRouteFixes(130, 90, 5, 6, false, 70));
    }
    @Test public void ridingTheRouteBackwardsReroutes() {
        assertEquals(4, RouteSnap.offRouteFixes(3, 175, 5, 8, true, 0));
    }
    @Test public void roughFixesNeverReroute() {
        assertEquals(0, RouteSnap.offRouteFixes(200, 90, 40, 8, true, 0));
    }
    @Test public void startGapIsMeasuredFromTheRoad() {
        List<double[]> route = Arrays.asList(new double[]{10.3000, 123.9000}, new double[]{10.3010, 123.9000});
        double gap = RouteSnap.gapTo(route, new double[]{0, 111}, 10.3005, 123.9002, 300);
        assertEquals(21.9, gap, 1);
    }
}
