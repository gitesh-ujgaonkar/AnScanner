package com.anscanner.app.model.annotation;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.anscanner.app.util.FontUtils;

import java.io.Serializable;
import java.util.Objects;

/**
 * Dynamic & Always-Editable Text Model for document annotations.
 * Preserves text content, position, typography, color, size, bounding box,
 * rotation angle, creation timestamp, and active selection state.
 */
public class TextAnnotation implements AnnotationItem, Serializable {
    private static final long serialVersionUID = 4L;

    public String text;
    public float x; // Document space X
    public float y; // Document space Y (baseline)
    public PointF position; // Normalized (0.0f - 1.0f) coordinates
    public float textSize; // Document space text size in px
    public int textColor; // Text color ARGB
    public int color; // Alias for backward compatibility
    public String fontFamily = "sans"; // "sans", "serif", "monospace", "cursive", "bold_condensed", etc.
    public transient Typeface typeface;
    public boolean hasBackground = true;
    public int backgroundColor = Color.parseColor("#B3000000"); // Semi-transparent dark
    public boolean isSelected = false;
    private float rotationAngle = 0f; // Degrees (0 to 360)
    private long timestamp = System.currentTimeMillis();

    public final Rect bounds = new Rect();
    public final RectF boundingBox = new RectF();

    private transient Paint textPaint;
    private transient Paint bgPaint;
    private transient Paint selectionBoxPaint;
    private transient Paint handleFillPaint;
    private transient Paint handleStrokePaint;
    private transient Paint rotateIconPaint;

    public float getRotationAngle() {
        return rotationAngle;
    }

    public void setRotationAngle(float angle) {
        this.rotationAngle = ((angle % 360f) + 360f) % 360f;
    }

    public TextAnnotation() {
        this("", 0f, 0f, Color.WHITE, 18f);
    }

    public TextAnnotation(String text, float x, float y, int color, float textSize) {
        this.text = text != null ? text : "";
        this.x = x;
        this.y = y;
        this.color = color;
        this.textColor = color;
        this.textSize = textSize;
        this.position = new PointF(0f, 0f);
        this.timestamp = System.currentTimeMillis();
    }

    @Override
    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    @Override
    public boolean isSelected() {
        return isSelected;
    }

    @Override
    public void setSelected(boolean selected) {
        this.isSelected = selected;
    }

    @NonNull
    @Override
    public TextAnnotation copy() {
        TextAnnotation item = new TextAnnotation(this.text, this.x, this.y, this.color, this.textSize);
        item.textColor = this.textColor;
        item.fontFamily = this.fontFamily;
        item.typeface = this.typeface;
        item.hasBackground = this.hasBackground;
        item.backgroundColor = this.backgroundColor;
        item.isSelected = this.isSelected;
        item.bounds.set(this.bounds);
        item.boundingBox.set(this.boundingBox);
        if (this.position != null) {
            item.position = new PointF(this.position.x, this.position.y);
        }
        item.setRotationAngle(this.rotationAngle);
        item.timestamp = this.timestamp;
        return item;
    }

    public void setFontFamily(@Nullable String fontFamily) {
        this.fontFamily = (fontFamily != null && !fontFamily.trim().isEmpty()) ? fontFamily.trim() : "sans";
        this.typeface = null; // Re-resolve lazily or on draw
    }

    public void setTypeface(@Nullable Context context, @Nullable String fontFamily) {
        setFontFamily(fontFamily);
        if (context != null) {
            this.typeface = FontUtils.getTypeface(context, this.fontFamily);
        }
    }

    @NonNull
    public Typeface getTypeface(@Nullable Context context) {
        if (this.typeface == null) {
            this.typeface = FontUtils.getTypeface(context, this.fontFamily);
        }
        return this.typeface;
    }

    public void setTextColor(int color) {
        this.textColor = color;
        this.color = color;
    }

    public void updateNormalizedPosition(int docWidth, int docHeight) {
        if (docWidth > 0 && docHeight > 0) {
            float nx = Math.max(0.0f, Math.min(1.0f, x / (float) docWidth));
            float ny = Math.max(0.0f, Math.min(1.0f, y / (float) docHeight));
            this.position = new PointF(nx, ny);
        }
    }

