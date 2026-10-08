package com.anscanner.app.ui.view;

import android.animation.ValueAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Interactive whole-document zoom and pan container following Adobe Acrobat / Chrome / Google Drive standards.
 *
 * <p>Wraps a vertical document container (such as {@link RecyclerView}) and coordinates:
 * <ul>
 *   <li>Continuous vertical scrolling through all document pages when at default scale (1.0x)</li>
 *   <li>Pinch-to-zoom (1.0x to 5.0x) applying globally across all pages</li>
 *   <li>Double-tap zoom toggle between 1.0x and 2.5x with smooth deceleration animation</li>
 *   <li>2D panning across the scaled document canvas with seamless edge-scroll handover</li>
 *   <li>Independent zoom and pan locking for drawing/crop modes</li>
 * </ul>
 * </p>
 */
public class DocumentZoomLayout extends FrameLayout {

    public static final float MIN_SCALE = 1.0f;
    public static final float MID_SCALE = 2.5f;
    public static final float MAX_SCALE = 5.0f;

    private float currentScale = MIN_SCALE;
    private float transX = 0f;
    private float transY = 0f;

    private boolean isZoomEnabled = true;
    private boolean isPanEnabled = true;

    private ScaleGestureDetector scaleGestureDetector;
    private GestureDetector gestureDetector;
    private int touchSlop;

    private float lastTouchX;
    private float lastTouchY;
    private boolean isDragging = false;
    private ValueAnimator scaleAnimator;

    public interface OnZoomChangeListener {
        void onZoomChanged(float scale);
    }

    private OnZoomChangeListener zoomChangeListener;

    public DocumentZoomLayout(@NonNull Context context) {
        super(context);
        init(context);
    }

