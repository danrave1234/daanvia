package net.drave.atrdiagnostics;

import android.app.Notification;
import android.app.NotificationManager;
import org.junit.Test;
import static org.junit.Assert.*;

/** Which notifications reach the bike display, for each choice in Settings. */
public class RideNotificationsTest {
    private static final int LOUD = NotificationManager.IMPORTANCE_DEFAULT, SILENT = NotificationManager.IMPORTANCE_LOW;

    @Test public void callsAndSocialShowsChatsAndSocialAppsOnly() {
        int scope = RideNotifications.SOCIAL;
        assertTrue(RideNotifications.wanted(scope, true, "com.some.bank", Notification.CATEGORY_MESSAGE, LOUD, 0));    // any app's message
        assertTrue(RideNotifications.wanted(scope, false, "com.facebook.orca", null, LOUD, 0));                        // Messenger
        assertTrue(RideNotifications.wanted(scope, false, "com.facebook.katana", Notification.CATEGORY_SOCIAL, LOUD, 0)); // Facebook
        assertFalse(RideNotifications.wanted(scope, false, "com.zhiliaoapp.musically", null, SILENT, 0));               // TikTok, but silent on the phone
        assertFalse(RideNotifications.wanted(scope, false, "com.shopee.ph", Notification.CATEGORY_PROMO, LOUD, 0));
        assertFalse(RideNotifications.wanted(scope, false, "com.google.android.gm", Notification.CATEGORY_EMAIL, LOUD, 0));
    }
    @Test public void everythingAddsOtherAppsButNotBackgroundNoise() {
        int scope = RideNotifications.EVERYTHING;
        assertTrue(RideNotifications.wanted(scope, false, "com.shopee.ph", Notification.CATEGORY_PROMO, LOUD, 0));
        assertTrue(RideNotifications.wanted(scope, false, "com.google.android.gm", Notification.CATEGORY_EMAIL, LOUD, 0));
        assertTrue(RideNotifications.wanted(scope, false, "com.gcash.app", null, LOUD, 0));
        assertFalse(RideNotifications.wanted(scope, false, "com.some.app", null, SILENT, 0));                            // shown silently by Android
        assertFalse(RideNotifications.wanted(scope, false, "com.spotify.music", Notification.CATEGORY_TRANSPORT, LOUD, 0));
        assertFalse(RideNotifications.wanted(scope, false, "com.android.vending", Notification.CATEGORY_PROGRESS, LOUD, 0));
        assertFalse(RideNotifications.wanted(scope, false, "android", Notification.CATEGORY_SYSTEM, LOUD, 0));
        assertFalse(RideNotifications.wanted(scope, false, "com.android.systemui", null, LOUD, 0));
    }
    @Test public void statusLinesNeverShowEvenFromChatApps() {
        for (int scope : new int[]{RideNotifications.SOCIAL, RideNotifications.EVERYTHING}) {
            // Messenger's "Chat heads active · 3 conversations": a running service's notice (Android 13+ drops the ongoing flag).
            assertFalse(RideNotifications.wanted(scope, false, "com.facebook.orca", null, LOUD, Notification.FLAG_FOREGROUND_SERVICE));
            assertFalse(RideNotifications.wanted(scope, false, "com.facebook.orca", Notification.CATEGORY_SERVICE, LOUD, 0));
            assertFalse(RideNotifications.wanted(scope, false, "com.facebook.orca", null, SILENT, 0));
            assertFalse(RideNotifications.wanted(scope, false, "com.whatsapp", Notification.CATEGORY_PROGRESS, LOUD, 0));   // sending a video
            assertFalse(RideNotifications.wanted(scope, false, "com.facebook.orca", null, LOUD, Notification.FLAG_ONGOING_EVENT));
            // A real message from the same app still shows.
            assertTrue(RideNotifications.wanted(scope, true, "com.facebook.orca", Notification.CATEGORY_MESSAGE, LOUD, 0));
        }
    }
    @Test public void kindsReadAsWords() {
        assertEquals("chat heads service", RideNotifications.kind("chat_heads_service"));
        assertEquals("direct messages", RideNotifications.kind("directMessages"));
        assertEquals("other", RideNotifications.kind(""));
    }
    @Test public void phoneAppsAreNotSocial() {
        assertFalse(RideNotifications.social("com.google.android.dialer"));
        assertTrue(RideNotifications.social("com.google.android.apps.messaging"));
        assertTrue(RideNotifications.social("com.instagram.android"));
    }
    @Test public void cardsCarryTheAppsColour() {
        assertEquals(0xFF0084FF, RideNotifications.color("com.facebook.orca", 0));          // Messenger's blue, whatever the notification says
        assertEquals(0xFF25D366, RideNotifications.color("com.whatsapp", 0xFF000000));
        assertEquals(0xFF123456, RideNotifications.color("com.some.app", 0x00123456));        // another app: its notification's colour, made opaque
        assertEquals(0, RideNotifications.color("com.some.app", 0));                           // none: the card falls back to Daanvia's accent
    }
}
