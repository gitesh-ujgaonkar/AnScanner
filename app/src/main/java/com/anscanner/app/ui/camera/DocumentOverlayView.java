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

    public static final int MODE_DOCUMENT = 0;
    public static final int MODE_BATCH = 1;
    public static final int MODE_ID_CARD = 2;

    public static final int ID_STEP_FRONT = 1;
    public static final int ID_STEP_BACK = 2;

    private int scanMode = MODE_DOCUMENT;
    private int idCardStep = ID_STEP_FRONT;

    private final Paint scrimPaint;
    private final Paint fillPaint;
    private final Paint borderPaint;
    private final Paint cornerPaint;
    private final Path cutoutPath;

    // ID Card guide elements
    private final Paint idGuideBorderPaint;
    private final Paint idGuideBracketPaint;
    private final Paint idBadgeBgPaint;
    private final Paint idBadgeTextPaint;
    private final Path idCardPath;
    private final android.graphics.RectF idCardRect = new android.graphics.RectF();

    // Current smoothed corner positions (in screen coordinates)
    private PointF[] currentCorners = null;

    // Target corner positions (in screen coordinates)
    private PointF[] targetCorners = null;

    // Tap-to-focus animation and state
    private PointF focusPoint = null;
    private float focusRingRadius = 0f;
    private int focusRingAlpha = 0;
    private android.animation.ValueAnimator focusAnimator = null;
    private final Paint focusRingPaint;

    // Analysis image dimensions (for coordinate mapping)
    private int analysisWidth = 0;
    private int analysisHeight = 0;

    // Smoothing factor (0 = no smoothing, 1 = no update)
    private static final float SMOOTH_FACTOR = 0.5f;

    // Corner handle circle radius in dp
    private static final float CORNER_RADIUS_DP = 6f;
    private final float cornerRadiusPx;
    private final float density;

    public DocumentOverlayView(Context context) {
        this(context, null);
    }

    public DocumentOverlayView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public DocumentOverlayView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        density = context.getResources().getDisplayMetrics().density;
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

        // ID Card mode styling
        idGuideBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        idGuideBorderPaint.setColor(ContextCompat.getColor(context, R.color.accent_mint));
        idGuideBorderPaint.setStyle(Paint.Style.STROKE);
        idGuideBorderPaint.setStrokeWidth(2f * density);

        idGuideBracketPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        idGuideBracketPaint.setColor(ContextCompat.getColor(context, R.color.accent_mint));
        idGuideBracketPaint.setStyle(Paint.Style.STROKE);
        idGuideBracketPaint.setStrokeWidth(4.5f * density);
        idGuideBracketPaint.setStrokeCap(Paint.Cap.ROUND);
        idGuideBracketPaint.setStrokeJoin(Paint.Join.ROUND);

        idBadgeBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        idBadgeBgPaint.setColor(0xCC1A202C); // Semi-transparent dark slate
        idBadgeBgPaint.setStyle(Paint.Style.FILL);

        idBadgeTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        idBadgeTextPaint.setColor(Color.WHITE);
        idBadgeTextPaint.setTextSize(13f * density);
        idBadgeTextPaint.setTextAlign(Paint.Align.CENTER);
        idBadgeTextPaint.setFakeBoldText(true);

        idCardPath = new Path();

        // Focus indicator styling
        focusRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        focusRingPaint.setColor(ContextCompat.getColor(context, R.color.accent_mint));
        focusRingPaint.setStyle(Paint.Style.STROKE);
        focusRingPaint.setStrokeWidth(2.5f * density);
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

    public int getAnalysisWidth() {
        return analysisWidth;
    }

    public int getAnalysisHeight() {
        return analysisHeight;
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

        // CameraX PreviewView with ScaleType.FILL_CENTER scales uniformly with center-crop
        float scale = Math.max((float) viewW / analysisWidth, (float) viewH / analysisHeight);
        float scaledW = analysisWidth * scale;
        float scaledH = analysisHeight * scale;
        float offsetX = (viewW - scaledW) / 2f;
        float offsetY = (viewH - scaledH) / 2f;

        targetCorners = new PointF[4];
        for (int i = 0; i < 4; i++) {
            targetCorners[i] = new PointF(
                    (float) corners[i].x * scale + offsetX,
                    (float) corners[i].y * scale + offsetY
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
        if (viewW == 0 || viewH == 0 || analysisWidth == 0 || analysisHeight == 0) return null;

        float scale = Math.max((float) viewW / analysisWidth, (float) viewH / analysisHeight);
        float scaledW = analysisWidth * scale;
        float scaledH = analysisHeight * scale;
        float offsetX = (viewW - scaledW) / 2f;
        float offsetY = (viewH - scaledH) / 2f;

        float captureScaleX = (float) capturedWidth / analysisWidth;
        float captureScaleY = (float) capturedHeight / analysisHeight;

        org.opencv.core.Point[] pts = new org.opencv.core.Point[4];
        for (int i = 0; i < 4; i++) {
            float analysisX = (currentCorners[i].x - offsetX) / scale;
            float analysisY = (currentCorners[i].y - offsetY) / scale;
            pts[i] = new org.opencv.core.Point(
                    Math.max(0, Math.min(capturedWidth, analysisX * captureScaleX)),
                    Math.max(0, Math.min(capturedHeight, analysisY * captureScaleY))
            );
        }
        return pts;
    }

    public void setScanMode(int mode) {
        this.scanMode = mode;
        postInvalidate();
    }

    public int getScanMode() {
        return scanMode;
    }

    public void setIdCardStep(int step) {
        this.idCardStep = step;
        postInvalidate();
    }

    public int getIdCardStep() {
        return idCardStep;
    }

    /**
     * Returns the ID Card frame mapped to captured image coordinates.
     */
    public android.graphics.Rect getIdCardCropRect(int capturedWidth, int capturedHeight) {
        int viewW = getWidth();
        int viewH = getHeight();
        if (viewW == 0 || viewH == 0 || idCardRect.isEmpty()) {
            return new android.graphics.Rect(0, 0, capturedWidth, capturedHeight);
        }

        float scaleX = (float) capturedWidth / viewW;
        float scaleY = (float) capturedHeight / viewH;

        int left = Math.max(0, Math.round(idCardRect.left * scaleX));
        int top = Math.max(0, Math.round(idCardRect.top * scaleY));
        int right = Math.min(capturedWidth, Math.round(idCardRect.right * scaleX));
        int bottom = Math.min(capturedHeight, Math.round(idCardRect.bottom * scaleY));

        return new android.graphics.Rect(left, top, right, bottom);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

                if (scanMode == MODE_ID_CARD) {
            drawIdCardGuide(canvas);
        } else if (currentCorners != null && targetCorners != null) {
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
        // Draw Tap-to-Focus visual ring indicator
        if (focusPoint != null && focusRingAlpha > 0) {
            focusRingPaint.setAlpha(focusRingAlpha);
            canvas.drawCircle(focusPoint.x, focusPoint.y, focusRingRadius, focusRingPaint);
            canvas.drawCircle(focusPoint.x, focusPoint.y, 2.5f * density, focusRingPaint);
        }
    }

    private void drawIdCardGuide(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        // Standard ID card aspect ratio: 85.6mm / 53.98mm ≈ 1.585
        float cardW = w * 0.86f;
        float cardH = cardW / 1.585f;
        if (cardH > h * 0.65f) {
            cardH = h * 0.65f;
            cardW = cardH * 1.585f;
        }

        float left = (w - cardW) / 2f;
        float top = (h - cardH) / 2f - (20f * density);
        float right = left + cardW;
        float bottom = top + cardH;
        idCardRect.set(left, top, right, bottom);

        float cardRadius = 14f * density;
        idCardPath.reset();
        idCardPath.addRoundRect(idCardRect, cardRadius, cardRadius, Path.Direction.CW);

        // 1. Draw dark scrim outside the card window
        canvas.save();
        canvas.clipPath(idCardPath, Region.Op.DIFFERENCE);
        canvas.drawRect(0, 0, w, h, scrimPaint);
        canvas.restore();

        // 2. Draw card outline
        canvas.drawPath(idCardPath, idGuideBorderPaint);

        // 3. Draw 4 corner guide brackets
        float bracketLen = 24f * density;
        // Top-Left
        canvas.drawLine(left, top + bracketLen, left, top + cardRadius, idGuideBracketPaint);
        canvas.drawLine(left + cardRadius, top, left + bracketLen, top, idGuideBracketPaint);
        // Top-Right
        canvas.drawLine(right - bracketLen, top, right - cardRadius, top, idGuideBracketPaint);
        canvas.drawLine(right, top + cardRadius, right, top + bracketLen, idGuideBracketPaint);
        // Bottom-Right
        canvas.drawLine(right, bottom - bracketLen, right, bottom - cardRadius, idGuideBracketPaint);
        canvas.drawLine(right - cardRadius, bottom, right - bracketLen, bottom, idGuideBracketPaint);
        // Bottom-Left
        canvas.drawLine(left + bracketLen, bottom, left + cardRadius, bottom, idGuideBracketPaint);
        canvas.drawLine(left, bottom - cardRadius, left, bottom - bracketLen, idGuideBracketPaint);


    }
    public void showFocusIndicator(float x, float y) {
        focusPoint = new PointF(x, y);
        if (focusAnimator != null && focusAnimator.isRunning()) {
            focusAnimator.cancel();
        }
        final float startRadius = 45f * density;
        final float endRadius = 30f * density;
        focusAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f);
        focusAnimator.setDuration(800);
        focusAnimator.setInterpolator(new android.view.animation.DecelerateInterpolator());
        focusAnimator.addUpdateListener(animation -> {
            float frac = (float) animation.getAnimatedValue();
            focusRingRadius = startRadius - (startRadius - endRadius) * frac;
            if (frac < 0.6f) {
                focusRingAlpha = 255;
            } else {
                focusRingAlpha = Math.round(255 * (1f - (frac - 0.6f) / 0.4f));
            }
            postInvalidateOnAnimation();
        });
        focusAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                focusPoint = null;
                focusRingAlpha = 0;
                postInvalidateOnAnimation();
            }
        });
        focusAnimator.start();
    }

    public PointF getDetectedCenterPoint() {
        if (scanMode == MODE_ID_CARD && !idCardRect.isEmpty()) {
            return new PointF(idCardRect.centerX(), idCardRect.centerY());
        }
        if (currentCorners != null && currentCorners.length == 4) {
            float sumX = 0f;
            float sumY = 0f;
            for (PointF p : currentCorners) {
                sumX += p.x;
                sumY += p.y;
            }
            return new PointF(sumX / 4f, sumY / 4f);
        }
        int w = getWidth();
        int h = getHeight();
        if (w > 0 && h > 0) {
            return new PointF(w / 2f, h / 2f);
        }
        return null;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
