package com.anscanner.app.ui.custom;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewParent;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.github.chrisbanes.photoview.PhotoView;

/**
 * High-performance touch-enabled scaling ImageView supporting smooth pinch-to-zoom (up to 5x),
 * two-dimensional panning, double-tap zoom toggling, and seamless gesture coordination with
 * parent scrolling containers (such as RecyclerView).
 */
public class TouchImageView extends PhotoView {

    public static final float MIN_SCALE = 1.0f;
    public static final float MID_SCALE = 2.5f;
    public static final float MAX_SCALE = 5.0f;

    public TouchImageView(@NonNull Context context) {
        super(context);
        init();
    }

    public TouchImageView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public TouchImageView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private final android.graphics.Matrix fitCenterMatrix = new android.graphics.Matrix();
    private boolean zoomEnabled = true;

    private void init() {
        setMinimumScale(MIN_SCALE);
        setMediumScale(MID_SCALE);
        setMaximumScale(MAX_SCALE);
        setScaleType(ScaleType.FIT_CENTER);
    }

    public void updateFitCenterMatrix() {
        android.graphics.drawable.Drawable d = getDrawable();
        if (d == null) return;
        int dw = d.getIntrinsicWidth();
        int dh = d.getIntrinsicHeight();
        int vw = getWidth() - getPaddingLeft() - getPaddingRight();
        int vh = getHeight() - getPaddingTop() - getPaddingBottom();
        if (dw <= 0 || dh <= 0 || vw <= 0 || vh <= 0) return;

        android.graphics.RectF src = new android.graphics.RectF(0, 0, dw, dh);
        android.graphics.RectF dst = new android.graphics.RectF(getPaddingLeft(), getPaddingTop(), getPaddingLeft() + vw, getPaddingTop() + vh);
        fitCenterMatrix.reset();
        fitCenterMatrix.setRectToRect(src, dst, android.graphics.Matrix.ScaleToFit.CENTER);
        super.setImageMatrix(fitCenterMatrix);
    }

    public android.graphics.Matrix getFitCenterMatrix() {
        return fitCenterMatrix;
    }

    @Override
    public android.graphics.RectF getDisplayRect() {
        if (!zoomEnabled) {
            android.graphics.drawable.Drawable d = getDrawable();
            if (d != null) {
                int dw = d.getIntrinsicWidth();
                int dh = d.getIntrinsicHeight();
                int vw = getWidth() - getPaddingLeft() - getPaddingRight();
                int vh = getHeight() - getPaddingTop() - getPaddingBottom();
                if (dw > 0 && dh > 0 && vw > 0 && vh > 0) {
                    android.graphics.RectF src = new android.graphics.RectF(0, 0, dw, dh);
                    android.graphics.RectF dst = new android.graphics.RectF(getPaddingLeft(), getPaddingTop(), getPaddingLeft() + vw, getPaddingTop() + vh);
                    android.graphics.Matrix m = new android.graphics.Matrix();
                    m.setRectToRect(src, dst, android.graphics.Matrix.ScaleToFit.CENTER);
                    android.graphics.RectF rect = new android.graphics.RectF();
                    m.mapRect(rect, src);
                    return rect;
                }
            }
        }
        return super.getDisplayRect();
    }

    @Override
    public void setImageDrawable(@Nullable android.graphics.drawable.Drawable drawable) {
        super.setImageDrawable(drawable);
        if (!zoomEnabled) {
            updateFitCenterMatrix();
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (!zoomEnabled) {
            updateFitCenterMatrix();
        }
    }

    @Override
    public void setImageMatrix(android.graphics.Matrix matrix) {
        if (!zoomEnabled) {
            super.setImageMatrix(fitCenterMatrix);
            return;
        }
        super.setImageMatrix(matrix);
    }

    @Override
    public void setZoomable(boolean zoomable) {
        this.zoomEnabled = zoomable;
        // Do NOT delegate setZoomable(false) to PhotoViewAttacher because PhotoView's
        // implementation resets the base matrix to identity, destroying fitCenter scaling
        // and causing the bitmap to display at 1:1 unscaled (appearing zoomed-in/cropped).
        // Instead, keep attacher's internal zoom active to preserve the fitCenter matrix,
        // and gate touch gesture handling via onTouchEvent().
        if (!zoomable) {
            setScale(1.0f, false);
            updateFitCenterMatrix();
        } else {
            if (getAttacher() != null) {
                getAttacher().update();
            }
        }
    }

    @Override
    public boolean isZoomable() {
        return zoomEnabled;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!zoomEnabled) {
            return false;
        }
        // While zoomed in, strictly prevent parent scrolling containers from intercepting touch gestures
        if (getScale() > 1.05f) {
            ViewParent parent = getParent();
            if (parent != null) {
                parent.requestDisallowInterceptTouchEvent(true);
            }
        }
        return super.onTouchEvent(ev);
    }
}
