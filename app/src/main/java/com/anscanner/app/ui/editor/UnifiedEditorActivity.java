package com.anscanner.app.ui.editor;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.res.ColorStateList;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.anscanner.app.R;
import com.anscanner.app.databinding.ActivityUnifiedEditorBinding;
import com.anscanner.app.model.draft.DraftManager;
import com.anscanner.app.model.draft.EditHistoryManager;
import com.anscanner.app.model.draft.ScanEditState;
import com.anscanner.app.model.ocr.OcrPage;
import com.anscanner.app.processing.DocumentDetector;
import com.anscanner.app.processing.ImageProcessor;
import com.anscanner.app.service.CacheManager;
import com.anscanner.app.service.OcrHelper;
import com.anscanner.app.ui.camera.CameraActivity;
import com.anscanner.app.ui.crop.CropOverlayView;
import com.anscanner.app.ui.crop.CropPreviewActivity;
import com.anscanner.app.ui.custom.AnnotationDrawingView;
import com.anscanner.app.ui.custom.DrawingOverlayView;
import com.anscanner.app.ui.custom.TouchImageView;
import com.anscanner.app.ui.save.SaveScanBottomSheet;
import com.anscanner.app.ui.dialog.CustomColorPickerDialog;
import com.anscanner.app.util.FontUtils;
import com.anscanner.app.util.InsetsHelper;
import android.text.Editable;
import android.text.TextWatcher;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.mlkit.vision.text.Text;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Stack;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Unified multi-page canvas editor supporting:
 * <ul>
 *   <li>Standardized ViewPager2 workspace for Batch Scan, PDF Editing, and Single Capture</li>
 *   <li>Horizontal swipe between pages with bottom thumbnail sync</li>
 *   <li>Touch lock: swiping disabled during active drawing or cropping</li>
 *   <li>Per-page Crop, Drawing (Pen/Highlighter), Text label, Signature, Rotate, Delete</li>
 *   <li>Direct flattening into temporary image files upon "Done" or before page swipe</li>
 *   <li>Final PDF generation and export via PdfGenerator</li>
 * </ul>
 */
public class UnifiedEditorActivity extends AppCompatActivity {
    private static final String TAG = "UnifiedEditorActivity";

    public static final String EXTRA_PAGE_PATHS = "extra_page_paths";
    public static final String EXTRA_ORIGINAL_PAGE_PATHS = "extra_original_page_paths";
    public static final String EXTRA_PAGE_INDEX = "extra_page_index";
    public static final String EXTRA_IMAGE_PATH = "extra_image_path";
    public static final String EXTRA_MODE = "extra_mode";
    public static final String EXTRA_PDF_URI = "extra_pdf_uri";
    public static final String EXTRA_START_ANNOTATE = "extra_start_annotate";
    public static final String EXTRA_IS_ADDING_PAGE = "extra_is_adding_page";
    public static final String EXTRA_FROM_REVIEW = "extra_from_review";
    public static final String EXTRA_ENABLE_CROP_DEFAULT = "extra_enable_crop_default";
    public static final String EXTRA_RESUME_DRAFT = "extra_resume_draft";

    public static final String MODE_BATCH_SCAN = "BATCH_SCAN";
    public static final String MODE_PDF_EDIT = "PDF_EDIT";
    public static final String MODE_SINGLE_SCAN = "SINGLE_SCAN";

    private ActivityUnifiedEditorBinding binding;
    private PageEditorAdapter pageAdapter;
    private ThumbnailStripAdapter thumbAdapter;
    private EditorViewModel viewModel;

    private ArrayList<String> pagePaths;
    private ArrayList<String> originalPagePaths;
    private int pageIndex = 0;
    private String editorMode = MODE_SINGLE_SCAN;
    private boolean isAddingPage = false;
    private boolean isFromReview = false;
    private String sessionId = UUID.randomUUID().toString();

