package com.anscanner.app.service;

import android.app.ProgressDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.pdf.PdfRenderer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Helper utility for on-device optical character recognition (OCR) using ML Kit Text Recognition v2.
 * Supports extracting structured {@link Text} from Bitmaps for on-image overlays and clipboard actions,
 * as well as multi-page batch OCR across all pages of a {@link PdfRenderer}.
 */
public final class OcrHelper {

    private static final String TAG = "OcrHelper";

    public interface OcrCallback {
        void onSuccess(Text visionText);
        void onError(Exception e);
    }

    public interface MultiPageOcrCallback {
        void onSuccess(String fullText);
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

    /**
     * Extracts text across ALL pages of a PDF document in a loop:
     * for (int i = 0; i < pdfRenderer.getPageCount(); i++)
     *
     * <p>Renders each page to a temporary Bitmap, processes it with ML Kit,
     * appends the resulting text to a master StringBuilder, and closes the page
     * and recycles the bitmap before moving to the next. Displays a ProgressDialog
     * while the loop executes.</p>
     *
     * @param context     Context reference for ProgressDialog and Main thread handler.
     * @param pdfRenderer The open PdfRenderer to extract pages from.
     * @param callback    Callback providing the master combined text or error.
     */
    public static void extractTextFromPdf(@NonNull Context context,
                                          @NonNull PdfRenderer pdfRenderer,
                                          @NonNull MultiPageOcrCallback callback) {
        final int totalPages = pdfRenderer.getPageCount();
        if (totalPages == 0) {
            callback.onError(new IllegalStateException("PDF has no pages"));
            return;
        }

        final ProgressDialog progressDialog = new ProgressDialog(context);
        progressDialog.setTitle("Extracting Text (OCR)");
        progressDialog.setMessage("Processing page 1 of " + totalPages + "...");
        progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progressDialog.setMax(totalPages);
        progressDialog.setProgress(0);
        progressDialog.setCancelable(false);
        progressDialog.show();

        final Handler mainHandler = new Handler(Looper.getMainLooper());
        final ExecutorService executor = Executors.newSingleThreadExecutor();

        executor.execute(() -> {
            StringBuilder masterBuilder = new StringBuilder();
            TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

            try {
                for (int i = 0; i < pdfRenderer.getPageCount(); i++) {
                    final int currentPageNum = i + 1;
                    mainHandler.post(() -> {
                        try {
                            if (progressDialog.isShowing()) {
                                progressDialog.setProgress(currentPageNum);
                                progressDialog.setMessage("Processing page " + currentPageNum + " of " + totalPages + "...");
                            }
                        } catch (Exception ignored) {}
                    });

                    PdfRenderer.Page page = null;
                    Bitmap pageBitmap = null;
                    try {
                        page = pdfRenderer.openPage(i);
                        int targetWidth = 1400;
                        int targetHeight = (int) ((float) page.getHeight() / page.getWidth() * targetWidth);
                        if (targetHeight <= 0) targetHeight = 1400;

                        pageBitmap = com.anscanner.app.util.PdfRendererHelper.renderPageWithWhiteBackground(
                                page, targetWidth, targetHeight, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    } finally {
                        if (page != null) {
                            page.close();
                            page = null;
                        }
                    }

                    if (pageBitmap != null) {
                        try {
                            InputImage image = InputImage.fromBitmap(pageBitmap, 0);
                            Text visionText = Tasks.await(recognizer.process(image));
                            if (visionText != null) {
                                String text = visionText.getText().trim();
                                if (!text.isEmpty()) {
                                    if (masterBuilder.length() > 0) {
                                        masterBuilder.append("\n\n--- Page ").append(currentPageNum).append(" ---\n\n");
                                    } else if (totalPages > 1) {
                                        masterBuilder.append("--- Page ").append(currentPageNum).append(" ---\n\n");
                                    }
                                    masterBuilder.append(text);
                                }
                            }
                        } finally {
                            if (!pageBitmap.isRecycled()) {
                                pageBitmap.recycle();
                            }
                        }
                    }
                }

                mainHandler.post(() -> {
                    try {
                        if (progressDialog.isShowing()) {
                            progressDialog.dismiss();
                        }
                    } catch (Exception ignored) {}
                    callback.onSuccess(masterBuilder.toString());
                });

            } catch (Exception e) {
                Log.e(TAG, "Multi-page OCR extraction failed", e);
                mainHandler.post(() -> {
                    try {
                        if (progressDialog.isShowing()) {
                            progressDialog.dismiss();
                        }
                    } catch (Exception ignored) {}
                    callback.onError(e);
                });
            } finally {
                try {
                    recognizer.close();
                } catch (Exception e) {
                    Log.w(TAG, "Error closing TextRecognizer", e);
                }
                executor.shutdown();
            }
        });
    }
}
