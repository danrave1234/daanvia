package net.drave.atrdiagnostics;

import android.content.Context;
import android.graphics.*;
import android.view.View;

/** Phone-side preview of the scene the dashboard receives, drawn by the same renderer at the TFT's 5:3 shape. */
public final class TftPreview extends View {
    private final DashboardRenderer renderer; private final Path clip = new Path(); private final float radius;
    private final NavSession s;
    public TftPreview(Context c, NavSession s) { super(c); this.s = s; renderer = new DashboardRenderer(s); radius = Gabi.dp(c, 14); }
    /** The bike's panel shape once a bike has connected (5:3 before that), so the preview looks like the real display. */
    protected void onMeasure(int w, int h) { int width = MeasureSpec.getSize(w); float a = s.panelW > 0 && s.panelH > 0 ? (float)s.panelW / s.panelH : 5f / 3f; setMeasuredDimension(width, Math.round(width / Math.max(0.5f, Math.min(4f, a)))); }
    protected void onSizeChanged(int w, int h, int ow, int oh) { clip.reset(); clip.addRoundRect(0, 0, w, h, radius, radius, Path.Direction.CW); }
    /** During a ride it keeps drawing at the bike display's rate (up to 30 frames a second), so it moves like the TFT. */
    protected void onDraw(Canvas c) { c.save(); c.clipPath(clip); renderer.draw(c, getWidth(), getHeight()); c.restore(); if (s.active && isShown()) postInvalidateDelayed(1000 / Math.max(15, Math.min(30, s.bikeFps > 0 ? s.bikeFps : 30))); }
}
