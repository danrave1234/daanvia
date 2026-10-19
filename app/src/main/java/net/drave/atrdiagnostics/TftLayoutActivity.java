package net.drave.atrdiagnostics;

import android.app.Activity;
import android.content.Context;
import android.graphics.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;

import static net.drave.atrdiagnostics.Gabi.Role.*;

/**
 * Place the bike display's turn card, trip card and arrow so nothing hides behind the bike's own speedometer and
 * gauges. The phone shows the same scene the bike gets; while this screen is open the bike shows sample directions
 * with outlines and follows every drag. Saved for the paired bike.
 */
public final class TftLayoutActivity extends Activity {
    private NavSession s; private Editor editor;

    public void onCreate(Bundle state) {
        super.onCreate(state);
        s = NavSession.get(this);
        Gabi.Kit k = new Gabi.Kit(this, new Gabi.Themer(Gabi.resolve(this, s.lat, s.lon, s.fixAt > 0)));
        Window w = getWindow(); Gabi.edgeToEdge(w); Gabi.barIcons(w, Gabi.NIGHT); w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root = k.column(12); root.setBackgroundColor(0xFF0A0F14);
        LinearLayout bar = k.row(10); bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(k.circle(IconDrawable.BACK, 44, CHIP, INK, this::finish));
        TextView title = k.text("Bike display layout", 19, 750, INK); bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(k.ghost("Reset", () -> { s.resetLayout(); s.saveFit(); editor.invalidate(); }));
        root.addView(bar);
        editor = new Editor(this, s);
        root.addView(editor, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView hint = k.text("Drag the Turn and Trip cards and the arrow where your bike’s speedometer and gauges don’t cover them. Drag a card’s corner dot to resize it. Your bike display follows along while it’s connected.", 13.5f, 500, MUTED);
        hint.setGravity(Gravity.CENTER); root.addView(hint);
        Gabi.insets(root, (l, t, r, b, ime) -> root.setPadding(l + k.dp(16), t + k.dp(10), r + k.dp(16), b + k.dp(14)));
        setContentView(root);
    }
    protected void onResume() { super.onResume(); s.layoutEditing = true; s.signal(); }
    protected void onPause() { s.layoutEditing = false; s.saveFit(); super.onPause(); }

    /** The bike's scene at its 5:3 shape, with the cards and arrow draggable. */
    static final class Editor extends View {
        private final NavSession s; private final DashboardRenderer renderer; private final float d;
        private int mode; private float[] grabbed; private float downX, downY, startX, startY, startW, startH;
        private static final int NONE = 0, MOVE = 1, RESIZE = 2, ARROW = 3;
        Editor(Context c, NavSession s) { super(c); this.s = s; renderer = new DashboardRenderer(s); d = c.getResources().getDisplayMetrics().density; }

        private RectF box() {
            float w = getWidth(), h = getHeight(), bw = Math.min(w, h * 5f / 3f), bh = bw * 3f / 5f;
            float l = (w - bw) / 2f, t = (h - bh) / 2f; return new RectF(l, t, l + bw, t + bh);
        }
        protected void onDraw(Canvas c) {
            RectF b = box(); c.save(); c.translate(b.left, b.top);
            Path clip = new Path(); clip.addRoundRect(0, 0, b.width(), b.height(), 14 * d, 14 * d, Path.Direction.CW); c.clipPath(clip);
            renderer.draw(c, Math.round(b.width()), Math.round(b.height())); c.restore();
            postInvalidateDelayed(250);
        }
        /** The renderer's visible area inside the editor, in view pixels. */
        private RectF scene() { RectF b = box(); RectF r = renderer.scene(Math.round(b.width()), Math.round(b.height())); r.offset(b.left, b.top); return r; }

        public boolean onTouchEvent(MotionEvent e) {
            RectF sc = scene(); if (sc.width() <= 0) return false;
            float fx = (e.getX() - sc.left) / sc.width(), fy = (e.getY() - sc.top) / sc.height();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    getParent().requestDisallowInterceptTouchEvent(true);
                    downX = fx; downY = fy; mode = NONE; grabbed = null;
                    float ax = sc.left + s.arrowX * sc.width(), ay = sc.top + s.arrowY * sc.height();
                    if (Math.hypot(e.getX() - ax, e.getY() - ay) < 40 * d) { mode = ARROW; startX = s.arrowX; startY = s.arrowY; return true; }
                    for (float[] card : new float[][]{s.tripCard, s.turnCard}) {
                        float cx = sc.left + (card[0] + card[2]) * sc.width(), cy = sc.top + (card[1] + card[3]) * sc.height();
                        if (Math.hypot(e.getX() - cx, e.getY() - cy) < 30 * d) { mode = RESIZE; grabbed = card; break; }
                        if (fx >= card[0] && fx <= card[0] + card[2] && fy >= card[1] && fy <= card[1] + card[3]) { mode = MOVE; grabbed = card; break; }
                    }
                    if (grabbed != null) { startX = grabbed[0]; startY = grabbed[1]; startW = grabbed[2]; startH = grabbed[3]; performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); }
                    return mode != NONE;
                }
                case MotionEvent.ACTION_MOVE: {
                    float dx = fx - downX, dy = fy - downY;
                    if (mode == ARROW) { s.arrowX = clamp(startX + dx, 0.06f, 0.94f); s.arrowY = clamp(startY + dy, 0.08f, 0.94f); }
                    else if (mode == MOVE || mode == RESIZE) {
                        float[] n = grabbed.clone();
                        if (mode == MOVE) { n[0] = clamp(startX + dx, 0, 1 - n[2]); n[1] = clamp(startY + dy, 0, 1 - n[3]); }
                        else { n[2] = clamp(startW + dx, 0.16f, 1 - n[0]); n[3] = clamp(startH + dy, 0.14f, 1 - n[1]); }
                        if (grabbed == s.turnCard) s.turnCard = n; else s.tripCard = n;
                        grabbed = n;
                    }
                    invalidate(); return true;
                }
                case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL:
                    if (mode != NONE) s.saveFit();
                    mode = NONE; grabbed = null; return true;
            }
            return false;
        }
        private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
    }
}
