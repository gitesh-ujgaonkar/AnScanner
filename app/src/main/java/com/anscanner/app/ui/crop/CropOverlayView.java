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

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.anscanner.app.R;

public class CropOverlayView extends View {
    private final Paint polygonPaint;
    private final Paint linePaint;
    private final Paint handleFillPaint;
    private final Paint handleStrokePaint;
    private final Path path;
    
    // Magnifier / Loupe fields
    private final Paint loupeBorderPaint;
    private final Paint loupeShadowPaint;
    private final Paint loupeCrosshairPaint;
    private final Paint loupeCrosshairShadowPaint;
    private final Paint loupeBitmapPaint;
    private final Path loupeClipPath;
    private final float loupeRadius;
    private final float loupeMargin;
    private final float zoomFactor = 2.0f;

    private Point[] imageCorners = null;
    private PointF[] viewCorners = new PointF[4];
    private ImageView imageView;
    
    public interface OnCornerDragListener {
        void onCornerDragStarted(int cornerIndex, float x, float y);
        void onCornerDragging(int cornerIndex, float x, float y);
        void onCornerDragEnded();
    }

    public interface OnPageSwipeListener {
        void onSwipeNext();
        void onSwipePrevious();
    }

    private OnCornerDragListener cornerDragListener;
    private OnPageSwipeListener pageSwipeListener;
    private GestureDetector gestureDetector;
    private boolean isCornerMoved = false;
    private int draggingCornerIndex = -1;
    private final float touchRadius;
    private final float handleRadius;

    public CropOverlayView(Context context) {
        this(context, null);
    }

    public CropOverlayView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CropOverlayView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        gestureDetector = new GestureDetector(context, new SwipeGestureListener());
        
        polygonPaint = new Paint();
        polygonPaint.setColor(ContextCompat.getColor(context, R.color.crop_overlay));
        polygonPaint.setStyle(Paint.Style.FILL);

        linePaint = new Paint();
        linePaint.setColor(ContextCompat.getColor(context, R.color.accent_mint));
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(context.getResources().getDisplayMetrics().density * 2);
        
        handleFillPaint = new Paint();
        handleFillPaint.setColor(ContextCompat.getColor(context, R.color.crop_handle_fill));
        handleFillPaint.setStyle(Paint.Style.FILL);
        
        handleStrokePaint = new Paint();
        handleStrokePaint.setColor(ContextCompat.getColor(context, R.color.crop_handle_stroke));
        handleStrokePaint.setStyle(Paint.Style.STROKE);
        handleStrokePaint.setStrokeWidth(context.getResources().getDisplayMetrics().density * 2);

        path = new Path();
        
        float density = context.getResources().getDisplayMetrics().density;
        touchRadius = density * 40; // Generous 40dp touch target
        handleRadius = density * 10; // 10dp
        
        // Initialize Magnifier / Loupe
        loupeRadius = density * 65; // 65dp
        loupeMargin = density * 16; // 16dp

        loupeBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        loupeBorderPaint.setStyle(Paint.Style.STROKE);
        loupeBorderPaint.setStrokeWidth(density * 2);
        loupeBorderPaint.setColor(ContextCompat.getColor(context, R.color.accent_mint));

        loupeShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        loupeShadowPaint.setColor(0x40000000);
        loupeShadowPaint.setStyle(Paint.Style.FILL);

        loupeCrosshairPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        loupeCrosshairPaint.setStyle(Paint.Style.STROKE);
        loupeCrosshairPaint.setStrokeWidth(density * 2);
        loupeCrosshairPaint.setColor(ContextCompat.getColor(context, R.color.accent_mint));

        loupeCrosshairShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        loupeCrosshairShadowPaint.setStyle(Paint.Style.STROKE);
        loupeCrosshairShadowPaint.setStrokeWidth(density * 4);
        loupeCrosshairShadowPaint.setColor(0xCC000000);

        loupeBitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

        loupeClipPath = new Path();

