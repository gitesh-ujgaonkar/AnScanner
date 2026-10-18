package com.anscanner.app.ui.crop;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.anscanner.app.R;
import com.anscanner.app.processing.DocumentDetector;

/**
 * Interactive document crop overlay providing standard mobile document scanner behavior:
 * 1. Normalized coordinate system [0.0..1.0] decoupled from screen/view dimensions and letterboxing.
 * 2. 4 corner handles + 4 midpoint handles on all 4 edges for intuitive dragging.
 * 3. Minimum 44x44 dp touch targets to prevent fingers from obscuring handles.
 * 4. Precision circular magnifying loupe displayed above the active corner handle while dragging.
 */
public class CropOverlayView extends View {

    private final Paint polygonPaint;
    private final Paint linePaint;
    private final Paint handleFillPaint;
    private final Paint handleStrokePaint;
    private final Paint handleDotPaint;
    private final Paint midHandleFillPaint;
    private final Paint midHandleStrokePaint;
    private final Path path;

    // Magnifier / Loupe fields
    private final Paint loupeBorderPaint;
    private final Paint loupeShadowPaint;
    private final Paint loupeCrosshairPaint;
    private final Paint loupeCrosshairShadowPaint;
    private final Paint loupeBitmapPaint;
    private final Path loupeClipPath;
    private final float loupeRadius;
    private final float zoomFactor = 2.0f;

    // 4 Corner coordinates normalized to [0.0..1.0] relative to underlying image: [TL, TR, BR, BL]
    private final PointF[] normalizedCorners = new PointF[4];
    // 4 Corner coordinates in underlying bitmap pixel space: [TL, TR, BR, BL]
    private final PointF[] bitmapCorners = new PointF[4];
    // Display coordinates in this overlay's pixel space: [TL, TR, BR, BL]
    private final PointF[] viewCorners = new PointF[4];
    // Edge midpoint display coordinates: [Top (0-1), Right (1-2), Bottom (2-3), Left (3-0)]
    private final PointF[] midPoints = new PointF[4];

    // Explicit projection matrices mapping bitmap pixels to overlay viewport pixels
    private final Matrix displayMatrix = new Matrix();
    private final Matrix invertMatrix = new Matrix();
    private final RectF visibleImageBounds = new RectF();

    private ImageView imageView;
    private boolean viewCornersCalculated = false;
    private Point[] pendingPixelCorners = null;

    public interface OnCornerDragListener {
        void onCornerDragStarted(int cornerIndex, float x, float y);
        void onCornerDragging(int cornerIndex, float x, float y);
        void onCornerDragEnded();
        default void onNormalizedCornersUpdated(float[] flatNormalizedCorners) {}
    }

    public interface OnPageSwipeListener {
        void onSwipeNext();
        void onSwipePrevious();
    }

    private OnCornerDragListener cornerDragListener;
    private OnPageSwipeListener pageSwipeListener;
    private GestureDetector gestureDetector;
    private boolean isCornerMoved = false;

    // Active drag index: 0..3 for corner handles, -1 if none
    private int draggingCornerIndex = -1;
    // Active drag edge: 0..3 for midpoint handles (Top=0, Right=1, Bottom=2, Left=3), -1 if none
    private int draggingEdgeIndex = -1;
    private float lastTouchX = 0f;
    private float lastTouchY = 0f;

    private final float touchRadius;
    private final float handleRadius;
    private final float midHandleRadius;

    public CropOverlayView(Context context) {
        this(context, null);
    }

    public CropOverlayView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CropOverlayView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        gestureDetector = new GestureDetector(context, new SwipeGestureListener());

        polygonPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        polygonPaint.setColor(ContextCompat.getColor(context, R.color.crop_overlay));
        polygonPaint.setStyle(Paint.Style.FILL);

        linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        linePaint.setColor(ContextCompat.getColor(context, R.color.accent_mint));
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(context.getResources().getDisplayMetrics().density * 2f);

        handleFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        handleFillPaint.setColor(Color.WHITE);
        handleFillPaint.setStyle(Paint.Style.FILL);

        handleStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        handleStrokePaint.setColor(ContextCompat.getColor(context, R.color.accent_mint));
        handleStrokePaint.setStyle(Paint.Style.STROKE);
        handleStrokePaint.setStrokeWidth(context.getResources().getDisplayMetrics().density * 2.5f);

        handleDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        handleDotPaint.setColor(ContextCompat.getColor(context, R.color.accent_mint));
        handleDotPaint.setStyle(Paint.Style.FILL);

        midHandleFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        midHandleFillPaint.setColor(Color.WHITE);
        midHandleFillPaint.setStyle(Paint.Style.FILL);

        midHandleStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        midHandleStrokePaint.setColor(ContextCompat.getColor(context, R.color.accent_mint));
        midHandleStrokePaint.setStyle(Paint.Style.STROKE);
        midHandleStrokePaint.setStrokeWidth(context.getResources().getDisplayMetrics().density * 2f);

        path = new Path();

        float density = context.getResources().getDisplayMetrics().density;
        // Strict requirement: touch target radius minimum 44x44 dp so fingers don't obscure the point
        touchRadius = Math.max(density * 44f, 44f);
        handleRadius = density * 12f;
        midHandleRadius = density * 7f;

        // Initialize Magnifier / Loupe
        loupeRadius = density * 60f;

        loupeBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        loupeBorderPaint.setStyle(Paint.Style.STROKE);
        loupeBorderPaint.setStrokeWidth(density * 2.5f);
        loupeBorderPaint.setColor(ContextCompat.getColor(context, R.color.accent_mint));

        loupeShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        loupeShadowPaint.setColor(0x40000000);
        loupeShadowPaint.setStyle(Paint.Style.FILL);

        loupeCrosshairPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        loupeCrosshairPaint.setStyle(Paint.Style.STROKE);
        loupeCrosshairPaint.setStrokeWidth(density * 2f);
        loupeCrosshairPaint.setColor(ContextCompat.getColor(context, R.color.accent_mint));

        loupeCrosshairShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        loupeCrosshairShadowPaint.setStyle(Paint.Style.STROKE);
        loupeCrosshairShadowPaint.setStrokeWidth(density * 3.5f);
        loupeCrosshairShadowPaint.setColor(0xCC000000);

        loupeBitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        loupeClipPath = new Path();

