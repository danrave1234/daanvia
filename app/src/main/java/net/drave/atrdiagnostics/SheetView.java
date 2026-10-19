package net.drave.atrdiagnostics;

import android.content.Context;
import android.view.*;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

/**
 * Floating glass bottom sheet. Content swaps spring in from below; the sheet can be dragged down to a
 * peek and flung back up. Height changes are reported so map padding and floating buttons can follow.
 */
public final class SheetView extends FrameLayout {
    public interface Listener { void onSheetMoved(float visibleHeight); }

    private final Gabi.Kit kit; private final LinearLayout body; private View current;
    private final SpringAnimation spring; private Listener listener;
    private float downY, startT; private boolean dragging, collapsed; private VelocityTracker velocity;
    private final int slop; private int peek;

    public SheetView(Context c, Gabi.Kit kit) {
        super(c); this.kit = kit;
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        body = kit.column(0); body.setPadding(kit.dp(16), kit.dp(10), kit.dp(16), kit.dp(16));
        body.addView(kit.grab());
        addView(body, new LayoutParams(-1, -2));
        kit.glass(this, 30);
        spring = new SpringAnimation(this, DynamicAnimation.TRANSLATION_Y, 0);
        spring.getSpring().setStiffness(520f).setDampingRatio(0.82f);
        spring.addUpdateListener((a, v, vel) -> report());
        addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> report());
        peek = kit.dp(92);
    }
    public void setListener(Listener l) { listener = l; }
    public void setPeek(int px) { peek = px; }
    private void report() { fadeRest(); if (listener != null) listener.onSheetMoved(getVisibility() == VISIBLE ? Math.max(0, getHeight() - getTranslationY()) : 0); }
    /** As the sheet folds, everything under its first row fades out, so a folded sheet shows one clean row. */
    private void fadeRest() {
        if (!(current instanceof ViewGroup)) return;
        float max = collapsedOffset(), f = max <= 0 ? 0 : Math.max(0, Math.min(1, getTranslationY() / max));
        ViewGroup g = (ViewGroup)current; for (int i = 1; i < g.getChildCount(); i++) g.getChildAt(i).setAlpha(1 - f);
    }

    /** Replace the sheet content with a lift-and-settle spring. */
    public void swap(View next) {
        if (next == current) { expand(); return; }
        if (current != null) body.removeView(current);
        current = next;
        if (next.getParent() != null) ((ViewGroup)next.getParent()).removeView(next);
        body.addView(next, new LinearLayout.LayoutParams(-1, -2));
        collapsed = false;
        boolean wasHidden = getVisibility() != VISIBLE;
        animate().cancel(); // a hide() still running would set it GONE when it ends
        setVisibility(VISIBLE);
        next.setAlpha(0f); next.setTranslationY(kit.dp(18));
        next.animate().alpha(1f).translationY(0).setStartDelay(wasHidden ? 60 : 30).setDuration(420).setInterpolator(Gabi.EASE_OUT).start();
        if (wasHidden) { post(() -> { setTranslationY(getHeight() + kit.dp(40)); spring.animateToFinalPosition(0); }); }
        else { setTranslationY(getTranslationY() + kit.dp(26)); spring.animateToFinalPosition(0); }
    }
    public View content() { return current; }
    public void expand() {
        collapsed = false; animate().cancel();
        if (getVisibility() != VISIBLE) { setVisibility(VISIBLE); post(() -> { setTranslationY(getHeight() + kit.dp(40)); spring.animateToFinalPosition(0); }); }
        else spring.animateToFinalPosition(0);
    }
    public void hide() {
        if (getVisibility() != VISIBLE) return;
        spring.cancel();
        animate().translationY(getHeight() + kit.dp(40)).setDuration(260).setInterpolator(Gabi.EASE_IN_OUT)
            .setUpdateListener(a -> report()).withEndAction(() -> { setVisibility(GONE); setTranslationY(0); report(); }).start();
    }
    public boolean isCollapsed() { return collapsed; }

    // ---------------------------------------------------------------- drag
    /** Fold the sheet down to its title row; the map gets the room. */
    public void collapse() { if (getVisibility() != VISIBLE) return; collapsed = true; spring.animateToFinalPosition(collapsedOffset()); }
    /** How far down the sheet slides when folded: just the handle and the content's first row stay above the gesture bar. */
    private float collapsedOffset() {
        int row = peek;
        if (current instanceof ViewGroup && ((ViewGroup)current).getChildCount() > 0) row = current.getTop() + ((ViewGroup)current).getChildAt(0).getBottom() + kit.dp(12);
        int below = getParent() instanceof View ? ((View)getParent()).getHeight() - getBottom() : 0;
        androidx.core.view.WindowInsetsCompat insets = androidx.core.view.ViewCompat.getRootWindowInsets(this);
        int nav = insets == null ? 0 : insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom;
        return Math.max(0, getHeight() + below - nav - row);
    }

    /** Drags that start on a row or button are taken over once they move; the handle, title and gaps drag directly. */
    public boolean onInterceptTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: downY = e.getRawY(); startT = getTranslationY(); dragging = false; return false;
            case MotionEvent.ACTION_MOVE:
                float dy = e.getRawY() - downY;
                if (Math.abs(dy) > slop && (dy > 0 || collapsed) && (collapsed || e.getY() < kit.dp(120))) { begin(); return true; }
                return false;
            default: return false;
        }
    }
    private void begin() { dragging = true; spring.cancel(); if (velocity == null) velocity = VelocityTracker.obtain(); }
    public boolean onTouchEvent(MotionEvent e) {
        if (velocity != null) velocity.addMovement(e);
        float max = collapsedOffset();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downY = e.getRawY(); startT = getTranslationY(); dragging = false;
                if (velocity != null) velocity.recycle();
                velocity = VelocityTracker.obtain(); velocity.addMovement(e);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging) { if (Math.abs(e.getRawY() - downY) <= slop) return true; begin(); }
                float t = startT + e.getRawY() - downY;
                setTranslationY(t < 0 ? t * 0.25f : Math.min(t, max + kit.dp(20))); report(); return true;
            case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL:
                float v = 0; if (velocity != null) { velocity.computeCurrentVelocity(1000); v = velocity.getYVelocity(); velocity.recycle(); velocity = null; }
                if (!dragging) { if (e.getActionMasked() == MotionEvent.ACTION_UP && collapsed) expand(); return true; }
                boolean down = v > 900 || (v > -900 && getTranslationY() > max * 0.4f);
                collapsed = down;
                spring.setStartVelocity(v).animateToFinalPosition(down ? max : 0);
                dragging = false; return true;
        }
        return true;
    }
}
