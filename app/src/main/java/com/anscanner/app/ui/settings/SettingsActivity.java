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
        binding.rowFeedback.setOnClickListener(v -> showFeedbackDialog());
    }

    private void showFeedbackDialog() {
        com.anscanner.app.databinding.DialogFeedbackBinding dialogBinding =
                com.anscanner.app.databinding.DialogFeedbackBinding.inflate(getLayoutInflater());
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(dialogBinding.getRoot())
                .setCancelable(true)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        dialogBinding.btnFeedbackCancel.setOnClickListener(v -> dialog.dismiss());

        dialogBinding.btnFeedbackSubmit.setOnClickListener(v -> {
            String message = dialogBinding.etFeedbackMessage.getText() != null
                    ? dialogBinding.etFeedbackMessage.getText().toString().trim()
                    : "";

            if (message.isEmpty()) {
                dialogBinding.tilFeedback.setError(getString(R.string.feedback_empty_error));
                return;
            }
            dialogBinding.tilFeedback.setError(null);

            // Disable submit button & cancel button, show loading indicator
            dialogBinding.btnFeedbackSubmit.setEnabled(false);
            dialogBinding.btnFeedbackCancel.setEnabled(false);
            dialogBinding.pbFeedbackLoading.setVisibility(android.view.View.VISIBLE);

            // Prepare feedback payload
            java.util.HashMap<String, Object> feedbackMap = new java.util.HashMap<>();
            feedbackMap.put("message", message);
            feedbackMap.put("timestamp", com.google.firebase.firestore.FieldValue.serverTimestamp());
            feedbackMap.put("device_model", android.os.Build.MODEL);
            feedbackMap.put("os_version", android.os.Build.VERSION.RELEASE);

            com.google.firebase.firestore.FirebaseFirestore db = com.google.firebase.firestore.FirebaseFirestore.getInstance();
            db.collection("feedbacks")
                    .add(feedbackMap)
                    .addOnSuccessListener(documentReference -> {
                        if (isFinishing() || isDestroyed()) return;
                        dialog.dismiss();
                        Toast.makeText(this, R.string.feedback_success, Toast.LENGTH_SHORT).show();
                    })
                    .addOnFailureListener(e -> {
                        if (isFinishing() || isDestroyed()) return;
                        dialogBinding.btnFeedbackSubmit.setEnabled(true);
                        dialogBinding.btnFeedbackCancel.setEnabled(true);
                        dialogBinding.pbFeedbackLoading.setVisibility(android.view.View.GONE);
                        Toast.makeText(this, R.string.feedback_error, Toast.LENGTH_SHORT).show();
                    });
        });

        dialog.show();
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
