package com.anscanner.app.ui.settings;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.anscanner.app.R;
import com.anscanner.app.databinding.ActivitySettingsBinding;
import com.anscanner.app.service.CacheManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SettingsActivity extends AppCompatActivity {
    private ActivitySettingsBinding binding;
    private SharedPreferences prefs;
    private ExecutorService executor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        prefs = getSharedPreferences("anscanner_prefs", MODE_PRIVATE);
        executor = Executors.newSingleThreadExecutor();

        setupUI();
        updateCacheSize();
    }

    private void setupUI() {
        binding.btnBack.setOnClickListener(v -> finish());

        binding.btnClearCache.setOnClickListener(v -> clearCache());

        boolean analyticsEnabled = prefs.getBoolean("analytics_enabled", true);
        binding.switchAnalytics.setChecked(analyticsEnabled);
        binding.switchAnalytics.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("analytics_enabled", isChecked).apply();
        });

        binding.rowPrivacyPolicy.setOnClickListener(v -> showPrivacyPolicy());
    }

    private void updateCacheSize() {
        executor.execute(() -> {
            String size = CacheManager.getCacheSizeFormatted(this);
            runOnUiThread(() -> {
                binding.tvCacheSize.setText(getString(R.string.settings_cache_size, size));
            });
        });
    }

    private void clearCache() {
        executor.execute(() -> {
            CacheManager.clearAllCache(this);
            runOnUiThread(() -> {
                Toast.makeText(this, R.string.settings_cache_cleared, Toast.LENGTH_SHORT).show();
                updateCacheSize();
            });
        });
    }

    private void showPrivacyPolicy() {
        WebView webView = new WebView(this);
        webView.loadUrl("file:///android_asset/privacy_policy.html");
        
        new MaterialAlertDialogBuilder(this)
                .setView(webView)
                .setPositiveButton("Close", null)
                .show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}
