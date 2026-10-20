package net.drave.atrdiagnostics;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Line icons drawn on a 24-unit grid, so they stay crisp at any size and take any palette color. */
public final class IconDrawable extends Drawable {
    public static final int SEARCH = 1, LOC = 2, BIKE = 3, RIGHT = 4, LEFT = 5, SLIGHT_RIGHT = 6, SLIGHT_LEFT = 7, SHARP_RIGHT = 8, SHARP_LEFT = 9,
        UTURN = 10, STRAIGHT = 11, FLAG = 12, ROUNDABOUT = 13, MERGE = 14, PARK = 15, TFT = 16, PLAY = 17, PIN = 18, CLOCK = 19, CLOSE = 20,
        CHEVRON = 21, BACK = 22, QR = 23, SHARE = 24, CROSSHAIR = 25, SUN = 26, MOON = 27, CHECK = 28, PHOTO = 29, TOOLS = 30, STOP = 31, AUTO = 32, ROAD = 33, GEAR = 34, STAR = 35, STAR_FILLED = 36, INFO = 37, DOWNLOAD = 38, VOICE = 39, MUTE = 40, MAP = 41, COMPASS = 42, MIRROR = 43,
        FOOD = 44, CAFE = 45, BAG = 46, CART = 47, HEALTH = 48, PILL = 49, PESO = 50, CAP = 51, CHURCH = 52, BED = 53, FUEL = 54, TREE = 55, BUS = 56, CIVIC = 57;

