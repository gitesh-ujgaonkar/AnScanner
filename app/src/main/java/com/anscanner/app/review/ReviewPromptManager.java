package com.anscanner.app.review;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.anscanner.app.BuildConfig;
import com.anscanner.app.R;
import com.google.android.gms.tasks.Task;
import com.google.android.play.core.review.ReviewInfo;
import com.google.android.play.core.review.ReviewManager;
import com.google.android.play.core.review.ReviewManagerFactory;

/**
 * Manages standard, Play Store policy-compliant In-App Reviews for AnScanner
 * using the official Google Play Review API in pure Java.
 *
 * Strict Policy Compliance:
 * - Zero custom pre-rating or sentiment gating dialogs.
 * - Manages review prompt quota across version upgrades and document exports.
 */
public class ReviewPromptManager {

    private static final String TAG = "ReviewPromptManager";
    private static final String PREFS_NAME = "anscanner_review_prefs";

    public static final String KEY_LAST_VERSION_CODE = "key_last_version_code";
    public static final String KEY_EXPORT_COUNT = "key_export_count";
    public static final String KEY_TARGET_COUNT = "key_target_count";
    public static final String KEY_HAS_REQUESTED_FLOW = "key_has_requested_flow";

    private final Context context;

    public ReviewPromptManager() {
        this(null);
    }

    public ReviewPromptManager(@Nullable Context context) {
        this.context = context;
    }

