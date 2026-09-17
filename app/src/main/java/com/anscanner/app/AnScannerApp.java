package com.anscanner.app;

import android.app.Application;
import android.util.Log;

import com.anscanner.app.service.CrashManager;

/**
 * AnScanner Application class.
 * Initializes CrashManager for global uncaught exception catching
 * and application-level configuration on startup.
 */
public class AnScannerApp extends Application {

    private static final String TAG = "AnScannerApp";

    private static AnScannerApp instance;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        // ── Apply Persisted Theme (Defaults to Dark Mode) ───────────────────
        com.anscanner.app.util.ThemeHelper.applyTheme(this);

        // ── Global Crash Handler ───────────────────────────────────────────
        Thread.setDefaultUncaughtExceptionHandler(new CrashManager(this));
        Log.i(TAG, "CrashManager registered as default uncaught exception handler");

        // ── Initialize OpenCV ───────────────────────────────────────────
        if (org.opencv.android.OpenCVLoader.initLocal()) {
            Log.i(TAG, "OpenCV loaded successfully via initLocal()");
        } else {
            Log.e(TAG, "OpenCV initialization failed! Image processing will not work.");
        }

        Log.i(TAG, "AnScanner Application initialized");
    }

    /**
     * Returns the singleton Application instance.
     */
    public static AnScannerApp getInstance() {
        return instance;
    }
}
