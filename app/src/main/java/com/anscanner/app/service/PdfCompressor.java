package com.anscanner.app.service;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.pdf.PdfDocument;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * High-performance PDF compression engine with dynamic downsampling and quality tuning.
 *
 * <p>Prevents file size inflation by:
 * <ul>
 *   <li>Calculating a dynamic target byte budget per page based on total page count.</li>
 *   <li>Dynamically downsampling rendered bitmaps using a scale factor proportional to the square root of the target budget.</li>
 *   <li>Capping maximum rendering dimensions to 150 DPI (~1240 x 1754 px) rather than native screen or 300+ DPI.</li>
 *   <li>Directly embedding compressed JPEG streams into the PDF via PDFBox, preventing Skia from re-inflating them into uncompressed ARGB_8888 streams.</li>
 * </ul>
 * </p>
 */
public final class PdfCompressor {

    private static final String TAG = "PdfCompressor";

    /** Standard A4 dimensions in PDF points (72 DPI). */
    public static final int PAGE_WIDTH_A4_POINTS = 595;
    public static final int PAGE_HEIGHT_A4_POINTS = 842;

    /** Capped maximum dimensions (150 DPI A4) to prevent 300+ DPI bitmap inflation. */
    public static final int MAX_RENDER_WIDTH = 1240;
    public static final int MAX_RENDER_HEIGHT = 1754;

    /** Estimated baseline compressed page byte size at 150 DPI (~300 KB). */
    public static final long ESTIMATED_RAW_PAGE_BYTES = 300_000L;

    public interface CompressionProgressListener {
        void onProgress(int currentPage, int totalPages);
    }

    private static class PageData {
        final byte[] jpegBytes;
        final int pointWidth;
        final int pointHeight;

        PageData(byte[] jpegBytes, int pointWidth, int pointHeight) {
            this.jpegBytes = jpegBytes;
            this.pointWidth = pointWidth;
            this.pointHeight = pointHeight;
        }
    }

    private PdfCompressor() {
        // Static utility class
    }

