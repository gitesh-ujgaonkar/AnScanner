package com.anscanner.app.ui.custom;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Interactive freehand drawing and signature overlay that sits on top of a PhotoView or PDF page.
 * Captures freehand touch gestures with smooth Bézier curves and renders them to screen.
 *
 * <p>Provides full resolution vector flattening onto {@link android.graphics.pdf.PdfDocument}
 * or {@link android.graphics.Bitmap} canvases via {@link #drawToCanvas(Canvas, RectF, int, int)}.</p>
 */
public class DrawingOverlayView extends View {

    public static class Stroke {
        public final Path path;
        public final int color;
        public final float strokeWidth;

        public Stroke(Path path, int color, float strokeWidth) {
            this.path = path;
            this.color = color;
            this.strokeWidth = strokeWidth;
        }
    }

    private final List<Stroke> strokes = new ArrayList<>();
    private final Path currentPath = new Path();
    private final Paint drawPaint = new Paint();

    private int currentColor = Color.parseColor("#48BB78"); // Default Mint Green accent
    private float currentStrokeWidth = 8f; // Default 8dp-ish stroke
    private boolean isDrawingEnabled = true;

    private float lastTouchX;
    private float lastTouchY;

    public DrawingOverlayView(Context context) {
        super(context);
        init();
    }

    public DrawingOverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public DrawingOverlayView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        drawPaint.setAntiAlias(true);
        drawPaint.setDither(true);
        drawPaint.setStyle(Paint.Style.STROKE);
        drawPaint.setStrokeJoin(Paint.Join.ROUND);
        drawPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    public void setDrawingEnabled(boolean enabled) {
        this.isDrawingEnabled = enabled;
    }

    public boolean isDrawingEnabled() {
        return isDrawingEnabled;
    }

    public void setStrokeColor(int color) {
        this.currentColor = color;
    }

    public int getStrokeColor() {
        return currentColor;
    }

    public void setStrokeWidth(float width) {
        this.currentStrokeWidth = Math.max(2f, width);
    }

    public float getStrokeWidth() {
        return currentStrokeWidth;
    }

    public void undo() {
        if (!strokes.isEmpty()) {
            strokes.remove(strokes.size() - 1);
            invalidate();
        }
    }

    public void clear() {
        strokes.clear();
        currentPath.reset();
        invalidate();
    }

    public boolean isEmpty() {
        return strokes.isEmpty() && currentPath.isEmpty();
    }

    public int getStrokeCount() {
        return strokes.size();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isDrawingEnabled || !isEnabled()) {
            return super.onTouchEvent(event);
        }

        float x = event.getX();
        float y = event.getY();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                currentPath.reset();
                currentPath.moveTo(x, y);
                lastTouchX = x;
                lastTouchY = y;
                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE:
                float dx = Math.abs(x - lastTouchX);
                float dy = Math.abs(y - lastTouchY);
                if (dx >= 3f || dy >= 3f) {
                    currentPath.quadTo(lastTouchX, lastTouchY, (x + lastTouchX) / 2f, (y + lastTouchY) / 2f);
                    lastTouchX = x;
                    lastTouchY = y;
                    invalidate();
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                currentPath.lineTo(x, y);
                strokes.add(new Stroke(new Path(currentPath), currentColor, currentStrokeWidth));
                currentPath.reset();
                invalidate();
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;

            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // Draw recorded strokes
        for (Stroke stroke : strokes) {
            drawPaint.setColor(stroke.color);
            drawPaint.setStrokeWidth(stroke.strokeWidth);
            canvas.drawPath(stroke.path, drawPaint);
        }

        // Draw active in-progress stroke
        if (!currentPath.isEmpty()) {
            drawPaint.setColor(currentColor);
            drawPaint.setStrokeWidth(currentStrokeWidth);
            canvas.drawPath(currentPath, drawPaint);
        }
    }

    /**
     * Flattens and renders all vector strokes onto a destination canvas (e.g. a PdfDocument page or Bitmap).
     *
     * @param targetCanvas      Destination canvas to draw on.
     * @param sourceRectOnView  The display rect of the image/page inside the View (e.g., from PhotoView.getDisplayRect()).
     *                          If null, uses this View's full bounds.
     * @param targetWidth       Width of the destination canvas/page in points or pixels.
     * @param targetHeight      Height of the destination canvas/page in points or pixels.
     */
    public void drawToCanvas(@NonNull Canvas targetCanvas,
                             @Nullable RectF sourceRectOnView,
                             int targetWidth,
                             int targetHeight) {
        if (strokes.isEmpty()) {
            return;
        }

        Matrix matrix = new Matrix();
        float scaleFactor = 1.0f;

        if (sourceRectOnView != null && sourceRectOnView.width() > 0 && sourceRectOnView.height() > 0) {
            float sx = (float) targetWidth / sourceRectOnView.width();
            float sy = (float) targetHeight / sourceRectOnView.height();
            scaleFactor = (sx + sy) / 2f;

            matrix.postTranslate(-sourceRectOnView.left, -sourceRectOnView.top);
            matrix.postScale(sx, sy);
        } else if (getWidth() > 0 && getHeight() > 0) {
            float sx = (float) targetWidth / getWidth();
            float sy = (float) targetHeight / getHeight();
            scaleFactor = (sx + sy) / 2f;

            matrix.postScale(sx, sy);
        }

        Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setDither(true);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeCap(Paint.Cap.ROUND);

        Path transformedPath = new Path();
        for (Stroke stroke : strokes) {
            paint.setColor(stroke.color);
            paint.setStrokeWidth(Math.max(1f, stroke.strokeWidth * scaleFactor));

            transformedPath.reset();
            stroke.path.transform(matrix, transformedPath);
            targetCanvas.drawPath(transformedPath, paint);
        }
    }
}
