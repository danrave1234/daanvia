package net.drave.atrdiagnostics;

import android.app.Activity;
import android.content.Intent;
import android.os.Handler;
import android.os.SystemClock;
import android.text.*;
import android.text.style.ForegroundColorSpan;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;
import java.util.concurrent.Executor;

import static net.drave.atrdiagnostics.Gabi.Role.*;

/**
 * The search screen over the map: the field, offline results (saved places, the area's places with their parking, places
 * found online before), places found online with signal, and the hand-off to Google Maps for places too new for OpenStreetMap.
 */
final class SearchPanel {
    interface Host {
        void choose(double lat, double lon, String name);
        void openLot(Lots.Lot lot, String parent);
        /** Back to the map (Mode.EXPLORE). */
        void close();
        void notice(String text);
        void hideKeyboard();
        String lotName(Lots.Lot lot, String parent);
        /** Search history, newest first. */
        JSONArray recents();
        /** Where distances are measured from: the rider, else the middle of the map. */
        double[] searchFrom();
        /** The area's search index finished loading. */
        void onIndexed();
    }

    static final int PARKING_METERS = 150;

    private final Activity a; private final NavSession s; private final Gabi.Kit k; private final Handler ui; private final Executor worker, net; private final Bookmarks bookmarks; private final Host host;
    // Results are rebuilt from scratch at each keystroke, so their rows colour themselves through a theme of their own,
    // cleared at each rebuild: the screen's theme list doesn't grow with every keystroke.
    private final Gabi.Themer resultsTheme; private final Gabi.Kit rk;
    final LinearLayout panel; private final LinearLayout results; private final EditText field;
    private SearchIndex index; private Lots lots; private volatile int searchToken; private int indexFor = -1;
    /** Town names over the whole country (bundled), to name chain branches and to jump the download frame. */
    SearchIndex towns;
    private final ArrayList<String> shownNames = new ArrayList<>(); private final ArrayList<double[]> shownSpots = new ArrayList<>();

