package com.anscanner.app.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.anscanner.app.service.CacheManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.UUID;

/**
 * Centralized utility for safe PDF page rasterization using Android's {@link PdfRenderer}.
 *
 * <p><strong>Key Invariant:</strong>
 * {@link PdfRenderer.Page#render(Bitmap, android.graphics.Rect, android.graphics.Matrix, int)}
 * rasterizes transparent page backgrounds onto bitmaps. To prevent black background artifacts
 * and invisible black text in Dark Mode or when exporting to JPEG, this helper guarantees
 * an opaque white fill ({@link Canvas#drawColor(int)} with {@link Color#WHITE}) before
 * any page rendering takes place.
 * </p>
 */
public final class PdfRendererHelper {

    private static final String TAG = "PdfRendererHelper";

    private PdfRendererHelper() {}

    /**
     * Renders a single PDF page onto an ARGB_8888 bitmap with a guaranteed opaque white background.
     *
     * @param page The open {@link PdfRenderer.Page} to render.
     * @param width Target bitmap width in pixels.
     * @param height Target bitmap height in pixels.
     * @param renderMode {@link PdfRenderer.Page#RENDER_MODE_FOR_DISPLAY} or {@link PdfRenderer.Page#RENDER_MODE_FOR_PRINT}.
     * @return A newly allocated {@link Bitmap} containing the rendered page over opaque white.
     */
    @NonNull
    public static Bitmap renderPageWithWhiteBackground(
            @NonNull PdfRenderer.Page page,
            int width,
            int height,
            int renderMode) {

        int targetWidth = Math.max(1, width);
        int targetHeight = Math.max(1, height);

        Bitmap bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);

        page.render(bitmap, null, null, renderMode);
        return bitmap;
    }

    /**
     * Extracts all pages of a PDF document into temporary JPEG image files with guaranteed opaque
     * white backgrounds.
     *
     * @param context Application or activity context.
     * @param pdfUri URI to the source PDF document.
     * @return List of absolute file paths to the generated temporary page image files.
     */
    @NonNull
    public static ArrayList<String> extractAllPagesToTempFiles(
            @NonNull Context context,
            @NonNull Uri pdfUri) {

        ArrayList<String> pagePaths = new ArrayList<>();
        ParcelFileDescriptor pfd = null;
        File tempFile = null;

        try {
            if ("file".equalsIgnoreCase(pdfUri.getScheme()) && pdfUri.getPath() != null) {
                File file = new File(pdfUri.getPath());
                if (file.exists() && file.canRead()) {
                    try {
                        pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
                    } catch (Exception ignored) {}
                }
            }

            if (pfd == null) {
                // Ensure a seekable file on disk for PdfRenderer across all Android versions & storage schemes
                tempFile = new File(context.getCacheDir(), "extract_temp_" + UUID.randomUUID() + ".pdf");
                try (InputStream in = context.getContentResolver().openInputStream(pdfUri);
                     FileOutputStream out = new FileOutputStream(tempFile)) {
                    if (in != null) {
                        byte[] buffer = new byte[16384];
                        int bytesRead;
                        while ((bytesRead = in.read(buffer)) > 0) {
                            out.write(buffer, 0, bytesRead);
                        }
                        out.flush();
                        pfd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY);
                    }
                }
            }

            if (pfd != null) {
                try (PdfRenderer renderer = new PdfRenderer(pfd)) {
                    int count = renderer.getPageCount();
                    for (int i = 0; i < count; i++) {
                        PdfRenderer.Page page = renderer.openPage(i);
                        try {
                            int w = Math.min(1600, Math.max(page.getWidth() * 2, 720));
                            int h = Math.round((float) w * page.getHeight() / Math.max(1, page.getWidth()));

                            Bitmap bmp = null;
                            try {
                                bmp = renderPageWithWhiteBackground(page, w, h, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                            } catch (OutOfMemoryError oom) {
                                try {
                                    bmp = renderPageWithWhiteBackground(page, Math.max(1, w / 2), Math.max(1, h / 2), PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                                } catch (Throwable ignored) {}
                            }

                            if (bmp != null) {
                                String path = CacheManager.saveTempBitmap(context, bmp, "page_" + i + "_" + UUID.randomUUID());
                                bmp.recycle();

                                if (path != null) {
                                    pagePaths.add(path);
                                }
                            }
                        } finally {
                            page.close();
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error extracting all pages from PDF: " + pdfUri, e);
        } finally {
            if (pfd != null) {
                try {
                    pfd.close();
                } catch (Exception ignored) {}
            }
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete();
            }
        }

        return pagePaths;
    }
}
