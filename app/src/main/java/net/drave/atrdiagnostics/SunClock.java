package net.drave.atrdiagnostics;

import java.util.Calendar;
import java.util.TimeZone;

/** Solar elevation from the NOAA approximation; good to about a minute, which is plenty for a day/night map switch. */
public final class SunClock {
    private SunClock() {}

    public static double elevation(double lat, double lon, long epochMillis) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC")); c.setTimeInMillis(epochMillis);
        int day = c.get(Calendar.DAY_OF_YEAR);
        double hour = c.get(Calendar.HOUR_OF_DAY) + c.get(Calendar.MINUTE) / 60.0 + c.get(Calendar.SECOND) / 3600.0;
        double g = 2 * Math.PI / 365.0 * (day - 1 + (hour - 12) / 24.0);
        double eqTime = 229.18 * (0.000075 + 0.001868 * Math.cos(g) - 0.032077 * Math.sin(g) - 0.014615 * Math.cos(2 * g) - 0.040849 * Math.sin(2 * g));
        double decl = 0.006918 - 0.399912 * Math.cos(g) + 0.070257 * Math.sin(g) - 0.006758 * Math.cos(2 * g) + 0.000907 * Math.sin(2 * g) - 0.002697 * Math.cos(3 * g) + 0.00148 * Math.sin(3 * g);
        double trueSolar = hour * 60 + eqTime + 4 * lon;
        double hourAngle = Math.toRadians(trueSolar / 4 - 180);
        double phi = Math.toRadians(lat);
        double cosZenith = Math.sin(phi) * Math.sin(decl) + Math.cos(phi) * Math.cos(decl) * Math.cos(hourAngle);
        return 90 - Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cosZenith))));
    }

    /** Night once the sun is below the standard sunset line (refraction and solar radius included). */
    public static boolean isNight(double lat, double lon, long epochMillis) { return elevation(lat, lon, epochMillis) < -0.833; }
}
