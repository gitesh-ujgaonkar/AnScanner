package com.anscanner.app.ui.settings;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.anscanner.app.R;
import com.anscanner.app.databinding.ActivitySettingsBinding;
import com.google.android.gms.ads.AdRequest;

/**
 * SettingsActivity hosts {@link SettingsFragment} and manages the AdMob bottom banner.
 */
public class SettingsActivity extends AppCompatActivity {

    private ActivitySettingsBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.settings_container, new SettingsFragment())
                    .commit();
        }

        setupAds();
    }

    private void setupAds() {
        if (binding != null && binding.adView != null) {
            AdRequest adRequest = new AdRequest.Builder().build();
            binding.adView.loadAd(adRequest);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (binding != null && binding.adView != null) {
            binding.adView.resume();
        }
    }

    @Override
    protected void onPause() {
        if (binding != null && binding.adView != null) {
            binding.adView.pause();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (binding != null && binding.adView != null) {
            binding.adView.destroy();
        }
        super.onDestroy();
        binding = null;
    }
}