    public void restoreFromNormalizedPosition(int docWidth, int docHeight) {
        if (position != null && docWidth > 0 && docHeight > 0) {
            this.x = position.x * docWidth;
            this.y = position.y * docHeight;
        }
    }

    public void updateBounds(@NonNull Paint paint, float density) {
        paint.setTextSize(textSize);
        paint.setTypeface(getTypeface(null));
        paint.getTextBounds(text, 0, text.length(), bounds);
        float pad = 10f * density;
        boundingBox.set(
                x - pad,
                y - bounds.height() - pad,
                x + bounds.width() + pad,
                y + pad
        );
    }

    public RectF getBoundingBox(float density) {
        float pad = 10f * density;
        return new RectF(
                x - pad,
                y - bounds.height() - pad,
                x + bounds.width() + pad,
                y + pad
        );
    }

    @Override
    public boolean containsPoint(float x, float y) {
        return contains(x, y, 2.0f);
    }

    public boolean contains(float docX, float docY, float density) {
        RectF box = getBoundingBox(density);
        if (rotationAngle == 0f) {
            return box.contains(docX, docY);
        }
        float cx = box.centerX();
        float cy = box.centerY();
        float rad = (float) Math.toRadians(-rotationAngle);
        float cos = (float) Math.cos(rad);
        float sin = (float) Math.sin(rad);
        float dx = docX - cx;
        float dy = docY - cy;
        float localX = cx + (dx * cos - dy * sin);
        float localY = cy + (dx * sin + dy * cos);
        return box.contains(localX, localY);
    }

    public RectF getResizeHandleRect(float density) {
        RectF box = getBoundingBox(density);
        float handleRadius = 16f * density;
        return new RectF(
                box.right - handleRadius,
                box.bottom - handleRadius,
                box.right + handleRadius,
                box.bottom + handleRadius
        );
    }

    public boolean containsResizeHandle(float docX, float docY, float density) {
        RectF box = getBoundingBox(density);
        float cx = box.centerX();
        float cy = box.centerY();
        float localX = docX;
        float localY = docY;
        if (rotationAngle != 0f) {
            float rad = (float) Math.toRadians(-rotationAngle);
            float cos = (float) Math.cos(rad);
            float sin = (float) Math.sin(rad);
            float dx = docX - cx;
            float dy = docY - cy;
            localX = cx + (dx * cos - dy * sin);
            localY = cy + (dx * sin + dy * cos);
        }
        return getResizeHandleRect(density).contains(localX, localY);
    }

    public RectF getRotateHandleRect(float density) {
        RectF box = getBoundingBox(density);
        float stalkLength = 24f * density;
        float handleRadius = 14f * density;
        float hx = box.centerX();
        float hy = box.top - stalkLength;
        return new RectF(hx - handleRadius, hy - handleRadius, hx + handleRadius, hy + handleRadius);
    }

    public boolean containsRotateHandle(float docX, float docY, float density) {
        RectF box = getBoundingBox(density);
        float cx = box.centerX();
        float cy = box.centerY();
        float localX = docX;
        float localY = docY;
        if (rotationAngle != 0f) {
            float rad = (float) Math.toRadians(-rotationAngle);
            float cos = (float) Math.cos(rad);
            float sin = (float) Math.sin(rad);
            float dx = docX - cx;
            float dy = docY - cy;
            localX = cx + (dx * cos - dy * sin);
            localY = cy + (dx * sin + dy * cos);
        }
        float stalkLength = 24f * density;
        float touchRadius = 20f * density;
        float hx = box.centerX();
        float hy = box.top - stalkLength;
        return Math.hypot(localX - hx, localY - hy) <= touchRadius;
    }

    @NonNull
    public Paint getTextPaint() {
        if (textPaint == null) {
            textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        }
        return textPaint;
    }

    @NonNull
    public Paint getBackgroundPaint() {
        if (bgPaint == null) {
            bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            bgPaint.setStyle(Paint.Style.FILL);
        }
        return bgPaint;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        draw(canvas, getTextPaint(), getBackgroundPaint(), 2.0f);
    }

