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
        assertTrue(RideNotifications.wanted(scope, true, "com.some.bank", Notification.CATEGORY_MESSAGE, LOUD));    // any app's message
        assertTrue(RideNotifications.wanted(scope, false, "com.facebook.orca", null, LOUD));                        // Messenger
        assertTrue(RideNotifications.wanted(scope, false, "com.facebook.katana", Notification.CATEGORY_SOCIAL, LOUD)); // Facebook
        assertTrue(RideNotifications.wanted(scope, false, "com.zhiliaoapp.musically", null, SILENT));                // TikTok
        assertFalse(RideNotifications.wanted(scope, false, "com.shopee.ph", Notification.CATEGORY_PROMO, LOUD));
        assertFalse(RideNotifications.wanted(scope, false, "com.google.android.gm", Notification.CATEGORY_EMAIL, LOUD));
    }
    @Test public void everythingAddsOtherAppsButNotBackgroundNoise() {
        int scope = RideNotifications.EVERYTHING;
        assertTrue(RideNotifications.wanted(scope, false, "com.shopee.ph", Notification.CATEGORY_PROMO, LOUD));
        assertTrue(RideNotifications.wanted(scope, false, "com.google.android.gm", Notification.CATEGORY_EMAIL, LOUD));
        assertTrue(RideNotifications.wanted(scope, false, "com.gcash.app", null, LOUD));
        assertFalse(RideNotifications.wanted(scope, false, "com.some.app", null, SILENT));                            // shown silently by Android
        assertFalse(RideNotifications.wanted(scope, false, "com.spotify.music", Notification.CATEGORY_TRANSPORT, LOUD));
        assertFalse(RideNotifications.wanted(scope, false, "com.android.vending", Notification.CATEGORY_PROGRESS, LOUD));
        assertFalse(RideNotifications.wanted(scope, false, "android", Notification.CATEGORY_SYSTEM, LOUD));
        assertFalse(RideNotifications.wanted(scope, false, "com.android.systemui", null, LOUD));
    }
    @Test public void phoneAppsAreNotSocial() {
        assertFalse(RideNotifications.social("com.google.android.dialer"));
        assertTrue(RideNotifications.social("com.google.android.apps.messaging"));
        assertTrue(RideNotifications.social("com.instagram.android"));
    }
}
