# Project-specific ProGuard / R8 rules for Apeiron

# Retain AndroidX Media3 / ExoPlayer components and decoders
-keep class androidx.media3.exoplayer.** { *; }
-keep class androidx.media3.ui.** { *; }
-keep class androidx.media3.common.** { *; }
-dontwarn androidx.media3.**
-dontwarn com.google.errorprone.annotations.**

# Retain Coil ImageLoader & GIF decoder
-keep class coil.** { *; }
-dontwarn coil.**
-keepclassmembers class * implements coil.decode.Decoder$Factory {
    public <init>(...);
}

# Retain Coroutines & Flow internals
-keepclassmembers class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# Strip debug logging in release builds to reduce APK size & improve security
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
}
