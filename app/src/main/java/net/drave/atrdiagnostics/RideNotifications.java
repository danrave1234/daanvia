package net.drave.atrdiagnostics;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * Calls and messages on the bike display: while Daanvia is on the dashboard, an incoming call or a new message (SMS,
 * Messenger, WhatsApp, Viber, Telegram and other chat apps) shows as a short card over the map, so the rider can read
 * who it is without taking out the phone. Needs Android's Notification access, which the rider turns on in Settings ›
 * Bike display. Nothing is stored or sent anywhere; the card is only drawn on the bike display (DashboardRenderer).
 */
public final class RideNotifications extends NotificationListenerService {
    static final long SHOW_MS = 8000, CALL_MS = 30000;

    static boolean enabled(Context c) { return c.getSharedPreferences("ui", 0).getBoolean("tft_notifications", false); }
    static void setEnabled(Context c, boolean on) {
        c.getSharedPreferences("ui", 0).edit().putBoolean("tft_notifications", on).apply();
        if (on) try { requestRebind(new ComponentName(c, RideNotifications.class)); } catch (Exception ignored) {}
    }
    /** Switched off in Daanvia but access still granted: let go, so Android doesn't keep Daanvia running for nothing. */
    @Override public void onListenerConnected() { if (!enabled(this)) try { requestUnbind(); } catch (Exception ignored) {} }
    /** Whether the rider has given Daanvia Notification access in Android's settings. */
    static boolean allowed(Context c) {
        return androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.getPackageName());
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        if (!enabled(this) || !StreamService.navigationActive || getPackageName().equals(sbn.getPackageName())) return;
        Notification n = sbn.getNotification();
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
        boolean call = Notification.CATEGORY_CALL.equals(n.category);
        Bundle x = n.extras;
        NavSession s = NavSession.get(this);
        // An answered call updates the same notification into an ongoing one (a running timer): that ends the card.
        if (call && (x.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)
                || android.os.Build.VERSION.SDK_INT >= 31 && x.getInt(Notification.EXTRA_CALL_TYPE) == Notification.CallStyle.CALL_TYPE_ONGOING)) {
            if (sbn.getKey().equals(s.popupKey)) s.popupUntil = 0;
            return;
        }
        if (!call && !(Notification.CATEGORY_MESSAGE.equals(n.category) || chat(sbn.getPackageName()))) return;
        // An ongoing notification that isn't a call (music, navigation, a download) is never a message.
        if (!call && (n.flags & Notification.FLAG_ONGOING_EVENT) != 0) return;
        String title = text(x.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE));
        if (title.isEmpty()) title = text(x.getCharSequence(Notification.EXTRA_TITLE));
        String body = call ? "Incoming call" : lastMessage(x);
        if (body.isEmpty()) body = text(x.getCharSequence(Notification.EXTRA_TEXT));
        if (title.isEmpty() && body.isEmpty()) return;
        // Apps post the same notification again without anything new (read on another device, a sync): shown once.
        if (sbn.getKey().equals(s.popupKey) && body.equals(s.popupText) && title.equals(s.popupTitle)) return;
        s.popup(app(sbn.getPackageName()), title, body, call, sbn.getKey(), SystemClock.elapsedRealtime() + (call ? CALL_MS : SHOW_MS));
    }

    /** A call that is answered or ends takes its card away at once. */
    @Override public void onNotificationRemoved(StatusBarNotification sbn) {
        // Only a card shown during a ride can need clearing; otherwise touching NavSession would load the whole map.
        if (!StreamService.navigationActive) return;
        NavSession s = NavSession.get(this);
        if (sbn.getKey().equals(s.popupKey) && s.popupCall) s.popupUntil = 0;
    }

    /** The newest message of a messaging-style notification (several messages from one chat come as one notification). */
    private static String lastMessage(Bundle x) {
        Parcelable[] messages = x.getParcelableArray(Notification.EXTRA_MESSAGES);
        if (messages == null || messages.length == 0 || !(messages[messages.length - 1] instanceof Bundle)) return "";
        return text(((Bundle) messages[messages.length - 1]).getCharSequence("text"));
    }
    private static String text(CharSequence c) { return c == null ? "" : c.toString().trim(); }

    private static final String[][] APPS = {
        {"com.facebook.orca", "Messenger"}, {"com.facebook.mlite", "Messenger"}, {"com.whatsapp", "WhatsApp"}, {"com.whatsapp.w4b", "WhatsApp"},
        {"com.viber.voip", "Viber"}, {"org.telegram.messenger", "Telegram"}, {"com.google.android.apps.messaging", "Messages"},
        {"com.android.mms", "Messages"}, {"com.samsung.android.messaging", "Messages"}, {"com.discord", "Discord"},
        {"com.instagram.android", "Instagram"}, {"com.google.android.dialer", "Phone"}, {"com.android.incallui", "Phone"},
        {"com.samsung.android.incallui", "Phone"}, {"com.android.dialer", "Phone"},
    };
    private static boolean chat(String pkg) { for (String[] a : APPS) if (a[0].equals(pkg)) return !"Phone".equals(a[1]); return pkg.contains("mms") || pkg.contains("messaging"); }
    private String app(String pkg) {
        for (String[] a : APPS) if (a[0].equals(pkg)) return a[1];
        try { return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0)).toString(); } catch (Exception e) { return ""; }
    }
}
