package net.drave.atrdiagnostics;

import android.app.Activity;
import android.content.Intent;
import android.os.Handler;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;

import static net.drave.atrdiagnostics.Gabi.Role.*;

/**
 * The live ride over the map: the turn card, the ETA bar with End and Blocked, the arrow that moves every frame with the
 * camera following it, and the dark resting screen ("bike mode") shown while the bike display has the directions.
 */
final class RideScreen {
    interface Host {
        /** The home screen is on the ride. */
        boolean riding();
        /** The map's style has loaded, so the camera and arrow can move. */
        boolean mapReady();
        void refresh();
        void notice(String text);
        /** Every frame of a ride, after the arrow moved: the route and the street squares follow it. */
        void onRideFrame(long now, double lat, double lon, int routeFrom);
        /** The frames stopped: the next fix redraws the location dot. */
        void puckStale();
    }

    private final Activity a; private final NavSession s; private final Gabi.Kit k; private final MapView mapView; private final Handler ui; private final HomeLayout lay; private final Host host;
    private MapLibreMap map;
    /** The turn card (with the "Then" chip under it) and the ETA bar, placed by HomeActivity's layout. */
    LinearLayout top, bar;
    private LinearLayout maneuver, thenChip; private ImageView maneuverIcon, thenIcon; private TextView maneuverDist, maneuverStreet, thenText, etaMin, etaMeta, endBtn, blockBtn, simBadge, tftBadge;
    private int shownManeuver = -1; private boolean endArmed, blockArmed;

    RideScreen(Activity a, NavSession s, Gabi.Kit k, FrameLayout root, MapView mapView, Handler ui, HomeLayout lay, Host host) {
        this.a = a; this.s = s; this.k = k; this.mapView = mapView; this.ui = ui; this.lay = lay; this.host = host;
        rideMotion = new RideMotion(s);
        // While the camera follows a ride the rider is always at the camera's focal point, so the arrow is drawn there
        // on screen: no lag behind the map, which updates its own layers a little later. Right above the map, under everything else.
        arrow = new ImageView(a); arrow.setImageBitmap(MapStyle.ridePuck(a)); arrow.setVisibility(View.GONE);
        root.addView(arrow, root.indexOfChild(mapView) + 1, new FrameLayout.LayoutParams(-2, -2));
        build(root); buildBikeMode(root);
    }
    void attach(MapLibreMap m) { map = m; }

    private void build(FrameLayout root) {
        maneuver = k.row(14); maneuver.setPadding(k.dp(14), k.dp(14), k.dp(18), k.dp(14)); k.glass(maneuver, 26);
        FrameLayout tile = new FrameLayout(a); k.fill(tile, 18, ACCENT);
        maneuverIcon = k.icon(IconDrawable.STRAIGHT, 36, ON_ACCENT); ((IconDrawable)maneuverIcon.getDrawable()).stroke(2.6f);
        tile.addView(maneuverIcon, new FrameLayout.LayoutParams(k.dp(36), k.dp(36), Gravity.CENTER));
        maneuver.addView(tile, new LinearLayout.LayoutParams(k.dp(62), k.dp(62)));
        LinearLayout mt = k.column(3);
        maneuverDist = k.num("", 32, 800, INK); maneuverDist.setLetterSpacing(-0.02f); mt.addView(maneuverDist);
        maneuverStreet = k.single(k.text("", 15, 600, INK_SOFT)); mt.addView(maneuverStreet);
        maneuver.addView(mt, new LinearLayout.LayoutParams(0, -2, 1));
        maneuver.setContentDescription("Repeat next direction"); Gabi.press(maneuver, () -> { if (s.active) a.startService(new Intent(a, NavService.class).setAction("repeat")); });
        top = k.column(6); top.setClipChildren(false); top.setClipToPadding(false);
        top.addView(maneuver, new LinearLayout.LayoutParams(-1, -2));
        FrameLayout.LayoutParams ml = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP); ml.leftMargin = ml.rightMargin = k.dp(10);
        root.addView(top, ml); top.setVisibility(View.GONE);

