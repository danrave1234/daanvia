package net.drave.atrdiagnostics;

import android.content.Context;
import android.graphics.*;
import android.view.View;

/** Phone-side preview of the scene the dashboard receives, drawn by the same renderer at the TFT's 5:3 shape. */
public final class TftPreview extends View {
    private final DashboardRenderer renderer; private final Path clip = new Path(); private final float radius;
    private final NavSession s;
    public TftPreview(Context c, NavSession s) { super(c); this.s = s; renderer = new DashboardRenderer(s); radius = Gabi.dp(c, 14); }
    protected void onMeasure(int w, int h) { int width = MeasureSpec.getSize(w); setMeasuredDimension(width, Math.round(width * 0.6f)); }
    protected void onSizeChanged(int w, int h, int ow, int oh) { clip.reset(); clip.addRoundRect(0, 0, w, h, radius, radius, Path.Direction.CW); }
    /** During a ride it keeps drawing at the bike display's 15 frames a second, so the preview moves as smoothly as the TFT. */
    protected void onDraw(Canvas c) { c.save(); c.clipPath(clip); renderer.draw(c, getWidth(), getHeight()); c.restore(); if (s.active && isShown()) postInvalidateDelayed(66); }
}
