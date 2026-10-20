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

import com.anscanner.app.model.annotation.AnnotationItem;
import com.anscanner.app.model.annotation.StrokeAnnotation;
import com.anscanner.app.util.FontUtils;
import com.anscanner.app.util.HapticHelper;
import java.util.Collections;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.Serializable;
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
        void onTextItemDoubleTapped(@NonNull TextItem item);
        void onSignatureItemTapped(@NonNull SignatureItem item);
        void onSelectionCleared();
    }

    public static class Stroke extends com.anscanner.app.model.annotation.StrokeAnnotation {
        private static final long serialVersionUID = 1L;

        public Stroke(Path path, int color, float strokeWidth, boolean isHighlighter) {
            super(path, color, strokeWidth, isHighlighter);
        }

        public Stroke(Path path, int color, float strokeWidth, boolean isHighlighter, long timestamp) {
            super(path, color, strokeWidth, isHighlighter, timestamp);
        }

        public Stroke(Path path, int color, float strokeWidth, boolean isHighlighter, long timestamp, List<PointF> points) {
            super(path, color, strokeWidth, isHighlighter, timestamp, points);
        }

        @NonNull
        @Override
        public Stroke copy() {
            Stroke copy = new Stroke(new Path(this.path), this.color, this.strokeWidth, this.isHighlighter, this.getTimestamp(), this.points);
            copy.setSelected(this.isSelected());
            return copy;
        }
    }

    public static class TextItem extends com.anscanner.app.model.annotation.TextAnnotation {
        private static final long serialVersionUID = 1L;

        public TextItem(String text, float x, float y, int color, float textSize) {
            super(text, x, y, color, textSize);
        }

        @NonNull
        @Override
        public TextItem copy() {
            TextItem item = new TextItem(this.text, this.x, this.y, this.color, this.textSize);
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
            item.setRotationAngle(this.getRotationAngle());
            return item;
        }

        public JSONObject toJson() throws JSONException {
            JSONObject j = new JSONObject();
            j.put("text", text);
            j.put("x", (double) x);
            j.put("y", (double) y);
            j.put("textSize", (double) textSize);
            j.put("textColor", textColor);
            j.put("fontFamily", fontFamily != null ? fontFamily : "sans");
            j.put("hasBackground", hasBackground);
            j.put("backgroundColor", backgroundColor);
            j.put("rotationAngle", (double) getRotationAngle());
            return j;
        }

        public static TextItem fromJson(JSONObject j) {
            String text = j.optString("text", "");
            float x = (float) j.optDouble("x", 0.0);
            float y = (float) j.optDouble("y", 0.0);
            float textSize = (float) j.optDouble("textSize", 24.0);
            int textColor = j.optInt("textColor", Color.WHITE);
            TextItem item = new TextItem(text, x, y, textColor, textSize);
            item.setFontFamily(j.optString("fontFamily", "sans"));
            item.hasBackground = j.optBoolean("hasBackground", true);
            item.backgroundColor = j.optInt("backgroundColor", Color.parseColor("#B3000000"));
            item.setRotationAngle((float) j.optDouble("rotationAngle", 0.0));
            return item;
        }
    }

    public static class SignatureItem extends com.anscanner.app.model.annotation.SignatureAnnotation {
        private static final long serialVersionUID = 1L;

        public SignatureItem(Bitmap bitmap, float x, float y, float width, float height) {
            super(bitmap, x, y, width, height);
        }

        @NonNull
        @Override
        public SignatureItem copy() {
            SignatureItem item = new SignatureItem(this.bitmap, this.x, this.y, this.width, this.height);
            item.tintColor = this.tintColor;
            item.isSelected = this.isSelected;
            item.setRotationAngle(this.getRotationAngle());
            return item;
        }

        public JSONObject toJson() throws JSONException {
            JSONObject j = new JSONObject();
            j.put("x", (double) x);
            j.put("y", (double) y);
            j.put("width", (double) width);
            j.put("height", (double) height);
            j.put("tintColor", tintColor);
            j.put("rotationAngle", (double) getRotationAngle());
            if (bitmap != null && !bitmap.isRecycled()) {
                try {
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos);
                    j.put("bitmapBase64", android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP));
                } catch (Exception ignored) {}
            }
            return j;
        }

        public static SignatureItem fromJson(JSONObject j) {
            float x = (float) j.optDouble("x", 0.0);
            float y = (float) j.optDouble("y", 0.0);
            float width = (float) j.optDouble("width", 100.0);
            float height = (float) j.optDouble("height", 50.0);
            int tintColor = j.optInt("tintColor", Color.BLACK);
            float rotationAngle = (float) j.optDouble("rotationAngle", 0.0);
            Bitmap bm = null;
            if (j.has("bitmapBase64")) {
                try {
                    byte[] bytes = android.util.Base64.decode(j.getString("bitmapBase64"), android.util.Base64.NO_WRAP);
                    bm = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                } catch (Exception ignored) {}
            }
            SignatureItem item = new SignatureItem(bm, x, y, width, height);
            item.tintColor = tintColor;
            item.setRotationAngle(rotationAngle);
            return item;
        }
    }

    /**
     * Immutable snapshot container for all vector annotations on a page.
     */
    public static class AnnotationState implements Serializable {
        private static final long serialVersionUID = 2L;

        @NonNull
        public final List<AnnotationItem> items;
        @NonNull
        public final List<Stroke> strokes;
        @NonNull
        public final List<TextItem> textItems;
        @NonNull
        public final List<SignatureItem> signatureItems;

        public AnnotationState() {
            this(new ArrayList<>());
        }

        public AnnotationState(@NonNull List<AnnotationItem> items) {
            this.items = new ArrayList<>(items);
            List<Stroke> sList = new ArrayList<>();
            List<TextItem> tList = new ArrayList<>();
            List<SignatureItem> sigList = new ArrayList<>();
            for (AnnotationItem item : this.items) {
                if (item instanceof Stroke) {
                    sList.add((Stroke) item);
                } else if (item instanceof TextItem) {
                    tList.add((TextItem) item);
                } else if (item instanceof SignatureItem) {
                    sigList.add((SignatureItem) item);
                }
            }
            this.strokes = sList;
            this.textItems = tList;
            this.signatureItems = sigList;
        }

        public AnnotationState(@NonNull List<Stroke> strokes,
                               @NonNull List<TextItem> textItems,
                               @NonNull List<SignatureItem> signatureItems) {
            this.strokes = new ArrayList<>(strokes);
            this.textItems = new ArrayList<>(textItems);
            this.signatureItems = new ArrayList<>(signatureItems);
            List<AnnotationItem> all = new ArrayList<>(strokes.size() + textItems.size() + signatureItems.size());
            all.addAll(strokes);
            all.addAll(signatureItems);
            all.addAll(textItems);
            this.items = all;
        }

        @NonNull
        public AnnotationState copy() {
            List<AnnotationItem> copied = new ArrayList<>(items.size());
            for (AnnotationItem it : items) {
                copied.add(it.copy());
            }
            return new AnnotationState(copied);
        }

        public boolean isEmpty() {
            return items.isEmpty();
        }

        public int size() {
            return items.size();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            AnnotationState that = (AnnotationState) o;
            return items.equals(that.items);
        }

        @Override
        public int hashCode() {
            return items.hashCode();
        }

        public JSONObject toJson() {
            JSONObject j = new JSONObject();
            try {
                JSONArray itemsArr = new JSONArray();
                for (AnnotationItem item : items) {
                    if (item instanceof TextItem) {
                        JSONObject tj = ((TextItem) item).toJson();
                        tj.put("itemType", "text");
                        itemsArr.put(tj);
                    } else if (item instanceof SignatureItem) {
                        JSONObject sj = ((SignatureItem) item).toJson();
                        sj.put("itemType", "signature");
                        itemsArr.put(sj);
                    } else if (item instanceof Stroke) {
                        JSONObject stj = ((Stroke) item).toJson();
                        stj.put("itemType", "stroke");
                        itemsArr.put(stj);
                    }
                }
                j.put("items", itemsArr);

                // Backward-compatible fallback arrays
                JSONArray textArr = new JSONArray();
                for (TextItem t : textItems) {
                    textArr.put(t.toJson());
                }
                j.put("textItems", textArr);

                JSONArray sigArr = new JSONArray();
                for (SignatureItem s : signatureItems) {
                    sigArr.put(s.toJson());
                }
                j.put("signatureItems", sigArr);
            } catch (Exception ignored) {}
            return j;
        }

        public static AnnotationState fromJson(@Nullable JSONObject j) {
            if (j == null) return new AnnotationState();
            if (j.has("items")) {
                try {
                    JSONArray arr = j.getJSONArray("items");
                    List<AnnotationItem> list = new ArrayList<>(arr.length());
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject obj = arr.getJSONObject(i);
                        String type = obj.optString("itemType", obj.optString("type", ""));
                        if ("text".equals(type)) {
                            list.add(TextItem.fromJson(obj));
                        } else if ("signature".equals(type)) {
                            list.add(SignatureItem.fromJson(obj));
                        } else if ("stroke".equals(type)) {
                            StrokeAnnotation sa = StrokeAnnotation.fromJson(obj);
                            list.add(new Stroke(sa.path, sa.color, sa.strokeWidth, sa.isHighlighter, sa.getTimestamp(), sa.points));
                        }
                    }
                    return new AnnotationState(list);
                } catch (Exception ignored) {}
            }
            List<TextItem> texts = new ArrayList<>();
            if (j.has("textItems")) {
                try {
                    JSONArray arr = j.getJSONArray("textItems");
                    for (int i = 0; i < arr.length(); i++) {
                        texts.add(TextItem.fromJson(arr.getJSONObject(i)));
                    }
                } catch (Exception ignored) {}
            }
            List<SignatureItem> sigs = new ArrayList<>();
            if (j.has("signatureItems")) {
                try {
                    JSONArray arr = j.getJSONArray("signatureItems");
                    for (int i = 0; i < arr.length(); i++) {
                        sigs.add(SignatureItem.fromJson(arr.getJSONObject(i)));
                    }
                } catch (Exception ignored) {}
            }
            return new AnnotationState(new ArrayList<>(), texts, sigs);
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
    private final List<AnnotationItem> annotationItems = new ArrayList<>();
    private final List<Stroke> strokes = new ArrayList<>();
    private final List<TextItem> textItems = new ArrayList<>();
    private final List<SignatureItem> signatureItems = new ArrayList<>();
    private final List<AnnotationAction> actionHistory = new ArrayList<>();

    public List<AnnotationItem> getAnnotationItems() {
        return Collections.unmodifiableList(annotationItems);
    }

    @Nullable
    public AnnotationItem getSelectedItem() {
        for (int i = annotationItems.size() - 1; i >= 0; i--) {
            AnnotationItem item = annotationItems.get(i);
            if (item.isSelected()) return item;
        }
        return null;
    }

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
    private final Paint rotateIconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private ToolMode currentToolMode = ToolMode.NONE;
    private int currentColor = Color.parseColor("#68D391"); // Default Mint Green
    private float currentStrokeWidth = 8f; // Document space default
    private float lastDocX;
    private float lastDocY;

    // Selection & Drag/Resize/Rotate state
    private TextItem activeDraggingText = null;
    private SignatureItem activeDraggingSignature = null;
    private TextItem activeResizingText = null;
    private SignatureItem activeResizingSignature = null;
    private TextItem activeRotatingText = null;
    private SignatureItem activeRotatingSignature = null;
    private float lastSnappedAngle = -1f;
    private boolean isTwoFingerRotating = false;
    private float initialTwoFingerAngle = 0f;
    private float initialObjectAngle = 0f;
    private float dragOffsetX = 0f;
    private float dragOffsetY = 0f;
    private float initialTouchX = 0f;
    private float initialTouchY = 0f;
    private float initialWidth = 0f;
    private float initialHeight = 0f;
    private float initialTextSize = 0f;
    private float downScreenX = 0f;
    private float downScreenY = 0f;
    private float downDocX = 0f;
    private float downDocY = 0f;
    private long lastTextTapTime = 0;
    private TextItem lastTappedTextItem = null;
    private static final long DOUBLE_TAP_TIMEOUT_MS = 350;
    private TextItem pendingTouchText = null;
    private SignatureItem pendingTouchSignature = null;

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

        rotateIconPaint.setStyle(Paint.Style.STROKE);
        rotateIconPaint.setColor(Color.parseColor("#10B981"));
        rotateIconPaint.setStrokeWidth(2f * getResources().getDisplayMetrics().density);
        rotateIconPaint.setStrokeCap(Paint.Cap.ROUND);
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
        setToolMode(mode, false);
    }

    public void setToolMode(ToolMode mode, boolean keepSelection) {
        this.currentToolMode = mode;
        if (!keepSelection && mode != ToolMode.TEXT) {
            clearSelection();
        }
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
            selText.setTextColor(color);
            notifyActionAdded();
            invalidate();
        }
        SignatureItem selSig = getSelectedSignatureItem();
        if (selSig != null) {
            selSig.tintColor = color;
            notifyActionAdded();
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
        } else if (targetImageView != null && targetImageView.getDrawable() != null) {
            int dw = targetImageView.getDrawable().getIntrinsicWidth();
            int dh = targetImageView.getDrawable().getIntrinsicHeight();
            if (dw > 0 && dh > 0) {
                spawnX = dw / 3f;
                spawnY = dh / 2f;
            }
        } else if (documentWidth > 0 && documentHeight > 0) {
            spawnX = documentWidth / 3f;
            spawnY = documentHeight / 2f;
        }

        clearSelection();
        TextItem item = new TextItem(text, spawnX, spawnY, color, textSizePx);
        item.setFontFamily(fontFamily);
        item.hasBackground = hasBackground;
        item.isSelected = true;
        item.updateNormalizedPosition(documentWidth, documentHeight);
        textPaint.setTextSize(textSizePx);
        textPaint.setTypeface(getTypefaceForFont(getContext(), item.fontFamily));
        textPaint.setFakeBoldText("bold_condensed".equalsIgnoreCase(item.fontFamily) || "sans".equalsIgnoreCase(item.fontFamily));
        textPaint.getTextBounds(text, 0, text.length(), item.bounds);
        item.boundingBox.set(item.getBoundingBox(density));

        textItems.add(item);
        annotationItems.add(item);
        actionHistory.add(new AnnotationAction(ActionType.TEXT, item));
        currentToolMode = ToolMode.TEXT;
        notifyActionAdded();
        invalidate();
        if (itemSelectionListener != null) {
            itemSelectionListener.onTextItemTapped(item);
        }
    }

    public void updateSelectedTextFont(@Nullable String fontFamily) {
        TextItem sel = getSelectedTextItem();
        if (sel != null) {
            sel.setFontFamily(fontFamily);
            textPaint.setTextSize(sel.textSize);
            textPaint.setTypeface(getTypefaceForFont(getContext(), sel.fontFamily));
            textPaint.setFakeBoldText("bold_condensed".equalsIgnoreCase(sel.fontFamily) || "sans".equalsIgnoreCase(sel.fontFamily));
            textPaint.getTextBounds(sel.text, 0, sel.text.length(), sel.bounds);
            float density = getResources().getDisplayMetrics().density;
            sel.boundingBox.set(sel.getBoundingBox(density));
            notifyActionAdded();
            invalidate();
        }
    }

    public void updateSelectedTextColor(int color) {
        TextItem sel = getSelectedTextItem();
        if (sel != null) {
            sel.setTextColor(color);
            notifyActionAdded();
            invalidate();
        }
    }

    public void updateSelectedTextSize(float textSizeSp) {
        TextItem sel = getSelectedTextItem();
        if (sel != null) {
            float density = getResources().getDisplayMetrics().density;
            sel.textSize = textSizeSp * density;
            textPaint.setTextSize(sel.textSize);
            textPaint.setTypeface(getTypefaceForFont(getContext(), sel.fontFamily));
            textPaint.setFakeBoldText("bold_condensed".equalsIgnoreCase(sel.fontFamily) || "sans".equalsIgnoreCase(sel.fontFamily));
            textPaint.getTextBounds(sel.text, 0, sel.text.length(), sel.bounds);
            sel.boundingBox.set(sel.getBoundingBox(density));
            notifyActionAdded();
            invalidate();
        }
    }

    public void updateSelectedTextString(@NonNull String newText) {
        TextItem sel = getSelectedTextItem();
        if (sel != null) {
            sel.text = newText;
            float density = getResources().getDisplayMetrics().density;
            textPaint.setTextSize(sel.textSize);
            textPaint.setTypeface(getTypefaceForFont(getContext(), sel.fontFamily));
            textPaint.setFakeBoldText("bold_condensed".equalsIgnoreCase(sel.fontFamily) || "sans".equalsIgnoreCase(sel.fontFamily));
            textPaint.getTextBounds(sel.text, 0, sel.text.length(), sel.bounds);
            sel.boundingBox.set(sel.getBoundingBox(density));
            notifyActionAdded();
            invalidate();
        }
    }

    public void toggleSelectedTextBackground() {
        TextItem sel = getSelectedTextItem();
        if (sel != null) {
            sel.hasBackground = !sel.hasBackground;
            notifyActionAdded();
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
        } else if (targetImageView != null && targetImageView.getDrawable() != null) {
            int dw = targetImageView.getDrawable().getIntrinsicWidth();
            int dh = targetImageView.getDrawable().getIntrinsicHeight();
            if (dw > 0 && dh > 0) {
                spawnX = (dw - targetWidth) / 2f;
                spawnY = (dh - targetHeight) / 2f;
            }
        } else if (documentWidth > 0 && documentHeight > 0) {
            spawnX = (documentWidth - targetWidth) / 2f;
            spawnY = (documentHeight - targetHeight) / 2f;
        }

        clearSelection();
        SignatureItem item = new SignatureItem(signatureBitmap, spawnX, spawnY, targetWidth, targetHeight);
        item.tintColor = Color.BLACK; // Default solid black ink
        item.isSelected = true;

        signatureItems.add(item);
        annotationItems.add(item);
        actionHistory.add(new AnnotationAction(ActionType.SIGNATURE, item));
        notifyActionAdded();
        invalidate();
        if (itemSelectionListener != null) {
            itemSelectionListener.onSignatureItemTapped(item);
        }
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
        for (AnnotationItem item : annotationItems) item.setSelected(false);
        for (TextItem item : textItems) item.isSelected = false;
        for (SignatureItem item : signatureItems) item.isSelected = false;
        activeDraggingText = null;
        activeDraggingSignature = null;
        activeResizingText = null;
        activeResizingSignature = null;
        activeRotatingText = null;
        activeRotatingSignature = null;
        isTwoFingerRotating = false;
        lastSnappedAngle = -1f;
    }

    public void deleteSelectedItem() {
        AnnotationItem sel = getSelectedItem();
        if (sel != null) {
            annotationItems.remove(sel);
            strokes.remove(sel);
            textItems.remove(sel);
            signatureItems.remove(sel);
            for (int i = actionHistory.size() - 1; i >= 0; i--) {
                if (actionHistory.get(i).item == sel) {
                    actionHistory.remove(i);
                    break;
                }
            }
            clearSelection();
            notifyActionAdded();
            invalidate();
            if (itemSelectionListener != null) itemSelectionListener.onSelectionCleared();
            return;
        }
        TextItem selText = getSelectedTextItem();
        if (selText != null) {
            annotationItems.remove(selText);
            textItems.remove(selText);
            notifyActionAdded();
            invalidate();
            if (itemSelectionListener != null) itemSelectionListener.onSelectionCleared();
            return;
        }
        SignatureItem selSig = getSelectedSignatureItem();
        if (selSig != null) {
            annotationItems.remove(selSig);
            signatureItems.remove(selSig);
            notifyActionAdded();
            invalidate();
            if (itemSelectionListener != null) itemSelectionListener.onSelectionCleared();
        }
    }

    public void undo() {
        if (!annotationItems.isEmpty()) {
            AnnotationItem last = annotationItems.remove(annotationItems.size() - 1);
            strokes.remove(last);
            textItems.remove(last);
            signatureItems.remove(last);
            for (int i = actionHistory.size() - 1; i >= 0; i--) {
                if (actionHistory.get(i).item == last) {
                    actionHistory.remove(i);
                    break;
                }
            }
            clearSelection();
            invalidate();
            if (itemSelectionListener != null) itemSelectionListener.onSelectionCleared();
        }
    }

    public void clear() {
        annotationItems.clear();
        strokes.clear();
        textItems.clear();
        signatureItems.clear();
        actionHistory.clear();
        currentPath.reset();
        clearSelection();
        invalidate();
        if (itemSelectionListener != null) itemSelectionListener.onSelectionCleared();
    }

    @NonNull
    public AnnotationState getAnnotationState() {
        List<AnnotationItem> copy = new ArrayList<>(annotationItems.size());
        for (AnnotationItem item : annotationItems) copy.add(item.copy());
        return new AnnotationState(copy);
    }

    public void setAnnotationState(@Nullable AnnotationState state) {
        annotationItems.clear();
        strokes.clear();
        textItems.clear();
        signatureItems.clear();
        actionHistory.clear();
        currentPath.reset();
        clearSelection();

        if (state != null) {
            for (AnnotationItem item : state.items) {
                AnnotationItem copy = item.copy();
                annotationItems.add(copy);
                if (copy instanceof Stroke) {
                    Stroke sc = (Stroke) copy;
                    strokes.add(sc);
                    actionHistory.add(new AnnotationAction(ActionType.STROKE, sc));
                } else if (copy instanceof TextItem) {
                    TextItem tc = (TextItem) copy;
                    textItems.add(tc);
                    actionHistory.add(new AnnotationAction(ActionType.TEXT, tc));
                } else if (copy instanceof SignatureItem) {
                    SignatureItem sigc = (SignatureItem) copy;
                    signatureItems.add(sigc);
                    actionHistory.add(new AnnotationAction(ActionType.SIGNATURE, sigc));
                }
            }
        }
        invalidate();
    }

    /**
     * Transforms an AnnotationState using a projective or affine transformation Matrix (e.g. crop warp or rotation).
     * Returns a new transformed AnnotationState without mutating the original.
     */
    @Nullable
    public static AnnotationState transformState(@Nullable AnnotationState state, @NonNull Matrix matrix, @Nullable Context context) {
        if (state == null || state.isEmpty() || matrix == null) {
            return state != null ? state.copy() : null;
        }

        float density = context != null ? context.getResources().getDisplayMetrics().density : 2.0f;
        List<AnnotationItem> transformedItems = new ArrayList<>(state.items.size());

        float[] vec = new float[]{1f, 0f};
        matrix.mapVectors(vec);
        float scale = Math.max(0.01f, (float) Math.hypot(vec[0], vec[1]));
        float matrixRotationDeg = (float) Math.toDegrees(Math.atan2(vec[1], vec[0]));

        Paint measurePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        for (AnnotationItem item : state.items) {
            if (item instanceof Stroke) {
                Stroke s = (Stroke) item;
                Path newPath = new Path(s.path);
                newPath.transform(matrix);
                float newStrokeWidth = Math.max(1f, s.strokeWidth * scale);
                Stroke sc = new Stroke(newPath, s.color, newStrokeWidth, s.isHighlighter, s.getTimestamp(), s.points);
                transformedItems.add(sc);
            } else if (item instanceof TextItem) {
                TextItem t = (TextItem) item;
                float cx = (t.boundingBox.width() > 0) ? t.boundingBox.centerX() : (t.x + t.bounds.width() / 2f);
                float cy = (t.boundingBox.height() > 0) ? t.boundingBox.centerY() : (t.y - t.bounds.height() / 2f);
                float[] centerPt = new float[]{cx, cy};
                matrix.mapPoints(centerPt);

                float[] textVec = new float[]{0f, t.textSize};
                matrix.mapVectors(textVec);
                float newTextSize = Math.max(8f * density, (float) Math.hypot(textVec[0], textVec[1]));

                measurePaint.setTextSize(newTextSize);
                measurePaint.setTypeface(getTypefaceForFont(context, t.fontFamily));
                measurePaint.setFakeBoldText("bold_condensed".equalsIgnoreCase(t.fontFamily) || "sans".equalsIgnoreCase(t.fontFamily));
                Rect textBounds = new Rect();
                measurePaint.getTextBounds(t.text, 0, t.text.length(), textBounds);

                float pad = 10f * density;
                float boxWidth = textBounds.width() + 2 * pad;
                float boxHeight = textBounds.height() + 2 * pad;

                float newBoxLeft = centerPt[0] - boxWidth / 2f;
                float newBoxBottom = centerPt[1] + boxHeight / 2f;
                float newX = newBoxLeft + pad;
                float newY = newBoxBottom - pad;

                TextItem tc = new TextItem(t.text, newX, newY, t.color, newTextSize);
                tc.setFontFamily(t.fontFamily);
                tc.hasBackground = t.hasBackground;
                tc.backgroundColor = t.backgroundColor;
                tc.isSelected = false;
                tc.bounds.set(textBounds);
                tc.boundingBox.set(newBoxLeft, newBoxBottom - boxHeight, newBoxLeft + boxWidth, newBoxBottom);
                float newAngle = ((t.getRotationAngle() + matrixRotationDeg) % 360f + 360f) % 360f;
                tc.setRotationAngle(newAngle);
                tc.setTimestamp(t.getTimestamp());
                transformedItems.add(tc);
            } else if (item instanceof SignatureItem) {
                SignatureItem sig = (SignatureItem) item;
                float cx = sig.x + sig.width / 2f;
                float cy = sig.y + sig.height / 2f;
                float[] centerPt = new float[]{cx, cy};
                matrix.mapPoints(centerPt);

                float newW = sig.width * scale;
                float newH = sig.height * scale;
                float newX = centerPt[0] - newW / 2f;
                float newY = centerPt[1] - newH / 2f;

                SignatureItem sc = new SignatureItem(
                        sig.bitmap,
                        newX,
                        newY,
                        Math.max(10f * density, newW),
                        Math.max(10f * density, newH)
                );
                sc.tintColor = sig.tintColor;
                sc.isSelected = false;
                float newAngle = ((sig.getRotationAngle() + matrixRotationDeg) % 360f + 360f) % 360f;
                sc.setRotationAngle(newAngle);
                sc.setTimestamp(sig.getTimestamp());
                transformedItems.add(sc);
            } else {
                AnnotationItem copy = item.copy();
                copy.transform(matrix);
                transformedItems.add(copy);
            }
        }

        return new AnnotationState(transformedItems);
    }

    /**
     * Transforms current live annotations in place using a 3x3 transformation Matrix.
     */
    public void transformAnnotations(@NonNull Matrix matrix) {
        AnnotationState transformed = transformState(getAnnotationState(), matrix, getContext());
        setAnnotationState(transformed);
    }

    /**
     * Renders vector annotations onto an export bitmap without altering editor state.
     */
    @NonNull
    public static Bitmap flattenOnto(@NonNull Bitmap baseBitmap, @Nullable AnnotationState state, @Nullable Context context) {
        if (baseBitmap == null || baseBitmap.isRecycled()) {
            return baseBitmap;
        }
        if (state == null || state.isEmpty()) {
            return baseBitmap;
        }

        Bitmap outputBitmap;
        try {
            outputBitmap = baseBitmap.copy(Bitmap.Config.ARGB_8888, true);
        } catch (OutOfMemoryError oom) {
            System.gc();
            try {
                outputBitmap = baseBitmap.copy(Bitmap.Config.ARGB_8888, true);
            } catch (OutOfMemoryError oom2) {
                return baseBitmap;
            }
        }
        if (outputBitmap == null) return baseBitmap;

        Canvas canvas = new Canvas(outputBitmap);
        float density = context != null ? context.getResources().getDisplayMetrics().density : 2.0f;

        Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);

        Paint sigP = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        Paint flatTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint flatBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        flatBgPaint.setStyle(Paint.Style.FILL);

        // Render all elements sequentially from bottom to top
        for (AnnotationItem item : state.items) {
            if (item instanceof Stroke) {
                Stroke s = (Stroke) item;
                strokePaint.setColor(s.color);
                strokePaint.setStrokeWidth(s.strokeWidth);
                canvas.drawPath(s.path, strokePaint);
            } else if (item instanceof SignatureItem) {
                SignatureItem sig = (SignatureItem) item;
                if (sig.bitmap != null && !sig.bitmap.isRecycled()) {
                    canvas.save();
                    float cx = sig.x + sig.width / 2f;
                    float cy = sig.y + sig.height / 2f;
                    canvas.rotate(sig.getRotationAngle(), cx, cy);

                    if (sig.tintColor != 0) {
                        sigP.setColorFilter(new PorterDuffColorFilter(sig.tintColor, PorterDuff.Mode.SRC_IN));
                    } else {
                        sigP.setColorFilter(null);
                    }
                    RectF dst = new RectF(sig.x, sig.y, sig.x + sig.width, sig.y + sig.height);
                    canvas.drawBitmap(sig.bitmap, null, dst, sigP);
                    canvas.restore();
                }
            } else if (item instanceof TextItem) {
                TextItem txt = (TextItem) item;
                flatTextPaint.setTextSize(txt.textSize);
                flatTextPaint.setTypeface(getTypefaceForFont(context, txt.fontFamily));
                flatTextPaint.setFakeBoldText("bold_condensed".equalsIgnoreCase(txt.fontFamily) || "sans".equalsIgnoreCase(txt.fontFamily));
                flatTextPaint.setColor(txt.color);

                Rect bounds = new Rect();
                flatTextPaint.getTextBounds(txt.text, 0, txt.text.length(), bounds);
                txt.bounds.set(bounds);
                RectF pillRect = txt.getBoundingBox(density);

                canvas.save();
                float cx = pillRect.centerX();
                float cy = pillRect.centerY();
                canvas.rotate(txt.getRotationAngle(), cx, cy);

                if (txt.hasBackground) {
                    flatBgPaint.setColor(txt.backgroundColor);
                    canvas.drawRoundRect(pillRect, 6f * density, 6f * density, flatBgPaint);
                }
                canvas.drawText(txt.text, txt.x, txt.y, flatTextPaint);
                canvas.restore();
            } else {
                item.draw(canvas);
            }
        }

        return outputBitmap;
    }

    public boolean isEmpty() {
        return annotationItems.isEmpty() && currentPath.isEmpty();
    }

    public int getActionCount() {
        return annotationItems.size();
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

        // ── 1. Multi-Touch Gesture Handling (Rotation & Zoom) ──
        if (event.getPointerCount() >= 2) {
            TextItem selText = getSelectedTextItem();
            SignatureItem selSig = getSelectedSignatureItem();
            if (selText != null || selSig != null) {
                float p0x = event.getX(0), p0y = event.getY(0);
                float p1x = event.getX(1), p1y = event.getY(1);
                float currentTwoFingerAngle = (float) Math.toDegrees(Math.atan2(p1y - p0y, p1x - p0x));
                if (action == MotionEvent.ACTION_POINTER_DOWN || !isTwoFingerRotating) {
                    isTwoFingerRotating = true;
                    initialTwoFingerAngle = currentTwoFingerAngle;
                    initialObjectAngle = selText != null ? selText.getRotationAngle() : selSig.getRotationAngle();
                    disallowParentIntercept(true);
                    return true;
                } else if (action == MotionEvent.ACTION_MOVE && isTwoFingerRotating) {
                    float delta = currentTwoFingerAngle - initialTwoFingerAngle;
                    float rawAngle = ((initialObjectAngle + delta) % 360f + 360f) % 360f;
                    float snapped = applyAngleSnapping(rawAngle);
                    if (selText != null) {
                        selText.setRotationAngle(snapped);
                    } else {
                        selSig.setRotationAngle(snapped);
                    }
                    invalidate();
                    disallowParentIntercept(true);
                    return true;
                }
            } else {
                isMultiTouching = true;
                currentPath.reset();
                if (targetImageView != null) {
                    targetImageView.dispatchTouchEvent(event);
                }
                disallowParentIntercept(true);
                invalidate();
                return true;
            }
        }

        if (action == MotionEvent.ACTION_POINTER_UP) {
            if (isTwoFingerRotating) {
                isTwoFingerRotating = false;
                notifyActionAdded();
                invalidate();
                return true;
            }
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

        // ── 3. Touch Event Routing ─────────────────────────────────────────
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                downScreenX = event.getX();
                downScreenY = event.getY();
                downDocX = docX;
                downDocY = docY;
                disallowParentIntercept(true);

                // A. Check if touched rotation handle of selected text
                TextItem selText = getSelectedTextItem();
                if (selText != null && selText.containsRotateHandle(docX, docY, density)) {
                    activeRotatingText = selText;
                    lastSnappedAngle = -1f;
                    return true;
                }

                // B. Check if touched rotation handle of selected signature
                SignatureItem selSig = getSelectedSignatureItem();
                if (selSig != null && selSig.containsRotateHandle(docX, docY, density)) {
                    activeRotatingSignature = selSig;
                    lastSnappedAngle = -1f;
                    return true;
                }

                // C. Check if touched resize handle of selected text
                if (selText != null && selText.containsResizeHandle(docX, docY, density)) {
                    activeResizingText = selText;
                    initialTouchX = docX;
                    initialTouchY = docY;
                    initialTextSize = selText.textSize;
                    return true;
                }

                // D. Check if touched resize handle of selected signature
                if (selSig != null && selSig.containsResizeHandle(docX, docY, density)) {
                    activeResizingSignature = selSig;
                    initialTouchX = docX;
                    initialTouchY = docY;
                    initialWidth = selSig.width;
                    initialHeight = selSig.height;
                    return true;
                }

                // Traverse annotationItems in REVERSE (top-most / newest to bottom-most / oldest)
                for (int i = annotationItems.size() - 1; i >= 0; i--) {
                    AnnotationItem item = annotationItems.get(i);
                    if (item instanceof TextItem) {
                        TextItem txt = (TextItem) item;
                        if (txt.contains(docX, docY, density)) {
                            pendingTouchText = txt;
                            if (currentToolMode == ToolMode.TEXT || txt.isSelected) {
                                clearSelection();
                                txt.isSelected = true;
                                activeDraggingText = txt;
                                dragOffsetX = docX - txt.x;
                                dragOffsetY = docY - txt.y;
                                invalidate();
                            }
                            return true;
                        }
                    } else if (item instanceof SignatureItem) {
                        SignatureItem sig = (SignatureItem) item;
                        if (sig.contains(docX, docY, density)) {
                            pendingTouchSignature = sig;
                            if (currentToolMode == ToolMode.SIGNATURE || sig.isSelected) {
                                clearSelection();
                                sig.isSelected = true;
                                activeDraggingSignature = sig;
                                dragOffsetX = docX - sig.x;
                                dragOffsetY = docY - sig.y;
                                invalidate();
                            }
                            return true;
                        }
                    }
                }

                // E. Touched empty canvas space
                pendingTouchText = null;
                pendingTouchSignature = null;
                if (currentToolMode == ToolMode.TEXT || currentToolMode == ToolMode.SIGNATURE) {
                    clearSelection();
                    invalidate();
                    if (itemSelectionListener != null) itemSelectionListener.onSelectionCleared();
                    return true;
                }

                // Mode is PEN or HIGHLIGHTER -> Start freehand stroke
                currentPath.reset();
                currentPath.moveTo(docX, docY);
                lastDocX = docX;
                lastDocY = docY;
                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE:
                disallowParentIntercept(true);
                float distMove = (float) Math.hypot(event.getX() - downScreenX, event.getY() - downScreenY);

                // Rotating Text
                if (activeRotatingText != null) {
                    float cx = activeRotatingText.boundingBox.centerX();
                    float cy = activeRotatingText.boundingBox.centerY();
                    float rawAngle = (float) Math.toDegrees(Math.atan2(docY - cy, docX - cx)) + 90f;
                    rawAngle = ((rawAngle % 360f) + 360f) % 360f;
                    float snapped = applyAngleSnapping(rawAngle);
                    activeRotatingText.setRotationAngle(snapped);
                    invalidate();
                    return true;
                }

                // Rotating Signature
                if (activeRotatingSignature != null) {
                    float cx = activeRotatingSignature.x + activeRotatingSignature.width / 2f;
                    float cy = activeRotatingSignature.y + activeRotatingSignature.height / 2f;
                    float rawAngle = (float) Math.toDegrees(Math.atan2(docY - cy, docX - cx)) + 90f;
                    rawAngle = ((rawAngle % 360f) + 360f) % 360f;
                    float snapped = applyAngleSnapping(rawAngle);
                    activeRotatingSignature.setRotationAngle(snapped);
                    invalidate();
                    return true;
                }

                // Resizing Text
                if (activeResizingText != null) {
                    float delta = (docX - initialTouchX);
                    activeResizingText.textSize = Math.max(8f * density, initialTextSize + delta * 0.25f);
                    textPaint.setTextSize(activeResizingText.textSize);
                    textPaint.setTypeface(getTypefaceForFont(getContext(), activeResizingText.fontFamily));
                    textPaint.getTextBounds(activeResizingText.text, 0, activeResizingText.text.length(), activeResizingText.bounds);
                    activeResizingText.boundingBox.set(activeResizingText.getBoundingBox(density));
                    invalidate();
                    return true;
                }

                // Resizing Signature
                if (activeResizingSignature != null) {
                    float delta = (docX - initialTouchX);
                    float newW = Math.max(30f * density, initialWidth + delta);
                    float aspect = initialHeight / Math.max(1f, initialWidth);
                    activeResizingSignature.width = newW;
                    activeResizingSignature.height = newW * aspect;
                    invalidate();
                    return true;
                }

                // Dragging Text
                if (activeDraggingText != null) {
                    activeDraggingText.x = docX - dragOffsetX;
                    activeDraggingText.y = docY - dragOffsetY;
                    activeDraggingText.updateNormalizedPosition(documentWidth, documentHeight);
                    activeDraggingText.boundingBox.set(activeDraggingText.getBoundingBox(density));
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

                // Pending text touched while in PEN/HIGHLIGHTER mode
                if (pendingTouchText != null) {
                    if (distMove > 8f * density) {
                        if (currentToolMode == ToolMode.PEN || currentToolMode == ToolMode.HIGHLIGHTER) {
                            pendingTouchText = null;
                            currentPath.reset();
                            currentPath.moveTo(downDocX, downDocY);
                            currentPath.lineTo(docX, docY);
                            lastDocX = docX;
                            lastDocY = docY;
                            invalidate();
                        } else {
                            clearSelection();
                            pendingTouchText.isSelected = true;
                            activeDraggingText = pendingTouchText;
                            dragOffsetX = downDocX - pendingTouchText.x;
                            dragOffsetY = downDocY - pendingTouchText.y;
                            pendingTouchText = null;
                            invalidate();
                        }
                    }
                    return true;
                }

                // Pending signature touched
                if (pendingTouchSignature != null) {
                    if (distMove > 8f * density) {
                        clearSelection();
                        pendingTouchSignature.isSelected = true;
                        activeDraggingSignature = pendingTouchSignature;
                        dragOffsetX = downDocX - pendingTouchSignature.x;
                        dragOffsetY = downDocY - pendingTouchSignature.y;
                        pendingTouchSignature = null;
                        invalidate();
                    }
                    return true;
                }

                // Freehand stroke drawing
                if (!currentPath.isEmpty()) {
                    float dx = Math.abs(docX - lastDocX);
                    float dy = Math.abs(docY - lastDocY);
                    if (dx >= 2f || dy >= 2f) {
                        currentPath.quadTo(lastDocX, lastDocY, (docX + lastDocX) / 2f, (docY + lastDocY) / 2f);
                        lastDocX = docX;
                        lastDocY = docY;
                        invalidate();
                    }
                    return true;
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                float distUp = (float) Math.hypot(event.getX() - downScreenX, event.getY() - downScreenY);
                boolean isTap = distUp < (10f * density);
                boolean wasManipulating = (activeDraggingText != null || activeDraggingSignature != null
                        || activeResizingText != null || activeResizingSignature != null
                        || activeRotatingText != null || activeRotatingSignature != null
                        || isTwoFingerRotating);

                if (wasManipulating) {
                    notifyActionAdded();
                }

                activeDraggingText = null;
                activeDraggingSignature = null;
                activeResizingText = null;
                activeResizingSignature = null;
                activeRotatingText = null;
                activeRotatingSignature = null;
                isTwoFingerRotating = false;
                lastSnappedAngle = -1f;

                if (isTap) {
                    if (pendingTouchText != null) {
                        TextItem tapped = pendingTouchText;
                        pendingTouchText = null;
                        long now = System.currentTimeMillis();
                        if (lastTappedTextItem == tapped && (now - lastTextTapTime) < DOUBLE_TAP_TIMEOUT_MS) {
                            lastTextTapTime = 0;
                            lastTappedTextItem = null;
                            if (itemSelectionListener != null) {
                                itemSelectionListener.onTextItemDoubleTapped(tapped);
                            }
                        } else {
                            lastTextTapTime = now;
                            lastTappedTextItem = tapped;
                            clearSelection();
                            tapped.isSelected = true;
                            currentToolMode = ToolMode.TEXT;
                            invalidate();
                            if (itemSelectionListener != null) {
                                itemSelectionListener.onTextItemTapped(tapped);
                            }
                        }
                        disallowParentIntercept(false);
                        return true;
                    }

                    if (pendingTouchSignature != null) {
                        SignatureItem tappedSig = pendingTouchSignature;
                        pendingTouchSignature = null;
                        clearSelection();
                        tappedSig.isSelected = true;
                        currentToolMode = ToolMode.SIGNATURE;
                        invalidate();
                        if (itemSelectionListener != null) {
                            itemSelectionListener.onSignatureItemTapped(tappedSig);
                        }
                        disallowParentIntercept(false);
                        return true;
                    }

                    if (currentPath.isEmpty()) {
                        boolean hadSel = getSelectedTextItem() != null || getSelectedSignatureItem() != null;
                        clearSelection();
                        invalidate();
                        if (hadSel && itemSelectionListener != null) {
                            itemSelectionListener.onSelectionCleared();
                        }
                    }
                }

                pendingTouchText = null;
                pendingTouchSignature = null;

                // Finalize freehand stroke if one was active
                if (!currentPath.isEmpty()) {
                    currentPath.lineTo(docX, docY);
                    boolean isHighlighter = (currentToolMode == ToolMode.HIGHLIGHTER);
                    float strokeW = isHighlighter ? currentStrokeWidth * 2.2f : currentStrokeWidth;
                    int finalColor = isHighlighter ? ColorUtils.setAlphaComponent(currentColor, 102) : currentColor;
                    Stroke stroke = new Stroke(new Path(currentPath), finalColor, strokeW, isHighlighter);
                    strokes.add(stroke);
                    annotationItems.add(stroke);
                    actionHistory.add(new AnnotationAction(ActionType.STROKE, stroke));
                    notifyActionAdded();
                    currentPath.reset();
                    invalidate();
                }

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

        float density = getResources().getDisplayMetrics().density;

        // 1. Render all annotation elements sequentially from bottom (oldest) to top (newest)
        for (AnnotationItem item : annotationItems) {
            if (item instanceof Stroke) {
                Stroke s = (Stroke) item;
                Paint p = s.isHighlighter ? highlighterPaint : penPaint;
                p.setColor(s.color);
                p.setStrokeWidth(s.strokeWidth);
                canvas.drawPath(s.path, p);
            } else if (item instanceof SignatureItem) {
                SignatureItem sig = (SignatureItem) item;
                if (sig.bitmap != null && !sig.bitmap.isRecycled()) {
                    canvas.save();
                    float cx = sig.x + sig.width / 2f;
                    float cy = sig.y + sig.height / 2f;
                    canvas.rotate(sig.getRotationAngle(), cx, cy);

                    if (sig.tintColor != 0) {
                        signaturePaint.setColorFilter(new PorterDuffColorFilter(sig.tintColor, PorterDuff.Mode.SRC_IN));
                    } else {
                        signaturePaint.setColorFilter(null);
                    }
                    RectF dst = new RectF(sig.x, sig.y, sig.x + sig.width, sig.y + sig.height);
                    canvas.drawBitmap(sig.bitmap, null, dst, signaturePaint);
                    canvas.restore();
                }
            } else if (item instanceof TextItem) {
                TextItem txt = (TextItem) item;
                textPaint.setTextSize(txt.textSize);
                textPaint.setTypeface(getTypefaceForFont(getContext(), txt.fontFamily));
                textPaint.setFakeBoldText("bold_condensed".equalsIgnoreCase(txt.fontFamily) || "sans".equalsIgnoreCase(txt.fontFamily));
                textPaint.getTextBounds(txt.text, 0, txt.text.length(), txt.bounds);

                RectF pillRect = txt.getBoundingBox(density);
                txt.boundingBox.set(pillRect);

                canvas.save();
                float cx = pillRect.centerX();
                float cy = pillRect.centerY();
                canvas.rotate(txt.getRotationAngle(), cx, cy);

                if (txt.hasBackground) {
                    textBackgroundPaint.setColor(txt.backgroundColor);
                    canvas.drawRoundRect(pillRect, 6f * density, 6f * density, textBackgroundPaint);
                }

                textPaint.setColor(txt.color);
                canvas.drawText(txt.text, txt.x, txt.y, textPaint);
                canvas.restore();
            } else {
                item.draw(canvas);
            }
        }

        // 2. Draw active in-progress stroke on top of completed items
        if (!currentPath.isEmpty()) {
            boolean isHighlighter = (currentToolMode == ToolMode.HIGHLIGHTER);
            Paint p = isHighlighter ? highlighterPaint : penPaint;
            int c = isHighlighter ? ColorUtils.setAlphaComponent(currentColor, 102) : currentColor;
            float w = isHighlighter ? currentStrokeWidth * 2.2f : currentStrokeWidth;
            p.setColor(c);
            p.setStrokeWidth(w);
            canvas.drawPath(currentPath, p);
        }

        // 3. Render selection bounding box and handles ONLY for the active item
        AnnotationItem selectedItem = getSelectedItem();
        if (selectedItem != null) {
            if (selectedItem instanceof TextItem) {
                TextItem txt = (TextItem) selectedItem;
                canvas.save();
                float cx = txt.boundingBox.centerX();
                float cy = txt.boundingBox.centerY();
                canvas.rotate(txt.getRotationAngle(), cx, cy);
                drawBoundingBoxAndHandles(canvas, txt.boundingBox, density);
                canvas.restore();
            } else if (selectedItem instanceof SignatureItem) {
                SignatureItem sig = (SignatureItem) selectedItem;
                canvas.save();
                float cx = sig.x + sig.width / 2f;
                float cy = sig.y + sig.height / 2f;
                canvas.rotate(sig.getRotationAngle(), cx, cy);
                RectF dst = new RectF(sig.x, sig.y, sig.x + sig.width, sig.y + sig.height);
                drawBoundingBoxAndHandles(canvas, dst, density);
                canvas.restore();
            } else {
                selectedItem.drawSelectionHandles(canvas, density);
            }
        }

        canvas.restore();
    }

    private void drawBoundingBoxAndHandles(Canvas canvas, RectF box, float density) {
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

        // 3. Subtle curved rotate icon ↻ inside the handle circle
        float iconR = 5f * density;
        RectF arcBox = new RectF(topCenterX - iconR, stalkEndY - iconR, topCenterX + iconR, stalkEndY + iconR);
        canvas.drawArc(arcBox, 45f, 270f, false, rotateIconPaint);
        drawRotateArrowhead(canvas, topCenterX, stalkEndY, iconR, density);

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
    }

    private void drawRotateArrowhead(Canvas canvas, float cx, float cy, float r, float density) {
        float angleRad = (float) Math.toRadians(45);
        float ax = cx + (float) (r * Math.cos(angleRad));
        float ay = cy + (float) (r * Math.sin(angleRad));

        Path arrow = new Path();
        float arrowSize = 3.5f * density;
        arrow.moveTo(ax, ay - arrowSize);
        arrow.lineTo(ax, ay);
        arrow.lineTo(ax + arrowSize, ay);
        canvas.drawPath(arrow, rotateIconPaint);
    }

    private float applyAngleSnapping(float rawAngle) {
        float[] targets = new float[]{0f, 90f, 180f, 270f, 360f};
        for (float t : targets) {
            float diff = Math.abs(rawAngle - t);
            if (diff <= 5.0f || diff >= 355.0f) {
                float snapped = (t == 360f) ? 0f : t;
                if (lastSnappedAngle != snapped) {
                    lastSnappedAngle = snapped;
                    HapticHelper.snapTick(this);
                }
                return snapped;
            }
        }
        lastSnappedAngle = -1f;
        return rawAngle;
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
        if (baseBitmap == null || baseBitmap.isRecycled()) {
            return baseBitmap;
        }
        if (isEmpty() && baseBitmap.isMutable()) {
            return baseBitmap;
        }

        Bitmap outputBitmap;
        try {
            outputBitmap = baseBitmap.copy(Bitmap.Config.ARGB_8888, true);
        } catch (OutOfMemoryError oom) {
            System.gc();
            try {
                outputBitmap = baseBitmap.copy(Bitmap.Config.ARGB_8888, true);
            } catch (OutOfMemoryError oom2) {
                android.util.Log.e("AnnotationDrawingView", "OOM in flattenOnto", oom2);
                return baseBitmap;
            }
        }

        if (outputBitmap == null) {
            return baseBitmap;
        }

        Canvas canvas = new Canvas(outputBitmap);
        float density = getResources().getDisplayMetrics().density;

        Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);

        Paint sigP = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        Paint flatTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint flatBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        flatBgPaint.setStyle(Paint.Style.FILL);

        // Render elements sequentially from bottom to top
        for (AnnotationItem item : annotationItems) {
            if (item instanceof Stroke) {
                Stroke s = (Stroke) item;
                strokePaint.setColor(s.color);
                strokePaint.setStrokeWidth(s.strokeWidth);
                canvas.drawPath(s.path, strokePaint);
            } else if (item instanceof SignatureItem) {
                SignatureItem sig = (SignatureItem) item;
                if (sig.bitmap != null && !sig.bitmap.isRecycled()) {
                    canvas.save();
                    float cx = sig.x + sig.width / 2f;
                    float cy = sig.y + sig.height / 2f;
                    canvas.rotate(sig.getRotationAngle(), cx, cy);

                    if (sig.tintColor != 0) {
                        sigP.setColorFilter(new PorterDuffColorFilter(sig.tintColor, PorterDuff.Mode.SRC_IN));
                    }
                    RectF dst = new RectF(sig.x, sig.y, sig.x + sig.width, sig.y + sig.height);
                    canvas.drawBitmap(sig.bitmap, null, dst, sigP);
                    canvas.restore();
                }
            } else if (item instanceof TextItem) {
                TextItem txt = (TextItem) item;
                flatTextPaint.setTextSize(txt.textSize);
                flatTextPaint.setTypeface(getTypefaceForFont(getContext(), txt.fontFamily));
                flatTextPaint.setFakeBoldText("bold_condensed".equalsIgnoreCase(txt.fontFamily) || "sans".equalsIgnoreCase(txt.fontFamily));
                flatTextPaint.setColor(txt.color);

                Rect bounds = new Rect();
                flatTextPaint.getTextBounds(txt.text, 0, txt.text.length(), bounds);
                txt.bounds.set(bounds);
                RectF pillRect = txt.getBoundingBox(density);

                canvas.save();
                float cx = pillRect.centerX();
                float cy = pillRect.centerY();
                canvas.rotate(txt.getRotationAngle(), cx, cy);

                if (txt.hasBackground) {
                    flatBgPaint.setColor(txt.backgroundColor);
                    canvas.drawRoundRect(pillRect, 6f * density, 6f * density, flatBgPaint);
                }

                canvas.drawText(txt.text, txt.x, txt.y, flatTextPaint);
                canvas.restore();
            } else {
                item.draw(canvas);
            }
        }

        // In-progress stroke if present
        if (!currentPath.isEmpty()) {
            boolean isHighlighter = (currentToolMode == ToolMode.HIGHLIGHTER);
            int c = isHighlighter ? ColorUtils.setAlphaComponent(currentColor, 85) : currentColor;
            float w = isHighlighter ? currentStrokeWidth * 2.5f : currentStrokeWidth;
            strokePaint.setColor(c);
            strokePaint.setStrokeWidth(w);
            canvas.drawPath(currentPath, strokePaint);
        }

        return outputBitmap;
    }
}