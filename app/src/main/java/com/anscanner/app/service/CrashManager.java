package com.anscanner.app.service;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;

/**
 * Custom crash reporting handler.
 *
 * <p>Catches fatal uncaught exceptions on any thread, writes the full stack trace
 * into persistent SharedPreferences, and delegates to the original default handler
 * to allow clean OS process termination.</p>
 *
 * <p>On the subsequent launch, {@link #checkAndPromptCrashLog(Activity)} prompts the
 * user to submit the crash report to Cloud Firestore.</p>
 */
public class CrashManager implements Thread.UncaughtExceptionHandler {

    private static final String TAG = "CrashManager";
    public static final String PREFS_NAME = "anscanner_crash_prefs";
    public static final String KEY_PENDING_CRASH_LOG = "pending_crash_log";

    private final Context context;
    private final Thread.UncaughtExceptionHandler defaultHandler;

    public CrashManager(Context context) {
        this.context = context.getApplicationContext();
        this.defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
    }

    public static void install(Context context) {
        Thread.setDefaultUncaughtExceptionHandler(new CrashManager(context));
    }

    @Override
    public void uncaughtException(@NonNull Thread thread, @NonNull Throwable throwable) {
        try {
            // 1. Extract Throwable stack trace into a String
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            throwable.printStackTrace(pw);
            String stackTrace = sw.toString();

            // 2. Save into SharedPreferences using commit() for synchronous write
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putString(KEY_PENDING_CRASH_LOG, stackTrace).commit();

            SharedPreferences appPrefs = context.getSharedPreferences("anscanner_prefs", Context.MODE_PRIVATE);
            appPrefs.edit().putString(KEY_PENDING_CRASH_LOG, stackTrace).commit();

            Log.e(TAG, "Fatal crash captured:\n" + stackTrace);

        } catch (Exception e) {
            Log.e(TAG, "Failed to persist crash log", e);
        } finally {
            // 3. Call original default handler so the OS can terminate the app properly
            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, throwable);
            }
        }
    }

    /**
     * Checks SharedPreferences for a pending crash log from a previous session.
     * If found, presents a Material3 dialog asking the user if they'd like to upload it.
     */
    public static void checkAndPromptCrashLog(@NonNull Activity activity) {
        SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String crashLog = prefs.getString(KEY_PENDING_CRASH_LOG, null);
        if (crashLog == null || crashLog.trim().isEmpty()) {
            prefs = activity.getSharedPreferences("anscanner_prefs", Context.MODE_PRIVATE);
            crashLog = prefs.getString(KEY_PENDING_CRASH_LOG, null);
        }

        if (crashLog == null || crashLog.trim().isEmpty()) {
            return;
        }

        final String finalCrashLog = crashLog;

        new MaterialAlertDialogBuilder(activity)
                .setTitle("Oops! Something went wrong.")
                .setMessage("Send crash logs to devs?")
                .setCancelable(false)
                .setPositiveButton("Yes, send", (dialog, which) -> {
                    // Prepare crash payload for Firestore
                    HashMap<String, Object> crashData = new HashMap<>();
                    crashData.put("crash", finalCrashLog);
                    crashData.put("crash_log", finalCrashLog);
                    crashData.put("device_model", Build.MODEL);
                    crashData.put("os_version", Build.VERSION.RELEASE);
                    crashData.put("timestamp", FieldValue.serverTimestamp());

                    try {
                        FirebaseFirestore db = FirebaseFirestore.getInstance();
                        db.collection("crashes")
                                .add(crashData)
                                .addOnSuccessListener(ref -> Log.i(TAG, "Crash report uploaded: " + ref.getId()))
                                .addOnFailureListener(e -> Log.e(TAG, "Failed to upload crash report", e));
                    } catch (Exception e) {
                        Log.e(TAG, "Error initializing Firestore for crash upload", e);
                    }

                    // Clear the SharedPreferences key
                    clearCrashLog(activity);

                    Toast.makeText(activity, "Thank you for helping us making app better", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("No", (dialog, which) -> {
                    // Clear the SharedPreferences key
                    clearCrashLog(activity);

                    Toast.makeText(activity, "Please restart app", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    /**
     * Clears the pending crash log from SharedPreferences.
     */
    public static void clearCrashLog(Context context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().remove(KEY_PENDING_CRASH_LOG).apply();
        context.getSharedPreferences("anscanner_prefs", Context.MODE_PRIVATE)
                .edit().remove(KEY_PENDING_CRASH_LOG).apply();
    }
}
