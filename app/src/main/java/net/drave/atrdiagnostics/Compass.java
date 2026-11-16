package net.drave.atrdiagnostics;

import android.content.Context;
import android.hardware.*;
import android.hardware.display.DisplayManager;
import android.os.SystemClock;
import android.view.Display;
import android.view.Surface;

/**
 * Which way the phone faces, for a ride that starts from a standstill (a pump, a parking space): GPS has no course until
 * the bike moves, and the bearing kept from before could point anywhere, so the arrow and the heading-up map would face
 * north. NavService listens only from the ride's start until the first GPS course, a few seconds of sensor use; after
 * that the last course is kept through every stop, since a phone in a pocket or near the engine says nothing reliable.
 *
 * <p>Works for a phone lying flat (the top edge points ahead) and one standing in a handlebar mount (the back faces
 * ahead), in any screen rotation, the way map apps' compasses do it.
 */
final class Compass implements SensorEventListener {
    private final SensorManager sensors; private final Context c;
    private final float[] r = new float[9], out = new float[9], o = new float[3];
    private volatile float heading = Float.NaN; private volatile long at; private boolean on; private float declination;

    Compass(Context c) { this.c = c.getApplicationContext(); sensors = c.getSystemService(SensorManager.class); }

    void start(double lat, double lon) {
        if (on || sensors == null) return;
        Sensor s = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR); if (s == null) return;
        declination = new GeomagneticField((float)lat, (float)lon, 0, System.currentTimeMillis()).getDeclination();
        on = sensors.registerListener(this, s, SensorManager.SENSOR_DELAY_UI);
    }
    void stop() { if (!on) return; on = false; sensors.unregisterListener(this); heading = Float.NaN; }
    boolean running() { return on; }

    /** Degrees clockwise from true north, or NaN without a fresh, trustworthy reading. */
    float heading() { return SystemClock.elapsedRealtime() - at < 2000 ? heading : Float.NaN; }

    @Override public void onSensorChanged(SensorEvent e) {
        SensorManager.getRotationMatrixFromVector(r, e.values);
        // r[8]: how much the screen faces up. Flat, the top edge points ahead; upright in a mount, the phone's back does.
        boolean flat = Math.abs(r[8]) > 0.7f;
        int x, y;
        switch (rotation()) {
            case Surface.ROTATION_90: x = flat ? SensorManager.AXIS_Y : SensorManager.AXIS_Z; y = SensorManager.AXIS_MINUS_X; break;
            case Surface.ROTATION_180: x = SensorManager.AXIS_MINUS_X; y = flat ? SensorManager.AXIS_MINUS_Y : SensorManager.AXIS_MINUS_Z; break;
            case Surface.ROTATION_270: x = flat ? SensorManager.AXIS_MINUS_Y : SensorManager.AXIS_MINUS_Z; y = SensorManager.AXIS_X; break;
            default: x = SensorManager.AXIS_X; y = flat ? SensorManager.AXIS_Y : SensorManager.AXIS_Z;
        }
        if (!SensorManager.remapCoordinateSystem(r, x, y, out)) return;
        SensorManager.getOrientation(out, o);
        float h = (float)GeoMath.normalise(Math.toDegrees(o[0]) + declination);
        // Smoothed the short way round, so the reading doesn't swing through 180 between 359 and 1 degrees.
        heading = Float.isNaN(heading) ? h : (float)GeoMath.normalise(heading + GeoMath.turn(heading, h) * 0.25f);
        at = SystemClock.elapsedRealtime();
    }
    @Override public void onAccuracyChanged(Sensor s, int accuracy) { if (accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) heading = Float.NaN; }

    private int rotation() {
        try { DisplayManager dm = c.getSystemService(DisplayManager.class); Display d = dm == null ? null : dm.getDisplay(Display.DEFAULT_DISPLAY); return d == null ? Surface.ROTATION_0 : d.getRotation(); }
        catch (RuntimeException ex) { return Surface.ROTATION_0; }
    }
}
