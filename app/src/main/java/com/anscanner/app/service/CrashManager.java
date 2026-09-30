package com.anscanner.app.service;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.crashlytics.FirebaseCrashlytics;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;

/**
 * Custom crash reporting handler integrating Firebase Crashlytics with user opt-in consent.
 *
 * <p>Catches fatal uncaught exceptions on any thread, writes the stack trace into persistent
 * SharedPreferences, logs device metadata into Crashlytics locally, and delegates to the original
 * default handler to allow clean OS process termination.</p>
 *
 * <p>On the subsequent launch, {@link #checkAndPromptCrashLog(Activity)} prompts the user.
 * If accepted, unsent reports are sent to Firebase Crashlytics (and Cloud Firestore).</p>
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

            // 2. Log context and metadata into Crashlytics locally
            try {
                FirebaseCrashlytics crashlytics = FirebaseCrashlytics.getInstance();
                crashlytics.setCustomKey("device_model", Build.MODEL);
                crashlytics.setCustomKey("os_version", Build.VERSION.RELEASE);
                crashlytics.setCustomKey("thread_name", thread.getName());
                crashlytics.log("Fatal crash on thread " + thread.getName() + ":\n" + stackTrace);
                crashlytics.recordException(throwable);
            } catch (Exception e) {
                Log.e(TAG, "Failed to record crash in Crashlytics", e);
            }

            // 3. Save into SharedPreferences using commit() for synchronous write
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putString(KEY_PENDING_CRASH_LOG, stackTrace).commit();

            SharedPreferences appPrefs = context.getSharedPreferences("anscanner_prefs", Context.MODE_PRIVATE);
            appPrefs.edit().putString(KEY_PENDING_CRASH_LOG, stackTrace).commit();

            Log.e(TAG, "Fatal crash captured:\n" + stackTrace);

        } catch (Exception e) {
            Log.e(TAG, "Failed to persist crash log", e);
        } finally {
            // 4. Call original default handler so the OS and Crashlytics can process & terminate
            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, throwable);
            }
        }
    }

    /**
     * Checks SharedPreferences and Firebase Crashlytics for unsent reports from a previous crash.
     * If found, presents a Material3 dialog asking the user if they'd like to upload it.
     */
    public static void checkAndPromptCrashLog(@NonNull Activity activity) {
        if (activity.isFinishing() || activity.isDestroyed()) return;

        SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String crashLog = prefs.getString(KEY_PENDING_CRASH_LOG, null);
        if (crashLog == null || crashLog.trim().isEmpty()) {
            prefs = activity.getSharedPreferences("anscanner_prefs", Context.MODE_PRIVATE);
            crashLog = prefs.getString(KEY_PENDING_CRASH_LOG, null);
        }

        final String finalCrashLog = crashLog;

        try {
            FirebaseCrashlytics crashlytics = FirebaseCrashlytics.getInstance();
            crashlytics.checkForUnsentReports().addOnCompleteListener(activity, task -> {
                boolean hasUnsentReports = task.isSuccessful() && Boolean.TRUE.equals(task.getResult());
                boolean hasSavedLog = finalCrashLog != null && !finalCrashLog.trim().isEmpty();

                if (hasUnsentReports || hasSavedLog) {
                    showCrashDialog(activity, finalCrashLog);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error checking for unsent Crashlytics reports", e);
            if (finalCrashLog != null && !finalCrashLog.trim().isEmpty()) {
                showCrashDialog(activity, finalCrashLog);
            }
        }
    }

    private static void showCrashDialog(@NonNull Activity activity, String crashLog) {
        if (activity.isFinishing() || activity.isDestroyed()) return;

        new MaterialAlertDialogBuilder(activity)
                .setTitle("Oops! Something went wrong.")
                .setMessage("Send crash logs to devs?")
                .setCancelable(false)
                .setPositiveButton("Yes, send", (dialog, which) -> {
                    // 1. Dispatch unsent reports to Firebase Crashlytics
                    try {
                        FirebaseCrashlytics crashlytics = FirebaseCrashlytics.getInstance();
                        if (crashLog != null && !crashLog.trim().isEmpty()) {
                            crashlytics.log("User consented crash log:\n" + crashLog);
                        }
                        crashlytics.sendUnsentReports();
                        Log.i(TAG, "Unsent reports dispatched to Firebase Crashlytics");
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to send Crashlytics reports", e);
                    }

                    // 2. Prepare crash payload for Firestore backup
                    if (crashLog != null && !crashLog.trim().isEmpty()) {
                        HashMap<String, Object> crashData = new HashMap<>();
                        crashData.put("crash", crashLog);
                        crashData.put("crash_log", crashLog);
                        crashData.put("device_model", Build.MODEL);
                        crashData.put("os_version", Build.VERSION.RELEASE);
                        crashData.put("timestamp", FieldValue.serverTimestamp());

                        try {
                            FirebaseFirestore db = FirebaseFirestore.getInstance();
                            db.collection("crashes")
                                    .add(crashData)
                                    .addOnSuccessListener(ref -> Log.i(TAG, "Crash report uploaded to Firestore: " + ref.getId()))
                                    .addOnFailureListener(e -> Log.e(TAG, "Failed to upload crash report to Firestore", e));
                        } catch (Exception e) {
                            Log.e(TAG, "Error initializing Firestore for crash upload", e);
                        }
                    }

                    // 3. Clear the SharedPreferences key
                    clearCrashLog(activity);

                    Toast.makeText(activity, "Thank you for helping us making app better", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("No", (dialog, which) -> {
                    // Delete unsent reports from Crashlytics
                    try {
                        FirebaseCrashlytics.getInstance().deleteUnsentReports();
                        Log.i(TAG, "Unsent Crashlytics reports deleted on user decline");
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to delete Crashlytics reports", e);
                    }

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
        if (context == null) return;
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().remove(KEY_PENDING_CRASH_LOG).apply();
        context.getSharedPreferences("anscanner_prefs", Context.MODE_PRIVATE)
                .edit().remove(KEY_PENDING_CRASH_LOG).apply();
    }
}
