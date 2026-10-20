package com.anscanner.app.ui.dialog;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.anscanner.app.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.Locale;

/**
 * Modern HSV Color Picker Dialog with Hue, Saturation, Brightness, and Alpha controls,
 * real-time color preview, hex representation, and one-tap quick swatches.
 */
public class CustomColorPickerDialog {

    public interface OnColorSelectedListener {
        void onColorSelected(int color);
    }

    public static void show(@NonNull Context context, int initialColor, boolean showAlpha, @NonNull OnColorSelectedListener listener) {
        float density = context.getResources().getDisplayMetrics().density;
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_custom_color_picker, null);

        View viewColorPreview = view.findViewById(R.id.viewColorPreview);
        TextView tvHexCode = view.findViewById(R.id.tvHexCode);
        SeekBar seekBarHue = view.findViewById(R.id.seekBarHue);
        SeekBar seekBarSaturation = view.findViewById(R.id.seekBarSaturation);
        SeekBar seekBarValue = view.findViewById(R.id.seekBarValue);
        SeekBar seekBarAlpha = view.findViewById(R.id.seekBarAlpha);
        TextView tvAlphaLabel = view.findViewById(R.id.tvAlphaLabel);
        LinearLayout layoutPresetSwatches = view.findViewById(R.id.layoutPresetSwatches);

        if (!showAlpha) {
            tvAlphaLabel.setVisibility(View.GONE);
            seekBarAlpha.setVisibility(View.GONE);
        }

        // HSV state
        final float[] hsv = new float[3];
        Color.colorToHSV(initialColor != 0 ? initialColor : Color.parseColor("#10B981"), hsv);
        final int[] alphaVal = new int[]{showAlpha ? Color.alpha(initialColor != 0 ? initialColor : 0xFF10B981) : 255};
        if (alphaVal[0] == 0) alphaVal[0] = 255;

        // Rainbow gradient for Hue track
        int[] rainbow = new int[]{
                0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF, 0xFF0000FF, 0xFFFF00FF, 0xFFFF0000
        };
        GradientDrawable hueGrad = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, rainbow);
        hueGrad.setCornerRadius(6f * density);
        seekBarHue.setProgressDrawable(hueGrad);

        // Saturation track gradient updater
        Runnable updateTracksAndPreview = () -> {
            int currentColor = Color.HSVToColor(alphaVal[0], hsv);
            viewColorPreview.setBackgroundColor(currentColor);

            String hexStr = showAlpha
                    ? String.format(Locale.US, "#%08X", currentColor)
                    : String.format(Locale.US, "#%06X", (0xFFFFFF & currentColor));
            tvHexCode.setText(hexStr);

            // Update Saturation track gradient (from white/gray to full hue)
            float[] satStart = new float[]{hsv[0], 0f, hsv[2]};
            float[] satEnd = new float[]{hsv[0], 1f, hsv[2]};
            int[] satColors = new int[]{Color.HSVToColor(satStart), Color.HSVToColor(satEnd)};
            GradientDrawable satGrad = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, satColors);
            satGrad.setCornerRadius(6f * density);
            seekBarSaturation.setProgressDrawable(satGrad);

            // Update Value track gradient (from black to full hue)
            float[] valStart = new float[]{hsv[0], hsv[1], 0f};
            float[] valEnd = new float[]{hsv[0], hsv[1], 1f};
            int[] valColors = new int[]{Color.HSVToColor(valStart), Color.HSVToColor(valEnd)};
            GradientDrawable valGrad = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, valColors);
            valGrad.setCornerRadius(6f * density);
            seekBarValue.setProgressDrawable(valGrad);
        };

        // Initialize seekbars
        seekBarHue.setProgress((int) hsv[0]);
        seekBarSaturation.setProgress((int) (hsv[1] * 100f));
        seekBarValue.setProgress((int) (hsv[2] * 100f));
        seekBarAlpha.setProgress(alphaVal[0]);

        updateTracksAndPreview.run();

        // Listeners
        seekBarHue.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                hsv[0] = (float) progress;
                updateTracksAndPreview.run();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        seekBarSaturation.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                hsv[1] = progress / 100f;
                updateTracksAndPreview.run();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        seekBarValue.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                hsv[2] = progress / 100f;
                updateTracksAndPreview.run();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        seekBarAlpha.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                alphaVal[0] = progress;
                updateTracksAndPreview.run();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        // Add Quick Preset Swatches
        int[] presetColors = new int[]{
                Color.parseColor("#00FF88"), // Neon Green
                Color.parseColor("#10B981"), // Mint Green
                Color.parseColor("#EF4444"), // Red
                Color.parseColor("#3B82F6"), // Blue
                Color.parseColor("#FACC15"), // Yellow Highlighter
                Color.parseColor("#8B5CF6"), // Purple
                Color.WHITE,                 // White
                Color.parseColor("#111827")  // Black
        };

        for (int pColor : presetColors) {
            FrameLayout slot = new FrameLayout(context);
            int slotSize = (int) (32 * density);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(slotSize, slotSize);
            lp.setMargins((int) (3 * density), 0, (int) (3 * density), 0);
            slot.setLayoutParams(lp);

            View circle = new View(context);
            int circleDim = (int) (26 * density);
            FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(circleDim, circleDim, Gravity.CENTER);
            circle.setLayoutParams(clp);

            GradientDrawable cd = new GradientDrawable();
            cd.setShape(GradientDrawable.OVAL);
            cd.setColor(pColor);
            cd.setStroke((int) (1.5f * density), pColor == Color.WHITE ? Color.parseColor("#D1D5DB") : Color.TRANSPARENT);
            circle.setBackground(cd);

            slot.addView(circle);
            slot.setOnClickListener(v -> {
                Color.colorToHSV(pColor, hsv);
                if (showAlpha) {
                    alphaVal[0] = Color.alpha(pColor);
                    if (alphaVal[0] == 0) alphaVal[0] = 255;
                    seekBarAlpha.setProgress(alphaVal[0]);
                }
                seekBarHue.setProgress((int) hsv[0]);
                seekBarSaturation.setProgress((int) (hsv[1] * 100f));
                seekBarValue.setProgress((int) (hsv[2] * 100f));
                updateTracksAndPreview.run();
            });

            layoutPresetSwatches.addView(slot);
        }

        new MaterialAlertDialogBuilder(context)
                .setTitle("Custom Color")
                .setView(view)
                .setPositiveButton("Select Color", (dialog, which) -> {
                    int chosen = Color.HSVToColor(alphaVal[0], hsv);
                    listener.onColorSelected(chosen);
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }
}
