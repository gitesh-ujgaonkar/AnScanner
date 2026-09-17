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

    private void init() {
        setMinimumScale(MIN_SCALE);
        setMediumScale(MID_SCALE);
        setMaximumScale(MAX_SCALE);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
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