    private final int type; private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); private final Path path = new Path(), solid = new Path(); private boolean filled;
    public IconDrawable(int type, int color) {
        this.type = type; paint.setColor(color); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2f);
        paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND); build();
    }
    public void setColor(int color) { paint.setColor(color); invalidateSelf(); }
    public IconDrawable stroke(float w) { paint.setStrokeWidth(w); invalidateSelf(); return this; }

    private void build() {
        Path p = path;
        switch (type) {
            case SEARCH: p.addCircle(11, 11, 7, Path.Direction.CW); p.moveTo(20, 20); p.lineTo(16.5f, 16.5f); break;
            case LOC: p.moveTo(12, 2); p.lineTo(19, 21); p.lineTo(12, 17); p.lineTo(5, 21); p.close(); break;
            case BIKE: p.addCircle(5.5f, 16.5f, 3.5f, Path.Direction.CW); p.addCircle(18.5f, 16.5f, 3.5f, Path.Direction.CW);
                p.moveTo(5.5f, 16.5f); p.lineTo(9.5f, 10.5f); p.lineTo(14.5f, 10.5f); p.lineTo(18.5f, 16.5f); p.moveTo(14.5f, 10.5f); p.lineTo(12.5f, 6.5f); p.lineTo(15.5f, 6.5f); break;
            case RIGHT: p.moveTo(7, 21); p.lineTo(7, 13); p.quadTo(7, 9, 11, 9); p.lineTo(19, 9); p.moveTo(15, 5); p.lineTo(19, 9); p.lineTo(15, 13); break;
            case LEFT: p.moveTo(17, 21); p.lineTo(17, 13); p.quadTo(17, 9, 13, 9); p.lineTo(5, 9); p.moveTo(9, 5); p.lineTo(5, 9); p.lineTo(9, 13); break;
            case SLIGHT_RIGHT: p.moveTo(9, 21); p.lineTo(9, 13); p.quadTo(9, 10, 11, 8); p.lineTo(17, 3); p.moveTo(11.5f, 3); p.lineTo(17, 3); p.lineTo(17, 8.5f); break;
            case SLIGHT_LEFT: p.moveTo(15, 21); p.lineTo(15, 13); p.quadTo(15, 10, 13, 8); p.lineTo(7, 3); p.moveTo(12.5f, 3); p.lineTo(7, 3); p.lineTo(7, 8.5f); break;
            case SHARP_RIGHT: p.moveTo(8, 3); p.lineTo(8, 12); p.lineTo(17, 20); p.moveTo(17, 14); p.lineTo(17, 20); p.lineTo(11, 20); break;
            case SHARP_LEFT: p.moveTo(16, 3); p.lineTo(16, 12); p.lineTo(7, 20); p.moveTo(7, 14); p.lineTo(7, 20); p.lineTo(13, 20); break;
            case UTURN: p.moveTo(16, 21); p.lineTo(16, 10); p.quadTo(16, 5, 11.5f, 5); p.quadTo(7, 5, 7, 10); p.lineTo(7, 16); p.moveTo(3, 12.5f); p.lineTo(7, 16.5f); p.lineTo(11, 12.5f); break;
            case STRAIGHT: p.moveTo(12, 21); p.lineTo(12, 4); p.moveTo(6, 10); p.lineTo(12, 4); p.lineTo(18, 10); break;
            case FLAG: p.moveTo(5, 21); p.lineTo(5, 4); p.lineTo(17, 4); p.lineTo(14.5f, 8); p.lineTo(17, 12); p.lineTo(5, 12); break;
            case ROUNDABOUT: p.addCircle(12, 9, 4.5f, Path.Direction.CW); p.moveTo(12, 21); p.lineTo(12, 13.5f); p.moveTo(16.2f, 5.6f); p.lineTo(19.5f, 3); break;
            case MERGE: p.moveTo(7, 21); p.lineTo(7, 15); p.quadTo(7, 11, 12, 8); p.lineTo(12, 3); p.moveTo(17, 21); p.lineTo(17, 15); p.quadTo(17, 11, 12, 8); p.moveTo(8, 7); p.lineTo(12, 3); p.lineTo(16, 7); break;
            case PARK: p.addRoundRect(3, 3, 21, 21, 5, 5, Path.Direction.CW); p.moveTo(10, 17); p.lineTo(10, 7); p.lineTo(13, 7); p.quadTo(16, 7, 16, 10); p.quadTo(16, 13, 13, 13); p.lineTo(10, 13); break;
            case TFT: p.addRoundRect(2, 5, 22, 17, 2, 2, Path.Direction.CW); p.moveTo(8, 21); p.lineTo(16, 21); break;
            case PLAY: p.moveTo(7, 4); p.lineTo(20, 12); p.lineTo(7, 20); p.close(); filled = true; break;
            case PIN: p.moveTo(12, 21); p.quadTo(5, 14.5f, 5, 9); p.arcTo(new RectF(5, 2, 19, 16), 180, 180); p.quadTo(19, 14.5f, 12, 21); p.close(); p.addCircle(12, 9, 2.5f, Path.Direction.CW); break;
            case CLOCK: p.addCircle(12, 12, 9, Path.Direction.CW); p.moveTo(12, 7); p.lineTo(12, 12); p.lineTo(15, 14); break;
            case CLOSE: p.moveTo(6, 6); p.lineTo(18, 18); p.moveTo(18, 6); p.lineTo(6, 18); break;
            case CHEVRON: p.moveTo(9, 6); p.lineTo(15, 12); p.lineTo(9, 18); break;
            case BACK: p.moveTo(15, 6); p.lineTo(9, 12); p.lineTo(15, 18); break;
            case QR: p.addRoundRect(3, 3, 10, 10, 1, 1, Path.Direction.CW); p.addRoundRect(14, 3, 21, 10, 1, 1, Path.Direction.CW); p.addRoundRect(3, 14, 10, 21, 1, 1, Path.Direction.CW);
                p.moveTo(14, 14); p.lineTo(17, 14); p.lineTo(17, 17); p.moveTo(21, 14); p.lineTo(21, 21); p.lineTo(14, 21); break;
            case SHARE: p.moveTo(4, 12); p.lineTo(4, 20); p.lineTo(20, 20); p.lineTo(20, 12); p.moveTo(12, 3); p.lineTo(12, 15); p.moveTo(7, 8); p.lineTo(12, 3); p.lineTo(17, 8); break;
            case CROSSHAIR: p.addCircle(12, 12, 7, Path.Direction.CW); p.moveTo(12, 2); p.lineTo(12, 5); p.moveTo(12, 19); p.lineTo(12, 22); p.moveTo(2, 12); p.lineTo(5, 12); p.moveTo(19, 12); p.lineTo(22, 12); break;
            case SUN: p.addCircle(12, 12, 4, Path.Direction.CW);
                for (int i = 0; i < 8; i++) { double a = Math.PI / 4 * i; p.moveTo((float)(12 + Math.cos(a) * 7), (float)(12 + Math.sin(a) * 7)); p.lineTo((float)(12 + Math.cos(a) * 9.5), (float)(12 + Math.sin(a) * 9.5)); } break;
            case MOON: { Path a = new Path(); a.addCircle(12, 12, 8.5f, Path.Direction.CW); Path b = new Path(); b.addCircle(17, 8, 7, Path.Direction.CW); a.op(b, Path.Op.DIFFERENCE); p.set(a); break; }
            case AUTO: p.addCircle(12, 12, 8.5f, Path.Direction.CW); p.moveTo(12, 3.5f); p.lineTo(12, 20.5f); break;
            case CHECK: p.moveTo(5, 12.5f); p.lineTo(10, 17.5f); p.lineTo(19, 7); break;
            case PHOTO: p.addRoundRect(3, 5, 21, 19, 3, 3, Path.Direction.CW); p.addCircle(12, 12, 3.5f, Path.Direction.CW); p.moveTo(8, 5); p.lineTo(9.5f, 3); p.lineTo(14.5f, 3); p.lineTo(16, 5); break;
            case TOOLS: p.moveTo(14.5f, 6.5f); p.quadTo(17, 3, 20.5f, 5); p.lineTo(17.5f, 8); p.lineTo(16, 8); p.lineTo(16, 6.5f); p.lineTo(19, 3.5f); p.moveTo(14.5f, 6.5f); p.lineTo(4, 17); p.lineTo(7, 20); p.lineTo(17.5f, 9.5f); break;
            case STOP: p.addRoundRect(6, 6, 18, 18, 2.5f, 2.5f, Path.Direction.CW); break;
            case GEAR: p.addCircle(12, 12, 3.2f, Path.Direction.CW); p.addCircle(12, 12, 7.2f, Path.Direction.CW);
                for (int i = 0; i < 8; i++) { double a = Math.PI / 4 * i; p.moveTo((float)(12 + Math.cos(a) * 7.2), (float)(12 + Math.sin(a) * 7.2)); p.lineTo((float)(12 + Math.cos(a) * 9.8), (float)(12 + Math.sin(a) * 9.8)); } break;
            case STAR: case STAR_FILLED:
                for (int i = 0; i < 10; i++) { double a = -Math.PI / 2 + Math.PI / 5 * i; float r = i % 2 == 0 ? 9.5f : 4.2f; float x = (float)(12 + Math.cos(a) * r), y = (float)(12.6 + Math.sin(a) * r); if (i == 0) p.moveTo(x, y); else p.lineTo(x, y); }
                p.close(); filled = type == STAR_FILLED; break;
            case INFO: p.addCircle(12, 12, 9.5f, Path.Direction.CW); p.moveTo(12, 11); p.lineTo(12, 16.5f); p.moveTo(12, 7.6f); p.lineTo(12, 7.7f); break;
            case DOWNLOAD: p.moveTo(12, 3); p.lineTo(12, 15); p.moveTo(7, 10.5f); p.lineTo(12, 15.5f); p.lineTo(17, 10.5f); p.moveTo(4, 20); p.lineTo(20, 20); break;
            case VOICE: case MUTE: p.moveTo(4, 9); p.lineTo(8, 9); p.lineTo(13, 4.5f); p.lineTo(13, 19.5f); p.lineTo(8, 15); p.lineTo(4, 15); p.close();
                if (type == VOICE) { p.moveTo(16.5f, 9); p.quadTo(18.5f, 12, 16.5f, 15); p.moveTo(19, 6.5f); p.quadTo(23, 12, 19, 17.5f); } else { p.moveTo(16.5f, 9.5f); p.lineTo(21.5f, 14.5f); p.moveTo(21.5f, 9.5f); p.lineTo(16.5f, 14.5f); } break;
            case MAP: p.moveTo(3, 6); p.lineTo(9, 3.5f); p.lineTo(15, 6); p.lineTo(21, 3.5f); p.lineTo(21, 18); p.lineTo(15, 20.5f); p.lineTo(9, 18); p.lineTo(3, 20.5f); p.close(); p.moveTo(9, 3.5f); p.lineTo(9, 18); p.moveTo(15, 6); p.lineTo(15, 20.5f); break;
            // A compass needle: the north half solid, so the way it points reads at a glance.
            case COMPASS: p.moveTo(12, 2.5f); p.lineTo(16.5f, 12); p.lineTo(12, 21.5f); p.lineTo(7.5f, 12); p.close(); solid.moveTo(12, 2.5f); solid.lineTo(16.5f, 12); solid.lineTo(7.5f, 12); solid.close(); break;
            // Screen casting: a display with signal arcs from its corner.
            case MIRROR: p.moveTo(2, 9); p.lineTo(2, 6); p.quadTo(2, 4, 4, 4); p.lineTo(20, 4); p.quadTo(22, 4, 22, 6); p.lineTo(22, 18); p.quadTo(22, 20, 20, 20); p.lineTo(14, 20);
                p.moveTo(2, 16.5f); p.quadTo(6, 16.5f, 6, 20.5f); p.moveTo(2, 12.5f); p.quadTo(10, 12.5f, 10, 20.5f); solid.addCircle(2.6f, 20, 1.1f, Path.Direction.CW); break;
            // Place types on the map, Google Maps style.
            case FOOD: p.moveTo(8, 10.5f); p.lineTo(8, 21); p.moveTo(5.5f, 3); p.lineTo(5.5f, 8); p.quadTo(5.5f, 10.5f, 8, 10.5f); p.quadTo(10.5f, 10.5f, 10.5f, 8); p.lineTo(10.5f, 3); p.moveTo(8, 3); p.lineTo(8, 7.5f);
                p.moveTo(16.5f, 21); p.lineTo(16.5f, 3); p.quadTo(19.5f, 5, 19.5f, 11); p.lineTo(16.5f, 12); break;
            case CAFE: p.moveTo(4, 9); p.lineTo(16, 9); p.lineTo(16, 15); p.quadTo(16, 19, 12, 19); p.lineTo(8, 19); p.quadTo(4, 19, 4, 15); p.close();
                p.moveTo(16, 10.5f); p.quadTo(20, 10.5f, 20, 13); p.quadTo(20, 15.5f, 16, 15.5f); p.moveTo(8, 3.5f); p.lineTo(8, 6); p.moveTo(12, 3.5f); p.lineTo(12, 6); break;
            case BAG: p.moveTo(5, 8); p.lineTo(19, 8); p.lineTo(18, 21); p.lineTo(6, 21); p.close(); p.moveTo(9, 8); p.lineTo(9, 6); p.quadTo(9, 3, 12, 3); p.quadTo(15, 3, 15, 6); p.lineTo(15, 8); break;
            case CART: p.moveTo(2.5f, 4); p.lineTo(5.5f, 4); p.lineTo(7.5f, 15); p.lineTo(18, 15); p.lineTo(20.5f, 7); p.lineTo(6.5f, 7); p.addCircle(9, 19.5f, 1.4f, Path.Direction.CW); p.addCircle(17, 19.5f, 1.4f, Path.Direction.CW); break;
            case HEALTH: p.moveTo(12, 4.5f); p.lineTo(12, 19.5f); p.moveTo(4.5f, 12); p.lineTo(19.5f, 12); break;
            case PILL: p.addRoundRect(3.5f, 8.5f, 20.5f, 15.5f, 3.5f, 3.5f, Path.Direction.CW); p.moveTo(12, 8.5f); p.lineTo(12, 15.5f); break;
            case PESO: p.moveTo(8, 20.5f); p.lineTo(8, 3.5f); p.lineTo(13, 3.5f); p.quadTo(18, 3.5f, 18, 8.5f); p.quadTo(18, 13.5f, 13, 13.5f); p.lineTo(8, 13.5f); p.moveTo(4.5f, 7); p.lineTo(20, 7); p.moveTo(4.5f, 10); p.lineTo(20, 10); break;
            case CAP: p.moveTo(2, 9); p.lineTo(12, 4); p.lineTo(22, 9); p.lineTo(12, 14); p.close(); p.moveTo(6, 11); p.lineTo(6, 16); p.quadTo(12, 20, 18, 16); p.lineTo(18, 11); break;
            case CHURCH: p.moveTo(12, 2); p.lineTo(12, 9); p.moveTo(9.5f, 4.5f); p.lineTo(14.5f, 4.5f); p.moveTo(6, 21); p.lineTo(6, 13); p.lineTo(12, 9); p.lineTo(18, 13); p.lineTo(18, 21); p.close();
                p.moveTo(10.5f, 21); p.lineTo(10.5f, 17); p.lineTo(13.5f, 17); p.lineTo(13.5f, 21); break;
            case BED: p.moveTo(3, 19); p.lineTo(3, 6); p.moveTo(3, 14.5f); p.lineTo(21, 14.5f); p.lineTo(21, 19); p.addRoundRect(5, 9.5f, 9.5f, 12.5f, 1, 1, Path.Direction.CW);
                p.moveTo(11.5f, 14.5f); p.lineTo(11.5f, 10); p.lineTo(18.5f, 10); p.quadTo(21, 10, 21, 12.5f); p.lineTo(21, 14.5f); break;
            case FUEL: p.addRoundRect(4.5f, 4, 13.5f, 21, 1.5f, 1.5f, Path.Direction.CW); p.moveTo(7, 8.5f); p.lineTo(11, 8.5f); p.moveTo(13.5f, 9); p.lineTo(16.5f, 9); p.lineTo(19, 11.5f); p.lineTo(19, 17.5f); p.quadTo(19, 19.5f, 17.2f, 19.5f); break;
            case TREE: p.addCircle(12, 9, 6, Path.Direction.CW); p.moveTo(12, 15); p.lineTo(12, 21.5f); break;
            case BUS: p.addRoundRect(5, 3, 19, 18, 2.5f, 2.5f, Path.Direction.CW); p.moveTo(5, 11); p.lineTo(19, 11); p.moveTo(8, 18); p.lineTo(8, 21); p.moveTo(16, 18); p.lineTo(16, 21); break;
            case CIVIC: p.moveTo(3, 21); p.lineTo(21, 21); p.moveTo(3.5f, 9); p.lineTo(12, 4); p.lineTo(20.5f, 9); p.close(); p.moveTo(7, 11.5f); p.lineTo(7, 18); p.moveTo(12, 11.5f); p.lineTo(12, 18); p.moveTo(17, 11.5f); p.lineTo(17, 18); break;
            case ROAD: p.moveTo(8, 3); p.lineTo(4, 21); p.moveTo(16, 3); p.lineTo(20, 21); p.moveTo(12, 4); p.lineTo(12, 7); p.moveTo(12, 11); p.lineTo(12, 14); p.moveTo(12, 18); p.lineTo(12, 20); break;
        }
    }

    public void draw(Canvas c) {
        Rect b = getBounds(); if (b.isEmpty()) return;
        float s = Math.min(b.width(), b.height()) / 24f;
        c.save(); c.translate(b.left + (b.width() - 24 * s) / 2, b.top + (b.height() - 24 * s) / 2); c.scale(s, s);
        paint.setStyle(filled ? Paint.Style.FILL_AND_STROKE : Paint.Style.STROKE);
        c.drawPath(path, paint);
        if (!solid.isEmpty()) { paint.setStyle(Paint.Style.FILL_AND_STROKE); c.drawPath(solid, paint); }
        c.restore();
    }
    public int getIntrinsicWidth() { return -1; }
    public int getIntrinsicHeight() { return -1; }
    public void setAlpha(int a) { paint.setAlpha(a); invalidateSelf(); }
    public void setColorFilter(ColorFilter f) { paint.setColorFilter(f); invalidateSelf(); }
    @SuppressWarnings("deprecation") public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
