package com.anscanner.app.ui.crop;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.PointF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.anscanner.app.R;
import com.anscanner.app.databinding.ActivityCropPreviewBinding;
import com.anscanner.app.processing.DocumentDetector;
import com.anscanner.app.processing.ImageProcessor;
import com.anscanner.app.service.CacheManager;
import com.anscanner.app.service.OcrHelper;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.mlkit.vision.text.Text;
import com.anscanner.app.ui.camera.CameraActivity;
import com.anscanner.app.ui.review.ReviewScanActivity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CropPreviewActivity extends AppCompatActivity {
    public static final String EXTRA_IMAGE_PATH = "extra_image_path";
    public static final String EXTRA_PAGE_PATHS = "extra_page_paths";
    public static final String EXTRA_ORIGINAL_PAGE_PATHS = "extra_original_page_paths";
    public static final String EXTRA_PAGE_INDEX = "extra_page_index";
    public static final String EXTRA_IS_ADDING_PAGE = "extra_is_adding_page";
    public static final String EXTRA_CORNER_POINTS = "extra_corner_points";
    public static final String EXTRA_FROM_REVIEW = "extra_from_review";

    private ActivityCropPreviewBinding binding;
    private String currentImagePath;

    private ArrayList<String> pagePaths;
    private ArrayList<String> originalPagePaths;
    private int pageIndex;
    private boolean isAddingPage = false;
    private boolean isFromReview = false;
    private boolean currentLoupeOnRight = false;
    private volatile boolean isBusy = false;

    // ── Per-page persistent states ───────────────────────────────────────
    private final Map<Integer, Point[]> savedCornersMap = new HashMap<>();
    private final Map<Integer, Boolean> isCroppedMap = new HashMap<>();
    private final Map<Integer, Filter> pageFilterMap = new HashMap<>();
    private final Map<Integer, Deque<EditState>> undoStacks = new HashMap<>();

    // ── Active bitmaps in memory ─────────────────────────────────────────
    private Bitmap masterOriginalBitmap;     // Pristine uncropped bitmap
    private Bitmap currentCroppedBitmap;      // Perspective-warped bitmap
    private Bitmap currentFilteredBitmap;     // Disposable filtered preview bitmap

    private ExecutorService executor;
    private Handler mainHandler;
    private enum Filter { ORIGINAL, MAGIC, BW, GRAYSCALE, SHARPEN, SCAN_ENHANCE }

    private static class EditState {
        final String workingImagePath;
        final Point[] savedCorners;
        final boolean isCropped;
        final Filter filter;

        EditState(String workingImagePath, Point[] savedCorners, boolean isCropped, Filter filter) {
            this.workingImagePath = workingImagePath;
            this.savedCorners = savedCorners != null ? copyPoints(savedCorners) : null;
            this.isCropped = isCropped;
            this.filter = filter;
        }

        private static Point[] copyPoints(Point[] src) {
            Point[] copy = new Point[src.length];
            for (int i = 0; i < src.length; i++) {
                copy[i] = new Point(src[i].x, src[i].y);
            }
            return copy;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityCropPreviewBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        executor = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());

        Intent intent = getIntent();
        currentImagePath = intent.getStringExtra(EXTRA_IMAGE_PATH);
        isAddingPage = intent.getBooleanExtra(EXTRA_IS_ADDING_PAGE, false);
        isFromReview = intent.getBooleanExtra(EXTRA_FROM_REVIEW, false);

        if (intent.hasExtra(EXTRA_PAGE_PATHS)) {
            pagePaths = intent.getStringArrayListExtra(EXTRA_PAGE_PATHS);
        } else {
            pagePaths = new ArrayList<>();
            pagePaths.add(currentImagePath);
        }

        if (intent.hasExtra(EXTRA_ORIGINAL_PAGE_PATHS)) {
            originalPagePaths = intent.getStringArrayListExtra(EXTRA_ORIGINAL_PAGE_PATHS);
        } else {
            originalPagePaths = new ArrayList<>(pagePaths);
        }

        pageIndex = intent.getIntExtra(EXTRA_PAGE_INDEX, 0);

        binding.tvTitle.setText(getString(R.string.crop_title, pageIndex + 1, pagePaths.size()));

        if (isAddingPage) {
            binding.btnSaveDocument.setText(R.string.crop_confirm_next);
        } else if (isFromReview) {
            binding.btnSaveDocument.setText(R.string.action_confirm);
        }

        // If opened from review scan or is an ID composite, mark as cropped
        if (isFromReview || (currentImagePath != null && currentImagePath.contains("id_composite"))) {
            isCroppedMap.put(pageIndex, true);
        }

        double[] cornerArray = intent.getDoubleArrayExtra(CameraActivity.EXTRA_DETECTED_CORNERS);

        setupUI();
        updateNavigationButtons();
        updateUndoButton();
        loadPage(pageIndex, cornerArray, null);
    }

    private void setupUI() {
        binding.btnClose.setOnClickListener(v -> handleExit());
        binding.cropOverlay.setImageView(binding.ivDocument);

        binding.lensOverlay.setOnDismissListener(() -> {
            boolean isCropped = Boolean.TRUE.equals(isCroppedMap.get(pageIndex));
            if (!isCropped) {
                binding.cropOverlay.setVisibility(View.VISIBLE);
            }
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (binding.lensOverlay.getVisibility() == View.VISIBLE) {
                    dismissLensOverlay();
                } else {
                    handleExit();
                }
            }
        });

        // Undo button
        binding.btnUndo.setOnClickListener(v -> performUndo());

        // Page navigation via arrows
        binding.btnPrevPage.setOnClickListener(v -> navigateToPage(pageIndex - 1));
        binding.btnNextPage.setOnClickListener(v -> navigateToPage(pageIndex + 1));

        // Horizontal swipe gesture listener on crop canvas
        binding.cropOverlay.setOnPageSwipeListener(new CropOverlayView.OnPageSwipeListener() {
            @Override
            public void onSwipeNext() {
                if (pageIndex < pagePaths.size() - 1) {
                    navigateToPage(pageIndex + 1);
                }
            }

            @Override
            public void onSwipePrevious() {
                if (pageIndex > 0) {
                    navigateToPage(pageIndex - 1);
                }
            }
        });

        // The Crop button: toggles Re-Crop mode if cropped, or commits crop if in edge adjust mode
        binding.btnCrop.setOnClickListener(v -> onCropButtonClicked());

        binding.btnFilter.setOnClickListener(v -> {
            boolean isVisible = binding.filterContainer.getVisibility() == View.VISIBLE;
            binding.filterContainer.setVisibility(isVisible ? View.GONE : View.VISIBLE);
        });

        binding.btnFilterOriginal.setOnClickListener(v -> handleFilterClick(Filter.ORIGINAL));
        binding.btnFilterMagic.setOnClickListener(v -> handleFilterClick(Filter.MAGIC));
        binding.btnFilterBW.setOnClickListener(v -> handleFilterClick(Filter.BW));
        binding.btnFilterGrayscale.setOnClickListener(v -> handleFilterClick(Filter.GRAYSCALE));
        binding.btnFilterSharpen.setOnClickListener(v -> handleFilterClick(Filter.SHARPEN));
        binding.btnFilterScanEnhance.setOnClickListener(v -> handleFilterClick(Filter.SCAN_ENHANCE));

        // Rotate
        binding.btnRotate.setOnClickListener(v -> {
            if (isBusy) return;
            boolean isCropped = Boolean.TRUE.equals(isCroppedMap.get(pageIndex));
            if (!isCropped) {
                commitCrop(this::doRotate);
            } else {
                doRotate();
            }
        });

        binding.btnDelete.setOnClickListener(v -> showDeleteConfirmation());

        // Save / Proceed: never re-warps if already cropped!
        binding.btnSaveDocument.setOnClickListener(v -> {
            if (isBusy) return;
            boolean isCropped = Boolean.TRUE.equals(isCroppedMap.get(pageIndex));
            if (!isCropped) {
                commitCrop(this::finishAndProceed);
            } else {
                finishAndProceed();
            }
        });

        binding.btnExtractText.setOnClickListener(v -> extractTextFromCurrentScan());

        setupLoupe();
    }

    // ── Undo State Management ────────────────────────────────────────────

    private void pushUndoState() {
        Deque<EditState> stack = undoStacks.computeIfAbsent(pageIndex, k -> new ArrayDeque<>());
        Point[] currentCorners = binding.cropOverlay.getCornerPoints();
        if (currentCorners == null) {
            currentCorners = savedCornersMap.get(pageIndex);
        }
        boolean isCropped = Boolean.TRUE.equals(isCroppedMap.get(pageIndex));
        Filter filter = pageFilterMap.getOrDefault(pageIndex, Filter.ORIGINAL);

        stack.push(new EditState(currentImagePath, currentCorners, isCropped, filter));
        if (stack.size() > 30) {
            stack.removeLast();
        }
        updateUndoButton();
    }

    private void updateUndoButton() {
        Deque<EditState> stack = undoStacks.get(pageIndex);
        boolean canUndo = stack != null && !stack.isEmpty();
        binding.btnUndo.setEnabled(canUndo);
        binding.btnUndo.setAlpha(canUndo ? 1.0f : 0.3f);
    }

    private void performUndo() {
        if (isBusy) return;
        Deque<EditState> stack = undoStacks.get(pageIndex);
        if (stack == null || stack.isEmpty()) return;

        isBusy = true;
        dismissLensOverlay();
        EditState previous = stack.pop();
        updateUndoButton();

        currentImagePath = previous.workingImagePath;
        pagePaths.set(pageIndex, currentImagePath);
        isCroppedMap.put(pageIndex, previous.isCropped);
        pageFilterMap.put(pageIndex, previous.filter);
        if (previous.savedCorners != null) {
            savedCornersMap.put(pageIndex, previous.savedCorners);
        }

        executor.execute(() -> {
            if (currentCroppedBitmap != null && !currentCroppedBitmap.isRecycled()) {
                currentCroppedBitmap.recycle();
                currentCroppedBitmap = null;
            }
            if (currentFilteredBitmap != null && !currentFilteredBitmap.isRecycled()) {
                currentFilteredBitmap.recycle();
                currentFilteredBitmap = null;
            }

            Bitmap displayBitmap;
            if (previous.isCropped) {
                currentCroppedBitmap = CacheManager.loadBitmap(currentImagePath);
                if (previous.filter != Filter.ORIGINAL && currentCroppedBitmap != null) {
                    currentFilteredBitmap = runFilterAlgorithm(previous.filter, currentCroppedBitmap);
                    displayBitmap = currentFilteredBitmap != null ? currentFilteredBitmap : currentCroppedBitmap;
                } else {
                    displayBitmap = currentCroppedBitmap;
                }
            } else {
                if (previous.filter != Filter.ORIGINAL && masterOriginalBitmap != null) {
                    currentFilteredBitmap = runFilterAlgorithm(previous.filter, masterOriginalBitmap);
                    displayBitmap = currentFilteredBitmap != null ? currentFilteredBitmap : masterOriginalBitmap;
                } else {
                    displayBitmap = masterOriginalBitmap;
                }
            }

            runOnUiThread(() -> {
                isBusy = false;
                binding.ivDocument.setImageBitmap(displayBitmap);
                if (previous.isCropped) {
                    binding.cropOverlay.setVisibility(View.GONE);
                } else {
                    binding.cropOverlay.setCorners(previous.savedCorners);
                    binding.cropOverlay.resetCornerMoved();
                    binding.cropOverlay.setVisibility(View.VISIBLE);
                }
                Toast.makeText(CropPreviewActivity.this, R.string.action_undo, Toast.LENGTH_SHORT).show();
            });
        });
    }

    // ── Crop & Re-Crop Mode Handling ─────────────────────────────────────

    private void onCropButtonClicked() {
        if (isBusy) return;
        boolean isCurrentlyCropped = Boolean.TRUE.equals(isCroppedMap.get(pageIndex));

        if (isCurrentlyCropped) {
            // ── RE-CROP MODE: Switch back to master original and restore previous edges ──
            pushUndoState();
            isBusy = true;
            dismissLensOverlay();

            executor.execute(() -> {
                if (masterOriginalBitmap == null || masterOriginalBitmap.isRecycled()) {
                    masterOriginalBitmap = CacheManager.loadBitmap(originalPagePaths.get(pageIndex));
                }

                Point[] corners = savedCornersMap.get(pageIndex);
                if (corners == null && masterOriginalBitmap != null) {
                    org.opencv.core.Point[] detected = DocumentDetector.getInstance(CropPreviewActivity.this)
                            .detectCorners(masterOriginalBitmap);
                    if (detected == null) {
                        detected = DocumentDetector.getDefaultCorners(
                                masterOriginalBitmap.getWidth(), masterOriginalBitmap.getHeight());
                    }
                    corners = toAndroidPoints(detected);
                    savedCornersMap.put(pageIndex, corners);
                }

                Filter activeFilter = pageFilterMap.getOrDefault(pageIndex, Filter.ORIGINAL);
                Bitmap displayBitmap;
                if (activeFilter != Filter.ORIGINAL && masterOriginalBitmap != null) {
                    if (currentFilteredBitmap != null && !currentFilteredBitmap.isRecycled()) {
                        currentFilteredBitmap.recycle();
                    }
                    currentFilteredBitmap = runFilterAlgorithm(activeFilter, masterOriginalBitmap);
                    displayBitmap = currentFilteredBitmap != null ? currentFilteredBitmap : masterOriginalBitmap;
                } else {
                    displayBitmap = masterOriginalBitmap;
                }

                final Point[] finalCorners = corners;
                runOnUiThread(() -> {
                    isBusy = false;
                    isCroppedMap.put(pageIndex, false);
                    binding.ivDocument.setImageBitmap(displayBitmap);
                    binding.cropOverlay.setCorners(finalCorners);
                    binding.cropOverlay.resetCornerMoved();
                    binding.cropOverlay.setVisibility(View.VISIBLE);
                    Toast.makeText(CropPreviewActivity.this, "Adjust edges & tap Crop to apply", Toast.LENGTH_SHORT).show();
                });
            });
        } else {
            // ── COMMIT CROP ───────────────────────────────────────────────
            commitCrop(null);
        }
    }

    /**
     * Commits the perspective warp using current corner points.
     * Always warps against masterOriginalBitmap to prevent progressive over-cropping.
     */
    private void commitCrop(@Nullable Runnable onComplete) {
        Point[] currentCorners = binding.cropOverlay.getCornerPoints();
        if (currentCorners == null || currentCorners.length != 4) {
            currentCorners = savedCornersMap.get(pageIndex);
        }
        if (currentCorners == null || currentCorners.length != 4 || masterOriginalBitmap == null) {
            if (onComplete != null) onComplete.run();
            return;
        }

        pushUndoState();
        isBusy = true;
        binding.cropOverlay.setVisibility(View.GONE);

        final Point[] cornersToApply = currentCorners;
        executor.execute(() -> {
            // ALWAYS WARP FROM THE MASTER ORIGINAL IMAGE
            org.opencv.core.Point[] opencvCorners = toOpenCvPoints(cornersToApply);
            Bitmap cropped = ImageProcessor.perspectiveWarp(masterOriginalBitmap, opencvCorners);

            String croppedPath = CacheManager.saveTempBitmap(this, cropped, UUID.randomUUID().toString());

            if (currentCroppedBitmap != null && !currentCroppedBitmap.isRecycled()) {
                currentCroppedBitmap.recycle();
            }
            currentCroppedBitmap = cropped;
            currentImagePath = croppedPath;

            pagePaths.set(pageIndex, croppedPath);
            savedCornersMap.put(pageIndex, cornersToApply);
            isCroppedMap.put(pageIndex, true);

            Filter activeFilter = pageFilterMap.getOrDefault(pageIndex, Filter.ORIGINAL);
            Bitmap displayBmp;
            if (activeFilter != Filter.ORIGINAL) {
                if (currentFilteredBitmap != null && !currentFilteredBitmap.isRecycled()) {
                    currentFilteredBitmap.recycle();
                }
                currentFilteredBitmap = runFilterAlgorithm(activeFilter, currentCroppedBitmap);
                displayBmp = currentFilteredBitmap != null ? currentFilteredBitmap : currentCroppedBitmap;
            } else {
                displayBmp = currentCroppedBitmap;
            }

            runOnUiThread(() -> {
                isBusy = false;
                binding.cropOverlay.resetCornerMoved();
                binding.cropOverlay.setVisibility(View.GONE);
                binding.ivDocument.setImageBitmap(displayBmp);
                Toast.makeText(CropPreviewActivity.this, "Crop applied", Toast.LENGTH_SHORT).show();

                if (onComplete != null) {
                    onComplete.run();
                }
            });
        });
    }

    // ── Filter Pipeline ──────────────────────────────────────────────────

    private void handleFilterClick(Filter filter) {
        if (isBusy) return;
        boolean isCropped = Boolean.TRUE.equals(isCroppedMap.get(pageIndex));
        if (!isCropped) {
            commitCrop(() -> applyFilter(filter));
        } else {
            applyFilter(filter);
        }
    }

    private Bitmap runFilterAlgorithm(Filter filter, Bitmap src) {
        if (src == null || src.isRecycled()) return null;
        switch (filter) {
            case MAGIC:
                return ImageProcessor.applyCLAHE(src);
            case BW:
                return ImageProcessor.applyOtsuBW(src);
            case GRAYSCALE:
                return ImageProcessor.applyGrayscale(src);
            case SHARPEN:
                return ImageProcessor.applySharpen(src);
            case SCAN_ENHANCE:
                return ImageProcessor.applyScanEnhance(src);
            default:
                return ImageProcessor.applyOriginalFilter(src);
        }
    }

    private void applyFilter(Filter filter) {
        if (filter == pageFilterMap.getOrDefault(pageIndex, Filter.ORIGINAL)) return;
        dismissLensOverlay();

        if (binding.cbApplyToAll.isChecked() && pagePaths != null && pagePaths.size() > 1) {
            applyFilterToAllPages(filter);
            return;
        }

        pushUndoState();
        pageFilterMap.put(pageIndex, filter);

        Bitmap base = currentCroppedBitmap != null ? currentCroppedBitmap : masterOriginalBitmap;
        if (base == null) return;

        if (filter == Filter.ORIGINAL) {
            if (currentFilteredBitmap != null && !currentFilteredBitmap.isRecycled()) {
                currentFilteredBitmap.recycle();
                currentFilteredBitmap = null;
            }
            binding.ivDocument.setImageBitmap(base);
            return;
        }

        isBusy = true;
        executor.execute(() -> {
            if (currentFilteredBitmap != null && !currentFilteredBitmap.isRecycled()) {
                currentFilteredBitmap.recycle();
                currentFilteredBitmap = null;
            }

            Bitmap filtered = runFilterAlgorithm(filter, base);
            currentFilteredBitmap = filtered;

            runOnUiThread(() -> {
                isBusy = false;
                if (currentFilteredBitmap != null && !currentFilteredBitmap.isRecycled()) {
                    binding.ivDocument.setImageBitmap(currentFilteredBitmap);
                }
            });
        });
    }

    private void applyFilterToAllPages(Filter filter) {
        pushUndoState();
        pageFilterMap.put(pageIndex, filter);
        isBusy = true;

        View progressView = getLayoutInflater().inflate(R.layout.dialog_progress, null);
        TextView tvProgressMessage = progressView.findViewById(R.id.tvProgressMessage);
        if (tvProgressMessage != null) {
            tvProgressMessage.setText("Applying filter to all pages…");
        }

        AlertDialog progressDialog = new MaterialAlertDialogBuilder(this)
                .setView(progressView)
                .setCancelable(false)
                .show();

        executor.execute(() -> {
            for (int i = 0; i < pagePaths.size(); i++) {
                pageFilterMap.put(i, filter);
                if (i == pageIndex) {
                    Bitmap base = currentCroppedBitmap != null ? currentCroppedBitmap : masterOriginalBitmap;
                    if (base != null) {
                        if (currentFilteredBitmap != null && !currentFilteredBitmap.isRecycled()) {
                            currentFilteredBitmap.recycle();
                            currentFilteredBitmap = null;
                        }
                        if (filter != Filter.ORIGINAL) {
                            Bitmap filtered = runFilterAlgorithm(filter, base);
                            currentFilteredBitmap = filtered;
                            String savedPath = CacheManager.saveTempBitmap(this, filtered, UUID.randomUUID().toString());
                            pagePaths.set(i, savedPath);
                            currentImagePath = savedPath;
                        }
                    }
                } else {
                    Bitmap otherBmp = CacheManager.loadBitmap(pagePaths.get(i));
                    if (otherBmp != null) {
                        if (filter != Filter.ORIGINAL) {
                            Bitmap filteredOther = runFilterAlgorithm(filter, otherBmp);
                            String savedPath = CacheManager.saveTempBitmap(this, filteredOther, UUID.randomUUID().toString());
                            pagePaths.set(i, savedPath);
                            filteredOther.recycle();
                        }
                        otherBmp.recycle();
                    }
                }
            }

            runOnUiThread(() -> {
                isBusy = false;
                if (!isFinishing() && !isDestroyed()) {
                    progressDialog.dismiss();
                }
                Bitmap base = currentCroppedBitmap != null ? currentCroppedBitmap : masterOriginalBitmap;
                Bitmap displayBmp = (currentFilteredBitmap != null && !currentFilteredBitmap.isRecycled())
                        ? currentFilteredBitmap : base;
                binding.ivDocument.setImageBitmap(displayBmp);
                Toast.makeText(this, "Filter applied to all " + pagePaths.size() + " pages", Toast.LENGTH_SHORT).show();
            });
        });
    }

    // ── Rotate ───────────────────────────────────────────────────────────

    private void doRotate() {
        if (masterOriginalBitmap == null) return;
        dismissLensOverlay();
        pushUndoState();
        isBusy = true;

        executor.execute(() -> {
            int oldW = masterOriginalBitmap.getWidth();
            int oldH = masterOriginalBitmap.getHeight();

            // Rotate master original bitmap
            Bitmap rotatedMaster = ImageProcessor.rotateBitmap(masterOriginalBitmap, 90);
            if (masterOriginalBitmap != null && !masterOriginalBitmap.isRecycled()) {
                masterOriginalBitmap.recycle();
            }
            masterOriginalBitmap = rotatedMaster;

            String newMasterPath = CacheManager.saveTempBitmap(this, masterOriginalBitmap, UUID.randomUUID().toString());
            originalPagePaths.set(pageIndex, newMasterPath);

            // Transform saved corner points to match rotated master
            Point[] oldCorners = savedCornersMap.get(pageIndex);
            if (oldCorners != null && oldCorners.length == 4) {
                Point[] newCorners = new Point[4];
                newCorners[0] = rotatePoint90(oldCorners[3], oldW, oldH); // BL -> TL
                newCorners[1] = rotatePoint90(oldCorners[0], oldW, oldH); // TL -> TR
                newCorners[2] = rotatePoint90(oldCorners[1], oldW, oldH); // TR -> BR
                newCorners[3] = rotatePoint90(oldCorners[2], oldW, oldH); // BR -> BL
                savedCornersMap.put(pageIndex, newCorners);
            }

            boolean isCropped = Boolean.TRUE.equals(isCroppedMap.get(pageIndex));
            Bitmap displayBitmap;
            Filter activeFilter = pageFilterMap.getOrDefault(pageIndex, Filter.ORIGINAL);

            if (isCropped && currentCroppedBitmap != null) {
                Bitmap rotatedCropped = ImageProcessor.rotateBitmap(currentCroppedBitmap, 90);
                if (currentCroppedBitmap != null && !currentCroppedBitmap.isRecycled()) {
                    currentCroppedBitmap.recycle();
                }
                currentCroppedBitmap = rotatedCropped;
                String newCroppedPath = CacheManager.saveTempBitmap(this, currentCroppedBitmap, UUID.randomUUID().toString());
                currentImagePath = newCroppedPath;
                pagePaths.set(pageIndex, currentImagePath);

                if (activeFilter != Filter.ORIGINAL) {
                    if (currentFilteredBitmap != null && !currentFilteredBitmap.isRecycled()) {
                        currentFilteredBitmap.recycle();
                    }
                    currentFilteredBitmap = runFilterAlgorithm(activeFilter, currentCroppedBitmap);
                    displayBitmap = currentFilteredBitmap != null ? currentFilteredBitmap : currentCroppedBitmap;
                } else {
                    displayBitmap = currentCroppedBitmap;
                }
            } else {
                currentImagePath = newMasterPath;
                pagePaths.set(pageIndex, currentImagePath);
                if (activeFilter != Filter.ORIGINAL) {
                    if (currentFilteredBitmap != null && !currentFilteredBitmap.isRecycled()) {
                        currentFilteredBitmap.recycle();
                    }
                    currentFilteredBitmap = runFilterAlgorithm(activeFilter, masterOriginalBitmap);
                    displayBitmap = currentFilteredBitmap != null ? currentFilteredBitmap : masterOriginalBitmap;
                } else {
                    displayBitmap = masterOriginalBitmap;
                }
            }

            runOnUiThread(() -> {
                isBusy = false;
                binding.ivDocument.setImageBitmap(displayBitmap);
                if (!isCropped) {
                    binding.cropOverlay.setCorners(savedCornersMap.get(pageIndex));
                    binding.cropOverlay.resetCornerMoved();
                    binding.cropOverlay.setVisibility(View.VISIBLE);
                } else {
                    binding.cropOverlay.setVisibility(View.GONE);
                }
            });
        });
    }

    private static Point rotatePoint90(Point p, int width, int height) {
        return new Point(height - 1 - p.y, p.x);
    }

    // ── Page Loading & Navigation ────────────────────────────────────────

    private void loadPage(int index, @Nullable double[] preDetectedCorners, @Nullable Runnable onLoaded) {
        isBusy = true;
        pageIndex = index;
        currentImagePath = pagePaths.get(pageIndex);
        binding.tvTitle.setText(getString(R.string.crop_title, pageIndex + 1, pagePaths.size()));
        updateNavigationButtons();
        updateUndoButton();

        executor.execute(() -> {
            if (masterOriginalBitmap != null && !masterOriginalBitmap.isRecycled()) {
                masterOriginalBitmap.recycle();
                masterOriginalBitmap = null;
            }
            if (currentCroppedBitmap != null && !currentCroppedBitmap.isRecycled()) {
                currentCroppedBitmap.recycle();
                currentCroppedBitmap = null;
            }
            if (currentFilteredBitmap != null && !currentFilteredBitmap.isRecycled()) {
                currentFilteredBitmap.recycle();
                currentFilteredBitmap = null;
            }

            // Load master pristine original bitmap
            masterOriginalBitmap = CacheManager.loadBitmap(originalPagePaths.get(pageIndex));

            boolean isCropped = Boolean.TRUE.equals(isCroppedMap.get(pageIndex));
            Filter activeFilter = pageFilterMap.getOrDefault(pageIndex, Filter.ORIGINAL);
            Bitmap displayBitmap;

            if (isCropped) {
                currentCroppedBitmap = CacheManager.loadBitmap(pagePaths.get(pageIndex));
                if (activeFilter != Filter.ORIGINAL && currentCroppedBitmap != null) {
                    currentFilteredBitmap = runFilterAlgorithm(activeFilter, currentCroppedBitmap);
                    displayBitmap = currentFilteredBitmap != null ? currentFilteredBitmap : currentCroppedBitmap;
                } else {
                    displayBitmap = currentCroppedBitmap;
                }
            } else {
                Point[] edges = savedCornersMap.get(pageIndex);
                if (edges == null) {
                    if (preDetectedCorners != null && preDetectedCorners.length == 8) {
                        edges = toAndroidPoints(new org.opencv.core.Point[]{
                                new org.opencv.core.Point(preDetectedCorners[0], preDetectedCorners[1]),
                                new org.opencv.core.Point(preDetectedCorners[2], preDetectedCorners[3]),
                                new org.opencv.core.Point(preDetectedCorners[4], preDetectedCorners[5]),
                                new org.opencv.core.Point(preDetectedCorners[6], preDetectedCorners[7])
                        });
                    } else if (masterOriginalBitmap != null) {
                        String currentPath = pagePaths.get(pageIndex);
                        if (currentPath != null && currentPath.contains("id_composite")) {
                            isCroppedMap.put(pageIndex, true);
                        } else {
                            org.opencv.core.Point[] opencvEdges = DocumentDetector.getInstance(CropPreviewActivity.this)
                                    .detectCorners(masterOriginalBitmap);
                            if (opencvEdges == null) {
                                opencvEdges = DocumentDetector.getDefaultCorners(
                                        masterOriginalBitmap.getWidth(), masterOriginalBitmap.getHeight());
                            }
                            edges = toAndroidPoints(opencvEdges);
                        }
                    }
                    savedCornersMap.put(pageIndex, edges);
                }

                if (activeFilter != Filter.ORIGINAL && masterOriginalBitmap != null) {
                    currentFilteredBitmap = runFilterAlgorithm(activeFilter, masterOriginalBitmap);
                    displayBitmap = currentFilteredBitmap != null ? currentFilteredBitmap : masterOriginalBitmap;
                } else {
                    displayBitmap = masterOriginalBitmap;
                }
            }

            final boolean finalIsCropped = isCropped;
            final Bitmap finalDisplayBitmap = displayBitmap;
            final Point[] finalEdges = savedCornersMap.get(pageIndex);

            runOnUiThread(() -> {
                isBusy = false;
                binding.ivDocument.setImageBitmap(finalDisplayBitmap);
                if (finalIsCropped) {
                    binding.cropOverlay.setVisibility(View.GONE);
                } else {
                    binding.cropOverlay.setCorners(finalEdges);
                    binding.cropOverlay.resetCornerMoved();
                    binding.cropOverlay.setVisibility(View.VISIBLE);
                }
                if (onLoaded != null) {
                    onLoaded.run();
                }
            });
        });
    }

    private void navigateToPage(int targetIndex) {
        if (targetIndex < 0 || targetIndex >= pagePaths.size() || targetIndex == pageIndex || isBusy) {
            return;
        }

        boolean isCropped = Boolean.TRUE.equals(isCroppedMap.get(pageIndex));
        if (!isCropped && binding.cropOverlay.isCornerMoved()) {
            commitCrop(() -> performPageSwitch(targetIndex));
        } else {
            performPageSwitch(targetIndex);
        }
    }

    private void performPageSwitch(int targetIndex) {
        dismissLensOverlay();
        boolean movingForward = targetIndex > pageIndex;
        float outX = movingForward ? -binding.frameCenter.getWidth() : binding.frameCenter.getWidth();
        float inX = movingForward ? binding.frameCenter.getWidth() : -binding.frameCenter.getWidth();

        binding.cropOverlay.setVisibility(View.INVISIBLE);
        binding.frameCenter.animate()
                .translationX(outX)
                .alpha(0.2f)
                .setDuration(160)
                .withEndAction(() -> {
                    binding.frameCenter.setTranslationX(inX);
                    loadPage(targetIndex, null, () -> {
                        runOnUiThread(() -> {
                            binding.frameCenter.animate()
                                    .translationX(0f)
                                    .alpha(1.0f)
                                    .setDuration(160)
                                    .start();
                        });
                    });
                }).start();
    }

    private void updateNavigationButtons() {
        if (pagePaths != null && pagePaths.size() > 1) {
            binding.btnPrevPage.setVisibility(View.VISIBLE);
            binding.btnNextPage.setVisibility(View.VISIBLE);

            boolean canGoPrev = pageIndex > 0;
            boolean canGoNext = pageIndex < pagePaths.size() - 1;

            binding.btnPrevPage.setEnabled(canGoPrev);
            binding.btnPrevPage.setAlpha(canGoPrev ? 1.0f : 0.35f);

            binding.btnNextPage.setEnabled(canGoNext);
            binding.btnNextPage.setAlpha(canGoNext ? 1.0f : 0.35f);

            binding.cbApplyToAll.setVisibility(View.VISIBLE);
        } else {
            binding.btnPrevPage.setVisibility(View.GONE);
            binding.btnNextPage.setVisibility(View.GONE);
            binding.cbApplyToAll.setVisibility(View.GONE);
        }
    }

    // ── Delete ───────────────────────────────────────────────────────────

    private void showDeleteConfirmation() {
        new AlertDialog.Builder(this)
            .setTitle(R.string.crop_action_delete)
            .setMessage(R.string.review_delete_page_confirm)
            .setPositiveButton(R.string.action_delete, (dialog, which) -> {
                if (pagePaths != null && pageIndex >= 0 && pageIndex < pagePaths.size()) {
                    pagePaths.remove(pageIndex);
                    if (pageIndex < originalPagePaths.size()) {
                        originalPagePaths.remove(pageIndex);
                    }
                    savedCornersMap.remove(pageIndex);
                    isCroppedMap.remove(pageIndex);
                    pageFilterMap.remove(pageIndex);
                    undoStacks.remove(pageIndex);

                    if (pagePaths.isEmpty()) {
                        handleExit();
                        return;
                    }
                    int newIndex = Math.min(pageIndex, pagePaths.size() - 1);
                    loadPage(newIndex, null, null);
                }
            })
            .setNegativeButton(R.string.action_cancel, null)
            .show();
    }

    // ── Finish and Proceed ───────────────────────────────────────────────

    private void finishAndProceed() {
        if (isAddingPage) {
            Intent resultIntent = new Intent();
            resultIntent.putExtra(CameraActivity.EXTRA_CROPPED_PATH, currentImagePath);
            setResult(RESULT_OK, resultIntent);
            finish();
        } else if (isFromReview) {
            Intent resultIntent = new Intent();
            resultIntent.putStringArrayListExtra(EXTRA_PAGE_PATHS, pagePaths);
            resultIntent.putStringArrayListExtra(EXTRA_ORIGINAL_PAGE_PATHS, originalPagePaths);
            setResult(RESULT_OK, resultIntent);
            finish();
        } else {
            Intent reviewIntent = new Intent(CropPreviewActivity.this, ReviewScanActivity.class);
            reviewIntent.putStringArrayListExtra(EXTRA_PAGE_PATHS, pagePaths);
            reviewIntent.putStringArrayListExtra(EXTRA_ORIGINAL_PAGE_PATHS, originalPagePaths);
            startActivity(reviewIntent);
            finish();
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

    // ── Lens Overlay & Loupe Magnifier ───────────────────────────────────

    private void dismissLensOverlay() {
        if (binding.lensOverlay.getVisibility() == View.VISIBLE) {
            binding.lensOverlay.setVisibility(View.GONE);
            binding.lensOverlay.clear();
            boolean isCropped = Boolean.TRUE.equals(isCroppedMap.get(pageIndex));
            if (!isCropped) {
                binding.cropOverlay.setVisibility(View.VISIBLE);
            }
        }
    }

    private void extractTextFromCurrentScan() {
        if (binding.lensOverlay.getVisibility() == View.VISIBLE) {
            dismissLensOverlay();
            return;
        }

        Bitmap activeBitmap = (currentFilteredBitmap != null && !currentFilteredBitmap.isRecycled())
                ? currentFilteredBitmap
                : (currentCroppedBitmap != null && !currentCroppedBitmap.isRecycled()
                ? currentCroppedBitmap : masterOriginalBitmap);

        if (activeBitmap == null || activeBitmap.isRecycled()) {
            Toast.makeText(this, R.string.ocr_error, Toast.LENGTH_SHORT).show();
            return;
        }

        View progressView = getLayoutInflater().inflate(R.layout.dialog_progress, null);
        TextView tvProgressMessage = progressView.findViewById(R.id.tvProgressMessage);
        if (tvProgressMessage != null) {
            tvProgressMessage.setText(R.string.ocr_extracting);
        }

        AlertDialog progressDialog = new MaterialAlertDialogBuilder(this)
                .setView(progressView)
                .setCancelable(false)
                .show();

        OcrHelper.extractText(activeBitmap, this, new OcrHelper.OcrCallback() {
            @Override
            public void onSuccess(Text visionText) {
                if (!isFinishing() && !isDestroyed()) {
                    progressDialog.dismiss();
                    if (visionText == null || visionText.getTextBlocks().isEmpty()) {
                        Toast.makeText(CropPreviewActivity.this, R.string.ocr_empty, Toast.LENGTH_SHORT).show();
                        return;
                    }

                    binding.cropOverlay.setVisibility(View.INVISIBLE);
                    binding.lensOverlay.setTargetRect(null);
                    binding.lensOverlay.setVisionText(visionText, activeBitmap.getWidth(), activeBitmap.getHeight());
                    binding.lensOverlay.setVisibility(View.VISIBLE);
                    Toast.makeText(CropPreviewActivity.this, R.string.ocr_lens_hint, Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onError(Exception e) {
                if (!isFinishing() && !isDestroyed()) {
                    progressDialog.dismiss();
                    Toast.makeText(CropPreviewActivity.this, R.string.ocr_error, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void setupLoupe() {
        binding.cropOverlay.setOnCornerDragListener(new CropOverlayView.OnCornerDragListener() {
            @Override
            public void onCornerDragStarted(int cornerIndex, float x, float y) {
                float containerWidth = (float) binding.frameCenter.getWidth();
                float loupeSize = getResources().getDimension(R.dimen.loupe_size);
                float margin = getResources().getDisplayMetrics().density * 16f;

                boolean isTouchOnLeft = x < containerWidth / 2f;
                currentLoupeOnRight = isTouchOnLeft;
                float targetX = isTouchOnLeft ? (containerWidth - loupeSize - margin) : margin;
                float targetY = margin;

                binding.cardLoupe.setTranslationX(targetX);
                binding.cardLoupe.setTranslationY(targetY);

                binding.cardLoupe.setAlpha(0f);
                binding.cardLoupe.setVisibility(View.VISIBLE);
                updateLoupe(x, y);
                binding.cardLoupe.animate().alpha(1f).setDuration(150).start();
            }

            @Override
            public void onCornerDragging(int cornerIndex, float x, float y) {
                float containerWidth = (float) binding.frameCenter.getWidth();
                float loupeSize = getResources().getDimension(R.dimen.loupe_size);
                float margin = getResources().getDisplayMetrics().density * 16f;

                boolean isTouchOnLeft = x < containerWidth / 2f;
                if (isTouchOnLeft != currentLoupeOnRight) {
                    currentLoupeOnRight = isTouchOnLeft;
                    float targetX = isTouchOnLeft ? (containerWidth - loupeSize - margin) : margin;
                    binding.cardLoupe.animate().translationX(targetX).setDuration(150).start();
                }

                updateLoupe(x, y);
            }

            @Override
            public void onCornerDragEnded() {
                binding.cardLoupe.animate().alpha(0f).setDuration(150).withEndAction(() -> {
                    binding.cardLoupe.setVisibility(View.GONE);
                }).start();
            }
        });
    }

    private void updateLoupe(float viewX, float viewY) {
        Bitmap activeBitmap = (currentFilteredBitmap != null && !currentFilteredBitmap.isRecycled())
                ? currentFilteredBitmap
                : (currentCroppedBitmap != null && !currentCroppedBitmap.isRecycled()
                ? currentCroppedBitmap : masterOriginalBitmap);
        if (activeBitmap == null || activeBitmap.isRecycled()) return;

        if (binding.ivLoupe.getDrawable() == null || binding.ivLoupe.getTag() != activeBitmap) {
            binding.ivLoupe.setImageBitmap(activeBitmap);
            binding.ivLoupe.setTag(activeBitmap);
        }

        PointF bmpPt = binding.cropOverlay.getImageCoordinates(viewX, viewY);

        Matrix docMatrix = binding.cropOverlay.getImageViewToOverlayMatrix();
        float[] docValues = new float[9];
        docMatrix.getValues(docValues);
        float screenScale = (float) Math.hypot(docValues[Matrix.MSCALE_X], docValues[Matrix.MSKEW_Y]);
        if (screenScale <= 0f) screenScale = 1.0f;
        float zoomScale = screenScale * 2.2f;

        float loupeHalfWidth = binding.ivLoupe.getWidth() > 0
                ? (float) binding.ivLoupe.getWidth() / 2f
                : (float) getResources().getDimension(R.dimen.loupe_size) / 2f;
        float loupeHalfHeight = binding.ivLoupe.getHeight() > 0
                ? (float) binding.ivLoupe.getHeight() / 2f
                : (float) getResources().getDimension(R.dimen.loupe_size) / 2f;

        Matrix loupeMatrix = new Matrix();
        loupeMatrix.postTranslate(-bmpPt.x, -bmpPt.y);
        loupeMatrix.postScale(zoomScale, zoomScale);
        loupeMatrix.postTranslate(loupeHalfWidth, loupeHalfHeight);

        binding.ivLoupe.setImageMatrix(loupeMatrix);
        binding.ivLoupe.invalidate();
    }

    // ── Coordinate conversion helpers ────────────────────────────────────

    private Point[] toAndroidPoints(org.opencv.core.Point[] opencvPoints) {
        if (opencvPoints == null) return null;
        Point[] pts = new Point[opencvPoints.length];
        for (int i = 0; i < opencvPoints.length; i++) {
            pts[i] = new Point((int) opencvPoints[i].x, (int) opencvPoints[i].y);
        }
        return pts;
    }

    private org.opencv.core.Point[] toOpenCvPoints(Point[] androidPoints) {
        if (androidPoints == null) return null;
        org.opencv.core.Point[] pts = new org.opencv.core.Point[androidPoints.length];
        for (int i = 0; i < androidPoints.length; i++) {
            pts[i] = new org.opencv.core.Point(androidPoints[i].x, androidPoints[i].y);
        }
        return pts;
    }

    // ── Lifecycle & Memory Cleanup ───────────────────────────────────────

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (binding != null && binding.ivLoupe != null) {
            binding.ivLoupe.setImageBitmap(null);
        }
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }
        if (currentFilteredBitmap != null && !currentFilteredBitmap.isRecycled()) {
            currentFilteredBitmap.recycle();
            currentFilteredBitmap = null;
        }
        if (currentCroppedBitmap != null && !currentCroppedBitmap.isRecycled()) {
            currentCroppedBitmap.recycle();
            currentCroppedBitmap = null;
        }
        if (masterOriginalBitmap != null && !masterOriginalBitmap.isRecycled()) {
            masterOriginalBitmap.recycle();
            masterOriginalBitmap = null;
        }
    }
}
