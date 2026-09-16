package com.anscanner.app.service;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

/**
 * Helper utility for on-device optical character recognition (OCR) using ML Kit Text Recognition v2.
 * Supports extracting structured {@link Text} from Bitmaps for on-image overlays and clipboard actions.
 */
public final class OcrHelper {

    private static final String TAG = "OcrHelper";

    public interface OcrCallback {
        void onSuccess(Text visionText);
        void onError(Exception e);
    }

    private OcrHelper() {
        // Utility class
    }

    /**
     * Extracts structured text from a Bitmap using ML Kit on-device Text Recognition v2.
     * Automatically closes the recognizer when finished.
     *
     * @param bitmap   The bitmap to process. Caller retains lifecycle/recycle responsibility.
     * @param context  Context reference.
     * @param callback Asynchronous callback for success/error handling returning the ML Kit {@link Text}.
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
                        callback.onSuccess(visionText);
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
}
