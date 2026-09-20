package com.anscanner.app.service;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.pdf.PdfRenderer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.anscanner.app.R;
import com.google.android.gms.tasks.Tasks;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Helper utility for on-device optical character recognition (OCR) using ML Kit Text Recognition v2.
 * Supports extracting structured {@link Text} from Bitmaps for on-image overlays and clipboard actions,
 * as well as multi-page batch OCR across all pages of a {@link PdfRenderer} or list of image files.
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

    public interface BatchOcrCallback {
        void onProgress(int currentPage, int totalPages);
        void onSuccess(String aggregatedText);
        void onCancelled(String partialText);
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
     * Extracts text across ALL pages of a PDF document sequentially with cancellation support:
     * Renders each page to a temporary Bitmap with opaque white background, processes it with ML Kit,
     * aggregates the resulting text structured by page, and immediately recycles the bitmap.
     *
     * @param context     Context reference for ProgressDialog and UI updates.
     * @param pdfRenderer The open PdfRenderer to extract pages from.
     * @param callback    Callback providing progress, aggregated text, partial text on cancel, or error.
     */
    public static void extractTextFromPdfBatch(@NonNull Context context,
                                               @NonNull PdfRenderer pdfRenderer,
                                               @NonNull BatchOcrCallback callback) {
        final int totalPages = pdfRenderer.getPageCount();
        if (totalPages == 0) {
            callback.onError(new IllegalStateException("PDF has no pages"));
            return;
        }

        final AtomicBoolean isCancelled = new AtomicBoolean(false);
        final View progressView = LayoutInflater.from(context).inflate(R.layout.dialog_batch_ocr_progress, null);
        final TextView tvStatus = progressView.findViewById(R.id.tvOcrProgressStatus);
        final LinearProgressIndicator progressBar = progressView.findViewById(R.id.ocrProgressBar);

        progressBar.setMax(totalPages);
        progressBar.setProgressCompat(1, false);
        tvStatus.setText(context.getString(R.string.ocr_extracting_page, 1, totalPages));

        final AlertDialog progressDialog = new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.ocr_dialog_title)
                .setView(progressView)
                .setCancelable(false)
                .setNegativeButton(R.string.action_cancel, (dialog, which) -> {
                    isCancelled.set(true);
                    dialog.dismiss();
                })
                .create();

        progressDialog.show();

        final Handler mainHandler = new Handler(Looper.getMainLooper());
        final ExecutorService executor = Executors.newSingleThreadExecutor();

        executor.execute(() -> {
            StringBuilder masterBuilder = new StringBuilder();
            TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

            try {
                for (int i = 0; i < totalPages; i++) {
                    if (isCancelled.get()) {
                        break;
                    }

                    final int currentPageNum = i + 1;
                    mainHandler.post(() -> {
                        try {
                            if (progressDialog.isShowing()) {
                                progressBar.setProgressCompat(currentPageNum, true);
                                tvStatus.setText(context.getString(R.string.ocr_extracting_page, currentPageNum, totalPages));
                            }
                        } catch (Exception ignored) {}
                    });

                    callback.onProgress(currentPageNum, totalPages);

                    PdfRenderer.Page page = null;
                    Bitmap pageBitmap = null;
                    try {
                        synchronized (pdfRenderer) {
                            page = pdfRenderer.openPage(i);
                            int targetWidth = 1400;
                            int targetHeight = (int) ((float) page.getHeight() / page.getWidth() * targetWidth);
                            if (targetHeight <= 0) targetHeight = 1400;

                            pageBitmap = com.anscanner.app.util.PdfRendererHelper.renderPageWithWhiteBackground(
                                    page, targetWidth, targetHeight, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        }
                    } finally {
                        if (page != null) {
                            page.close();
                        }
                    }

                    if (isCancelled.get()) {
                        if (pageBitmap != null && !pageBitmap.isRecycled()) {
                            pageBitmap.recycle();
                        }
                        break;
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
                                    } else {
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

                    if (isCancelled.get()) {
                        callback.onCancelled(masterBuilder.toString());
                    } else {
                        callback.onSuccess(masterBuilder.toString());
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "Multi-page PDF OCR extraction failed", e);
                mainHandler.post(() -> {
                    try {
                        if (progressDialog.isShowing()) {
                            progressDialog.dismiss();
                        }
                    } catch (Exception ignored) {}
                    if (isCancelled.get()) {
                        callback.onCancelled(masterBuilder.toString());
                    } else {
                        callback.onError(e);
                    }
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

    /**
     * Extracts text across ALL image pages in a list sequentially with cancellation support:
     * Decodes each image into a temporary Bitmap, processes it with ML Kit,
     * aggregates the resulting text structured by page, and immediately recycles the bitmap.
     *
     * @param context    Context reference for ProgressDialog and UI updates.
     * @param imagePaths List of file paths for each scanned page.
     * @param callback   Callback providing progress, aggregated text, partial text on cancel, or error.
     */
    public static void extractTextFromImagesBatch(@NonNull Context context,
                                                  @NonNull List<String> imagePaths,
                                                  @NonNull BatchOcrCallback callback) {
        final int totalPages = imagePaths.size();
        if (totalPages == 0) {
            callback.onError(new IllegalStateException("No image pages to process"));
            return;
        }

        final AtomicBoolean isCancelled = new AtomicBoolean(false);
        final View progressView = LayoutInflater.from(context).inflate(R.layout.dialog_batch_ocr_progress, null);
        final TextView tvStatus = progressView.findViewById(R.id.tvOcrProgressStatus);
        final LinearProgressIndicator progressBar = progressView.findViewById(R.id.ocrProgressBar);

        progressBar.setMax(totalPages);
        progressBar.setProgressCompat(1, false);
        tvStatus.setText(context.getString(R.string.ocr_extracting_page, 1, totalPages));

        final AlertDialog progressDialog = new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.ocr_dialog_title)
                .setView(progressView)
                .setCancelable(false)
                .setNegativeButton(R.string.action_cancel, (dialog, which) -> {
                    isCancelled.set(true);
                    dialog.dismiss();
                })
                .create();

        progressDialog.show();

        final Handler mainHandler = new Handler(Looper.getMainLooper());
        final ExecutorService executor = Executors.newSingleThreadExecutor();

        executor.execute(() -> {
            StringBuilder masterBuilder = new StringBuilder();
            TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

            try {
                for (int i = 0; i < totalPages; i++) {
                    if (isCancelled.get()) {
                        break;
                    }

                    final int currentPageNum = i + 1;
                    mainHandler.post(() -> {
                        try {
                            if (progressDialog.isShowing()) {
                                progressBar.setProgressCompat(currentPageNum, true);
                                tvStatus.setText(context.getString(R.string.ocr_extracting_page, currentPageNum, totalPages));
                            }
                        } catch (Exception ignored) {}
                    });

                    callback.onProgress(currentPageNum, totalPages);

                    String path = imagePaths.get(i);
                    Bitmap pageBitmap = null;
                    try {
                        pageBitmap = CacheManager.loadBitmap(path);
                        if (pageBitmap == null) {
                            pageBitmap = BitmapFactory.decodeFile(path);
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Error loading image page " + currentPageNum + ": " + path, e);
                    }

                    if (isCancelled.get()) {
                        if (pageBitmap != null && !pageBitmap.isRecycled()) {
                            pageBitmap.recycle();
                        }
                        break;
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
                                    } else {
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

                    if (isCancelled.get()) {
                        callback.onCancelled(masterBuilder.toString());
                    } else {
                        callback.onSuccess(masterBuilder.toString());
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "Multi-page image OCR extraction failed", e);
                mainHandler.post(() -> {
                    try {
                        if (progressDialog.isShowing()) {
                            progressDialog.dismiss();
                        }
                    } catch (Exception ignored) {}
                    if (isCancelled.get()) {
                        callback.onCancelled(masterBuilder.toString());
                    } else {
                        callback.onError(e);
                    }
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

    /**
     * Backward-compatible overload for extracting text from all pages of a PDF.
     */
    public static void extractTextFromPdf(@NonNull Context context,
                                          @NonNull PdfRenderer pdfRenderer,
                                          @NonNull MultiPageOcrCallback callback) {
        extractTextFromPdfBatch(context, pdfRenderer, new BatchOcrCallback() {
            @Override
            public void onProgress(int currentPage, int totalPages) {}

            @Override
            public void onSuccess(String aggregatedText) {
                callback.onSuccess(aggregatedText);
            }

            @Override
            public void onCancelled(String partialText) {
                callback.onSuccess(partialText);
            }

            @Override
            public void onError(Exception e) {
                callback.onError(e);
            }
        });
    }

    /**
     * Displays a clean preview dialog showing aggregated OCR text with "Copy All" and "Share" options.
     *
     * @param context   Context reference.
     * @param text      Aggregated text content.
     * @param isPartial True if extraction was cancelled and only partial text is shown.
     * @param pageCount Total number of pages processed.
     */
    public static void showExtractedTextDialog(@NonNull Context context,
                                               @NonNull String text,
                                               boolean isPartial,
                                               int pageCount) {
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_ocr_result_preview, null);
        TextView tvSubtitle = view.findViewById(R.id.tvResultSubtitle);
        TextView tvContent = view.findViewById(R.id.tvExtractedContent);

        if (isPartial) {
            tvSubtitle.setText(R.string.ocr_cancelled_subtitle);
        } else {
            tvSubtitle.setText(context.getString(R.string.ocr_all_pages_subtitle, Math.max(1, pageCount)));
        }

        final String finalContent = (text == null || text.trim().isEmpty())
                ? context.getString(R.string.ocr_no_text)
                : text.trim();
        tvContent.setText(finalContent);

        new MaterialAlertDialogBuilder(context)
                .setTitle(isPartial ? R.string.ocr_cancelled_preview_title : R.string.ocr_dialog_title)
                .setView(view)
                .setPositiveButton(R.string.ocr_dialog_copy, (dialog, which) -> {
                    ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("Extracted Text", finalContent));
                        Toast.makeText(context, R.string.ocr_copied, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNeutralButton(R.string.ocr_dialog_share, (dialog, which) -> {
                    Intent shareIntent = new Intent(Intent.ACTION_SEND);
                    shareIntent.setType("text/plain");
                    shareIntent.putExtra(Intent.EXTRA_SUBJECT, "Extracted Text");
                    shareIntent.putExtra(Intent.EXTRA_TEXT, finalContent);
                    context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.ocr_dialog_share)));
                })
                .setNegativeButton(R.string.action_done, null)
                .show();
    }
}
