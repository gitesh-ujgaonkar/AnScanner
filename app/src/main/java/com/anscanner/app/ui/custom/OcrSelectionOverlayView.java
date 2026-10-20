package com.anscanner.app.ui.custom;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.anscanner.app.R;
import com.anscanner.app.model.ocr.OcrElement;
import com.anscanner.app.model.ocr.OcrPage;
import com.anscanner.app.ui.dialog.OcrTextBottomSheetDialog;
import com.google.android.material.button.MaterialButton;

import java.util.List;

/**
 * Interactive Google Lens-style OCR overlay view supporting word/line selection,
 * fluid drag-to-select, handle pin controls, visual bounding highlights (#4000FF88),
 * a contextual floating action toolbar, and full-text bottom sheet modal.
 */
public class OcrSelectionOverlayView extends FrameLayout {

    public interface OnSelectionChangeListener {
        void onSelectionChanged(@NonNull String selectedText, boolean hasSelection);
    }

    private OcrPage ocrPage;
    private RectF targetRect;

    private int selectionStart = -1;
    private int selectionEnd = -1;
    private int dragAnchorIndex = -1;

    private boolean isDraggingStartHandle = false;
    private boolean isDraggingEndHandle = false;
    private boolean isDraggingSelection = false;

    private final Matrix transformMatrix = new Matrix();
    private final Matrix inverseMatrix = new Matrix();
    private final RectF tempMappedRect = new RectF();
    private final RectF selectionBoundingRect = new RectF();

    private final Paint wordOutlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint highlightFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint highlightStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleInnerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float density;
    private float cornerRadius;
    private float handleRadius;
    private float handleTouchRadius;

    private float startHandleX = -1f;
    private float startHandleY = -1f;
    private float endHandleX = -1f;
    private float endHandleY = -1f;

    private View floatingToolbarCard;
    private MaterialButton btnFloatingCopy;
    private MaterialButton btnFloatingSelectAll;
    private MaterialButton btnFloatingShare;
    private ImageButton btnFloatingViewAll;

    private OnSelectionChangeListener selectionChangeListener;

    public OcrSelectionOverlayView(@NonNull Context context) {
        this(context, null);
    }

    public OcrSelectionOverlayView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public OcrSelectionOverlayView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(@NonNull Context context) {
        setWillNotDraw(false);
        density = getResources().getDisplayMetrics().density;
        cornerRadius = 4f * density;
        handleRadius = 8f * density;
        handleTouchRadius = 28f * density;

        // Subtle unselected word detection outlines
        wordOutlinePaint.setStyle(Paint.Style.STROKE);
        wordOutlinePaint.setStrokeWidth(1f * density);
        wordOutlinePaint.setColor(Color.parseColor("#3310B981")); // Translucent mint

        // Smooth translucent highlight (#4000FF88 or system mint)
        highlightFillPaint.setStyle(Paint.Style.FILL);
        highlightFillPaint.setColor(Color.parseColor("#4000FF88"));

        highlightStrokePaint.setStyle(Paint.Style.STROKE);
        highlightStrokePaint.setStrokeWidth(1.5f * density);
        highlightStrokePaint.setColor(Color.parseColor("#8000FF88"));

        // Selection pin handles
        handlePaint.setStyle(Paint.Style.FILL);
        handlePaint.setColor(Color.parseColor("#FF007AFF")); // Vibrant blue handle

        handleInnerPaint.setStyle(Paint.Style.FILL);
        handleInnerPaint.setColor(Color.WHITE);

        // Inflate floating action toolbar card
        floatingToolbarCard = LayoutInflater.from(context).inflate(R.layout.layout_ocr_floating_toolbar, this, false);
        addView(floatingToolbarCard);
        floatingToolbarCard.setVisibility(View.GONE);

        btnFloatingCopy = floatingToolbarCard.findViewById(R.id.btnFloatingCopy);
        btnFloatingSelectAll = floatingToolbarCard.findViewById(R.id.btnFloatingSelectAll);
        btnFloatingShare = floatingToolbarCard.findViewById(R.id.btnFloatingShare);
        btnFloatingViewAll = floatingToolbarCard.findViewById(R.id.btnFloatingViewAll);

        setupToolbarActions();
    }