    public DocumentZoomLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public DocumentZoomLayout(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        scaleGestureDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                if (!isZoomEnabled) return false;

                float factor = detector.getScaleFactor();
                float newScale = currentScale * factor;
                newScale = Math.max(0.9f, Math.min(MAX_SCALE * 1.2f, newScale));

                float focusX = detector.getFocusX();
                float focusY = detector.getFocusY();

                // Zoom centered around focal point
                transX = focusX - (focusX - transX) * (newScale / currentScale);
                transY = focusY - (focusY - transY) * (newScale / currentScale);
                currentScale = newScale;

                clampTranslations();
                applyTransform();

                if (zoomChangeListener != null) {
                    zoomChangeListener.onZoomChanged(currentScale);
                }
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector detector) {
                if (currentScale < MIN_SCALE) {
                    animateScale(MIN_SCALE, getWidth() / 2f, getHeight() / 2f);
                } else if (currentScale > MAX_SCALE) {
                    animateScale(MAX_SCALE, detector.getFocusX(), detector.getFocusY());
                }
            }
        });

        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (!isZoomEnabled) return false;
                float targetScale = (currentScale > 1.2f) ? MIN_SCALE : MID_SCALE;
                animateScale(targetScale, e.getX(), e.getY());
                return true;
            }
        });
    }

    public void setOnZoomChangeListener(OnZoomChangeListener listener) {
        this.zoomChangeListener = listener;
    }

    public void setZoomEnabled(boolean enabled) {
        this.isZoomEnabled = enabled;
    }

    public boolean isZoomEnabled() {
        return isZoomEnabled;
    }

    public void setPanEnabled(boolean enabled) {
        this.isPanEnabled = enabled;
    }

    public boolean isPanEnabled() {
        return isPanEnabled;
    }

    public float getCurrentScale() {
        return currentScale;
    }

    public void resetZoom(boolean animated) {
        if (animated) {
            animateScale(MIN_SCALE, getWidth() / 2f, getHeight() / 2f);
        } else {
            if (scaleAnimator != null && scaleAnimator.isRunning()) {
                scaleAnimator.cancel();
            }
            currentScale = MIN_SCALE;
            transX = 0f;
            transY = 0f;
            applyTransform();
            if (zoomChangeListener != null) {
                zoomChangeListener.onZoomChanged(currentScale);
            }
        }
    }

    public void animateScale(float targetScale, float focusX, float focusY) {
        if (scaleAnimator != null && scaleAnimator.isRunning()) {
            scaleAnimator.cancel();
        }

        final float startScale = currentScale;
        final float startTransX = transX;
        final float startTransY = transY;

        float targetTransX;
        float targetTransY;

        if (targetScale <= 1.05f) {
            targetScale = MIN_SCALE;
            targetTransX = 0f;
            targetTransY = 0f;
        } else {
            targetScale = Math.min(MAX_SCALE, targetScale);
            targetTransX = focusX - (focusX - startTransX) * (targetScale / startScale);
            targetTransY = focusY - (focusY - startTransY) * (targetScale / startScale);
        }

        final float finalScale = targetScale;
        final float finalTransX = targetTransX;
        final float finalTransY = targetTransY;

        scaleAnimator = ValueAnimator.ofFloat(0f, 1f);
        scaleAnimator.setDuration(250);
        scaleAnimator.setInterpolator(new DecelerateInterpolator());
        scaleAnimator.addUpdateListener(animation -> {
            float fraction = animation.getAnimatedFraction();
            currentScale = startScale + (finalScale - startScale) * fraction;
            transX = startTransX + (finalTransX - startTransX) * fraction;
            transY = startTransY + (finalTransY - startTransY) * fraction;
            clampTranslations();
            applyTransform();

            if (zoomChangeListener != null) {
                zoomChangeListener.onZoomChanged(currentScale);
            }
        });
        scaleAnimator.start();
    }

    private void clampTranslations() {
        View child = getChildAt(0);
        if (child == null) return;

        float childW = child.getWidth() * currentScale;
        float childH = child.getHeight() * currentScale;
        float viewW = getWidth();
        float viewH = getHeight();

        if (childW <= viewW) {
            transX = (viewW - childW) / 2f;
        } else {
            float minX = viewW - childW;
            float maxX = 0f;
            transX = Math.max(minX, Math.min(maxX, transX));
        }

        if (childH <= viewH) {
            transY = (viewH - childH) / 2f;
        } else {
            float minY = viewH - childH;
            float maxY = 0f;
            transY = Math.max(minY, Math.min(maxY, transY));
        }
    }

    private void applyTransform() {
        View child = getChildAt(0);
        if (child != null) {
            child.setPivotX(0f);
            child.setPivotY(0f);
            child.setScaleX(currentScale);
            child.setScaleY(currentScale);
            child.setTranslationX(transX);
            child.setTranslationY(transY);
        }
        invalidate();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (!isZoomEnabled) {
            return false;
        }

        scaleGestureDetector.onTouchEvent(ev);
        gestureDetector.onTouchEvent(ev);

        if (scaleGestureDetector.isInProgress()) {
            return true;
        }

        int action = ev.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                lastTouchX = ev.getX();
                lastTouchY = ev.getY();
                isDragging = false;
                break;

            case MotionEvent.ACTION_MOVE:
                if (ev.getPointerCount() >= 2) {
                    return true;
                }
                if (currentScale > 1.05f && isPanEnabled) {
                    float dx = Math.abs(ev.getX() - lastTouchX);
                    float dy = Math.abs(ev.getY() - lastTouchY);
                    if (dx > touchSlop || dy > touchSlop) {
                        isDragging = true;
                        return true;
                    }
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                isDragging = false;
                break;
        }

        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!isZoomEnabled) {
            return super.onTouchEvent(ev);
        }

        boolean scaleHandled = scaleGestureDetector.onTouchEvent(ev);
        boolean gestureHandled = gestureDetector.onTouchEvent(ev);

        if (scaleGestureDetector.isInProgress()) {
            return true;
        }

        int action = ev.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                lastTouchX = ev.getX();
                lastTouchY = ev.getY();
                return true;

            case MotionEvent.ACTION_MOVE:
                if (ev.getPointerCount() == 1 && currentScale > 1.05f && isPanEnabled) {
                    float dx = ev.getX() - lastTouchX;
                    float dy = ev.getY() - lastTouchY;
                    lastTouchX = ev.getX();
                    lastTouchY = ev.getY();

                    // Pan horizontally
                    transX += dx;

                    // Pan vertically and handover to RecyclerView when hitting boundaries
                    View child = getChildAt(0);
                    if (child instanceof RecyclerView) {
                        RecyclerView rv = (RecyclerView) child;
                        float prevTransY = transY;
                        transY += dy;
                        clampTranslations();

                        float unconsumedY = dy - (transY - prevTransY);
                        if (Math.abs(unconsumedY) > 0.5f) {
                            rv.scrollBy(0, (int) (-unconsumedY / currentScale));
                        }
                    } else {
                        transY += dy;
                        clampTranslations();
                    }

                    applyTransform();
                    return true;
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                isDragging = false;
                if (currentScale < MIN_SCALE) {
                    animateScale(MIN_SCALE, getWidth() / 2f, getHeight() / 2f);
                } else if (currentScale > MAX_SCALE) {
                    animateScale(MAX_SCALE, getWidth() / 2f, getHeight() / 2f);
                }
                break;
        }

        return scaleHandled || gestureHandled || super.onTouchEvent(ev);
    }
}
