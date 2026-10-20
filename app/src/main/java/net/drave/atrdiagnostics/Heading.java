package net.drave.atrdiagnostics;

import android.app.Activity;
import android.hardware.GeomagneticField;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.Surface;

/**
 * Which way the phone is facing, from its compass, the way Google Maps turns the beam on its blue dot as you turn
 * around. Uses Android's rotation vector (compass, accelerometer and gyroscope fused), corrected for how the screen is
 * turned and for the difference between magnetic and true north where the rider is. Smoothed so it doesn't shiver.
 */
final class Heading implements SensorEventListener {
    interface Sink { void heading(float degrees); }

    private final Activity a; private final NavSession s; private final Sink sink;
    private final SensorManager sm; private final Sensor sensor;
    private final float[] rot = new float[9], remapped = new float[9], angles = new float[3];
    private float smooth = Float.NaN, sent = Float.NaN, declination; private double declAt = 999;
    private long sentAt; private boolean on;

    Heading(Activity a, NavSession s, Sink sink) {
        this.a = a; this.s = s; this.sink = sink;
        sm = a.getSystemService(SensorManager.class);
        Sensor r = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if (r == null && sm != null) r = sm.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR);
        sensor = r;
    }

    /** Whether the phone has the sensors for it. */
    boolean available() { return sensor != null; }
    /** The last heading in degrees from true north, or NaN before the first reading. */
    float degrees() { return smooth; }

    void start() { if (on || sensor == null) return; on = true; sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI); }
    void stop() { if (!on) return; on = false; sm.unregisterListener(this); smooth = Float.NaN; sent = Float.NaN; }

    public void onSensorChanged(SensorEvent e) {
        SensorManager.getRotationMatrixFromVector(rot, e.values);
        // The screen's "up" is the phone's top only when held upright; turned sideways, the axes swap.
        int x = SensorManager.AXIS_X, y = SensorManager.AXIS_Y;
        switch (a.getWindowManager().getDefaultDisplay().getRotation()) {
            case Surface.ROTATION_90: x = SensorManager.AXIS_Y; y = SensorManager.AXIS_MINUS_X; break;
            case Surface.ROTATION_180: x = SensorManager.AXIS_MINUS_X; y = SensorManager.AXIS_MINUS_Y; break;
            case Surface.ROTATION_270: x = SensorManager.AXIS_MINUS_Y; y = SensorManager.AXIS_X; break;
        }
        SensorManager.remapCoordinateSystem(rot, x, y, remapped);
        SensorManager.getOrientation(remapped, angles);
        // Magnetic north is a little off true north (about -1° in the Philippines); the map is drawn to true north.
        if (Math.abs(s.lat - declAt) > 0.5) { declAt = s.lat; declination = new GeomagneticField((float)s.lat, (float)s.lon, 0, System.currentTimeMillis()).getDeclination(); }
        float deg = (float)((Math.toDegrees(angles[0]) + declination + 360) % 360);
        smooth = Float.isNaN(smooth) ? deg : (smooth + turn(smooth, deg) * 0.25f + 360) % 360;
        // Redraw only when it has turned enough to see, at most 20 times a second.
        long now = android.os.SystemClock.uptimeMillis();
        if (Float.isNaN(sent) || Math.abs(turn(sent, smooth)) >= 2 && now - sentAt >= 50) { sent = smooth; sentAt = now; sink.heading(smooth); }
    }
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    /** The shortest turn from {@code from} to {@code to}, -180..180. */
    static float turn(float from, float to) { return ((to - from + 540) % 360) - 180; }
}
