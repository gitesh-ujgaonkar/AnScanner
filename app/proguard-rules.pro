# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# ── OpenCV ───────────────────────────────────────────────────────────────
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**

# ── Room ─────────────────────────────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }

# ── Material Components ──────────────────────────────────────────────────
-keep class com.google.android.material.** { *; }
-dontwarn com.google.android.material.**

# ── CameraX ──────────────────────────────────────────────────────────────
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# ── Keep ViewBinding generated classes ───────────────────────────────────
-keep class com.anscanner.app.databinding.** { *; }

# ── Prevent stripping of Parcelable creators ─────────────────────────────
-keepclassmembers class * implements android.os.Parcelable {
    static ** CREATOR;
}

# ── Prevent obfuscation of data entity classes ───────────────────────────
-keep class com.anscanner.app.data.entity.** { *; }

# ── PhotoView ────────────────────────────────────────────────────────────
-keep class com.github.chrisbanes.photoview.** { *; }
-dontwarn com.github.chrisbanes.photoview.**