    private void setupToolbarActions() {
        if (btnFloatingCopy != null) {
            btnFloatingCopy.setOnClickListener(v -> copySelectedTextToClipboard());
        }
        if (btnFloatingSelectAll != null) {
            btnFloatingSelectAll.setOnClickListener(v -> selectAll());
        }
        if (btnFloatingShare != null) {
            btnFloatingShare.setOnClickListener(v -> shareSelectedText());
        }
        if (btnFloatingViewAll != null) {
            btnFloatingViewAll.setOnClickListener(v -> showFullTextBottomSheet());
        }
    }

    public void setOnSelectionChangeListener(@Nullable OnSelectionChangeListener listener) {
        this.selectionChangeListener = listener;
    }

    public void setOcrPage(@Nullable OcrPage page) {
        this.ocrPage = page;
        this.selectionStart = -1;
        this.selectionEnd = -1;
        updateTransformMatrix();
        invalidate();
        updateToolbarState();
    }

    public void setTargetRect(@Nullable RectF targetRect) {
        this.targetRect = targetRect;
        updateTransformMatrix();
        invalidate();
        updateToolbarPosition();
    }

    @Nullable
    public OcrPage getOcrPage() {
        return ocrPage;
    }

    public int getSelectionStart() {
        return selectionStart;
    }

    public int getSelectionEnd() {
        return selectionEnd;
    }

    public void setSelection(int start, int end) {
        if (ocrPage == null || ocrPage.getElements().isEmpty()) {
            clearSelection();
            return;
        }
        int max = ocrPage.getElements().size() - 1;
        this.selectionStart = Math.max(0, Math.min(start, max));
        this.selectionEnd = Math.max(0, Math.min(end, max));
        if (this.selectionStart > this.selectionEnd) {
            int tmp = this.selectionStart;
            this.selectionStart = this.selectionEnd;
            this.selectionEnd = tmp;
        }
        invalidate();
        updateToolbarState();
    }

    public void selectAll() {
        if (ocrPage == null || ocrPage.getElements().isEmpty()) {
            return;
        }
        setSelection(0, ocrPage.getElements().size() - 1);
    }

    public void clearSelection() {
        this.selectionStart = -1;
        this.selectionEnd = -1;
        this.dragAnchorIndex = -1;
        invalidate();
        updateToolbarState();
    }

    public boolean hasSelection() {
        return ocrPage != null && selectionStart >= 0 && selectionEnd >= 0 && selectionStart <= selectionEnd;
    }

    @NonNull
    public String getSelectedText() {
        if (!hasSelection() || ocrPage == null) {
            return "";
        }
        return ocrPage.getSelectedText(selectionStart, selectionEnd);
    }

    @NonNull
    public String getAllText() {
        return ocrPage != null ? ocrPage.getFullText() : "";
    }

    public void copySelectedTextToClipboard() {
        String text = getSelectedText();
        if (text.isEmpty()) {
            text = getAllText();
        }
        if (text.isEmpty()) {
            Toast.makeText(getContext(), R.string.ocr_nothing_selected, Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("Extracted Text", text));
            Toast.makeText(getContext(), R.string.ocr_copied, Toast.LENGTH_SHORT).show();
        }
    }

