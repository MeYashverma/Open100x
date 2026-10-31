# Open100x release rules.
# Model + settings classes are serialised through org.json and enum name lookups.
-keep class com.example.zoomhundred.model.** { *; }

# Custom views are inflated from XML: keep their (Context, AttributeSet) constructors.
-keepclasseswithmembers class * extends android.view.View {
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# CameraX / Guava ship consumer rules; these warnings are from optional backends.
-dontwarn androidx.camera.**
-dontwarn com.google.common.**
-dontwarn org.jetbrains.annotations.**

# Keep crash-report friendly names for our own entry points.
-keep class com.example.zoomhundred.MainActivity { *; }
-keep class com.example.zoomhundred.SettingsActivity { *; }
-keep class com.example.zoomhundred.GalleryActivity { *; }
