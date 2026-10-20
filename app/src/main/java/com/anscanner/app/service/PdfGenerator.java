package com.anscanner.app.service;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.pdf.PdfDocument;
import android.graphics.pdf.PdfRenderer;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission;
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy;

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

    /** Standard A4 page dimensions in PDF points (72 points per inch). */
    public static final int PAGE_WIDTH_A4 = 595;
    public static final int PAGE_HEIGHT_A4 = 842;

    /** Standard US Letter page dimensions in PDF points (72 points per inch). */
    public static final int PAGE_WIDTH_LETTER = 612;
    public static final int PAGE_HEIGHT_LETTER = 792;

    /** Default fallback DPI for images when EXIF metadata is missing or non-positive. */
    public static final float DEFAULT_DPI = 72.0f;

    /** Margin in PDF points around each page (used when explicit margins are desired). */
    public static final float PAGE_MARGIN = 20f;

    /**
     * Page format specification for PDF generation.
     */
    public enum PageFormat {
        /** Matches each page's dimensions to the input image's native aspect ratio and dimensions (zero pillarboxing/letterboxing). */
        FIT_IMAGE,
        /** Standard ISO A4 dimensions (595 x 842 pt). */
        A4,
        /** Standard US Letter dimensions (612 x 792 pt). */
        LETTER
    }

    /**
     * Fit mode when a fixed page format (such as A4 or LETTER) is chosen.
     */
    public enum PageFitMode {
        /** Proportional scale with centering within page bounds (may have side bars if aspect ratios differ). */
        FIT_PAGE,
        /** Proportional scale covering the full page bounds (crop to fill, zero white side bars). */
        FILL_PAGE
    }

    /**
     * Calculated layout dimensions and render bounds for a PDF page.
     */
    public static final class PageLayout {
        public final int pageWidth;
        public final int pageHeight;
        public final Rect srcRect;   // null if entire source bitmap is drawn
        public final RectF destRect;

        public PageLayout(int pageWidth, int pageHeight, Rect srcRect, RectF destRect) {
            this.pageWidth = pageWidth;
            this.pageHeight = pageHeight;
            this.srcRect = srcRect;
            this.destRect = destRect;
        }
    }

    private PdfGenerator() {
        // Static utility class
    }

    /**
     * Creates a multi-page PDF from a list of page image file paths using the default
     * {@link PageFormat#FIT_IMAGE} mode (zero pillarboxing/letterboxing) and standard fit.
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
        return createPdfFromPages(pagePaths, outputFile, quality, PageFormat.FIT_IMAGE, PageFitMode.FIT_PAGE);
    }

    /**
     * Backward-compatible overload for boolean highQuality using {@link PageFormat#FIT_IMAGE}.
     */
    public static long createPdfFromPages(List<String> pagePaths,
                                          File outputFile,
                                          boolean highQuality) throws IOException {
        return createPdfFromPages(pagePaths, outputFile, highQuality ? 100 : 60, PageFormat.FIT_IMAGE, PageFitMode.FIT_PAGE);
    }

    /**
     * Creates a multi-page PDF with a specific {@link PageFormat} using default {@link PageFitMode#FIT_PAGE}.
     */
    public static long createPdfFromPages(List<String> pagePaths,
                                          File outputFile,
                                          int quality,
                                          PageFormat pageFormat) throws IOException {
        return createPdfFromPages(pagePaths, outputFile, quality, pageFormat, PageFitMode.FIT_PAGE);
    }

    /**
     * Creates a multi-page PDF from a list of page image file paths with configurable page format and fit mode.
     *
     * <p>When {@link PageFormat#FIT_IMAGE} is used, each page dynamically sizes its PDF canvas bounds
     * to match the source image's aspect ratio and dimensions:
     * <pre>
     *   page_width  = image_width  * (72 / dpi)
     *   page_height = image_height * (72 / dpi)
     * </pre>
     * and renders edge-to-edge with 0 margin padding, avoiding any white pillarboxing or letterboxing.</p>
     *
     * @param pagePaths   Ordered list of absolute file paths to page JPEG images.
     * @param outputFile  Destination file for the PDF.
     * @param quality     Compression quality (1-100), e.g. 100 for High, 60 for Medium, 30 for Low.
     * @param pageFormat  Page layout format (FIT_IMAGE, A4, LETTER).
     * @param pageFitMode Fitting mode for fixed formats (FIT_PAGE, FILL_PAGE).
     * @return The file size in bytes, or -1 on failure.
     * @throws IOException If file writing fails.
     */
    public static long createPdfFromPages(List<String> pagePaths,
                                          File outputFile,
                                          int quality,
                                          PageFormat pageFormat,
                                          PageFitMode pageFitMode) throws IOException {
        if (pagePaths == null || pagePaths.isEmpty()) {
            throw new IllegalArgumentException("Page paths list cannot be empty");
        }
        if (pageFormat == null) {
            pageFormat = PageFormat.FIT_IMAGE;
        }
        if (pageFitMode == null) {
            pageFitMode = PageFitMode.FIT_PAGE;
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
                    float dpi = getImageDpi(pagePath);
                    PageLayout layout = computePageLayout(bitmap.getWidth(), bitmap.getHeight(), dpi, pageFormat, pageFitMode);

                    // Configure page with per-page independent dynamic dimensions
                    PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(
                            layout.pageWidth, layout.pageHeight, pageNumber
                    ).create();

                    PdfDocument.Page page = document.startPage(pageInfo);
                    Canvas canvas = page.getCanvas();

                    // 1. First compress the Bitmap: bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
                    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream);
                    byte[] compressedBytes = outputStream.toByteArray();

                    // 2. Decode the compressed byte array into an opaque RGB_565 Bitmap to avoid ARGB_8888 SMask overhead
                    BitmapFactory.Options decodeOpts = new BitmapFactory.Options();
                    decodeOpts.inPreferredConfig = Bitmap.Config.RGB_565;
                    decodeOpts.inDither = true;
                    tempBitmap = BitmapFactory.decodeByteArray(compressedBytes, 0, compressedBytes.length, decodeOpts);

                    Bitmap drawBitmap = (tempBitmap != null) ? tempBitmap : bitmap;
                    Rect srcRect = layout.srcRect;
                    if (srcRect != null && (drawBitmap.getWidth() != bitmap.getWidth() || drawBitmap.getHeight() != bitmap.getHeight())) {
                        srcRect = calculateFillSrcRect(drawBitmap.getWidth(), drawBitmap.getHeight(), layout.pageWidth, layout.pageHeight);
                    }

                    // 3. Draw it to the PDF canvas (edge-to-edge for FIT_IMAGE and FILL_PAGE)
                    canvas.drawBitmap(drawBitmap, srcRect, layout.destRect, paint);

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
                    " (" + (pageNumber - 1) + " pages, " + fileSize + " bytes, quality=" + quality +
                    ", format=" + pageFormat + ", fitMode=" + pageFitMode + ")");
            return fileSize;

        } finally {
            document.close();
        }
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
        return PdfCompressor.compressPdf(context, sourcePdfUri, outputFile, quality);
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
        return PdfCompressor.compressPdfToTargetSize(context, sourcePdfUri, outputFile, targetSizeBytes,
                listener != null ? listener::onProgress : null);
    }

    /**
     * Creates a PDF from page image paths using an iterative binary search loop to match
     * the requested target file size, defaulting to {@link PageFormat#FIT_IMAGE}.
     */
    public static long createPdfToTargetSize(List<String> pagePaths,
                                             File outputFile,
                                             long targetSizeBytes) throws IOException {
        return createPdfToTargetSize(pagePaths, outputFile, targetSizeBytes, PageFormat.FIT_IMAGE, PageFitMode.FIT_PAGE);
    }

    /**
     * Creates a PDF from page image paths using an iterative binary search loop to match
     * the requested target file size with configurable {@link PageFormat} and {@link PageFitMode}.
     */
    public static long createPdfToTargetSize(List<String> pagePaths,
                                             File outputFile,
                                             long targetSizeBytes,
                                             PageFormat pageFormat,
                                             PageFitMode pageFitMode) throws IOException {
        if (pagePaths == null || pagePaths.isEmpty()) {
            throw new IllegalArgumentException("Page paths list cannot be empty");
        }
        if (pageFormat == null) {
            pageFormat = PageFormat.FIT_IMAGE;
        }
        if (pageFitMode == null) {
            pageFitMode = PageFitMode.FIT_PAGE;
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
                    float dpi = getImageDpi(pagePath);
                    PageLayout layout = computePageLayout(bitmap.getWidth(), bitmap.getHeight(), dpi, pageFormat, pageFitMode);

                    PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(
                            layout.pageWidth, layout.pageHeight, pageNumber
                    ).create();

                    PdfDocument.Page page = document.startPage(pageInfo);
                    Canvas canvas = page.getCanvas();

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
                        BitmapFactory.Options decodeOpts = new BitmapFactory.Options();
                        decodeOpts.inPreferredConfig = Bitmap.Config.RGB_565;
                        decodeOpts.inDither = true;
                        tempBitmap = BitmapFactory.decodeByteArray(bestBytes, 0, bestBytes.length, decodeOpts);
                    }

                    Bitmap drawBitmap = (tempBitmap != null) ? tempBitmap : bitmap;
                    Rect srcRect = layout.srcRect;
                    if (srcRect != null && (drawBitmap.getWidth() != bitmap.getWidth() || drawBitmap.getHeight() != bitmap.getHeight())) {
                        srcRect = calculateFillSrcRect(drawBitmap.getWidth(), drawBitmap.getHeight(), layout.pageWidth, layout.pageHeight);
                    }

                    canvas.drawBitmap(drawBitmap, srcRect, layout.destRect, paint);

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
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            opts.inScaled = false;
            return BitmapFactory.decodeFile(path, opts);
        } catch (Exception e) {
            Log.e(TAG, "Error loading bitmap from " + path, e);
            return null;
        }
    }

    /**
     * Computes the page dimensions, source crop rectangle, and destination canvas rectangle
     * for an individual image according to the selected {@link PageFormat} and {@link PageFitMode}.
     *
     * @param imgWidth  Width of the source image in pixels.
     * @param imgHeight Height of the source image in pixels.
     * @param dpi       DPI of the source image (or fallback DPI).
     * @param format    Page format (defaults to {@link PageFormat#FIT_IMAGE}).
     * @param fitMode   Fit mode (defaults to {@link PageFitMode#FIT_PAGE}).
     * @return Computed {@link PageLayout}.
     */
    public static PageLayout computePageLayout(int imgWidth, int imgHeight,
                                               float dpi,
                                               PageFormat format,
                                               PageFitMode fitMode) {
        if (format == null) {
            format = PageFormat.FIT_IMAGE;
        }
        if (fitMode == null) {
            fitMode = PageFitMode.FIT_PAGE;
        }

        if (format == PageFormat.FIT_IMAGE) {
            if (dpi <= 0) {
                dpi = DEFAULT_DPI;
            }
            float scale = 72.0f / dpi;
            int pageWidth = Math.max(1, Math.round(imgWidth * scale));
            int pageHeight = Math.max(1, Math.round(imgHeight * scale));
            RectF destRect = new RectF(0, 0, pageWidth, pageHeight);
            return new PageLayout(pageWidth, pageHeight, null, destRect);
        }

        // Standard fixed formats (A4 or LETTER)
        int stdW = (format == PageFormat.LETTER) ? PAGE_WIDTH_LETTER : PAGE_WIDTH_A4;
        int stdH = (format == PageFormat.LETTER) ? PAGE_HEIGHT_LETTER : PAGE_HEIGHT_A4;

        // Auto-orient page to match image aspect ratio
        boolean isLandscape = imgWidth > imgHeight;
        int pageWidth = isLandscape ? stdH : stdW;
        int pageHeight = isLandscape ? stdW : stdH;

        if (fitMode == PageFitMode.FILL_PAGE) {
            Rect srcRect = calculateFillSrcRect(imgWidth, imgHeight, pageWidth, pageHeight);
            RectF destRect = new RectF(0, 0, pageWidth, pageHeight);
            return new PageLayout(pageWidth, pageHeight, srcRect, destRect);
        } else {
            // FIT_PAGE: Proportional scale with centering, zero margin
            RectF destRect = calculateFitRect(imgWidth, imgHeight, pageWidth, pageHeight, 0f);
            return new PageLayout(pageWidth, pageHeight, null, destRect);
        }
    }

    /**
     * Calculates the center-crop bounding box [left, top, right, bottom] on the input image
     * to fill the target aspect ratio without distortion or side bars.
     *
     * @return An integer array containing [left, top, right, bottom].
     */
    public static int[] calculateFillCropBounds(int srcWidth, int srcHeight, int targetWidth, int targetHeight) {
        if (srcWidth <= 0 || srcHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) {
            return new int[]{0, 0, Math.max(1, srcWidth), Math.max(1, srcHeight)};
        }
        float srcAspect = (float) srcWidth / (float) srcHeight;
        float targetAspect = (float) targetWidth / (float) targetHeight;

        if (srcAspect > targetAspect) {
            // Source is wider than target aspect -> crop left and right
            int cropWidth = Math.max(1, Math.round(srcHeight * targetAspect));
            int left = Math.max(0, (srcWidth - cropWidth) / 2);
            return new int[]{left, 0, Math.min(srcWidth, left + cropWidth), srcHeight};
        } else {
            // Source is taller than target aspect -> crop top and bottom
            int cropHeight = Math.max(1, Math.round(srcWidth / targetAspect));
            int top = Math.max(0, (srcHeight - cropHeight) / 2);
            return new int[]{0, top, srcWidth, Math.min(srcHeight, top + cropHeight)};
        }
    }

    /**
     * Calculates the center-crop source rectangle on the input image to fill the target
     * aspect ratio without distortion or side bars.
     */
    public static Rect calculateFillSrcRect(int srcWidth, int srcHeight, int targetWidth, int targetHeight) {
        int[] b = calculateFillCropBounds(srcWidth, srcHeight, targetWidth, targetHeight);
        return new Rect(b[0], b[1], b[2], b[3]);
    }

    /**
     * Safely extracts horizontal DPI from image EXIF metadata, falling back to {@link #DEFAULT_DPI} (72 DPI)
     * if EXIF metadata is missing, non-positive, or unreadable.
     *
     * @param imagePath Absolute path to the image file.
     * @return DPI in dots-per-inch, or 72.0f if not present or invalid.
     */
    public static float getImageDpi(String imagePath) {
        if (imagePath == null) {
            return DEFAULT_DPI;
        }
        try {
            ExifInterface exif = new ExifInterface(imagePath);
            double xRes = exif.getAttributeDouble(ExifInterface.TAG_X_RESOLUTION, 0.0);
            if (xRes <= 0.0) {
                String xResStr = exif.getAttribute(ExifInterface.TAG_X_RESOLUTION);
                if (xResStr != null && !xResStr.trim().isEmpty()) {
                    xRes = parseRational(xResStr);
                }
            }
            if (xRes > 0.0) {
                int unit = exif.getAttributeInt(ExifInterface.TAG_RESOLUTION_UNIT, 2); // 2 = inches, 3 = cm
                if (unit == 3) {
                    xRes = xRes * 2.54; // Convert dots/cm to dots/inch
                }
                return (float) xRes;
            }
        } catch (Throwable t) {
            Log.w(TAG, "Could not extract EXIF DPI from " + imagePath + ", using fallback " + DEFAULT_DPI + ": " + t.getMessage());
        }
        return DEFAULT_DPI;
    }

    private static double parseRational(String rationalStr) {
        try {
            int slashIndex = rationalStr.indexOf('/');
            if (slashIndex > 0) {
                double num = Double.parseDouble(rationalStr.substring(0, slashIndex).trim());
                double den = Double.parseDouble(rationalStr.substring(slashIndex + 1).trim());
                if (den != 0) {
                    return num / den;
                }
            } else {
                return Double.parseDouble(rationalStr.trim());
            }
        } catch (Exception ignored) {
        }
        return 0.0;
    }

    /**
     * Calculates a destination rectangle that preserves the source aspect ratio
     * within the page dimensions minus margins.
     */
    public static RectF calculateFitRect(int srcWidth, int srcHeight,
                                          int pageWidth, int pageHeight,
                                          float margin) {
        float availableWidth = Math.max(1f, pageWidth - (2 * margin));
        float availableHeight = Math.max(1f, pageHeight - (2 * margin));

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

    /**
     * Encrypts a PDF file using standard 128-bit AES encryption with the specified password.
     * The protected PDF will require the password to open in any standard PDF reader.
     *
     * @param context   Application context.
     * @param sourcePdf The original unencrypted PDF file.
     * @param destPdf   The destination file for the encrypted PDF.
     * @param password  User password required to open the PDF.
     * @throws IOException If file reading, encryption, or writing fails.
     */
    public static void encryptPdf(Context context, File sourcePdf, File destPdf, String password) throws IOException {
        if (password == null || password.trim().isEmpty()) {
            throw new IllegalArgumentException("Encryption password cannot be empty");
        }
        PDFBoxResourceLoader.init(context.getApplicationContext());
        try (PDDocument document = PDDocument.load(sourcePdf)) {
            AccessPermission ap = new AccessPermission();
            StandardProtectionPolicy spp = new StandardProtectionPolicy(password, password, ap);
            spp.setEncryptionKeyLength(128);
            spp.setPermissions(ap);
            document.protect(spp);
            document.save(destPdf);
        }
    }
}
