package com.anscanner.app.util;

import android.app.Activity;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/**
 * Global Edge-to-Edge and Window Insets management utility.
 * Ensures consistent handling of status bar / display cutout (notch) on top
 * and dynamic adaptation to navigation styles (gesture bar vs 3-button navigation) on bottom
 * across all screens in the application.
 */
public final class InsetsHelper {

    private InsetsHelper() {
        // Static utility class
    }

    /**
     * Enables edge-to-edge window decor and sets theme-aware system bar icon contrast.
     *
     * @param activity Host activity.
     */
    public static void applyEdgeToEdge(@NonNull Activity activity) {
        Window window = activity.getWindow();
        WindowCompat.setDecorFitsSystemWindows(window, false);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);

        boolean isDarkMode = ThemeHelper.isDarkMode(activity);
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(window, window.getDecorView());
        if (controller != null) {
            controller.setAppearanceLightStatusBars(!isDarkMode);
            controller.setAppearanceLightNavigationBars(!isDarkMode);
        }
    }

    /**
     * Enables edge-to-edge with explicit dark or light system bar icons.
     * Useful for immersive full-screen screens like CameraActivity that are always dark.
     *
     * @param activity         Host activity.
     * @param forceLightIcons  True if status & nav bar icons should be light/white.
     */
    public static void applyEdgeToEdgeCustom(@NonNull Activity activity, boolean forceLightIcons) {
        Window window = activity.getWindow();
        WindowCompat.setDecorFitsSystemWindows(window, false);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);

        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(window, window.getDecorView());
        if (controller != null) {
            controller.setAppearanceLightStatusBars(!forceLightIcons);
            controller.setAppearanceLightNavigationBars(!forceLightIcons);
        }
    }

    /**
     * Dynamically adjusts topView and bottomView padding to perfectly accommodate
     * status bar / notch cutouts at the top and navigation bar (gesture pill or 3 buttons) at the bottom.
     *
     * @param root       The root view to attach the insets listener to.
     * @param topView    The top header/toolbar view to receive top inset padding.
     * @param bottomView The bottom controls/toolbar view to receive bottom inset padding.
     */
    public static void handleSystemBars(@NonNull View root, @Nullable View topView, @Nullable View bottomView) {
        final int initialTopPad = (topView != null) ? topView.getPaddingTop() : 0;
        final int initialBottomPad = (bottomView != null) ? bottomView.getPaddingBottom() : 0;
        final int initialLeftPad = root.getPaddingLeft();
        final int initialRightPad = root.getPaddingRight();

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()
            );

            if (topView != null) {
                topView.setPadding(
                        topView.getPaddingLeft(),
                        initialTopPad + insets.top,
                        topView.getPaddingRight(),
                        topView.getPaddingBottom()
                );
            }

            if (bottomView != null) {
                bottomView.setPadding(
                        bottomView.getPaddingLeft(),
                        bottomView.getPaddingTop(),
                        bottomView.getPaddingRight(),
                        initialBottomPad + insets.bottom
                );
            }

            // Apply horizontal safe insets for landscape cutouts and navigation bars
            root.setPadding(
                    initialLeftPad + insets.left,
                    root.getPaddingTop(),
                    initialRightPad + insets.right,
                    root.getPaddingBottom()
            );

            return windowInsets;
        });

        ViewCompat.requestApplyInsets(root);
    }

    /**
     * Dynamically adjusts root view padding directly to stay within safe system bar boundaries.
     *
     * @param root The root view to receive top and bottom system insets padding.
     */
    public static void handleSystemBarsRoot(@NonNull View root) {
        final int initialTop = root.getPaddingTop();
        final int initialBottom = root.getPaddingBottom();
        final int initialLeft = root.getPaddingLeft();
        final int initialRight = root.getPaddingRight();

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()
            );

            root.setPadding(
                    initialLeft + insets.left,
                    initialTop + insets.top,
                    initialRight + insets.right,
                    initialBottom + insets.bottom
            );

            return windowInsets;
        });

        ViewCompat.requestApplyInsets(root);
    }
}
