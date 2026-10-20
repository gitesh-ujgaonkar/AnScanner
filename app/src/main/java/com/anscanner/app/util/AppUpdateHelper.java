package com.anscanner.app.util;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.anscanner.app.R;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.play.core.appupdate.AppUpdateInfo;
import com.google.android.play.core.appupdate.AppUpdateManager;
import com.google.android.play.core.appupdate.AppUpdateManagerFactory;
import com.google.android.play.core.install.InstallState;
import com.google.android.play.core.install.InstallStateUpdatedListener;
import com.google.android.play.core.install.model.AppUpdateType;
import com.google.android.play.core.install.model.InstallStatus;
import com.google.android.play.core.install.model.UpdateAvailability;

/**
 * Pure Java helper for Google Play In-App Updates using the Flexible update flow.
 *
 * <p>Policy-compliant: uses only the official Play Core {@link AppUpdateManager} API,
 * no custom pre-update prompts, no external APK downloads, and no forced updates.</p>
 */
public class AppUpdateHelper {

    private static final String TAG = "AppUpdateHelper";

    /** Extra flag set when launching an activity from the update notification. */
    public static final String EXTRA_TRIGGER_UPDATE = "EXTRA_TRIGGER_UPDATE";

    /** Request code used with {@code startUpdateFlowForResult}. */
    public static final int RC_APP_UPDATE = 1001;

    // SharedPreferences keys for persisting known update availability
    public static final String PREF_NAME = "anscanner_app_update_prefs";
    public static final String KEY_UPDATE_AVAILABLE = "key_update_available";
    public static final String KEY_AVAILABLE_VERSION_CODE = "key_available_version_code";
    public static final String KEY_LAST_UPDATE_CHECK_TS = "key_last_update_check_ts";

    private final Activity activity;
    private final AppUpdateManager appUpdateManager;
    private final InstallStateUpdatedListener installStateListener;

    /**
     * Callback interface for asynchronous update check queries.
     */
    public interface UpdateCheckCallback {
        void onUpdateAvailable(@NonNull AppUpdateInfo appUpdateInfo);
        void onUpToDate();
        void onFailure(@NonNull Exception e);
    }

    public AppUpdateHelper(@NonNull Activity activity) {
        this.activity = activity;
        this.appUpdateManager = AppUpdateManagerFactory.create(activity);

        // Listen for flexible download-state transitions (PENDING → DOWNLOADING → DOWNLOADED)
        this.installStateListener = new InstallStateUpdatedListener() {
            @Override
            public void onStateUpdate(@NonNull InstallState state) {
                if (state.installStatus() == InstallStatus.DOWNLOADED) {
                    Log.i(TAG, "Update downloaded — prompting user to restart.");
                    showUpdateReadySnackbar();
                } else if (state.installStatus() == InstallStatus.FAILED) {
                    Log.w(TAG, "In-app update download failed.");
                }
            }
        };

        appUpdateManager.registerListener(installStateListener);
    }

    public AppUpdateManager getAppUpdateManager() {
        return appUpdateManager;
    }

    // ── SharedPreferences Persistence ──────────────────────────────────────

    public static void setUpdateAvailableState(@NonNull Context context, boolean isAvailable, int versionCode) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit()
                .putBoolean(KEY_UPDATE_AVAILABLE, isAvailable)
                .putInt(KEY_AVAILABLE_VERSION_CODE, versionCode)
                .putLong(KEY_LAST_UPDATE_CHECK_TS, System.currentTimeMillis())
                .apply();
    }

    public static boolean isUpdateKnownAvailable(@NonNull Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_UPDATE_AVAILABLE, false);
    }

    public static int getAvailableVersionCode(@NonNull Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getInt(KEY_AVAILABLE_VERSION_CODE, 0);
    }

    // ── Update Checking & Triggering ───────────────────────────────────────

    /**
     * Checks Google Play for an available flexible update without callback.
     */
    public void checkForUpdate() {
        checkUpdate(null);
    }

    /**
     * Checks Google Play for an available flexible update and notifies the optional callback.
     */
    public void checkUpdate(@Nullable UpdateCheckCallback callback) {
        appUpdateManager.getAppUpdateInfo().addOnSuccessListener(appUpdateInfo -> {
            if (activity.isFinishing() || activity.isDestroyed()) return;

            boolean isAvailable = appUpdateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                    && appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE);

            setUpdateAvailableState(activity, isAvailable, appUpdateInfo.availableVersionCode());

            if (isAvailable) {
                Log.i(TAG, "Flexible update available (versionCode=" + appUpdateInfo.availableVersionCode() + ").");
                if (callback != null) {
                    callback.onUpdateAvailable(appUpdateInfo);
                }
            } else {
                Log.d(TAG, "No flexible update available (availability=" + appUpdateInfo.updateAvailability() + ").");
                if (callback != null) {
                    callback.onUpToDate();
                }
            }
        }).addOnFailureListener(e -> {
            Log.w(TAG, "AppUpdateInfo check failed: " + e.getMessage());
            if (callback != null) {
                callback.onFailure(e);
            }
        });
    }

    /**
     * Directly starts the official Google Play In-App Update flow with the provided AppUpdateInfo.
     */
    public void startFlexibleUpdate(@NonNull AppUpdateInfo appUpdateInfo) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        try {
            appUpdateManager.startUpdateFlowForResult(
                    appUpdateInfo,
                    AppUpdateType.FLEXIBLE,
                    activity,
                    RC_APP_UPDATE
            );
            Log.i(TAG, "Flexible update flow started.");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start flexible update flow", e);
        }
    }

    /**
     * Immediately checks and launches the flexible update flow, showing a toast if already up to date.
     */
    public void startFlexibleUpdateFlow() {
        checkUpdate(new UpdateCheckCallback() {
            @Override
            public void onUpdateAvailable(@NonNull AppUpdateInfo appUpdateInfo) {
                startFlexibleUpdate(appUpdateInfo);
            }

            @Override
            public void onUpToDate() {
                Toast.makeText(activity, R.string.settings_app_up_to_date, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onFailure(@NonNull Exception e) {
                Toast.makeText(activity, R.string.settings_update_check_failed, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * Call from {@code Activity.onResume()} to detect if an update completed downloading in the background.
     */
    public void onResume() {
        appUpdateManager.getAppUpdateInfo().addOnSuccessListener(appUpdateInfo -> {
            if (activity.isFinishing() || activity.isDestroyed()) return;

            if (appUpdateInfo.installStatus() == InstallStatus.DOWNLOADED) {
                Log.i(TAG, "Update already downloaded (detected in onResume).");
                showUpdateReadySnackbar();
            }
        });
    }

    /**
     * Call from {@code Activity.onDestroy()} to prevent memory leaks.
     */
    public void onDestroy() {
        if (appUpdateManager != null && installStateListener != null) {
            appUpdateManager.unregisterListener(installStateListener);
        }
    }

    /**
     * Shows a Snackbar informing the user that the update is ready and offers a "RESTART" action.
     */
    public void showUpdateReadySnackbar() {
        if (activity.isFinishing() || activity.isDestroyed()) return;

        View rootView = activity.findViewById(android.R.id.content);
        if (rootView == null) return;

        Snackbar.make(rootView,
                        R.string.update_ready_to_install,
                        Snackbar.LENGTH_INDEFINITE)
                .setAction(R.string.update_restart_button, v -> {
                    Log.i(TAG, "User tapped RESTART — completing update.");
                    appUpdateManager.completeUpdate();
                })
                .show();
    }
}
