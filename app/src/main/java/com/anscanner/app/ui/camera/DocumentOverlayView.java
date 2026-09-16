package com.anscanner.app.ui.camera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.CornerPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.Region;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.anscanner.app.R;

/**
 * Custom overlay view drawn on top of the CameraX PreviewView.
 *
 * <p>Displays a live document boundary polygon detected by the
 * real-time ImageAnalysis pipeline. The 4 corner coordinates are
 * received in analysis-image space and mapped to screen space
 * before drawing.</p>
 *
 * <p>When no document is detected, a full-screen translucent scrim
 * is drawn. When corners are set, the detected region is cut out
 * of the scrim and highlighted with a mint-green border.</p>
 */
public class DocumentOverlayView extends View {

    private final Paint scrimPaint;
    private final Paint fillPaint;
    private final Paint borderPaint;
    private final Paint cornerPaint;
    private final Path cutoutPath;

    // Current smoothed corner positions (in screen coordinates)
    private PointF[] currentCorners = null;

    // Target corner positions (in screen coordinates)
    private PointF[] targetCorners = null;

    // Analysis image dimensions (for coordinate mapping)
    private int analysisWidth = 0;
    private int analysisHeight = 0;

    // Smoothing factor (0 = no smoothing, 1 = no update)
    private static final float SMOOTH_FACTOR = 0.5f;

    // Corner handle circle radius in dp
    private static final float CORNER_RADIUS_DP = 6f;
    private final float cornerRadiusPx;

    public DocumentOverlayView(Context context) {
        this(context, null);
    }

    public DocumentOverlayView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public DocumentOverlayView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        float density = context.getResources().getDisplayMetrics().density;
        cornerRadiusPx = CORNER_RADIUS_DP * density;

        // Semi-transparent scrim covering the non-document area
        scrimPaint = new Paint();
        scrimPaint.setColor(0x60000000); // 38% black
        scrimPaint.setStyle(Paint.Style.FILL);

        // Semi-transparent green fill for the detected document area
        fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillPaint.setColor(0x2068D391); // ~12% mint green
        fillPaint.setStyle(Paint.Style.FILL);

        // Solid green border stroke
        borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        borderPaint.setColor(ContextCompat.getColor(context, R.color.accent_mint));
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(3f * density);
        borderPaint.setStrokeJoin(Paint.Join.ROUND);
        borderPaint.setStrokeCap(Paint.Cap.ROUND);
        borderPaint.setPathEffect(new CornerPathEffect(8f * density));

        // Green circles at each corner
        cornerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        cornerPaint.setColor(ContextCompat.getColor(context, R.color.accent_mint));
        cornerPaint.setStyle(Paint.Style.FILL);

