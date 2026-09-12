package com.anscanner.app;

import android.app.Application;
import android.util.Log;

import org.opencv.android.OpenCVLoader;

/**
 * AnScanner Application class.
 * Initializes OpenCV native libraries and Room database on app startup.
 */
public class AnScannerApplication extends Application {

    private static final String TAG = "AnScannerApp";

    private static AnScannerApplication instance;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        // ── Initialize OpenCV ───────────────────────────────────────────
        // initLocal() loads the bundled libopencv_java4.so from the APK.
        // No OpenCV Manager app is needed.
        if (OpenCVLoader.initLocal()) {
            Log.i(TAG, "OpenCV loaded successfully via initLocal()");
        } else {
            Log.e(TAG, "OpenCV initialization failed! Image processing will not work.");
        }

        // ── Initialize Room Database (lazy singleton) ───────────────────
        // The database is lazily instantiated on first DAO access via
        // AppDatabase.getInstance(context). No eager init needed here.

        // ── Initialize AdMob (uncomment when google-services.json is added) ──
        // MobileAds.initialize(this, initializationStatus -> {
        //     Log.i(TAG, "AdMob initialized");
        // });

        Log.i(TAG, "AnScanner Application initialized");
    }

    /**
     * Returns the singleton Application instance.
     * Useful for accessing application context from non-Activity classes.
     */
    public static AnScannerApplication getInstance() {
        return instance;
    }
}
