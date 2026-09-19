package com.anscanner.app.ui.pdf;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.anscanner.app.R;
import com.anscanner.app.databinding.BottomSheetReadingModeBinding;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

/**
 * Material3 BottomSheet dialog providing Kindle-style e-reader customization:
 * <ul>
 *   <li>Paper reading theme: Light, Sepia (#FBF0D9), Dark Charcoal, and OLED Night Inverted</li>
 *   <li>Adjustable page margins: Compact, Normal, Wide</li>
 *   <li>Reading scroll mode: Page-by-Page Snap vs Continuous scroll</li>
 *   <li>Keep screen awake toggle</li>
 * </ul>
 */
public class ReadingModeBottomSheet extends BottomSheetDialogFragment {

    public static final String TAG = "ReadingModeBottomSheet";
    private static final String PREFS_NAME = "anscanner_reading_settings";

    public static final String PREF_THEME = "pref_reading_theme";
    public static final String PREF_MARGIN = "pref_reading_margin";
    public static final String PREF_PAGE_SNAP = "pref_reading_page_snap";
    public static final String PREF_KEEP_AWAKE = "pref_reading_keep_awake";

    public interface OnReadingSettingsChangedListener {
        void onThemeChanged(@NonNull PdfPageAdapter.ReadingTheme theme);
        void onMarginChanged(@NonNull PdfPageAdapter.ReadingMargin margin);
        void onPageSnapChanged(boolean snapEnabled);
        void onKeepAwakeChanged(boolean keepAwakeEnabled);
    }

    private BottomSheetReadingModeBinding binding;
    private OnReadingSettingsChangedListener listener;

    private PdfPageAdapter.ReadingTheme selectedTheme = PdfPageAdapter.ReadingTheme.LIGHT;
    private PdfPageAdapter.ReadingMargin selectedMargin = PdfPageAdapter.ReadingMargin.NORMAL;
    private boolean isPageSnap = false;
    private boolean isKeepAwake = false;

    public static ReadingModeBottomSheet newInstance() {
        return new ReadingModeBottomSheet();
    }