        for (int i = 0; i < 4; i++) {
            viewCorners[i] = new PointF();
        }
    }

    public void setImageView(ImageView iv) {
        this.imageView = iv;
        if (this.imageView != null) {
            this.imageView.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                if (imageCorners != null && imageCorners.length == 4) {
                    updateViewCorners();
                    invalidate();
                }
            });
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

    public Matrix getImageViewToOverlayMatrix() {
        if (imageView == null || imageView.getDrawable() == null) {
            return new Matrix();
        }
        Matrix matrix = new Matrix(imageView.getImageMatrix());
        float offsetX = (float) (imageView.getLeft() - getLeft() + imageView.getPaddingLeft());
        float offsetY = (float) (imageView.getTop() - getTop() + imageView.getPaddingTop());
        matrix.postTranslate(offsetX, offsetY);
        return matrix;
    }

    public PointF getImageCoordinates(float viewX, float viewY) {
        if (imageView == null || imageView.getDrawable() == null) {
            return new PointF(viewX, viewY);
        }
        Matrix inverse = new Matrix();
        if (getImageViewToOverlayMatrix().invert(inverse)) {
            float[] pts = new float[]{viewX, viewY};
            inverse.mapPoints(pts);
            return new PointF(pts[0], pts[1]);
        }
        return new PointF(viewX, viewY);
    }

    public void setCorners(Point[] corners) {
        if (corners != null && corners.length == 4) {
            this.imageCorners = corners;
            updateViewCorners();
            invalidate();
        }
    }
    
    public Point[] getCornerPoints() {
        if (imageView == null || imageView.getDrawable() == null) return null;
        
        Matrix inverse = new Matrix();
        if (!getImageViewToOverlayMatrix().invert(inverse)) return null;
        
        Point[] result = new Point[4];
        float[] pts = new float[2];
        
        for (int i = 0; i < 4; i++) {
            pts[0] = viewCorners[i].x;
            pts[1] = viewCorners[i].y;
            inverse.mapPoints(pts);
            result[i] = new Point(Math.round(pts[0]), Math.round(pts[1]));
        }
        return result;
    }

    private void updateViewCorners() {
        if (imageCorners == null || imageView == null || imageView.getDrawable() == null) return;
        
        Matrix matrix = getImageViewToOverlayMatrix();
        float[] pts = new float[2];
        
        for (int i = 0; i < 4; i++) {
            pts[0] = imageCorners[i].x;
            pts[1] = imageCorners[i].y;
            matrix.mapPoints(pts);
            viewCorners[i].set(pts[0], pts[1]);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (imageCorners != null && imageCorners.length == 4) {
            updateViewCorners();
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        if (imageCorners == null || imageView == null) return;
        
        // Sometimes view corners need updating if image bounds changed
        if (viewCorners[0].x == 0 && viewCorners[0].y == 0 && imageCorners[0] != null) {
            updateViewCorners();
        }

        path.reset();
        path.moveTo(viewCorners[0].x, viewCorners[0].y);
        path.lineTo(viewCorners[1].x, viewCorners[1].y);
        path.lineTo(viewCorners[2].x, viewCorners[2].y);
        path.lineTo(viewCorners[3].x, viewCorners[3].y);
        path.close();

        canvas.drawPath(path, polygonPaint);
        canvas.drawPath(path, linePaint);
        
        for (int i = 0; i < 4; i++) {
            canvas.drawCircle(viewCorners[i].x, viewCorners[i].y, handleRadius, handleFillPaint);
            canvas.drawCircle(viewCorners[i].x, viewCorners[i].y, handleRadius, handleStrokePaint);
        }

        // Draw Corner Magnifier / Zoom Loupe if a corner is actively being dragged
        if (draggingCornerIndex >= 0 && draggingCornerIndex < 4 && imageView != null && imageView.getDrawable() != null) {
            drawCornerMagnifier(canvas);
        }
    }

    private void drawCornerMagnifier(Canvas canvas) {
        Bitmap bitmap = null;
        if (imageView.getDrawable() instanceof BitmapDrawable) {
            bitmap = ((BitmapDrawable) imageView.getDrawable()).getBitmap();
        }
        if (bitmap == null || bitmap.isRecycled()) {
            return;
        }

        float density = getResources().getDisplayMetrics().density;
        float touchX = viewCorners[draggingCornerIndex].x;
        float touchY = viewCorners[draggingCornerIndex].y;

        // Determine opposite diagonal corner for magnifier placement
        boolean isLeft = touchX < getWidth() / 2f;
        boolean isTop = touchY < getHeight() / 2f;

        float cx;
        float cy;

        if (isLeft && isTop) {
            // Dragging Top-Left -> Magnifier at Bottom-Right
            cx = getWidth() - loupeMargin - loupeRadius;
            cy = getHeight() - loupeMargin - loupeRadius;
        } else if (!isLeft && isTop) {
            // Dragging Top-Right -> Magnifier at Bottom-Left
            cx = loupeMargin + loupeRadius;
            cy = getHeight() - loupeMargin - loupeRadius;
        } else if (!isLeft && !isTop) {
            // Dragging Bottom-Right -> Magnifier at Top-Left
            cx = loupeMargin + loupeRadius;
            cy = loupeMargin + loupeRadius;
        } else {
            // Dragging Bottom-Left -> Magnifier at Top-Right
            cx = getWidth() - loupeMargin - loupeRadius;
            cy = loupeMargin + loupeRadius;
        }

        // 1. Draw outer elevation drop shadow
        canvas.drawCircle(cx, cy + density * 3f, loupeRadius + density * 2f, loupeShadowPaint);

        // 2. Setup transform matrix: scale 2x centered at touch point, then translate to loupe center
        Matrix imgMatrix = getImageViewToOverlayMatrix();
        Matrix loupeMatrix = new Matrix(imgMatrix);
        loupeMatrix.postScale(zoomFactor, zoomFactor, touchX, touchY);
        loupeMatrix.postTranslate(cx - touchX, cy - touchY);

        // 3. Clip to circular loupe area and draw source bitmap
        canvas.save();
        loupeClipPath.reset();
        loupeClipPath.addCircle(cx, cy, loupeRadius, Path.Direction.CW);
        canvas.clipPath(loupeClipPath);

        // White background behind bitmap in case edge of image is reached
        canvas.drawColor(Color.WHITE);
        canvas.drawBitmap(bitmap, loupeMatrix, loupeBitmapPaint);
        canvas.restore();

        // 4. Draw high-contrast crosshair at center (cx, cy)
        float crosshairSize = density * 12f;
        // Horizontal shadow & line
        canvas.drawLine(cx - crosshairSize, cy, cx + crosshairSize, cy, loupeCrosshairShadowPaint);
        canvas.drawLine(cx - crosshairSize, cy, cx + crosshairSize, cy, loupeCrosshairPaint);
        // Vertical shadow & line
        canvas.drawLine(cx, cy - crosshairSize, cx, cy + crosshairSize, loupeCrosshairShadowPaint);
        canvas.drawLine(cx, cy - crosshairSize, cx, cy + crosshairSize, loupeCrosshairPaint);
        // Center dot
        canvas.drawCircle(cx, cy, density * 2f, loupeCrosshairPaint);

        // 5. Draw 2dp border around loupe
        canvas.drawCircle(cx, cy, loupeRadius, loupeBorderPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (imageView == null || imageView.getDrawable() == null) return false;

        float x = event.getX();
        float y = event.getY();

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                draggingCornerIndex = -1;
                float minDistance = Float.MAX_VALUE;
                for (int i = 0; i < 4; i++) {
                    float dx = x - viewCorners[i].x;
                    float dy = y - viewCorners[i].y;
                    float dist = (float) Math.sqrt(dx * dx + dy * dy);
                    if (dist < touchRadius && dist < minDistance) {
                        minDistance = dist;
                        draggingCornerIndex = i;
                    }
                }
                if (draggingCornerIndex != -1) {
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    if (cornerDragListener != null) {
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

            case MotionEvent.ACTION_MOVE:
                if (draggingCornerIndex != -1) {
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    isCornerMoved = true;
                    // Get image bounds in view coordinates to clamp using accurate matrix
                    RectF bounds = new RectF(0, 0, (float) imageView.getDrawable().getIntrinsicWidth(), (float) imageView.getDrawable().getIntrinsicHeight());
                    getImageViewToOverlayMatrix().mapRect(bounds);
                    
                    float clampedX = Math.max(bounds.left, Math.min(x, bounds.right));
                    float clampedY = Math.max(bounds.top, Math.min(y, bounds.bottom));
                    
                    viewCorners[draggingCornerIndex].set(clampedX, clampedY);
                    invalidate();

                    if (cornerDragListener != null) {
                        cornerDragListener.onCornerDragging(draggingCornerIndex, clampedX, clampedY);
                    }
                    return true;
                } else if (gestureDetector != null && pageSwipeListener != null) {
                    gestureDetector.onTouchEvent(event);
                }
                break;

            case MotionEvent.ACTION_UP:
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                if (draggingCornerIndex != -1) {
                    isCornerMoved = true;
                    RectF bounds = new RectF(0, 0, (float) imageView.getDrawable().getIntrinsicWidth(), (float) imageView.getDrawable().getIntrinsicHeight());
                    getImageViewToOverlayMatrix().mapRect(bounds);
                    
                    float clampedX = Math.max(bounds.left, Math.min(x, bounds.right));
                    float clampedY = Math.max(bounds.top, Math.min(y, bounds.bottom));
                    
                    viewCorners[draggingCornerIndex].set(clampedX, clampedY);
                    
                    Point[] currentCorners = getCornerPoints();
                    if (currentCorners != null) {
                        this.imageCorners = currentCorners;
                    }

                    if (cornerDragListener != null) {
                        cornerDragListener.onCornerDragging(draggingCornerIndex, clampedX, clampedY);
                        cornerDragListener.onCornerDragEnded();
                    }
                    draggingCornerIndex = -1;
                    invalidate();
                    return true;
                } else if (gestureDetector != null && pageSwipeListener != null) {
                    gestureDetector.onTouchEvent(event);
                }
                break;

            case MotionEvent.ACTION_CANCEL:
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                if (draggingCornerIndex != -1) {
                    draggingCornerIndex = -1;
                    if (cornerDragListener != null) {
                        cornerDragListener.onCornerDragEnded();
                    }
                    invalidate();
                }
                break;
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
                        // Swiped right to left -> Next page
                        if (pageSwipeListener != null) {
                            pageSwipeListener.onSwipeNext();
                            return true;
                        }
                    } else {
                        // Swiped left to right -> Previous page
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
