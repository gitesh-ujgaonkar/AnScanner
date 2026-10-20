package com.anscanner.app.ui.splash;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.appcompat.app.AppCompatActivity;

import com.anscanner.app.BuildConfig;
import com.anscanner.app.R;
import com.anscanner.app.databinding.ActivitySplashBinding;
import com.anscanner.app.ui.camera.CameraActivity;
import com.anscanner.app.ui.onboarding.OnboardingActivity;
import com.anscanner.app.util.InsetsHelper;

/**
 * SplashActivity displays the app logo, branding, and dynamic build version
 * for a split second upon app launch before routing to CameraActivity or OnboardingActivity.
 */
public class SplashActivity extends AppCompatActivity {

    private static final int SPLASH_DURATION_MS = 800;

    private ActivitySplashBinding binding;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean hasNavigated = false;

    private final Runnable navigationRunnable = this::proceedToNextScreen;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        InsetsHelper.applyEdgeToEdge(this);
        super.onCreate(savedInstanceState);

        binding = ActivitySplashBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        InsetsHelper.handleSystemBars(binding.getRoot(), null, binding.tvSplashVersion);

        // Dynamically show the app version directly from BuildConfig (defined in build.gradle)
        binding.tvSplashVersion.setText(getString(R.string.app_version, BuildConfig.VERSION_NAME));

        // Allow instant proceed on tap, or wait for the split-second timer
        binding.splashRoot.setOnClickListener(v -> proceedToNextScreen());

        handler.postDelayed(navigationRunnable, SPLASH_DURATION_MS);
    }

    private void proceedToNextScreen() {
        if (hasNavigated || isFinishing() || isDestroyed()) return;
        hasNavigated = true;
        handler.removeCallbacks(navigationRunnable);

        SharedPreferences prefs = getSharedPreferences(
                OnboardingActivity.PREF_NAME, MODE_PRIVATE);
        boolean isFirstLaunch = prefs.getBoolean(
                OnboardingActivity.KEY_IS_FIRST_LAUNCH, true);

        if (!isFirstLaunch && com.anscanner.app.service.AdCacheManager.getInstance().isAppOpenAdAvailable()) {
            com.anscanner.app.service.AdCacheManager.getInstance().showAppOpenAdIfAvailable(this, () -> navigateToTarget(isFirstLaunch));
        } else {
            navigateToTarget(isFirstLaunch);
        }
    }

    private void navigateToTarget(boolean isFirstLaunch) {
        if (isFinishing() || isDestroyed()) return;
        Intent intent = new Intent(this, isFirstLaunch ? OnboardingActivity.class : CameraActivity.class);
        if (getIntent() != null && getIntent().getExtras() != null) {
            intent.putExtras(getIntent().getExtras());
        }
        startActivity(intent);
        finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(navigationRunnable);
    }
}
