package com.anscanner.app.service;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;

import com.anscanner.app.BuildConfig;
import com.google.android.gms.tasks.Task;
import com.google.android.play.core.review.ReviewInfo;
import com.google.android.play.core.review.ReviewManager;
import com.google.android.play.core.review.ReviewManagerFactory;
import com.google.android.play.core.review.testing.FakeReviewManager;

/**
 * Manages Google Play In-App Review requests based on user engagement.
 *
 * <p>Tracks scan iterations in {@link SharedPreferences}:
 * <ul>
 *   <li>Prompts on the 1st scan completion.</li>
 *   <li>Prompts every 3rd subsequent scan if unreviewed (4, 7, 10...).</li>
 *   <li>Permanently suppresses prompts once reviewed.</li>
 *   <li>Uses {@link FakeReviewManager} in {@code DEBUG} builds for local testing.</li>
 * </ul>
 * </p>
 */
public final class InAppReviewHelper {

    private static final String TAG = "InAppReviewHelper";
    public static final String PREFS_NAME = "anscanner_prefs";
    public static final String KEY_SCAN_COUNT = "scan_count";
    public static final String KEY_HAS_REVIEWED = "has_reviewed";

    private InAppReviewHelper() {}

    /**
     * Triggered when a document scan is successfully finalized and saved.
     * Increments the scan counter and evaluates review prompt criteria.
     *
     * @param activity Hosting Activity to anchor the review dialog to.
     */
    public static void onScanCompleted(@NonNull Activity activity) {
        if (activity.isFinishing() || activity.isDestroyed()) return;

        SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean hasReviewed = prefs.getBoolean(KEY_HAS_REVIEWED, false);
        if (hasReviewed) {
            return;
        }

        int scanCount = prefs.getInt(KEY_SCAN_COUNT, 0) + 1;
        prefs.edit().putInt(KEY_SCAN_COUNT, scanCount).apply();

        Log.d(TAG, "Scan completed. Current scan_count=" + scanCount + ", has_reviewed=" + hasReviewed);

        // Trigger on 1st scan, then every 3rd scan thereafter (1, 4, 7, 10...)
        if (scanCount == 1 || (scanCount > 1 && (scanCount - 1) % 3 == 0)) {
            triggerReviewFlow(activity, prefs);
        }
    }

    private static void triggerReviewFlow(@NonNull Activity activity, @NonNull SharedPreferences prefs) {
        final ReviewManager manager;
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Using FakeReviewManager for local debug verification");
            manager = new FakeReviewManager(activity);
        } else {
            manager = ReviewManagerFactory.create(activity);
        }

        Task<ReviewInfo> request = manager.requestReviewFlow();
        request.addOnCompleteListener(task -> {
            if (task.isSuccessful() && task.getResult() != null) {
                ReviewInfo reviewInfo = task.getResult();
                Task<Void> flow = manager.launchReviewFlow(activity, reviewInfo);
                flow.addOnCompleteListener(flowTask -> {
                    // Review dialog completed or dismissed
                    Log.d(TAG, "In-app review flow completed");
                    prefs.edit().putBoolean(KEY_HAS_REVIEWED, true).apply();
                });
            } else {
                Log.w(TAG, "Failed to initialize review flow", task.getException());
            }
        });
    }
}
