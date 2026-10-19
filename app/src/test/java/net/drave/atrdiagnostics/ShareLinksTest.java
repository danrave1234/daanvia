package net.drave.atrdiagnostics;
import org.junit.Test;import static org.junit.Assert.*;
public class ShareLinksTest {
    @Test public void shortLinkKeepsSharedNameAndNeedsOneLookup(){ShareLinks.Place p=ShareLinks.parse("Balay ni Drave\nhttps://maps.app.goo.gl/AbC123xyz");assertNotNull(p);assertFalse(p.located());assertEquals("Balay ni Drave",p.name);assertEquals("https://maps.app.goo.gl/AbC123xyz",p.unresolved);}
    @Test public void placeUrlPrefersPinOverViewport(){ShareLinks.Place p=ShareLinks.parse("https://www.google.com/maps/place/Balay+ni+Drave/@10.30,123.88,17z/data=!3m1!4b1!4m6!3m5!1s0x33a999:0x1!8m2!3d10.312345!4d123.891234!16s");assertTrue(p.located());assertEquals("Balay ni Drave",p.name);assertEquals(10.312345,p.lat,1e-9);assertEquals(123.891234,p.lon,1e-9);}
    @Test public void geoUriWithLabel(){ShareLinks.Place p=ShareLinks.parse("geo:10.3157,123.8854?q=10.3157,123.8854(Balay%20ni%20Drave)");assertTrue(p.located());assertEquals("Balay ni Drave",p.name);assertEquals(123.8854,p.lon,1e-9);}
    @Test public void queryUrlAndPlainCoordinates(){assertEquals(10.31,ShareLinks.parse("https://maps.google.com/?q=10.31,123.89").lat,1e-9);ShareLinks.Place p=ShareLinks.parse("Home 10.3157, 123.8854");assertTrue(p.located());assertEquals("Home",p.name);}
    @Test public void consentInterstitialIsUnwrapped(){ShareLinks.Place p=ShareLinks.parse("https://consent.google.com/m?continue=https%3A%2F%2Fwww.google.com%2Fmaps%2Fplace%2FBalay%2Bni%2BDrave%2F%4010.3%2C123.9%2C17z%2Fdata%3D!3d10.3111!4d123.8999&gl=PH");assertTrue(p.located());assertEquals(10.3111,p.lat,1e-9);assertEquals("Balay ni Drave",p.name);}
    @Test public void textWithoutPlaceIsRejected(){assertNull(ShareLinks.parse("check this out"));assertNull(ShareLinks.parse("  "));}
}