    /**
     * Compresses a PDF to a specific target byte size using dynamic downsampling and quality tuning.
     *
     * @param context         Application context.
     * @param sourcePdfUri    URI of the PDF to compress.
     * @param outputFile      Output destination file.
     * @param targetSizeBytes Requested target size in bytes.
     * @param listener        Optional progress callback.
     * @return Resulting compressed file size in bytes.
     * @throws IOException If file access or compression fails.
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
        try {
            renderer = new PdfRenderer(pfd);
            int pageCount = renderer.getPageCount();
            if (pageCount == 0) {
                throw new IOException("PDF contains 0 pages");
            }

            // 1. Calculate Target Budget per Page (reserve ~10% for PDF structure/xref overhead)
            long totalTargetImageBytes = Math.max(16384L, (long) (targetSizeBytes * 0.90));
            long targetBytesPerPage = Math.max(8192L, totalTargetImageBytes / pageCount);

            // 2. Calculate Dynamic Downsampling Scale Factor
            float scale = Math.min(1.0f, (float) Math.sqrt((double) targetBytesPerPage / ESTIMATED_RAW_PAGE_BYTES));
            scale = Math.max(0.30f, scale); // Minimum scale floor to ensure text remains legible

            Log.i(TAG, String.format("Target compression: totalTarget=%d bytes, pages=%d, perPage=%d bytes, scale=%.2f",
                    targetSizeBytes, pageCount, targetBytesPerPage, scale));

            List<PageData> compressedPages = new ArrayList<>(pageCount);

            for (int i = 0; i < pageCount; i++) {
                if (listener != null) {
                    listener.onProgress(i + 1, pageCount);
                }

                PdfRenderer.Page page = renderer.openPage(i);
                int pageW = page.getWidth() > 0 ? page.getWidth() : PAGE_WIDTH_A4_POINTS;
                int pageH = page.getHeight() > 0 ? page.getHeight() : PAGE_HEIGHT_A4_POINTS;

                // Cap max dimensions at 150 DPI (standard A4: 1240 x 1754 px)
                int baseW = Math.min(pageW * 2, MAX_RENDER_WIDTH);
                int baseH = Math.round((float) baseW * pageH / pageW);
                if (baseH > MAX_RENDER_HEIGHT) {
                    baseH = MAX_RENDER_HEIGHT;
                    baseW = Math.round((float) baseH * pageW / pageH);
                }

                int renderW = Math.max(360, Math.round(baseW * scale));
                int renderH = Math.max(480, Math.round(baseH * scale));

                // Render page (PdfRenderer strictly requires ARGB_8888 for render target)
                Bitmap pageBitmap = Bitmap.createBitmap(renderW, renderH, Bitmap.Config.ARGB_8888);
                Canvas pageCanvas = new Canvas(pageBitmap);
                pageCanvas.drawColor(Color.WHITE);
                page.render(pageBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
                page.close();

                // 3. Quality Tuning: Binary search JPEG quality to match target byte budget per page
                byte[] bestBytes = tuneJpegQuality(pageBitmap, targetBytesPerPage);
                pageBitmap.recycle();

                compressedPages.add(new PageData(bestBytes, pageW, pageH));
            }

            // 4. Assemble the final PDF with direct JPEG stream embedding
            writePagesToPdf(context, compressedPages, outputFile);

            long finalSize = outputFile.length();
            Log.i(TAG, String.format("Compression completed: target=%d, actual=%d bytes", targetSizeBytes, finalSize));
            return finalSize;

        } finally {
            if (renderer != null) {
                renderer.close();
            }
            pfd.close();
        }
    }

    /**
     * Compresses a PDF with a quality preset (100 = High, 60 = Medium, 30 = Low).
     *
     * @param context      Application context.
     * @param sourcePdfUri URI of the PDF to compress.
     * @param outputFile   Output destination file.
     * @param quality      Quality percentage (e.g. 100, 60, 30).
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
        try {
            renderer = new PdfRenderer(pfd);
            int pageCount = renderer.getPageCount();
            if (pageCount == 0) {
                throw new IOException("PDF contains 0 pages");
            }

            // Map quality preset to scale factor and JPEG compression quality
            float scale;
            int jpegQuality;
            if (quality >= 85) {
                // High Quality: Crisp 150 DPI (~1240px width), 82% JPEG
                scale = 1.0f;
                jpegQuality = 82;
            } else if (quality >= 50) {
                // Medium Quality: Downsampled to ~75% (~930px width), 60% JPEG
                scale = 0.75f;
                jpegQuality = 60;
            } else {
                // Low Quality: Downsampled to ~55% (~680px width), 40% JPEG
                scale = 0.55f;
                jpegQuality = 40;
            }

            List<PageData> compressedPages = new ArrayList<>(pageCount);

            for (int i = 0; i < pageCount; i++) {
                PdfRenderer.Page page = renderer.openPage(i);
                int pageW = page.getWidth() > 0 ? page.getWidth() : PAGE_WIDTH_A4_POINTS;
                int pageH = page.getHeight() > 0 ? page.getHeight() : PAGE_HEIGHT_A4_POINTS;

                int baseW = Math.min(pageW * 2, MAX_RENDER_WIDTH);
                int baseH = Math.round((float) baseW * pageH / pageW);
                if (baseH > MAX_RENDER_HEIGHT) {
                    baseH = MAX_RENDER_HEIGHT;
                    baseW = Math.round((float) baseH * pageW / pageH);
                }

                int renderW = Math.max(360, Math.round(baseW * scale));
                int renderH = Math.max(480, Math.round(baseH * scale));

                Bitmap pageBitmap = Bitmap.createBitmap(renderW, renderH, Bitmap.Config.ARGB_8888);
                Canvas pageCanvas = new Canvas(pageBitmap);
                pageCanvas.drawColor(Color.WHITE);
                page.render(pageBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
                page.close();

                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                pageBitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, bos);
                byte[] jpegBytes = bos.toByteArray();
                pageBitmap.recycle();

                compressedPages.add(new PageData(jpegBytes, pageW, pageH));
            }

            writePagesToPdf(context, compressedPages, outputFile);
            return outputFile.length();

        } finally {
            if (renderer != null) {
                renderer.close();
            }
            pfd.close();
        }
    }

    /**
     * Binary searches the optimal JPEG quality parameter to best hit the target byte budget.
     */
    private static byte[] tuneJpegQuality(Bitmap bitmap, long targetBytesPerPage) {
        int low = 15;
        int high = 90;
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
                bestBytes = currentBytes;
                break;
            }
        }

        if (bestBytes == null) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, bestQuality, bos);
            bestBytes = bos.toByteArray();
        }

        return bestBytes;
    }

    /**
     * Writes compressed JPEG page streams directly to the output PDF.
     * Uses Apache PDFBox for direct JPEG embedding, with fallback to native Android PdfDocument.
     */
    private static void writePagesToPdf(Context context, List<PageData> pages, File outputFile) throws IOException {
        try {
            PDFBoxResourceLoader.init(context.getApplicationContext());
            try (PDDocument pdDoc = new PDDocument()) {
                for (int i = 0; i < pages.size(); i++) {
                    PageData pd = pages.get(i);
                    PDRectangle rect = new PDRectangle(pd.pointWidth, pd.pointHeight);
                    PDPage page = new PDPage(rect);
                    pdDoc.addPage(page);

                    PDImageXObject pdImage = PDImageXObject.createFromByteArray(pdDoc, pd.jpegBytes, "page_" + i);
                    try (PDPageContentStream contentStream = new PDPageContentStream(pdDoc, page)) {
                        contentStream.drawImage(pdImage, 0, 0, pd.pointWidth, pd.pointHeight);
                    }
                }
                pdDoc.save(outputFile);
            }
        } catch (Throwable t) {
            Log.w(TAG, "PDFBox direct stream embedding encountered issue, falling back to native PdfDocument", t);
            writePagesNativeFallback(pages, outputFile);
        }
    }

    /**
     * Fallback writer using native Android PdfDocument with RGB_565 to avoid ARGB_8888 SMask inflation.
     */
    private static void writePagesNativeFallback(List<PageData> pages, File outputFile) throws IOException {
        PdfDocument document = new PdfDocument();
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        paint.setDither(true);

        BitmapFactory.Options decodeOpts = new BitmapFactory.Options();
        decodeOpts.inPreferredConfig = Bitmap.Config.RGB_565;
        decodeOpts.inDither = true;

        try {
            for (int i = 0; i < pages.size(); i++) {
                PageData pd = pages.get(i);
                Bitmap pageBmp = BitmapFactory.decodeByteArray(pd.jpegBytes, 0, pd.jpegBytes.length, decodeOpts);
                if (pageBmp == null) {
                    continue;
                }

                PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(pd.pointWidth, pd.pointHeight, i + 1).create();
                PdfDocument.Page docPage = document.startPage(pageInfo);
                Canvas docCanvas = docPage.getCanvas();
                RectF destRect = new RectF(0, 0, pd.pointWidth, pd.pointHeight);

                docCanvas.drawBitmap(pageBmp, null, destRect, paint);
                document.finishPage(docPage);

                pageBmp.recycle();
            }

            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                document.writeTo(fos);
                fos.flush();
            }
        } finally {
            document.close();
        }
    }
}
