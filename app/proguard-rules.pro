# R8 rules for Daanvia's shrunk builds (release, and debug with -Pshrink).
# Activities, services, receivers and the provider named in AndroidManifest.xml are kept by AGP. MapLibre, ML Kit,
# CameraX, Play services location and AndroidX ship their own consumer rules. The app itself uses no reflection or
# class-by-name loading: JSON is read and written by hand with org.json, which is part of the platform.

# Stack traces in logcat and in uploaded support logs keep their line numbers; map them back with
# app/build/outputs/mapping/release/mapping.txt, which belongs with every published APK.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# valhalla-mobile: the routing engine runs in C++ (libvalhalla-wrapper.so). Its own consumer rules keep the HTTP client
# and response that the native side looks up by name, and the Moshi models. Keep the whole Kotlin wrapper as well: it is
# small, the native side and Moshi reach it by name, and a stripped member fails silently as "no route" rather than
# as a crash, which no build check would catch.
-keep class com.valhalla.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class com.valhalla.** { native <methods>; }
