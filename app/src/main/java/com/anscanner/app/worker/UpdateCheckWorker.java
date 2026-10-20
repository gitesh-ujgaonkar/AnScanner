package com.anscanner.app.worker;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.anscanner.app.MainActivity;
import com.anscanner.app.R;
import com.anscanner.app.util.AppUpdateHelper;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.android.play.core.appupdate.AppUpdateInfo;
import com.google.android.play.core.appupdate.AppUpdateManager;
import com.google.android.play.core.appupdate.AppUpdateManagerFactory;
import com.google.android.play.core.install.model.AppUpdateType;
import com.google.android.play.core.install.model.UpdateAvailability;

import java.util.concurrent.TimeUnit;

/**
 * Pure Java background worker using AndroidX WorkManager to periodically check Google Play
 * for app updates and post a policy-compliant local system notification when available.
 */
public class UpdateCheckWorker extends Worker {

    private static final String TAG = "UpdateCheckWorker";

    public static final String WORK_NAME = "AnScannerUpdateCheckWork";
    public static final String CHANNEL_ID = "app_updates_channel";
    public static final String CHANNEL_NAME = "App Updates";
    public static final int NOTIFICATION_ID = 2001;

    public UpdateCheckWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    /**
     * Schedules the unique periodic worker to run once every 24 hours with a network constraint.
     * Uses {@link ExistingPeriodicWorkPolicy#KEEP} to avoid resetting schedule timers on every launch.
     */
    public static void schedulePeriodicCheck(@NonNull Context context) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        PeriodicWorkRequest updateRequest = new PeriodicWorkRequest.Builder(
                UpdateCheckWorker.class, 24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build();

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                updateRequest
        );
        Log.i(TAG, "Scheduled periodic update check worker (24h interval, connected network).");
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        Log.d(TAG, "Executing periodic background update check...");

        try {
            AppUpdateManager updateManager = AppUpdateManagerFactory.create(context);
            Task<AppUpdateInfo> task = updateManager.getAppUpdateInfo();

            // Await the Task synchronously on this background worker thread (30s timeout)
            AppUpdateInfo appUpdateInfo = Tasks.await(task, 30, TimeUnit.SECONDS);

            if (appUpdateInfo != null
                    && appUpdateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                    && appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)) {

                int availableVersionCode = appUpdateInfo.availableVersionCode();
                Log.i(TAG, "Google Play update available: versionCode=" + availableVersionCode);

                // Persist state in SharedPreferences for Settings screen and banner
                AppUpdateHelper.setUpdateAvailableState(context, true, availableVersionCode);

                // Post high-priority local notification
                postUpdateNotification(context);
            } else {
                Log.d(TAG, "No update available during periodic check.");
                AppUpdateHelper.setUpdateAvailableState(context, false, 0);
            }

            return Result.success();
        } catch (Exception e) {
            Log.w(TAG, "Failed to check for app update in background worker: " + e.getMessage());
            return Result.retry();
        }
    }

    /**
     * Posts a local system notification informing the user that a new version of AnScanner is available.
     * Respects Android 13+ POST_NOTIFICATIONS permission and sets up a high-priority notification channel.
     */
    private void postUpdateNotification(@NonNull Context context) {
        // Compatibility check for Android 13+ (API 33+) runtime notification permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "POST_NOTIFICATIONS permission not granted. Skipping notification.");
                return;
            }
        }

        NotificationManager notificationManager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager == null) return;

        // Create high-priority NotificationChannel on Android 8.0+ (API 26+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription(context.getString(R.string.notification_channel_app_updates_desc));
            channel.enableLights(true);
            channel.setLightColor(Color.CYAN);
            channel.enableVibration(true);
            notificationManager.createNotificationChannel(channel);
        }

        // PendingIntent that launches MainActivity with EXTRA_TRIGGER_UPDATE = true
        Intent intent = new Intent(context, MainActivity.class);
        intent.putExtra(AppUpdateHelper.EXTRA_TRIGGER_UPDATE, true);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        int pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingIntentFlags |= PendingIntent.FLAG_IMMUTABLE;
        }

        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                NOTIFICATION_ID,
                intent,
                pendingIntentFlags
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(context.getString(R.string.update_notification_title))
                .setContentText(context.getString(R.string.update_notification_text))
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText(context.getString(R.string.update_notification_text)))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setColor(ContextCompat.getColor(context, R.color.accent_mint))
                .setAutoCancel(true)
                .setContentIntent(pendingIntent);

        notificationManager.notify(NOTIFICATION_ID, builder.build());
        Log.i(TAG, "App update local system notification posted successfully.");
    }
}
