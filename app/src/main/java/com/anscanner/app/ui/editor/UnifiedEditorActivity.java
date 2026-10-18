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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
        if (!canUndo && isDrawingMode) {
            PageEditorAdapter.PageViewHolder vh = PageEditorAdapter.getViewHolder(binding.viewPagerPages, pageIndex);
            if (vh != null && vh.drawingOverlay.getActionCount() > 0) {
                canUndo = true;
            }
        }
        binding.btnUndo.setEnabled(canUndo);
        binding.btnUndo.setAlpha(canUndo ? 1.0f : 0.3f);

        if (binding.btnRedo != null) {
            boolean canRedo = (hm != null && hm.canRedo());
            binding.btnRedo.setEnabled(canRedo);
            binding.btnRedo.setAlpha(canRedo ? 1.0f : 0.3f);
        }
    }

    private void renderStateNonDestructively(int targetPageIndex, @Nullable ScanEditState state, @Nullable Runnable onComplete) {
        if (state == null || targetPageIndex < 0 || targetPageIndex >= pagePaths.size()) {
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
                            vh.drawingOverlay.bindImageView(vh.ivPageImage);
                        }
                    }
                }

                updateUndoButtonState();
                if (onComplete != null) onComplete.run();
            });
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
            if (isCropMode && position == pageIndex) {
                holder.ivPageImage.setScale(1.0f, false);
                holder.ivPageImage.setZoomable(false);
                holder.cropOverlay.setImageView(holder.ivPageImage);
                prepareCropForPage(position, holder);
            }
        });
        pageAdapter.setOnBitmapLoadedListener((holder, position, bitmap) -> {
            if (isCropMode && position == pageIndex) {
                holder.ivPageImage.setScale(1.0f, false);
                holder.ivPageImage.setZoomable(false);
                holder.cropOverlay.setImageView(holder.ivPageImage);
                prepareCropForPage(position, holder);
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
                if (isDrawingMode) {
                    commitCurrentAnnotations();
                    exitDrawingMode();
                } else if (isCropMode) {
                    exitCropMode();
                } else if (isFilterMode) {
                    exitFilterMode();
                }
                scrollToPage(position, true);
            }
        });
        binding.rvThumbnails.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        binding.rvThumbnails.setAdapter(thumbAdapter);
    }

    private void onPageSwitched(int newPosition) {
        if (newPosition < 0 || newPosition >= pagePaths.size()) return;

        // Auto-commit any active drawing before switching and dismiss annotation/filter menu
        if (isDrawingMode) {
            commitCurrentAnnotations();
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
            if (isDrawingMode) {
                PageEditorAdapter.PageViewHolder vh = PageEditorAdapter.getViewHolder(binding.viewPagerPages, pageIndex);
                if (vh != null && vh.drawingOverlay.getActionCount() > 0) {
                    vh.drawingOverlay.undo();
                    updateUndoButtonState();
                    return;
                }
            }
            EditHistoryManager hm = getHistoryManager(pageIndex);
            if (hm != null && hm.canUndo()) {
                ScanEditState prevState = hm.undo();
                renderStateNonDestructively(pageIndex, prevState, this::updateUndoButtonState);
            }
        });

        if (binding.btnRedo != null) {
            binding.btnRedo.setOnClickListener(v -> {
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
            if (isDrawingMode) {
                commitCurrentAnnotations();
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
                    handleExit();
                }
            }
        });
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
        if (isDrawingMode) {
            commitCurrentAnnotations();
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
        ScanEditState next = (curr != null) ? curr.withCrop(flatCorners)
                : new ScanEditState(flatCorners, 0, ScanEditState.FILTER_ORIGINAL, System.currentTimeMillis());
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
            vh.drawingOverlay.setToolMode(currentToolMode);
            vh.drawingOverlay.setStrokeColor(currentDrawingColor);
            vh.drawingOverlay.setStrokeWidth(currentStrokeWidth);
            vh.drawingOverlay.setOnActionAddedListener(this::updateUndoButtonState);
            vh.drawingOverlay.setOnItemSelectionListener(new AnnotationDrawingView.OnItemSelectionListener() {
                @Override
                public void onTextItemTapped(@NonNull AnnotationDrawingView.TextItem item) {
                    showTextCustomizationDialog(item);
                }

                @Override
                public void onSignatureItemTapped(@NonNull AnnotationDrawingView.SignatureItem item) {
                    selectDrawingColor(item.tintColor);
                }

                @Override
                public void onSelectionCleared() {
                }
            });
        }
    }

    private void exitDrawingMode() {
        isDrawingMode = false;
        if (viewModel != null) viewModel.setDrawingMode(false);
        binding.layoutAnnotationBar.setVisibility(View.GONE);
        updateSwipeLock();
        updateUndoButtonState();

        PageEditorAdapter.PageViewHolder vh = PageEditorAdapter.getViewHolder(binding.viewPagerPages, pageIndex);
        if (vh != null) {
            vh.drawingOverlay.setVisibility(View.GONE);
            vh.drawingOverlay.setToolMode(AnnotationDrawingView.ToolMode.NONE);
        }
    }

    /**
     * Requirement 4: Flatten applied annotations directly into the page's temporary image file.
     */
    private void commitCurrentAnnotations() {
        PageEditorAdapter.PageViewHolder vh = PageEditorAdapter.getViewHolder(binding.viewPagerPages, pageIndex);
        if (vh == null || vh.drawingOverlay.isEmpty()) {
            return;
        }

        final int targetIndex = pageIndex;
        final String currentPath = pagePaths.get(targetIndex);

        Bitmap base = CacheManager.loadBitmap(currentPath);
        if (base == null) return;

        RectF displayRect = vh.ivPageImage.getDisplayRect();
        Bitmap flattened = vh.drawingOverlay.flattenOnto(base, displayRect);
        base.recycle();

        String newPath = CacheManager.saveTempBitmap(
                this, flattened, "page_" + targetIndex + "_annotated_" + UUID.randomUUID().toString());
        flattened.recycle();

        if (newPath != null) {
            pagePaths.set(targetIndex, newPath);
            vh.drawingOverlay.clear();
            pageAdapter.notifyItemChanged(targetIndex);
            thumbAdapter.notifyItemChanged(targetIndex);
            updateUndoButtonState();

            Toast.makeText(this, R.string.annotate_saved, Toast.LENGTH_SHORT).show();
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

        binding.btnStrokeWidth.setOnClickListener(v -> {
            strokeWidthPreset = (strokeWidthPreset + 1) % 3;
            float density = getResources().getDisplayMetrics().density;
            if (strokeWidthPreset == 0) {
                currentStrokeWidth = 4f * density;
                binding.btnStrokeWidth.setText(R.string.stroke_width_fine);
            } else if (strokeWidthPreset == 1) {
                currentStrokeWidth = 8f * density;
                binding.btnStrokeWidth.setText(R.string.stroke_width_medium);
            } else {
                currentStrokeWidth = 16f * density;
                binding.btnStrokeWidth.setText(R.string.stroke_width_bold);
            }
            PageEditorAdapter.PageViewHolder vh = PageEditorAdapter.getViewHolder(binding.viewPagerPages, pageIndex);
            if (vh != null) vh.drawingOverlay.setStrokeWidth(currentStrokeWidth);
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
        // Color selection: 6 slots with outer outline rings
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

        PageEditorAdapter.PageViewHolder vh = PageEditorAdapter.getViewHolder(binding.viewPagerPages, pageIndex);
        if (vh != null) vh.drawingOverlay.setStrokeColor(color);
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
        if (isDrawingMode) {
            commitCurrentAnnotations();
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
            binding.ivFilterIcon.setColorFilter(ContextCompat.getColor(this, R.color.text_primary));
            binding.tvFilterLabel.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
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

            // Downscale to 140x140 thumbnail once for carousel preview generation (zero heavy ops on full-res)
            Bitmap baseThumb = null;
            try {
                baseThumb = android.media.ThumbnailUtils.extractThumbnail(base, 140, 140);
            } finally {
                if (base != null && !base.isRecycled() && base != baseThumb) {
                    base.recycle();
                }
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
                        showTextCustomizationDialog(activeItem);
                    } else {
                        showSignatureCaptureDialog();
                    }
                })
                .show();
    }

    private void showTextCustomizationDialog(@Nullable AnnotationDrawingView.TextItem existingItem) {
        float density = getResources().getDisplayMetrics().density;

        // Save original snapshot for clean cancellation/revert
        final String origText = existingItem != null ? existingItem.text : null;
        final String origFont = existingItem != null ? existingItem.fontFamily : null;
        final float origSize = existingItem != null ? existingItem.textSize : 0f;
        final int origColor = existingItem != null ? existingItem.color : 0;
        final boolean origBg = existingItem != null ? existingItem.hasBackground : true;

        ScrollView scrollView = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * density);
        layout.setPadding(pad, pad, pad, pad);
        scrollView.addView(layout);

        final String[] selectedFont = new String[]{existingItem != null ? existingItem.fontFamily : "sans"};

        // 1. Text input
        final EditText input = new EditText(this);
        input.setHint(R.string.text_dialog_hint);
        input.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        input.setHintTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        if (existingItem != null) {
            input.setText(existingItem.text);
            input.setSelection(existingItem.text.length());
        }
        input.setTypeface(FontUtils.getTypeface(this, selectedFont[0]));
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (existingItem != null) {
                    existingItem.text = s.toString();
                    PageEditorAdapter.PageViewHolder vh = PageEditorAdapter.getViewHolder(binding.viewPagerPages, pageIndex);
                    if (vh != null) {
                        vh.drawingOverlay.invalidate();
                    }
                }
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        layout.addView(input);

        // 2. Font Family Selector
        TextView tvFontLabel = new TextView(this);
        tvFontLabel.setText("Font Family");
        tvFontLabel.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        tvFontLabel.setTextSize(12);
        tvFontLabel.setPadding(0, (int) (12 * density), 0, (int) (6 * density));
        layout.addView(tvFontLabel);

        LinearLayout fontRow = new LinearLayout(this);
        fontRow.setOrientation(LinearLayout.HORIZONTAL);

        final String[] fonts = {"Sans", "Serif", "Mono", "Cursive"};
        final String[] fontValues = {"sans", "serif", "monospace", "cursive"};
        final TextView[] fontButtons = new TextView[fonts.length];

        for (int i = 0; i < fonts.length; i++) {
            final int idx = i;
            TextView btn = new TextView(this);
            btn.setText(fonts[i]);
            btn.setTextSize(12);
            btn.setPadding((int) (12 * density), (int) (6 * density), (int) (12 * density), (int) (6 * density));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins((int) (2 * density), 0, (int) (2 * density), 0);
            btn.setLayoutParams(lp);
            btn.setGravity(Gravity.CENTER);
            btn.setTypeface(FontUtils.getTypeface(this, fontValues[i]));

            boolean isCurrent = fontValues[i].equalsIgnoreCase(selectedFont[0]);
            btn.setBackgroundResource(isCurrent ? R.drawable.bg_title_pill : 0);
            btn.setTextColor(isCurrent ? ContextCompat.getColor(this, R.color.accent_mint) : ContextCompat.getColor(this, R.color.text_primary));

            btn.setOnClickListener(v -> {
                selectedFont[0] = fontValues[idx];
                for (int j = 0; j < fontButtons.length; j++) {
                    boolean sel = (j == idx);
                    fontButtons[j].setBackgroundResource(sel ? R.drawable.bg_title_pill : 0);
                    fontButtons[j].setTextColor(sel ? ContextCompat.getColor(this, R.color.accent_mint) : ContextCompat.getColor(this, R.color.text_primary));
                }
                Typeface tf = FontUtils.getTypeface(this, selectedFont[0]);
                input.setTypeface(tf);
                CharSequence cur = input.getText();
                if (cur != null && cur.length() > 0) {
                    int s = input.getSelectionStart();
                    int e = input.getSelectionEnd();
                    input.setText(cur);
                    if (s >= 0 && e >= 0) {
                        input.setSelection(Math.min(s, cur.length()), Math.min(e, cur.length()));
                    }
                }
                input.invalidate();
                input.requestLayout();

                // Update active text annotation directly and invalidate canvas immediately
                if (existingItem != null) {
                    existingItem.setFontFamily(selectedFont[0]);
                    PageEditorAdapter.PageViewHolder vh = PageEditorAdapter.getViewHolder(binding.viewPagerPages, pageIndex);
                    if (vh != null) {
                        vh.drawingOverlay.invalidate();
                    }
                }
            });

            fontButtons[i] = btn;
            fontRow.addView(btn);
        }
        layout.addView(fontRow);

        // 3. Font Size Slider
        final float initialSizeSp = existingItem != null ? (existingItem.textSize / density) : 18f;
        final float[] selectedSize = new float[]{initialSizeSp};

        final TextView tvSizeLabel = new TextView(this);
        tvSizeLabel.setText(String.format(Locale.US, "Font Size: %dsp", (int) selectedSize[0]));
        tvSizeLabel.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        tvSizeLabel.setTextSize(12);
        tvSizeLabel.setPadding(0, (int) (12 * density), 0, (int) (4 * density));
        layout.addView(tvSizeLabel);

        SeekBar seekBar = new SeekBar(this);
        seekBar.setMax(48); // 12sp to 60sp
        seekBar.setProgress((int) Math.max(0, Math.min(48, selectedSize[0] - 12)));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                selectedSize[0] = 12 + progress;
                tvSizeLabel.setText(String.format(Locale.US, "Font Size: %dsp", (int) selectedSize[0]));
                input.setTextSize(selectedSize[0]);
                if (existingItem != null) {
                    existingItem.textSize = selectedSize[0] * density;
                    PageEditorAdapter.PageViewHolder vh = PageEditorAdapter.getViewHolder(binding.viewPagerPages, pageIndex);
                    if (vh != null) {
                        vh.drawingOverlay.invalidate();
                    }
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        layout.addView(seekBar);

        // 4. Color Palette
        TextView tvColorLabel = new TextView(this);
        tvColorLabel.setText("Text Color");
        tvColorLabel.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        tvColorLabel.setTextSize(12);
        tvColorLabel.setPadding(0, (int) (12 * density), 0, (int) (6 * density));
        layout.addView(tvColorLabel);

        LinearLayout colorRow = new LinearLayout(this);
        colorRow.setOrientation(LinearLayout.HORIZONTAL);
        colorRow.setGravity(Gravity.CENTER);

        final int[] colors = {
                Color.parseColor("#68D391"), // Mint
                Color.parseColor("#EF4444"), // Red
                Color.parseColor("#111827"), // Black
                Color.WHITE,                 // White
                Color.parseColor("#FACC15"), // Yellow
                Color.parseColor("#3B82F6")  // Blue
        };
        final int[] colorDrawables = {
                R.drawable.bg_circle_mint,
                R.drawable.bg_circle_red,
                R.drawable.bg_circle_black,
                R.drawable.bg_circle_white,
                R.drawable.bg_circle_yellow,
                R.drawable.bg_circle_blue
        };
        final int[] selectedColor = new int[]{existingItem != null ? existingItem.color : currentDrawingColor};
        final View[] rings = new View[colors.length];

        for (int i = 0; i < colors.length; i++) {
            final int c = colors[i];
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
            ring.setVisibility(c == selectedColor[0] ? View.VISIBLE : View.GONE);
            rings[i] = ring;
            slot.addView(ring);

            View circle = new View(this);
            int circleDim = (int) (22 * density);
            FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(circleDim, circleDim, Gravity.CENTER);
            circle.setLayoutParams(clp);
            circle.setBackgroundResource(colorDrawables[i]);
            slot.addView(circle);

            slot.setOnClickListener(v -> {
                selectedColor[0] = c;
                for (int j = 0; j < rings.length; j++) {
                    rings[j].setVisibility(j == idx ? View.VISIBLE : View.GONE);
                }
                input.setTextColor(c);
                if (existingItem != null) {
                    existingItem.color = c;
                    PageEditorAdapter.PageViewHolder vh = PageEditorAdapter.getViewHolder(binding.viewPagerPages, pageIndex);
                    if (vh != null) {
                        vh.drawingOverlay.invalidate();
                    }
                }
            });
            colorRow.addView(slot);
        }
        layout.addView(colorRow);

        // 5. Background Toggle
        MaterialSwitch switchBg = new MaterialSwitch(this);
        switchBg.setText("Solid Background");
        switchBg.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        switchBg.setTextSize(13);
        switchBg.setChecked(existingItem != null ? existingItem.hasBackground : true);
        switchBg.setPadding(0, (int) (14 * density), 0, (int) (8 * density));
        switchBg.setOnCheckedChangeListener((btn, isChecked) -> {
            if (existingItem != null) {
                existingItem.hasBackground = isChecked;
                PageEditorAdapter.PageViewHolder vh = PageEditorAdapter.getViewHolder(binding.viewPagerPages, pageIndex);
                if (vh != null) {
                    vh.drawingOverlay.invalidate();
                }
            }
        });
        layout.addView(switchBg);

        // Build Dialog
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle(existingItem != null ? "Edit Text Annotation" : "Add Text Annotation")
                .setView(scrollView)
                .setPositiveButton(R.string.action_confirm, (dialog, which) -> {
                    String text = input.getText().toString().trim();
                    if (!text.isEmpty()) {
                        enterDrawingMode();
                        PageEditorAdapter.PageViewHolder vh = PageEditorAdapter.getViewHolder(binding.viewPagerPages, pageIndex);
                        if (vh != null) {
                            if (existingItem != null) {
                                existingItem.text = text;
                                existingItem.setFontFamily(selectedFont[0]);
                                existingItem.textSize = selectedSize[0] * density;
                                existingItem.color = selectedColor[0];
                                existingItem.hasBackground = switchBg.isChecked();
                                vh.drawingOverlay.invalidate();
                            } else {
                                vh.drawingOverlay.addTextAnnotation(
                                        text, selectedColor[0], selectedSize[0], selectedFont[0], switchBg.isChecked());
                                vh.drawingOverlay.setToolMode(AnnotationDrawingView.ToolMode.TEXT);
                            }
                        }
                    }
                })
                .setNegativeButton(R.string.action_cancel, (dialog, which) -> {
                    if (existingItem != null) {
                        existingItem.text = origText;
                        existingItem.fontFamily = origFont;
                        existingItem.textSize = origSize;
                        existingItem.color = origColor;
                        existingItem.hasBackground = origBg;
                        PageEditorAdapter.PageViewHolder vh = PageEditorAdapter.getViewHolder(binding.viewPagerPages, pageIndex);
                        if (vh != null) {
                            vh.drawingOverlay.invalidate();
                        }
                    }
                });

        if (existingItem != null) {
            builder.setNeutralButton(R.string.action_delete, (dialog, which) -> {
                PageEditorAdapter.PageViewHolder vh = PageEditorAdapter.getViewHolder(binding.viewPagerPages, pageIndex);
                if (vh != null) {
                    vh.drawingOverlay.deleteSelectedItem();
                }
            });
        }

        builder.show();
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
                            vh.drawingOverlay.addSignatureAnnotation(sigBmp);
                            AnnotationDrawingView.SignatureItem sigItem = vh.drawingOverlay.getSelectedSignatureItem();
                            if (sigItem != null) {
                                sigItem.tintColor = selectedInk[0];
                            }
                            vh.drawingOverlay.setToolMode(AnnotationDrawingView.ToolMode.SIGNATURE);
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
        if (isDrawingMode) commitCurrentAnnotations();
        if (isCropMode) exitCropMode();

        final int targetIndex = pageIndex;
        EditHistoryManager hm = getHistoryManager(targetIndex);
        ScanEditState curr = hm.getCurrentState();
        int newRot = (curr != null ? curr.getRotationDegrees() + 90 : 90) % 360;
        ScanEditState next = (curr != null) ? curr.withRotation(newRot)
                : new ScanEditState(null, newRot, ScanEditState.FILTER_ORIGINAL, System.currentTimeMillis());
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
        if (isAddingPage || isFromReview) {
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
            finish();
        } else {
            // Open SaveScanBottomSheet which executes PdfGenerator and saves to Room/MediaStore
            SaveScanBottomSheet bottomSheet = SaveScanBottomSheet.newInstance(pagePaths);
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