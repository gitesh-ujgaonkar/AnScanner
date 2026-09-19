package com.anscanner.app.ui.pdf;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.pdf.PdfRenderer;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.anscanner.app.util.PdfRendererHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Dedicated single-page e-reader surface with realistic physical page-curl simulation.
 *
 * <p><strong>Key Features:</strong>
 * <ul>
 *   <li>Corner-Aware 2D Page Curl: Top corner, bottom corner, and middle vertical fold deformation.</li>
 *   <li>Dynamic crease gradient: Underneath drop shadow and 3D cylindrical fold highlight.</li>
 *   <li>Curled flap rendering: 2D Householder reflection across the fold line with subtle paper backing.</li>
 *   <li>Touch zones: Left 20% tap (prev), Right 20% tap (next), Center 60% tap (toggle controls).</li>
 *   <li>Paper Warmth Tint, E-Ink Grayscale Carta simulation, and procedural paper grain overlay.</li>
 *   <li>Strict 3-page LRU render cache ([N-1], [N], [N+1]) with instant recycling of off-screen bitmaps.</li>
 * </ul>
 * </p>
 */
public class PageCurlView extends View {

    private static final String TAG = "PageCurlView";

    public interface OnPageTurnListener {
        void onPageChanged(int newPageIndex, int totalPages);
    }

    public interface OnCenterTapListener {
        void onCenterTapped();
    }

    public enum PaperWarmth {
        WHITE(Color.WHITE, 0),
        WARM(Color.parseColor("#FFFBF3E8"), Color.parseColor("#FAF4E8")),
        SEPIA(Color.parseColor("#FFF4ECD8"), Color.parseColor("#EED8B8")),
        DARK(Color.parseColor("#FF222222"), Color.parseColor("#1A1A1A")),
        NIGHT(Color.BLACK, Color.BLACK);

        public final int backgroundColor;
        public final int tintMultiply;

        PaperWarmth(int bg, int tint) {
            this.backgroundColor = bg;
            this.tintMultiply = tint;
        }
    }

    // PDF source and pagination
    private PdfRenderer pdfRenderer;
    private int pageCount = 0;
    private int currentPageIndex = 0;

    // Strict 3-page render cache
    private final Map<Integer, Bitmap> pageCache = new HashMap<>();
    private final ExecutorService renderExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Shaders, tones, and textures
    private PaperWarmth currentWarmth = PaperWarmth.WHITE;
    private boolean isEInkMode = false;
    private boolean isPaperTextureEnabled = true;
    private Bitmap textureBitmap;

    // Paints
    private final Paint pagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint flapBackingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint texturePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Curl touch and geometry state
    private boolean isCurling = false;
    private boolean isAnimating = false;
    private boolean isForwardCurl = true;
    private PointF cornerOrigin = new PointF();
    private PointF touchPos = new PointF();
    private PointF downPos = new PointF();
    private float touchDownTime = 0;

    // Listeners
    private OnPageTurnListener pageTurnListener;
    private OnCenterTapListener centerTapListener;

    public PageCurlView(Context context) {
        this(context, null);
    }

    public PageCurlView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public PageCurlView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setLayerType(LAYER_TYPE_SOFTWARE, null); // Guarantees crisp Path clipping and Matrix reflections

        flapBackingPaint.setStyle(Paint.Style.FILL);
        flapBackingPaint.setColor(Color.parseColor("#EBFBF0D9")); // Warm paper backing