        cutoutPath = new Path();
    }

    /**
     * Sets the analysis image dimensions used for coordinate mapping.
     * Must be called before setDetectedCorners().
     *
     * @param width  Analysis image width in pixels.
     * @param height Analysis image height in pixels.
     */
    public void setAnalysisDimensions(int width, int height) {
        this.analysisWidth = width;
        this.analysisHeight = height;
    }

    /**
     * Sets the detected document corners from the OpenCV analysis pipeline.
     * Coordinates are in analysis-image space and will be mapped to screen
     * coordinates during onDraw().
     *
     * @param corners 4 corner points [TL, TR, BR, BL] in analysis coords,
     *                or null to clear the overlay.
     */
    public void setDetectedCorners(@Nullable org.opencv.core.Point[] corners) {
        if (corners == null || corners.length != 4) {
            targetCorners = null;
            currentCorners = null;
            postInvalidate();
            return;
        }

        int viewW = getWidth();
        int viewH = getHeight();
        if (viewW == 0 || viewH == 0 || analysisWidth == 0 || analysisHeight == 0) {
            postInvalidate();
            return;
        }

        float scaleX = (float) viewW / analysisWidth;
        float scaleY = (float) viewH / analysisHeight;

        targetCorners = new PointF[4];
        for (int i = 0; i < 4; i++) {
            targetCorners[i] = new PointF(
                    (float) corners[i].x * scaleX,
                    (float) corners[i].y * scaleY
            );
        }

        // Initialize currentCorners on first detection
        if (currentCorners == null) {
            currentCorners = new PointF[4];
            for (int i = 0; i < 4; i++) {
                currentCorners[i] = new PointF(targetCorners[i].x, targetCorners[i].y);
            }
        }

        postInvalidate();
    }

    /**
     * Legacy method for setting corners directly in screen coordinates.
     * Used by CropOverlayView-style direct coordinate setting.
     */
    public void setCorners(android.graphics.Point[] corners) {
        if (corners == null || corners.length != 4) {
            currentCorners = null;
            targetCorners = null;
            postInvalidate();
            return;
        }

        currentCorners = new PointF[4];
        targetCorners = new PointF[4];
        for (int i = 0; i < 4; i++) {
            currentCorners[i] = new PointF(corners[i].x, corners[i].y);
            targetCorners[i] = new PointF(corners[i].x, corners[i].y);
        }
        postInvalidate();
    }

    /**
     * Returns the current corner positions as OpenCV Points,
     * mapped back to the full-resolution captured image coordinates.
     * Returns null if no corners are currently detected.
     *
     * @param capturedWidth  Width of the captured full-res image.
     * @param capturedHeight Height of the captured full-res image.
     */
    public org.opencv.core.Point[] getCornersForCapture(int capturedWidth, int capturedHeight) {
        if (currentCorners == null) return null;

        int viewW = getWidth();
        int viewH = getHeight();
        if (viewW == 0 || viewH == 0) return null;

        float scaleX = (float) capturedWidth / viewW;
        float scaleY = (float) capturedHeight / viewH;

        org.opencv.core.Point[] pts = new org.opencv.core.Point[4];
        for (int i = 0; i < 4; i++) {
            pts[i] = new org.opencv.core.Point(
                    currentCorners[i].x * scaleX,
                    currentCorners[i].y * scaleY
            );
        }
        return pts;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (currentCorners != null && targetCorners != null) {
            // Smooth interpolation toward target
            for (int i = 0; i < 4; i++) {
                currentCorners[i].x += (targetCorners[i].x - currentCorners[i].x) * SMOOTH_FACTOR;
                currentCorners[i].y += (targetCorners[i].y - currentCorners[i].y) * SMOOTH_FACTOR;
            }

            // Build the polygon path from smoothed corners
            cutoutPath.reset();
            cutoutPath.moveTo(currentCorners[0].x, currentCorners[0].y);
            cutoutPath.lineTo(currentCorners[1].x, currentCorners[1].y);
            cutoutPath.lineTo(currentCorners[2].x, currentCorners[2].y);
            cutoutPath.lineTo(currentCorners[3].x, currentCorners[3].y);
            cutoutPath.close();

            // Draw scrim with cutout
            canvas.save();
            canvas.clipPath(cutoutPath, Region.Op.DIFFERENCE);
            canvas.drawRect(0, 0, getWidth(), getHeight(), scrimPaint);
            canvas.restore();

            // Draw semi-transparent fill inside the document region
            canvas.drawPath(cutoutPath, fillPaint);

            // Draw the solid green border
            canvas.drawPath(cutoutPath, borderPaint);

            // Draw corner handles
            for (PointF corner : currentCorners) {
                canvas.drawCircle(corner.x, corner.y, cornerRadiusPx, cornerPaint);
            }

            // Keep animating if not yet converged
            boolean needsUpdate = false;
            for (int i = 0; i < 4; i++) {
                float dx = Math.abs(targetCorners[i].x - currentCorners[i].x);
                float dy = Math.abs(targetCorners[i].y - currentCorners[i].y);
                if (dx > 0.5f || dy > 0.5f) {
                    needsUpdate = true;
                    break;
                }
            }
            if (needsUpdate) {
                postInvalidateOnAnimation();
            }
        } else {
            // No document detected — draw a light scrim
            canvas.drawRect(0, 0, getWidth(), getHeight(), scrimPaint);
        }
    }
}