    public void draw(@NonNull Canvas canvas, @NonNull Paint tPaint, @NonNull Paint bPaint, float density) {
        tPaint.setTextSize(textSize);
        tPaint.setTypeface(getTypeface(null));
        tPaint.setFakeBoldText("bold_condensed".equalsIgnoreCase(fontFamily) || "sans".equalsIgnoreCase(fontFamily));
        tPaint.getTextBounds(text, 0, text.length(), bounds);

        RectF box = getBoundingBox(density);
        boundingBox.set(box);

        canvas.save();
        float cx = box.centerX();
        float cy = box.centerY();
        canvas.rotate(rotationAngle, cx, cy);

        if (hasBackground) {
            bPaint.setColor(backgroundColor);
            canvas.drawRoundRect(box, 6f * density, 6f * density, bPaint);
        }
        tPaint.setColor(textColor);
        canvas.drawText(text, x, y, tPaint);

        canvas.restore();
    }

    @Override
    public void transform(@NonNull Matrix matrix) {
        if (matrix == null) return;
        float cx = (boundingBox.width() > 0) ? boundingBox.centerX() : (x + bounds.width() / 2f);
        float cy = (boundingBox.height() > 0) ? boundingBox.centerY() : (y - bounds.height() / 2f);
        float[] centerPt = new float[]{cx, cy};
        matrix.mapPoints(centerPt);

        float[] textVec = new float[]{0f, textSize};
        matrix.mapVectors(textVec);
        this.textSize = Math.max(8f * 2.0f, (float) Math.hypot(textVec[0], textVec[1]));

        float[] rotVec = new float[]{1f, 0f};
        matrix.mapVectors(rotVec);
        float matrixRotationDeg = (float) Math.toDegrees(Math.atan2(rotVec[1], rotVec[0]));
        setRotationAngle(getRotationAngle() + matrixRotationDeg);

        Paint measurePaint = getTextPaint();
        measurePaint.setTextSize(textSize);
        measurePaint.setTypeface(getTypeface(null));
        measurePaint.setFakeBoldText("bold_condensed".equalsIgnoreCase(fontFamily) || "sans".equalsIgnoreCase(fontFamily));
        measurePaint.getTextBounds(text, 0, text.length(), bounds);

        float pad = 10f * 2.0f;
        float boxWidth = bounds.width() + 2 * pad;
        float boxHeight = bounds.height() + 2 * pad;

        float newBoxLeft = centerPt[0] - boxWidth / 2f;
        float newBoxBottom = centerPt[1] + boxHeight / 2f;
        this.x = newBoxLeft + pad;
        this.y = newBoxBottom - pad;
        this.boundingBox.set(newBoxLeft, newBoxBottom - boxHeight, newBoxLeft + boxWidth, newBoxBottom);
    }