        texturePaint.setStyle(Paint.Style.FILL);
        texturePaint.setAlpha(18); // ~7% subtle organic paper grain
    }

    public void initialize(@NonNull PdfRenderer renderer, int initialPage) {
        this.pdfRenderer = renderer;
        synchronized (this.pdfRenderer) {
            this.pageCount = this.pdfRenderer.getPageCount();
        }
        this.currentPageIndex = Math.max(0, Math.min(initialPage, pageCount - 1));
        clearCache();
        ensureCacheAroundCurrentPage();
        invalidate();
        notifyPageChanged();
    }

    public void setOnPageTurnListener(@Nullable OnPageTurnListener listener) {
        this.pageTurnListener = listener;
    }

    public void setOnCenterTapListener(@Nullable OnCenterTapListener listener) {
        this.centerTapListener = listener;
    }

    public void setPaperWarmth(@NonNull PaperWarmth warmth) {
        this.currentWarmth = warmth;
        updatePaints();
        invalidate();
    }

    @NonNull
    public PaperWarmth getPaperWarmth() {
        return currentWarmth;
    }

    public void setEInkMode(boolean enabled) {
        this.isEInkMode = enabled;
        updatePaints();
        invalidate();
    }

    public boolean isEInkMode() {
        return isEInkMode;
    }

    public void setPaperTextureEnabled(boolean enabled) {
        this.isPaperTextureEnabled = enabled;
        invalidate();
    }

    public boolean isPaperTextureEnabled() {
        return isPaperTextureEnabled;
    }

    public int getCurrentPageIndex() {
        return currentPageIndex;
    }

    public int getPageCount() {
        return pageCount;
    }

    private void updatePaints() {
        if (currentWarmth == PaperWarmth.NIGHT) {
            // OLED Inversion
            ColorMatrix invert = new ColorMatrix(new float[] {
                    -1f, 0, 0, 0, 255f,
                     0, -1f, 0, 0, 255f,
                     0, 0, -1f, 0, 255f,
                     0, 0, 0, 1f, 0
            });
            pagePaint.setColorFilter(new ColorMatrixColorFilter(invert));
        } else if (isEInkMode) {
            // E-Ink pure desaturation + crisp contrast
            ColorMatrix cm = new ColorMatrix();
            cm.setSaturation(0.0f);
            pagePaint.setColorFilter(new ColorMatrixColorFilter(cm));
        } else if (currentWarmth.tintMultiply != 0) {
            // Multiply warm tint onto page surface
            pagePaint.setColorFilter(new PorterDuffColorFilter(currentWarmth.tintMultiply, PorterDuff.Mode.MULTIPLY));
        } else {
            pagePaint.setColorFilter(null);
        }
    }

    // ── 3-Page LRU Render Cache & Memory Management ───────────────────────

    private void clearCache() {
        for (Bitmap bmp : pageCache.values()) {
            if (bmp != null && !bmp.isRecycled()) {
                bmp.recycle();
            }
        }
        pageCache.clear();
    }

    private void ensureCacheAroundCurrentPage() {
        if (pdfRenderer == null || getWidth() <= 0 || getHeight() <= 0) {
            return;
        }

        // 1. Evict any page strictly outside [currentPage - 1, currentPage + 1]
        Iterator<Map.Entry<Integer, Bitmap>> it = pageCache.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Bitmap> entry = it.next();
            int page = entry.getKey();
            if (page < currentPageIndex - 1 || page > currentPageIndex + 1) {
                Bitmap bmp = entry.getValue();
                if (bmp != null && !bmp.isRecycled()) {
                    bmp.recycle();
                }
                it.remove();
            }
        }

        // 2. Pre-render missing pages in window: current, next, prev
        int[] targetPages = new int[] { currentPageIndex, currentPageIndex + 1, currentPageIndex - 1 };
        int viewW = getWidth();
        int viewH = getHeight();

        for (int p : targetPages) {
            if (p >= 0 && p < pageCount && !pageCache.containsKey(p)) {
                renderPageAsync(p, viewW, viewH);
            }
        }
    }

    private void renderPageAsync(int pageIndex, int viewWidth, int viewHeight) {
        renderExecutor.execute(() -> {
            Bitmap rendered = null;
            try {
                synchronized (pdfRenderer) {
                    if (pageIndex < 0 || pageIndex >= pdfRenderer.getPageCount()) return;
                    PdfRenderer.Page page = pdfRenderer.openPage(pageIndex);
                    try {
                        int pw = page.getWidth();
                        int ph = page.getHeight();

                        // Fit page inside viewport preserving document aspect ratio
                        float scale = Math.min((float) viewWidth / pw, (float) viewHeight / ph);
                        int targetW = Math.max(1, Math.round(pw * scale));
                        int targetH = Math.max(1, Math.round(ph * scale));

                        rendered = PdfRendererHelper.renderPageWithWhiteBackground(
                                page, targetW, targetH, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    } finally {
                        page.close();
                    }
                }

                final Bitmap finalBitmap = rendered;
                mainHandler.post(() -> {
                    // Check if still within current active 3-page window
                    if (pageIndex >= currentPageIndex - 1 && pageIndex <= currentPageIndex + 1) {
                        pageCache.put(pageIndex, finalBitmap);
                        invalidate();
                    } else if (finalBitmap != null && !finalBitmap.isRecycled()) {
                        finalBitmap.recycle();
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "Failed to render page " + pageIndex, e);
                if (rendered != null && !rendered.isRecycled()) {
                    rendered.recycle();
                }
            }
        });
    }

    @Nullable
    private Bitmap getPageBitmap(int index) {
        return pageCache.get(index);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0) {
            ensureCacheAroundCurrentPage();
            generatePaperTexture(w, h);
        }
    }

    private void generatePaperTexture(int w, int h) {
        if (textureBitmap != null && !textureBitmap.isRecycled()) {
            textureBitmap.recycle();
        }
        try {
            int tw = Math.min(w, 512);
            int th = Math.min(h, 512);
            textureBitmap = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(textureBitmap);
            c.drawColor(Color.TRANSPARENT);

            Paint p = new Paint();
            p.setColor(Color.DKGRAY);
            p.setAlpha(12);

            // Procedural subtle fiber/grain speckles
            java.util.Random rng = new java.util.Random(1337);
            for (int i = 0; i < 6000; i++) {
                float rx = rng.nextFloat() * tw;
                float ry = rng.nextFloat() * th;
                float rlen = 1f + rng.nextFloat() * 2.5f;
                c.drawCircle(rx, ry, rlen / 2f, p);
            }
        } catch (Exception ignored) {}
    }

    // ── Touch Handling & Gesture Physics ──────────────────────────────────

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (pageCount == 0 || isAnimating) {
            return super.onTouchEvent(event);
        }

        int w = getWidth();
        int h = getHeight();
        float x = event.getX();
        float y = event.getY();

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                downPos.set(x, y);
                touchDownTime = System.currentTimeMillis();

                // Corner-Aware origin detection
                if (x > w * 0.5f) {
                    // Right edge drag -> forward flip (peeling current to reveal next)
                    if (currentPageIndex >= pageCount - 1) {
                        isCurling = false;
                        return true;
                    }
                    isForwardCurl = true;
                    isCurling = true;
                    if (y < h * 0.35f) {
                        cornerOrigin.set(w, 0); // Top-right corner
                    } else if (y > h * 0.65f) {
                        cornerOrigin.set(w, h); // Bottom-right corner
                    } else {
                        cornerOrigin.set(w, y); // Middle-right fold
                    }
                } else {
                    // Left edge drag -> backward flip (peeling current to reveal prev)
                    if (currentPageIndex <= 0) {
                        isCurling = false;
                        return true;
                    }
                    isForwardCurl = false;
                    isCurling = true;
                    if (y < h * 0.35f) {
                        cornerOrigin.set(0, 0); // Top-left corner
                    } else if (y > h * 0.65f) {
                        cornerOrigin.set(0, h); // Bottom-left corner
                    } else {
                        cornerOrigin.set(0, y); // Middle-left fold
                    }
                }

                touchPos.set(x, y);
                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE:
                if (!isCurling) return true;

                // Restrict curl from moving past opposite edge
                if (isForwardCurl) {
                    touchPos.set(Math.min(x, w), y);
                } else {
                    touchPos.set(Math.max(x, 0), y);
                }
                invalidate();
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                float dx = x - downPos.x;
                float dy = y - downPos.y;
                float dist = (float) Math.hypot(dx, dy);
                long duration = System.currentTimeMillis() - (long) touchDownTime;

                // 1. Single Tap Detection (motion < 12dp and tap < 350ms)
                if (dist < 12f * getResources().getDisplayMetrics().density && duration < 350) {
                    isCurling = false;
                    invalidate();
                    handleSingleTap(downPos.x, downPos.y, w);
                    return true;
                }

                // 2. Drag Release Physics (Flick / threshold completion)
                if (isCurling) {
                    float dragProgress;
                    if (isForwardCurl) {
                        dragProgress = (cornerOrigin.x - x) / (float) w;
                    } else {
                        dragProgress = (x - cornerOrigin.x) / (float) w;
                    }

                    boolean shouldComplete = dragProgress > 0.20f;
                    animateCurlRelease(shouldComplete);
                }
                return true;
        }

        return super.onTouchEvent(event);
    }

    private void handleSingleTap(float tapX, float tapY, int viewWidth) {
        if (tapX < viewWidth * 0.20f) {
            // Left 20% -> turn to previous page
            flipToPreviousPage();
        } else if (tapX > viewWidth * 0.80f) {
            // Right 20% -> turn to next page
            flipToNextPage();
        } else {
            // Center 60% -> toggle reader controls overlay
            if (centerTapListener != null) {
                centerTapListener.onCenterTapped();
            }
        }
    }

    public void flipToNextPage() {
        if (currentPageIndex >= pageCount - 1 || isAnimating) return;
        isForwardCurl = true;
        isCurling = true;
        int w = getWidth();
        int h = getHeight();
        cornerOrigin.set(w, h * 0.85f);
        touchPos.set(w - 10, h * 0.85f);
        animateCurlRelease(true);
    }

    public void flipToPreviousPage() {
        if (currentPageIndex <= 0 || isAnimating) return;
        isForwardCurl = false;
        isCurling = true;
        int w = getWidth();
        int h = getHeight();
        cornerOrigin.set(0, h * 0.85f);
        touchPos.set(10, h * 0.85f);
        animateCurlRelease(true);
    }

    private void animateCurlRelease(boolean complete) {
        isAnimating = true;
        int w = getWidth();

        float startX = touchPos.x;
        float startY = touchPos.y;

        float targetX;
        float targetY = cornerOrigin.y;

        if (complete) {
            targetX = isForwardCurl ? -w * 0.3f : w * 1.3f;
        } else {
            targetX = cornerOrigin.x;
        }

        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(280);
        animator.setInterpolator(new DecelerateInterpolator(1.5f));

        animator.addUpdateListener(va -> {
            float frac = (float) va.getAnimatedValue();
            touchPos.x = startX + (targetX - startX) * frac;
            touchPos.y = startY + (targetY - startY) * frac;
            invalidate();
        });

        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                isAnimating = false;
                isCurling = false;

                if (complete) {
                    if (isForwardCurl) {
                        currentPageIndex = Math.min(currentPageIndex + 1, pageCount - 1);
                    } else {
                        currentPageIndex = Math.max(currentPageIndex - 1, 0);
                    }
                    ensureCacheAroundCurrentPage();
                    notifyPageChanged();
                }

                invalidate();
            }
        });

        animator.start();
    }

    private void notifyPageChanged() {
        if (pageTurnListener != null) {
            pageTurnListener.onPageChanged(currentPageIndex, pageCount);
        }
    }

    // ── Drawing & Canvas Page-Curl Deformation ────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        // Background container fill matching current warmth
        canvas.drawColor(currentWarmth.backgroundColor);

        Bitmap currentBmp = getPageBitmap(currentPageIndex);

        if (!isCurling || currentBmp == null) {
            // Static flat single-page book surface
            if (currentBmp != null) {
                drawPageBitmapCentered(canvas, currentBmp, w, h);
            }
            drawPaperTextureOverlay(canvas, w, h);
            return;
        }

        // Active dynamic page curl
        int underneathIndex = isForwardCurl ? currentPageIndex + 1 : currentPageIndex - 1;
        Bitmap underneathBmp = getPageBitmap(underneathIndex);

        renderRealisticPageCurl(canvas, currentBmp, underneathBmp, w, h);
        drawPaperTextureOverlay(canvas, w, h);
    }

    private void drawPageBitmapCentered(Canvas canvas, Bitmap bmp, int viewW, int viewH) {
        if (bmp == null || bmp.isRecycled()) return;

        int bw = bmp.getWidth();
        int bh = bmp.getHeight();
        float left = (viewW - bw) / 2f;
        float top = (viewH - bh) / 2f;

        // Draw soft book drop-shadow around page perimeter
        Paint pageShadow = new Paint();
        pageShadow.setColor(Color.parseColor("#26000000"));
        canvas.drawRect(left - 4, top + 4, left + bw + 4, top + bh + 8, pageShadow);

        canvas.drawBitmap(bmp, left, top, pagePaint);
    }

    private void drawPaperTextureOverlay(Canvas canvas, int w, int h) {
        if (isPaperTextureEnabled && textureBitmap != null && !textureBitmap.isRecycled()) {
            canvas.drawBitmap(textureBitmap, null, new Rect(0, 0, w, h), texturePaint);
        }
    }

    private void renderRealisticPageCurl(Canvas canvas, Bitmap frontBmp, @Nullable Bitmap backBmp, int w, int h) {
        float cx = cornerOrigin.x;
        float cy = cornerOrigin.y;
        float px = touchPos.x;
        float py = touchPos.y;

        // Midpoint of the fold
        float mx = (cx + px) / 2f;
        float my = (cy + py) / 2f;

        float dx = px - cx;
        float dy = py - cy;
        float len = (float) Math.hypot(dx, dy);

        if (len < 4f) {
            drawPageBitmapCentered(canvas, frontBmp, w, h);
            return;
        }

        float nx = dx / len;
        float ny = dy / len;

        // Crease line intersection with screen edges
        List<PointF> intersections = findCreaseIntersections(mx, my, nx, ny, w, h);
        if (intersections.size() < 2) {
            drawPageBitmapCentered(canvas, frontBmp, w, h);
            return;
        }

        PointF p1 = intersections.get(0);
        PointF p2 = intersections.get(1);

        // 1. Construct peeled region path (where underneath page is revealed)
        Path peeledPath = buildPeeledPath(p1, p2, cx, cy, w, h);

        // 2. Construct reflected curled flap path (back of the turning page)
        Path flapPath = buildReflectedFlapPath(p1, p2, mx, my, nx, ny, cx, cy, px, py, w, h);

        // ── LAYER 1: Draw revealed Underneath Page inside peeledPath ─────────
        canvas.save();
        canvas.clipPath(peeledPath);
        if (backBmp != null) {
            drawPageBitmapCentered(canvas, backBmp, w, h);
        } else {
            canvas.drawColor(Color.WHITE);
        }

        // Draw underneath crease drop shadow
        drawCreaseDropShadow(canvas, p1, p2, nx, ny);
        canvas.restore();

        // ── LAYER 2: Draw remaining Front Page (Uncurled area) ───────────────
        canvas.save();
        canvas.clipOutPath(peeledPath);
        drawPageBitmapCentered(canvas, frontBmp, w, h);
        canvas.restore();

        // ── LAYER 3: Draw Curled Flap (Back of turning page) ─────────────────
        canvas.save();
        canvas.clipPath(flapPath);

        // 2D Householder reflection transformation matrix across crease line
        Matrix reflectMatrix = new Matrix();
        float[] vals = new float[9];
        vals[0] = 1f - 2f * nx * nx;
        vals[1] = -2f * nx * ny;
        vals[2] = 2f * (mx * nx + my * ny) * nx;
        vals[3] = -2f * nx * ny;
        vals[4] = 1f - 2f * ny * ny;
        vals[5] = 2f * (mx * nx + my * ny) * ny;
        vals[6] = 0f;
        vals[7] = 0f;
        vals[8] = 1f;
        reflectMatrix.setValues(vals);

        // Draw mirrored reverse page with paper backing
        canvas.concat(reflectMatrix);
        drawPageBitmapCentered(canvas, frontBmp, w, h);
        canvas.drawRect(0, 0, w, h, flapBackingPaint);

        // Curvature 3D fold highlight along the crease ridge
        drawCreaseHighlight(canvas, p1, p2, nx, ny);

        canvas.restore();
    }

    private List<PointF> findCreaseIntersections(float mx, float my, float nx, float ny, int w, int h) {
        List<PointF> points = new ArrayList<>();

        // Top: y = 0
        if (Math.abs(nx) > 1e-4f) {
            float x = mx + (my * ny) / nx;
            if (x >= 0 && x <= w) points.add(new PointF(x, 0));
        }
        // Bottom: y = h
        if (Math.abs(nx) > 1e-4f) {
            float x = mx - ((h - my) * ny) / nx;
            if (x >= 0 && x <= w) points.add(new PointF(x, h));
        }
        // Left: x = 0
        if (Math.abs(ny) > 1e-4f) {
            float y = my + (mx * nx) / ny;
            if (y >= 0 && y <= h) points.add(new PointF(0, y));
        }
        // Right: x = w
        if (Math.abs(ny) > 1e-4f) {
            float y = my - ((w - mx) * nx) / ny;
            if (y >= 0 && y <= h) points.add(new PointF(w, y));
        }

        // Deduplicate close points
        List<PointF> unique = new ArrayList<>();
        for (PointF pt : points) {
            boolean dup = false;
            for (PointF u : unique) {
                if (Math.hypot(pt.x - u.x, pt.y - u.y) < 2f) {
                    dup = true;
                    break;
                }
            }
            if (!dup) unique.add(pt);
        }

        return unique;
    }

    private Path buildPeeledPath(PointF p1, PointF p2, float cx, float cy, int w, int h) {
        Path path = new Path();
        path.moveTo(p1.x, p1.y);
        path.lineTo(p2.x, p2.y);

        // Include the curled corner (cx, cy)
        path.lineTo(cx, cy);

        path.close();
        return path;
    }

    private Path buildReflectedFlapPath(
            PointF p1, PointF p2, float mx, float my, float nx, float ny,
            float cx, float cy, float px, float py, int w, int h) {

        Path path = new Path();
        path.moveTo(p1.x, p1.y);
        path.lineTo(p2.x, p2.y);

        // Reflect the corner (cx, cy) -> which lands precisely on touch point (px, py)
        path.lineTo(px, py);

        path.close();
        return path;
    }

    private void drawCreaseDropShadow(Canvas canvas, PointF p1, PointF p2, float nx, float ny) {
        float shadowWidth = 28f * getResources().getDisplayMetrics().density;

        LinearGradient gradient = new LinearGradient(
                p1.x, p1.y,
                p1.x - nx * shadowWidth, p1.y - ny * shadowWidth,
                new int[] { Color.parseColor("#73000000"), Color.TRANSPARENT },
                null,
                Shader.TileMode.CLAMP
        );
        shadowPaint.setShader(gradient);

        Path shadowPath = new Path();
        shadowPath.moveTo(p1.x, p1.y);
        shadowPath.lineTo(p2.x, p2.y);
        shadowPath.lineTo(p2.x - nx * shadowWidth, p2.y - ny * shadowWidth);
        shadowPath.lineTo(p1.x - nx * shadowWidth, p1.y - ny * shadowWidth);
        shadowPath.close();

        canvas.drawPath(shadowPath, shadowPaint);
    }

    private void drawCreaseHighlight(Canvas canvas, PointF p1, PointF p2, float nx, float ny) {
        float hlWidth = 14f * getResources().getDisplayMetrics().density;

        LinearGradient gradient = new LinearGradient(
                p1.x, p1.y,
                p1.x + nx * hlWidth, p1.y + ny * hlWidth,
                new int[] { Color.parseColor("#4DFFFFFF"), Color.TRANSPARENT },
                null,
                Shader.TileMode.CLAMP
        );
        highlightPaint.setShader(gradient);

        Path hlPath = new Path();
        hlPath.moveTo(p1.x, p1.y);
        hlPath.lineTo(p2.x, p2.y);
        hlPath.lineTo(p2.x + nx * hlWidth, p2.y + ny * hlWidth);
        hlPath.lineTo(p1.x + nx * hlWidth, p1.y + ny * hlWidth);
        hlPath.close();

        canvas.drawPath(hlPath, highlightPaint);
    }

    public void cleanup() {
        clearCache();
        if (!renderExecutor.isShutdown()) {
            renderExecutor.shutdown();
        }
        if (textureBitmap != null && !textureBitmap.isRecycled()) {
            textureBitmap.recycle();
            textureBitmap = null;
        }
    }
}
