package net.drave.atrdiagnostics;

import android.app.Activity;
import android.graphics.Color;
import android.view.*;
import android.widget.*;

/** Scrolling page scaffold for the secondary screens: edge-to-edge, a back button and a title. */
final class GabiPage {
    final LinearLayout content; final ScrollView scroll; final FrameLayout root;

    GabiPage(Activity a, Gabi.Kit k, String title) {
        Window w = a.getWindow(); Gabi.edgeToEdge(w);
        k.t.add(p -> Gabi.barIcons(w, p));
        root = new FrameLayout(a); k.t.add(p -> root.setBackgroundColor(p.bg));
        scroll = new ScrollView(a); scroll.setClipToPadding(false); scroll.setVerticalScrollBarEnabled(false);
        content = k.column(14); content.setPadding(k.dp(16), 0, k.dp(16), k.dp(24));
        LinearLayout head = k.row(12); head.setPadding(0, k.dp(6), 0, k.dp(6));
        head.addView(k.circle(IconDrawable.BACK, 44, Gabi.Role.CHIP, Gabi.Role.INK, a::finish));
        head.addView(k.text(title, 22, 800, Gabi.Role.INK));
        content.addView(head);
        scroll.addView(content); root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        // On wide screens (landscape, tablets) the page is a centred column instead of stretching edge to edge.
        int[] in = new int[4];
        Runnable pad = () -> { int side = Math.max(0, (root.getWidth() - k.dp(680)) / 2);
            content.setPadding(Math.max(k.dp(16) + in[0], side), in[1] + k.dp(8), Math.max(k.dp(16) + in[2], side), in[3] + k.dp(24)); };
        Gabi.insets(root, (l, t, r, b, ime) -> { in[0] = l; in[1] = t; in[2] = r; in[3] = Math.max(b, ime); pad.run(); });
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> { if (r - l != or - ol) root.post(pad); });
        a.setContentView(root);
        content.setAlpha(0f); content.setTranslationY(k.dp(16));
        content.animate().alpha(1f).translationY(0).setDuration(420).setInterpolator(Gabi.EASE_OUT).start();
    }

    static LinearLayout card(Gabi.Kit k) {
        LinearLayout c = k.column(12); c.setPadding(k.dp(16), k.dp(16), k.dp(16), k.dp(16));
        k.t.add(p -> c.setBackground(Gabi.round(p.night ? 0xFF151E28 : 0xFFFFFFFF, k.dp(24), p.stroke, k.dp(1))));
        return c;
    }
}