    public void setOnReadingSettingsChangedListener(@Nullable OnReadingSettingsChangedListener listener) {
        this.listener = listener;
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (context instanceof OnReadingSettingsChangedListener && listener == null) {
            listener = (OnReadingSettingsChangedListener) context;
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = BottomSheetReadingModeBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        loadSavedPreferences();
        setupThemeSelectors();
        setupMarginSelector();
        setupSwitches();

        binding.btnCloseSettings.setOnClickListener(v -> dismiss());
    }

    private void loadSavedPreferences() {
        Context context = getContext();
        if (context == null) return;

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String themeStr = prefs.getString(PREF_THEME, PdfPageAdapter.ReadingTheme.LIGHT.name());
        try {
            selectedTheme = PdfPageAdapter.ReadingTheme.valueOf(themeStr);
        } catch (Exception e) {
            selectedTheme = PdfPageAdapter.ReadingTheme.LIGHT;
        }

        String marginStr = prefs.getString(PREF_MARGIN, PdfPageAdapter.ReadingMargin.NORMAL.name());
        try {
            selectedMargin = PdfPageAdapter.ReadingMargin.valueOf(marginStr);
        } catch (Exception e) {
            selectedMargin = PdfPageAdapter.ReadingMargin.NORMAL;
        }

        isPageSnap = prefs.getBoolean(PREF_PAGE_SNAP, false);
        isKeepAwake = prefs.getBoolean(PREF_KEEP_AWAKE, false);
    }

    private void setupThemeSelectors() {
        updateThemeCardStyles();

        binding.cardThemeLight.setOnClickListener(v -> {
            selectedTheme = PdfPageAdapter.ReadingTheme.LIGHT;
            savePreference(PREF_THEME, selectedTheme.name());
            updateThemeCardStyles();
            if (listener != null) listener.onThemeChanged(selectedTheme);
        });

        binding.cardThemeSepia.setOnClickListener(v -> {
            selectedTheme = PdfPageAdapter.ReadingTheme.SEPIA;
            savePreference(PREF_THEME, selectedTheme.name());
            updateThemeCardStyles();
            if (listener != null) listener.onThemeChanged(selectedTheme);
        });

        binding.cardThemeDark.setOnClickListener(v -> {
            selectedTheme = PdfPageAdapter.ReadingTheme.DARK;
            savePreference(PREF_THEME, selectedTheme.name());
            updateThemeCardStyles();
            if (listener != null) listener.onThemeChanged(selectedTheme);
        });

        binding.cardThemeNight.setOnClickListener(v -> {
            selectedTheme = PdfPageAdapter.ReadingTheme.NIGHT;
            savePreference(PREF_THEME, selectedTheme.name());
            updateThemeCardStyles();
            if (listener != null) listener.onThemeChanged(selectedTheme);
        });
    }

    private void updateThemeCardStyles() {
        if (binding == null || getContext() == null) return;

        int activeBorder = ContextCompat.getColor(requireContext(), R.color.accent_mint);
        int inactiveBorder = ContextCompat.getColor(requireContext(), R.color.divider);

        binding.cardThemeLight.setStrokeColor(selectedTheme == PdfPageAdapter.ReadingTheme.LIGHT ? activeBorder : inactiveBorder);
        binding.cardThemeLight.setStrokeWidth(selectedTheme == PdfPageAdapter.ReadingTheme.LIGHT ? dpToPx(2) : dpToPx(1));

        binding.cardThemeSepia.setStrokeColor(selectedTheme == PdfPageAdapter.ReadingTheme.SEPIA ? activeBorder : inactiveBorder);
        binding.cardThemeSepia.setStrokeWidth(selectedTheme == PdfPageAdapter.ReadingTheme.SEPIA ? dpToPx(2) : dpToPx(1));

        binding.cardThemeDark.setStrokeColor(selectedTheme == PdfPageAdapter.ReadingTheme.DARK ? activeBorder : inactiveBorder);
        binding.cardThemeDark.setStrokeWidth(selectedTheme == PdfPageAdapter.ReadingTheme.DARK ? dpToPx(2) : dpToPx(1));

        binding.cardThemeNight.setStrokeColor(selectedTheme == PdfPageAdapter.ReadingTheme.NIGHT ? activeBorder : inactiveBorder);
        binding.cardThemeNight.setStrokeWidth(selectedTheme == PdfPageAdapter.ReadingTheme.NIGHT ? dpToPx(2) : dpToPx(1));
    }

    private void setupMarginSelector() {
        switch (selectedMargin) {
            case COMPACT:
                binding.toggleGroupMargins.check(R.id.btnMarginCompact);
                break;
            case WIDE:
                binding.toggleGroupMargins.check(R.id.btnMarginWide);
                break;
            case NORMAL:
            default:
                binding.toggleGroupMargins.check(R.id.btnMarginNormal);
                break;
        }

        binding.toggleGroupMargins.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;

            if (checkedId == R.id.btnMarginCompact) {
                selectedMargin = PdfPageAdapter.ReadingMargin.COMPACT;
            } else if (checkedId == R.id.btnMarginWide) {
                selectedMargin = PdfPageAdapter.ReadingMargin.WIDE;
            } else {
                selectedMargin = PdfPageAdapter.ReadingMargin.NORMAL;
            }

            savePreference(PREF_MARGIN, selectedMargin.name());
            if (listener != null) listener.onMarginChanged(selectedMargin);
        });
    }

    private void setupSwitches() {
        binding.switchPageSnap.setChecked(isPageSnap);
        binding.switchPageSnap.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isPageSnap = isChecked;
            saveBooleanPreference(PREF_PAGE_SNAP, isChecked);
            if (listener != null) listener.onPageSnapChanged(isChecked);
        });

        binding.switchKeepAwake.setChecked(isKeepAwake);
        binding.switchKeepAwake.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isKeepAwake = isChecked;
            saveBooleanPreference(PREF_KEEP_AWAKE, isChecked);
            if (listener != null) listener.onKeepAwakeChanged(isChecked);
        });
    }

    private void savePreference(String key, String value) {
        Context context = getContext();
        if (context != null) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(key, value)
                    .apply();
        }
    }

    private void saveBooleanPreference(String key, boolean value) {
        Context context = getContext();
        if (context != null) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(key, value)
                    .apply();
        }
    }

    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (dp * density);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
