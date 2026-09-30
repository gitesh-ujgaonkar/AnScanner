# ===========================================================================
# ProGuard / R8 Configuration for AnScanner (Release Builds)
# ===========================================================================

# ── General Attributes & Annotations ───────────────────────────────────────
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable
-keepattributes JavascriptInterface
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
-dontwarn com.google.errorprone.annotations.**

# ── Native Methods & JNI ───────────────────────────────────────────────────
-keepclasseswithmembernames class * {
    native <methods>;
}

# ── Enum Serialization ─────────────────────────────────────────────────────
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ── Parcelable Creators ───────────────────────────────────────────────────
-keepclassmembers class * implements android.os.Parcelable {
    static ** CREATOR;
}

# ── ViewBinding & Data Binding ─────────────────────────────────────────────
-keep class com.anscanner.app.databinding.** { *; }

# ── App Data Models & Room Entities / DAOs ─────────────────────────────────
-keep class com.anscanner.app.data.entity.** { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-dontwarn androidx.room.paging.**

# ── Google AdMob & Play Services Ads ───────────────────────────────────────
-keep public class com.google.android.gms.ads.** {
    public *;
}
-keep public class com.google.ads.** {
    public *;
}
-keep class com.google.android.gms.internal.ads.** { *; }
-dontwarn com.google.android.gms.ads.**
-dontwarn com.google.ads.**

# ── Firebase (Crashlytics, Firestore, Analytics) ───────────────────────────
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**
-keepclassmembers class * {
    @com.google.firebase.firestore.PropertyName <fields>;
    @com.google.firebase.firestore.PropertyName <methods>;
}

# ── Google ML Kit Text Recognition ─────────────────────────────────────────
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.vision.** { *; }
-keep class com.google.android.gms.tasks.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.vision.**

# ── TensorFlow Lite & Support ──────────────────────────────────────────────
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**

# ── OpenCV Android SDK ─────────────────────────────────────────────────────
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**

# ── Apache PDFBox for Android ──────────────────────────────────────────────
-keep class com.tom_roush.pdfbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**
-keep class org.apache.fontbox.** { *; }
-dontwarn org.apache.fontbox.**

# ── Android PDF Viewer (Native PDFium Engine) ──────────────────────────────
-keep class com.github.barteksc.pdfviewer.** { *; }
-dontwarn com.github.barteksc.pdfviewer.**
-keep class com.shockwave.** { *; }
-dontwarn com.shockwave.**

# ── PhotoView (chrisbanes) ──────────────────────────────────────────────────
-keep class com.github.chrisbanes.photoview.** { *; }
-dontwarn com.github.chrisbanes.photoview.**

# ── CameraX ────────────────────────────────────────────────────────────────
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# ── Material Components & AndroidX UI ──────────────────────────────────────
-keep class com.google.android.material.** { *; }
-dontwarn com.google.android.material.**
-keep class androidx.appcompat.widget.** { *; }

# ── Google Play Review ─────────────────────────────────────────────────────
-keep class com.google.android.play.core.** { *; }
-dontwarn com.google.android.play.core.**

# ── Guava ──────────────────────────────────────────────────────────────────
-dontwarn com.google.common.**