    /**
     * Resolves the app's version code using PackageInfo.getLongVersionCode() on API 28+,
     * falling back to versionCode on older platforms.
     */
    public static long getAppVersionCode(@NonNull Context context) {
        try {
            PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return pInfo.getLongVersionCode();
            } else {
                return pInfo.versionCode;
            }
        } catch (PackageManager.NameNotFoundException e) {
            return (long) BuildConfig.VERSION_CODE;
        }
    }

    /**
     * Instance method to process document export completion.
     */
    public void onExportCompleted(@NonNull Activity activity) {
        onExportCompleted(activity, getAppVersionCode(activity));
    }

    /**
     * Convenience instance method if initialized with an Activity context.
     */
    public void onExportCompleted() {
        if (context instanceof Activity) {
            onExportCompleted((Activity) context);
        }
    }

    /**
     * Overload supporting explicit int versionCode.
     */
    public static void onExportCompleted(@NonNull Activity activity, int versionCode) {
        onExportCompleted(activity, (long) versionCode);
    }

    /**
     * Evaluates export quotas and launches the Google Play In-App Review sheet when appropriate.
     */
    public static void onExportCompleted(@NonNull Activity activity, long versionCode) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }

        SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        long lastVersionCode = prefs.getLong(KEY_LAST_VERSION_CODE, -1L);

        // 1. Reset quota if updated to a newer version
        if (lastVersionCode != -1L && versionCode > lastVersionCode) {
            Log.i(TAG, "App updated from version " + lastVersionCode + " to " + versionCode + ". Resetting review prompt quota.");
            prefs.edit()
                    .putLong(KEY_LAST_VERSION_CODE, versionCode)
                    .putInt(KEY_EXPORT_COUNT, 0)
                    .putInt(KEY_TARGET_COUNT, 1)
                    .putBoolean(KEY_HAS_REQUESTED_FLOW, false)
                    .apply();
        } else if (lastVersionCode == -1L) {
            prefs.edit().putLong(KEY_LAST_VERSION_CODE, versionCode).apply();
        }

        if (!prefs.contains(KEY_TARGET_COUNT)) {
            prefs.edit().putInt(KEY_TARGET_COUNT, 1).apply();
        }

        // 2. Do nothing if already requested for current version
        boolean hasRequested = prefs.getBoolean(KEY_HAS_REQUESTED_FLOW, false);
        if (hasRequested) {
            Log.d(TAG, "In-app review already requested for version " + versionCode + ". Skipping.");
            return;
        }

        // 3. Increment export count
        int currentExportCount = prefs.getInt(KEY_EXPORT_COUNT, 0) + 1;
        prefs.edit().putInt(KEY_EXPORT_COUNT, currentExportCount).apply();

        int targetCount = prefs.getInt(KEY_TARGET_COUNT, 1);
        Log.d(TAG, "Export completed. Current count: " + currentExportCount + ", Target: " + targetCount + " (Version: " + versionCode + ")");

        // 4. Launch flow when reaching target count
        if (currentExportCount >= targetCount) {
            launchReviewFlow(activity, versionCode, prefs);
        }
    }

    private static void launchReviewFlow(@NonNull Activity activity, long versionCode, @NonNull SharedPreferences prefs) {
        try {
            ReviewManager manager = ReviewManagerFactory.create(activity);
            Task<ReviewInfo> request = manager.requestReviewFlow();
            request.addOnCompleteListener(task -> {
                if (activity.isFinishing() || activity.isDestroyed()) {
                    return;
                }

                if (task.isSuccessful() && task.getResult() != null) {
                    ReviewInfo reviewInfo = task.getResult();
                    Task<Void> flow = manager.launchReviewFlow(activity, reviewInfo);
                    flow.addOnCompleteListener(flowTask -> {
                        if (flowTask.isSuccessful()) {
                            Log.i(TAG, "Google Play In-App Review flow launched successfully.");
                            prefs.edit()
                                    .putBoolean(KEY_HAS_REQUESTED_FLOW, true)
                                    .putLong(KEY_LAST_VERSION_CODE, versionCode)
                                    .apply();
                        } else {
                            Log.w(TAG, "launchReviewFlow completed with error or deferred.");
                            deferTargetCount(prefs);
                        }
                    });
                } else {
                    String msg = task.getException() != null ? task.getException().getMessage() : "unknown";
                    Log.w(TAG, "requestReviewFlow failed: " + msg);
                    deferTargetCount(prefs);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Exception during review flow execution", e);
            deferTargetCount(prefs);
        }
    }

    private static void deferTargetCount(@NonNull SharedPreferences prefs) {
        int currentTarget = prefs.getInt(KEY_TARGET_COUNT, 1);
        int newTarget = currentTarget + 2;
        prefs.edit().putInt(KEY_TARGET_COUNT, newTarget).apply();
        Log.i(TAG, "Review API deferred. Target count bumped by 2 to: " + newTarget);
    }

    /**
     * Instance helper to open store listing for context provided at construction.
     */
    public void openStoreListing() {
        if (context != null) {
            openStoreListing(context);
        }
    }

    /**
     * Opens the app's Google Play Store listing via market:// URI scheme,
     * falling back to HTTPS web URL if the Play Store app is not installed.
     */
    public static void openStoreListing(@NonNull Context context) {
        String packageName = context.getPackageName();
        Intent marketIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + packageName));
        marketIntent.addFlags(
                Intent.FLAG_ACTIVITY_NO_HISTORY |
                Intent.FLAG_ACTIVITY_NEW_DOCUMENT |
                Intent.FLAG_ACTIVITY_MULTIPLE_TASK |
                Intent.FLAG_ACTIVITY_NEW_TASK
        );

        try {
            context.startActivity(marketIntent);
        } catch (ActivityNotFoundException e) {
            Intent webIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + packageName));
            webIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                context.startActivity(webIntent);
            } catch (Exception ex) {
                Log.e(TAG, "Unable to launch Play Store or browser for package: " + packageName, ex);
                Toast.makeText(context, "Unable to open Google Play Store", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /**
     * Shows a polite user-facing reminder toast.
     */
    public void showPostponeToast() {
        if (context != null) {
            showPostponeToast(context);
        }
    }

    public static void showPostponeToast(@NonNull Context context) {
        Toast.makeText(context.getApplicationContext(),
                "You can review AnScanner anytime from Settings or the Play Store!",
                Toast.LENGTH_LONG).show();
    }

    public static void showReviewReminderToast(@NonNull Context context) {
        showPostponeToast(context);
    }
}
