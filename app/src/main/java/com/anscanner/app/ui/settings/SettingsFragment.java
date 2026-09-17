package com.anscanner.app.ui.settings;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.anscanner.app.R;
import com.anscanner.app.databinding.DialogFeedbackBinding;
import com.anscanner.app.databinding.FragmentSettingsBinding;
import com.anscanner.app.service.CacheManager;
import com.anscanner.app.util.ThemeHelper;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SettingsFragment hosts the app preferences including Theme Selection (Dark, Light, System),
 * Cache clearing, Analytics preferences, Privacy Policy, Terms, and Feedback.
 */
public class SettingsFragment extends Fragment {

    private FragmentSettingsBinding binding;
    private SharedPreferences prefs;
    private ExecutorService executor;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentSettingsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        Context context = requireContext();
        prefs = context.getSharedPreferences("anscanner_prefs", Context.MODE_PRIVATE);
        executor = Executors.newSingleThreadExecutor();

        setupUI();
        updateCacheSize();
    }

    private void setupUI() {
        binding.btnBack.setOnClickListener(v -> {
            if (getActivity() != null) {
                getActivity().finish();
            }
        });

        // ── Theme Selector ──────────────────────────────────────────────────
        updateThemeDisplay();
        binding.rowTheme.setOnClickListener(v -> showThemeDialog());

        // ── Cache ───────────────────────────────────────────────────────────
        binding.btnClearCache.setOnClickListener(v -> clearCache());

        // ── Analytics ───────────────────────────────────────────────────────
        boolean analyticsEnabled = prefs.getBoolean("analytics_enabled", true);
        binding.switchAnalytics.setChecked(analyticsEnabled);
        binding.switchAnalytics.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("analytics_enabled", isChecked).apply();
        });

        // ── Legal & Feedback ────────────────────────────────────────────────
        binding.rowPrivacyPolicy.setOnClickListener(v -> showPrivacyPolicy());
        binding.rowTerms.setOnClickListener(v -> showTermsAndConditions());
        binding.rowFeedback.setOnClickListener(v -> showFeedbackDialog());
    }

    private void updateThemeDisplay() {
        if (binding == null || getContext() == null) return;
        String currentTheme = ThemeHelper.getThemePreference(requireContext());
        binding.tvCurrentTheme.setText(ThemeHelper.getThemeDisplayName(requireContext(), currentTheme));
    }

    /**
     * Shows a Material3 single-choice dialog for Dark (Default), Light, and System Default.
     */
    private void showThemeDialog() {
        if (getContext() == null || getActivity() == null) return;

        String currentTheme = ThemeHelper.getThemePreference(requireContext());
        int checkedItem = 0;
        if (ThemeHelper.THEME_LIGHT.equalsIgnoreCase(currentTheme)) {
            checkedItem = 1;
        } else if (ThemeHelper.THEME_SYSTEM.equalsIgnoreCase(currentTheme)) {
            checkedItem = 2;
        }

        String[] themeOptions = new String[]{
                getString(R.string.theme_dark_default),
                getString(R.string.theme_light),
                getString(R.string.theme_system)
        };

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_theme_dialog_title)
                .setSingleChoiceItems(themeOptions, checkedItem, (dialog, which) -> {
                    dialog.dismiss();
                    String chosenTheme;
                    switch (which) {
                        case 1:
                            chosenTheme = ThemeHelper.THEME_LIGHT;
                            break;
                        case 2:
                            chosenTheme = ThemeHelper.THEME_SYSTEM;
                            break;
                        case 0:
                        default:
                            chosenTheme = ThemeHelper.THEME_DARK;
                            break;
                    }

                    if (!chosenTheme.equalsIgnoreCase(currentTheme)) {
                        binding.tvCurrentTheme.setText(ThemeHelper.getThemeDisplayName(requireContext(), chosenTheme));
                        ThemeHelper.applyThemeWithTransition(requireActivity(), chosenTheme);
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void updateCacheSize() {
        if (getContext() == null) return;
        Context appContext = requireContext().getApplicationContext();
        executor.execute(() -> {
            String size = CacheManager.getCacheSizeFormatted(appContext);
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (binding != null && isAdded()) {
                        binding.tvCacheSize.setText(getString(R.string.settings_cache_size, size));
                    }
                });
            }
        });
    }

    private void clearCache() {
        if (getContext() == null) return;
        Context appContext = requireContext().getApplicationContext();
        executor.execute(() -> {
            CacheManager.clearAllCache(appContext);
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (binding != null && isAdded()) {
                        Toast.makeText(requireContext(), R.string.settings_cache_cleared, Toast.LENGTH_SHORT).show();
                        updateCacheSize();
                    }
                });
            }
        });
    }

    private void showPrivacyPolicy() {
        if (getContext() == null) return;
        WebView webView = new WebView(requireContext());
        webView.loadUrl("file:///android_asset/privacy_policy.html");

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_privacy_policy)
                .setView(webView)
                .setPositiveButton(R.string.action_close, null)
                .setNeutralButton(R.string.action_view_on_web, (dialog, which) -> {
                    Intent browserIntent = new Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://gitesh-ujgaonkar.github.io/AnScanner-Privacy/"));
                    startActivity(browserIntent);
                })
                .show();
    }

    private void showTermsAndConditions() {
        if (getContext() == null) return;
        WebView webView = new WebView(requireContext());
        webView.loadUrl("file:///android_asset/terms_and_conditions.html");

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_terms)
                .setView(webView)
                .setPositiveButton(R.string.action_close, null)
                .setNeutralButton(R.string.action_view_on_web, (dialog, which) -> {
                    Intent browserIntent = new Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://gitesh-ujgaonkar.github.io/AnScanner-Privacy/terms.html"));
                    startActivity(browserIntent);
                })
                .show();
    }

    private void showFeedbackDialog() {
        if (getContext() == null || getActivity() == null) return;

        DialogFeedbackBinding dialogBinding = DialogFeedbackBinding.inflate(getLayoutInflater());
        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
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

            dialogBinding.btnFeedbackSubmit.setEnabled(false);
            dialogBinding.btnFeedbackCancel.setEnabled(false);
            dialogBinding.pbFeedbackLoading.setVisibility(View.VISIBLE);

            HashMap<String, Object> feedbackMap = new HashMap<>();
            feedbackMap.put("message", message);
            feedbackMap.put("timestamp", FieldValue.serverTimestamp());
            feedbackMap.put("device_model", Build.MODEL);
            feedbackMap.put("os_version", Build.VERSION.RELEASE);

            FirebaseFirestore db = FirebaseFirestore.getInstance();
            db.collection("feedbacks")
                    .add(feedbackMap)
                    .addOnSuccessListener(documentReference -> {
                        if (!isAdded() || getActivity() == null) return;
                        dialog.dismiss();
                        Toast.makeText(requireContext(), R.string.feedback_success, Toast.LENGTH_SHORT).show();
                    })
                    .addOnFailureListener(e -> {
                        if (!isAdded() || getActivity() == null) return;
                        dialogBinding.btnFeedbackSubmit.setEnabled(true);
                        dialogBinding.btnFeedbackCancel.setEnabled(true);
                        dialogBinding.pbFeedbackLoading.setVisibility(View.GONE);
                        Toast.makeText(requireContext(), R.string.feedback_error, Toast.LENGTH_SHORT).show();
                    });
        });

        dialog.show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (executor != null) {
            executor.shutdown();
        }
        binding = null;
    }
}
