package com.anscanner.app.ui.library;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;

import androidx.appcompat.app.AppCompatActivity;

import com.anscanner.app.R;
import com.anscanner.app.databinding.ActivityLibraryBinding;
import com.anscanner.app.service.CrashManager;
import com.anscanner.app.ui.camera.CameraActivity;
import com.anscanner.app.ui.settings.SettingsActivity;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.MobileAds;
import com.google.android.material.tabs.TabLayoutMediator;

public class LibraryActivity extends AppCompatActivity {
    private static final String TAG = "LibraryActivity";

    private ActivityLibraryBinding binding;
    private LibraryPagerAdapter pagerAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityLibraryBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Prompt user if a crash occurred previously
        CrashManager.checkAndPromptCrashLog(this);

        MobileAds.initialize(this, initializationStatus -> {});
        AdRequest adRequest = new AdRequest.Builder().build();
        binding.adView.loadAd(adRequest);

        setupViewPagerAndTabs();
        setupBottomNav();
        setupSearch();
    }

    @Override
    protected void onPause() {
        if (binding.adView != null) {
            binding.adView.pause();
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (binding.adView != null) {
            binding.adView.resume();
        }
    }

    private void setupViewPagerAndTabs() {
        pagerAdapter = new LibraryPagerAdapter(this);
        binding.viewPager.setAdapter(pagerAdapter);

        new TabLayoutMediator(binding.tabLayout, binding.viewPager, (tab, position) -> {
            if (position == 0) {
                tab.setText(R.string.tab_scanned_in_app);
            } else {
                tab.setText(R.string.tab_all_device_pdfs);
            }
        }).attach();
    }

    private void setupBottomNav() {
        binding.fabCamera.setOnClickListener(v -> {
            startActivity(new Intent(this, CameraActivity.class));
        });

        binding.navSettings.setOnClickListener(v -> {
            startActivity(new Intent(this, SettingsActivity.class));
        });

        binding.navLibrary.setOnClickListener(v -> {
            if (pagerAdapter != null) {
                pagerAdapter.scrollToTop(binding.viewPager.getCurrentItem());
            }
        });
    }

    private void setupSearch() {
        binding.searchView.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                if (pagerAdapter != null) {
                    pagerAdapter.setFilterQuery(s != null ? s.toString() : "");
                }
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (binding != null && binding.adView != null) {
            binding.adView.destroy();
        }
        super.onDestroy();
    }
}
