package com.anscanner.app.ui.crop;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.anscanner.app.service.CacheManager;
import com.anscanner.app.ui.editor.UnifiedEditorActivity;

import java.io.File;
import java.util.ArrayList;
import java.util.UUID;

/**
 * Backwards-compatible subclass of {@link UnifiedEditorActivity} retaining legacy intent
 * contracts and static utility methods.
 */
public class CropPreviewActivity extends UnifiedEditorActivity {
    private static final String TAG = "CropPreviewActivity";

    public static Point[] toAndroidPoints(org.opencv.core.Point[] opencv) {
        if (opencv == null) return null;
        Point[] pts = new Point[opencv.length];
        for (int i = 0; i < opencv.length; i++) {
            pts[i] = new Point((int) Math.round(opencv[i].x), (int) Math.round(opencv[i].y));
        }
        return pts;
    }

    public static org.opencv.core.Point[] toOpenCvPoints(Point[] android) {
        if (android == null) return null;
        org.opencv.core.Point[] pts = new org.opencv.core.Point[android.length];
        for (int i = 0; i < android.length; i++) {
            pts[i] = new org.opencv.core.Point(android[i].x, android[i].y);
        }
        return pts;
    }

    public static ArrayList<String> extractAllPagesFromPdf(Context context, Uri pdfUri) {
        ArrayList<String> paths = new ArrayList<>();
        ParcelFileDescriptor pfd = null;
        try {
            if ("file".equalsIgnoreCase(pdfUri.getScheme()) && pdfUri.getPath() != null) {
                pfd = ParcelFileDescriptor.open(new File(pdfUri.getPath()), ParcelFileDescriptor.MODE_READ_ONLY);
            } else {
                pfd = context.getContentResolver().openFileDescriptor(pdfUri, "r");
            }
            if (pfd != null) {
                PdfRenderer renderer = new PdfRenderer(pfd);
                int count = renderer.getPageCount();
                for (int i = 0; i < count; i++) {
                    PdfRenderer.Page page = renderer.openPage(i);
                    int w = Math.min(1600, Math.max(page.getWidth() * 2, 720));
                    int h = Math.round((float) w * page.getHeight() / Math.max(1, page.getWidth()));
                    Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
                    page.close();
                    String path = CacheManager.saveTempBitmap(context, bmp, "page_" + i + "_" + UUID.randomUUID().toString());
                    bmp.recycle();
                    if (path != null) {
                        paths.add(path);
                    }
                }
                renderer.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error extracting all pages from PDF: " + pdfUri, e);
        } finally {
            if (pfd != null) {
                try {
                    pfd.close();
                } catch (Exception ignored) {}
            }
        }
        return paths;
    }
}