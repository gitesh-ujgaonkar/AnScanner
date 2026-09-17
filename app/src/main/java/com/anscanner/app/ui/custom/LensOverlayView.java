package com.anscanner.app.ui.custom;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.anscanner.app.R;
import com.google.mlkit.vision.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Interactive "Google Lens" style overlay view that renders translucent bounding boxes
 * over recognized OCR text blocks on top of an image or PDF preview.
 *
 * <p>Supports:
 * <ul>
 *   <li>Dynamic coordinate scaling between source bitmap dimensions and screen coordinates.</li>
 *   <li>Optional target rect alignment for nested previews (e.g. PDF view holders).</li>
 *   <li>Visual selection feedback (translucent mint highlight on active block).</li>
 *   <li>Instant clipboard copying of selected text block with preview toast.</li>
 *   <li>Auto-dismiss on tapping outside selectable text blocks.</li>
 * </ul>
 * </p>
 */
public class LensOverlayView extends View {

    public interface OnDismissListener {
        void onDismiss();
    }

    private Text visionText;
    private int bitmapWidth = 0;
    private int bitmapHeight = 0;
    private RectF targetRect = null;

    private final Paint boxFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint boxStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint highlightFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint highlightStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final List<RectF> scaledBoxes = new ArrayList<>();
    private int selectedBlockIndex = -1;
    private float cornerRadius = 0f;

    private OnDismissListener dismissListener;

    public LensOverlayView(Context context) {
        this(context, null);
    }

    public LensOverlayView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public LensOverlayView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        float density = getResources().getDisplayMetrics().density;
        cornerRadius = 6f * density;

        // Default unselected box: translucent white with subtle border
        boxFillPaint.setStyle(Paint.Style.FILL);
        boxFillPaint.setColor(Color.parseColor("#33FFFFFF"));

        boxStrokePaint.setStyle(Paint.Style.STROKE);
        boxStrokePaint.setStrokeWidth(1.5f * density);
        boxStrokePaint.setColor(Color.parseColor("#80FFFFFF"));

        // Selected active box: translucent mint green (#68D391) with prominent border
        highlightFillPaint.setStyle(Paint.Style.FILL);
        highlightFillPaint.setColor(Color.parseColor("#4D68D391"));

        highlightStrokePaint.setStyle(Paint.Style.STROKE);
        highlightStrokePaint.setStrokeWidth(2.5f * density);
        highlightStrokePaint.setColor(Color.parseColor("#68D391"));
    }

    public void setOnDismissListener(@Nullable OnDismissListener listener) {
        this.dismissListener = listener;
    }

    /**
     * Sets the OCR text result and source bitmap dimensions for coordinate mapping.
     */
    public void setVisionText(@Nullable Text text, int bitmapWidth, int bitmapHeight) {
        this.visionText = text;
        this.bitmapWidth = bitmapWidth;
        this.bitmapHeight = bitmapHeight;
        this.selectedBlockIndex = -1;
        updateScaledBoxes();
        invalidate();
    }

    /**
     * Sets an optional target rectangle within the view where the image is actually drawn.
     * If null, uses the view's full bounds.
     */
    public void setTargetRect(@Nullable RectF targetRect) {
        this.targetRect = targetRect;
        updateScaledBoxes();
        invalidate();
    }

    /**
     * Clears all vision text and bounding boxes.
     */
    public void clear() {
        this.visionText = null;
        this.bitmapWidth = 0;
        this.bitmapHeight = 0;
        this.targetRect = null;
        this.selectedBlockIndex = -1;
        this.scaledBoxes.clear();
        invalidate();
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

        // Calculate uniform scale factor to match centerInside / fitCenter
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

            if (i == selectedBlockIndex) {
                canvas.drawRoundRect(box, cornerRadius, cornerRadius, highlightFillPaint);
                canvas.drawRoundRect(box, cornerRadius, cornerRadius, highlightStrokePaint);
            } else {
                canvas.drawRoundRect(box, cornerRadius, cornerRadius, boxFillPaint);
                canvas.drawRoundRect(box, cornerRadius, cornerRadius, boxStrokePaint);
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
                    selectedBlockIndex = i;
                    invalidate();
                    copyBlockToClipboard(blocks.get(i));
                    hit = true;
                    break;
                }
            }

            if (!hit) {
                // Tapped outside any text block -> dismiss the overlay
                setVisibility(View.GONE);
                selectedBlockIndex = -1;
                invalidate();
                if (dismissListener != null) {
                    dismissListener.onDismiss();
                }
            }
            return true;
        }

        return super.onTouchEvent(event);
    }

    private void copyBlockToClipboard(Text.TextBlock block) {
        String text = block.getText();
        if (text == null) return;
        text = text.trim();
        if (text.isEmpty()) return;

        ClipboardManager clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            ClipData clip = ClipData.newPlainText("Extracted Text", text);
            clipboard.setPrimaryClip(clip);
        }

        String snippet = getSnippet(text);
        String message = getContext().getString(R.string.ocr_copied_snippet, snippet);
        Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
    }

    private String getSnippet(String text) {
        String singleLine = text.replaceAll("\\s+", " ").trim();
        String[] words = singleLine.split(" ");
        if (words.length <= 5) {
            return singleLine;
        }
        return words[0] + " " + words[1] + " " + words[2] + " " + words[3] + " " + words[4] + "…";
    }
}
