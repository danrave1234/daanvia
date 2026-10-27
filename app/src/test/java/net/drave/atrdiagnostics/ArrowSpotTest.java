package net.drave.atrdiagnostics;

import static org.junit.Assert.*;
import org.junit.Test;

public class ArrowSpotTest {
    private static final float W = 800, H = 480;
    // Turn card top left, trip card on the right (as on the rider's bike).
    private static final float[][] CARDS = {{16, 14, 336, 230}, {560, 160, 784, 380}};

    @Test public void anOpenSpotIsLeftAlone() {
        float[] at = DashboardRenderer.arrowSpot(400, 400, W, H, CARDS);
        assertEquals(400, at[0], 0.01); assertEquals(400, at[1], 0.01);
    }

    @Test public void anArrowUnderACardMovesBesideItOnTheSameRow() {
        float[] at = DashboardRenderer.arrowSpot(670, 300, W, H, CARDS);
        assertEquals(300, at[1], 0.01);
        assertTrue("left of the trip card", at[0] < 560 - 36);
        for (float[] c : CARDS) assertFalse(at[0] > c[0] && at[0] < c[2] && at[1] > c[1] && at[1] < c[3]);
    }

    @Test public void aRowFullOfCardsMovesTheArrowToAnotherRow() {
        float[][] wide = {{0, 200, 800, 330}, {0, 0, 300, 100}};
        float[] at = DashboardRenderer.arrowSpot(400, 260, W, H, wide);
        assertTrue(at[1] < 200 - 36 || at[1] > 330 + 36);
    }

    @Test public void cardsCoveringEverythingFallBackToTheMiddle() {
        float[][] all = {{0, 0, 800, 480}};
        float[] at = DashboardRenderer.arrowSpot(100, 100, W, H, all);
        assertEquals(400, at[0], 0.01);
    }
}
