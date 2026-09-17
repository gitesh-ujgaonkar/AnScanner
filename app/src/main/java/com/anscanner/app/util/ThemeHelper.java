package com.anscanner.app.util;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;

import com.anscanner.app.R;

/**
 * Utility helper for managing app themes (Dark, Light, System Default).
 * Enforces Dark Mode as default on first install and provides smooth
 * cross-fade activity transitions when switching themes.
 */
public final class ThemeHelper {

    public static final String PREFS_NAME = "anscanner_prefs";
    public static final String KEY_THEME = "app_theme";

    public static final String THEME_DARK = "dark";
    public static final String THEME_LIGHT = "light";
    public static final String THEME_SYSTEM = "system";

    private ThemeHelper() {
        // Utility class
    }

    /**
     * Applies the persisted theme mode globally. Defaults to Dark Mode.
     */
    public static void applyTheme(Context context) {
        String theme = getThemePreference(context);
        AppCompatDelegate.setDefaultNightMode(getNightMode(theme));
    }

    /**
     * Gets the currently saved theme key, defaulting to {@link #THEME_DARK}.
     */
    public static String getThemePreference(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_THEME, THEME_DARK);
    }

    /**
     * Persists the selected theme key in SharedPreferences.
     */
    public static void setThemePreference(Context context, String theme) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_THEME, theme).apply();
    }

    /**
     * Maps the theme key to its corresponding {@link AppCompatDelegate} mode.
     */
    public static int getNightMode(String theme) {
        if (THEME_LIGHT.equalsIgnoreCase(theme)) {
            return AppCompatDelegate.MODE_NIGHT_NO;
        } else if (THEME_SYSTEM.equalsIgnoreCase(theme)) {
            return AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        } else {
            return AppCompatDelegate.MODE_NIGHT_YES;
        }
    }

    /**
     * Returns a user-facing localized display name for the given theme key.
     */
    public static String getThemeDisplayName(Context context, String theme) {
        if (THEME_LIGHT.equalsIgnoreCase(theme)) {
            return context.getString(R.string.theme_light);
        } else if (THEME_SYSTEM.equalsIgnoreCase(theme)) {
            return context.getString(R.string.theme_system);
        } else {
            return context.getString(R.string.theme_dark_default);
        }
    }

    /**
     * Updates the theme preference, applies the night mode, and performs
     * a smooth cross-fade recreation of the host activity to prevent jarring flashes.
     */
    public static void applyThemeWithTransition(Activity activity, String newTheme) {
        setThemePreference(activity, newTheme);
        AppCompatDelegate.setDefaultNightMode(getNightMode(newTheme));

        // Smooth fade window transition
        Intent intent = activity.getIntent();
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        activity.finish();
        activity.overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
        activity.startActivity(intent);
        activity.overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
    }
}
