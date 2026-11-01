package net.drave.atrdiagnostics;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * The buttons on Daanvia's notifications, which work from the lock screen without unlocking the phone:
 *  - Reconnect: drops the bike display link and finds the bike again (the same quiet search auto-connect uses).
 *  - Exit: ends everything Daanvia runs in the background (the ride, the bike display, the searches) and closes the app.
 */
public final class RideActions extends BroadcastReceiver {
    static final String RECONNECT = "net.drave.daanvia.RECONNECT", EXIT = "net.drave.daanvia.EXIT";
    /** Sent to open screens when the rider taps Exit, so they close too. */
    static final String CLOSE_SCREENS = "net.drave.daanvia.CLOSE_SCREENS";

    static PendingIntent intent(Context c, String action, int code) {
        return PendingIntent.getBroadcast(c, code, new Intent(c, RideActions.class).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @Override public void onReceive(Context c, Intent i) {
        if (RECONNECT.equals(i.getAction())) {
            DashboardLink.headless(c).reconnect();
        } else if (EXIT.equals(i.getAction())) {
            NavSession.get(c).stop();
            DashboardLink link = DashboardLink.existing(); if (link != null) link.stop();
            c.stopService(new Intent(c, StreamService.class));
            c.stopService(new Intent(c, NavService.class));
            c.sendBroadcast(new Intent(CLOSE_SCREENS).setPackage(c.getPackageName()));
        }
    }
}