        // Initialize normalized corners to safe 5% inset fallback: [TL, TR, BR, BL]
        PointF[] defaultNorm = DocumentDetector.getDefaultNormalizedCorners();
        for (int i = 0; i < 4; i++) {
            normalizedCorners[i] = new PointF(defaultNorm[i].x, defaultNorm[i].y);
            bitmapCorners[i] = new PointF();
            viewCorners[i] = new PointF();
            midPoints[i] = new PointF();
        }
    }

    @Nullable
    private Bitmap getBitmap() {
        if (imageView != null && imageView.getDrawable() instanceof BitmapDrawable) {
            return ((BitmapDrawable) imageView.getDrawable()).getBitmap();
        }
        return null;
    }

    /**
     * Accurately determines the rendered bounds of the image inside this overlay view,
     * accounting for fitCenter scaling and letterboxing/pillarboxing.
     */
    @Nullable
    public RectF getImageBoundsInOverlay() {
        if (imageView == null || imageView.getDrawable() == null) {
            return null;
        }

        int drawableW = imageView.getDrawable().getIntrinsicWidth();
        int drawableH = imageView.getDrawable().getIntrinsicHeight();
        if (drawableW <= 0 || drawableH <= 0) {
            return null;
        }

        // Location of ImageView relative to this CropOverlayView
        int[] imgLoc = new int[2];
        int[] overlayLoc = new int[2];
        imageView.getLocationOnScreen(imgLoc);
        getLocationOnScreen(overlayLoc);
        float offsetX = imgLoc[0] - overlayLoc[0];
        float offsetY = imgLoc[1] - overlayLoc[1];

        if (imageView instanceof com.anscanner.app.ui.custom.TouchImageView && !((com.anscanner.app.ui.custom.TouchImageView) imageView).isZoomable()) {
            RectF displayRect = ((com.anscanner.app.ui.custom.TouchImageView) imageView).getDisplayRect();
            if (displayRect != null && displayRect.width() > 0 && displayRect.height() > 0) {
                RectF rect = new RectF(displayRect);
                rect.offset(offsetX, offsetY);
                return rect;
            }
        } else if (imageView instanceof com.github.chrisbanes.photoview.PhotoView) {
            RectF displayRect = ((com.github.chrisbanes.photoview.PhotoView) imageView).getDisplayRect();
            if (displayRect != null && displayRect.width() > 0 && displayRect.height() > 0) {
                float maxAllowedW = Math.max(getWidth(), imageView.getWidth()) * 1.5f;
                float maxAllowedH = Math.max(getHeight(), imageView.getHeight()) * 1.5f;
                if (maxAllowedW > 0 && maxAllowedH > 0 && (displayRect.width() <= maxAllowedW && displayRect.height() <= maxAllowedH)) {
                    RectF rect = new RectF(displayRect);
                    rect.offset(offsetX, offsetY);
                    return rect;
                }
            }
        }

        // Standard fitCenter calculation
        float viewW = imageView.getWidth() - imageView.getPaddingLeft() - imageView.getPaddingRight();
        float viewH = imageView.getHeight() - imageView.getPaddingTop() - imageView.getPaddingBottom();
        if (viewW <= 0 || viewH <= 0) {
            viewW = getWidth();
            viewH = getHeight();
        }
        if (viewW <= 0 || viewH <= 0) {
            return null;
        }

        float scale = Math.min(viewW / drawableW, viewH / drawableH);
        float renderedW = drawableW * scale;
        float renderedH = drawableH * scale;
        float left = offsetX + imageView.getPaddingLeft() + (viewW - renderedW) / 2f;
        float top = offsetY + imageView.getPaddingTop() + (viewH - renderedH) / 2f;

        return new RectF(left, top, left + renderedW, top + renderedH);
    }

    public void setImageView(ImageView iv) {
        this.imageView = iv;
        if (this.imageView != null) {
            this.imageView.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                updateViewCorners();
                invalidate();
            });
            if (this.imageView instanceof com.github.chrisbanes.photoview.PhotoView) {
                ((com.github.chrisbanes.photoview.PhotoView) this.imageView).setOnMatrixChangeListener(rect -> {
                    updateViewCorners();
                    invalidate();
                });
            }
            updateViewCorners();
            invalidate();
        }
    }

    public void setOnCornerDragListener(OnCornerDragListener listener) {
        this.cornerDragListener = listener;
    }

    public void setOnPageSwipeListener(OnPageSwipeListener listener) {
        this.pageSwipeListener = listener;
    }

    public boolean isCornerMoved() {
        return isCornerMoved;
    }

    public void resetCornerMoved() {
        this.isCornerMoved = false;
    }

    public Point[] getCorners() {
        return getCornerPoints();
    }

    /**
     * Sets corner points in image-pixel coordinates and normalizes them.
     */
    public void setCorners(Point[] corners) {
        if (corners != null && corners.length == 4) {
            Bitmap bmp = getBitmap();
            float baseW = (bmp != null) ? bmp.getWidth() : (imageView != null && imageView.getDrawable() != null ? imageView.getDrawable().getIntrinsicWidth() : 0);
            float baseH = (bmp != null) ? bmp.getHeight() : (imageView != null && imageView.getDrawable() != null ? imageView.getDrawable().getIntrinsicHeight() : 0);

            if (baseW > 0 && baseH > 0) {
                pendingPixelCorners = null;
                for (int i = 0; i < 4; i++) {
                    float bx = Math.max(0f, Math.min(baseW, (float) corners[i].x));
                    float by = Math.max(0f, Math.min(baseH, (float) corners[i].y));
                    bitmapCorners[i].set(bx, by);
                    normalizedCorners[i].set(bx / baseW, by / baseH);
                }
            } else {
                pendingPixelCorners = corners;
            }
            updateViewCorners();
            invalidate();
        }
    }

    /**
     * Sets normalized corner coordinates [0.0..1.0].
     */
    public void setNormalizedCorners(@Nullable PointF[] corners) {
        if (corners != null && corners.length == 4) {
            pendingPixelCorners = null;
            PointF[] ordered = DocumentDetector.orderNormalizedCorners(corners);
            Bitmap bmp = getBitmap();
            float baseW = (bmp != null) ? bmp.getWidth() : (imageView != null && imageView.getDrawable() != null ? imageView.getDrawable().getIntrinsicWidth() : 0);
            float baseH = (bmp != null) ? bmp.getHeight() : (imageView != null && imageView.getDrawable() != null ? imageView.getDrawable().getIntrinsicHeight() : 0);

            for (int i = 0; i < 4; i++) {
                normalizedCorners[i].set(
                        Math.max(0f, Math.min(1f, ordered[i].x)),
                        Math.max(0f, Math.min(1f, ordered[i].y))
                );
                if (baseW > 0 && baseH > 0) {
                    bitmapCorners[i].set(normalizedCorners[i].x * baseW, normalizedCorners[i].y * baseH);
                }
            }
            updateViewCorners();
            invalidate();
        }
    }

    /**
     * Sets normalized corners from a flat float array [x0, y0, x1, y1, x2, y2, x3, y3].
     */
    public void setNormalizedCornersFlat(@Nullable float[] flat) {
        if (flat != null && flat.length == 8) {
            pendingPixelCorners = null;
            PointF[] pts = new PointF[4];
            for (int i = 0; i < 4; i++) {
                pts[i] = new PointF(
                        Math.max(0f, Math.min(1f, flat[i * 2])),
                        Math.max(0f, Math.min(1f, flat[i * 2 + 1]))
                );
            }
            setNormalizedCorners(pts);
        }
    }

    @NonNull
    public PointF[] getNormalizedCorners() {
        PointF[] copy = new PointF[4];
        for (int i = 0; i < 4; i++) {
            copy[i] = new PointF(normalizedCorners[i].x, normalizedCorners[i].y);
        }
        return copy;
    }

    @NonNull
    public float[] getNormalizedCornersFlat() {
        float[] flat = new float[8];
        for (int i = 0; i < 4; i++) {
            flat[i * 2] = normalizedCorners[i].x;
            flat[i * 2 + 1] = normalizedCorners[i].y;
        }
        return flat;
    }

    @Nullable
    public Point[] getCornerPoints() {
        Bitmap bmp = getBitmap();
        float baseW = (bmp != null) ? bmp.getWidth() : (imageView != null && imageView.getDrawable() != null ? imageView.getDrawable().getIntrinsicWidth() : 0);
        float baseH = (bmp != null) ? bmp.getHeight() : (imageView != null && imageView.getDrawable() != null ? imageView.getDrawable().getIntrinsicHeight() : 0);
        if (baseW <= 0 || baseH <= 0) return null;

        Point[] result = new Point[4];
        for (int i = 0; i < 4; i++) {
            result[i] = new Point(
                    Math.round(bitmapCorners[i].x),
                    Math.round(bitmapCorners[i].y)
            );
        }
        return result;
    }

    public boolean updateDisplayMatrix() {
        RectF bounds = getImageBoundsInOverlay();
        if (bounds == null || bounds.width() <= 0 || bounds.height() <= 0) {
            return false;
        }

        Bitmap bmp = getBitmap();
        float baseW = (bmp != null) ? bmp.getWidth() : (imageView != null && imageView.getDrawable() != null ? imageView.getDrawable().getIntrinsicWidth() : 0);
        float baseH = (bmp != null) ? bmp.getHeight() : (imageView != null && imageView.getDrawable() != null ? imageView.getDrawable().getIntrinsicHeight() : 0);
        if (baseW <= 0 || baseH <= 0) {
            return false;
        }

        visibleImageBounds.set(bounds);
        RectF srcRect = new RectF(0, 0, baseW, baseH);
        displayMatrix.reset();
        displayMatrix.setRectToRect(srcRect, visibleImageBounds, Matrix.ScaleToFit.CENTER);
        displayMatrix.invert(invertMatrix);
        return true;
    }

    private void updateViewCorners() {
        if (!updateDisplayMatrix()) {
            return;
        }

        Bitmap bmp = getBitmap();
        float baseW = (bmp != null) ? bmp.getWidth() : (imageView != null && imageView.getDrawable() != null ? imageView.getDrawable().getIntrinsicWidth() : 0);
        float baseH = (bmp != null) ? bmp.getHeight() : (imageView != null && imageView.getDrawable() != null ? imageView.getDrawable().getIntrinsicHeight() : 0);
        if (baseW <= 0 || baseH <= 0) return;

        if (pendingPixelCorners != null) {
            for (int i = 0; i < 4; i++) {
                float bx = Math.max(0f, Math.min(baseW, (float) pendingPixelCorners[i].x));
                float by = Math.max(0f, Math.min(baseH, (float) pendingPixelCorners[i].y));
                bitmapCorners[i].set(bx, by);
                normalizedCorners[i].set(bx / baseW, by / baseH);
            }
            pendingPixelCorners = null;
        } else {
            // Keep bitmapCorners in sync with normalizedCorners
            for (int i = 0; i < 4; i++) {
                bitmapCorners[i].set(
                        normalizedCorners[i].x * baseW,
                        normalizedCorners[i].y * baseH
                );
            }
        }

        float[] srcPts = new float[8];
        float[] dstPts = new float[8];
        for (int i = 0; i < 4; i++) {
            srcPts[i * 2] = bitmapCorners[i].x;
            srcPts[i * 2 + 1] = bitmapCorners[i].y;
        }
        displayMatrix.mapPoints(dstPts, srcPts);
        for (int i = 0; i < 4; i++) {
            viewCorners[i].set(dstPts[i * 2], dstPts[i * 2 + 1]);
        }
        updateMidPoints();
        viewCornersCalculated = true;
    }

    private void updateMidPoints() {
        for (int i = 0; i < 4; i++) {
            int next = (i + 1) % 4;
            midPoints[i].set(
                    (viewCorners[i].x + viewCorners[next].x) / 2f,
                    (viewCorners[i].y + viewCorners[next].y) / 2f
            );
        }
    }

    private void updateNormalizedCornerFromView(int index, float vx, float vy) {
        if (visibleImageBounds.width() <= 0 || visibleImageBounds.height() <= 0) return;

        float[] screenPt = new float[]{vx, vy};
        float[] bmpPt = new float[2];
        invertMatrix.mapPoints(bmpPt, screenPt);

        Bitmap bmp = getBitmap();
        float baseW = (bmp != null) ? bmp.getWidth() : (imageView != null && imageView.getDrawable() != null ? imageView.getDrawable().getIntrinsicWidth() : 0);
        float baseH = (bmp != null) ? bmp.getHeight() : (imageView != null && imageView.getDrawable() != null ? imageView.getDrawable().getIntrinsicHeight() : 0);

        if (baseW > 0 && baseH > 0) {
            float bx = Math.max(0f, Math.min(baseW, bmpPt[0]));
            float by = Math.max(0f, Math.min(baseH, bmpPt[1]));
            bitmapCorners[index].set(bx, by);
            normalizedCorners[index].set(bx / baseW, by / baseH);
        } else {
            float nx = Math.max(0.0f, Math.min(1.0f, (vx - visibleImageBounds.left) / visibleImageBounds.width()));
            float ny = Math.max(0.0f, Math.min(1.0f, (vy - visibleImageBounds.top) / visibleImageBounds.height()));
            normalizedCorners[index].set(nx, ny);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateViewCorners();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (imageView == null) return;

        if (!viewCornersCalculated) {
            updateViewCorners();
        }

        // 1. Draw polygon background highlight & border
        path.reset();
        path.moveTo(viewCorners[0].x, viewCorners[0].y);
        path.lineTo(viewCorners[1].x, viewCorners[1].y);
        path.lineTo(viewCorners[2].x, viewCorners[2].y);
        path.lineTo(viewCorners[3].x, viewCorners[3].y);
        path.close();

        canvas.drawPath(path, polygonPaint);
        canvas.drawPath(path, linePaint);

        // 2. Draw 4 Midpoint Handles on the 4 edges
        for (int i = 0; i < 4; i++) {
            canvas.drawCircle(midPoints[i].x, midPoints[i].y, midHandleRadius, midHandleFillPaint);
            canvas.drawCircle(midPoints[i].x, midPoints[i].y, midHandleRadius, midHandleStrokePaint);
        }

        // 3. Draw 4 Corner Handles
        float density = getResources().getDisplayMetrics().density;
        for (int i = 0; i < 4; i++) {
            canvas.drawCircle(viewCorners[i].x, viewCorners[i].y, handleRadius, handleFillPaint);
            canvas.drawCircle(viewCorners[i].x, viewCorners[i].y, handleRadius, handleStrokePaint);
            canvas.drawCircle(viewCorners[i].x, viewCorners[i].y, density * 3f, handleDotPaint);
        }

        // 4. Draw Circular Magnifying Loupe above the active corner handle
        if (draggingCornerIndex >= 0 && draggingCornerIndex < 4) {
            drawCornerMagnifier(canvas);
        }
    }

    private void drawCornerMagnifier(Canvas canvas) {
        Bitmap bitmap = getBitmap();
        if (bitmap == null || bitmap.isRecycled()) {
            return;
        }

        RectF imageBounds = getImageBoundsInOverlay();
        if (imageBounds == null || imageBounds.width() <= 0 || imageBounds.height() <= 0) {
            return;
        }

        float density = getResources().getDisplayMetrics().density;
        float touchX = viewCorners[draggingCornerIndex].x;
        float touchY = viewCorners[draggingCornerIndex].y;

        // Position circular loupe directly above the active corner handle
        float loupeOffset = density * 75f;
        float cx = touchX;
        float cy = touchY - loupeOffset;

        // If too close to top edge, flip below finger
        if (cy - loupeRadius < density * 12f) {
            cy = touchY + loupeOffset;
        }

        // Clamp to screen bounds
        cx = Math.max(loupeRadius + density * 8f, Math.min(getWidth() - loupeRadius - density * 8f, cx));
        cy = Math.max(loupeRadius + density * 8f, Math.min(getHeight() - loupeRadius - density * 8f, cy));

        // 1. Draw outer drop shadow
        canvas.drawCircle(cx, cy + density * 3f, loupeRadius + density * 2f, loupeShadowPaint);

        // 2. Setup transform matrix: scale around touch point, translate to loupe center
        float bmpX = bitmapCorners[draggingCornerIndex].x;
        float bmpY = bitmapCorners[draggingCornerIndex].y;

        RectF bounds = visibleImageBounds.width() > 0 ? visibleImageBounds : imageBounds;
        float scale = zoomFactor * (bounds.width() / (float) bitmap.getWidth());
        Matrix loupeMatrix = new Matrix();
        loupeMatrix.postScale(scale, scale);
        loupeMatrix.postTranslate(cx - bmpX * scale, cy - bmpY * scale);

        // 3. Clip and draw bitmap inside circular loupe
        canvas.save();
        loupeClipPath.reset();
        loupeClipPath.addCircle(cx, cy, loupeRadius, Path.Direction.CW);
        canvas.clipPath(loupeClipPath);

        canvas.drawColor(Color.WHITE);
        canvas.drawBitmap(bitmap, loupeMatrix, loupeBitmapPaint);
        canvas.restore();

        // 4. Reticle / Crosshair centered at (cx, cy)
        float crosshairSize = density * 14f;
        canvas.drawLine(cx - crosshairSize, cy, cx + crosshairSize, cy, loupeCrosshairShadowPaint);
        canvas.drawLine(cx - crosshairSize, cy, cx + crosshairSize, cy, loupeCrosshairPaint);
        canvas.drawLine(cx, cy - crosshairSize, cx, cy + crosshairSize, loupeCrosshairShadowPaint);
        canvas.drawLine(cx, cy - crosshairSize, cx, cy + crosshairSize, loupeCrosshairPaint);
        canvas.drawCircle(cx, cy, density * 2.5f, loupeCrosshairPaint);

        // 5. Outer border ring
        canvas.drawCircle(cx, cy, loupeRadius, loupeBorderPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (imageView == null || imageView.getDrawable() == null) return false;

        float x = event.getX();
        float y = event.getY();

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN: {
                draggingCornerIndex = -1;
                draggingEdgeIndex = -1;

                // 1. Check Corner Handles first (strict minimum 44x44 dp touch radius)
                float minDistance = Float.MAX_VALUE;
                for (int i = 0; i < 4; i++) {
                    float dx = x - viewCorners[i].x;
                    float dy = y - viewCorners[i].y;
                    float dist = (float) Math.hypot(dx, dy);
                    if (dist < touchRadius && dist < minDistance) {
                        minDistance = dist;
                        draggingCornerIndex = i;
                    }
                }

                // 2. If no corner touched, check Midpoint Handles
                if (draggingCornerIndex == -1) {
                    float minEdgeDistance = Float.MAX_VALUE;
                    for (int i = 0; i < 4; i++) {
                        float dx = x - midPoints[i].x;
                        float dy = y - midPoints[i].y;
                        float dist = (float) Math.hypot(dx, dy);
                        if (dist < touchRadius && dist < minEdgeDistance) {
                            minEdgeDistance = dist;
                            draggingEdgeIndex = i;
                        }
                    }
                    if (draggingEdgeIndex != -1) {
                        lastTouchX = x;
                        lastTouchY = y;
                    }
                }

                if (draggingCornerIndex != -1 || draggingEdgeIndex != -1) {
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    if (cornerDragListener != null && draggingCornerIndex != -1) {
                        cornerDragListener.onCornerDragStarted(draggingCornerIndex, viewCorners[draggingCornerIndex].x, viewCorners[draggingCornerIndex].y);
                    }
                    invalidate();
                    return true;
                }

                if (gestureDetector != null && pageSwipeListener != null) {
                    gestureDetector.onTouchEvent(event);
                    return true;
                }
                return false;
            }

            case MotionEvent.ACTION_MOVE: {
                if (draggingCornerIndex != -1) {
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    isCornerMoved = true;
                    RectF bounds = visibleImageBounds.width() > 0 ? visibleImageBounds : getImageBoundsInOverlay();
                    if (bounds != null) {
                        float clampedX = Math.max(bounds.left, Math.min(x, bounds.right));
                        float clampedY = Math.max(bounds.top, Math.min(y, bounds.bottom));

                        viewCorners[draggingCornerIndex].set(clampedX, clampedY);
                        updateNormalizedCornerFromView(draggingCornerIndex, clampedX, clampedY);
                        updateMidPoints();
                        invalidate();

                        if (cornerDragListener != null) {
                            cornerDragListener.onCornerDragging(draggingCornerIndex, clampedX, clampedY);
                            cornerDragListener.onNormalizedCornersUpdated(getNormalizedCornersFlat());
                        }
                    }
                    return true;

                } else if (draggingEdgeIndex != -1) {
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    isCornerMoved = true;
                    RectF bounds = visibleImageBounds.width() > 0 ? visibleImageBounds : getImageBoundsInOverlay();
                    if (bounds != null) {
                        float dx = x - lastTouchX;
                        float dy = y - lastTouchY;

                        int cA = draggingEdgeIndex;
                        int cB = (draggingEdgeIndex + 1) % 4;

                        // Ensure neither corner moves outside bounds
                        float ax = viewCorners[cA].x + dx;
                        float ay = viewCorners[cA].y + dy;
                        float bx = viewCorners[cB].x + dx;
                        float by = viewCorners[cB].y + dy;

                        if (ax < bounds.left) dx += (bounds.left - ax);
                        if (ax > bounds.right) dx -= (ax - bounds.right);
                        if (bx < bounds.left) dx += (bounds.left - bx);
                        if (bx > bounds.right) dx -= (bx - bounds.right);

                        if (ay < bounds.top) dy += (bounds.top - ay);
                        if (ay > bounds.bottom) dy -= (ay - bounds.bottom);
                        if (by < bounds.top) dy += (bounds.top - by);
                        if (by > bounds.bottom) dy -= (by - bounds.bottom);

                        viewCorners[cA].offset(dx, dy);
                        viewCorners[cB].offset(dx, dy);

                        updateNormalizedCornerFromView(cA, viewCorners[cA].x, viewCorners[cA].y);
                        updateNormalizedCornerFromView(cB, viewCorners[cB].x, viewCorners[cB].y);
                        updateMidPoints();

                        lastTouchX = x;
                        lastTouchY = y;
                        invalidate();

                        if (cornerDragListener != null) {
                            cornerDragListener.onCornerDragging(cA, viewCorners[cA].x, viewCorners[cA].y);
                            cornerDragListener.onNormalizedCornersUpdated(getNormalizedCornersFlat());
                        }
                    }
                    return true;

                } else if (gestureDetector != null && pageSwipeListener != null) {
                    gestureDetector.onTouchEvent(event);
                }
                break;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                if (draggingCornerIndex != -1 || draggingEdgeIndex != -1) {
                    draggingCornerIndex = -1;
                    draggingEdgeIndex = -1;
                    if (cornerDragListener != null) {
                        cornerDragListener.onNormalizedCornersUpdated(getNormalizedCornersFlat());
                        cornerDragListener.onCornerDragEnded();
                    }
                    invalidate();
                    return true;
                } else if (gestureDetector != null && pageSwipeListener != null) {
                    gestureDetector.onTouchEvent(event);
                }
                break;
            }
        }

        return super.onTouchEvent(event);
    }

    private class SwipeGestureListener extends GestureDetector.SimpleOnGestureListener {
        private static final int SWIPE_MIN_DISTANCE = 80;
        private static final int SWIPE_THRESHOLD_VELOCITY = 150;

        @Override
        public boolean onDown(MotionEvent e) {
            return true;
        }

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
            if (e1 == null || e2 == null) return false;
            float diffX = e2.getX() - e1.getX();
            float diffY = e2.getY() - e1.getY();
            if (Math.abs(diffX) > Math.abs(diffY)) {
                if (Math.abs(diffX) > SWIPE_MIN_DISTANCE && Math.abs(velocityX) > SWIPE_THRESHOLD_VELOCITY) {
                    if (diffX < 0) {
                        if (pageSwipeListener != null) {
                            pageSwipeListener.onSwipeNext();
                            return true;
                        }
                    } else {
                        if (pageSwipeListener != null) {
                            pageSwipeListener.onSwipePrevious();
                            return true;
                        }
                    }
                }
            }
            return false;
        }
    }
}
