package net.drave.atrdiagnostics;

import static org.junit.Assert.*;
import org.junit.Test;

public class GoogleHandoffTest {
    @Test public void onlyGoogleMapsLinksCountAsCopiedPlaces() {
        assertTrue(SearchPanel.googleMapsLink("SM Seaside City Cebu https://maps.app.goo.gl/AbC123"));
        assertTrue(SearchPanel.googleMapsLink("https://www.google.com/maps/place/Ayala+Center+Cebu/@10.31,123.90,17z"));
        assertTrue(SearchPanel.googleMapsLink("geo:10.3157,123.8854"));
        assertFalse(SearchPanel.googleMapsLink("https://youtu.be/xyz"));
        assertFalse(SearchPanel.googleMapsLink("see you at 5"));
    }
}
