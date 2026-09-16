package com.anscanner.app.service;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.google.android.gms.tasks.Task;
import com.google.android.play.core.review.ReviewInfo;
import com.google.android.play.core.review.ReviewManager;
import com.google.android.play.core.review.ReviewManagerFactory;

/**
 * Helper class for Google Play In-App Review API.
 *
 * <p>Tracks {@code successful_saves_count} in {@link SharedPreferences}.
 * When {@code successful_saves_count == 3}, prompts the user for an in-app review.</p>
 */
public final class ReviewHelper {

    private static final String TAG = "ReviewHelper";
    public static final String PREFS_NAME = "anscanner_prefs";
    public static final String KEY_SUCCESSFUL_SAVES_COUNT = "successful_saves_count";

    private ReviewHelper() {
        // Utility class
    }

    /**
     * Increments the count of successfully generated documents and triggers
     * {@link #showRateAppPromptIfNeeded(Activity)}.
     *
     * @param activity The host activity context.
     */
    public static void onDocumentSaved(Activity activity) {
        if (activity == null) return;
        SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int currentCount = prefs.getInt(KEY_SUCCESSFUL_SAVES_COUNT, 0) + 1;
        prefs.edit().putInt(KEY_SUCCESSFUL_SAVES_COUNT, currentCount).apply();
        Log.i(TAG, "Incremented successful_saves_count to: " + currentCount);

        showRateAppPromptIfNeeded(activity);
    }

    /**
     * Launches the Play Core In-App Review flow if the user has reached 3 successful saves.
     *
     * @param activity The host activity context.
     */
    public static void showRateAppPromptIfNeeded(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }

        SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int count = prefs.getInt(KEY_SUCCESSFUL_SAVES_COUNT, 0);

        if (count == 3) {
            Log.i(TAG, "Requesting Google Play In-App Review (saves count == 3)...");
            ReviewManager manager = ReviewManagerFactory.create(activity);
            Task<ReviewInfo> request = manager.requestReviewFlow();
            request.addOnCompleteListener(task -> {
                if (task.isSuccessful()) {
                    ReviewInfo reviewInfo = task.getResult();
                    Task<Void> flow = manager.launchReviewFlow(activity, reviewInfo);
                    flow.addOnCompleteListener(flowTask -> {
                        Log.i(TAG, "In-App Review flow completed");
                    });
                } else {
                    Log.w(TAG, "In-App Review request failed", task.getException());
                }
            });
        }
    }
}
