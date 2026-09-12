package com.anscanner.app.ui.camera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.Region;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.anscanner.app.R;

public class DocumentOverlayView extends View {
    private final Paint scrimPaint;
    private final Paint borderPaint;
    private final Path cutoutPath;
    private Point[] corners = null;

    public DocumentOverlayView(Context context) {
        this(context, null);
    }

    public DocumentOverlayView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public DocumentOverlayView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        scrimPaint = new Paint();
        scrimPaint.setColor(0x80000000); // 50% black
        scrimPaint.setStyle(Paint.Style.FILL);

        borderPaint = new Paint();
        borderPaint.setColor(ContextCompat.getColor(context, R.color.accent_mint));
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(context.getResources().getDisplayMetrics().density * 2);

        cutoutPath = new Path();
    }

    public void setCorners(Point[] corners) {
        this.corners = corners;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (corners != null && corners.length == 4) {
            cutoutPath.reset();
            cutoutPath.moveTo(corners[0].x, corners[0].y);
            cutoutPath.lineTo(corners[1].x, corners[1].y);
            cutoutPath.lineTo(corners[2].x, corners[2].y);
            cutoutPath.lineTo(corners[3].x, corners[3].y);
            cutoutPath.close();

            canvas.save();
            canvas.clipPath(cutoutPath, Region.Op.DIFFERENCE);
            canvas.drawRect(0, 0, getWidth(), getHeight(), scrimPaint);
            canvas.restore();

            canvas.drawPath(cutoutPath, borderPaint);
        } else {
            // Draw default scrim without cutout if no corners
            canvas.drawRect(0, 0, getWidth(), getHeight(), scrimPaint);
        }
    }
}