    @Override
    public void drawSelectionHandles(@NonNull Canvas canvas, float density) {
        if (selectionBoxPaint == null) {
            selectionBoxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            selectionBoxPaint.setStyle(Paint.Style.STROKE);
            selectionBoxPaint.setColor(Color.parseColor("#10B981"));
            selectionBoxPaint.setPathEffect(new DashPathEffect(new float[]{10f, 10f}, 0));

            handleFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            handleFillPaint.setStyle(Paint.Style.FILL);
            handleFillPaint.setColor(Color.WHITE);

            handleStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            handleStrokePaint.setStyle(Paint.Style.STROKE);
            handleStrokePaint.setColor(Color.parseColor("#10B981"));

            rotateIconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            rotateIconPaint.setStyle(Paint.Style.STROKE);
            rotateIconPaint.setColor(Color.parseColor("#10B981"));
            rotateIconPaint.setStrokeCap(Paint.Cap.ROUND);
        }

        selectionBoxPaint.setStrokeWidth(2f * density);
        handleStrokePaint.setStrokeWidth(2.5f * density);
        rotateIconPaint.setStrokeWidth(2f * density);

        RectF box = getBoundingBox(density);

        canvas.save();
        float cx = box.centerX();
        float cy = box.centerY();
        canvas.rotate(rotationAngle, cx, cy);

        canvas.drawRoundRect(box, 4f * density, 4f * density, selectionBoxPaint);

        // 1. Top stalk extending 24dp above top center
        float stalkLength = 24f * density;
        float topCenterX = box.centerX();
        float topCenterY = box.top;
        float stalkEndY = topCenterY - stalkLength;
        canvas.drawLine(topCenterX, topCenterY, topCenterX, stalkEndY, handleStrokePaint);

        // 2. Rotation handle circle at top of stalk
        float rotRadius = 12f * density;
        canvas.drawCircle(topCenterX, stalkEndY, rotRadius, handleFillPaint);
        canvas.drawCircle(topCenterX, stalkEndY, rotRadius, handleStrokePaint);

        // 3. Subtle curved rotate icon inside handle circle
        float iconR = 5f * density;
        RectF arcBox = new RectF(topCenterX - iconR, stalkEndY - iconR, topCenterX + iconR, stalkEndY + iconR);
        canvas.drawArc(arcBox, 45f, 270f, false, rotateIconPaint);
        float angleRad = (float) Math.toRadians(45);
        float ax = topCenterX + (float) (iconR * Math.cos(angleRad));
        float ay = stalkEndY + (float) (iconR * Math.sin(angleRad));
        Path arrow = new Path();
        float arrowSize = 3.5f * density;
        arrow.moveTo(ax, ay - arrowSize);
        arrow.lineTo(ax, ay);
        arrow.lineTo(ax + arrowSize, ay);
        canvas.drawPath(arrow, rotateIconPaint);

        // 4. Corner resize handle at bottom-right
        float handleRadius = 12f * density;
        canvas.drawCircle(box.right, box.bottom, handleRadius, handleFillPaint);
        canvas.drawCircle(box.right, box.bottom, handleRadius, handleStrokePaint);

        // 5. Subtle corner anchor dots
        float dotRadius = 4f * density;
        canvas.drawCircle(box.left, box.top, dotRadius, handleFillPaint);
        canvas.drawCircle(box.left, box.top, dotRadius, handleStrokePaint);
        canvas.drawCircle(box.right, box.top, dotRadius, handleFillPaint);
        canvas.drawCircle(box.right, box.top, dotRadius, handleStrokePaint);
        canvas.drawCircle(box.left, box.bottom, dotRadius, handleFillPaint);
        canvas.drawCircle(box.left, box.bottom, dotRadius, handleStrokePaint);

        canvas.restore();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TextAnnotation that = (TextAnnotation) o;
        return Float.compare(that.x, x) == 0 &&
                Float.compare(that.y, y) == 0 &&
                color == that.color &&
                textColor == that.textColor &&
                Float.compare(that.textSize, textSize) == 0 &&
                hasBackground == that.hasBackground &&
                backgroundColor == that.backgroundColor &&
                Float.compare(that.rotationAngle, rotationAngle) == 0 &&
                timestamp == that.timestamp &&
                Objects.equals(text, that.text) &&
                Objects.equals(fontFamily, that.fontFamily);
    }

    @Override
    public int hashCode() {
        int result = text != null ? text.hashCode() : 0;
        result = 31 * result + (x != +0.0f ? Float.floatToIntBits(x) : 0);
        result = 31 * result + (y != +0.0f ? Float.floatToIntBits(y) : 0);
        result = 31 * result + color;
        result = 31 * result + textColor;
        result = 31 * result + (textSize != +0.0f ? Float.floatToIntBits(textSize) : 0);
        result = 31 * result + (fontFamily != null ? fontFamily.hashCode() : 0);
        result = 31 * result + (hasBackground ? 1 : 0);
        result = 31 * result + backgroundColor;
        result = 31 * result + (rotationAngle != +0.0f ? Float.floatToIntBits(rotationAngle) : 0);
        result = 31 * result + (int) (timestamp ^ (timestamp >>> 32));
        return result;
    }

    @NonNull
    @Override
    public String toString() {
        return "TextAnnotation{" +
                "text='" + text + '\'' +
                ", x=" + x +
                ", y=" + y +
                ", textSize=" + textSize +
                ", font='" + fontFamily + '\'' +
                ", rotation=" + rotationAngle +
                ", isSelected=" + isSelected +
                ", timestamp=" + timestamp +
                '}';
    }
}
