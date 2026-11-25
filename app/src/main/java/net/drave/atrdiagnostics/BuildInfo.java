package net.drave.atrdiagnostics;

public final class BuildInfo {
    /** The app's version, from versionName in app/build.gradle. */
    public static final String VERSION = BuildConfig.VERSION_NAME;
    /** The Google Play build: Play updates it, so it has no self-updater (see the store flavors in app/build.gradle). */
    public static final boolean PLAY = BuildConfig.PLAY;
    private BuildInfo() {}
}
