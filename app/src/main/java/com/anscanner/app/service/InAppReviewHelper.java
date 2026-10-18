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
        new com.anscanner.app.review.ReviewPromptManager(activity).onExportCompleted(activity);
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
