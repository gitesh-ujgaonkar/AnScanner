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

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.anscanner.app.R;
import com.anscanner.app.databinding.ActivityCropPreviewBinding;
import com.anscanner.app.processing.ImageProcessor;
import com.anscanner.app.service.CacheManager;
import com.anscanner.app.service.OcrHelper;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.anscanner.app.ui.camera.CameraActivity;
import com.anscanner.app.ui.review.ReviewScanActivity;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CropPreviewActivity extends AppCompatActivity {
    public static final String EXTRA_IMAGE_PATH = "extra_image_path";
    public static final String EXTRA_PAGE_PATHS = "extra_page_paths";
    public static final String EXTRA_PAGE_INDEX = "extra_page_index";
    public static final String EXTRA_IS_ADDING_PAGE = "extra_is_adding_page";

    private ActivityCropPreviewBinding binding;
    private String currentImagePath;

    // ── Pristine source vs disposable preview filter management ──────────
    private Bitmap sourceBitmap;           // The pristine original bitmap
    private Bitmap currentFilteredBitmap;  // The disposable preview bitmap

    private ArrayList<String> pagePaths;
    private int pageIndex;
    private boolean isAddingPage = false;
    private boolean currentLoupeOnRight = false;

    private ExecutorService executor;
    private enum Filter { ORIGINAL, MAGIC, BW, GRAYSCALE, SHARPEN, SCAN_ENHANCE }
    private Filter currentFilter = Filter.ORIGINAL;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityCropPreviewBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        executor = Executors.newSingleThreadExecutor();

        Intent intent = getIntent();
        currentImagePath = intent.getStringExtra(EXTRA_IMAGE_PATH);
        isAddingPage = intent.getBooleanExtra(EXTRA_IS_ADDING_PAGE, false);

        if (intent.hasExtra(EXTRA_PAGE_PATHS)) {
            pagePaths = intent.getStringArrayListExtra(EXTRA_PAGE_PATHS);
        } else {
            pagePaths = new ArrayList<>();
            pagePaths.add(currentImagePath);
        }
        pageIndex = intent.getIntExtra(EXTRA_PAGE_INDEX, 0);

        binding.tvTitle.setText(getString(R.string.crop_title, pageIndex + 1, pagePaths.size()));

        if (isAddingPage) {
            binding.btnSaveDocument.setText(R.string.crop_confirm_next);
        }

        // Extract pre-detected corners from CameraActivity (if available)
        double[] cornerArray = intent.getDoubleArrayExtra(
                CameraActivity.EXTRA_DETECTED_CORNERS);

        setupUI();
        loadImageAndDetectEdges(cornerArray);
    }

    private void setupUI() {
        binding.btnClose.setOnClickListener(v -> finish());
        binding.cropOverlay.setImageView(binding.ivDocument);

        // The manual "Crop" button re-detects edges on the pristine source bitmap
        binding.btnCrop.setOnClickListener(v -> redetectEdges());

        binding.btnFilter.setOnClickListener(v -> {
            boolean isVisible = binding.filterContainer.getVisibility() == View.VISIBLE;
            binding.filterContainer.setVisibility(isVisible ? View.GONE : View.VISIBLE);
        });

        binding.btnFilterOriginal.setOnClickListener(v -> applyFilter(Filter.ORIGINAL));
        binding.btnFilterMagic.setOnClickListener(v -> applyFilter(Filter.MAGIC));
        binding.btnFilterBW.setOnClickListener(v -> applyFilter(Filter.BW));
        binding.btnFilterGrayscale.setOnClickListener(v -> applyFilter(Filter.GRAYSCALE));
        binding.btnFilterSharpen.setOnClickListener(v -> applyFilter(Filter.SHARPEN));
        binding.btnFilterScanEnhance.setOnClickListener(v -> applyFilter(Filter.SCAN_ENHANCE));

        binding.btnRotate.setOnClickListener(v -> doRotate());
        binding.btnDelete.setOnClickListener(v -> showDeleteConfirmation());
        binding.btnSaveDocument.setOnClickListener(v -> confirmAndProceed());
        binding.btnExtractText.setOnClickListener(v -> extractTextFromCurrentScan());

        setupLoupe();
    }

    private void extractTextFromCurrentScan() {
        Bitmap activeBitmap = (currentFilteredBitmap != null && !currentFilteredBitmap.isRecycled())
                ? currentFilteredBitmap : sourceBitmap;

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
            public void onSuccess(String extractedText) {
                if (!isFinishing() && !isDestroyed()) {
                    progressDialog.dismiss();
                    OcrHelper.showExtractedTextDialog(CropPreviewActivity.this, extractedText);
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
                float containerWidth = binding.frameCenter.getWidth();
                float loupeSize = getResources().getDimension(R.dimen.loupe_size);
                float margin = getResources().getDisplayMetrics().density * 16;

                boolean isTouchOnLeft = x < containerWidth / 2f;
                currentLoupeOnRight = isTouchOnLeft;
                float targetX = isTouchOnLeft ? (containerWidth - loupeSize - margin) : margin;
                float targetY = margin;

                binding.cardLoupe.setTranslationX(targetX);
                binding.cardLoupe.setTranslationY(targetY);
                updateLoupe(x, y);

                binding.cardLoupe.setAlpha(0f);
                binding.cardLoupe.setVisibility(View.VISIBLE);
                binding.cardLoupe.animate().alpha(1f).setDuration(150).start();
            }

            @Override
            public void onCornerDragging(int cornerIndex, float x, float y) {
                float containerWidth = binding.frameCenter.getWidth();
                float loupeSize = getResources().getDimension(R.dimen.loupe_size);
                float margin = getResources().getDisplayMetrics().density * 16;

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
                ? currentFilteredBitmap : sourceBitmap;
        if (activeBitmap == null || activeBitmap.isRecycled()) return;

        if (binding.ivLoupe.getDrawable() == null || binding.ivLoupe.getTag() != activeBitmap) {
            binding.ivLoupe.setImageBitmap(activeBitmap);
            binding.ivLoupe.setTag(activeBitmap);
        }

        PointF bmpPt = binding.cropOverlay.getImageCoordinates(viewX, viewY);

        Matrix docMatrix = binding.ivDocument.getImageMatrix();
        float[] docValues = new float[9];
        docMatrix.getValues(docValues);
        float screenScale = docValues[Matrix.MSCALE_X];
        if (screenScale <= 0f) screenScale = 1.0f;
        float zoomScale = screenScale * 2.2f;

        float loupeHalfWidth = binding.cardLoupe.getWidth() / 2f;
        float loupeHalfHeight = binding.cardLoupe.getHeight() / 2f;
        if (loupeHalfWidth <= 0f) {
            float loupeSize = getResources().getDimension(R.dimen.loupe_size);
            loupeHalfWidth = loupeSize / 2f;
            loupeHalfHeight = loupeSize / 2f;
        }

        Matrix loupeMatrix = new Matrix();
        loupeMatrix.postTranslate(-bmpPt.x, -bmpPt.y);
        loupeMatrix.postScale(zoomScale, zoomScale);
        loupeMatrix.postTranslate(loupeHalfWidth, loupeHalfHeight);

        binding.ivLoupe.setImageMatrix(loupeMatrix);
        binding.ivLoupe.invalidate();
    }

    /**
     * Loads the initial image into sourceBitmap and auto-detects document edges.
     * The sourceBitmap is kept pristine in memory and not recycled until activity destruction.
     */
    private void loadImageAndDetectEdges(double[] preDetectedCorners) {
        executor.execute(() -> {
            Bitmap bmp = CacheManager.loadBitmap(currentImagePath);
            if (bmp != null) {
                if (sourceBitmap != null && !sourceBitmap.isRecycled()) {
                    sourceBitmap.recycle();
                }
                sourceBitmap = bmp;

                if (currentFilteredBitmap != null && !currentFilteredBitmap.isRecycled()) {
                    currentFilteredBitmap.recycle();
                    currentFilteredBitmap = null;
                }

                Point[] edges;
                if (preDetectedCorners != null && preDetectedCorners.length == 8) {
                    edges = toAndroidPoints(new org.opencv.core.Point[]{
                            new org.opencv.core.Point(preDetectedCorners[0], preDetectedCorners[1]),
                            new org.opencv.core.Point(preDetectedCorners[2], preDetectedCorners[3]),
                            new org.opencv.core.Point(preDetectedCorners[4], preDetectedCorners[5]),
                            new org.opencv.core.Point(preDetectedCorners[6], preDetectedCorners[7])
                    });
                } else {
                    org.opencv.core.Point[] opencvEdges = ImageProcessor.detectDocumentEdges(sourceBitmap);
                    edges = toAndroidPoints(opencvEdges);
                }

                new Handler(Looper.getMainLooper()).post(() -> {
                    binding.ivDocument.setImageBitmap(sourceBitmap);
                    binding.cropOverlay.setCorners(edges);
                    binding.cropOverlay.setVisibility(View.VISIBLE);
                });
            }
        });
    }

    // ── Safe Filter Pipeline ─────────────────────────────────────────────

    /**
     * Safe filter execution:
     * - Revert to Original: recycles currentFilteredBitmap, sets it to null, restores sourceBitmap.
     * - New Filter: recycles existing currentFilteredBitmap, applies OpenCV filter to the pristine
     *   sourceBitmap, assigns result to currentFilteredBitmap, and sets to ImageView.
     */
    private void applyFilter(Filter filter) {
        if (sourceBitmap == null || filter == currentFilter) return;
        currentFilter = filter;

        if (filter == Filter.ORIGINAL) {
            if (currentFilteredBitmap != null && !currentFilteredBitmap.isRecycled()) {
                currentFilteredBitmap.recycle();
                currentFilteredBitmap = null;
            }
            binding.ivDocument.setImageBitmap(sourceBitmap);
            return;
        }

        executor.execute(() -> {
            if (currentFilteredBitmap != null && !currentFilteredBitmap.isRecycled()) {
                currentFilteredBitmap.recycle();
                currentFilteredBitmap = null;
            }

            Bitmap filtered;
            switch (filter) {
                case MAGIC:
                    filtered = ImageProcessor.applyCLAHE(sourceBitmap);
                    break;
                case BW:
                    filtered = ImageProcessor.applyOtsuBW(sourceBitmap);
                    break;
                case GRAYSCALE:
                    filtered = ImageProcessor.applyGrayscale(sourceBitmap);
                    break;
                case SHARPEN:
                    filtered = ImageProcessor.applySharpen(sourceBitmap);
                    break;
                case SCAN_ENHANCE:
                    filtered = ImageProcessor.applyScanEnhance(sourceBitmap);
                    break;
                default:
                    filtered = ImageProcessor.applyOriginalFilter(sourceBitmap);
                    break;
            }

            currentFilteredBitmap = filtered;

            runOnUiThread(() -> {
                if (currentFilteredBitmap != null && !currentFilteredBitmap.isRecycled()) {
                    binding.ivDocument.setImageBitmap(currentFilteredBitmap);
                }
            });
        });
    }

    // ── Streamlined Auto-Crop Confirm ────────────────────────────────────

    private void confirmAndProceed() {
        Bitmap activeBitmap = (currentFilteredBitmap != null && !currentFilteredBitmap.isRecycled())
                ? currentFilteredBitmap : sourceBitmap;
        if (activeBitmap == null) return;

        binding.btnSaveDocument.setEnabled(false);
        binding.btnSaveDocument.setText(R.string.crop_processing);

        Point[] androidCorners = binding.cropOverlay.getCornerPoints();

        executor.execute(() -> {
            String croppedPath;

            if (androidCorners != null) {
                org.opencv.core.Point[] corners = toOpenCvPoints(androidCorners);
                Bitmap cropped = ImageProcessor.perspectiveWarp(activeBitmap, corners);

                croppedPath = CacheManager.saveTempBitmap(this, cropped, UUID.randomUUID().toString());
                cropped.recycle();
            } else {
                if (currentFilteredBitmap != null && !currentFilteredBitmap.isRecycled()) {
                    croppedPath = CacheManager.saveTempBitmap(this, currentFilteredBitmap, UUID.randomUUID().toString());
                } else {
                    croppedPath = currentImagePath;
                }
            }

            if (pageIndex < pagePaths.size()) {
                pagePaths.set(pageIndex, croppedPath);
            }

            final String finalPath = croppedPath;

            new Handler(Looper.getMainLooper()).post(() -> {
                if (isAddingPage) {
                    Intent resultIntent = new Intent();
                    resultIntent.putExtra(CameraActivity.EXTRA_CROPPED_PATH, finalPath);
                    setResult(RESULT_OK, resultIntent);
                    finish();
                } else {
                    Intent reviewIntent = new Intent(CropPreviewActivity.this, ReviewScanActivity.class);
                    reviewIntent.putStringArrayListExtra(EXTRA_PAGE_PATHS, pagePaths);
                    startActivity(reviewIntent);
                    finish();
                }
            });
        });
    }

    // ── Edge Re-detection ────────────────────────────────────────────────

    private void redetectEdges() {
        if (sourceBitmap == null) return;

        executor.execute(() -> {
            org.opencv.core.Point[] opencvEdges = ImageProcessor.detectDocumentEdges(sourceBitmap);
            Point[] edges = toAndroidPoints(opencvEdges);

            new Handler(Looper.getMainLooper()).post(() -> {
                binding.cropOverlay.setCorners(edges);
                binding.cropOverlay.setVisibility(View.VISIBLE);
            });
        });
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

    // ── Rotate ───────────────────────────────────────────────────────────

    private void doRotate() {
        if (sourceBitmap == null) return;

        executor.execute(() -> {
            Bitmap rotated = ImageProcessor.rotateBitmap(sourceBitmap, 90);
            if (sourceBitmap != null && !sourceBitmap.isRecycled()) {
                sourceBitmap.recycle();
            }
            sourceBitmap = rotated;

            if (currentFilteredBitmap != null && !currentFilteredBitmap.isRecycled()) {
                currentFilteredBitmap.recycle();
                currentFilteredBitmap = null;
            }

            Bitmap displayBitmap;
            if (currentFilter != Filter.ORIGINAL) {
                switch (currentFilter) {
                    case MAGIC:
                        currentFilteredBitmap = ImageProcessor.applyCLAHE(sourceBitmap);
                        break;
                    case BW:
                        currentFilteredBitmap = ImageProcessor.applyOtsuBW(sourceBitmap);
                        break;
                    case GRAYSCALE:
                        currentFilteredBitmap = ImageProcessor.applyGrayscale(sourceBitmap);
                        break;
                    case SHARPEN:
                        currentFilteredBitmap = ImageProcessor.applySharpen(sourceBitmap);
                        break;
                    case SCAN_ENHANCE:
                        currentFilteredBitmap = ImageProcessor.applyScanEnhance(sourceBitmap);
                        break;
                    default:
                        break;
                }
                displayBitmap = currentFilteredBitmap != null ? currentFilteredBitmap : sourceBitmap;
            } else {
                displayBitmap = sourceBitmap;
            }

            org.opencv.core.Point[] opencvEdges = ImageProcessor.detectDocumentEdges(sourceBitmap);
            Point[] edges = toAndroidPoints(opencvEdges);

            new Handler(Looper.getMainLooper()).post(() -> {
                binding.ivDocument.setImageBitmap(displayBitmap);
                binding.cropOverlay.setCorners(edges);
                binding.cropOverlay.setVisibility(View.VISIBLE);
            });
        });
    }

    // ── Delete ───────────────────────────────────────────────────────────

    private void showDeleteConfirmation() {
        new AlertDialog.Builder(this)
            .setTitle(R.string.crop_action_delete)
            .setMessage(R.string.review_delete_page_confirm)
            .setPositiveButton(R.string.action_delete, (dialog, which) -> {
                pagePaths.remove(pageIndex);
                finish();
            })
            .setNegativeButton(R.string.action_cancel, null)
            .show();
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
        if (sourceBitmap != null && !sourceBitmap.isRecycled()) {
            sourceBitmap.recycle();
            sourceBitmap = null;
        }
    }
}