    public void shareSelectedText() {
        String text = getSelectedText();
        if (text.isEmpty()) {
            text = getAllText();
        }
        if (text.isEmpty()) {
            Toast.makeText(getContext(), R.string.ocr_nothing_selected, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, "Extracted Text");
        shareIntent.putExtra(Intent.EXTRA_TEXT, text);
        getContext().startActivity(Intent.createChooser(shareIntent, getContext().getString(R.string.ocr_dialog_share)));
    }

    public void showFullTextBottomSheet() {
        if (ocrPage == null || ocrPage.isEmpty()) {
            Toast.makeText(getContext(), R.string.ocr_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        OcrTextBottomSheetDialog.show(getContext(), ocrPage, newText -> {
            if (ocrPage != null) {
                ocrPage.setFullText(newText);
            }
        });
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateTransformMatrix();
    }

    private void updateTransformMatrix() {
        if (ocrPage == null || ocrPage.getBitmapWidth() <= 0 || ocrPage.getBitmapHeight() <= 0) {
            transformMatrix.reset();
            inverseMatrix.reset();
            return;
        }

        float availableWidth = (targetRect != null && targetRect.width() > 0) ? targetRect.width() : getWidth();
        float availableHeight = (targetRect != null && targetRect.height() > 0) ? targetRect.height() : getHeight();

        if (availableWidth <= 0 || availableHeight <= 0) {
            return;
        }

        float scale = Math.min(availableWidth / (float) ocrPage.getBitmapWidth(), availableHeight / (float) ocrPage.getBitmapHeight());
        float contentLeft = (targetRect != null) ? targetRect.left : 0f;
        float contentTop = (targetRect != null) ? targetRect.top : 0f;

        float offsetX = contentLeft + (availableWidth - (ocrPage.getBitmapWidth() * scale)) / 2f;
        float offsetY = contentTop + (availableHeight - (ocrPage.getBitmapHeight() * scale)) / 2f;

        transformMatrix.reset();
        transformMatrix.postScale(scale, scale);
        transformMatrix.postTranslate(offsetX, offsetY);
        transformMatrix.invert(inverseMatrix);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (ocrPage == null || ocrPage.getElements().isEmpty()) {
            return;
        }

        List<OcrElement> elements = ocrPage.getElements();

        // 1. Draw subtle unselected word outlines (Google Lens background mode)
        for (int i = 0; i < elements.size(); i++) {
            if (hasSelection() && i >= selectionStart && i <= selectionEnd) {
                continue; // Drawn in highlight pass below
            }
            OcrElement elem = elements.get(i);
            mapNormalizedRectToScreen(elem.getNormalizedRect(), tempMappedRect);
            if (!tempMappedRect.isEmpty()) {
                canvas.drawRoundRect(tempMappedRect, cornerRadius, cornerRadius, wordOutlinePaint);
            }
        }

        // 2. Draw smooth translucent highlights (#4000FF88) over selected word range
        if (hasSelection()) {
            selectionBoundingRect.setEmpty();

            for (int i = selectionStart; i <= selectionEnd && i < elements.size(); i++) {
                OcrElement elem = elements.get(i);
                mapNormalizedRectToScreen(elem.getNormalizedRect(), tempMappedRect);
                if (!tempMappedRect.isEmpty()) {
                    canvas.drawRoundRect(tempMappedRect, cornerRadius, cornerRadius, highlightFillPaint);
                    canvas.drawRoundRect(tempMappedRect, cornerRadius, cornerRadius, highlightStrokePaint);

                    if (selectionBoundingRect.isEmpty()) {
                        selectionBoundingRect.set(tempMappedRect);
                    } else {
                        selectionBoundingRect.union(tempMappedRect);
                    }
                }
            }

            // 3. Render selection handle pins (Start & End)
            if (selectionStart < elements.size()) {
                mapNormalizedRectToScreen(elements.get(selectionStart).getNormalizedRect(), tempMappedRect);
                startHandleX = tempMappedRect.left;
                startHandleY = tempMappedRect.top;

                // Vertical stem & circle pin
                canvas.drawLine(startHandleX, tempMappedRect.top, startHandleX, tempMappedRect.bottom, handlePaint);
                canvas.drawCircle(startHandleX, startHandleY, handleRadius, handlePaint);
                canvas.drawCircle(startHandleX, startHandleY, handleRadius * 0.4f, handleInnerPaint);
            }

            if (selectionEnd < elements.size()) {
                mapNormalizedRectToScreen(elements.get(selectionEnd).getNormalizedRect(), tempMappedRect);
                endHandleX = tempMappedRect.right;
                endHandleY = tempMappedRect.bottom;

                // Vertical stem & circle pin
                canvas.drawLine(endHandleX, tempMappedRect.top, endHandleX, tempMappedRect.bottom, handlePaint);
                canvas.drawCircle(endHandleX, endHandleY, handleRadius, handlePaint);
                canvas.drawCircle(endHandleX, endHandleY, handleRadius * 0.4f, handleInnerPaint);
            }
        }
    }

    private void mapNormalizedRectToScreen(@NonNull RectF norm, @NonNull RectF out) {
        if (ocrPage == null || norm.isEmpty()) {
            out.setEmpty();
            return;
        }
        float pixelLeft = norm.left * ocrPage.getBitmapWidth();
        float pixelTop = norm.top * ocrPage.getBitmapHeight();
        float pixelRight = norm.right * ocrPage.getBitmapWidth();
        float pixelBottom = norm.bottom * ocrPage.getBitmapHeight();

        out.set(pixelLeft, pixelTop, pixelRight, pixelBottom);
        transformMatrix.mapRect(out);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (ocrPage == null || ocrPage.getElements().isEmpty() || getVisibility() != View.VISIBLE) {
            return super.onTouchEvent(event);
        }

        float x = event.getX();
        float y = event.getY();

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                if (hasSelection()) {
                    if (Math.hypot(x - startHandleX, y - startHandleY) <= handleTouchRadius) {
                        isDraggingStartHandle = true;
                        return true;
                    }
                    if (Math.hypot(x - endHandleX, y - endHandleY) <= handleTouchRadius) {
                        isDraggingEndHandle = true;
                        return true;
                    }
                }

                int hitIndex = findElementAtOrNear(x, y);
                if (hitIndex >= 0) {
                    dragAnchorIndex = hitIndex;
                    selectionStart = hitIndex;
                    selectionEnd = hitIndex;
                    isDraggingSelection = true;
                    invalidate();
                    updateToolbarState();
                    return true;
                } else {
                    clearSelection();
                    return true;
                }

            case MotionEvent.ACTION_MOVE:
                int currentHit = findElementAtOrNear(x, y);
                if (currentHit >= 0) {
                    if (isDraggingStartHandle) {
                        if (currentHit <= selectionEnd) {
                            selectionStart = currentHit;
                            invalidate();
                            updateToolbarState();
                        }
                    } else if (isDraggingEndHandle) {
                        if (currentHit >= selectionStart) {
                            selectionEnd = currentHit;
                            invalidate();
                            updateToolbarState();
                        }
                    } else if (isDraggingSelection && dragAnchorIndex >= 0) {
                        selectionStart = Math.min(dragAnchorIndex, currentHit);
                        selectionEnd = Math.max(dragAnchorIndex, currentHit);
                        invalidate();
                        updateToolbarState();
                    }
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                isDraggingStartHandle = false;
                isDraggingEndHandle = false;
                isDraggingSelection = false;
                updateToolbarState();
                return true;
        }

        return super.onTouchEvent(event);
    }

    private int findElementAtOrNear(float screenX, float screenY) {
        if (ocrPage == null || ocrPage.getElements().isEmpty()) {
            return -1;
        }

        float[] pts = new float[]{ screenX, screenY };
        inverseMatrix.mapPoints(pts);
        float bmpX = pts[0];
        float bmpY = pts[1];

        List<OcrElement> elements = ocrPage.getElements();

        // Direct containment check
        for (int i = 0; i < elements.size(); i++) {
            OcrElement elem = elements.get(i);
            if (elem.getBoundingBox().contains((int) bmpX, (int) bmpY)) {
                return i;
            }
        }

        // Proximity search (within ~24dp)
        float maxDistBmp = 24f * density * (inverseMatrix.mapRadius(1f));
        int closestIndex = -1;
        float minDistance = Float.MAX_VALUE;

        for (int i = 0; i < elements.size(); i++) {
            OcrElement elem = elements.get(i);
            float cx = elem.getBoundingBox().exactCenterX();
            float cy = elem.getBoundingBox().exactCenterY();
            float dist = (float) Math.hypot(bmpX - cx, bmpY - cy);
            if (dist < minDistance && dist <= maxDistBmp) {
                minDistance = dist;
                closestIndex = i;
            }
        }

        return closestIndex;
    }

    private void updateToolbarState() {
        if (hasSelection()) {
            floatingToolbarCard.setVisibility(View.VISIBLE);
            updateToolbarPosition();
            if (selectionChangeListener != null) {
                selectionChangeListener.onSelectionChanged(getSelectedText(), true);
            }
        } else {
            floatingToolbarCard.setVisibility(View.GONE);
            if (selectionChangeListener != null) {
                selectionChangeListener.onSelectionChanged("", false);
            }
        }
    }

    private void updateToolbarPosition() {
        if (!hasSelection() || floatingToolbarCard.getVisibility() != View.VISIBLE) {
            return;
        }

        floatingToolbarCard.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);
        int toolbarWidth = floatingToolbarCard.getMeasuredWidth();
        int toolbarHeight = floatingToolbarCard.getMeasuredHeight();

        float targetX = selectionBoundingRect.centerX() - (toolbarWidth / 2f);
        float targetY = selectionBoundingRect.top - toolbarHeight - (12f * density);

        // Clamp inside overlay view boundaries
        targetX = Math.max(12f * density, Math.min(targetX, getWidth() - toolbarWidth - (12f * density)));
        if (targetY < (12f * density)) {
            targetY = selectionBoundingRect.bottom + (12f * density);
        }

        floatingToolbarCard.setX(targetX);
        floatingToolbarCard.setY(targetY);
    }
}
