package com.anscanner.app.service;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.anscanner.app.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

/**
 * Helper utility for on-device optical character recognition (OCR) using ML Kit Text Recognition v2.
 * Supports extracting text from Bitmaps and displaying editable, copyable text dialogs.
 */
public final class OcrHelper {

    private static final String TAG = "OcrHelper";

    public interface OcrCallback {
        void onSuccess(String extractedText);
        void onError(Exception e);
    }

    private OcrHelper() {
        // Utility class
    }

    /**
     * Extracts text from a Bitmap using ML Kit on-device Text Recognition v2.
     * Automatically closes the recognizer when finished.
     *
     * @param bitmap   The bitmap to process. Caller retains lifecycle/recycle responsibility.
     * @param context  Context reference.
     * @param callback Asynchronous callback for success/error handling.
     */
    public static void extractText(@Nullable Bitmap bitmap, @NonNull Context context, @NonNull OcrCallback callback) {
        if (bitmap == null || bitmap.isRecycled()) {
            callback.onError(new IllegalArgumentException("Bitmap is null or already recycled"));
            return;
        }

        try {
            InputImage inputImage = InputImage.fromBitmap(bitmap, 0);
            TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

            recognizer.process(inputImage)
                    .addOnSuccessListener(visionText -> {
                        String text = (visionText != null) ? visionText.getText() : "";
                        callback.onSuccess(text);
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "Failed to extract text using ML Kit OCR", e);
                        callback.onError(e);
                    })
                    .addOnCompleteListener(task -> {
                        try {
                            recognizer.close();
                        } catch (Exception e) {
                            Log.w(TAG, "Error closing TextRecognizer", e);
                        }
                    });
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error starting OCR processing", e);
            callback.onError(e);
        }
    }

    /**
     * Shows a Material3 alert dialog containing the extracted text in a scrollable, editable
     * EditText, allowing the user to make corrections and copy the text to clipboard.
     *
     * @param context       The calling Activity/Context.
     * @param extractedText The extracted raw text.
     */
    public static void showExtractedTextDialog(@NonNull Context context, @Nullable String extractedText) {
        final String text = (extractedText == null) ? "" : extractedText.trim();

        ScrollView scrollView = new ScrollView(context);
        scrollView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        scrollView.setFillViewport(true);

        EditText editText = new EditText(context);
        editText.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        int padding = (int) (18 * context.getResources().getDisplayMetrics().density);
        editText.setPadding(padding, padding, padding, padding);
        editText.setText(text);
        editText.setHint(R.string.ocr_empty);
        editText.setTextSize(14f);
        editText.setTextColor(context.getColor(R.color.text_primary));
        editText.setGravity(Gravity.TOP | Gravity.START);
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        editText.setHorizontallyScrolling(false);
        editText.setBackground(null);

        scrollView.addView(editText);

        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.ocr_title)
                .setView(scrollView)
                .setNegativeButton(R.string.action_close, null)
                .setPositiveButton(R.string.ocr_copy, (dialog, which) -> {
                    String currentContent = editText.getText().toString();
                    if (currentContent.trim().isEmpty()) {
                        Toast.makeText(context, R.string.ocr_empty, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        ClipData clip = ClipData.newPlainText("Extracted Text", currentContent);
                        clipboard.setPrimaryClip(clip);
                        Toast.makeText(context, R.string.ocr_copied, Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }
}
