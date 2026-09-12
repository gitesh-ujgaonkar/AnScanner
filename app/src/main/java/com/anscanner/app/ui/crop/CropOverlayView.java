package com.anscanner.app.ui.crop;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.RectF;
import android.util.AttributeSet;
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
    
    private Point[] imageCorners = null;
    private PointF[] viewCorners = new PointF[4];
    private ImageView imageView;
    
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
        
        touchRadius = context.getResources().getDisplayMetrics().density * 32; // 32dp
        handleRadius = context.getResources().getDisplayMetrics().density * 10; // 10dp
        
        for (int i = 0; i < 4; i++) {
            viewCorners[i] = new PointF();
        }
    }

    public void setImageView(ImageView iv) {
        this.imageView = iv;
    }

    public void setCorners(Point[] corners) {
        if (corners != null && corners.length == 4) {
            this.imageCorners = corners;
            updateViewCorners();
            invalidate();
        }
    }
    
    public Point[] getCornerPoints() {
        if (imageCorners == null || imageView == null || imageView.getDrawable() == null) return null;
        
        Matrix inverse = new Matrix();
        imageView.getImageMatrix().invert(inverse);
        
        Point[] result = new Point[4];
        float[] pts = new float[2];
        
        for (int i = 0; i < 4; i++) {
            pts[0] = viewCorners[i].x;
            pts[1] = viewCorners[i].y;
            inverse.mapPoints(pts);
            result[i] = new Point((int) pts[0], (int) pts[1]);
        }
        return result;
    }

    private void updateViewCorners() {
        if (imageCorners == null || imageView == null || imageView.getDrawable() == null) return;
        
        Matrix matrix = imageView.getImageMatrix();
        float[] pts = new float[2];
        
        for (int i = 0; i < 4; i++) {
            pts[0] = imageCorners[i].x;
            pts[1] = imageCorners[i].y;
            matrix.mapPoints(pts);
            viewCorners[i].set(pts[0], pts[1]);
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
                return draggingCornerIndex != -1;

            case MotionEvent.ACTION_MOVE:
                if (draggingCornerIndex != -1) {
                    // Get image bounds in view coordinates to clamp
                    RectF bounds = new RectF(0, 0, imageView.getDrawable().getIntrinsicWidth(), imageView.getDrawable().getIntrinsicHeight());
                    imageView.getImageMatrix().mapRect(bounds);
                    
                    float clampedX = Math.max(bounds.left, Math.min(x, bounds.right));
                    float clampedY = Math.max(bounds.top, Math.min(y, bounds.bottom));
                    
                    viewCorners[draggingCornerIndex].set(clampedX, clampedY);
                    invalidate();
                    return true;
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                draggingCornerIndex = -1;
                break;
        }

        return super.onTouchEvent(event);
    }
}