    private final ActivityResultLauncher<Intent> addPageCameraLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    handlePageAppended(result.getData());
                }
            });

    // Editing states
    private boolean isDrawingMode = false;
    private boolean isCropMode = false;
    private boolean isFilterMode = false;
    private AnnotationDrawingView.ToolMode currentToolMode = AnnotationDrawingView.ToolMode.PEN;
    private int currentDrawingColor = Color.parseColor("#48BB78"); // Mint Green
    private float currentStrokeWidth = 8f; // 8dp
    private int strokeWidthPreset = 1; // 0=Fine(4dp), 1=Medium(8dp), 2=Bold(16dp)

    public enum DocumentFilter {
        ORIGINAL,
        MAGIC,
        SCAN_ENHANCE,
        BW,
        GRAYSCALE,
        SHARPEN,
        INVERT
    }

    private FilterPreviewAdapter filterAdapter;
    private final Map<Integer, Map<DocumentFilter, Bitmap>> filterPreviewCache = new HashMap<>();
    private final Map<Integer, Point[]> savedCornersMap = new HashMap<>();
    private ExecutorService executor;
    private Handler mainHandler;
    private volatile boolean isBusy = false;

    // ── Non-Destructive Undo / Redo & Draft Architecture ──────────────
    private final Map<Integer, EditHistoryManager> pageHistoryMap = new HashMap<>();
    private final Map<Integer, ScanEditState> lastRenderedBitmapState = new HashMap<>();

    public EditHistoryManager getHistoryManager(int targetIndex) {
        EditHistoryManager hm = pageHistoryMap.get(targetIndex);
        if (hm == null) {
            hm = new EditHistoryManager(ScanEditState.createDefault());
            pageHistoryMap.put(targetIndex, hm);
        }
        return hm;
    }

    private void updateUndoButtonState() {
        EditHistoryManager hm = getHistoryManager(pageIndex);
        boolean canUndo = (hm != null && hm.canUndo());
        binding.btnUndo.setEnabled(canUndo);
        binding.btnUndo.setAlpha(canUndo ? 1.0f : 0.3f);

        if (binding.btnRedo != null) {
            boolean canRedo = (hm != null && hm.canRedo());
            binding.btnRedo.setEnabled(canRedo);
            binding.btnRedo.setAlpha(canRedo ? 1.0f : 0.3f);
        }
    }

    private void onAnnotationActionAdded(int targetPageIndex) {
        PageEditorAdapter.PageViewHolder vh = PageEditorAdapter.getViewHolder(binding.viewPagerPages, targetPageIndex);
        if (vh == null || vh.drawingOverlay == null) return;
        EditHistoryManager hm = getHistoryManager(targetPageIndex);
        ScanEditState curr = hm.getCurrentState();
        AnnotationDrawingView.AnnotationState newAnnos = vh.drawingOverlay.getAnnotationState();
        ScanEditState next = (curr != null) ? curr.withAnnotations(newAnnos)
                : new ScanEditState(null, 0, ScanEditState.FILTER_ORIGINAL, System.currentTimeMillis(), newAnnos);
        hm.pushState(next);
        updateUndoButtonState();
    }

    private void saveCurrentPageAnnotationsToHistory() {
        PageEditorAdapter.PageViewHolder vh = PageEditorAdapter.getViewHolder(binding.viewPagerPages, pageIndex);
        if (vh == null || vh.drawingOverlay == null) return;
        EditHistoryManager hm = getHistoryManager(pageIndex);
        ScanEditState curr = hm.getCurrentState();
        AnnotationDrawingView.AnnotationState newAnnos = vh.drawingOverlay.getAnnotationState();
        ScanEditState next = (curr != null) ? curr.withAnnotations(newAnnos)
                : new ScanEditState(null, 0, ScanEditState.FILTER_ORIGINAL, System.currentTimeMillis(), newAnnos);
        hm.pushState(next);
        updateUndoButtonState();
    }

    @NonNull
    public static Matrix computeCropTransformMatrix(int srcW, int srcH, @NonNull float[] flatCorners) {
        if (flatCorners == null || flatCorners.length < 8 || srcW <= 0 || srcH <= 0) {
            return new Matrix();
        }

        org.opencv.core.Point[] opencvCorners = new org.opencv.core.Point[4];
        for (int i = 0; i < 4; i++) {
            opencvCorners[i] = new org.opencv.core.Point(
                    Math.max(0.0, Math.min((double) srcW, (double) flatCorners[i * 2] * srcW)),
                    Math.max(0.0, Math.min((double) srcH, (double) flatCorners[i * 2 + 1] * srcH))
            );
        }

        opencvCorners = DocumentDetector.orderCorners(opencvCorners);

        double widthTop = Math.hypot(opencvCorners[0].x - opencvCorners[1].x, opencvCorners[0].y - opencvCorners[1].y);
        double widthBottom = Math.hypot(opencvCorners[3].x - opencvCorners[2].x, opencvCorners[3].y - opencvCorners[2].y);
        int targetWidth = (int) Math.round(Math.max(widthTop, widthBottom));

        double heightLeft = Math.hypot(opencvCorners[0].x - opencvCorners[3].x, opencvCorners[0].y - opencvCorners[3].y);
        double heightRight = Math.hypot(opencvCorners[1].x - opencvCorners[2].x, opencvCorners[1].y - opencvCorners[2].y);
        int targetHeight = (int) Math.round(Math.max(heightLeft, heightRight));

        targetWidth = Math.max(1, targetWidth);
        targetHeight = Math.max(1, targetHeight);

        float[] srcPts = new float[]{
                (float) opencvCorners[0].x, (float) opencvCorners[0].y,
                (float) opencvCorners[1].x, (float) opencvCorners[1].y,
                (float) opencvCorners[2].x, (float) opencvCorners[2].y,
                (float) opencvCorners[3].x, (float) opencvCorners[3].y
        };

        float[] dstPts = new float[]{
                0f, 0f,
                (float) targetWidth, 0f,
                (float) targetWidth, (float) targetHeight,
                0f, (float) targetHeight
        };

        Matrix matrix = new Matrix();
        matrix.setPolyToPoly(srcPts, 0, dstPts, 0, 4);
        return matrix;
    }

    private void renderStateNonDestructively(int targetPageIndex, @Nullable ScanEditState state, @Nullable Runnable onComplete) {
        if (state == null || targetPageIndex < 0 || targetPageIndex >= pagePaths.size()) {
            if (onComplete != null) onComplete.run();
            return;
        }

        // 1. Zero-latency check: If only vector annotations changed, update overlay instantly on UI thread
        ScanEditState last = lastRenderedBitmapState.get(targetPageIndex);
        boolean bitmapParamsMatch = (last != null)
                && Arrays.equals(last.getNormalizedCropPoints(), state.getNormalizedCropPoints())
                && last.getRotationDegrees() == state.getRotationDegrees()
                && Objects.equals(last.getFilterMode(), state.getFilterMode());

        if (bitmapParamsMatch) {
            PageEditorAdapter.PageViewHolder vh = PageEditorAdapter.getViewHolder(binding.viewPagerPages, targetPageIndex);
            if (vh != null) {
                vh.drawingOverlay.setAnnotationState(state.getAnnotations());
                boolean hasAnnos = state.getAnnotations() != null && !state.getAnnotations().isEmpty();
                vh.drawingOverlay.setVisibility(hasAnnos ? View.VISIBLE : (isDrawingMode ? View.VISIBLE : View.GONE));
            }
            updateUndoButtonState();
            if (onComplete != null) onComplete.run();
            return;
        }

        isBusy = true;
        if (binding.pbFilterApplying != null) {
            binding.pbFilterApplying.setVisibility(View.VISIBLE);
        }

        final String origPath = (targetPageIndex < originalPagePaths.size())
                ? originalPagePaths.get(targetPageIndex) : pagePaths.get(targetPageIndex);

        executor.execute(() -> {
            try {
                Bitmap base = CacheManager.loadBitmap(origPath);
            if (base == null) {
                base = CacheManager.loadBitmap(pagePaths.get(targetPageIndex));
            }
            if (base == null) {
                runOnUiThread(() -> {
                    isBusy = false;
                    if (binding.pbFilterApplying != null) binding.pbFilterApplying.setVisibility(View.GONE);
                    if (onComplete != null) onComplete.run();
                });
                return;
            }

            // 1. Perspective Crop Warp
            float[] normCorners = state.getNormalizedCropPoints();
            if (normCorners != null && normCorners.length == 8) {
                int bw = base.getWidth();
                int bh = base.getHeight();
                org.opencv.core.Point[] opencvCorners = new org.opencv.core.Point[4];
                for (int i = 0; i < 4; i++) {
                    opencvCorners[i] = new org.opencv.core.Point(
                            Math.max(0.0, Math.min((double) bw, (double) normCorners[i * 2] * bw)),
                            Math.max(0.0, Math.min((double) bh, (double) normCorners[i * 2 + 1] * bh))
                    );
                }
                opencvCorners = DocumentDetector.orderCorners(opencvCorners);
                Bitmap cropped = ImageProcessor.perspectiveWarp(base, opencvCorners);
                base.recycle();
                base = cropped;
            }

            // 2. Post-Crop Rotation
            int rotation = ((state.getRotationDegrees() % 360) + 360) % 360;
            if (rotation != 0) {
                Matrix matrix = new Matrix();
                matrix.postRotate(rotation);
                Bitmap rotated = Bitmap.createBitmap(base, 0, 0, base.getWidth(), base.getHeight(), matrix, true);
                base.recycle();
                base = rotated;
            }

            // 3. Document Filter
            String filterMode = state.getFilterMode();
            if (filterMode != null && !filterMode.equals("ORIGINAL")) {
                try {
                    DocumentFilter filter = DocumentFilter.valueOf(filterMode);
                    Bitmap filtered = runFilterAlgorithm(filter, base);
                    base.recycle();
                    base = filtered;
                } catch (Exception e) {
                    Log.e(TAG, "Error applying filter: " + filterMode, e);
                }
            }

            if (base == null) {
                runOnUiThread(() -> {
                    isBusy = false;
                    if (binding.pbFilterApplying != null) binding.pbFilterApplying.setVisibility(View.GONE);
                    if (onComplete != null) onComplete.run();
                });
                return;
            }

            String renderedPath = CacheManager.saveTempBitmap(
                    UnifiedEditorActivity.this, base, "page_" + targetPageIndex + "_rendered_" + UUID.randomUUID().toString());
            base.recycle();

            runOnUiThread(() -> {
                isBusy = false;
                if (binding.pbFilterApplying != null) {
                    binding.pbFilterApplying.setVisibility(View.GONE);
                }

                if (renderedPath != null) {
                    pagePaths.set(targetPageIndex, renderedPath);

                    if (normCorners != null && normCorners.length == 8) {
                        if (viewModel != null) {
                            viewModel.setNormalizedCornersForPage(targetPageIndex, normCorners);
                            if (targetPageIndex == pageIndex) {
                                viewModel.setActivePoints(normCorners);
                            }
                        }
                    } else {
                        savedCornersMap.remove(targetPageIndex);
                        if (viewModel != null) {
                            viewModel.setNormalizedCornersForPage(targetPageIndex, null);
                            if (targetPageIndex == pageIndex) {
                                viewModel.setActivePoints(null);
                            }
                        }
                    }

                    if (filterMode != null) {
                        try {
                            DocumentFilter filter = DocumentFilter.valueOf(filterMode);
                            if (viewModel != null) viewModel.setSelectedFilter(filter);
                            if (filterAdapter != null) filterAdapter.setSelectedFilter(filter);
                        } catch (Exception ignored) {}
                    }

                    invalidateFilterPreview(targetPageIndex);
                    pageAdapter.notifyItemChanged(targetPageIndex);
                    thumbAdapter.notifyItemChanged(targetPageIndex);

                    PageEditorAdapter.PageViewHolder vh = PageEditorAdapter.getViewHolder(binding.viewPagerPages, targetPageIndex);
                    if (vh != null) {
                        Bitmap bmp = CacheManager.loadBitmap(renderedPath);
                        if (bmp != null) {
                            vh.ivPageImage.setImageBitmap(bmp);
                            vh.cropOverlay.setImageView(vh.ivPageImage);
                            vh.drawingOverlay.bindImageView(vh.ivPageImage);
                            vh.drawingOverlay.setDocumentDimensions(bmp.getWidth(), bmp.getHeight());
                        }
                        vh.drawingOverlay.setAnnotationState(state.getAnnotations());
                        boolean hasAnnos = state.getAnnotations() != null && !state.getAnnotations().isEmpty();
                        vh.drawingOverlay.setVisibility(hasAnnos ? View.VISIBLE : (isDrawingMode ? View.VISIBLE : View.GONE));
                    }
                    lastRenderedBitmapState.put(targetPageIndex, state);
                }

                updateUndoButtonState();
                if (onComplete != null) onComplete.run();
            });
            } catch (Throwable t) {
                Log.e(TAG, "Error in renderStateNonDestructively", t);
                runOnUiThread(() -> {
                    isBusy = false;
                    if (binding != null && binding.pbFilterApplying != null) {
                        binding.pbFilterApplying.setVisibility(View.GONE);
                    }
                    if (onComplete != null) onComplete.run();
                });
            }
        });
    }

    private boolean hasEdits() {
        for (int i = 0; i < pagePaths.size(); i++) {
            EditHistoryManager hm = pageHistoryMap.get(i);
            if (hm != null && hm.hasEdits()) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        InsetsHelper.applyEdgeToEdge(this);
        super.onCreate(savedInstanceState);
        binding = ActivityUnifiedEditorBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        InsetsHelper.handleSystemBars(binding.getRoot(), binding.topBar, binding.bottomContainer);

        executor = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());
        viewModel = new ViewModelProvider(this).get(EditorViewModel.class);

        Intent intent = getIntent();
        boolean isResumingDraft = intent != null && intent.getBooleanExtra(DraftManager.EXTRA_RESUME_DRAFT, false);
        DraftManager.DraftSession draftSession = null;
        if (isResumingDraft) {
            draftSession = DraftManager.loadDraft(this);
        }

        if (draftSession != null && !draftSession.pagePaths.isEmpty()) {
            editorMode = draftSession.editorMode;
            isAddingPage = false;
            isFromReview = false;
            pagePaths = draftSession.pagePaths;
            originalPagePaths = draftSession.originalPagePaths;
            pageIndex = draftSession.pageIndex;
            pageHistoryMap.clear();
            pageHistoryMap.putAll(draftSession.historyMap);

            viewModel.setPagePaths(pagePaths);
            viewModel.setOriginalPagePaths(originalPagePaths);
            viewModel.setPageIndex(pageIndex);
            viewModel.setEditorMode(editorMode);
            viewModel.setAddingPage(isAddingPage);
            viewModel.setFromReview(isFromReview);

            EditHistoryManager hm = getHistoryManager(pageIndex);
            ScanEditState state = hm.getCurrentState();
            if (state != null) {
                if (state.getNormalizedCropPoints() != null) {
                    viewModel.setNormalizedCornersForPage(pageIndex, state.getNormalizedCropPoints());
                    viewModel.setActivePoints(state.getNormalizedCropPoints());
                }
                if (state.getFilterMode() != null) {
                    try {
                        viewModel.setSelectedFilter(DocumentFilter.valueOf(state.getFilterMode()));
                    } catch (Exception ignored) {}
                }
            }
        } else if (viewModel.getPagePaths() != null && !viewModel.getPagePaths().isEmpty()) {
            editorMode = viewModel.getEditorMode();
            if (editorMode == null) editorMode = MODE_SINGLE_SCAN;
            isAddingPage = viewModel.isAddingPage();
            isFromReview = viewModel.isFromReview();
            pagePaths = viewModel.getPagePaths();
            originalPagePaths = viewModel.getOriginalPagePaths();
            pageIndex = viewModel.getPageIndex();
            isCropMode = viewModel.isCropMode();
            isDrawingMode = viewModel.isDrawingMode();
            isFilterMode = viewModel.isFilterMode();
        } else if (savedInstanceState != null) {
            editorMode = savedInstanceState.getString(EXTRA_MODE, MODE_SINGLE_SCAN);
            isAddingPage = savedInstanceState.getBoolean(EXTRA_IS_ADDING_PAGE, false);
            isFromReview = savedInstanceState.getBoolean(EXTRA_FROM_REVIEW, false);
            pagePaths = savedInstanceState.getStringArrayList(EXTRA_PAGE_PATHS);
            originalPagePaths = savedInstanceState.getStringArrayList(EXTRA_ORIGINAL_PAGE_PATHS);
            pageIndex = savedInstanceState.getInt(EXTRA_PAGE_INDEX, 0);
            isCropMode = savedInstanceState.getBoolean("isCropMode", false);
            isDrawingMode = savedInstanceState.getBoolean("isDrawingMode", false);
            isFilterMode = savedInstanceState.getBoolean("isFilterMode", false);
            sessionId = savedInstanceState.getString("sessionId", UUID.randomUUID().toString());
            float[] restoredActivePts = savedInstanceState.getFloatArray("activePoints");
            if (restoredActivePts != null) {
                viewModel.setActivePoints(restoredActivePts);
                viewModel.setNormalizedCornersForPage(pageIndex, restoredActivePts);
            }
            String filterName = savedInstanceState.getString("selectedFilter");
            if (filterName != null) {
                try {
                    viewModel.setSelectedFilter(DocumentFilter.valueOf(filterName));
                } catch (Exception ignored) {}
            }
            viewModel.setPagePaths(pagePaths);
            viewModel.setOriginalPagePaths(originalPagePaths);
            viewModel.setPageIndex(pageIndex);
            viewModel.setEditorMode(editorMode);
            viewModel.setAddingPage(isAddingPage);
            viewModel.setFromReview(isFromReview);
            viewModel.setCropMode(isCropMode);
            viewModel.setDrawingMode(isDrawingMode);
            viewModel.setFilterMode(isFilterMode);
        } else {
            if (intent != null) {
                editorMode = intent.getStringExtra(EXTRA_MODE);
                isAddingPage = intent.getBooleanExtra(EXTRA_IS_ADDING_PAGE, false);
                isFromReview = intent.getBooleanExtra(EXTRA_FROM_REVIEW, false);
                if (intent.hasExtra(CameraActivity.EXTRA_SESSION_ID)) {
                    sessionId = intent.getStringExtra(CameraActivity.EXTRA_SESSION_ID);
                }
            }
            if (editorMode == null) editorMode = MODE_SINGLE_SCAN;

            // Resolve input paths or PDF URI
            Uri pdfUri = intent != null ? intent.getData() : null;
            if (pdfUri == null && intent != null && intent.hasExtra(EXTRA_PDF_URI)) {
                try {
                    Object extra = intent.getExtras() != null ? intent.getExtras().get(EXTRA_PDF_URI) : null;
                    if (extra instanceof Uri) {
                        pdfUri = (Uri) extra;
                    } else if (extra instanceof String) {
                        pdfUri = Uri.parse((String) extra);
                    }
                } catch (Exception ignored) {}
            }
            String singleImagePath = intent != null ? intent.getStringExtra(EXTRA_IMAGE_PATH) : null;
            if (pdfUri == null && singleImagePath != null && singleImagePath.toLowerCase().endsWith(".pdf")) {
                pdfUri = Uri.fromFile(new File(singleImagePath));
            }

            if (intent != null && intent.hasExtra(EXTRA_PAGE_PATHS)) {
                pagePaths = intent.getStringArrayListExtra(EXTRA_PAGE_PATHS);
            } else if (pdfUri != null) {
                pagePaths = CropPreviewActivity.extractAllPagesFromPdf(this, pdfUri);
            } else {
                pagePaths = new ArrayList<>();
                if (singleImagePath != null) {
                    pagePaths.add(singleImagePath);
                }
            }

            if (pagePaths == null) {
                pagePaths = new ArrayList<>();
            }

            if (intent != null && intent.hasExtra(EXTRA_ORIGINAL_PAGE_PATHS)) {
                originalPagePaths = intent.getStringArrayListExtra(EXTRA_ORIGINAL_PAGE_PATHS);
            } else {
                originalPagePaths = new ArrayList<>(pagePaths);
            }

            pageIndex = intent != null ? intent.getIntExtra(EXTRA_PAGE_INDEX, 0) : 0;

            viewModel.setPagePaths(pagePaths);
            viewModel.setOriginalPagePaths(originalPagePaths);
            viewModel.setPageIndex(pageIndex);
            viewModel.setEditorMode(editorMode);
            viewModel.setAddingPage(isAddingPage);
            viewModel.setFromReview(isFromReview);
            if (pdfUri != null) {
                viewModel.setCurrentImageUri(pdfUri);
            } else if (!pagePaths.isEmpty()) {
                viewModel.setCurrentImageUri(Uri.fromFile(new File(pagePaths.get(pageIndex))));
            }
        }

        if (pagePaths == null) {
            pagePaths = new ArrayList<>();
        }
        if (originalPagePaths == null) {
            originalPagePaths = new ArrayList<>(pagePaths);
        }

        if (pageIndex < 0 || pageIndex >= pagePaths.size()) {
            pageIndex = 0;
        }

        for (int i = 0; i < pagePaths.size(); i++) {
            if (!pageHistoryMap.containsKey(i)) {
                pageHistoryMap.put(i, new EditHistoryManager(ScanEditState.createDefault()));
            }
            EditHistoryManager hm = pageHistoryMap.get(i);
            ScanEditState st = (hm != null && hm.getCurrentState() != null)
                    ? hm.getCurrentState() : ScanEditState.createDefault();
            lastRenderedBitmapState.put(i, st);
        }

        if (pagePaths.isEmpty()) {
            Toast.makeText(this, R.string.error_generic, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        setupThumbnailStrip();
        setupViewPager();
        setupTopBar();
        setupBottomToolbar();
        setupAnnotationBar();
        setupTextToolbar();
        setupFilterBar();
        updateTitle();
        updateNavigationButtons();
        updateSwipeLock();

        // Read pre-detected corners if passed from CameraActivity
        if (intent != null) {
            double[] cornerArray = intent.getDoubleArrayExtra(CameraActivity.EXTRA_DETECTED_CORNERS);
            if (cornerArray != null && cornerArray.length == 8) {
                Point[] corners = new Point[4];
                for (int i = 0; i < 4; i++) {
                    corners[i] = new Point((int) Math.round(cornerArray[i * 2]), (int) Math.round(cornerArray[i * 2 + 1]));
                }
                savedCornersMap.put(pageIndex, corners);
            }

            // Check if restored or opened directly into annotation or crop mode
            if (isCropMode) {
                binding.viewPagerPages.post(this::enterCropMode);
            } else if (isDrawingMode) {
                binding.getRoot().post(this::enterDrawingMode);
            } else if (isFilterMode) {
                binding.getRoot().post(this::enterFilterMode);
            } else if (intent.getBooleanExtra(EXTRA_START_ANNOTATE, false)) {
                binding.getRoot().post(this::enterDrawingMode);
            } else if (intent.getBooleanExtra(EXTRA_ENABLE_CROP_DEFAULT, false)) {
                binding.viewPagerPages.post(this::enterCropMode);
            }
        }
        restoreLensOverlayState(savedInstanceState);
    }

    private void restoreLensOverlayState(@Nullable Bundle savedInstanceState) {
        if (savedInstanceState == null || !savedInstanceState.getBoolean("KEY_LENS_ACTIVE", false)) {
            return;
        }
        OcrPage savedPage = savedInstanceState.getParcelable("KEY_LENS_OCR_PAGE");
        int selStart = savedInstanceState.getInt("KEY_LENS_SEL_START", -1);
        int selEnd = savedInstanceState.getInt("KEY_LENS_SEL_END", -1);

        if (savedPage != null) {
            binding.lensOverlay.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                PageEditorAdapter.PageViewHolder vh = PageEditorAdapter.getViewHolder(binding.viewPagerPages, pageIndex);
                RectF displayRect = vh != null ? vh.ivPageImage.getDisplayRect() : null;
                binding.lensOverlay.setTargetRect(displayRect);
                binding.lensOverlay.setOcrPage(savedPage);
                if (selStart >= 0 && selEnd >= 0) {
                    binding.lensOverlay.setSelection(selStart, selEnd);
                }
                binding.lensOverlay.setVisibility(View.VISIBLE);
            });
        }
    }

    private void setupViewPager() {
        if (binding == null || binding.viewPagerPages == null) return;
        pageAdapter = new PageEditorAdapter(pagePaths, null);
        binding.viewPagerPages.setAdapter(pageAdapter);
        binding.viewPagerPages.setOffscreenPageLimit(1);
        pageAdapter.setOnViewHolderBoundListener((holder, position) -> {
            EditHistoryManager hm = pageHistoryMap.get(position);
            ScanEditState state = (hm != null) ? hm.getCurrentState() : null;
            AnnotationDrawingView.AnnotationState annos = (state != null) ? state.getAnnotations() : null;

            holder.drawingOverlay.bindImageView(holder.ivPageImage);
            holder.drawingOverlay.setAnnotationState(annos);
            holder.drawingOverlay.setOnActionAddedListener(() -> onAnnotationActionAdded(position));
            holder.drawingOverlay.setOnItemSelectionListener(new AnnotationDrawingView.OnItemSelectionListener() {
                @Override
                public void onTextItemTapped(@NonNull AnnotationDrawingView.TextItem item) {
                    showTextToolbar(item);
                }

                @Override
                public void onTextItemDoubleTapped(@NonNull AnnotationDrawingView.TextItem item) {
                    showQuickTextEditDialog(item);
                }

                @Override
                public void onSignatureItemTapped(@NonNull AnnotationDrawingView.SignatureItem item) {
                    selectDrawingColor(item.tintColor);
                }

                @Override
                public void onSelectionCleared() {
                    hideTextToolbar();
                }
            });

            if (isCropMode && position == pageIndex) {
                holder.ivPageImage.setScale(1.0f, false);
                holder.ivPageImage.setZoomable(false);
                holder.cropOverlay.setImageView(holder.ivPageImage);
                holder.drawingOverlay.setToolMode(AnnotationDrawingView.ToolMode.NONE);
                holder.drawingOverlay.setVisibility(annos != null && !annos.isEmpty() ? View.VISIBLE : View.GONE);
                prepareCropForPage(position, holder);
            } else if (isDrawingMode && position == pageIndex) {
                holder.drawingOverlay.setVisibility(View.VISIBLE);
                holder.drawingOverlay.setToolMode(currentToolMode != AnnotationDrawingView.ToolMode.NONE ? currentToolMode : AnnotationDrawingView.ToolMode.PEN);
                holder.drawingOverlay.setStrokeColor(currentDrawingColor);
                holder.drawingOverlay.setStrokeWidth(currentStrokeWidth);
            } else {
                holder.drawingOverlay.setToolMode(AnnotationDrawingView.ToolMode.NONE);
                holder.drawingOverlay.setVisibility(annos != null && !annos.isEmpty() ? View.VISIBLE : View.GONE);
            }
        });
        pageAdapter.setOnBitmapLoadedListener((holder, position, bitmap) -> {
            holder.cropOverlay.setImageView(holder.ivPageImage);
            holder.drawingOverlay.bindImageView(holder.ivPageImage);
            holder.drawingOverlay.setDocumentDimensions(bitmap.getWidth(), bitmap.getHeight());

            EditHistoryManager hm = pageHistoryMap.get(position);
            ScanEditState state = (hm != null) ? hm.getCurrentState() : null;
            AnnotationDrawingView.AnnotationState annos = (state != null) ? state.getAnnotations() : null;
            if (annos != null && !annos.isEmpty()) {
                holder.drawingOverlay.setAnnotationState(annos);
                holder.drawingOverlay.setVisibility(View.VISIBLE);
            }

            if (isCropMode && position == pageIndex) {
                holder.ivPageImage.setScale(1.0f, false);
                holder.ivPageImage.setZoomable(false);
                prepareCropForPage(position, holder);
            } else if (isDrawingMode && position == pageIndex) {
                holder.drawingOverlay.setVisibility(View.VISIBLE);
            }
        });
        binding.viewPagerPages.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                onPageSwitched(position);
            }
        });

        if (pageIndex > 0 && pageIndex < pagePaths.size()) {
            binding.viewPagerPages.setCurrentItem(pageIndex, false);
        }
    }

    private void scrollToPage(int targetPageIndex, boolean smooth) {
        if (targetPageIndex < 0 || targetPageIndex >= pagePaths.size()) return;
        pageIndex = targetPageIndex;
        updateTitle();
        if (thumbAdapter != null) {
            thumbAdapter.setSelectedPosition(pageIndex);
        }
        if (binding != null && binding.rvThumbnails != null) {
            binding.rvThumbnails.smoothScrollToPosition(pageIndex);
        }
        updateNavigationButtons();
        updateUndoButtonState();

        if (binding != null && binding.viewPagerPages != null) {
            binding.viewPagerPages.setCurrentItem(targetPageIndex, smooth);
        }
    }

    private void setupThumbnailStrip() {
        if (binding == null || binding.rvThumbnails == null) return;
        thumbAdapter = new ThumbnailStripAdapter(pagePaths, pageIndex, position -> {
            if (position >= 0 && position < pagePaths.size()) {
                saveCurrentPageAnnotationsToHistory();
                if (isDrawingMode) {
                    exitDrawingMode();
                } else if (isCropMode) {
                    exitCropMode();
                } else if (isFilterMode) {
                    exitFilterMode();
                }
                scrollToPage(position, true);
            }
        }, this::launchAddPageCamera);
        binding.rvThumbnails.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        binding.rvThumbnails.setAdapter(thumbAdapter);
    }

    private void onPageSwitched(int newPosition) {
        if (newPosition < 0 || newPosition >= pagePaths.size()) return;

        // Auto-save any active drawing before switching and dismiss annotation/filter menu
        saveCurrentPageAnnotationsToHistory();
        if (isDrawingMode) {
            exitDrawingMode();
        } else if (isCropMode) {
            exitCropMode();
        } else if (isFilterMode) {
            exitFilterMode();
        }

        pageIndex = newPosition;
        if (viewModel != null) viewModel.setPageIndex(newPosition);
        updateTitle();
        if (thumbAdapter != null) {
            thumbAdapter.setSelectedPosition(pageIndex);
        }
        if (binding != null && binding.rvThumbnails != null) {
            binding.rvThumbnails.smoothScrollToPosition(pageIndex);
        }

        updateNavigationButtons();
        updateUndoButtonState();
    }

    private void updateTitle() {
        if (binding == null || binding.tvTitle == null) return;
        binding.tvTitle.setText(getString(R.string.crop_title, pageIndex + 1, Math.max(1, pagePaths.size())));
    }

    private void updateNavigationButtons() {
        if (binding == null) return;
        if (binding.btnPrevPage != null) {
            binding.btnPrevPage.setVisibility(pageIndex > 0 ? View.VISIBLE : View.GONE);
        }
        if (binding.btnNextPage != null) {
            binding.btnNextPage.setVisibility(pageIndex < pagePaths.size() - 1 ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * Disable ViewPager2 horizontal swiping gestures while in an active crop or drawing state.
     */
    private void updateSwipeLock() {
        if (binding == null || binding.viewPagerPages == null) return;
        boolean enabled = !isDrawingMode && !isCropMode;
        binding.viewPagerPages.setUserInputEnabled(enabled);
    }

    private void setupTopBar() {
        binding.btnClose.setOnClickListener(v -> handleExit());

        binding.btnPrevPage.setOnClickListener(v -> {
            if (pageIndex > 0) {
                if (isDrawingMode) {
                    commitCurrentAnnotations();
                    exitDrawingMode();
                } else if (isCropMode) {
                    exitCropMode();
                } else if (isFilterMode) {
                    exitFilterMode();
                }
                scrollToPage(pageIndex - 1, true);
            }
        });

        binding.btnNextPage.setOnClickListener(v -> {
            if (pageIndex < pagePaths.size() - 1) {
                if (isDrawingMode) {
                    commitCurrentAnnotations();
                    exitDrawingMode();
                } else if (isCropMode) {
                    exitCropMode();
                } else if (isFilterMode) {
                    exitFilterMode();
                }
                scrollToPage(pageIndex + 1, true);
            }
        });

        binding.btnUndo.setOnClickListener(v -> {
            if (isBusy) return;
            EditHistoryManager hm = getHistoryManager(pageIndex);
            if (hm != null && hm.canUndo()) {
                ScanEditState prevState = hm.undo();
                renderStateNonDestructively(pageIndex, prevState, this::updateUndoButtonState);
            }
        });

        if (binding.btnRedo != null) {
            binding.btnRedo.setOnClickListener(v -> {
                if (isBusy) return;
                EditHistoryManager hm = getHistoryManager(pageIndex);
                if (hm != null && hm.canRedo()) {
                    ScanEditState nextState = hm.redo();
                    renderStateNonDestructively(pageIndex, nextState, this::updateUndoButtonState);
                }
            });
        }

        binding.btnExtractText.setOnClickListener(v -> extractTextFromCurrentScan());
        binding.tvTitle.setOnClickListener(v -> showJumpToPageDialog());

        binding.btnSaveDocument.setOnClickListener(v -> {
            if (isBusy) return;
            if (isFilterMode) {
                exitFilterMode();
            }
            commitAllActiveAnnotations();
            if (isDrawingMode) {
                exitDrawingMode();
            } else if (isCropMode) {
                commitCrop(this::finishAndExport);
                return;
            }
            finishAndExport();
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (binding.lensOverlay.getVisibility() == View.VISIBLE) {
                    dismissLensOverlay();
                } else if (isFilterMode) {
                    exitFilterMode();
                } else if (isDrawingMode) {
                    commitCurrentAnnotations();
                    exitDrawingMode();
                } else if (isCropMode) {
                    exitCropMode();
                } else {
                    saveCurrentPageAnnotationsToHistory();
                    handleExit();
                }
            }
        });
    }

    // ── Append Page Flow ──────────────────────────────────────────────

    private void launchAddPageCamera() {
        Log.d(TAG, "launchAddPageCamera triggered");

        saveCurrentPageAnnotationsToHistory();
        if (isFilterMode) {
            exitFilterMode();
        }
        if (isDrawingMode) {
            commitCurrentAnnotations();
            exitDrawingMode();
        } else if (isCropMode) {
            exitCropMode();
        }

        Intent intent = new Intent(this, CameraActivity.class);
        intent.putExtra(CameraActivity.EXTRA_APPEND_MODE, true);
        intent.putExtra(CameraActivity.EXTRA_IS_ADDING_PAGE, true);
        intent.putExtra(CameraActivity.EXTRA_SESSION_ID, sessionId);
        addPageCameraLauncher.launch(intent);
    }

    private void handlePageAppended(@NonNull Intent data) {
        ArrayList<String> newPaths = data.getStringArrayListExtra(EXTRA_PAGE_PATHS);
        if (newPaths == null || newPaths.isEmpty()) {
            String singlePath = data.getStringExtra(CameraActivity.EXTRA_IMAGE_PATH);
            if (singlePath == null) {
                singlePath = data.getStringExtra(CameraActivity.EXTRA_CROPPED_PATH);
            }
            if (singlePath != null) {
                newPaths = new ArrayList<>();
                newPaths.add(singlePath);
            }
        }

        if (newPaths == null || newPaths.isEmpty()) return;

        int firstNewIndex = pagePaths.size();
        double[] cornerArray = data.getDoubleArrayExtra(CameraActivity.EXTRA_DETECTED_CORNERS);

        for (int i = 0; i < newPaths.size(); i++) {
            String path = newPaths.get(i);
            int targetIdx = pagePaths.size();
            pagePaths.add(path);
            originalPagePaths.add(path);
            pageHistoryMap.put(targetIdx, new EditHistoryManager(ScanEditState.createDefault()));
            lastRenderedBitmapState.put(targetIdx, ScanEditState.createDefault());

            if (i == 0 && cornerArray != null && cornerArray.length == 8) {
                Point[] corners = new Point[4];
                for (int c = 0; c < 4; c++) {
                    corners[c] = new Point((int) Math.round(cornerArray[c * 2]), (int) Math.round(cornerArray[c * 2 + 1]));
                }
                savedCornersMap.put(targetIdx, corners);
            }
        }

        if (viewModel != null) {
            viewModel.setPagePaths(pagePaths);
            viewModel.setOriginalPagePaths(originalPagePaths);
        }

        if (pageAdapter != null) {
            pageAdapter.notifyDataSetChanged();
        }
        if (thumbAdapter != null) {
            thumbAdapter.notifyDataSetChanged();
        }

        scrollToPage(firstNewIndex, true);
        updateTitle();
        updateNavigationButtons();
        updateUndoButtonState();

        DraftManager.saveDraftAsync(this, originalPagePaths, pagePaths, pageHistoryMap, pageIndex, editorMode);
        Toast.makeText(this, R.string.page_added, Toast.LENGTH_SHORT).show();
    }

    private void setupBottomToolbar() {
        binding.btnCrop.setOnClickListener(v -> onCropClicked());
        binding.btnFilter.setOnClickListener(v -> onFilterClicked());
        binding.btnAnnotate.setOnClickListener(v -> onAnnotateClicked());
        binding.btnTextSign.setOnClickListener(v -> showTextOrSignatureChooser());
        binding.btnRotate.setOnClickListener(v -> rotateCurrentPage());
        binding.btnDelete.setOnClickListener(v -> showDeleteConfirmation());
    }

    // â”€â”€ Crop Mode â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private void onCropClicked() {
        if (isBusy) return;

        if (!isCropMode) {
            enterCropMode();
            Toast.makeText(this, "Adjust edges & tap Apply to crop", Toast.LENGTH_SHORT).show();
        } else {
            // Apply crop
            commitCrop(null);
        }
    }

    private void enterCropMode() {
        if (isBusy || isCropMode) return;

        if (isFilterMode) {
            exitFilterMode();
        }
        saveCurrentPageAnnotationsToHistory();
        if (isDrawingMode) {
            exitDrawingMode();
        }
        isCropMode = true;
        if (viewModel != null) viewModel.setCropMode(true);
        updateSwipeLock();

        binding.tvCropLabel.setText(R.string.action_apply);
        binding.ivCropIcon.setImageResource(R.drawable.ic_check);

        PageEditorAdapter.PageViewHolder vh = PageEditorAdapter.getViewHolder(binding.viewPagerPages, pageIndex);
        if (vh != null) {
            vh.ivPageImage.setScale(1.0f, false);
            vh.ivPageImage.setZoomable(false);
            vh.cropOverlay.setImageView(vh.ivPageImage);
            vh.drawingOverlay.setToolMode(AnnotationDrawingView.ToolMode.NONE);
            prepareCropForPage(pageIndex, vh);
        } else {
            tryPrepareCropWithRetry(pageIndex, 5);
        }
    }

    private void tryPrepareCropWithRetry(int index, int remainingAttempts) {
        PageEditorAdapter.PageViewHolder vh = PageEditorAdapter.getViewHolder(binding.viewPagerPages, index);
        if (vh != null) {
            vh.ivPageImage.setScale(1.0f, false);
            vh.ivPageImage.setZoomable(false);
            vh.cropOverlay.setImageView(vh.ivPageImage);
            prepareCropForPage(index, vh);
        } else if (remainingAttempts > 0 && binding != null && binding.viewPagerPages != null) {
            binding.viewPagerPages.postDelayed(() -> {
                if (isCropMode && pageIndex == index) {
                    tryPrepareCropWithRetry(index, remainingAttempts - 1);
                }
            }, 60);
        }
    }

    private void prepareCropForPage(int index, PageEditorAdapter.PageViewHolder vh) {
        isBusy = true;
        executor.execute(() -> {
            try {
                Point[] corners = savedCornersMap.get(index);
            float[] computedNorm = null;
            String currentPath = pagePaths.get(index);

            if (corners == null) {
                Bitmap bmp = CacheManager.loadBitmap(currentPath);
                if (bmp != null) {
                    org.opencv.core.Point[] detected = DocumentDetector.getInstance(UnifiedEditorActivity.this)
                            .detectCorners(bmp, true);
                    if (detected == null) {
                        detected = DocumentDetector.getDefaultCorners(bmp.getWidth(), bmp.getHeight());
                    }
                    corners = CropPreviewActivity.toAndroidPoints(detected);
                    savedCornersMap.put(index, corners);

                    int bw = bmp.getWidth();
                    int bh = bmp.getHeight();
                    if (bw > 0 && bh > 0) {
                        computedNorm = new float[8];
                        for (int i = 0; i < 4; i++) {
                            computedNorm[i * 2] = Math.max(0f, Math.min(1f, (float) detected[i].x / bw));
                            computedNorm[i * 2 + 1] = Math.max(0f, Math.min(1f, (float) detected[i].y / bh));
                        }
                    }
                    bmp.recycle();
                }
            } else {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(currentPath, opts);
                if (opts.outWidth > 0 && opts.outHeight > 0) {
                    computedNorm = new float[8];
                    for (int i = 0; i < 4; i++) {
                        computedNorm[i * 2] = Math.max(0f, Math.min(1f, (float) corners[i].x / opts.outWidth));
                        computedNorm[i * 2 + 1] = Math.max(0f, Math.min(1f, (float) corners[i].y / opts.outHeight));
                    }
                }
            }

            final Point[] finalCorners = corners;
            final float[] savedNorm = (viewModel != null) ? viewModel.getNormalizedCornersForPage(index) : null;
            final float[] finalNorm = (savedNorm != null) ? savedNorm : computedNorm;
            runOnUiThread(() -> {
                isBusy = false;
                if (vh != null) {
                    vh.ivPageImage.setScale(1.0f, false);
                    vh.ivPageImage.setZoomable(false);
                    vh.cropOverlay.setImageView(vh.ivPageImage);
                    if (finalNorm != null) {
                        vh.cropOverlay.setNormalizedCornersFlat(finalNorm);
                    } else if (finalCorners != null) {
                        vh.cropOverlay.setCorners(finalCorners);
                    }
                    float[] flat = vh.cropOverlay.getNormalizedCornersFlat();
                    if (flat != null && viewModel != null) {
                        viewModel.setNormalizedCornersForPage(index, flat);
                        viewModel.setActivePoints(flat);
                    }
                    vh.cropOverlay.resetCornerMoved();
                    vh.cropOverlay.setOnCornerDragListener(new CropOverlayView.OnCornerDragListener() {
                        @Override
                        public void onCornerDragStarted(int cornerIndex, float x, float y) {}

                        @Override
                        public void onCornerDragging(int cornerIndex, float x, float y) {}

                        @Override
                        public void onCornerDragEnded() {
                            float[] flat = vh.cropOverlay.getNormalizedCornersFlat();
                            if (flat != null && viewModel != null) {
                                viewModel.setNormalizedCornersForPage(index, flat);
                                viewModel.setActivePoints(flat);
                            }
                            Point[] currentCorners = vh.cropOverlay.getCornerPoints();
                            if (currentCorners != null) {
                                savedCornersMap.put(index, currentCorners);
                            }
                        }

                        @Override
                        public void onNormalizedCornersUpdated(float[] flatNormalizedCorners) {
                            if (flatNormalizedCorners != null && viewModel != null) {
                                viewModel.setNormalizedCornersForPage(index, flatNormalizedCorners);
                                viewModel.setActivePoints(flatNormalizedCorners);
                            }
                        }
                    });
                    vh.cropOverlay.setVisibility(View.VISIBLE);
                }
            });
            } catch (Throwable t) {
                Log.e(TAG, "Error in prepareCropForPage", t);
                runOnUiThread(() -> isBusy = false);
            }
        });
    }

    private void commitCrop(@Nullable Runnable onComplete) {
        PageEditorAdapter.PageViewHolder vh = PageEditorAdapter.getViewHolder(binding.viewPagerPages, pageIndex);
        PointF[] normCorners = vh != null ? vh.cropOverlay.getNormalizedCorners() : null;
        if (normCorners == null || normCorners.length != 4) {
            float[] savedFlat = (viewModel != null) ? viewModel.getNormalizedCornersForPage(pageIndex) : null;
            if (savedFlat != null && savedFlat.length == 8) {
                normCorners = new PointF[4];
                for (int i = 0; i < 4; i++) {
                    normCorners[i] = new PointF(savedFlat[i * 2], savedFlat[i * 2 + 1]);
                }
            }
        }

        if (normCorners == null || normCorners.length != 4) {
            normCorners = new PointF[]{
                    new PointF(0.05f, 0.05f),
                    new PointF(0.95f, 0.05f),
                    new PointF(0.95f, 0.95f),
                    new PointF(0.05f, 0.95f)
            };
        }

        final float[] flatCorners = new float[8];
        for (int i = 0; i < 4; i++) {
            flatCorners[i * 2] = normCorners[i].x;
            flatCorners[i * 2 + 1] = normCorners[i].y;
        }

        final int targetPageIndex = pageIndex;
        EditHistoryManager hm = getHistoryManager(targetPageIndex);
        ScanEditState curr = hm.getCurrentState();

        // 1. Determine base image dimensions to compute accurate projective homography
        final String origPath = (targetPageIndex < originalPagePaths.size())
                ? originalPagePaths.get(targetPageIndex) : pagePaths.get(targetPageIndex);
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(origPath, opts);
        int srcW = opts.outWidth > 0 ? opts.outWidth : 1000;
        int srcH = opts.outHeight > 0 ? opts.outHeight : 1000;

        // 2. Compute 3x3 projective transform matrix
        Matrix cropMatrix = computeCropTransformMatrix(srcW, srcH, flatCorners);

        // 3. Remap vector annotations without rasterization
        AnnotationDrawingView.AnnotationState currentAnnos = null;
        if (vh != null && !vh.drawingOverlay.isEmpty()) {
            currentAnnos = vh.drawingOverlay.getAnnotationState();
        } else if (curr != null) {
            currentAnnos = curr.getAnnotations();
        }

        AnnotationDrawingView.AnnotationState remappedAnnos =
                AnnotationDrawingView.transformState(currentAnnos, cropMatrix, this);

        // 4. Create and push next ScanEditState
        ScanEditState next = (curr != null) ? curr.withCrop(flatCorners, remappedAnnos)
                : new ScanEditState(flatCorners, 0, ScanEditState.FILTER_ORIGINAL, System.currentTimeMillis(), remappedAnnos);
        hm.pushState(next);

        renderStateNonDestructively(targetPageIndex, next, () -> {
            Toast.makeText(UnifiedEditorActivity.this, "Crop applied", Toast.LENGTH_SHORT).show();
            exitCropMode();
            if (onComplete != null) onComplete.run();
        });
    }

    private void exitCropMode() {
        isCropMode = false;
        if (viewModel != null) viewModel.setCropMode(false);
        PageEditorAdapter.PageViewHolder vh = PageEditorAdapter.getViewHolder(binding.viewPagerPages, pageIndex);
        if (vh != null) {
            vh.ivPageImage.setZoomable(true);
            vh.cropOverlay.resetCornerMoved();
            vh.cropOverlay.setVisibility(View.GONE);
            EditHistoryManager hm = getHistoryManager(pageIndex);
            ScanEditState curr = (hm != null) ? hm.getCurrentState() : null;
            AnnotationDrawingView.AnnotationState annos = (curr != null) ? curr.getAnnotations() : null;
            vh.drawingOverlay.setVisibility((annos != null && !annos.isEmpty()) ? View.VISIBLE : View.GONE);
        }
        updateSwipeLock();
        binding.tvCropLabel.setText(R.string.crop_action_crop);
        binding.ivCropIcon.setImageResource(R.drawable.ic_crop);
    }

    // â”€â”€ Annotation / Drawing Mode â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private void onAnnotateClicked() {
        if (isBusy) return;

        if (!isDrawingMode) {
            enterDrawingMode();
        } else {
            commitCurrentAnnotations();
            exitDrawingMode();
        }
    }

    private void enterDrawingMode() {
        if (isFilterMode) {
            exitFilterMode();
        }
        if (isCropMode) {
            exitCropMode();
        }
        isDrawingMode = true;
        if (viewModel != null) viewModel.setDrawingMode(true);
        updateSwipeLock();

        binding.layoutAnnotationBar.setVisibility(View.VISIBLE);
        updateUndoButtonState();

        PageEditorAdapter.PageViewHolder vh = PageEditorAdapter.getViewHolder(binding.viewPagerPages, pageIndex);
        if (vh != null) {
            vh.drawingOverlay.setVisibility(View.VISIBLE);
            vh.drawingOverlay.bindImageView(vh.ivPageImage);
            vh.drawingOverlay.setToolMode(currentToolMode != AnnotationDrawingView.ToolMode.NONE ? currentToolMode : AnnotationDrawingView.ToolMode.PEN);
            vh.drawingOverlay.setStrokeColor(currentDrawingColor);
            vh.drawingOverlay.setStrokeWidth(currentStrokeWidth);
            vh.drawingOverlay.setOnActionAddedListener(() -> onAnnotationActionAdded(pageIndex));
            vh.drawingOverlay.setOnItemSelectionListener(new AnnotationDrawingView.OnItemSelectionListener() {
                @Override
                public void onTextItemTapped(@NonNull AnnotationDrawingView.TextItem item) {
                    showTextToolbar(item);
                }

                @Override
                public void onTextItemDoubleTapped(@NonNull AnnotationDrawingView.TextItem item) {
                    showQuickTextEditDialog(item);
                }

                @Override
                public void onSignatureItemTapped(@NonNull AnnotationDrawingView.SignatureItem item) {
                    selectDrawingColor(item.tintColor);
                }

                @Override
                public void onSelectionCleared() {
                    hideTextToolbar();
                }
            });
        }
    }

    private void exitDrawingMode() {
        isDrawingMode = false;
        if (viewModel != null) viewModel.setDrawingMode(false);
        binding.layoutAnnotationBar.setVisibility(View.GONE);
        hideTextToolbar();
        updateSwipeLock();
        updateUndoButtonState();

        PageEditorAdapter.PageViewHolder vh = PageEditorAdapter.getViewHolder(binding.viewPagerPages, pageIndex);
        if (vh != null) {
            vh.drawingOverlay.setToolMode(AnnotationDrawingView.ToolMode.NONE);
            EditHistoryManager hm = getHistoryManager(pageIndex);
            ScanEditState curr = (hm != null) ? hm.getCurrentState() : null;
            AnnotationDrawingView.AnnotationState annos = (curr != null) ? curr.getAnnotations() : null;
            vh.drawingOverlay.setVisibility((annos != null && !annos.isEmpty()) ? View.VISIBLE : View.GONE);
        }
    }

    private void commitCurrentAnnotations() {
        saveCurrentPageAnnotationsToHistory();
        Toast.makeText(this, R.string.annotate_saved, Toast.LENGTH_SHORT).show();
    }

    private void commitAllActiveAnnotations() {
        for (int i = 0; i < pagePaths.size(); i++) {
            PageEditorAdapter.PageViewHolder vh = PageEditorAdapter.getViewHolder(binding.viewPagerPages, i);
            if (vh != null && vh.drawingOverlay != null && !vh.drawingOverlay.isEmpty()) {
                EditHistoryManager hm = getHistoryManager(i);
                ScanEditState curr = hm.getCurrentState();
                AnnotationDrawingView.AnnotationState annos = vh.drawingOverlay.getAnnotationState();
                ScanEditState next = (curr != null) ? curr.withAnnotations(annos)
                        : new ScanEditState(null, 0, ScanEditState.FILTER_ORIGINAL, System.currentTimeMillis(), annos);
                hm.pushState(next);
            }
        }
    }

    private void setupAnnotationBar() {
        binding.btnToolPen.setOnClickListener(v -> {
            updateSwipeLock();
            currentToolMode = AnnotationDrawingView.ToolMode.PEN;
            binding.btnToolPen.setColorFilter(ContextCompat.getColor(this, R.color.accent_mint));
            binding.btnToolHighlighter.setColorFilter(ContextCompat.getColor(this, R.color.text_secondary));
            PageEditorAdapter.PageViewHolder vh = PageEditorAdapter.getViewHolder(binding.viewPagerPages, pageIndex);
            if (vh != null) vh.drawingOverlay.setToolMode(currentToolMode);
        });

        binding.btnToolHighlighter.setOnClickListener(v -> {
            updateSwipeLock();
            currentToolMode = AnnotationDrawingView.ToolMode.HIGHLIGHTER;
            binding.btnToolHighlighter.setColorFilter(ContextCompat.getColor(this, R.color.accent_mint));
            binding.btnToolPen.setColorFilter(ContextCompat.getColor(this, R.color.text_secondary));
            PageEditorAdapter.PageViewHolder vh = PageEditorAdapter.getViewHolder(binding.viewPagerPages, pageIndex);
            if (vh != null) vh.drawingOverlay.setToolMode(currentToolMode);
        });

        // Brush Size Drawer toggle & real-time slider
        float density = getResources().getDisplayMetrics().density;
        int initialDp = Math.round(currentStrokeWidth / density);
        if (initialDp <= 0) initialDp = 8;
        binding.btnStrokeWidth.setText(initialDp + " dp");
        binding.tvBrushSizeValue.setText(initialDp + " dp");
        binding.sliderBrushSize.setValue(Math.max(2.0f, Math.min(40.0f, initialDp)));

        binding.btnStrokeWidth.setOnClickListener(v -> {
            boolean isVisible = binding.layoutBrushSizeDrawer.getVisibility() == View.VISIBLE;
            binding.layoutBrushSizeDrawer.setVisibility(isVisible ? View.GONE : View.VISIBLE);
        });

        binding.sliderBrushSize.addOnChangeListener((slider, value, fromUser) -> {
            if (fromUser) {
                currentStrokeWidth = value * density;
                int dpVal = Math.round(value);
                binding.btnStrokeWidth.setText(dpVal + " dp");
                binding.tvBrushSizeValue.setText(dpVal + " dp");

                // Update circular indicator preview
                int circleSize = (int) Math.max(4 * density, Math.min(32 * density, value * density * 0.8f));
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(circleSize, circleSize, Gravity.CENTER);
                binding.viewBrushPreviewCircle.setLayoutParams(lp);

                PageEditorAdapter.PageViewHolder vh = PageEditorAdapter.getViewHolder(binding.viewPagerPages, pageIndex);
                if (vh != null) vh.drawingOverlay.setStrokeWidth(currentStrokeWidth);
            }
        });

        binding.btnClearAnnotation.setOnClickListener(v -> {
            PageEditorAdapter.PageViewHolder vh = PageEditorAdapter.getViewHolder(binding.viewPagerPages, pageIndex);
            if (vh != null) vh.drawingOverlay.clear();
        });

        binding.btnDoneAnnotation.setOnClickListener(v -> {
            commitCurrentAnnotations();
            exitDrawingMode();
        });

        setupColorPalette();
    }

    private void setupColorPalette() {
        // Color selection: 6 slots + custom color
        binding.containerColorMint.setOnClickListener(v -> selectDrawingColor(Color.parseColor("#68D391")));
        binding.colorMint.setOnClickListener(v -> selectDrawingColor(Color.parseColor("#68D391")));

        binding.containerColorRed.setOnClickListener(v -> selectDrawingColor(Color.parseColor("#EF4444")));
        binding.colorRed.setOnClickListener(v -> selectDrawingColor(Color.parseColor("#EF4444")));

        binding.containerColorBlack.setOnClickListener(v -> selectDrawingColor(Color.parseColor("#111827")));
        binding.colorBlack.setOnClickListener(v -> selectDrawingColor(Color.parseColor("#111827")));

        binding.containerColorWhite.setOnClickListener(v -> selectDrawingColor(Color.WHITE));
        binding.colorWhite.setOnClickListener(v -> selectDrawingColor(Color.WHITE));

        binding.containerColorYellow.setOnClickListener(v -> selectDrawingColor(Color.parseColor("#FACC15")));
        binding.colorYellow.setOnClickListener(v -> selectDrawingColor(Color.parseColor("#FACC15")));

        binding.containerColorBlue.setOnClickListener(v -> selectDrawingColor(Color.parseColor("#3B82F6")));
        binding.colorBlue.setOnClickListener(v -> selectDrawingColor(Color.parseColor("#3B82F6")));

        // Custom Color Button
        binding.containerColorCustom.setOnClickListener(v -> openCustomDrawingColorPicker());
        binding.btnCustomDrawingColor.setOnClickListener(v -> openCustomDrawingColorPicker());
    }

    private void openCustomDrawingColorPicker() {
        boolean isHighlighter = (currentToolMode == AnnotationDrawingView.ToolMode.HIGHLIGHTER);
        CustomColorPickerDialog.show(this, currentDrawingColor, isHighlighter, chosen -> {
            selectDrawingColor(chosen);
            binding.ringCustom.setVisibility(View.VISIBLE);
        });
    }

    private void selectDrawingColor(int color) {
        currentDrawingColor = color;

        // Update outer outline selection ring indicators
        binding.ringMint.setVisibility(color == Color.parseColor("#68D391") ? View.VISIBLE : View.GONE);
        binding.ringRed.setVisibility(color == Color.parseColor("#EF4444") ? View.VISIBLE : View.GONE);
        binding.ringBlack.setVisibility(color == Color.parseColor("#111827") ? View.VISIBLE : View.GONE);
        binding.ringWhite.setVisibility(color == Color.WHITE ? View.VISIBLE : View.GONE);
        binding.ringYellow.setVisibility(color == Color.parseColor("#FACC15") ? View.VISIBLE : View.GONE);
        binding.ringBlue.setVisibility(color == Color.parseColor("#3B82F6") ? View.VISIBLE : View.GONE);
        boolean isPreset = (color == Color.parseColor("#68D391") || color == Color.parseColor("#EF4444")
                || color == Color.parseColor("#111827") || color == Color.WHITE
                || color == Color.parseColor("#FACC15") || color == Color.parseColor("#3B82F6"));
        binding.ringCustom.setVisibility(!isPreset ? View.VISIBLE : View.GONE);

        // Update brush preview circle color
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.OVAL);
        gd.setColor(color);
        binding.viewBrushPreviewCircle.setBackground(gd);

        PageEditorAdapter.PageViewHolder vh = PageEditorAdapter.getViewHolder(binding.viewPagerPages, pageIndex);
        if (vh != null) vh.drawingOverlay.setStrokeColor(color);
    }

    // ── Modern Text Annotation Styling Toolbar ──────────────────────────

    private final List<TextView> fontChipViews = new ArrayList<>();

    private void setupTextToolbar() {
        float density = getResources().getDisplayMetrics().density;

        // 1. Keyboard -> Edit text content
        binding.btnTextEditText.setOnClickListener(v -> {
            PageEditorAdapter.PageViewHolder vh = PageEditorAdapter.getViewHolder(binding.viewPagerPages, pageIndex);
            AnnotationDrawingView.TextItem sel = vh != null ? vh.drawingOverlay.getSelectedTextItem() : null;
            showQuickTextEditDialog(sel);
        });

        // 2. Font "Aa" -> Toggle font chips
        binding.btnTextFontPicker.setOnClickListener(v -> {
            boolean isVisible = binding.scrollFontOptions.getVisibility() == View.VISIBLE;
            binding.scrollFontOptions.setVisibility(isVisible ? View.GONE : View.VISIBLE);
            binding.layoutTextSizeSlider.setVisibility(View.GONE);
            binding.layoutTextColorPalette.setVisibility(View.GONE);
        });

        setupFontChips();

        // 3. Text Color -> Toggle color palette
        binding.btnTextColorPicker.setOnClickListener(v -> {
            boolean isVisible = binding.layoutTextColorPalette.getVisibility() == View.VISIBLE;
            binding.layoutTextColorPalette.setVisibility(isVisible ? View.GONE : View.VISIBLE);
            binding.scrollFontOptions.setVisibility(View.GONE);
            binding.layoutTextSizeSlider.setVisibility(View.GONE);
        });

        setupTextColorPalette();

        // 4. Text Size -> Toggle size slider
        binding.btnTextSizeToggle.setOnClickListener(v -> {
            boolean isVisible = binding.layoutTextSizeSlider.getVisibility() == View.VISIBLE;
            binding.layoutTextSizeSlider.setVisibility(isVisible ? View.GONE : View.VISIBLE);
            binding.scrollFontOptions.setVisibility(View.GONE);
            binding.layoutTextColorPalette.setVisibility(View.GONE);
        });

        binding.sliderTextSize.addOnChangeListener((slider, value, fromUser) -> {
            if (fromUser) {
                int sizeSp = Math.round(value);
                binding.btnTextSizeToggle.setText(String.format(Locale.US, "%dsp", sizeSp));
                binding.tvTextSizeLabel.setText(String.format(Locale.US, "%dsp", sizeSp));
                PageEditorAdapter.PageViewHolder vh = PageEditorAdapter.getViewHolder(binding.viewPagerPages, pageIndex);
                if (vh != null) {
                    vh.drawingOverlay.updateSelectedTextSize(value);
                }
            }
        });

        // 5. Background Toggle
        binding.btnTextBgToggle.setOnClickListener(v -> {
            PageEditorAdapter.PageViewHolder vh = PageEditorAdapter.getViewHolder(binding.viewPagerPages, pageIndex);
            if (vh != null) {
                vh.drawingOverlay.toggleSelectedTextBackground();
                AnnotationDrawingView.TextItem sel = vh.drawingOverlay.getSelectedTextItem();
                if (sel != null) {
                    binding.btnTextBgToggle.setAlpha(sel.hasBackground ? 1.0f : 0.4f);
                }
            }
        });

        // 6. Delete
        binding.btnTextDelete.setOnClickListener(v -> {
            PageEditorAdapter.PageViewHolder vh = PageEditorAdapter.getViewHolder(binding.viewPagerPages, pageIndex);
            if (vh != null) {
                vh.drawingOverlay.deleteSelectedItem();
            }
            hideTextToolbar();
        });

        // 7. Done
        binding.btnTextDone.setOnClickListener(v -> {
            PageEditorAdapter.PageViewHolder vh = PageEditorAdapter.getViewHolder(binding.viewPagerPages, pageIndex);
            if (vh != null) {
                vh.drawingOverlay.clearSelection();
            }
            hideTextToolbar();
        });
    }

    private void setupFontChips() {
        float density = getResources().getDisplayMetrics().density;
        binding.layoutFontChips.removeAllViews();
        fontChipViews.clear();

        final String[] fontNames = {"Sans-Serif", "Serif", "Monospace", "Script", "Bold Condensed"};
        final String[] fontKeys = {"sans", "serif", "monospace", "cursive", "bold_condensed"};

        for (int i = 0; i < fontNames.length; i++) {
            final String fKey = fontKeys[i];
            TextView chip = new TextView(this);
            chip.setText(fontNames[i]);
            chip.setTextSize(12);
            chip.setTypeface(FontUtils.getTypeface(this, fKey));
            chip.setPadding((int) (12 * density), (int) (6 * density), (int) (12 * density), (int) (6 * density));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, (int) (32 * density));
            lp.setMargins((int) (3 * density), 0, (int) (3 * density), 0);
            chip.setLayoutParams(lp);
            chip.setGravity(Gravity.CENTER);
            chip.setBackgroundResource(R.drawable.bg_title_pill);
            chip.setTextColor(ContextCompat.getColor(this, R.color.text_primary));

            chip.setOnClickListener(v -> {
                PageEditorAdapter.PageViewHolder vh = PageEditorAdapter.getViewHolder(binding.viewPagerPages, pageIndex);
                if (vh != null) {
                    vh.drawingOverlay.updateSelectedTextFont(fKey);
                }
                binding.btnTextFontPicker.setText(getFontDisplayName(fKey));
                updateFontChipSelection(fKey);
            });

            fontChipViews.add(chip);
            binding.layoutFontChips.addView(chip);
        }
    }

    private void updateFontChipSelection(@Nullable String activeFont) {
        final String[] fontKeys = {"sans", "serif", "monospace", "cursive", "bold_condensed"};
        String active = activeFont != null ? activeFont : "sans";
        for (int i = 0; i < fontChipViews.size(); i++) {
            boolean sel = fontKeys[i].equalsIgnoreCase(active);
            TextView chip = fontChipViews.get(i);
            chip.setTextColor(sel ? ContextCompat.getColor(this, R.color.accent_mint) : ContextCompat.getColor(this, R.color.text_primary));
            chip.setBackgroundResource(sel ? R.drawable.bg_title_pill : 0);
        }
    }

    private void setupTextColorPalette() {
        float density = getResources().getDisplayMetrics().density;
        binding.layoutTextColorPalette.removeAllViews();

        int[] colors = new int[]{
                Color.parseColor("#68D391"), // Mint
                Color.parseColor("#EF4444"), // Red
                Color.parseColor("#111827"), // Black
                Color.WHITE,                 // White
                Color.parseColor("#FACC15"), // Yellow
                Color.parseColor("#3B82F6")  // Blue
        };

        for (int c : colors) {
            FrameLayout slot = new FrameLayout(this);
            int slotDim = (int) (30 * density);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(slotDim, slotDim);
            lp.setMargins((int) (4 * density), 0, (int) (4 * density), 0);
            slot.setLayoutParams(lp);

            View circle = new View(this);
            int circleDim = (int) (22 * density);
            FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(circleDim, circleDim, Gravity.CENTER);
            circle.setLayoutParams(clp);
            GradientDrawable gd = new GradientDrawable();
            gd.setShape(GradientDrawable.OVAL);
            gd.setColor(c);
            gd.setStroke((int) (1.5f * density), c == Color.WHITE ? Color.parseColor("#D1D5DB") : Color.TRANSPARENT);
            circle.setBackground(gd);

            slot.addView(circle);
            slot.setOnClickListener(v -> {
                PageEditorAdapter.PageViewHolder vh = PageEditorAdapter.getViewHolder(binding.viewPagerPages, pageIndex);
                if (vh != null) {
                    vh.drawingOverlay.updateSelectedTextColor(c);
                }
                updateTextColorPreview(c);
            });
            binding.layoutTextColorPalette.addView(slot);
        }

        // Custom Color Button
        FrameLayout customSlot = new FrameLayout(this);
        int customDim = (int) (30 * density);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(customDim, customDim);
        clp.setMargins((int) (4 * density), 0, (int) (4 * density), 0);
        customSlot.setLayoutParams(clp);

        ImageView customIv = new ImageView(this);
        customIv.setImageResource(R.drawable.ic_color_lens);
        customIv.setColorFilter(ContextCompat.getColor(this, R.color.text_primary));
        FrameLayout.LayoutParams ivLp = new FrameLayout.LayoutParams((int) (22 * density), (int) (22 * density), Gravity.CENTER);
        customIv.setLayoutParams(ivLp);
        customSlot.addView(customIv);

        customSlot.setOnClickListener(v -> {
            PageEditorAdapter.PageViewHolder vh = PageEditorAdapter.getViewHolder(binding.viewPagerPages, pageIndex);
            AnnotationDrawingView.TextItem sel = vh != null ? vh.drawingOverlay.getSelectedTextItem() : null;
            int initial = sel != null ? sel.color : currentDrawingColor;
            CustomColorPickerDialog.show(this, initial, true, chosen -> {
                if (vh != null) {
                    vh.drawingOverlay.updateSelectedTextColor(chosen);
                }
                updateTextColorPreview(chosen);
            });
        });

        binding.layoutTextColorPalette.addView(customSlot);
    }

    private void updateTextColorPreview(int color) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.OVAL);
        gd.setColor(color);
        gd.setStroke((int) (1.5f * getResources().getDisplayMetrics().density), color == Color.WHITE ? Color.parseColor("#D1D5DB") : Color.TRANSPARENT);
        binding.textColorPreviewCircle.setBackground(gd);
    }

    private void showTextToolbar(@NonNull AnnotationDrawingView.TextItem item) {
        if (!isDrawingMode) {
            enterDrawingMode();
        }
        binding.layoutAnnotationBar.setVisibility(View.GONE);
        binding.layoutTextToolbar.setVisibility(View.VISIBLE);

        float density = getResources().getDisplayMetrics().density;
        int sizeSp = Math.round(item.textSize / density);
        if (sizeSp <= 0) sizeSp = 18;
        binding.btnTextSizeToggle.setText(String.format(Locale.US, "%dsp", sizeSp));
        binding.tvTextSizeLabel.setText(String.format(Locale.US, "%dsp", sizeSp));
        binding.sliderTextSize.setValue(Math.max(10.0f, Math.min(64.0f, sizeSp)));

        // Update color preview
        updateTextColorPreview(item.color);

        // Update font button label
        String fontLabel = getFontDisplayName(item.fontFamily);
        binding.btnTextFontPicker.setText(fontLabel);

        // Update background button alpha
        binding.btnTextBgToggle.setAlpha(item.hasBackground ? 1.0f : 0.4f);

        updateFontChipSelection(item.fontFamily);
    }

    private void hideTextToolbar() {
        binding.layoutTextToolbar.setVisibility(View.GONE);
        binding.scrollFontOptions.setVisibility(View.GONE);
        binding.layoutTextSizeSlider.setVisibility(View.GONE);
        binding.layoutTextColorPalette.setVisibility(View.GONE);
        if (isDrawingMode) {
            binding.layoutAnnotationBar.setVisibility(View.VISIBLE);
        }
    }

    private String getFontDisplayName(@Nullable String fontKey) {
        if (fontKey == null) return "Sans";
        switch (fontKey.toLowerCase(Locale.US)) {
            case "serif":
            case "times":
            case "playfair":
                return "Serif";
            case "mono":
            case "monospace":
            case "spacemono":
                return "Mono";
            case "cursive":
            case "script":
            case "handwriting":
            case "caveat":
                return "Script";
            case "bold_condensed":
            case "condensed":
            case "impact":
                return "Bold Cond.";
            default:
                return "Sans";
        }
    }

    // ── Filter Mode ──────────────────────────────────────────────────────

    private void onFilterClicked() {
        if (isBusy) return;

        if (!isFilterMode) {
            enterFilterMode();
        } else {
            exitFilterMode();
        }
    }

    private void enterFilterMode() {
        saveCurrentPageAnnotationsToHistory();
        if (isDrawingMode) {
            exitDrawingMode();
        }
        if (isCropMode) {
            exitCropMode();
        }
        isFilterMode = true;
        if (viewModel != null) viewModel.setFilterMode(true);
        binding.layoutFilterBar.setVisibility(View.VISIBLE);
        binding.ivFilterIcon.setColorFilter(ContextCompat.getColor(this, R.color.accent_mint));
        binding.tvFilterLabel.setTextColor(ContextCompat.getColor(this, R.color.accent_mint));

        DocumentFilter current = (viewModel != null) ? viewModel.getSelectedFilter() : DocumentFilter.ORIGINAL;
        if (filterAdapter != null) {
            filterAdapter.setSelectedFilter(current);
            int selectedPos = filterAdapter.getSelectedPosition();
            if (selectedPos >= 0 && binding.rvFilters != null) {
                binding.rvFilters.scrollToPosition(selectedPos);
            }
        }

        loadFilterPreviewsForPage(pageIndex);
    }

    private void exitFilterMode() {
        isFilterMode = false;
        if (viewModel != null) viewModel.setFilterMode(false);
        if (binding != null && binding.layoutFilterBar != null) {
            binding.layoutFilterBar.setVisibility(View.GONE);
            binding.ivFilterIcon.clearColorFilter();
            binding.tvFilterLabel.setTextColor(ContextCompat.getColorStateList(this, R.color.color_nav_item));
        }
    }

    private void setupFilterBar() {
        binding.btnCloseFilter.setOnClickListener(v -> exitFilterMode());
        setupFilterRecyclerView();
    }

    private void setupFilterRecyclerView() {
        if (binding == null || binding.rvFilters == null) return;
        binding.rvFilters.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        binding.rvFilters.setHasFixedSize(true);

        List<FilterPreviewAdapter.FilterItem> items = new ArrayList<>();
        for (DocumentFilter filter : DocumentFilter.values()) {
            items.add(new FilterPreviewAdapter.FilterItem(filter, getFilterName(filter)));
        }

        filterAdapter = new FilterPreviewAdapter(items, (filter, pos) -> {
            applyFilter(filter);
        });

        DocumentFilter current = (viewModel != null) ? viewModel.getSelectedFilter() : DocumentFilter.ORIGINAL;
        filterAdapter.setSelectedFilter(current);

        binding.rvFilters.setAdapter(filterAdapter);
    }

    private void loadFilterPreviewsForPage(int targetIndex) {
        if (targetIndex < 0 || targetIndex >= pagePaths.size()) return;

        Map<DocumentFilter, Bitmap> cached = filterPreviewCache.get(targetIndex);
        if (cached != null && !cached.isEmpty()) {
            if (filterAdapter != null) {
                filterAdapter.setPreviews(cached);
            }
            return;
        }

        if (filterAdapter != null) {
            filterAdapter.clearPreviews();
        }

        final int requestId = targetIndex;
        executor.execute(() -> {
            Bitmap base = getBaseBitmapForPage(targetIndex);
            if (base == null) {
                base = CacheManager.loadBitmap(pagePaths.get(targetIndex));
            }
            if (base == null) return;

            // Downscale to 128x128 square thumbnail directly matching the 64dp card view size
            Bitmap baseThumb = createSquareThumbnail(base, 128);
            if (base != null && !base.isRecycled() && base != baseThumb) {
                base.recycle();
            }
            if (baseThumb == null) return;

            Map<DocumentFilter, Bitmap> previews = new HashMap<>();
            for (DocumentFilter filter : DocumentFilter.values()) {
                Bitmap preview = runFilterAlgorithm(filter, baseThumb);
                if (preview != null) {
                    previews.put(filter, preview);
                }
            }
            baseThumb.recycle();

            runOnUiThread(() -> {
                filterPreviewCache.put(requestId, previews);
                if (isFilterMode && requestId == pageIndex && filterAdapter != null) {
                    filterAdapter.setPreviews(previews);
                }
            });
        });
    }

    private static Bitmap createSquareThumbnail(Bitmap src, int targetSize) {
        if (src == null || src.isRecycled()) return null;
        int width = src.getWidth();
        int height = src.getHeight();
        if (width <= 0 || height <= 0) return null;

        int cropSize = Math.min(width, height);
        int cropX = (width - cropSize) / 2;
        int cropY = (height - cropSize) / 2;

        Bitmap cropped = Bitmap.createBitmap(src, cropX, cropY, cropSize, cropSize);
        if (cropSize == targetSize) {
            return cropped;
        }
        Bitmap scaled = Bitmap.createScaledBitmap(cropped, targetSize, targetSize, true);
        if (cropped != scaled && cropped != src) {
            cropped.recycle();
        }
        return scaled;
    }

    private void invalidateFilterPreview(int pageIdx) {
        Map<DocumentFilter, Bitmap> oldPreviews = filterPreviewCache.remove(pageIdx);
        if (oldPreviews != null) {
            for (Bitmap b : oldPreviews.values()) {
                if (b != null && !b.isRecycled()) {
                    b.recycle();
                }
            }
        }
        if (isFilterMode && pageIdx == pageIndex) {
            loadFilterPreviewsForPage(pageIndex);
        }
    }

    private void clearFilterPreviewCache() {
        for (Map<DocumentFilter, Bitmap> map : filterPreviewCache.values()) {
            if (map != null) {
                for (Bitmap bmp : map.values()) {
                    if (bmp != null && !bmp.isRecycled()) {
                        bmp.recycle();
                    }
                }
            }
        }
        filterPreviewCache.clear();
    }

    private void applyFilter(DocumentFilter filter) {
        if (isBusy) return;
        if (viewModel != null) viewModel.setSelectedFilter(filter);
        if (filterAdapter != null) {
            filterAdapter.setSelectedFilter(filter);
        }
        boolean applyToAll = binding.cbFilterApplyToAll != null && binding.cbFilterApplyToAll.isChecked();

        if (applyToAll) {
            applyFilterToAllPages(filter);
        } else {
            applyFilterToSinglePage(filter, pageIndex);
        }
    }

    private void applyFilterToSinglePage(DocumentFilter filter, int targetIndex) {
        if (targetIndex < 0 || targetIndex >= pagePaths.size()) return;

        EditHistoryManager hm = getHistoryManager(targetIndex);
        ScanEditState curr = hm.getCurrentState();
        ScanEditState next = (curr != null) ? curr.withFilter(filter.name())
                : new ScanEditState(null, 0, filter.name(), System.currentTimeMillis());
        hm.pushState(next);

        renderStateNonDestructively(targetIndex, next, () -> {
            String filterName = getFilterName(filter);
            Toast.makeText(UnifiedEditorActivity.this, getString(R.string.filter_applied_single, filterName), Toast.LENGTH_SHORT).show();
        });
    }

    private void applyFilterToAllPages(DocumentFilter filter) {
        if (pagePaths.isEmpty()) return;
        isBusy = true;
        if (binding.pbFilterApplying != null) {
            binding.pbFilterApplying.setVisibility(View.VISIBLE);
        }

        View progressView = getLayoutInflater().inflate(R.layout.dialog_progress, null);
        TextView tvProgressMessage = progressView.findViewById(R.id.tvProgressMessage);
        if (tvProgressMessage != null) {
            tvProgressMessage.setText(R.string.crop_processing);
        }

        AlertDialog progressDialog = new MaterialAlertDialogBuilder(this)
                .setView(progressView)
                .setCancelable(false)
                .show();

        executor.execute(() -> {
            try {
                for (int i = 0; i < pagePaths.size(); i++) {
                final int idx = i;
                EditHistoryManager hm = getHistoryManager(idx);
                ScanEditState curr = hm.getCurrentState();
                ScanEditState next = (curr != null) ? curr.withFilter(filter.name())
                        : new ScanEditState(null, 0, filter.name(), System.currentTimeMillis());
                hm.pushState(next);

                String origPath = (idx < originalPagePaths.size())
                        ? originalPagePaths.get(idx) : pagePaths.get(idx);
                Bitmap base = CacheManager.loadBitmap(origPath);
                if (base == null) {
                    base = CacheManager.loadBitmap(pagePaths.get(idx));
                }
                if (base != null) {
                    // 1. Perspective Crop Warp
                    float[] normCorners = next.getNormalizedCropPoints();
                    if (normCorners != null && normCorners.length == 8) {
                        int bw = base.getWidth();
                        int bh = base.getHeight();
                        org.opencv.core.Point[] opencvCorners = new org.opencv.core.Point[4];
                        for (int k = 0; k < 4; k++) {
                            opencvCorners[k] = new org.opencv.core.Point(
                                    Math.max(0.0, Math.min((double) bw, (double) normCorners[k * 2] * bw)),
                                    Math.max(0.0, Math.min((double) bh, (double) normCorners[k * 2 + 1] * bh))
                            );
                        }
                        opencvCorners = DocumentDetector.orderCorners(opencvCorners);
                        Bitmap cropped = ImageProcessor.perspectiveWarp(base, opencvCorners);
                        base.recycle();
                        base = cropped;
                    }

                    // 2. Rotation
                    int rotation = ((next.getRotationDegrees() % 360) + 360) % 360;
                    if (rotation != 0) {
                        Matrix matrix = new Matrix();
                        matrix.postRotate(rotation);
                        Bitmap rotated = Bitmap.createBitmap(base, 0, 0, base.getWidth(), base.getHeight(), matrix, true);
                        base.recycle();
                        base = rotated;
                    }

                    // 3. Filter
                    Bitmap filtered = runFilterAlgorithm(filter, base);
                    base.recycle();
                    if (filtered != null) {
                        String newPath = CacheManager.saveTempBitmap(
                                UnifiedEditorActivity.this, filtered, "page_" + idx + "_filter_" + UUID.randomUUID().toString());
                        filtered.recycle();
                        if (newPath != null) {
                            pagePaths.set(idx, newPath);
                        }
                    }
                }
            }

            runOnUiThread(() -> {
                isBusy = false;
                if (binding.pbFilterApplying != null) {
                    binding.pbFilterApplying.setVisibility(View.GONE);
                }
                if (progressDialog.isShowing()) {
                    progressDialog.dismiss();
                }
                clearFilterPreviewCache();
                pageAdapter.notifyDataSetChanged();
                thumbAdapter.notifyDataSetChanged();
                updateUndoButtonState();
                Toast.makeText(UnifiedEditorActivity.this, getString(R.string.filter_applied_all, pagePaths.size()), Toast.LENGTH_SHORT).show();
            });
            } catch (Throwable t) {
                Log.e(TAG, "Error in applyFilterToAllPages", t);
                runOnUiThread(() -> {
                    isBusy = false;
                    if (binding != null && binding.pbFilterApplying != null) {
                        binding.pbFilterApplying.setVisibility(View.GONE);
                    }
                    if (progressDialog.isShowing()) {
                        progressDialog.dismiss();
                    }
                });
            }
        });
    }

    private Bitmap runFilterAlgorithm(DocumentFilter filter, Bitmap src) {
        if (src == null || src.isRecycled()) return null;
        switch (filter) {
            case MAGIC:
                return ImageProcessor.applyMagicColor(src);
            case SCAN_ENHANCE:
                return ImageProcessor.applyScanEnhance(src);
            case BW:
                return ImageProcessor.applyAdaptiveBW(src);
            case GRAYSCALE:
                return ImageProcessor.applyGrayscale(src);
            case SHARPEN:
                return ImageProcessor.applySharpen(src);
            case INVERT:
                return ImageProcessor.applyInvert(src);
            case ORIGINAL:
            default:
                return ImageProcessor.applyOriginalFilter(src);
        }
    }

    private Bitmap getBaseBitmapForPage(int targetIndex) {
        if (targetIndex < 0 || targetIndex >= pagePaths.size()) return null;
        String origPath = (targetIndex < originalPagePaths.size())
                ? originalPagePaths.get(targetIndex) : pagePaths.get(targetIndex);
        Bitmap base = CacheManager.loadBitmap(origPath);
        if (base == null) {
            base = CacheManager.loadBitmap(pagePaths.get(targetIndex));
        }
        if (base == null) return null;

        EditHistoryManager hm = pageHistoryMap.get(targetIndex);
        ScanEditState state = hm != null ? hm.getCurrentState() : null;
        if (state != null) {
            float[] normCorners = state.getNormalizedCropPoints();
            if (normCorners != null && normCorners.length == 8) {
                int bw = base.getWidth();
                int bh = base.getHeight();
                org.opencv.core.Point[] pts = new org.opencv.core.Point[4];
                for (int i = 0; i < 4; i++) {
                    pts[i] = new org.opencv.core.Point(
                            Math.max(0.0, Math.min((double) bw, (double) normCorners[i * 2] * bw)),
                            Math.max(0.0, Math.min((double) bh, (double) normCorners[i * 2 + 1] * bh))
                    );
                }
                pts = DocumentDetector.orderCorners(pts);
                Bitmap cropped = ImageProcessor.perspectiveWarp(base, pts);
                base.recycle();
                base = cropped;
            }
            int rotation = ((state.getRotationDegrees() % 360) + 360) % 360;
            if (rotation != 0) {
                Matrix matrix = new Matrix();
                matrix.postRotate(rotation);
                Bitmap rotated = Bitmap.createBitmap(base, 0, 0, base.getWidth(), base.getHeight(), matrix, true);
                base.recycle();
                base = rotated;
            }
            return base;
        }

        Point[] corners = savedCornersMap.get(targetIndex);
        if (corners != null && corners.length == 4) {
            org.opencv.core.Point[] opencvCorners = CropPreviewActivity.toOpenCvPoints(corners);
            opencvCorners = DocumentDetector.orderCorners(opencvCorners);
            Bitmap cropped = ImageProcessor.perspectiveWarp(base, opencvCorners);
            base.recycle();
            return cropped;
        }
        return base;
    }

    private String getFilterName(DocumentFilter filter) {
        switch (filter) {
            case MAGIC:
                return getString(R.string.crop_filter_magic_color);
            case SCAN_ENHANCE:
                return getString(R.string.crop_filter_scan_enhance);
            case BW:
                return getString(R.string.crop_filter_bw_clean);
            case GRAYSCALE:
                return getString(R.string.crop_filter_grayscale);
            case SHARPEN:
                return getString(R.string.crop_filter_sharpen);
            case INVERT:
                return getString(R.string.crop_filter_invert);
            case ORIGINAL:
            default:
                return getString(R.string.crop_filter_original);
        }
    }

    // ── Text & Signature Dialogs ─────────────────────────────────────────

    private void showTextOrSignatureChooser() {
        if (!isDrawingMode) {
            enterDrawingMode();
        }
        updateSwipeLock();
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.action_text_sign)
                .setItems(new CharSequence[]{getString(R.string.action_text), getString(R.string.action_signature)}, (dialog, which) -> {
                    if (which == 0) {
                        PageEditorAdapter.PageViewHolder vh = PageEditorAdapter.getViewHolder(binding.viewPagerPages, pageIndex);
                        AnnotationDrawingView.TextItem activeItem = vh != null ? vh.drawingOverlay.getSelectedTextItem() : null;
                        showQuickTextEditDialog(activeItem);
                    } else {
                        showSignatureCaptureDialog();
                    }
                })
                .show();
    }

    private void showQuickTextEditDialog(@Nullable AnnotationDrawingView.TextItem existingItem) {
        float density = getResources().getDisplayMetrics().density;

        final FrameLayout container = new FrameLayout(this);
        int padH = (int) (24 * density);
        int padV = (int) (12 * density);
        container.setPadding(padH, padV, padH, padV);

        final TextInputLayout til = new TextInputLayout(this, null,
                com.google.android.material.R.attr.textInputOutlinedStyle);
        til.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        til.setHint(getString(R.string.text_dialog_hint));

        final TextInputEditText input = new TextInputEditText(til.getContext());
        input.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        input.setHintTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        input.setTextSize(16);

        if (existingItem != null) {
            input.setText(existingItem.text);
            if (existingItem.text != null) {
                input.setSelection(existingItem.text.length());
            }
            if (existingItem.fontFamily != null) {
                input.setTypeface(FontUtils.getTypeface(this, existingItem.fontFamily));
            }
        }

        til.addView(input);
        container.addView(til);

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle(existingItem != null ? "Edit Text" : "Add Text")
                .setView(container)
                .setPositiveButton(R.string.action_confirm, (d, which) -> {
                    String text = input.getText() != null ? input.getText().toString().trim() : "";
                    if (!text.isEmpty()) {
                        if (!isDrawingMode) {
                            enterDrawingMode();
                        }
                        PageEditorAdapter.PageViewHolder vh = PageEditorAdapter.getViewHolder(binding.viewPagerPages, pageIndex);
                        if (vh != null) {
                            vh.drawingOverlay.setVisibility(View.VISIBLE);
                            vh.drawingOverlay.bindImageView(vh.ivPageImage);
                            if (existingItem != null) {
                                vh.drawingOverlay.updateSelectedTextString(text);
                            } else {
                                vh.drawingOverlay.addTextAnnotation(
                                        text, currentDrawingColor, 22f, "sans", true);
                                vh.drawingOverlay.setToolMode(AnnotationDrawingView.ToolMode.TEXT);
                            }
                            AnnotationDrawingView.TextItem sel = vh.drawingOverlay.getSelectedTextItem();
                            if (sel != null) {
                                showTextToolbar(sel);
                            }
                            updateUndoButtonState();
                        }
                    }
                })
                .setNegativeButton(R.string.action_cancel, null);

        if (existingItem != null) {
            builder.setNeutralButton(R.string.action_delete, (d, which) -> {
                PageEditorAdapter.PageViewHolder vh = PageEditorAdapter.getViewHolder(binding.viewPagerPages, pageIndex);
                if (vh != null) {
                    vh.drawingOverlay.deleteSelectedItem();
                }
                hideTextToolbar();
            });
        }

        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> {
            input.requestFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
            }
        });
        dialog.show();
    }

    private void showTextCustomizationDialog(@Nullable AnnotationDrawingView.TextItem existingItem) {
        showQuickTextEditDialog(existingItem);
    }

    private void showSignatureCaptureDialog() {
        float density = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * density);
        root.setPadding(pad, pad, pad, pad);

        // Ink Color Selector
        TextView tvInk = new TextView(this);
        tvInk.setText("Signature Ink Color");
        tvInk.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        tvInk.setTextSize(12);
        tvInk.setPadding(0, 0, 0, (int) (8 * density));
        root.addView(tvInk);

        LinearLayout colorRow = new LinearLayout(this);
        colorRow.setOrientation(LinearLayout.HORIZONTAL);
        colorRow.setGravity(Gravity.CENTER_VERTICAL);
        colorRow.setPadding(0, 0, 0, (int) (12 * density));

        final int[] inkColors = {
                Color.BLACK,                   // Black (Default #000000)
                Color.parseColor("#0055FF"),   // Blue
                Color.parseColor("#68D391"),   // Mint
                Color.parseColor("#EF4444")    // Red
        };
        final int[] inkDrawables = {
                R.drawable.bg_circle_black,
                R.drawable.bg_circle_blue,
                R.drawable.bg_circle_mint,
                R.drawable.bg_circle_red
        };
        final int[] selectedInk = new int[]{Color.BLACK};
        final View[] inkRings = new View[inkColors.length];

        for (int i = 0; i < inkColors.length; i++) {
            final int c = inkColors[i];
            final int idx = i;
            FrameLayout slot = new FrameLayout(this);
            int slotDim = (int) (32 * density);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(slotDim, slotDim);
            lp.setMargins((int) (4 * density), 0, (int) (4 * density), 0);
            slot.setLayoutParams(lp);

            View ring = new View(this);
            int ringDim = (int) (30 * density);
            FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(ringDim, ringDim, Gravity.CENTER);
            ring.setLayoutParams(rlp);
            ring.setBackgroundResource(R.drawable.bg_circle_ring);
            ring.setVisibility(i == 0 ? View.VISIBLE : View.GONE);
            inkRings[i] = ring;
            slot.addView(ring);

            View circle = new View(this);
            int circleDim = (int) (22 * density);
            FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(circleDim, circleDim, Gravity.CENTER);
            circle.setLayoutParams(clp);
            circle.setBackgroundResource(inkDrawables[i]);
            slot.addView(circle);

            colorRow.addView(slot);
        }
        root.addView(colorRow);

        // Drawing Canvas Card
        MaterialCardView card = new MaterialCardView(this);
        int h = (int) (200 * density);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h);
        card.setLayoutParams(clp);
        card.setCardBackgroundColor(Color.WHITE);
        card.setRadius(12f * density);
        card.setStrokeColor(Color.parseColor("#E2E8F0"));
        card.setStrokeWidth((int) (1 * density));

        DrawingOverlayView sigCanvas = new DrawingOverlayView(this);
        sigCanvas.setStrokeColor(Color.BLACK);
        sigCanvas.setStrokeWidth(5f);
        sigCanvas.setDrawingEnabled(true);
        card.addView(sigCanvas);
        root.addView(card);

        // Hook up color changes to sigCanvas live
        for (int i = 0; i < colorRow.getChildCount(); i++) {
            final int idx = i;
            colorRow.getChildAt(i).setOnClickListener(v -> {
                selectedInk[0] = inkColors[idx];
                for (int j = 0; j < inkRings.length; j++) {
                    inkRings[j].setVisibility(j == idx ? View.VISIBLE : View.GONE);
                }
                sigCanvas.setStrokeColor(selectedInk[0]);
            });
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.signature_dialog_title)
                .setView(root)
                .setPositiveButton(R.string.signature_use, (dialog, which) -> {
                    if (!sigCanvas.isEmpty()) {
                        int w = sigCanvas.getWidth() > 0 ? sigCanvas.getWidth() : 600;
                        int sigH = sigCanvas.getHeight() > 0 ? sigCanvas.getHeight() : 300;
                        Bitmap sigBmp = Bitmap.createBitmap(w, sigH, Bitmap.Config.ARGB_8888);
                        Canvas c = new Canvas(sigBmp);
                        sigCanvas.drawToCanvas(c, null, w, sigH);

                        enterDrawingMode();
                        PageEditorAdapter.PageViewHolder vh = PageEditorAdapter.getViewHolder(binding.viewPagerPages, pageIndex);
                        if (vh != null) {
                            vh.drawingOverlay.setVisibility(View.VISIBLE);
                            vh.drawingOverlay.bindImageView(vh.ivPageImage);
                            vh.drawingOverlay.addSignatureAnnotation(sigBmp);
                            AnnotationDrawingView.SignatureItem sigItem = vh.drawingOverlay.getSelectedSignatureItem();
                            if (sigItem != null) {
                                sigItem.tintColor = selectedInk[0];
                            }
                            vh.drawingOverlay.setToolMode(AnnotationDrawingView.ToolMode.SIGNATURE);
                            updateUndoButtonState();
                        }
                    }
                })
                .setNeutralButton(R.string.signature_clear, (dialog, which) -> sigCanvas.clear())
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    // â”€â”€ Rotate & Delete â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private void rotateCurrentPage() {
        if (isBusy) return;
        if (isFilterMode) exitFilterMode();
        if (isDrawingMode) exitDrawingMode();
        if (isCropMode) exitCropMode();

        final int targetIndex = pageIndex;
        EditHistoryManager hm = getHistoryManager(targetIndex);
        ScanEditState curr = hm.getCurrentState();

        PageEditorAdapter.PageViewHolder vh = PageEditorAdapter.getViewHolder(binding.viewPagerPages, pageIndex);
        AnnotationDrawingView.AnnotationState currentAnnos = null;
        if (vh != null && !vh.drawingOverlay.isEmpty()) {
            currentAnnos = vh.drawingOverlay.getAnnotationState();
        } else if (curr != null) {
            currentAnnos = curr.getAnnotations();
        }

        int curW = 1000;
        int curH = 1000;
        if (vh != null && vh.ivPageImage.getDrawable() != null) {
            curW = vh.ivPageImage.getDrawable().getIntrinsicWidth();
            curH = vh.ivPageImage.getDrawable().getIntrinsicHeight();
        } else if (targetIndex < pagePaths.size()) {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(pagePaths.get(targetIndex), opts);
            if (opts.outWidth > 0 && opts.outHeight > 0) {
                curW = opts.outWidth;
                curH = opts.outHeight;
            }
        }

        Matrix rotMatrix = new Matrix();
        rotMatrix.postRotate(90, curW / 2f, curH / 2f);
        RectF r = new RectF(0, 0, curW, curH);
        rotMatrix.mapRect(r);
        rotMatrix.postTranslate(-r.left, -r.top);

        AnnotationDrawingView.AnnotationState rotatedAnnos =
                AnnotationDrawingView.transformState(currentAnnos, rotMatrix, this);

        int newRot = (curr != null ? curr.getRotationDegrees() + 90 : 90) % 360;
        ScanEditState next = (curr != null) ? curr.withRotation(newRot, rotatedAnnos)
                : new ScanEditState(null, newRot, ScanEditState.FILTER_ORIGINAL, System.currentTimeMillis(), rotatedAnnos);
        hm.pushState(next);

        renderStateNonDestructively(targetIndex, next, null);
    }

    private void showDeleteConfirmation() {
        if (isFilterMode) exitFilterMode();
        if (pagePaths.size() <= 1) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.review_delete_page_confirm)
                    .setMessage("Deleting this page will discard the document session. Proceed?")
                    .setPositiveButton(R.string.action_delete, (dialog, which) -> {
                        DraftManager.clearDraft(this);
                        finish();
                    })
                    .setNegativeButton(R.string.action_cancel, null)
                    .show();
            return;
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.delete_page_confirm_title, pageIndex + 1))
                .setMessage(R.string.delete_page_confirm_message)
                .setPositiveButton(R.string.action_delete, (dialog, which) -> {
                    pagePaths.remove(pageIndex);
                    if (pageIndex < originalPagePaths.size()) {
                        originalPagePaths.remove(pageIndex);
                    }
                    pageHistoryMap.remove(pageIndex);
                    Map<Integer, EditHistoryManager> newHistoryMap = new HashMap<>();
                    int idx = 0;
                    for (int i = 0; i < pagePaths.size() + 1; i++) {
                        if (i != pageIndex && pageHistoryMap.containsKey(i)) {
                            newHistoryMap.put(idx, pageHistoryMap.get(i));
                            idx++;
                        }
                    }
                    pageHistoryMap.clear();
                    pageHistoryMap.putAll(newHistoryMap);

                    clearFilterPreviewCache();
                    pageAdapter.notifyDataSetChanged();
                    thumbAdapter.notifyDataSetChanged();
                    int newIndex = Math.min(pageIndex, pagePaths.size() - 1);
                    scrollToPage(newIndex, false);
                    onPageSwitched(newIndex);
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    // â”€â”€ OCR Extraction â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private void extractTextFromCurrentScan() {
        if (isFilterMode) {
            exitFilterMode();
        }
        if (binding.lensOverlay.getVisibility() == View.VISIBLE) {
            dismissLensOverlay();
            return;
        }

        if (pagePaths.size() > 1) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.ocr_dialog_title)
                    .setItems(new CharSequence[]{
                            getString(R.string.ocr_batch_all_pages),
                            getString(R.string.ocr_current_page_lens)
                    }, (dialog, which) -> {
                        if (which == 0) {
                            startBatchOcr();
                        } else {
                            extractTextFromSinglePage();
                        }
                    })
                    .setNegativeButton(R.string.action_cancel, null)
                    .show();
        } else {
            extractTextFromSinglePage();
        }
    }

    private void startBatchOcr() {
        if (pagePaths.isEmpty()) return;
        OcrHelper.extractTextFromImagesBatch(this, pagePaths, new OcrHelper.BatchOcrCallback() {
            @Override
            public void onProgress(int currentPage, int totalPages) {
                // Managed by OcrHelper's progress dialog
            }

            @Override
            public void onSuccess(String aggregatedText) {
                if (isFinishing() || isDestroyed()) return;
                OcrHelper.showExtractedTextDialog(UnifiedEditorActivity.this, aggregatedText, false, pagePaths.size());
            }

            @Override
            public void onCancelled(String partialText) {
                if (isFinishing() || isDestroyed()) return;
                if (partialText != null && !partialText.trim().isEmpty()) {
                    OcrHelper.showExtractedTextDialog(UnifiedEditorActivity.this, partialText, true, pagePaths.size());
                } else {
                    Toast.makeText(UnifiedEditorActivity.this, R.string.ocr_cancelled_toast, Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onError(Exception e) {
                if (isFinishing() || isDestroyed()) return;
                Log.e(TAG, "Batch OCR failed", e);
                Toast.makeText(UnifiedEditorActivity.this, R.string.ocr_error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void extractTextFromSinglePage() {
        if (pageIndex < 0 || pageIndex >= pagePaths.size()) return;
        Bitmap activeBitmap = CacheManager.loadBitmap(pagePaths.get(pageIndex));
        if (activeBitmap == null || activeBitmap.isRecycled()) {
            Toast.makeText(this, R.string.ocr_error, Toast.LENGTH_SHORT).show();
            return;
        }

        View progressView = getLayoutInflater().inflate(R.layout.dialog_progress, null);
        AlertDialog progressDialog = new MaterialAlertDialogBuilder(this)
                .setView(progressView)
                .setCancelable(false)
                .show();

        final int bmpW = activeBitmap.getWidth();
        final int bmpH = activeBitmap.getHeight();

        OcrHelper.extractText(activeBitmap, this, new OcrHelper.OcrCallback() {
            @Override
            public void onSuccess(Text text) {
                activeBitmap.recycle();
                if (isFinishing() || isDestroyed()) return;
                progressDialog.dismiss();

                if (text == null || text.getTextBlocks().isEmpty()) {
                    Toast.makeText(UnifiedEditorActivity.this, R.string.ocr_empty, Toast.LENGTH_SHORT).show();
                    return;
                }

                PageEditorAdapter.PageViewHolder vh = PageEditorAdapter.getViewHolder(binding.viewPagerPages, pageIndex);
                RectF displayRect = vh != null ? vh.ivPageImage.getDisplayRect() : null;
                binding.lensOverlay.setTargetRect(displayRect);
                binding.lensOverlay.setVisionText(text, bmpW, bmpH);
                binding.lensOverlay.setVisibility(View.VISIBLE);
                Toast.makeText(UnifiedEditorActivity.this, R.string.ocr_lens_hint, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(Exception e) {
                activeBitmap.recycle();
                if (isFinishing() || isDestroyed()) return;
                progressDialog.dismiss();
                Toast.makeText(UnifiedEditorActivity.this, R.string.ocr_error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showJumpToPageDialog() {
        int totalPages = pagePaths.size();
        if (totalPages <= 1) return;

        final int currentPage = pageIndex + 1;

        FrameLayout container = new FrameLayout(this);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        container.setPadding(padding, padding / 2, padding, 0);

        TextInputLayout til = new TextInputLayout(this);
        til.setHint(getString(R.string.jump_to_page_hint, totalPages));
        til.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        til.setBoxStrokeColor(ContextCompat.getColor(this, R.color.accent_mint));
        til.setDefaultHintTextColor(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.text_secondary)));

        TextInputEditText et = new TextInputEditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        et.setText(String.valueOf(currentPage));
        et.selectAll();
        til.addView(et);
        container.addView(til);

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.jump_to_page_title)
                .setView(container)
                .setPositiveButton(R.string.jump_to_page_go, null)
                .setNegativeButton(R.string.action_cancel, (d, which) -> d.dismiss())
                .create();

        dialog.setOnShowListener(d -> {
            et.requestFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT);
            }

            Button positiveBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            positiveBtn.setOnClickListener(v -> {
                String text = et.getText() != null ? et.getText().toString().trim() : "";
                if (text.isEmpty()) {
                    til.setError(getString(R.string.jump_to_page_error, totalPages));
                    return;
                }
                try {
                    int targetPage = Integer.parseInt(text);
                    if (targetPage < 1 || targetPage > totalPages) {
                        til.setError(getString(R.string.jump_to_page_error, totalPages));
                        return;
                    }
                    til.setError(null);
                    if (imm != null) {
                        imm.hideSoftInputFromWindow(et.getWindowToken(), 0);
                    }
                    dialog.dismiss();

                    int targetIndex = targetPage - 1;
                    if (isDrawingMode) {
                        commitCurrentAnnotations();
                        exitDrawingMode();
                    } else if (isCropMode) {
                        exitCropMode();
                    } else if (isFilterMode) {
                        exitFilterMode();
                    }
                    scrollToPage(targetIndex, true);
                } catch (NumberFormatException e) {
                    til.setError(getString(R.string.jump_to_page_error, totalPages));
                }
            });
        });

        dialog.show();
    }

    private void dismissLensOverlay() {
        if (binding.lensOverlay.getVisibility() == View.VISIBLE) {
            binding.lensOverlay.setVisibility(View.GONE);
            binding.lensOverlay.clear();
        }
    }

    // â”€â”€ Finish, Export & Save â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private void finishAndExport() {
        DraftManager.clearDraft(this);
        commitAllActiveAnnotations();

        ArrayList<String> exportPaths = new ArrayList<>();
        for (int i = 0; i < pagePaths.size(); i++) {
            String currentPath = pagePaths.get(i);
            EditHistoryManager hm = pageHistoryMap.get(i);
            ScanEditState state = (hm != null) ? hm.getCurrentState() : null;
            AnnotationDrawingView.AnnotationState annos = (state != null) ? state.getAnnotations() : null;

            if (annos != null && !annos.isEmpty()) {
                Bitmap base = CacheManager.loadBitmap(currentPath);
                if (base != null) {
                    Bitmap flattened = AnnotationDrawingView.flattenOnto(base, annos, this);
                    if (flattened != base && !base.isRecycled()) {
                        base.recycle();
                    }
                    String exportedPath = CacheManager.saveTempBitmap(
                            this, flattened, "page_" + i + "_export_" + UUID.randomUUID().toString());
                    if (flattened != null && !flattened.isRecycled()) {
                        flattened.recycle();
                    }
                    exportPaths.add(exportedPath != null ? exportedPath : currentPath);
                    continue;
                }
            }
            exportPaths.add(currentPath);
        }

        if (isAddingPage || isFromReview) {
            Intent resultIntent = new Intent();
            resultIntent.putStringArrayListExtra(EXTRA_PAGE_PATHS, exportPaths);
            resultIntent.putStringArrayListExtra(EXTRA_ORIGINAL_PAGE_PATHS, originalPagePaths);
            if (!exportPaths.isEmpty()) {
                String current = exportPaths.get(Math.min(pageIndex, exportPaths.size() - 1));
                resultIntent.putExtra("extra_cropped_path", current);
                resultIntent.putExtra(EXTRA_IMAGE_PATH, current);
                resultIntent.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(new File(current)));
            }
            setResult(RESULT_OK, resultIntent);
            finish();
        } else {
            // Open SaveScanBottomSheet which executes PdfGenerator and saves to Room/MediaStore
            SaveScanBottomSheet bottomSheet = SaveScanBottomSheet.newInstance(exportPaths);
            bottomSheet.show(getSupportFragmentManager(), "SaveScanBottomSheet");
        }
    }

    private void handleExit() {
        if (!isFromReview && !isAddingPage && hasEdits()) {
            DraftManager.saveDraftSync(this, originalPagePaths, pagePaths, pageHistoryMap, pageIndex, editorMode);
        }
        if (isFromReview || isAddingPage) {
            Intent resultIntent = new Intent();
            resultIntent.putStringArrayListExtra(EXTRA_PAGE_PATHS, pagePaths);
            resultIntent.putStringArrayListExtra(EXTRA_ORIGINAL_PAGE_PATHS, originalPagePaths);
            if (!pagePaths.isEmpty()) {
                String current = pagePaths.get(Math.min(pageIndex, pagePaths.size() - 1));
                resultIntent.putExtra("extra_cropped_path", current);
                resultIntent.putExtra(EXTRA_IMAGE_PATH, current);
                resultIntent.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(new File(current)));
            }
            setResult(RESULT_OK, resultIntent);
        }
        finish();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (!isFinishing() && !isAddingPage && !isFromReview && hasEdits()) {
            DraftManager.saveDraftAsync(this, originalPagePaths, pagePaths, pageHistoryMap, pageIndex, editorMode);
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putStringArrayList(EXTRA_PAGE_PATHS, pagePaths);
        outState.putStringArrayList(EXTRA_ORIGINAL_PAGE_PATHS, originalPagePaths);
        outState.putInt(EXTRA_PAGE_INDEX, pageIndex);
        outState.putString(EXTRA_MODE, editorMode);
        outState.putBoolean(EXTRA_IS_ADDING_PAGE, isAddingPage);
        outState.putBoolean(EXTRA_FROM_REVIEW, isFromReview);
        outState.putBoolean("isCropMode", isCropMode);
        outState.putBoolean("isDrawingMode", isDrawingMode);
        outState.putBoolean("isFilterMode", isFilterMode);
        outState.putString("sessionId", sessionId);
        if (viewModel != null) {
            float[] active = viewModel.getActivePoints();
            if (active != null) {
                outState.putFloatArray("activePoints", active);
            }
            outState.putString("selectedFilter", viewModel.getSelectedFilter().name());
        }
        if (binding != null && binding.lensOverlay.getVisibility() == View.VISIBLE) {
            outState.putBoolean("KEY_LENS_ACTIVE", true);
            OcrPage ocrPage = binding.lensOverlay.getOcrPage();
            if (ocrPage != null) {
                outState.putParcelable("KEY_LENS_OCR_PAGE", ocrPage);
                outState.putInt("KEY_LENS_SEL_START", binding.lensOverlay.getSelectionStart());
                outState.putInt("KEY_LENS_SEL_END", binding.lensOverlay.getSelectionEnd());
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        clearFilterPreviewCache();
        if (executor != null) {
            executor.shutdown();
        }
    }
}