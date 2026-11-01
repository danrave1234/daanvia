package net.drave.atrdiagnostics;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;

/**
 * Daanvia's two ongoing notifications (the ride, the bike display). Normal importance, not low: low ones go under
 * "Silent", collapsed, and many phones hide them on the lock screen, which hid the Reconnect and Exit buttons the rider
 * needs without unlocking. No sound and no vibration, so they still never make noise.
 */
final class NotifyChannels {
    private NotifyChannels() {}
    static final String RIDE = "daanvia-ride", DISPLAY = "daanvia-display";

    static void create(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class); if (nm == null) return;
        nm.createNotificationChannel(channel(RIDE, "Ride"));
        nm.createNotificationChannel(channel(DISPLAY, "Bike display"));
        // The old low-importance channels (until 0.9.16).
        nm.deleteNotificationChannel("daanvia-nav"); nm.deleteNotificationChannel("atr-stream");
    }
    private static NotificationChannel channel(String id, String name) {
        NotificationChannel ch = new NotificationChannel(id, name, NotificationManager.IMPORTANCE_DEFAULT);
        ch.setSound(null, null); ch.enableVibration(false); ch.setShowBadge(false);
        ch.setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC);
        return ch;
    }
}
