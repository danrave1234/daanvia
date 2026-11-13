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
 * Notifications on the bike display: while Daanvia is on the dashboard, an incoming call, a new message or a social
 * app's notification shows as a short card over the map, so the rider can see who it is without taking out the phone.
 * The rider picks how much in Settings > Bike display: calls, chats and social apps, or every app. Needs Android's
 * Notification access. Nothing is stored or sent anywhere; the card is only drawn on the bike display (DashboardRenderer).
 */
public final class RideNotifications extends NotificationListenerService {
    static final long SHOW_MS = 8000, CALL_MS = 30000;

    /** What shows: calls, chats and social apps (the default once on), or every app's notifications. */
    static final int SOCIAL = 0, EVERYTHING = 1;
    static boolean enabled(Context c) { return c.getSharedPreferences("ui", 0).getBoolean("tft_notifications", false); }
    static int scope(Context c) { return c.getSharedPreferences("ui", 0).getInt("tft_notifications_scope", SOCIAL); }
    static void setScope(Context c, int scope) { c.getSharedPreferences("ui", 0).edit().putInt("tft_notifications_scope", scope).apply(); }
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
        // An ongoing notification that isn't a call (music, navigation, a download) is never news.
        if (!call && (n.flags & Notification.FLAG_ONGOING_EVENT) != 0) return;
        if (!call && !wanted(scope(this), Notification.CATEGORY_MESSAGE.equals(n.category), sbn.getPackageName(), n.category, importance(sbn))) return;
        String title = text(x.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE));
        if (title.isEmpty()) title = text(x.getCharSequence(Notification.EXTRA_TITLE));
        String body = call ? "Incoming call" : lastMessage(x);
        if (body.isEmpty()) body = text(x.getCharSequence(Notification.EXTRA_BIG_TEXT));
        if (body.isEmpty()) body = text(x.getCharSequence(Notification.EXTRA_TEXT));
        if (title.isEmpty() && body.isEmpty()) return;
        // Apps post the same notification again without anything new (read on another device, a sync): shown once.
        if (sbn.getKey().equals(s.popupKey) && body.equals(s.popupText) && title.equals(s.popupTitle)) return;
        s.popup(app(sbn.getPackageName()), icon(sbn.getPackageName()), avatar(n), color(sbn.getPackageName(), n.color), title, body, call, sbn.getKey(), SystemClock.elapsedRealtime() + (call ? CALL_MS : SHOW_MS));
    }

    /** How loudly Android would announce it (silent ones score below IMPORTANCE_DEFAULT). */
    private int importance(StatusBarNotification sbn) {
        try { Ranking r = new Ranking(); if (getCurrentRanking().getRanking(sbn.getKey(), r)) return r.getImportance(); } catch (Exception ignored) {}
        return android.app.NotificationManager.IMPORTANCE_DEFAULT;
    }
    /**
     * Whether a notification that isn't a call belongs on the bike. Calls, chats and social: a message, or anything from
     * a chat or social app. Everything: any app, except what Android itself shows silently, and progress, music, system
     * and navigation chatter, which would only take the rider's eyes for nothing.
     */
    static boolean wanted(int scope, boolean message, String pkg, String category, int importance) {
        if (message || social(pkg)) return true;
        if (scope != EVERYTHING) return false;
        if (importance < android.app.NotificationManager.IMPORTANCE_DEFAULT) return false;
        if (pkg.equals("android") || pkg.startsWith("com.android.systemui") || pkg.startsWith("com.android.providers")) return false;
        return category == null || !(category.equals(Notification.CATEGORY_PROGRESS) || category.equals(Notification.CATEGORY_TRANSPORT)
            || category.equals(Notification.CATEGORY_SERVICE) || category.equals(Notification.CATEGORY_SYSTEM) || category.equals(Notification.CATEGORY_NAVIGATION)
            || category.equals(Notification.CATEGORY_STATUS) || category.equals(Notification.CATEGORY_STOPWATCH));
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

    /** Chat and social apps, by the name the card shows. "Phone" apps are only for naming a call's card. */
    private static final String[][] APPS = {
        {"com.facebook.orca", "Messenger"}, {"com.facebook.mlite", "Messenger"}, {"com.facebook.katana", "Facebook"}, {"com.facebook.lite", "Facebook"},
        {"com.instagram.android", "Instagram"}, {"com.instagram.barcelona", "Threads"}, {"com.zhiliaoapp.musically", "TikTok"}, {"com.ss.android.ugc.trill", "TikTok"},
        {"com.twitter.android", "X"}, {"com.snapchat.android", "Snapchat"}, {"com.whatsapp", "WhatsApp"}, {"com.whatsapp.w4b", "WhatsApp"},
        {"com.viber.voip", "Viber"}, {"org.telegram.messenger", "Telegram"}, {"org.thunderdog.challegram", "Telegram"}, {"org.thoughtcrime.securesms", "Signal"},
        {"jp.naver.line.android", "LINE"}, {"com.tencent.mm", "WeChat"}, {"com.discord", "Discord"}, {"com.linkedin.android", "LinkedIn"},
        {"com.reddit.frontpage", "Reddit"}, {"com.google.android.apps.messaging", "Messages"}, {"com.android.mms", "Messages"},
        {"com.samsung.android.messaging", "Messages"}, {"com.google.android.dialer", "Phone"}, {"com.android.incallui", "Phone"},
        {"com.samsung.android.incallui", "Phone"}, {"com.android.dialer", "Phone"},
    };
    /** A chat or social app: one of the list, or any texting app by its package name. */
    static boolean social(String pkg) { for (String[] a : APPS) if (a[0].equals(pkg)) return !"Phone".equals(a[1]); return pkg.contains("mms") || pkg.contains("messaging"); }
    /** The last app's icon, kept: a chat sends many messages in a row, and drawing an icon into a bitmap isn't free. */
    private String iconFor; private android.graphics.Bitmap iconKept;
    private android.graphics.Bitmap icon(String pkg) {
        if (!pkg.equals(iconFor)) { iconFor = pkg; iconKept = iconOf(getPackageManager(), pkg); }
        return iconKept;
    }
    /** The sender's photo: chat apps put it on the notification as its large icon. Null when there is none. */
    private android.graphics.Bitmap avatar(Notification n) {
        try {
            android.graphics.drawable.Icon large = n.getLargeIcon(); if (large == null) return null;
            android.graphics.drawable.Drawable d = large.loadDrawable(this); if (d == null) return null;
            android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(112, 112, android.graphics.Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, 112, 112); d.draw(new android.graphics.Canvas(b)); return b;
        } catch (Exception | OutOfMemoryError e) { return null; }
    }
    /** The colours these apps are known by; others use the colour their notification asks for. */
    private static final Object[][] COLORS = {
        {"Messenger", 0xFF0084FF}, {"Facebook", 0xFF1877F2}, {"Instagram", 0xFFE4405F}, {"Threads", 0xFFFFFFFF}, {"TikTok", 0xFFFE2C55},
        {"X", 0xFFFFFFFF}, {"Snapchat", 0xFFFFFC00}, {"WhatsApp", 0xFF25D366}, {"Viber", 0xFF7360F2}, {"Telegram", 0xFF26A5E4},
        {"Signal", 0xFF3A76F0}, {"LINE", 0xFF06C755}, {"WeChat", 0xFF07C160}, {"Discord", 0xFF5865F2}, {"LinkedIn", 0xFF0A66C2},
        {"Reddit", 0xFFFF4500}, {"Messages", 0xFF1A73E8}, {"Phone", 0xFF34A853},
    };
    /** The app's colour for its card: its known brand colour, else the one its notification carries, else 0 (none). */
    static int color(String pkg, int notificationColor) {
        for (String[] a : APPS) if (a[0].equals(pkg)) for (Object[] c : COLORS) if (c[0].equals(a[1])) return (Integer) c[1];
        return notificationColor == 0 ? 0 : notificationColor | 0xFF000000;
    }
    /** An app's own icon (the one on the home screen) for its card, 112 px square; null if Android won't give it. */
    static android.graphics.Bitmap iconOf(android.content.pm.PackageManager pm, String pkg) {
        try {
            android.graphics.drawable.Drawable d = pm.getApplicationIcon(pkg);
            android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(112, 112, android.graphics.Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, 112, 112); d.draw(new android.graphics.Canvas(b)); return b;
        } catch (Exception | OutOfMemoryError e) { return null; }
    }
    /** For the layout editor's sample card: the first chat or social app on this phone, as {package, name}; null if none. */
    static String[] sampleApp(android.content.pm.PackageManager pm) {
        for (String[] a : APPS) if (!"Phone".equals(a[1])) try { pm.getApplicationInfo(a[0], 0); return a; } catch (Exception notInstalled) { /* next */ }
        return null;
    }
    private String app(String pkg) {
        for (String[] a : APPS) if (a[0].equals(pkg)) return a[1];
        try { return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0)).toString(); } catch (Exception e) { return ""; }
    }
}