    SearchPanel(Activity a, NavSession s, Gabi.Kit k, FrameLayout root, Handler ui, Executor worker, Executor net, Bookmarks bookmarks, Host host) {
        this.a = a; this.s = s; this.k = k; this.ui = ui; this.worker = worker; this.net = net; this.bookmarks = bookmarks; this.host = host;
        rk = new Gabi.Kit(a, resultsTheme = k.t.child());
        panel = k.column(0); panel.setVisibility(View.GONE); panel.setElevation(k.dp(24));
        k.t.add(p -> panel.setBackgroundColor(p.scrim));
        LinearLayout fieldRow = k.row(10); fieldRow.setPadding(k.dp(16), 0, k.dp(8), 0);
        k.t.add(p -> fieldRow.setBackground(Gabi.round(p.chip, k.dp(26), p.accent, k.dp(1.5f))));
        fieldRow.addView(k.icon(IconDrawable.SEARCH, 20, ACCENT));
        field = new EditText(a); field.setBackground(null); field.setSingleLine(); field.setTextSize(16.5f); field.setTypeface(Gabi.font(a, 550)); field.setHint("Search a place");
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH); field.setPadding(0, 0, 0, 0);
        k.t.add(p -> { field.setTextColor(p.ink); field.setHintTextColor(p.muted); field.setHighlightColor(p.accentSoft); });
        fieldRow.addView(field, new LinearLayout.LayoutParams(0, -1, 1));
        fieldRow.addView(k.circle(IconDrawable.CLOSE, 38, CHIP, MUTED, () -> { if (field.length() > 0) field.setText(""); else host.close(); }));
        panel.addView(fieldRow, new LinearLayout.LayoutParams(-1, k.dp(54)));
        TextView lab = k.label("Places"); lab.setPadding(k.dp(4), k.dp(18), 0, k.dp(6)); panel.addView(lab);
        ScrollView sc = new ScrollView(a); sc.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS); sc.setVerticalScrollBarEnabled(false);
        results = k.column(0); sc.addView(results);
        panel.addView(sc, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(panel, new FrameLayout.LayoutParams(-1, -1));
        field.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence x, int b, int c, int d) {} public void onTextChanged(CharSequence x, int b, int c, int d) {}
            public void afterTextChanged(Editable e) { ui.removeCallbacks(run); ui.postDelayed(run, 110); }
        });
        field.setOnEditorActionListener((v, act, e) -> { host.hideKeyboard(); return true; });
    }

    private volatile PlaceCache placeCache;
    /** Places found online, kept for searching without signal. Opened off the main thread. */
    private synchronized PlaceCache placeCache() { if (placeCache == null) placeCache = PlaceCache.open(a); return placeCache; }

    // ---------------------------------------------------------------- showing
    /** Full width in portrait; in landscape a left column, clear of the system bars and keyboard. */
    void layout(HomeLayout lay) {
        FrameLayout.LayoutParams sp = (FrameLayout.LayoutParams)panel.getLayoutParams(); sp.width = lay.landscape ? lay.panelW + k.dp(28) : -1; sp.gravity = Gravity.START; panel.requestLayout();
        panel.setPadding(k.dp(14), lay.top + k.dp(8), k.dp(14), Math.max(lay.bottom, lay.ime));
    }
    /** Slide the search in over the map, keyboard up, and search what's in the field. */
    void open() {
        panel.setVisibility(View.VISIBLE); panel.setAlpha(0); panel.setTranslationY(k.dp(16)); panel.bringToFront();
        panel.animate().alpha(1).translationY(0).setDuration(300).setInterpolator(Gabi.EASE_OUT).start();
        field.requestFocus(); ui.postDelayed(() -> ((InputMethodManager)a.getSystemService(Activity.INPUT_METHOD_SERVICE)).showSoftInput(field, 0), 120);
        run.run();
    }
    /** Back from a parking lot: the results as they were, without the keyboard. */
    void reveal() { Gabi.show(panel, true, k.dp(16)); panel.bringToFront(); }
    void hide() { Gabi.show(panel, false, k.dp(16)); }

    // ---------------------------------------------------------------- the area's places
    /** Build the search index (and parking) of the active area, once per area, off the main thread. */
    void index() {
        if (!s.ready || indexFor == s.regionVersion) return;
        int version = s.regionVersion; indexFor = version;
        NavSession.Places places = s.places;
        worker.execute(() -> {
            SearchIndex built = new SearchIndex(places.names, places.lat, places.lon);
            Lots parking = null;
            try { parking = new Lots(s.regionText("lots.json")); } catch (Exception ignored) {}
            Lots loaded = parking;
            ui.post(() -> { if (version != s.regionVersion) return; index = built; lots = loaded; host.onIndexed(); });
        });
    }
    /** Another area became active: forget the old one's places and drop searches still running on it. */
    void reset() { index = null; lots = null; searchToken++; }
    /** A place saved before branches were named ("Jollibee"), named the way search names it now ("Jollibee · EDSA, Makati"). */
    String branchName(String name, double lat, double lon) {
        SearchIndex idx = index; if (idx == null || name.contains(" · ") || idx.copies(name) < 2) return name;
        return Branches.label(name, lat, lon, s.roadIndex, towns);
    }
    /** A place name tapped on the map: a chain's branch gets its road and town, like in search. */
    String branchLabel(String name, double lat, double lon) {
        SearchIndex idx = index;
        return idx != null && idx.copies(name) > 1 ? Branches.label(name, lat, lon, s.roadIndex, towns) : name;
    }

    // ---------------------------------------------------------------- searching
    /** Offline results for one query, ready to show. */
    private static final class Found {
        final int[] hits; final double fromLat, fromLon; final String[] near, labels; final List<PlaceCache.Entry> kept; final SearchIndex index;
        Found(int[] hits, double fromLat, double fromLon, String[] near, List<PlaceCache.Entry> kept, SearchIndex index, String[] labels) {
            this.hits = hits; this.fromLat = fromLat; this.fromLon = fromLon; this.near = near; this.kept = kept; this.index = index; this.labels = labels;
        }
    }
    /** Search what's in the field: offline at once, online a moment later. */
    final Runnable run = this::search;
    private void search() {
        String q = field.getText().toString(); int token = ++searchToken;
        double[] from = host.searchFrom(); double fromLat = from[0], fromLon = from[1];
        if (index == null) { showResults(q, null); searchOnline(token, q, fromLat, fromLon); return; }
        SearchIndex idx = index;
        worker.execute(() -> {
            List<SearchIndex.Hit> found = idx.search(q, fromLat, fromLon, 30); int[] hits = new int[found.size()]; String[] near = new String[found.size()];
            for (int i = 0; i < hits.length; i++) { hits[i] = found.get(i).i; near[i] = found.get(i).near; }
            // Branches of a chain, all called "Jollibee" on the map, are named after their road and town.
            String[] label = new String[hits.length]; SearchIndex tw = towns;
            for (int i = 0; i < hits.length; i++) { String n = idx.names[hits[i]]; label[i] = idx.copies(n) > 1 ? Branches.label(n, idx.lat[hits[i]], idx.lon[hits[i]], s.roadIndex, tw) : n; }
            List<PlaceCache.Entry> kept = placeCache().search(q, fromLat, fromLon, 8);
            ui.post(() -> { if (token == searchToken) { showResults(q, new Found(hits, fromLat, fromLon, near, kept, idx, label)); searchOnline(token, q, fromLat, fromLon); } });
        });
    }
    /** With signal, Photon adds every branch, street and barangay in the country under "More places". */
    private void searchOnline(int token, String q, double fromLat, double fromLon) {
        if (q.trim().length() < 3) return;
        ui.postDelayed(() -> {
            if (token != searchToken) return;
            net.execute(() -> {
                if (token != searchToken) return;
                List<OnlineSearch.Place> found = null;
                try { found = OnlineSearch.search(q, fromLat, fromLon, 10); placeCache().add(found); } catch (Exception ignored) {}
                List<OnlineSearch.Place> f = found;
                ui.post(() -> { if (token == searchToken) showOnline(q, f, fromLat, fromLon); });
            });
        }, 350);
    }
    /** The same place (same name, within 150 m) is already in the list. */
    private boolean alreadyShown(String name, double lat, double lon) {
        String f = SearchIndex.fold(name);
        for (int i = 0; i < shownNames.size(); i++) if (SearchIndex.fold(shownNames.get(i)).equals(f) && GeoMath.distance(shownSpots.get(i)[0], shownSpots.get(i)[1], lat, lon) < 150) return true;
        return false;
    }
    private void shown(String name, double lat, double lon) { shownNames.add(name); shownSpots.add(new double[]{lat, lon}); }
    /** The rows from {@code from} on rise into place one after another. */
    private void animateRows(int from) {
        for (int i = from; i < results.getChildCount(); i++) {
            View v = results.getChildAt(i); v.setAlpha(0f); v.setTranslationY(rk.dp(12));
            v.animate().alpha(1f).translationY(0).setStartDelay(Math.min(i - from, 10) * 22L).setDuration(360).setInterpolator(Gabi.EASE_OUT).start();
        }
    }
    private View waiting(int icon, String title) { View w = rk.listRow(icon, title, null, null, null); w.setTag("online-wait"); return w; }
    private void showOnline(String q, List<OnlineSearch.Place> found, double fromLat, double fromLon) {
        View wait = results.findViewWithTag("online-wait"); if (wait != null) results.removeView(wait);
        ArrayList<OnlineSearch.Place> fresh = new ArrayList<>();
        if (found != null) for (OnlineSearch.Place pl : found) if (!alreadyShown(pl.name, pl.lat, pl.lon)) { fresh.add(pl); shown(pl.name, pl.lat, pl.lon); }
        if (fresh.isEmpty()) {
            if (results.getChildCount() == 0) results.addView(rk.listRow(IconDrawable.SEARCH, "Can’t find “" + q.trim() + "”", found == null ? "No signal for online search. Try another spelling, or find it in Google Maps" : "Try another spelling, or find it in Google Maps", null, null));
            if (q.trim().length() >= 3) results.addView(googleRow(q));
            return;
        }
        int start = results.getChildCount();
        // Under the panel's own "Places" heading when nothing offline matched; its own heading otherwise.
        if (start > 0) { TextView lab = rk.label("More places"); lab.setPadding(rk.dp(4), rk.dp(16), 0, rk.dp(6)); results.addView(lab); }
        HashMap<String, Integer> seen = new HashMap<>(); for (OnlineSearch.Place pl : found) seen.merge(SearchIndex.fold(pl.name), 1, Integer::sum);
        for (OnlineSearch.Place pl : fresh) {
            boolean chain = seen.get(SearchIndex.fold(pl.name)) > 1 || index != null && index.copies(pl.name) > 1; String name = chain ? pl.branch : pl.name;
            results.addView(rk.listRow(IconDrawable.PIN, highlight(name, q), pl.address.isEmpty() ? null : pl.address, Gabi.distance(GeoMath.distance(fromLat, fromLon, pl.lat, pl.lon)), () -> host.choose(pl.lat, pl.lon, name)));
        }
        results.addView(googleRow(q));
        animateRows(start);
    }
    private void showResults(String q, Found r) {
        results.removeAllViews(); resultsTheme.clear(); shownNames.clear(); shownSpots.clear();
        if (r == null) { results.addView(waiting(IconDrawable.CLOCK, q.trim().length() >= 3 ? "Searching…" : "Getting search ready…")); return; }
        int[] hits = r.hits; double fromLat = r.fromLat, fromLon = r.fromLon; String[] nearOf = r.near; List<PlaceCache.Entry> kept = r.kept;
        SearchIndex index = r.index; Lots lots = index == this.index ? this.lots : null; String[] labels = r.labels;
        for (Bookmarks.Place b : bookmarks.match(q)) results.addView(starRow(rk, b, highlight(b.name, q), "Saved", Gabi.distance(GeoMath.distance(fromLat, fromLon, b.lat, b.lon))));
        boolean anySaved = results.getChildCount() > 0;
        if (q.trim().isEmpty()) {
            JSONArray rec = host.recents();
            for (int i = 0; i < rec.length() && i < 6; i++) { JSONObject o = rec.optJSONObject(i); double la = o.optDouble("lat"), lo = o.optDouble("lon"); String n = branchName(Lots.renamed(o.optString("name")), la, lo);
                results.addView(rk.listRow(IconDrawable.CLOCK, n, "Recent search", Gabi.distance(GeoMath.distance(fromLat, fromLon, la, lo)), () -> host.choose(la, lo, n))); }
            results.addView(rk.listRow(IconDrawable.LOC, "Choose on the map", "Long-press anywhere to drop a pin", null, () -> { host.close(); host.notice("Long-press the map where you want to go"); }));
        } else if (hits.length == 0 && !anySaved && kept.isEmpty()) {
            if (q.trim().length() >= 3) results.addView(waiting(IconDrawable.SEARCH, "Searching online…"));
            else results.addView(rk.listRow(IconDrawable.SEARCH, "Can’t find “" + q.trim() + "”", "Try another spelling, or share the place to Daanvia from Google Maps", null, null));
        } else {
            // Sub-places ("SM City Cebu PUJ Terminal") belong to the main place they are named after, so the
            // main place lists all of their parking once and the sub-places don't repeat it.
            String[] hn = new String[hits.length]; double[] hla = new double[hits.length], hlo = new double[hits.length];
            for (int n = 0; n < hits.length; n++) { hn[n] = index.names[hits[n]]; hla[n] = index.lat[hits[n]]; hlo[n] = index.lon[hits[n]]; }
            int[] parent = SearchIndex.parents(hn, hla, hlo), order = SearchIndex.grouped(parent);
            int roots = 0;
            for (int n : order) {
                int h = hits[n]; String name = index.names[h], label = labels[n]; double la = index.lat[h], lo = index.lon[h];
                List<Lots.Lot> near = new ArrayList<>();
                if (lots != null && parent[n] == -1 && roots++ < 12) {
                    LinkedHashMap<String, Lots.Lot> all = new LinkedHashMap<>();
                    // Parking that belongs to the place: on its grounds or right next door, the closest six.
                    for (Lots.Lot l : lots.near(la, lo, PARKING_METERS)) all.put(l.id, l);
                    for (int c = 0; c < hits.length; c++) if (parent[c] == n) for (Lots.Lot l : lots.near(index.lat[hits[c]], index.lon[hits[c]], PARKING_METERS)) all.putIfAbsent(l.id, l);
                    near.addAll(all.values()); Lots.rank(near, la, lo); if (near.size() > 6) near = new ArrayList<>(near.subList(0, 6));
                }
                String around = nearOf[n] == null ? null : "Near " + nearOf[n];
                String sub = parent[n] != -1 ? "Part of " + index.names[hits[parent[n]]] : near.isEmpty() ? around : (around == null ? "" : around + " · ") + near.size() + " parking nearby";
                shown(name, la, lo);
                results.addView(rk.listRow(IconDrawable.PIN, highlight(label, q), sub, Gabi.distance(GeoMath.distance(fromLat, fromLon, la, lo)), () -> host.choose(la, lo, label)));
                addLots(rk, results, near, name, la, lo, 3);
            }
            // Places found online before: still here without signal.
            for (PlaceCache.Entry e : kept) {
                if (alreadyShown(e.name, e.lat, e.lon)) continue;
                shown(e.name, e.lat, e.lon);
                results.addView(rk.listRow(IconDrawable.PIN, highlight(e.name, q), e.address.isEmpty() ? null : e.address, Gabi.distance(GeoMath.distance(fromLat, fromLon, e.lat, e.lon)), () -> host.choose(e.lat, e.lon, e.name)));
            }
        }
        animateRows(0);
    }

    // ---------------------------------------------------------------- rows
    /** A saved place's row, its star filled. */
    LinearLayout starRow(Gabi.Kit kit, Bookmarks.Place b, CharSequence title, String sub, String trail) {
        LinearLayout row = kit.listRow(IconDrawable.STAR_FILLED, title, sub, trail, () -> host.choose(b.lat, b.lon, b.name));
        ((ImageView)((FrameLayout)row.getChildAt(0)).getChildAt(0)).setImageDrawable(new IconDrawable(IconDrawable.STAR_FILLED, k.t.palette.accent));
        return row;
    }
    /** Parking that belongs to a place, drawn as branches under it; extra lots fold behind a "more" row. */
    void addLots(Gabi.Kit kit, LinearLayout into, List<Lots.Lot> near, String parent, double la, double lo, int show) {
        int count = Math.min(show, near.size());
        for (int i = 0; i < count; i++) {
            Lots.Lot l = near.get(i);
            into.addView(branch(kit, l.motorcycle() ? IconDrawable.BIKE : IconDrawable.PARK, host.lotName(l, null), l.summary(), Gabi.distance(GeoMath.distance(la, lo, l.lat, l.lon)), i == count - 1 && near.size() <= show, l.restricted(), () -> host.openLot(l, parent)));
        }
        if (near.size() > show) {
            int at = into.getChildCount(); int rest = near.size() - show;
            View[] more = new View[1];
            more[0] = branch(kit, IconDrawable.CHEVRON, "Show " + rest + " more parking", null, null, true, true, () -> {
                into.removeView(more[0]); LinearLayout tmp = kit.column(0); addLots(kit, tmp, near.subList(show, near.size()), parent, la, lo, near.size());
                int pos = at; while (tmp.getChildCount() > 0) { View v = tmp.getChildAt(0); tmp.removeViewAt(0); v.setAlpha(0); into.addView(v, pos++); v.animate().alpha(1).setDuration(260).start(); }
            });
            into.addView(more[0]);
        }
    }
    private LinearLayout branch(Gabi.Kit kit, int icon, String title, String sub, String trail, boolean last, boolean dim, Runnable action) {
        LinearLayout r = kit.row(10); r.setMinimumHeight(kit.dp(52)); r.setPadding(0, 0, 0, 0);
        View line = new View(a) {
            final android.graphics.Paint pt = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            protected void onDraw(android.graphics.Canvas c) {
                pt.setColor(k.t.palette.night ? 0x33FFFFFF : 0x33101820); pt.setStrokeWidth(kit.dp(1.5f)); pt.setStyle(android.graphics.Paint.Style.STROKE);
                float x = kit.dp(18), mid = getHeight() / 2f, rad = kit.dp(8);
                android.graphics.Path p = new android.graphics.Path(); p.moveTo(x, 0); p.lineTo(x, mid - rad); p.quadTo(x, mid, x + rad, mid); p.lineTo(getWidth(), mid);
                if (!last) { p.moveTo(x, mid - rad); p.lineTo(x, getHeight()); }
                c.drawPath(p, pt);
            }
        };
        r.addView(line, new LinearLayout.LayoutParams(kit.dp(34), -1));
        FrameLayout b = new FrameLayout(a); kit.fill(b, 10, dim ? CHIP : ACCENT_SOFT);
        b.addView(kit.icon(icon, 16, dim ? MUTED : ACCENT), new FrameLayout.LayoutParams(kit.dp(16), kit.dp(16), Gravity.CENTER));
        r.addView(b, new LinearLayout.LayoutParams(kit.dp(30), kit.dp(30)));
        LinearLayout col = kit.column(2); col.addView(kit.single(kit.text(title, 14, 600, dim ? INK_SOFT : INK)));
        if (sub != null) col.addView(kit.single(kit.text(sub, 12, 500, MUTED)));
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        if (trail != null) r.addView(kit.num(trail, 12, 600, MUTED));
        Gabi.press(r, action); return r;
    }
    /** The words of the query in the accent colour. */
    static CharSequence highlight(String name, String q, int color) {
        SpannableString sp = new SpannableString(name);
        String lower = name.toLowerCase(Locale.ROOT);
        for (String w : q.trim().toLowerCase(Locale.ROOT).split("\\s+")) {
            if (w.isEmpty()) continue; int at = lower.indexOf(w);
            if (at >= 0) sp.setSpan(new ForegroundColorSpan(color), at, at + w.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sp;
    }
    private CharSequence highlight(String name, String q) { return highlight(name, q, k.t.palette.accent); }

    // ---------------------------------------------------------------- Google Maps
    /*
     * Google Maps knows new places (and new roads) before OpenStreetMap does. Without a paid key Daanvia can't search it
     * itself, so it hands the search to Google Maps and takes the place back: from Share › Daanvia there, or from a link
     * the rider copied, noticed when they come back. Either way the route preview opens straight away.
     */
    private long googleAt, googleWall, lastClipAt; private String lastClip;
    private static final long GOOGLE_WINDOW_MS = 15 * 60_000L;
    private View googleRow(String q) {
        return rk.listRow(IconDrawable.SEARCH, "Find “" + q.trim() + "” in Google Maps", "Newer places. Tap Share › Daanvia there, or copy the link and come back", null, () -> openGoogle(q));
    }
    private void openGoogle(String q) {
        host.hideKeyboard(); googleAt = SystemClock.elapsedRealtime(); googleWall = System.currentTimeMillis();
        try { a.startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com/maps/search/?api=1&query=" + android.net.Uri.encode(q.trim())))); }
        catch (Exception e) { googleAt = 0; host.notice("Google Maps isn’t available on this phone"); }
    }
    /** A Google Maps link or a geo: link: anything else on the clipboard (a video, a message) is not a place. */
    static boolean googleMapsLink(String text) {
        String t = text.toLowerCase(java.util.Locale.ROOT);
        return t.contains("maps.app.goo.gl") || t.contains("goo.gl/maps") || t.contains("google.com/maps") || t.contains("maps.google.") || t.startsWith("geo:");
    }
    /** The rider went to Google Maps from search in the last 15 minutes. */
    boolean fromGoogle() { return googleAt > 0 && SystemClock.elapsedRealtime() - googleAt < GOOGLE_WINDOW_MS; }
    /** The place came back (shared or copied): the hand-off is over. */
    void forgetGoogle() { googleAt = 0; }
    /**
     * Back from Google Maps: a place link the rider copied there, or null. Only right after the handoff, so the
     * clipboard is left alone otherwise.
     */
    String copiedPlace() {
        String text = null;
        try { android.content.ClipboardManager cm = a.getSystemService(android.content.ClipboardManager.class);
            // The description says what's there and when, without reading it (reading shows Android's "pasted" notice):
            // only text copied after Google Maps was opened is looked at.
            android.content.ClipDescription d = cm == null ? null : cm.getPrimaryClipDescription();
            if (d == null || !d.hasMimeType("text/*") || d.getTimestamp() < googleWall) return null;
            // Each copy is read once, whatever it holds: not again at every return to this screen (each read shows the notice).
            if (d.getTimestamp() == lastClipAt) return null; lastClipAt = d.getTimestamp();
            android.content.ClipData clip = cm.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0 && clip.getItemAt(0).getText() != null) text = clip.getItemAt(0).getText().toString(); } catch (Exception ignored) {}
        if (text == null || text.equals(lastClip)) return null; lastClip = text;
        if (!googleMapsLink(text) || ShareLinks.parse(text) == null) return null;
        googleAt = 0; return text;
    }
}
