package net.drave.atrdiagnostics;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.util.*;

import static net.drave.atrdiagnostics.Gabi.Role.*;

/**
 * Settings › Bike display › Notifications on the bike › Choose apps: which apps' notifications reach the bike, and, from
 * what came up lately, one kind of an app's notifications to leave off (Messenger's "Chat heads active", say) while the
 * app's messages still show. Only package names and channel ids are kept; what notifications said is never saved.
 */
public final class NotifyAppsActivity extends Activity {
    private Gabi.Kit k; private LinearLayout recentCard, recentList, appList, kindsCard, kindsList;

    public void onCreate(Bundle state) {
        super.onCreate(state);
        NavSession s = NavSession.get(this);
        k = new Gabi.Kit(this, new Gabi.Themer(Gabi.resolve(this, s.lat, s.lon, s.fixAt > 0)));
        GabiPage page = new GabiPage(this, k, "Notifications on the bike");

        // What came up on the bike lately (this run of the app), with a way to leave that kind off.
        recentCard = GabiPage.card(k);
        LinearLayout head = k.row(4); head.addView(k.label("Lately on the bike"), new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(k.info("Hide one kind of an app's notifications, like Messenger's Chat heads, and its messages still show. The list is kept only while Daanvia is open."));
        recentCard.addView(head); recentList = k.column(0); recentCard.addView(recentList);
        page.content.addView(recentCard);

        LinearLayout apps = GabiPage.card(k);
        apps.addView(k.label("Apps"));
        apps.addView(k.text("An app turned off keeps its notifications on the phone only.", 13, 500, MUTED));
        appList = k.column(0); apps.addView(appList);
        page.content.addView(apps);

        kindsCard = GabiPage.card(k);
        kindsCard.addView(k.label("Kinds turned off"));
        kindsList = k.column(0); kindsCard.addView(kindsList);
        page.content.addView(kindsCard);
    }
    protected void onResume() { super.onResume(); paint(); }

    private void paint() {
        Set<String> off = RideNotifications.hidden(this);
        PackageManager pm = getPackageManager();

        recentList.removeAllViews();
        List<RideNotifications.Shown> recent = RideNotifications.recent();
        for (RideNotifications.Shown c : recent) {
            String key = c.pkg + "|" + c.channel; boolean hidden = off.contains(key);
            TextView act = k.text(hidden ? "Show again" : "Hide these", 13.5f, 700, hidden ? MUTED : ACCENT);
            act.setPadding(k.dp(12), k.dp(10), 0, k.dp(10));
            Gabi.press(act, () -> { RideNotifications.setHidden(this, key, !hidden); paint(); });
            recentList.addView(row(c.pkg, c.title, c.app + " · " + RideNotifications.kind(c.channel) + " · " + ago(c.at), act));
        }
        recentCard.setVisibility(recent.isEmpty() ? View.GONE : View.VISIBLE);

        // Installed chat and social apps first, then other apps seen sending news; Everything needs to be on for those.
        appList.removeAllViews();
        boolean everything = RideNotifications.scope(this) == RideNotifications.EVERYTHING;
        LinkedHashMap<String, String> listed = new LinkedHashMap<>();
        for (String[] a : RideNotifications.installedSocial(pm)) listed.put(a[0], a[1]);
        TreeMap<String, String> others = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String pkg : RideNotifications.seen(this)) if (!listed.containsKey(pkg)) { String name = label(pm, pkg); if (name != null) others.put(name, pkg); }
        for (Map.Entry<String, String> e : others.entrySet()) listed.put(e.getValue(), e.getKey());
        for (Map.Entry<String, String> e : listed.entrySet()) {
            String pkg = e.getKey(); boolean social = RideNotifications.social(pkg);
            FrameLayout sw = k.toggle(!off.contains(pkg), on -> { RideNotifications.setHidden(this, pkg, !on); });
            appList.addView(row(pkg, e.getValue(), social || everything ? null : "Shows with Everything on", sw));
        }
        if (listed.isEmpty()) appList.addView(k.text("Apps show here once they send a notification.", 13, 500, MUTED));

        kindsList.removeAllViews(); int kinds = 0;
        for (String key : new TreeSet<>(off)) {
            int bar = key.indexOf('|'); if (bar < 0) continue; kinds++;
            String pkg = key.substring(0, bar), name = label(pm, pkg);
            TextView act = k.text("Show again", 13.5f, 700, ACCENT); act.setPadding(k.dp(12), k.dp(10), 0, k.dp(10));
            Gabi.press(act, () -> { RideNotifications.setHidden(this, key, false); paint(); });
            kindsList.addView(row(pkg, name == null ? pkg : name, RideNotifications.kind(key.substring(bar + 1)), act));
        }
        kindsCard.setVisibility(kinds == 0 ? View.GONE : View.VISIBLE);
    }

    /** An app's row: its icon, a title, an optional second line, and a control at the end. */
    private LinearLayout row(String pkg, String title, String sub, View end) {
        LinearLayout r = k.row(12); r.setPadding(0, k.dp(9), 0, k.dp(9)); r.setMinimumHeight(k.dp(56)); r.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this); android.graphics.Bitmap b = RideNotifications.iconOf(getPackageManager(), pkg);
        if (b != null) icon.setImageBitmap(b); r.addView(icon, new LinearLayout.LayoutParams(k.dp(36), k.dp(36)));
        LinearLayout col = k.column(2);
        col.addView(k.single(k.text(title, 15, 600, INK)));
        if (sub != null) col.addView(k.single(k.text(sub, 12, 500, MUTED)));
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        if (end != null) r.addView(end);
        return r;
    }
    private static String label(PackageManager pm, String pkg) {
        try { return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(); } catch (Exception uninstalled) { return null; }
    }
    private static String ago(long at) {
        long min = Math.max(0, (System.currentTimeMillis() - at) / 60000);
        return min < 1 ? "just now" : min < 60 ? min + " min ago" : (min / 60) + " h ago";
    }
    public void finish() { super.finish(); overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out); }
}
