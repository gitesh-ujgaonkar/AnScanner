package com.anscanner.app.ui.custom;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

import com.anscanner.app.util.FontUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Interactive multi-mode canvas overlay supporting document-locked annotations:
 * <ul>
 *   <li>Internal coordinate space locked 1:1 to the document bitmap canvas</li>
 *   <li>Vector-accurate zoom & pan sync with {@link TouchImageView}</li>
 *   <li>Multi-touch gesture routing (2-finger pinch zooms without drawing stray lines)</li>
 *   <li>Freehand Pen & Highlighter drawing with configurable width & color</li>
 *   <li>Comprehensive Text styling: font family (Sans, Serif, Mono, Cursive), size, color, background toggle, tap-to-edit</li>
 *   <li>Signature customization: Black (#000000) default, color tinting, corner drag resize handles</li>
 *   <li>Undo, clear, and high-resolution bitmap flattening</li>
 * </ul>
 */
public class AnnotationDrawingView extends View {

    public enum ToolMode {
        NONE,
        PEN,
        HIGHLIGHTER,
        TEXT,
        SIGNATURE
    }

    public interface OnItemSelectionListener {
        void onTextItemTapped(@NonNull TextItem item);
        void onSignatureItemTapped(@NonNull SignatureItem item);
        void onSelectionCleared();
    }

    public static class Stroke {
        public final Path path;
        public final int color;
        public final float strokeWidth;
        public final boolean isHighlighter;

        public Stroke(Path path, int color, float strokeWidth, boolean isHighlighter) {
            this.path = path;
            this.color = color;
            this.strokeWidth = strokeWidth;
            this.isHighlighter = isHighlighter;
        }
    }

    public static class TextItem {
        public String text;
        public float x; // Document space X
        public float y; // Document space Y (baseline)
        public int color;
        public float textSize; // Document space text size
        public String fontFamily = "sans"; // "sans", "serif", "monospace", "cursive"
        public boolean hasBackground = true;
        public int backgroundColor = Color.parseColor("#B3000000"); // Semi-transparent dark
        public boolean isSelected = false;
        public final Rect bounds = new Rect();

        public TextItem(String text, float x, float y, int color, float textSize) {
            this.text = text;
            this.x = x;
            this.y = y;
            this.color = color;
            this.textSize = textSize;
        }

        public void setFontFamily(@Nullable String fontFamily) {
            this.fontFamily = (fontFamily != null && !fontFamily.trim().isEmpty()) ? fontFamily.trim() : "sans";
        }

        public void setTypeface(@Nullable Context context, @Nullable String fontFamily) {
            setFontFamily(fontFamily);
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

        public boolean contains(float docX, float docY, float density) {
            return getBoundingBox(density).contains(docX, docY);
        }

        public RectF getResizeHandleRect(float density) {
            RectF box = getBoundingBox(density);
            float handleRadius = 14f * density;
            return new RectF(
                    box.right - handleRadius,
                    box.bottom - handleRadius,
                    box.right + handleRadius,
                    box.bottom + handleRadius
            );
        }

        public boolean containsResizeHandle(float docX, float docY, float density) {
            return getResizeHandleRect(density).contains(docX, docY);
        }
    }

    public static class SignatureItem {
        public Bitmap bitmap;
        public float x; // Document space X
        public float y; // Document space Y
        public float width; // Document space width
        public float height; // Document space height
        public int tintColor = Color.BLACK; // Default solid black
        public boolean isSelected = false;

        public SignatureItem(Bitmap bitmap, float x, float y, float width, float height) {
            this.bitmap = bitmap;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.tintColor = Color.BLACK;
        }

        public RectF getBoundingBox(float density) {
            float pad = 6f * density;
            return new RectF(x - pad, y - pad, x + width + pad, y + height + pad);
        }

        public boolean contains(float docX, float docY, float density) {
            return getBoundingBox(density).contains(docX, docY);
        }

        public RectF getResizeHandleRect(float density) {
            RectF box = getBoundingBox(density);
            float handleRadius = 14f * density;
            return new RectF(
                    box.right - handleRadius,
                    box.bottom - handleRadius,
                    box.right + handleRadius,
                    box.bottom + handleRadius
            );
        }

        public boolean containsResizeHandle(float docX, float docY, float density) {
            return getResizeHandleRect(density).contains(docX, docY);
        }
    }

    // Action record for unified undo
    private enum ActionType { STROKE, TEXT, SIGNATURE }
    private static class AnnotationAction {
        final ActionType type;
        final Object item;
        AnnotationAction(ActionType type, Object item) {
            this.type = type;
            this.item = item;
        }
    }

    // Document Matrix synchronization
    private TouchImageView targetImageView;
    private final Matrix currentMatrix = new Matrix();
    private final Matrix inverseMatrix = new Matrix();
    private boolean isMultiTouching = false;
    private int documentWidth = 0;
    private int documentHeight = 0;

    // Collections
    private final List<Stroke> strokes = new ArrayList<>();
    private final List<TextItem> textItems = new ArrayList<>();
    private final List<SignatureItem> signatureItems = new ArrayList<>();
    private final List<AnnotationAction> actionHistory = new ArrayList<>();

    // Drawing in-progress
    private final Path currentPath = new Path();
    private final Paint penPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint highlighterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textBackgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint signaturePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint selectionBoxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private ToolMode currentToolMode = ToolMode.NONE;
    private int currentColor = Color.parseColor("#68D391"); // Default Mint Green
    private float currentStrokeWidth = 8f; // Document space default
    private float lastDocX;
    private float lastDocY;

    // Selection & Drag/Resize state
    private TextItem activeDraggingText = null;
    private SignatureItem activeDraggingSignature = null;
    private TextItem activeResizingText = null;
    private SignatureItem activeResizingSignature = null;
    private float dragOffsetX = 0f;
    private float dragOffsetY = 0f;
    private float initialTouchX = 0f;
    private float initialTouchY = 0f;
    private float initialWidth = 0f;
    private float initialHeight = 0f;
    private float initialTextSize = 0f;
    private float downScreenX = 0f;
    private float downScreenY = 0f;

    private OnItemSelectionListener itemSelectionListener;

    public AnnotationDrawingView(Context context) {
        super(context);
        init();
    }

    public AnnotationDrawingView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public AnnotationDrawingView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        penPaint.setStyle(Paint.Style.STROKE);
        penPaint.setStrokeJoin(Paint.Join.ROUND);
        penPaint.setStrokeCap(Paint.Cap.ROUND);
        penPaint.setDither(true);

        highlighterPaint.setStyle(Paint.Style.STROKE);
        highlighterPaint.setStrokeJoin(Paint.Join.ROUND);
        highlighterPaint.setStrokeCap(Paint.Cap.ROUND);
        highlighterPaint.setDither(true);
        highlighterPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_OVER));

        textPaint.setTextSize(getResources().getDisplayMetrics().density * 18f);
        textPaint.setFakeBoldText(true);

        textBackgroundPaint.setStyle(Paint.Style.FILL);
        textBackgroundPaint.setColor(Color.parseColor("#B3000000"));

        selectionBoxPaint.setStyle(Paint.Style.STROKE);
        selectionBoxPaint.setColor(Color.parseColor("#10B981"));
        selectionBoxPaint.setStrokeWidth(2f * getResources().getDisplayMetrics().density);
        selectionBoxPaint.setPathEffect(new DashPathEffect(new float[]{10f, 10f}, 0));

        handleFillPaint.setStyle(Paint.Style.FILL);
        handleFillPaint.setColor(Color.WHITE);

        handleStrokePaint.setStyle(Paint.Style.STROKE);
        handleStrokePaint.setColor(Color.parseColor("#10B981"));
        handleStrokePaint.setStrokeWidth(2.5f * getResources().getDisplayMetrics().density);
    }

    /**
     * Binds this annotation layer to the underlying zoomable TouchImageView.
     * Synchronizes zoom/pan matrix and routes multi-touch pinch gestures seamlessly.
     */
    public void bindImageView(@Nullable TouchImageView iv) {
        this.targetImageView = iv;
        if (iv != null) {
            iv.setOnMatrixChangeListener(rect -> {
                updateMatrix();
                invalidate();
            });
            updateMatrix();
        }
    }

    public void setDocumentDimensions(int width, int height) {
        this.documentWidth = width;
        this.documentHeight = height;
        updateMatrix();
        invalidate();
    }

    public void setOnItemSelectionListener(@Nullable OnItemSelectionListener listener) {
        this.itemSelectionListener = listener;
    }

    private void updateMatrix() {
        if (targetImageView != null) {
            currentMatrix.set(targetImageView.getImageMatrix());
            if (!currentMatrix.invert(inverseMatrix)) {
                inverseMatrix.reset();
            }
        } else {
            currentMatrix.reset();
            inverseMatrix.reset();
        }
    }

    public void setToolMode(ToolMode mode) {
        this.currentToolMode = mode;
        clearSelection();
        invalidate();
    }

    public ToolMode getToolMode() {
        return currentToolMode;
    }

    public void setStrokeColor(int color) {
        this.currentColor = color;
        // Also update color of currently selected text or signature item
        TextItem selText = getSelectedTextItem();
        if (selText != null) {
            selText.color = color;
            invalidate();
        }
        SignatureItem selSig = getSelectedSignatureItem();
        if (selSig != null) {
            selSig.tintColor = color;
            invalidate();
        }
    }

    public int getStrokeColor() {
        return currentColor;
    }

    public void setStrokeWidth(float width) {
        this.currentStrokeWidth = Math.max(2f, width);
    }

    public float getStrokeWidth() {
        return currentStrokeWidth;
    }

    public void addTextAnnotation(String text, int color, float textSizeSp) {
        addTextAnnotation(text, color, textSizeSp, "sans", true);
    }

    public void addTextAnnotation(String text, int color, float textSizeSp, @Nullable String fontFamily, boolean hasBackground) {
        float density = getResources().getDisplayMetrics().density;
        float textSizePx = textSizeSp * density;

        // Position text in center of document or viewport
        float spawnX = 100f;
        float spawnY = 200f;
        if (targetImageView != null && targetImageView.getDisplayRect() != null) {
            RectF dr = targetImageView.getDisplayRect();
            float[] center = new float[]{dr.centerX(), dr.centerY()};
            inverseMatrix.mapPoints(center);
            spawnX = center[0] - 80f;
            spawnY = center[1];
        } else if (documentWidth > 0 && documentHeight > 0) {
            spawnX = documentWidth / 3f;
            spawnY = documentHeight / 2f;
        }

        clearSelection();
        TextItem item = new TextItem(text, spawnX, spawnY, color, textSizePx);
        item.setFontFamily(fontFamily);
        item.hasBackground = hasBackground;
        item.isSelected = true;
        textPaint.setTextSize(textSizePx);
        textPaint.setTypeface(getTypefaceForFont(getContext(), item.fontFamily));
        textPaint.getTextBounds(text, 0, text.length(), item.bounds);

        textItems.add(item);
        actionHistory.add(new AnnotationAction(ActionType.TEXT, item));
        notifyActionAdded();
        invalidate();
    }

    public void updateSelectedTextFont(@Nullable String fontFamily) {
        TextItem sel = getSelectedTextItem();
        if (sel != null) {
            sel.setFontFamily(fontFamily);
            textPaint.setTextSize(sel.textSize);
            textPaint.setTypeface(getTypefaceForFont(getContext(), sel.fontFamily));
            textPaint.getTextBounds(sel.text, 0, sel.text.length(), sel.bounds);
            invalidate();
        }
    }

    public void addSignatureAnnotation(@NonNull Bitmap signatureBitmap) {
        if (signatureBitmap.isRecycled()) return;

        float density = getResources().getDisplayMetrics().density;
        float targetWidth = 180f * density;
        float scale = targetWidth / Math.max(1, signatureBitmap.getWidth());
        float targetHeight = signatureBitmap.getHeight() * scale;

        float spawnX = 80f;
        float spawnY = 200f;
        if (targetImageView != null && targetImageView.getDisplayRect() != null) {
            RectF dr = targetImageView.getDisplayRect();
            float[] center = new float[]{dr.centerX(), dr.centerY()};
            inverseMatrix.mapPoints(center);
            spawnX = center[0] - (targetWidth / 2f);
            spawnY = center[1] - (targetHeight / 2f);
        } else if (documentWidth > 0 && documentHeight > 0) {
            spawnX = (documentWidth - targetWidth) / 2f;
            spawnY = (documentHeight - targetHeight) / 2f;
        }

        clearSelection();
        SignatureItem item = new SignatureItem(signatureBitmap, spawnX, spawnY, targetWidth, targetHeight);
        item.tintColor = Color.BLACK; // Default solid black ink
        item.isSelected = true;

        signatureItems.add(item);
        actionHistory.add(new AnnotationAction(ActionType.SIGNATURE, item));
        notifyActionAdded();
        invalidate();
    }

    @Nullable
    public TextItem getSelectedTextItem() {
        for (TextItem item : textItems) {
            if (item.isSelected) return item;
        }
        return null;
    }

    @Nullable
    public SignatureItem getSelectedSignatureItem() {
        for (SignatureItem item : signatureItems) {
            if (item.isSelected) return item;
        }
        return null;
    }

    public void clearSelection() {
        for (TextItem item : textItems) item.isSelected = false;
        for (SignatureItem item : signatureItems) item.isSelected = false;
        activeDraggingText = null;
        activeDraggingSignature = null;
        activeResizingText = null;
        activeResizingSignature = null;
    }

    public void deleteSelectedItem() {
        TextItem selText = getSelectedTextItem();
        if (selText != null) {
            textItems.remove(selText);
            invalidate();
            if (itemSelectionListener != null) itemSelectionListener.onSelectionCleared();
            return;
        }
        SignatureItem selSig = getSelectedSignatureItem();
        if (selSig != null) {
            signatureItems.remove(selSig);
            invalidate();
            if (itemSelectionListener != null) itemSelectionListener.onSelectionCleared();
        }
    }

    public void undo() {
        if (!actionHistory.isEmpty()) {
            AnnotationAction last = actionHistory.remove(actionHistory.size() - 1);
            if (last.type == ActionType.STROKE) {
                strokes.remove(last.item);
            } else if (last.type == ActionType.TEXT) {
                textItems.remove(last.item);
            } else if (last.type == ActionType.SIGNATURE) {
                signatureItems.remove(last.item);
            }
            clearSelection();
            invalidate();
            if (itemSelectionListener != null) itemSelectionListener.onSelectionCleared();
        }
    }

    public void clear() {
        strokes.clear();
        textItems.clear();
        signatureItems.clear();
        actionHistory.clear();
        currentPath.reset();
        clearSelection();
        invalidate();
        if (itemSelectionListener != null) itemSelectionListener.onSelectionCleared();
    }

    public boolean isEmpty() {
        return strokes.isEmpty() && textItems.isEmpty() && signatureItems.isEmpty() && currentPath.isEmpty();
    }

    public int getActionCount() {
        return actionHistory.size();
    }

    public interface OnActionAddedListener {
        void onActionAdded();
    }

    private OnActionAddedListener actionAddedListener;

    public void setOnActionAddedListener(OnActionAddedListener listener) {
        this.actionAddedListener = listener;
    }

    private void notifyActionAdded() {
        if (actionAddedListener != null) {
            actionAddedListener.onActionAdded();
        }
    }

    public boolean isDrawingModeActive() {
        return currentToolMode != ToolMode.NONE;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (isDrawingModeActive()) {
            disallowParentIntercept(true);
        }
        return super.dispatchTouchEvent(event);
    }

    private void disallowParentIntercept(boolean disallow) {
        ViewParent parent = getParent();
        while (parent != null) {
            parent.requestDisallowInterceptTouchEvent(disallow);
            parent = parent.getParent();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (currentToolMode == ToolMode.NONE) {
            return false;
        }

        int action = event.getActionMasked();

        // CRITICAL: Block ViewPager2/ScrollView/RecyclerView from stealing drawing touches
        if (action == MotionEvent.ACTION_DOWN) {
            disallowParentIntercept(true);
        } else if (action == MotionEvent.ACTION_MOVE && event.getPointerCount() == 1) {
            disallowParentIntercept(true);
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            disallowParentIntercept(false);
        }

        updateMatrix();

        // ── 1. Multi-Touch Gesture Conflict (2-Finger Zoom While Drawing) ──
        if (event.getPointerCount() >= 2) {
            isMultiTouching = true;
            currentPath.reset();
            if (targetImageView != null) {
                targetImageView.dispatchTouchEvent(event);
            }
            disallowParentIntercept(true);
            invalidate();
            return true;
        }

        if (action == MotionEvent.ACTION_POINTER_UP) {
            // Discard residual drag after 2-finger zoom to avoid stray lines
            isMultiTouching = true;
            return true;
        }

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (isMultiTouching) {
                isMultiTouching = false;
                currentPath.reset();
                if (targetImageView != null) {
                    targetImageView.dispatchTouchEvent(event);
                }
                disallowParentIntercept(false);
                invalidate();
                return true;
            }
        }

        if (isMultiTouching) {
            return true;
        }

        // ── 2. Convert Screen Coordinates to Document Bitmap Space ─────────
        float[] pt = new float[]{event.getX(), event.getY()};
        inverseMatrix.mapPoints(pt);
        float docX = pt[0];
        float docY = pt[1];
        float density = getResources().getDisplayMetrics().density;

        // ── 3. Interactive Text / Signature Manipulation Mode ──────────────
        if (currentToolMode == ToolMode.TEXT || currentToolMode == ToolMode.SIGNATURE) {
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    downScreenX = event.getX();
                    downScreenY = event.getY();
                    disallowParentIntercept(true);

                    // A. Check if touched resize handle of selected text
                    TextItem selText = getSelectedTextItem();
                    if (selText != null && selText.containsResizeHandle(docX, docY, density)) {
                        activeResizingText = selText;
                        initialTouchX = docX;
                        initialTouchY = docY;
                        initialTextSize = selText.textSize;
                        return true;
                    }

                    // B. Check if touched resize handle of selected signature
                    SignatureItem selSig = getSelectedSignatureItem();
                    if (selSig != null && selSig.containsResizeHandle(docX, docY, density)) {
                        activeResizingSignature = selSig;
                        initialTouchX = docX;
                        initialTouchY = docY;
                        initialWidth = selSig.width;
                        initialHeight = selSig.height;
                        return true;
                    }

                    // C. Check if touched any text item
                    for (int i = textItems.size() - 1; i >= 0; i--) {
                        TextItem txt = textItems.get(i);
                        if (txt.contains(docX, docY, density)) {
                            clearSelection();
                            txt.isSelected = true;
                            activeDraggingText = txt;
                            dragOffsetX = docX - txt.x;
                            dragOffsetY = docY - txt.y;
                            invalidate();
                            return true;
                        }
                    }

                    // D. Check if touched any signature item
                    for (int i = signatureItems.size() - 1; i >= 0; i--) {
                        SignatureItem sig = signatureItems.get(i);
                        if (sig.contains(docX, docY, density)) {
                            clearSelection();
                            sig.isSelected = true;
                            activeDraggingSignature = sig;
                            dragOffsetX = docX - sig.x;
                            dragOffsetY = docY - sig.y;
                            invalidate();
                            return true;
                        }
                    }

                    // E. Touched outside any item
                    clearSelection();
                    invalidate();
                    if (itemSelectionListener != null) itemSelectionListener.onSelectionCleared();
                    return true;

                case MotionEvent.ACTION_MOVE:
                    disallowParentIntercept(true);
                    // Resizing Text
                    if (activeResizingText != null) {
                        float delta = (docX - initialTouchX);
                        activeResizingText.textSize = Math.max(10f * density, initialTextSize + delta * 0.25f);
                        invalidate();
                        return true;
                    }

                    // Resizing Signature
                    if (activeResizingSignature != null) {
                        float delta = (docX - initialTouchX);
                        float newW = Math.max(40f * density, initialWidth + delta);
                        float aspect = initialHeight / Math.max(1f, initialWidth);
                        activeResizingSignature.width = newW;
                        activeResizingSignature.height = newW * aspect;
                        invalidate();
                        return true;
                    }

                    // Dragging Signature
                    if (activeDraggingSignature != null) {
                        activeDraggingSignature.x = docX - dragOffsetX;
                        activeDraggingSignature.y = docY - dragOffsetY;
                        invalidate();
                        return true;
                    }

                    // Dragging Text
                    if (activeDraggingText != null) {
                        activeDraggingText.x = docX - dragOffsetX;
                        activeDraggingText.y = docY - dragOffsetY;
                        invalidate();
                        return true;
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    float dist = (float) Math.hypot(event.getX() - downScreenX, event.getY() - downScreenY);
                    boolean isTap = dist < (10f * density);

                    if (isTap) {
                        if (activeDraggingText != null && itemSelectionListener != null) {
                            itemSelectionListener.onTextItemTapped(activeDraggingText);
                        } else if (activeDraggingSignature != null && itemSelectionListener != null) {
                            itemSelectionListener.onSignatureItemTapped(activeDraggingSignature);
                        }
                    }

                    activeDraggingText = null;
                    activeDraggingSignature = null;
                    activeResizingText = null;
                    activeResizingSignature = null;
                    disallowParentIntercept(false);
                    return true;
            }
            return false;
        }

        // ── 4. Freehand Pen or Highlighter Drawing in Document Space ───────
        boolean isHighlighter = (currentToolMode == ToolMode.HIGHLIGHTER);
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                disallowParentIntercept(true);
                currentPath.reset();
                currentPath.moveTo(docX, docY);
                lastDocX = docX;
                lastDocY = docY;
                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE:
                if (event.getPointerCount() == 1) {
                    disallowParentIntercept(true);
                }
                float dx = Math.abs(docX - lastDocX);
                float dy = Math.abs(docY - lastDocY);
                if (dx >= 2f || dy >= 2f) {
                    currentPath.quadTo(lastDocX, lastDocY, (docX + lastDocX) / 2f, (docY + lastDocY) / 2f);
                    lastDocX = docX;
                    lastDocY = docY;
                    invalidate();
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                currentPath.lineTo(docX, docY);
                float strokeW = isHighlighter ? currentStrokeWidth * 2.5f : currentStrokeWidth;
                int finalColor = isHighlighter ? ColorUtils.setAlphaComponent(currentColor, 85) : currentColor;
                Stroke stroke = new Stroke(new Path(currentPath), finalColor, strokeW, isHighlighter);
                strokes.add(stroke);
                actionHistory.add(new AnnotationAction(ActionType.STROKE, stroke));
                notifyActionAdded();
                currentPath.reset();
                invalidate();
                disallowParentIntercept(false);
                return true;

            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        updateMatrix();

        // Lock all rendering to the document image matrix!
        canvas.save();
        canvas.concat(currentMatrix);

        // 1. Draw completed strokes
        for (Stroke s : strokes) {
            Paint p = s.isHighlighter ? highlighterPaint : penPaint;
            p.setColor(s.color);
            p.setStrokeWidth(s.strokeWidth);
            canvas.drawPath(s.path, p);
        }

        // 2. Draw active in-progress stroke
        if (!currentPath.isEmpty()) {
            boolean isHighlighter = (currentToolMode == ToolMode.HIGHLIGHTER);
            Paint p = isHighlighter ? highlighterPaint : penPaint;
            int c = isHighlighter ? ColorUtils.setAlphaComponent(currentColor, 85) : currentColor;
            float w = isHighlighter ? currentStrokeWidth * 2.5f : currentStrokeWidth;
            p.setColor(c);
            p.setStrokeWidth(w);
            canvas.drawPath(currentPath, p);
        }

        // 3. Draw signatures
        float density = getResources().getDisplayMetrics().density;
        for (SignatureItem sig : signatureItems) {
            if (sig.bitmap != null && !sig.bitmap.isRecycled()) {
                if (sig.tintColor != 0) {
                    signaturePaint.setColorFilter(new PorterDuffColorFilter(sig.tintColor, PorterDuff.Mode.SRC_IN));
                } else {
                    signaturePaint.setColorFilter(null);
                }
                RectF dst = new RectF(sig.x, sig.y, sig.x + sig.width, sig.y + sig.height);
                canvas.drawBitmap(sig.bitmap, null, dst, signaturePaint);

                if (sig.isSelected) {
                    drawBoundingBoxAndHandles(canvas, dst, density);
                }
            }
        }

        // 4. Draw text items
        for (TextItem txt : textItems) {
            textPaint.setTextSize(txt.textSize);
            textPaint.setTypeface(getTypefaceForFont(getContext(), txt.fontFamily));
            textPaint.getTextBounds(txt.text, 0, txt.text.length(), txt.bounds);

            RectF pillRect = txt.getBoundingBox(density);

            if (txt.hasBackground) {
                textBackgroundPaint.setColor(txt.backgroundColor);
                canvas.drawRoundRect(pillRect, 6f * density, 6f * density, textBackgroundPaint);
            }

            textPaint.setColor(txt.color);
            canvas.drawText(txt.text, txt.x, txt.y, textPaint);

            if (txt.isSelected) {
                drawBoundingBoxAndHandles(canvas, pillRect, density);
            }
        }

        canvas.restore();
    }

    private void drawBoundingBoxAndHandles(Canvas canvas, RectF box, float density) {
        canvas.drawRoundRect(box, 4f * density, 4f * density, selectionBoxPaint);

        // Corner resize handle at bottom-right
        float handleRadius = 10f * density;
        canvas.drawCircle(box.right, box.bottom, handleRadius, handleFillPaint);
        canvas.drawCircle(box.right, box.bottom, handleRadius, handleStrokePaint);
    }

    public static Typeface getTypefaceForFont(@Nullable String fontFamily) {
        return FontUtils.getTypeface(fontFamily);
    }

    public static Typeface getTypefaceForFont(@Nullable Context context, @Nullable String fontFamily) {
        return FontUtils.getTypeface(context, fontFamily);
    }

    /**
     * Flattens all annotations directly onto the base bitmap canvas.
     * Since all coordinates are stored in document space, this guarantees
     * vector-accurate 1:1 pixel rendering without distortion or drift.
     */
    @NonNull
    public Bitmap flattenOnto(@NonNull Bitmap baseBitmap, @Nullable RectF sourceRectOnView) {
        if (isEmpty() && baseBitmap.isMutable()) {
            return baseBitmap;
        }

        Bitmap outputBitmap = baseBitmap.copy(Bitmap.Config.ARGB_8888, true);
        Canvas canvas = new Canvas(outputBitmap);

        // 1. Flatten strokes
        Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);

        for (Stroke s : strokes) {
            strokePaint.setColor(s.color);
            strokePaint.setStrokeWidth(s.strokeWidth);
            canvas.drawPath(s.path, strokePaint);
        }

        // 2. Flatten signatures
        for (SignatureItem sig : signatureItems) {
            if (sig.bitmap != null && !sig.bitmap.isRecycled()) {
                Paint sigP = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                if (sig.tintColor != 0) {
                    sigP.setColorFilter(new PorterDuffColorFilter(sig.tintColor, PorterDuff.Mode.SRC_IN));
                }
                RectF dst = new RectF(sig.x, sig.y, sig.x + sig.width, sig.y + sig.height);
                canvas.drawBitmap(sig.bitmap, null, dst, sigP);
            }
        }

        // 3. Flatten text annotations
        float density = getResources().getDisplayMetrics().density;
        Paint flatTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        flatTextPaint.setFakeBoldText(true);

        Paint flatBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        flatBgPaint.setStyle(Paint.Style.FILL);

        for (TextItem txt : textItems) {
            flatTextPaint.setTextSize(txt.textSize);
            flatTextPaint.setTypeface(getTypefaceForFont(getContext(), txt.fontFamily));
            flatTextPaint.setColor(txt.color);

            Rect bounds = new Rect();
            flatTextPaint.getTextBounds(txt.text, 0, txt.text.length(), bounds);

            if (txt.hasBackground) {
                flatBgPaint.setColor(txt.backgroundColor);
                RectF pillRect = txt.getBoundingBox(density);
                canvas.drawRoundRect(pillRect, 6f * density, 6f * density, flatBgPaint);
            }

            canvas.drawText(txt.text, txt.x, txt.y, flatTextPaint);
        }

        return outputBitmap;
    }
}