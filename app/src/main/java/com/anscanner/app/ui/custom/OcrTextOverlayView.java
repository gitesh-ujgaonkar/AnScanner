package com.anscanner.app.ui.custom;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.mlkit.vision.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Interactive in-place OCR text overlay view that renders selectable, translucent bounding boxes
 * directly over recognized text on top of a PDF page or image.
 *
 * <p>Features:
 * <ul>
 *   <li>Maps Google ML Kit {@link Text.TextBlock} coordinates to display coordinates.</li>
 *   <li>Draws subtle translucent bounding outlines around all detected text blocks.</li>
 *   <li>Highlights the currently selected block with a prominent mint green fill and stroke.</li>
 *   <li>Dispatches selection change callbacks to drive the floating action bar.</li>
 *   <li>Allows extracting individual selected text or all page text.</li>
 * </ul>
 * </p>
 */
public class OcrTextOverlayView extends View {

    public interface OnTextSelectedListener {
        void onBlockSelected(@NonNull Text.TextBlock block, @NonNull String text);
        void onSelectionCleared();
    }

    private Text visionText;
    private int bitmapWidth = 0;
    private int bitmapHeight = 0;
    private RectF targetRect = null;

    private final Paint unselectedFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint unselectedStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectedFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectedStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final List<RectF> scaledBoxes = new ArrayList<>();
    private int selectedIndex = -1;
    private float cornerRadius = 0f;

    private OnTextSelectedListener selectionListener;

    public OcrTextOverlayView(Context context) {
        this(context, null);
    }

    public OcrTextOverlayView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public OcrTextOverlayView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        float density = getResources().getDisplayMetrics().density;
        cornerRadius = 6f * density;

        // Subtle unselected outline
        unselectedFillPaint.setStyle(Paint.Style.FILL);
        unselectedFillPaint.setColor(Color.parseColor("#2610B981")); // 15% mint green

        unselectedStrokePaint.setStyle(Paint.Style.STROKE);
        unselectedStrokePaint.setStrokeWidth(1.5f * density);
        unselectedStrokePaint.setColor(Color.parseColor("#8010B981")); // 50% mint green

        // Prominent active selection
        selectedFillPaint.setStyle(Paint.Style.FILL);
        selectedFillPaint.setColor(Color.parseColor("#5910B981")); // 35% mint green

        selectedStrokePaint.setStyle(Paint.Style.STROKE);
        selectedStrokePaint.setStrokeWidth(2.5f * density);
        selectedStrokePaint.setColor(Color.parseColor("#FF10B981")); // 100% solid mint green
    }

    public void setOnTextSelectedListener(@Nullable OnTextSelectedListener listener) {
        this.selectionListener = listener;
    }

    /**
     * Supplies recognized OCR text and base bitmap dimensions for coordinate mapping.
     */
    public void setVisionText(@Nullable Text text, int width, int height) {
        this.visionText = text;
        this.bitmapWidth = width;
        this.bitmapHeight = height;
        this.selectedIndex = -1;
        updateScaledBoxes();
        invalidate();
    }

    /**
     * Sets target viewport rectangle within this view (e.g. from TouchImageView#getDisplayRect).
     */
    public void setTargetRect(@Nullable RectF rect) {
        this.targetRect = rect;
        updateScaledBoxes();
        invalidate();
    }

    /**
     * Clears all vision text, geometry, and active selections.
     */
    public void clear() {
        this.visionText = null;
        this.bitmapWidth = 0;
        this.bitmapHeight = 0;
        this.targetRect = null;
        this.selectedIndex = -1;
        this.scaledBoxes.clear();
        if (selectionListener != null) {
            selectionListener.onSelectionCleared();
        }
        invalidate();
    }

    @Nullable
    public String getSelectedText() {
        if (visionText != null && selectedIndex >= 0 && selectedIndex < visionText.getTextBlocks().size()) {
            return visionText.getTextBlocks().get(selectedIndex).getText();
        }
        return null;
    }

    @NonNull
    public String getAllText() {
        if (visionText == null || visionText.getTextBlocks().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Text.TextBlock block : visionText.getTextBlocks()) {
            String txt = block.getText();
            if (txt != null && !txt.trim().isEmpty()) {
                if (sb.length() > 0) sb.append("\n\n");
                sb.append(txt.trim());
            }
        }
        return sb.toString();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateScaledBoxes();
    }

    private void updateScaledBoxes() {
        scaledBoxes.clear();
        if (visionText == null || bitmapWidth <= 0 || bitmapHeight <= 0) {
            return;
        }

        float availableWidth = (targetRect != null) ? targetRect.width() : getWidth();
        float availableHeight = (targetRect != null) ? targetRect.height() : getHeight();

        if (availableWidth <= 0 || availableHeight <= 0) {
            return;
        }

        float scale = Math.min(availableWidth / (float) bitmapWidth, availableHeight / (float) bitmapHeight);
        float contentLeft = (targetRect != null) ? targetRect.left : 0f;
        float contentTop = (targetRect != null) ? targetRect.top : 0f;

        float offsetX = contentLeft + (availableWidth - (bitmapWidth * scale)) / 2f;
        float offsetY = contentTop + (availableHeight - (bitmapHeight * scale)) / 2f;

        for (Text.TextBlock block : visionText.getTextBlocks()) {
            Rect box = block.getBoundingBox();
            if (box != null) {
                RectF scaled = new RectF(
                        offsetX + box.left * scale,
                        offsetY + box.top * scale,
                        offsetX + box.right * scale,
                        offsetY + box.bottom * scale
                );
                scaledBoxes.add(scaled);
            } else {
                scaledBoxes.add(new RectF());
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (visionText == null || scaledBoxes.isEmpty()) {
            return;
        }

        if (scaledBoxes.size() != visionText.getTextBlocks().size()) {
            updateScaledBoxes();
        }

        for (int i = 0; i < scaledBoxes.size(); i++) {
            RectF box = scaledBoxes.get(i);
            if (box == null || box.isEmpty()) {
                continue;
            }

            if (i == selectedIndex) {
                canvas.drawRoundRect(box, cornerRadius, cornerRadius, selectedFillPaint);
                canvas.drawRoundRect(box, cornerRadius, cornerRadius, selectedStrokePaint);
            } else {
                canvas.drawRoundRect(box, cornerRadius, cornerRadius, unselectedFillPaint);
                canvas.drawRoundRect(box, cornerRadius, cornerRadius, unselectedStrokePaint);
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (getVisibility() != View.VISIBLE || visionText == null || scaledBoxes.isEmpty()) {
            return super.onTouchEvent(event);
        }

        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            return true;
        }

        if (event.getAction() == MotionEvent.ACTION_UP) {
            float x = event.getX();
            float y = event.getY();
            boolean hit = false;
            List<Text.TextBlock> blocks = visionText.getTextBlocks();

            for (int i = 0; i < scaledBoxes.size() && i < blocks.size(); i++) {
                RectF box = scaledBoxes.get(i);
                if (box != null && box.contains(x, y)) {
                    selectedIndex = i;
                    invalidate();
                    hit = true;
                    if (selectionListener != null) {
                        selectionListener.onBlockSelected(blocks.get(i), blocks.get(i).getText());
                    }
                    break;
                }
            }

            if (!hit) {
                selectedIndex = -1;
                invalidate();
                if (selectionListener != null) {
                    selectionListener.onSelectionCleared();
                }
            }
            return true;
        }

        return super.onTouchEvent(event);
    }
}
