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
        return com.anscanner.app.util.PdfRendererHelper.extractAllPagesToTempFiles(context, pdfUri);
    }
}