        thenChip = k.row(6); thenChip.setPadding(k.dp(12), k.dp(7), k.dp(14), k.dp(7)); k.glass(thenChip, 14);
        thenChip.addView(k.text("Then", 12.5f, 650, MUTED)); thenIcon = k.icon(IconDrawable.RIGHT, 16, INK); thenChip.addView(thenIcon);
        thenText = k.single(k.text("", 12.5f, 650, INK_SOFT)); thenText.setMaxWidth(k.dp(200)); thenChip.addView(thenText);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2); tl.leftMargin = k.dp(12);
        top.addView(thenChip, tl); thenChip.setVisibility(View.GONE);

        bar = k.row(12); bar.setPadding(k.dp(20), k.dp(10), k.dp(10), k.dp(10)); k.glass(bar, 30);
        LinearLayout et = k.column(3);
        LinearLayout etTop = k.row(8); etaMin = k.num("", 25, 800, INK); etTop.addView(etaMin);
        simBadge = k.chip("DEMO", ACCENT_SOFT, ACCENT); simBadge.setTextSize(10.5f); simBadge.setLetterSpacing(0.08f); etTop.addView(simBadge);
        tftBadge = k.chip("On bike · Map", OK_SOFT, OK); tftBadge.setTextSize(10.5f); etTop.addView(tftBadge);
        // Tap: the bike shows its own screen with the turn arrow and distance (like Carbit's closed map), or the map again.
        tftBadge.setContentDescription("Switch the bike display between the map and turn arrows");
        Gabi.press(tftBadge, () -> {
            boolean own = !StreamService.ownScreen;
            if (!StreamService.showOwnScreen(own)) { host.notice("Connect the bike display first"); return; }
            paintBikeBadge(); host.notice(own ? "Bike shows turn arrows" : "Bike shows the map");
        });
        et.addView(etTop);
        etaMeta = k.num("", 13, 600, MUTED); et.addView(etaMeta);
        bar.addView(et, new LinearLayout.LayoutParams(0, -2, 1));
        endBtn = k.pill("End", 800, DANGER, WHITE, this::onEnd); endBtn.setPadding(k.dp(20), 0, k.dp(20), 0);
        // The offline map can't know about closures: the rider marks the road ahead blocked and gets a way around it.
        blockBtn = k.pill("Blocked", 800, CHIP, INK, this::onBlocked); blockBtn.setPadding(k.dp(16), 0, k.dp(16), 0);
        bar.addView(blockBtn); LinearLayout.LayoutParams bl = (LinearLayout.LayoutParams)blockBtn.getLayoutParams(); bl.rightMargin = k.dp(8); blockBtn.setLayoutParams(bl);
        bar.addView(endBtn);
        FrameLayout.LayoutParams el = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM); el.leftMargin = el.rightMargin = k.dp(10);
        root.addView(bar, el); bar.setVisibility(View.GONE);
    }

    // ---------------------------------------------------------------- showing and painting
    /** Fade the turn card and ETA bar in or out with the ride mode. */
    void show(boolean riding) {
        Gabi.show(top, riding, -k.dp(24)); if (!riding) thenChip.setVisibility(View.GONE);
        Gabi.show(bar, riding, k.dp(24));
    }
    /** "On bike · Map" or "On bike · Arrows" while the bike display is connected. */
    private void paintBikeBadge() {
        tftBadge.setVisibility(StreamService.navigationActive ? View.VISIBLE : View.GONE);
        String t = StreamService.ownScreen ? "On bike · Arrows" : "On bike · Map";
        if (!t.contentEquals(tftBadge.getText())) tftBadge.setText(t);
    }
    /** The ride mode was just entered: follow the rider and pop the first turn in. */
    void enter() { follow = true; shownManeuver = -1; }
    /** After a mode change: leave the resting screen with the ride, keep the screen on while riding, and count down to resting. */
    void settle(boolean riding) {
        if (!riding && bikeMode) leaveBikeMode();
        if (riding && (!bikeMode || KeepAlive.keepScreenOn())) a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); else a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (riding) armBikeMode();
    }
    /** On every ride update. */
    void paint(boolean riding) { if (riding) paintRide(); if (bikeMode) paintBikeMode(); }
    int barHeight() { return bar.getHeight(); }

    private final Runnable disarmEnd = () -> { endArmed = false; endBtn.setText("End"); };
    private final Runnable disarmBlock = () -> { blockArmed = false; blockBtn.setText("Blocked"); };
    /** First tap asks, second marks the road ahead blocked and finds a way around it (NavSession.blockAhead). */
    private void onBlocked() {
        if (!s.active || s.simulation) return;
        if (!blockArmed) { blockArmed = true; blockBtn.setText("Road blocked?"); ui.removeCallbacks(disarmBlock); ui.postDelayed(disarmBlock, 4000); return; }
        ui.removeCallbacks(disarmBlock); disarmBlock.run();
        s.blockAhead(); host.notice("Finding a way around the blocked road");
    }
    private void onEnd() {
        if (!endArmed && !"Destination nearby".equals(s.instruction)) { endArmed = true; endBtn.setText("Tap to end"); ui.removeCallbacks(disarmEnd); ui.postDelayed(disarmEnd, 3000); return; }
        ui.removeCallbacks(disarmEnd); disarmEnd.run();
        // The bike display stays connected between rides; the notification's Stop still disconnects everything.
        s.stop(); a.stopService(new Intent(a, NavService.class)); host.refresh();
    }
    private void paintRide() {
        if (s.cruise) { paintCruise(); return; }
        Gabi.show(blockBtn, true, 0);
        int idx = s.maneuver; TurnCard card = TurnCard.of(s.maneuvers, idx, s.instruction, s.destination);
        boolean stale = s.gpsStale(); Gabi.Palette p = k.t.palette;
        if (idx != shownManeuver) {
            shownManeuver = idx; maneuver.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            View content = maneuverIcon; content.setScaleX(0.6f); content.setScaleY(0.6f); content.setRotation(-12);
            content.animate().scaleX(1).scaleY(1).rotation(0).setDuration(520).setInterpolator(new android.view.animation.OvershootInterpolator(2.4f)).start();
            maneuverStreet.setAlpha(0); maneuverStreet.setTranslationY(k.dp(8)); maneuverStreet.animate().alpha(1).translationY(0).setDuration(380).setInterpolator(Gabi.EASE_OUT).start();
        }
        icon(maneuverIcon, card.icon, p.onAccent, 2.6f);
        maneuverDist.setText(stale ? "GPS weak" : card.arriving ? "Arriving" : Gabi.distance(s.distanceToTurn));
        maneuverDist.setTextColor(stale ? p.danger : p.ink);
        maneuverStreet.setText(card.street);
        JSONObject next = card.next;
        if (next != null) { icon(thenIcon, Maneuvers.icon(next.optInt("type", 8)), p.ink, 2.2f); thenText.setText(TurnCard.thenLabel(next, s.destination)); }
        Gabi.show(thenChip, next != null, -k.dp(6));
        etaMin.setText(card.arriving ? "You’re here" : Gabi.minutes(s.seconds));
        if (!endArmed) endBtn.setText(card.arriving ? "Finish" : "End");
        etaMeta.setText(Gabi.distance(s.remaining) + " · arrive " + Gabi.clock(s.seconds));
        simBadge.setVisibility(s.simulation ? View.VISIBLE : View.GONE);
        paintBikeBadge();
    }
    /** The ride screen on a free ride: the road the rider is on and their speed, with End; no turns, no arrival time. */
    private void paintCruise() {
        boolean stale = s.gpsStale(); Gabi.Palette p = k.t.palette;
        icon(maneuverIcon, IconDrawable.STRAIGHT, p.onAccent, 2.6f);
        maneuverDist.setText(stale ? "GPS weak" : s.street.isEmpty() ? "Free ride" : s.street);
        maneuverDist.setTextColor(stale ? p.danger : p.ink);
        maneuverStreet.setText(stale ? "Waiting for a clear GPS signal" : "No destination · the map follows you");
        Gabi.show(thenChip, false, -k.dp(6));
        etaMin.setText(stale ? "–" : Gabi.speed(s.speedMetersPerSecond));
        etaMeta.setText(s.street.isEmpty() ? "Free ride · no destination" : "Free ride · " + s.street);
        if (!endArmed) endBtn.setText("End");
        Gabi.show(blockBtn, false, 0);
        simBadge.setVisibility(View.GONE);
        paintBikeBadge();
    }
    /** Show the icon, keeping the one already there when nothing about it changed: this runs on every GPS fix. */
    private static void icon(ImageView v, int type, int color, float stroke) {
        android.graphics.drawable.Drawable d = v.getDrawable();
        if (d instanceof IconDrawable && ((IconDrawable)d).is(type, color, stroke)) return;
        v.setImageDrawable(new IconDrawable(type, color).stroke(stroke));
    }

    // ---------------------------------------------------------------- following the rider
    private boolean follow = true; private double shownLat, shownLon; private float shownBearing;
    boolean following() { return follow; }
    /** The locate button: the camera follows the rider again. */
    void recenter() { follow = true; }
    /** The rider moved the map: stop following until they tap locate. */
    void unfollow() { follow = false; }

    // During a ride the arrow moves every frame along the road at the rider's speed (the same motion as the bike
    // display), so it never trails a second behind the real position.
    private final RideMotion rideMotion; private boolean rideFrames; private long rideFrameAt; private final ImageView arrow;
    private final Choreographer.FrameCallback rideFrame = new Choreographer.FrameCallback() {
        public void doFrame(long nanos) {
            if (!rideFrames || a.isDestroyed()) return;
            if (!host.riding() || !s.active) { rideFrames = false; placeArrow(false); host.puckStale(); return; }
            long now = SystemClock.elapsedRealtime(); float gap = rideFrameAt == 0 ? 16f : Math.min(1000f, now - rideFrameAt); rideFrameAt = now;
            rideMotion.update(now, gap);
            if (host.mapReady() && rideMotion.lat != 0) {
                shownLat = rideMotion.lat; shownLon = rideMotion.lon; shownBearing = rideMotion.bearing;
                if (follow) follow();
                placeArrow(true);
                host.onRideFrame(now, shownLat, shownLon, rideMotion.routeFrom);
            }
            Choreographer.getInstance().postFrameCallback(this);
        }
    };
    boolean framesRunning() { return rideFrames; }
    void startFrames() { if (rideFrames || bikeMode) return; rideFrames = true; rideFrameAt = 0; Choreographer.getInstance().removeFrameCallback(rideFrame); Choreographer.getInstance().postFrameCallback(rideFrame); }
    /** The screen went away (onStop): no frames, no arrow, no falling asleep into the resting screen. */
    void stop() { rideFrames = false; placeArrow(false); ui.removeCallbacks(autoBikeMode); }
    /**
     * The ride arrow is a view over the map, moved by the renderer like any view. Following, it sits where follow()
     * puts the rider (the middle of the map below the top padding); after the rider pans away it sits on the rider's
     * spot on the map, turned to their heading.
     */
    private void placeArrow(boolean show) {
        if (!show || bikeMode) { if (arrow.getVisibility() != View.GONE) arrow.setVisibility(View.GONE); return; }
        float x, y;
        if (follow) {
            int w = mapView.getWidth(), h = mapView.getHeight(); float left = lay.landscape ? lay.panelW : 0, top = lay.landscape ? h * 0.3f : h * 0.42f;
            x = left + (w - left) / 2f; y = top + (h - top) / 2f; arrow.setRotation(0); arrow.setRotationX(38);
        } else {
            CameraPosition c = map.getCameraPosition(); android.graphics.PointF p = map.getProjection().toScreenLocation(new LatLng(shownLat, shownLon));
            x = p.x; y = p.y; arrow.setRotation((float)(shownBearing - c.bearing)); arrow.setRotationX((float)c.tilt);
        }
        arrow.setX(x - arrow.getWidth() / 2f); arrow.setY(y - arrow.getHeight() / 2f);
        if (arrow.getVisibility() != View.VISIBLE) arrow.setVisibility(View.VISIBLE);
    }
    private double followedLat, followedLon, followedZoom; private float followedBearing; private int followedH;
    private void follow() {
        int h = mapView.getHeight(); double zoom = !s.cruise && s.remaining < 600 ? 17.4 : 16.8;
        // Called every frame: while the rider is stopped the camera is already there, and moving it again would still cost a camera pass.
        if (shownLat == followedLat && shownLon == followedLon && shownBearing == followedBearing && zoom == followedZoom && h == followedH) return;
        followedLat = shownLat; followedLon = shownLon; followedBearing = shownBearing; followedZoom = zoom; followedH = h;
        CameraPosition pos = new CameraPosition.Builder().target(new LatLng(shownLat, shownLon)).bearing(shownBearing).tilt(52)
            .zoom(zoom).padding(lay.landscape ? lay.panelW : 0, lay.landscape ? h * 0.3 : h * 0.42, 0, 0).build();
        map.moveCamera(CameraUpdateFactory.newCameraPosition(pos));
    }

    // ---------------------------------------------------------------- bike mode
    // While the bike display shows the directions, the phone can rest: a plain dark screen with the next turn, the
    // phone map hidden so it stops drawing, the screen dimmed and allowed to sleep. Tap to bring the map back.
    private FrameLayout bikeView; private ImageView bikeIcon; private TextView bikeDist, bikeStreet, bikeEta, bikeEnd; private boolean bikeMode; private float savedBrightness = -1;
    private final Runnable autoBikeMode = this::autoBikeMode;
    private void autoBikeMode() { if (host.riding() && s.active && StreamService.navigationActive && a.getSharedPreferences("ui", 0).getBoolean("bikemode", true)) enterBikeMode(); }
    /** Fall asleep 15 s after the last touch, once the bike display is live. */
    void armBikeMode() { ui.removeCallbacks(autoBikeMode); if (!bikeMode) ui.postDelayed(autoBikeMode, 15000); }
    void onUserInteraction(boolean riding) { if (riding && !bikeMode) armBikeMode(); }
    private void buildBikeMode(FrameLayout root) {
        // Its own kit on the night palette: the app's kit recolours its views on every theme change (sunrise mid-ride).
        Gabi.Kit nk = new Gabi.Kit(a, new Gabi.Themer(Gabi.NIGHT));
        // Always light text on black, whatever the map theme: this screen is meant to be dark.
        bikeView = new FrameLayout(a); bikeView.setBackgroundColor(0xFF000000); bikeView.setVisibility(View.GONE); bikeView.setElevation(nk.dp(60));
        LinearLayout col = nk.column(8); col.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView head = nk.text("Directions are on your bike display", 14, 650, MUTED); head.setTextColor(0xFF8494A4); head.setGravity(Gravity.CENTER); col.addView(head);
        FrameLayout tile = new FrameLayout(a); tile.setBackground(Gabi.round(0xFFFFB020, nk.dp(26)));
        bikeIcon = nk.icon(IconDrawable.STRAIGHT, 56, ON_ACCENT); tile.addView(bikeIcon, new FrameLayout.LayoutParams(nk.dp(56), nk.dp(56), Gravity.CENTER));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(nk.dp(96), nk.dp(96)); tp.topMargin = nk.dp(10); col.addView(tile, tp);
        bikeDist = nk.num("", 40, 800, INK); bikeDist.setTextColor(0xFFFFFFFF); bikeDist.setGravity(Gravity.CENTER); col.addView(bikeDist);
        bikeStreet = nk.text("", 17, 650, INK_SOFT); bikeStreet.setTextColor(0xFFC9D3DC); bikeStreet.setGravity(Gravity.CENTER); bikeStreet.setMaxLines(2); col.addView(bikeStreet);
        bikeEta = nk.num("", 14.5f, 600, MUTED); bikeEta.setTextColor(0xFF8494A4); bikeEta.setGravity(Gravity.CENTER); col.addView(bikeEta);
        LinearLayout actions = nk.row(10);
        TextView show = nk.pill("Show map", 750, null, WHITE, this::leaveBikeMode); show.setBackground(Gabi.round(0xFF1B242E, nk.dp(26)));
        actions.addView(show, new LinearLayout.LayoutParams(0, -2, 1));
        bikeEnd = nk.pill("End", 800, null, WHITE, () -> { onEnd(); bikeEnd.setText(endBtn.getText()); }); bikeEnd.setBackground(Gabi.round(0xFF5A1B1E, nk.dp(26)));
        actions.addView(bikeEnd, new LinearLayout.LayoutParams(0, -2, 1));
        // The buttons sit right under the directions, so nothing overlaps in landscape.
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(Math.min(nk.dp(460), a.getResources().getDisplayMetrics().widthPixels - nk.dp(40)), -2); ap.topMargin = nk.dp(22); col.addView(actions, ap);
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER); cl.leftMargin = cl.rightMargin = nk.dp(20);
        bikeView.addView(col, cl);
        bikeView.setOnClickListener(v -> leaveBikeMode());
        root.addView(bikeView, new FrameLayout.LayoutParams(-1, -1));
    }
    void enterBikeMode() {
        if (bikeMode || !host.riding() || !s.active) return;
        bikeMode = true; ui.removeCallbacks(autoBikeMode); rideFrames = false; placeArrow(false);
        paintBikeMode();
        bikeView.setAlpha(0f); bikeView.setVisibility(View.VISIBLE); bikeView.animate().alpha(1f).setDuration(400).withEndAction(() -> { if (bikeMode) mapView.setVisibility(View.INVISIBLE); }).start();
        Window w = a.getWindow(); WindowManager.LayoutParams lp = w.getAttributes(); savedBrightness = lp.screenBrightness; lp.screenBrightness = 0.05f; w.setAttributes(lp);
        // OPPO, OnePlus and realme close apps soon after the screen turns off (logs of 1 Nov: the screen was on 9% of the
        // ride when the phone closed Daanvia), so there the dark resting screen stays on. Elsewhere it may turn off.
        if (KeepAlive.keepScreenOn()) w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    private void leaveBikeMode() {
        if (!bikeMode) return;
        bikeMode = false; mapView.setVisibility(View.VISIBLE);
        bikeView.animate().alpha(0f).setDuration(250).withEndAction(() -> { if (!bikeMode) bikeView.setVisibility(View.GONE); }).start();
        Window w = a.getWindow(); WindowManager.LayoutParams lp = w.getAttributes(); lp.screenBrightness = savedBrightness; w.setAttributes(lp);
        if (host.riding() && s.active) { w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); host.puckStale(); startFrames(); armBikeMode(); }
    }
    private void paintBikeMode() {
        int onAccent = k.t.palette.onAccent;
        if (s.cruise) {
            icon(bikeIcon, IconDrawable.STRAIGHT, onAccent, 2.6f);
            bikeDist.setText(s.gpsStale() ? "GPS weak" : Gabi.speed(s.speedMetersPerSecond)); bikeStreet.setText(s.street.isEmpty() ? "Free ride" : s.street); bikeEta.setText("Free ride · no destination");
            if (!endArmed) bikeEnd.setText("End"); return;
        }
        TurnCard card = TurnCard.of(s.maneuvers, s.maneuver, s.instruction, s.destination);
        icon(bikeIcon, card.icon, onAccent, 2.6f);
        bikeDist.setText(card.arriving ? "Arriving" : Gabi.distance(s.distanceToTurn));
        bikeStreet.setText(card.street);
        bikeEta.setText(Gabi.minutes(s.seconds) + " · " + Gabi.distance(s.remaining) + " · arrive " + Gabi.clock(s.seconds));
        if (!endArmed) bikeEnd.setText(card.arriving ? "Finish" : "End");
    }
}
