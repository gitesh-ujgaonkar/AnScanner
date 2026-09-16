package com.anscanner.app.service;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.pdf.PdfDocument;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Generates and compresses PDF documents using native Android APIs
 * ({@link android.graphics.pdf.PdfDocument} and {@link android.graphics.pdf.PdfRenderer}).
 *
 * <p>Zero third-party PDF dependencies.</p>
 *
 * <p><strong>Memory contract:</strong> Bitmaps are loaded/rendered one-at-a-time,
 * intermediate compressed copies are created and painted onto the canvas, and
 * both original and temporary bitmaps are strictly recycled immediately after drawing.</p>
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
     * Creates a multi-page PDF from a list of page image file paths with the specified JPEG quality.
     *
     * <p>When drawing the image to the canvas, the bitmap is first compressed to JPEG at the specified
     * quality, decoded back to a temporary Bitmap, drawn to the canvas, and both bitmaps are strictly
     * recycled immediately after drawing.</p>
     *
     * @param pagePaths  Ordered list of absolute file paths to page JPEG images.
     * @param outputFile Destination file for the PDF.
     * @param quality    Compression quality (1-100), e.g. 100 for High, 60 for Medium, 30 for Low.
     * @return The file size in bytes, or -1 on failure.
     * @throws IOException If file writing fails.
     */
    public static long createPdfFromPages(List<String> pagePaths,
                                          File outputFile,
                                          int quality) throws IOException {
        if (pagePaths == null || pagePaths.isEmpty()) {
            throw new IllegalArgumentException("Page paths list cannot be empty");
        }

        PdfDocument document = new PdfDocument();
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

        try {
            int pageNumber = 1;

            for (String pagePath : pagePaths) {
                // Load bitmap from disk (one at a time — strict memory control)
                Bitmap bitmap = loadBitmapFromPath(pagePath);
                if (bitmap == null) {
                    Log.w(TAG, "Skipping null bitmap for page " + pageNumber + ": " + pagePath);
                    pageNumber++;
                    continue;
                }

                Bitmap tempBitmap = null;
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

                    // 1. First compress the Bitmap: bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
                    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream);
                    byte[] compressedBytes = outputStream.toByteArray();

                    // 2. Decode the compressed byte array back into a temporary Bitmap
                    tempBitmap = BitmapFactory.decodeByteArray(compressedBytes, 0, compressedBytes.length);

                    // 3. Draw it to the PDF canvas
                    if (tempBitmap != null) {
                        canvas.drawBitmap(tempBitmap, null, destRect, paint);
                    } else {
                        canvas.drawBitmap(bitmap, null, destRect, paint);
                    }

                    document.finishPage(page);
                    pageNumber++;

                } finally {
                    // 4. Strictly call .recycle() on all Bitmaps immediately after drawing
                    if (tempBitmap != null && !tempBitmap.isRecycled()) {
                        tempBitmap.recycle();
                    }
                    if (bitmap != null && !bitmap.isRecycled()) {
                        bitmap.recycle();
                    }
                }
            }

            // Write the complete PDF to disk
            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                document.writeTo(fos);
                fos.flush();
            }

            long fileSize = outputFile.length();
            Log.i(TAG, "PDF generated: " + outputFile.getAbsolutePath() +
                    " (" + (pageNumber - 1) + " pages, " + fileSize + " bytes, quality=" + quality + ")");
            return fileSize;

        } finally {
            document.close();
        }
    }

    /**
     * Backward-compatible overload for boolean highQuality.
     */
    public static long createPdfFromPages(List<String> pagePaths,
                                          File outputFile,
                                          boolean highQuality) throws IOException {
        return createPdfFromPages(pagePaths, outputFile, highQuality ? 100 : 60);
    }

    /**
     * Compresses an external PDF by reading each page via PdfRenderer, rendering to a high-res
     * bitmap, applying the JPEG compression scale with the specified quality, drawing to a new
     * PdfDocument, and strictly recycling all bitmaps immediately.
     *
     * @param context      Application context.
     * @param sourcePdfUri Uri of the external PDF to compress.
     * @param outputFile   Destination file for the compressed PDF.
     * @param quality      Compression quality (1-100), e.g. 100 for High, 60 for Medium, 30 for Low.
     * @return Resulting compressed file size in bytes.
     * @throws IOException If file operations fail.
     */
    public static long compressPdf(Context context,
                                   Uri sourcePdfUri,
                                   File outputFile,
                                   int quality) throws IOException {
        ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(sourcePdfUri, "r");
        if (pfd == null) {
            throw new IOException("Unable to open file descriptor for URI: " + sourcePdfUri);
        }

        PdfRenderer renderer = null;
        PdfDocument document = null;
        try {
            renderer = new PdfRenderer(pfd);
            int pageCount = renderer.getPageCount();
            if (pageCount == 0) {
                throw new IOException("PDF contains 0 pages");
            }

            document = new PdfDocument();
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

            for (int i = 0; i < pageCount; i++) {
                PdfRenderer.Page page = renderer.openPage(i);
                int pageW = page.getWidth();
                int pageH = page.getHeight();

                // High-resolution rendering (crisp text and imagery)
                int renderW = Math.max(pageW * 2, PAGE_WIDTH_A4);
                int renderH = Math.round((float) renderW * pageH / pageW);

                Bitmap pageBitmap = Bitmap.createBitmap(renderW, renderH, Bitmap.Config.ARGB_8888);
                Canvas pageCanvas = new Canvas(pageBitmap);
                pageCanvas.drawColor(android.graphics.Color.WHITE);
                page.render(pageBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                page.close();

                Bitmap compressedBitmap = null;
                try {
                    // Apply JPEG compression scale
                    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                    pageBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream);
                    byte[] compressedBytes = outputStream.toByteArray();
                    compressedBitmap = BitmapFactory.decodeByteArray(compressedBytes, 0, compressedBytes.length);

                    PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(pageW, pageH, i + 1).create();
                    PdfDocument.Page docPage = document.startPage(pageInfo);
                    Canvas docCanvas = docPage.getCanvas();
                    RectF destRect = new RectF(0, 0, pageW, pageH);

                    if (compressedBitmap != null) {
                        docCanvas.drawBitmap(compressedBitmap, null, destRect, paint);
                    } else {
                        docCanvas.drawBitmap(pageBitmap, null, destRect, paint);
                    }
                    document.finishPage(docPage);

                } finally {
                    // Explicitly recycle both Bitmaps immediately
                    if (compressedBitmap != null && !compressedBitmap.isRecycled()) {
                        compressedBitmap.recycle();
                    }
                    if (pageBitmap != null && !pageBitmap.isRecycled()) {
                        pageBitmap.recycle();
                    }
                }
            }

            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                document.writeTo(fos);
                fos.flush();
            }

            long fileSize = outputFile.length();
            Log.i(TAG, "External PDF compressed: " + outputFile.getAbsolutePath() + " (" + fileSize + " bytes)");
            return fileSize;

        } finally {
            if (document != null) {
                document.close();
            }
            if (renderer != null) {
                renderer.close();
            }
            pfd.close();
        }
    }

    public interface CompressionProgressListener {
        void onProgress(int currentPage, int totalPages);
    }

    /**
     * Compresses an external PDF using an iterative binary search loop to match the
     * resulting byte array size to the requested target size as closely as possible.
     *
     * @param context         Application context.
     * @param sourcePdfUri    Uri of the external PDF to compress.
     * @param outputFile      Destination file for the compressed PDF.
     * @param targetSizeBytes Requested target size in bytes.
     * @param listener        Optional progress listener.
     * @return Resulting compressed file size in bytes.
     * @throws IOException If file operations fail.
     */
    public static long compressPdfToTargetSize(Context context,
                                               Uri sourcePdfUri,
                                               File outputFile,
                                               long targetSizeBytes,
                                               CompressionProgressListener listener) throws IOException {
        ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(sourcePdfUri, "r");
        if (pfd == null) {
            throw new IOException("Unable to open file descriptor for URI: " + sourcePdfUri);
        }

        PdfRenderer renderer = null;
        PdfDocument document = null;
        try {
            renderer = new PdfRenderer(pfd);
            int pageCount = renderer.getPageCount();
            if (pageCount == 0) {
                throw new IOException("PDF contains 0 pages");
            }

            document = new PdfDocument();
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

            // Reserve ~8% overhead for PDF headers, xref tables, and trailer structures
            long totalTargetImageBytes = Math.max(16384L, (long) (targetSizeBytes * 0.92));
            long targetBytesPerPage = Math.max(8192L, totalTargetImageBytes / pageCount);

            for (int i = 0; i < pageCount; i++) {
                if (listener != null) {
                    listener.onProgress(i + 1, pageCount);
                }

                PdfRenderer.Page page = renderer.openPage(i);
                int pageW = page.getWidth();
                int pageH = page.getHeight();

                // High-resolution rendering
                int renderW = Math.max(pageW * 2, PAGE_WIDTH_A4);
                int renderH = Math.round((float) renderW * pageH / pageW);

                Bitmap pageBitmap = Bitmap.createBitmap(renderW, renderH, Bitmap.Config.ARGB_8888);
                Canvas pageCanvas = new Canvas(pageBitmap);
                pageCanvas.drawColor(android.graphics.Color.WHITE);
                page.render(pageBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                page.close();

                Bitmap compressedBitmap = null;
                try {
                    // Iterative binary search loop adjusting Bitmap.compress quality (0-100)
                    int low = 5;
                    int high = 100;
                    int bestQuality = 60;
                    long bestDiff = Long.MAX_VALUE;
                    byte[] bestBytes = null;

                    while (low <= high) {
                        int mid = (low + high) / 2;
                        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                        pageBitmap.compress(Bitmap.CompressFormat.JPEG, mid, outputStream);
                        byte[] currentBytes = outputStream.toByteArray();
                        long currentSize = currentBytes.length;
                        long diff = Math.abs(currentSize - targetBytesPerPage);

                        if (diff < bestDiff) {
                            bestDiff = diff;
                            bestQuality = mid;
                            bestBytes = currentBytes;
                        }

                        if (currentSize > targetBytesPerPage) {
                            high = mid - 1;
                        } else if (currentSize < targetBytesPerPage) {
                            low = mid + 1;
                        } else {
                            bestQuality = mid;
                            bestBytes = currentBytes;
                            break;
                        }
                    }

                    if (bestBytes != null && bestBytes.length > 0) {
                        compressedBitmap = BitmapFactory.decodeByteArray(bestBytes, 0, bestBytes.length);
                    }

                    PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(pageW, pageH, i + 1).create();
                    PdfDocument.Page docPage = document.startPage(pageInfo);
                    Canvas docCanvas = docPage.getCanvas();
                    RectF destRect = new RectF(0, 0, pageW, pageH);

                    if (compressedBitmap != null) {
                        docCanvas.drawBitmap(compressedBitmap, null, destRect, paint);
                    } else {
                        docCanvas.drawBitmap(pageBitmap, null, destRect, paint);
                    }
                    document.finishPage(docPage);

                } finally {
                    if (compressedBitmap != null && !compressedBitmap.isRecycled()) {
                        compressedBitmap.recycle();
                    }
                    if (pageBitmap != null && !pageBitmap.isRecycled()) {
                        pageBitmap.recycle();
                    }
                }
            }

            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                document.writeTo(fos);
                fos.flush();
            }

            long fileSize = outputFile.length();
            Log.i(TAG, "Target-size PDF compressed: " + outputFile.getAbsolutePath() + " (" + fileSize + " bytes, target=" + targetSizeBytes + ")");
            return fileSize;

        } finally {
            if (document != null) {
                document.close();
            }
            if (renderer != null) {
                renderer.close();
            }
            pfd.close();
        }
    }

    /**
     * Creates a PDF from page image paths using an iterative binary search loop to match
     * the requested target file size.
     */
    public static long createPdfToTargetSize(List<String> pagePaths,
                                             File outputFile,
                                             long targetSizeBytes) throws IOException {
        if (pagePaths == null || pagePaths.isEmpty()) {
            throw new IllegalArgumentException("Page paths list cannot be empty");
        }

        PdfDocument document = new PdfDocument();
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

        try {
            int pageCount = pagePaths.size();
            long totalTargetImageBytes = Math.max(16384L, (long) (targetSizeBytes * 0.92));
            long targetBytesPerPage = Math.max(8192L, totalTargetImageBytes / pageCount);

            int pageNumber = 1;
            for (String pagePath : pagePaths) {
                Bitmap bitmap = loadBitmapFromPath(pagePath);
                if (bitmap == null) {
                    pageNumber++;
                    continue;
                }

                Bitmap tempBitmap = null;
                try {
                    PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(
                            PAGE_WIDTH_A4, PAGE_HEIGHT_A4, pageNumber
                    ).create();

                    PdfDocument.Page page = document.startPage(pageInfo);
                    Canvas canvas = page.getCanvas();

                    RectF destRect = calculateFitRect(
                            bitmap.getWidth(), bitmap.getHeight(),
                            PAGE_WIDTH_A4, PAGE_HEIGHT_A4,
                            PAGE_MARGIN
                    );

                    // Iterative binary search loop
                    int low = 5;
                    int high = 100;
                    int bestQuality = 60;
                    long bestDiff = Long.MAX_VALUE;
                    byte[] bestBytes = null;

                    while (low <= high) {
                        int mid = (low + high) / 2;
                        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                        bitmap.compress(Bitmap.CompressFormat.JPEG, mid, outputStream);
                        byte[] currentBytes = outputStream.toByteArray();
                        long currentSize = currentBytes.length;
                        long diff = Math.abs(currentSize - targetBytesPerPage);

                        if (diff < bestDiff) {
                            bestDiff = diff;
                            bestQuality = mid;
                            bestBytes = currentBytes;
                        }

                        if (currentSize > targetBytesPerPage) {
                            high = mid - 1;
                        } else if (currentSize < targetBytesPerPage) {
                            low = mid + 1;
                        } else {
                            bestQuality = mid;
                            bestBytes = currentBytes;
                            break;
                        }
                    }

                    if (bestBytes != null && bestBytes.length > 0) {
                        tempBitmap = BitmapFactory.decodeByteArray(bestBytes, 0, bestBytes.length);
                    }

                    if (tempBitmap != null) {
                        canvas.drawBitmap(tempBitmap, null, destRect, paint);
                    } else {
                        canvas.drawBitmap(bitmap, null, destRect, paint);
                    }

                    document.finishPage(page);
                    pageNumber++;

                } finally {
                    if (tempBitmap != null && !tempBitmap.isRecycled()) {
                        tempBitmap.recycle();
                    }
                    if (bitmap != null && !bitmap.isRecycled()) {
                        bitmap.recycle();
                    }
                }
            }

            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                document.writeTo(fos);
                fos.flush();
            }

            return outputFile.length();

        } finally {
            document.close();
        }
    }

    /**
     * Extracts a permanent 200x200 thumbnail from the first page of a PDF.
     */
    public static String generateThumbnailFromPdf(Context context, Uri pdfUri, long timestamp) {
        try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(pdfUri, "r")) {
            if (pfd == null) return null;
            try (PdfRenderer renderer = new PdfRenderer(pfd)) {
                if (renderer.getPageCount() == 0) return null;
                PdfRenderer.Page page = renderer.openPage(0);
                int targetW = 200;
                int targetH = Math.max(1, Math.round((float) targetW * page.getHeight() / page.getWidth()));
                Bitmap thumb = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(thumb);
                c.drawColor(android.graphics.Color.WHITE);
                page.render(thumb, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                page.close();

                File file = new File(context.getFilesDir(), "thumb_" + timestamp + ".jpg");
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    thumb.compress(Bitmap.CompressFormat.JPEG, 85, fos);
                    fos.flush();
                }
                thumb.recycle();
                return file.getAbsolutePath();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to create thumbnail from PDF", e);
            return null;
        }
    }

    /**
     * Returns the total page count of a PDF given its URI.
     */
    public static int getPdfPageCount(Context context, Uri pdfUri) {
        try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(pdfUri, "r")) {
            if (pfd == null) return 1;
            try (PdfRenderer renderer = new PdfRenderer(pfd)) {
                return renderer.getPageCount();
            }
        } catch (Exception e) {
            return 1;
        }
    }

    /**
     * Loads a bitmap from a file path.
     */
    private static Bitmap loadBitmapFromPath(String path) {
        try {
            return BitmapFactory.decodeFile(path);
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
