package com.anscanner.app.service;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.pdf.PdfDocument;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Generates PDF documents from a list of page image file paths using
 * the native {@link android.graphics.pdf.PdfDocument} API.
 *
 * <p>Zero third-party PDF dependencies. Each page is rendered onto an
 * A4-sized canvas with aspect-ratio-preserving scaling.</p>
 *
 * <p><strong>Memory contract:</strong> Bitmaps are loaded from disk
 * one-at-a-time, painted onto the PDF canvas, then immediately recycled.
 * At no point are multiple full-resolution bitmaps held in RAM.</p>
 */
public final class PdfGenerator {

    private static final String TAG = "PdfGenerator";

    /** A4 page dimensions in PDF points (72 points per inch). */
    public static final int PAGE_WIDTH_A4 = 595;
    public static final int PAGE_HEIGHT_A4 = 842;

    /** Margin in PDF points around each page. */
    private static final float PAGE_MARGIN = 20f;

    private PdfGenerator() {
        // Static utility class
    }

    /**
     * Creates a multi-page PDF from a list of page image file paths.
     *
     * <p>Each image is loaded one-at-a-time from disk, drawn onto an A4
     * canvas, and the bitmap is recycled before proceeding to the next page.
     * This ensures constant memory usage regardless of page count.</p>
     *
     * @param pagePaths   Ordered list of absolute file paths to page JPEG images.
     * @param outputFile  Destination file for the PDF.
     * @param highQuality If true, uses full resolution; if false, scales to 70%.
     * @return The file size in bytes, or -1 on failure.
     * @throws IOException If file writing fails.
     */
    public static long createPdfFromPages(java.util.List<String> pagePaths,
                                          File outputFile,
                                          boolean highQuality) throws IOException {
        if (pagePaths == null || pagePaths.isEmpty()) {
            throw new IllegalArgumentException("Page paths list cannot be empty");
        }

        PdfDocument document = new PdfDocument();
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

        try {
            int pageNumber = 1;

            for (String pagePath : pagePaths) {
                // Load bitmap from disk (one at a time — strict memory control)
                Bitmap bitmap = loadBitmapFromPath(pagePath, highQuality);
                if (bitmap == null) {
                    Log.w(TAG, "Skipping null bitmap for page " + pageNumber + ": " + pagePath);
                    pageNumber++;
                    continue;
                }

                try {
                    // Configure page
                    PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(
                            PAGE_WIDTH_A4, PAGE_HEIGHT_A4, pageNumber
                    ).create();

                    PdfDocument.Page page = document.startPage(pageInfo);
                    Canvas canvas = page.getCanvas();

                    // Calculate aspect-ratio-fit rectangle with margins
                    RectF destRect = calculateFitRect(
                            bitmap.getWidth(), bitmap.getHeight(),
                            PAGE_WIDTH_A4, PAGE_HEIGHT_A4,
                            PAGE_MARGIN
                    );

                    // Draw bitmap onto the PDF canvas
                    canvas.drawBitmap(bitmap, null, destRect, paint);

                    document.finishPage(page);
                    pageNumber++;

                } finally {
                    // CRITICAL: Recycle bitmap immediately after painting
                    bitmap.recycle();
                }
            }

            // Write the complete PDF to disk
            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                document.writeTo(fos);
                fos.flush();
            }

            long fileSize = outputFile.length();
            Log.i(TAG, "PDF generated: " + outputFile.getAbsolutePath() +
                    " (" + (pageNumber - 1) + " pages, " + fileSize + " bytes)");
            return fileSize;

        } finally {
            document.close();
        }
    }

    /**
     * Loads a bitmap from a file path, optionally downscaling for normal quality.
     *
     * @param path        Absolute file path to a JPEG image.
     * @param highQuality If false, scales the bitmap to 70% dimensions.
     * @return The loaded bitmap, or null on failure.
     */
    private static Bitmap loadBitmapFromPath(String path, boolean highQuality) {
        try {
            android.graphics.BitmapFactory.Options options =
                    new android.graphics.BitmapFactory.Options();

            if (!highQuality) {
                // For "Normal" quality, decode at reduced resolution
                options.inSampleSize = 2; // 50% resolution → ~25% memory
            }

            Bitmap bitmap = android.graphics.BitmapFactory.decodeFile(path, options);
            if (bitmap == null) {
                Log.e(TAG, "Failed to decode bitmap: " + path);
            }
            return bitmap;

        } catch (Exception e) {
            Log.e(TAG, "Error loading bitmap from " + path, e);
            return null;
        }
    }

    /**
     * Calculates a destination rectangle that preserves the source aspect ratio
     * within the page dimensions minus margins.
     */
    private static RectF calculateFitRect(int srcWidth, int srcHeight,
                                           int pageWidth, int pageHeight,
                                           float margin) {
        float availableWidth = pageWidth - (2 * margin);
        float availableHeight = pageHeight - (2 * margin);

        float srcAspect = (float) srcWidth / srcHeight;
        float destAspect = availableWidth / availableHeight;

        float targetWidth, targetHeight;
        if (srcAspect > destAspect) {
            // Source is wider → constrain by width
            targetWidth = availableWidth;
            targetHeight = availableWidth / srcAspect;
        } else {
            // Source is taller → constrain by height
            targetHeight = availableHeight;
            targetWidth = availableHeight * srcAspect;
        }

        // Center within the available area
        float left = margin + (availableWidth - targetWidth) / 2f;
        float top = margin + (availableHeight - targetHeight) / 2f;

        return new RectF(left, top, left + targetWidth, top + targetHeight);
    }
}
