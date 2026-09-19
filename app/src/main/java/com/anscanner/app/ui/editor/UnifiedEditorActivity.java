package com.anscanner.app.ui.editor;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.viewpager2.widget.ViewPager2;

import com.anscanner.app.R;
import com.anscanner.app.databinding.ActivityUnifiedEditorBinding;
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.mlkit.vision.text.Text;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
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

    public static final String MODE_BATCH_SCAN = "BATCH_SCAN";
    public static final String MODE_PDF_EDIT = "PDF_EDIT";
    public static final String MODE_SINGLE_SCAN = "SINGLE_SCAN";

    private ActivityUnifiedEditorBinding binding;
    private PageEditorAdapter pageAdapter;
    private ThumbnailStripAdapter thumbAdapter;

    private ArrayList<String> pagePaths;
    private ArrayList<String> originalPagePaths;
    private int pageIndex = 0;
    private String editorMode = MODE_SINGLE_SCAN;
    private boolean isAddingPage = false;
    private boolean isFromReview = false;

    // Editing states
    private boolean isDrawingMode = false;
    private boolean isCropMode = false;
    private AnnotationDrawingView.ToolMode currentToolMode = AnnotationDrawingView.ToolMode.PEN;
    private int currentDrawingColor = Color.parseColor("#48BB78"); // Mint Green
    private float currentStrokeWidth = 8f; // 8dp
    private int strokeWidthPreset = 1; // 0=Fine(4dp), 1=Medium(8dp), 2=Bold(16dp)

    private final Map<Integer, Point[]> savedCornersMap = new HashMap<>();
    private ExecutorService executor;
    private Handler mainHandler;
    private volatile boolean isBusy = false;

    // ── Unified Undo / Redo Architecture ──────────────────────────────

    public interface EditorAction {
        void undo();
        void redo();
    }

    public class ImageTransformAction implements EditorAction {
        private final int targetPageIndex;
        private final String backupPath;
        private final String transformedPath;
        private final Point[] previousCorners;
        private final Point[] newCorners;

        public ImageTransformAction(int targetPageIndex, String backupPath, String transformedPath, Point[] previousCorners, Point[] newCorners) {
            this.targetPageIndex = targetPageIndex;
            this.backupPath = backupPath;
            this.transformedPath = transformedPath;
            this.previousCorners = previousCorners;
            this.newCorners = newCorners;
        }

        public int getTargetPageIndex() {
            return targetPageIndex;
        }

        public String getBackupPath() {
            return backupPath;
        }

        public String getTransformedPath() {
            return transformedPath;
        }

        @Override
        public void undo() {
            if (targetPageIndex >= 0 && targetPageIndex < pagePaths.size()) {
                pagePaths.set(targetPageIndex, backupPath);
                if (previousCorners != null) {
                    savedCornersMap.put(targetPageIndex, previousCorners);
                } else {
                    savedCornersMap.remove(targetPageIndex);
                }

                if (binding.viewPager.getCurrentItem() != targetPageIndex) {
                    binding.viewPager.setCurrentItem(targetPageIndex, false);
                }

                pageAdapter.notifyItemChanged(targetPageIndex);
                thumbAdapter.notifyItemChanged(targetPageIndex);

                PageEditorAdapter.PageViewHolder vh = PageEditorAdapter.getViewHolder(binding.viewPager, targetPageIndex);
                if (vh != null) {
                    Bitmap bmp = CacheManager.loadBitmap(backupPath);
                    if (bmp != null) {
                        vh.ivPageImage.setImageBitmap(bmp);
                        vh.drawingOverlay.bindImageView(vh.ivPageImage);
                        if (previousCorners != null) {
                            vh.cropOverlay.setCorners(previousCorners);
                        }
                    }
                }
            }
        }

        @Override
        public void redo() {
            if (targetPageIndex >= 0 && targetPageIndex < pagePaths.size()) {
                pagePaths.set(targetPageIndex, transformedPath);
                if (newCorners != null) {
                    savedCornersMap.put(targetPageIndex, newCorners);
                }

                if (binding.viewPager.getCurrentItem() != targetPageIndex) {
                    binding.viewPager.setCurrentItem(targetPageIndex, false);
                }

                pageAdapter.notifyItemChanged(targetPageIndex);
                thumbAdapter.notifyItemChanged(targetPageIndex);

                PageEditorAdapter.PageViewHolder vh = PageEditorAdapter.getViewHolder(binding.viewPager, targetPageIndex);
                if (vh != null) {
                    Bitmap bmp = CacheManager.loadBitmap(transformedPath);
                    if (bmp != null) {
                        vh.ivPageImage.setImageBitmap(bmp);
                        vh.drawingOverlay.bindImageView(vh.ivPageImage);
                        if (newCorners != null) {
                            vh.cropOverlay.setCorners(newCorners);
                        }
                    }
                }
            }
        }
    }

    public class DrawingStrokeAction implements EditorAction {
        private final int targetPageIndex;

        public DrawingStrokeAction(int targetPageIndex) {
            this.targetPageIndex = targetPageIndex;
        }

        @Override
        public void undo() {
            if (binding.viewPager.getCurrentItem() != targetPageIndex) {
                binding.viewPager.setCurrentItem(targetPageIndex, false);
            }
            PageEditorAdapter.PageViewHolder vh = PageEditorAdapter.getViewHolder(binding.viewPager, targetPageIndex);
            if (vh != null) {
                vh.drawingOverlay.undo();
            }
        }

        @Override
        public void redo() {
        }
    }

    private final Stack<EditorAction> undoStack = new Stack<>();
    private final Stack<EditorAction> redoStack = new Stack<>();

    private String backupFile(String sourcePath, String prefix) {
        if (sourcePath == null) return null;
        try {
            File src = new File(sourcePath);
            if (!src.exists()) return null;
            File backup = new File(getCacheDir(), prefix + "_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString() + ".jpg");
            try (InputStream in = new FileInputStream(src);
                 OutputStream out = new FileOutputStream(backup)) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
            }
            return backup.getAbsolutePath();
        } catch (Exception e) {
            Log.e(TAG, "Failed to backup file: " + sourcePath, e);
            return sourcePath;
        }
    }

    private void updateUndoButtonState() {
        boolean canUndo = !undoStack.isEmpty();
        if (!canUndo && isDrawingMode) {
            PageEditorAdapter.PageViewHolder vh = PageEditorAdapter.getViewHolder(binding.viewPager, pageIndex);
            if (vh != null && vh.drawingOverlay.getActionCount() > 0) {
                canUndo = true;
            }
        }
        binding.btnUndo.setEnabled(canUndo);
        binding.btnUndo.setAlpha(canUndo ? 1.0f : 0.3f);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityUnifiedEditorBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        executor = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());

        Intent intent = getIntent();
        editorMode = intent.getStringExtra(EXTRA_MODE);
        if (editorMode == null) editorMode = MODE_SINGLE_SCAN;

        isAddingPage = intent.getBooleanExtra(EXTRA_IS_ADDING_PAGE, false);
        isFromReview = intent.getBooleanExtra(EXTRA_FROM_REVIEW, false);

        // Resolve input paths or PDF URI
        Uri pdfUri = intent.getData();
        if (pdfUri == null && intent.hasExtra(EXTRA_PDF_URI)) {
            pdfUri = intent.getParcelableExtra(EXTRA_PDF_URI);
        }
        String singleImagePath = intent.getStringExtra(EXTRA_IMAGE_PATH);
        if (pdfUri == null && singleImagePath != null && singleImagePath.toLowerCase().endsWith(".pdf")) {
            pdfUri = Uri.fromFile(new File(singleImagePath));
        }

        if (intent.hasExtra(EXTRA_PAGE_PATHS)) {
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

        if (intent.hasExtra(EXTRA_ORIGINAL_PAGE_PATHS)) {
            originalPagePaths = intent.getStringArrayListExtra(EXTRA_ORIGINAL_PAGE_PATHS);
        } else {
            originalPagePaths = new ArrayList<>(pagePaths);
        }

        pageIndex = intent.getIntExtra(EXTRA_PAGE_INDEX, 0);
        if (pageIndex < 0 || pageIndex >= pagePaths.size()) {
            pageIndex = 0;
        }

        setupViewPager();
        setupThumbnailStrip();
        setupTopBar();
        setupBottomToolbar();
        setupAnnotationBar();
        updateTitle();
        updateSwipeLock();

        // Check if opened directly into annotation mode
        if (intent.getBooleanExtra(EXTRA_START_ANNOTATE, false)) {
            binding.getRoot().post(this::enterDrawingMode);
        }
    }

    private void setupViewPager() {
        pageAdapter = new PageEditorAdapter(pagePaths, null);
        binding.viewPager.setAdapter(pageAdapter);
        binding.viewPager.setOffscreenPageLimit(1);
        binding.viewPager.setCurrentItem(pageIndex, false);

        binding.viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                onPageSwitched(position);
            }
        });
    }

    private void setupThumbnailStrip() {
        thumbAdapter = new ThumbnailStripAdapter(pagePaths, pageIndex, position -> {
            if (position >= 0 && position < pagePaths.size()) {
                if (isDrawingMode) {
                    commitCurrentAnnotations();
                } else if (isCropMode) {
                    exitCropMode();
                }
                binding.viewPager.setCurrentItem(position, true);
            }
        });
        binding.rvThumbnails.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        binding.rvThumbnails.setAdapter(thumbAdapter);
    }

    private void onPageSwitched(int newPosition) {
        if (newPosition < 0 || newPosition >= pagePaths.size()) return;

        // Auto-commit any active drawing before switching
        if (isDrawingMode) {
            commitCurrentAnnotations();
        } else if (isCropMode) {
            exitCropMode();
        }

        pageIndex = newPosition;
        updateTitle();
        thumbAdapter.setSelectedPosition(pageIndex);
        binding.rvThumbnails.smoothScrollToPosition(pageIndex);

        updateNavigationButtons();
        updateUndoButtonState();
    }

    private void updateTitle() {
        binding.tvTitle.setText(getString(R.string.crop_title, pageIndex + 1, Math.max(1, pagePaths.size())));
    }

    private void updateNavigationButtons() {
        binding.btnPrevPage.setVisibility(pageIndex > 0 ? View.VISIBLE : View.GONE);
        binding.btnNextPage.setVisibility(pageIndex < pagePaths.size() - 1 ? View.VISIBLE : View.GONE);
    }

    /**
     * Requirement 1: Disable ViewPager2 touch/swipe gestures while in an active editing state.
     */
    private void updateSwipeLock() {
        binding.viewPager.setUserInputEnabled(!isDrawingMode && !isCropMode);
    }

    private void setupTopBar() {
        binding.btnClose.setOnClickListener(v -> handleExit());

        binding.btnPrevPage.setOnClickListener(v -> {
            if (pageIndex > 0) {
                if (isDrawingMode) commitCurrentAnnotations();
                binding.viewPager.setCurrentItem(pageIndex - 1, true);
            }
        });

        binding.btnNextPage.setOnClickListener(v -> {
            if (pageIndex < pagePaths.size() - 1) {
                if (isDrawingMode) commitCurrentAnnotations();
                binding.viewPager.setCurrentItem(pageIndex + 1, true);
            }
        });

        binding.btnUndo.setOnClickListener(v -> {
            if (!undoStack.isEmpty()) {
                EditorAction action = undoStack.pop();
                action.undo();
                redoStack.push(action);
                updateUndoButtonState();
            } else if (isDrawingMode) {
                PageEditorAdapter.PageViewHolder vh = PageEditorAdapter.getViewHolder(binding.viewPager, pageIndex);
                if (vh != null && vh.drawingOverlay.getActionCount() > 0) {
                    vh.drawingOverlay.undo();
                    updateUndoButtonState();
                }
            }
        });

        binding.btnExtractText.setOnClickListener(v -> extractTextFromCurrentScan());

        binding.btnSaveDocument.setOnClickListener(v -> {
            if (isBusy) return;
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
        binding.btnAnnotate.setOnClickListener(v -> onAnnotateClicked());
        binding.btnTextSign.setOnClickListener(v -> showTextOrSignatureChooser());
        binding.btnRotate.setOnClickListener(v -> rotateCurrentPage());
        binding.btnDelete.setOnClickListener(v -> showDeleteConfirmation());
    }

    // â”€â”€ Crop Mode â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private void onCropClicked() {
        if (isBusy) return;

        if (!isCropMode) {
            // Enter crop mode
            if (isDrawingMode) {
                commitCurrentAnnotations();
                exitDrawingMode();
            }
            isCropMode = true;
            updateSwipeLock();

            binding.tvCropLabel.setText(R.string.action_apply);
            binding.ivCropIcon.setImageResource(R.drawable.ic_check);

            PageEditorAdapter.PageViewHolder vh = PageEditorAdapter.getViewHolder(binding.viewPager, pageIndex);
            if (vh != null) {
                vh.ivPageImage.setScale(1.0f, false);
                vh.cropOverlay.setImageView(vh.ivPageImage);
                prepareCropForPage(pageIndex, vh);
            }
            Toast.makeText(this, "Adjust edges & tap Apply to crop", Toast.LENGTH_SHORT).show();
        } else {
            // Apply crop
            commitCrop(null);
        }
    }

    private void prepareCropForPage(int index, PageEditorAdapter.PageViewHolder vh) {
        isBusy = true;
        executor.execute(() -> {
            Point[] corners = savedCornersMap.get(index);
            if (corners == null) {
                String origPath = (index < originalPagePaths.size()) ? originalPagePaths.get(index) : pagePaths.get(index);
                Bitmap bmp = CacheManager.loadBitmap(origPath);
                if (bmp == null) {
                    bmp = CacheManager.loadBitmap(pagePaths.get(index));
                }
                if (bmp != null) {
                    org.opencv.core.Point[] detected = DocumentDetector.getInstance(UnifiedEditorActivity.this)
                            .detectCorners(bmp);
                    if (detected == null) {
                        detected = DocumentDetector.getDefaultCorners(bmp.getWidth(), bmp.getHeight());
                    }
                    corners = CropPreviewActivity.toAndroidPoints(detected);
                    savedCornersMap.put(index, corners);
                    bmp.recycle();
                }
            }

            final Point[] finalCorners = corners;
            runOnUiThread(() -> {
                isBusy = false;
                if (vh != null) {
                    vh.ivPageImage.setScale(1.0f, false);
                    vh.cropOverlay.setImageView(vh.ivPageImage);
                    if (finalCorners != null) {
                        vh.cropOverlay.setCorners(finalCorners);
                        vh.cropOverlay.resetCornerMoved();
                    }
                    vh.cropOverlay.setVisibility(View.VISIBLE);
                }
            });
        });
    }

    private void commitCrop(@Nullable Runnable onComplete) {
        PageEditorAdapter.PageViewHolder vh = PageEditorAdapter.getViewHolder(binding.viewPager, pageIndex);
        Point[] currentCorners = vh != null ? vh.cropOverlay.getCornerPoints() : null;
        if (currentCorners == null || currentCorners.length != 4) {
            currentCorners = savedCornersMap.get(pageIndex);
        }
        if (currentCorners == null || currentCorners.length != 4) {
            exitCropMode();
            if (onComplete != null) onComplete.run();
            return;
        }

        isBusy = true;
        final Point[] cornersToApply = currentCorners;
        final int targetPageIndex = pageIndex;
        final String originalPath = (targetPageIndex < originalPagePaths.size())
                ? originalPagePaths.get(targetPageIndex) : pagePaths.get(targetPageIndex);
        final String preCropPath = pagePaths.get(targetPageIndex);
        final String backupPath = backupFile(preCropPath, "backup_crop_page_" + targetPageIndex);
        final Point[] prevCorners = savedCornersMap.get(targetPageIndex);

        executor.execute(() -> {
            Bitmap master = CacheManager.loadBitmap(originalPath);
            if (master == null) {
                master = CacheManager.loadBitmap(pagePaths.get(targetPageIndex));
            }
            if (master == null) {
                runOnUiThread(() -> {
                    isBusy = false;
                    exitCropMode();
                    if (onComplete != null) onComplete.run();
                });
                return;
            }

            org.opencv.core.Point[] opencvCorners = CropPreviewActivity.toOpenCvPoints(cornersToApply);
            Bitmap cropped = ImageProcessor.perspectiveWarp(master, opencvCorners);
            master.recycle();

            String croppedPath = CacheManager.saveTempBitmap(
                    UnifiedEditorActivity.this, cropped, "page_" + targetPageIndex + "_cropped_" + UUID.randomUUID().toString());
            cropped.recycle();

            runOnUiThread(() -> {
                isBusy = false;
                if (croppedPath != null) {
                    pagePaths.set(targetPageIndex, croppedPath);
                    savedCornersMap.put(targetPageIndex, cornersToApply);
                    pageAdapter.notifyItemChanged(targetPageIndex);
                    thumbAdapter.notifyItemChanged(targetPageIndex);

                    undoStack.push(new ImageTransformAction(targetPageIndex, backupPath, croppedPath, prevCorners, cornersToApply));
                    redoStack.clear();
                    updateUndoButtonState();

                    Toast.makeText(UnifiedEditorActivity.this, "Crop applied", Toast.LENGTH_SHORT).show();
                }

                exitCropMode();
                if (onComplete != null) onComplete.run();
            });
        });
    }

    private void exitCropMode() {
        isCropMode = false;
        PageEditorAdapter.PageViewHolder vh = PageEditorAdapter.getViewHolder(binding.viewPager, pageIndex);
        if (vh != null) {
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
        if (isCropMode) {
            exitCropMode();
        }
        isDrawingMode = true;
        updateSwipeLock();
        binding.viewPager.setUserInputEnabled(false);

        binding.layoutAnnotationBar.setVisibility(View.VISIBLE);
        updateUndoButtonState();

        PageEditorAdapter.PageViewHolder vh = PageEditorAdapter.getViewHolder(binding.viewPager, pageIndex);
        if (vh != null) {
            vh.drawingOverlay.setVisibility(View.VISIBLE);
            vh.drawingOverlay.bindImageView(vh.ivPageImage);
            vh.drawingOverlay.setToolMode(currentToolMode);
            vh.drawingOverlay.setStrokeColor(currentDrawingColor);
            vh.drawingOverlay.setStrokeWidth(currentStrokeWidth);
            vh.drawingOverlay.setOnActionAddedListener(() -> {
                undoStack.push(new DrawingStrokeAction(pageIndex));
                redoStack.clear();
                updateUndoButtonState();
            });
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
        binding.layoutAnnotationBar.setVisibility(View.GONE);
        updateSwipeLock();
        updateUndoButtonState();

        PageEditorAdapter.PageViewHolder vh = PageEditorAdapter.getViewHolder(binding.viewPager, pageIndex);
        if (vh != null) {
            vh.drawingOverlay.setVisibility(View.GONE);
            vh.drawingOverlay.setToolMode(AnnotationDrawingView.ToolMode.NONE);
        }
    }

    /**
     * Requirement 4: Flatten applied annotations directly into the page's temporary image file.
     */
    private void commitCurrentAnnotations() {
        PageEditorAdapter.PageViewHolder vh = PageEditorAdapter.getViewHolder(binding.viewPager, pageIndex);
        if (vh == null || vh.drawingOverlay.isEmpty()) {
            return;
        }

        final int targetIndex = pageIndex;
        final String currentPath = pagePaths.get(targetIndex);
        final String backupPath = backupFile(currentPath, "backup_annot_page_" + targetIndex);

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

            undoStack.push(new ImageTransformAction(targetIndex, backupPath, newPath, null, null));
            redoStack.clear();
            updateUndoButtonState();

            Toast.makeText(this, R.string.annotate_saved, Toast.LENGTH_SHORT).show();
        }
    }

    private void setupAnnotationBar() {
        binding.btnToolPen.setOnClickListener(v -> {
            binding.viewPager.setUserInputEnabled(false);
            currentToolMode = AnnotationDrawingView.ToolMode.PEN;
            binding.btnToolPen.setColorFilter(ContextCompat.getColor(this, R.color.accent_mint));
            binding.btnToolHighlighter.setColorFilter(ContextCompat.getColor(this, R.color.text_secondary));
            PageEditorAdapter.PageViewHolder vh = PageEditorAdapter.getViewHolder(binding.viewPager, pageIndex);
            if (vh != null) vh.drawingOverlay.setToolMode(currentToolMode);
        });

        binding.btnToolHighlighter.setOnClickListener(v -> {
            binding.viewPager.setUserInputEnabled(false);
            currentToolMode = AnnotationDrawingView.ToolMode.HIGHLIGHTER;
            binding.btnToolHighlighter.setColorFilter(ContextCompat.getColor(this, R.color.accent_mint));
            binding.btnToolPen.setColorFilter(ContextCompat.getColor(this, R.color.text_secondary));
            PageEditorAdapter.PageViewHolder vh = PageEditorAdapter.getViewHolder(binding.viewPager, pageIndex);
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
            PageEditorAdapter.PageViewHolder vh = PageEditorAdapter.getViewHolder(binding.viewPager, pageIndex);
            if (vh != null) vh.drawingOverlay.setStrokeWidth(currentStrokeWidth);
        });

        binding.btnClearAnnotation.setOnClickListener(v -> {
            PageEditorAdapter.PageViewHolder vh = PageEditorAdapter.getViewHolder(binding.viewPager, pageIndex);
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

        PageEditorAdapter.PageViewHolder vh = PageEditorAdapter.getViewHolder(binding.viewPager, pageIndex);
        if (vh != null) vh.drawingOverlay.setStrokeColor(color);
    }

    // ── Text & Signature Dialogs ─────────────────────────────────────────

    private void showTextOrSignatureChooser() {
        if (!isDrawingMode) {
            enterDrawingMode();
        }
        binding.viewPager.setUserInputEnabled(false);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.action_text_sign)
                .setItems(new CharSequence[]{getString(R.string.action_text), getString(R.string.action_signature)}, (dialog, which) -> {
                    if (which == 0) {
                        showTextCustomizationDialog(null);
                    } else {
                        showSignatureCaptureDialog();
                    }
                })
                .show();
    }

    private void showTextCustomizationDialog(@Nullable AnnotationDrawingView.TextItem existingItem) {
        float density = getResources().getDisplayMetrics().density;

        ScrollView scrollView = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * density);
        layout.setPadding(pad, pad, pad, pad);
        scrollView.addView(layout);

        // 1. Text input
        final EditText input = new EditText(this);
        input.setHint(R.string.text_dialog_hint);
        input.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        input.setHintTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        if (existingItem != null) {
            input.setText(existingItem.text);
            input.setSelection(existingItem.text.length());
        }
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
        final String[] selectedFont = new String[]{existingItem != null ? existingItem.fontFamily : "sans"};

        for (int i = 0; i < fonts.length; i++) {
            final int idx = i;
            TextView btn = new TextView(this);
            btn.setText(fonts[i]);
            btn.setTextSize(12);
            btn.setPadding((int) (12 * density), (int) (6 * density), (int) (12 * density), (int) (6 * density));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins((int) (2 * density), 0, (int) (2 * density), 0);
            btn.setLayoutParams(lp);
            btn.setGravity(android.view.Gravity.CENTER);
            btn.setTypeface(AnnotationDrawingView.getTypefaceForFont(fontValues[i]));

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
                input.setTypeface(AnnotationDrawingView.getTypefaceForFont(selectedFont[0]));
            });

            fontButtons[i] = btn;
            fontRow.addView(btn);
        }
        layout.addView(fontRow);

        // 3. Font Size Slider
        final float initialSizeSp = existingItem != null ? (existingItem.textSize / density) : 18f;
        final float[] selectedSize = new float[]{initialSizeSp};

        final TextView tvSizeLabel = new TextView(this);
        tvSizeLabel.setText(String.format(java.util.Locale.US, "Font Size: %dsp", (int) selectedSize[0]));
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
                tvSizeLabel.setText(String.format(java.util.Locale.US, "Font Size: %dsp", (int) selectedSize[0]));
                input.setTextSize(selectedSize[0]);
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
        colorRow.setGravity(android.view.Gravity.CENTER);

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
            FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(ringDim, ringDim, android.view.Gravity.CENTER);
            ring.setLayoutParams(rlp);
            ring.setBackgroundResource(R.drawable.bg_circle_ring);
            ring.setVisibility(c == selectedColor[0] ? View.VISIBLE : View.GONE);
            rings[i] = ring;
            slot.addView(ring);

            View circle = new View(this);
            int circleDim = (int) (22 * density);
            FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(circleDim, circleDim, android.view.Gravity.CENTER);
            circle.setLayoutParams(clp);
            circle.setBackgroundResource(colorDrawables[i]);
            slot.addView(circle);

            slot.setOnClickListener(v -> {
                selectedColor[0] = c;
                for (int j = 0; j < rings.length; j++) {
                    rings[j].setVisibility(j == idx ? View.VISIBLE : View.GONE);
                }
                input.setTextColor(c);
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
        layout.addView(switchBg);

        // Build Dialog
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle(existingItem != null ? "Edit Text Annotation" : "Add Text Annotation")
                .setView(scrollView)
                .setPositiveButton(R.string.action_confirm, (dialog, which) -> {
                    String text = input.getText().toString().trim();
                    if (!text.isEmpty()) {
                        enterDrawingMode();
                        PageEditorAdapter.PageViewHolder vh = PageEditorAdapter.getViewHolder(binding.viewPager, pageIndex);
                        if (vh != null) {
                            if (existingItem != null) {
                                existingItem.text = text;
                                existingItem.fontFamily = selectedFont[0];
                                existingItem.textSize = selectedSize[0] * density;
                                existingItem.color = selectedColor[0];
                                existingItem.hasBackground = switchBg.isChecked();
                                vh.drawingOverlay.invalidate();
                            } else {
                                vh.drawingOverlay.addTextAnnotation(text, selectedColor[0], selectedSize[0]);
                                AnnotationDrawingView.TextItem newItem = vh.drawingOverlay.getSelectedTextItem();
                                if (newItem != null) {
                                    newItem.fontFamily = selectedFont[0];
                                    newItem.hasBackground = switchBg.isChecked();
                                }
                                vh.drawingOverlay.setToolMode(AnnotationDrawingView.ToolMode.TEXT);
                            }
                        }
                    }
                })
                .setNegativeButton(R.string.action_cancel, null);

        if (existingItem != null) {
            builder.setNeutralButton(R.string.action_delete, (dialog, which) -> {
                PageEditorAdapter.PageViewHolder vh = PageEditorAdapter.getViewHolder(binding.viewPager, pageIndex);
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
        colorRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
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
            FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(ringDim, ringDim, android.view.Gravity.CENTER);
            ring.setLayoutParams(rlp);
            ring.setBackgroundResource(R.drawable.bg_circle_ring);
            ring.setVisibility(i == 0 ? View.VISIBLE : View.GONE);
            inkRings[i] = ring;
            slot.addView(ring);

            View circle = new View(this);
            int circleDim = (int) (22 * density);
            FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(circleDim, circleDim, android.view.Gravity.CENTER);
            circle.setLayoutParams(clp);
            circle.setBackgroundResource(inkDrawables[i]);
            slot.addView(circle);

            colorRow.addView(slot);
        }
        root.addView(colorRow);

        // Drawing Canvas Card
        com.google.android.material.card.MaterialCardView card = new com.google.android.material.card.MaterialCardView(this);
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
                        PageEditorAdapter.PageViewHolder vh = PageEditorAdapter.getViewHolder(binding.viewPager, pageIndex);
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
        if (isDrawingMode) commitCurrentAnnotations();
        if (isCropMode) exitCropMode();

        isBusy = true;
        final int targetIndex = pageIndex;
        final String preRotatePath = pagePaths.get(targetIndex);
        final String backupPath = backupFile(preRotatePath, "backup_rot_page_" + targetIndex);
        final Point[] prevCorners = savedCornersMap.get(targetIndex);

        executor.execute(() -> {
            Bitmap base = CacheManager.loadBitmap(preRotatePath);
            if (base == null) {
                runOnUiThread(() -> isBusy = false);
                return;
            }

            Matrix matrix = new Matrix();
            matrix.postRotate(90);
            Bitmap rotated = Bitmap.createBitmap(base, 0, 0, base.getWidth(), base.getHeight(), matrix, true);
            base.recycle();

            String newPath = CacheManager.saveTempBitmap(
                    UnifiedEditorActivity.this, rotated, "page_" + targetIndex + "_rot_" + UUID.randomUUID().toString());
            rotated.recycle();

            runOnUiThread(() -> {
                isBusy = false;
                if (newPath != null) {
                    pagePaths.set(targetIndex, newPath);
                    pageAdapter.notifyItemChanged(targetIndex);
                    thumbAdapter.notifyItemChanged(targetIndex);

                    undoStack.push(new ImageTransformAction(targetIndex, backupPath, newPath, prevCorners, null));
                    redoStack.clear();
                    updateUndoButtonState();
                }
            });
        });
    }

    private void showDeleteConfirmation() {
        if (pagePaths.size() <= 1) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.review_delete_page_confirm)
                    .setMessage("Deleting this page will discard the document session. Proceed?")
                    .setPositiveButton(R.string.action_delete, (dialog, which) -> {
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
                    pageAdapter.notifyDataSetChanged();
                    thumbAdapter.notifyDataSetChanged();
                    int newIndex = Math.min(pageIndex, pagePaths.size() - 1);
                    binding.viewPager.setCurrentItem(newIndex, false);
                    onPageSwitched(newIndex);
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    // â”€â”€ OCR Extraction â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private void extractTextFromCurrentScan() {
        if (binding.lensOverlay.getVisibility() == View.VISIBLE) {
            dismissLensOverlay();
            return;
        }

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

                PageEditorAdapter.PageViewHolder vh = PageEditorAdapter.getViewHolder(binding.viewPager, pageIndex);
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

    private void dismissLensOverlay() {
        if (binding.lensOverlay.getVisibility() == View.VISIBLE) {
            binding.lensOverlay.setVisibility(View.GONE);
            binding.lensOverlay.clear();
        }
    }

    // â”€â”€ Finish, Export & Save â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private void finishAndExport() {
        if (isAddingPage || isFromReview) {
            Intent resultIntent = new Intent();
            resultIntent.putStringArrayListExtra(EXTRA_PAGE_PATHS, pagePaths);
            resultIntent.putStringArrayListExtra(EXTRA_ORIGINAL_PAGE_PATHS, originalPagePaths);
            setResult(RESULT_OK, resultIntent);
            finish();
        } else {
            // Open SaveScanBottomSheet which executes PdfGenerator and saves to Room/MediaStore
            SaveScanBottomSheet bottomSheet = SaveScanBottomSheet.newInstance(pagePaths);
            bottomSheet.show(getSupportFragmentManager(), "SaveScanBottomSheet");
        }
    }

    private void handleExit() {
        if (isFromReview) {
            Intent resultIntent = new Intent();
            resultIntent.putStringArrayListExtra(EXTRA_PAGE_PATHS, pagePaths);
            resultIntent.putStringArrayListExtra(EXTRA_ORIGINAL_PAGE_PATHS, originalPagePaths);
            setResult(RESULT_OK, resultIntent);
        }
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executor != null) {
            executor.shutdown();
        }
    }
}