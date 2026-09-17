package com.anscanner.app.ui.camera;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Size;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;

import com.anscanner.app.R;
import com.anscanner.app.data.AppDatabase;
import com.anscanner.app.data.entity.DocumentEntity;
import com.anscanner.app.databinding.ActivityCameraBinding;
import com.anscanner.app.processing.DocumentDetector;
import com.anscanner.app.processing.ImageProcessor;
import com.anscanner.app.service.CacheManager;
import com.anscanner.app.service.CrashManager;
import com.anscanner.app.service.PdfGenerator;
import com.anscanner.app.service.StorageHelper;
import com.anscanner.app.ui.crop.CropPreviewActivity;
import com.anscanner.app.ui.library.LibraryActivity;
import com.anscanner.app.ui.review.ReviewScanActivity;
import com.anscanner.app.ui.save.CompressPdfBottomSheet;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.MobileAds;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.common.util.concurrent.ListenableFuture;

import org.opencv.core.Point;

import java.io.File;
import java.io.IOException;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class CameraActivity extends AppCompatActivity {
    private static final String TAG = "CameraActivity";

    public static final String EXTRA_IMAGE_PATH = "extra_image_path";
    public static final String EXTRA_IS_ADDING_PAGE = "extra_is_adding_page";
    public static final String EXTRA_CROPPED_PATH = "extra_cropped_path";
    public static final String EXTRA_DETECTED_CORNERS = "extra_detected_corners";

    private ActivityCameraBinding binding;
    private ImageCapture imageCapture;
    private ImageAnalysis imageAnalysis;
    private boolean isFlashOn = false;
    private Camera camera;
    private ExecutorService bgExecutor;
    private ExecutorService analysisExecutor;
    private RecentScansAdapter recentAdapter;
    private boolean isAddingPage = false;

    // Scan Modes
    public static final int SCAN_MODE_DOCUMENT = 0;
    public static final int SCAN_MODE_BATCH = 1;
    public static final int SCAN_MODE_ID_CARD = 2;
    private int currentScanMode = SCAN_MODE_DOCUMENT;

    // Batch Mode state
    private final ArrayList<String> batchPagePaths = new ArrayList<>();
    private BatchThumbAdapter batchAdapter;

    // Smart ID Card Mode state
    private int idCardStep = DocumentOverlayView.ID_STEP_FRONT;
    private String idFrontTempPath = null;

    /**
     * Guard to prevent multiple analysis frames from piling up.
     * Only one frame is processed at a time; others are dropped.
     */
    private final AtomicBoolean isAnalyzing = new AtomicBoolean(false);

    /**
     * The most recently detected document corners (in analysis-image space).
     * Stored so they can be passed to CropPreviewActivity on capture.
     */
    private volatile Point[] lastDetectedCorners = null;

    // ── Permission launcher ──────────────────────────────────────────────

    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    startCamera();
                } else {
                    Toast.makeText(this, R.string.camera_permission_denied, Toast.LENGTH_SHORT).show();
                    finish();
                }
            });

    // ── Gallery picker launcher (Multi-Image Selection) ──────────────────

    private final ActivityResultLauncher<PickVisualMediaRequest> pickMediaLauncher =
            registerForActivityResult(new ActivityResultContracts.PickMultipleVisualMedia(20), uris -> {
                if (uris != null && !uris.isEmpty()) {
                    handleGalleryImport(uris);
                }
            });

    // ── CropPreview launcher (used ONLY in add-page mode) ────────────────
    //    Relays the result from CropPreviewActivity back to
    //    ReviewScanActivity's addPageLauncher.

    private final ActivityResultLauncher<Intent> cropForResultLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    // Relay the cropped path straight back to ReviewScanActivity
                    setResult(RESULT_OK, result.getData());
                } else {
                    setResult(RESULT_CANCELED);
                }
                finish();
            });

    // ── External PDF Compression launcher ─────────────────────────────────

    private final ActivityResultLauncher<String> pickPdfForCompressLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    CompressPdfBottomSheet sheet = CompressPdfBottomSheet.newInstance(uri);
                    sheet.show(getSupportFragmentManager(), "CompressPdfBottomSheet");
                }
            });

    // ── Lifecycle ────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // First-launch check: redirect to onboarding if app hasn't completed onboarding yet
        android.content.SharedPreferences prefs = getSharedPreferences(
                com.anscanner.app.ui.onboarding.OnboardingActivity.PREF_NAME, MODE_PRIVATE);
        if (prefs.getBoolean(com.anscanner.app.ui.onboarding.OnboardingActivity.KEY_IS_FIRST_LAUNCH, true)) {
            startActivity(new Intent(this, com.anscanner.app.ui.onboarding.OnboardingActivity.class));
            finish();
            return;
        }

        binding = ActivityCameraBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        bgExecutor = Executors.newSingleThreadExecutor();
        analysisExecutor = Executors.newSingleThreadExecutor();
        isAddingPage = getIntent().getBooleanExtra(EXTRA_IS_ADDING_PAGE, false);

        // Check and prompt to upload crash log if a previous crash occurred
        CrashManager.checkAndPromptCrashLog(this);

        MobileAds.initialize(this, initializationStatus -> {});
        AdRequest adRequest = new AdRequest.Builder().build();
        binding.adView.loadAd(adRequest);

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA);
        }

        setupUI();
    }

    private void setupUI() {
        binding.btnFlash.setOnClickListener(v -> toggleFlash());
        binding.btnScan.setOnClickListener(v -> takePhoto());
        binding.btnGallery.setOnClickListener(v -> openGalleryPicker());
        binding.btnCompressPdf.setOnClickListener(v -> {
            pickPdfForCompressLauncher.launch("application/pdf");
        });
        binding.tvSeeAll.setOnClickListener(v ->
                startActivity(new Intent(this, LibraryActivity.class)));

        // Recent Scans adapter
        recentAdapter = new RecentScansAdapter(entity -> {
            if ("PDF".equalsIgnoreCase(entity.format)) {
                Intent intent = new Intent(this, com.anscanner.app.ui.pdf.PdfViewerActivity.class);
                if (entity.fileUri != null) {
                    intent.putExtra(com.anscanner.app.ui.pdf.PdfViewerActivity.EXTRA_PDF_URI, entity.fileUri);
                }
                intent.putExtra(com.anscanner.app.ui.pdf.PdfViewerActivity.EXTRA_DOCUMENT_TITLE, entity.title);
                startActivity(intent);
            }
        });
        binding.rvRecentScans.setAdapter(recentAdapter);

        // Batch Mode thumbnail adapter
        batchAdapter = new BatchThumbAdapter(position -> {
            if (!batchPagePaths.isEmpty()) {
                openReviewWithBatchPages();
            }
        });
        binding.rvBatchThumbs.setAdapter(batchAdapter);
        binding.btnBatchDone.setOnClickListener(v -> openReviewWithBatchPages());

        // ID Card mode controls
        binding.btnIdCardRetakeFront.setOnClickListener(v -> resetIdCardToStep1());

        // Mode selector listener
        binding.toggleMode.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            if (checkedId == R.id.btnModeDocument) {
                switchScanMode(SCAN_MODE_DOCUMENT);
            } else if (checkedId == R.id.btnModeBatch) {
                switchScanMode(SCAN_MODE_BATCH);
            } else if (checkedId == R.id.btnModeIdCard) {
                switchScanMode(SCAN_MODE_ID_CARD);
            }
        });
    }

    private void switchScanMode(int newMode) {
        currentScanMode = newMode;
        binding.overlayView.setScanMode(newMode);

        if (newMode == SCAN_MODE_DOCUMENT) {
            binding.layoutRecentScans.setVisibility(View.VISIBLE);
            binding.layoutBatchStrip.setVisibility(View.GONE);
            binding.layoutIdCardStep.setVisibility(View.GONE);
            binding.tvFramingHint.setText(R.string.camera_framing_hint);
        } else if (newMode == SCAN_MODE_BATCH) {
            binding.layoutRecentScans.setVisibility(View.GONE);
            binding.layoutBatchStrip.setVisibility(View.VISIBLE);
            binding.layoutIdCardStep.setVisibility(View.GONE);
            binding.tvFramingHint.setText(R.string.batch_mode_hint);
            updateBatchUI();
        } else if (newMode == SCAN_MODE_ID_CARD) {
            lastDetectedCorners = null;
            binding.overlayView.setDetectedCorners(null);
            binding.layoutRecentScans.setVisibility(View.GONE);
            binding.layoutBatchStrip.setVisibility(View.GONE);
            binding.layoutIdCardStep.setVisibility(View.VISIBLE);
            resetIdCardToStep1();
        }
    }

    private void updateBatchUI() {
        int count = batchPagePaths.size();
        binding.tvBatchCounter.setText(getString(R.string.batch_pages_counter, count));
        binding.btnBatchDone.setText(getString(R.string.batch_action_review, count));
        binding.btnBatchDone.setEnabled(count > 0);
        binding.btnBatchDone.setAlpha(count > 0 ? 1.0f : 0.5f);
    }

    private void openReviewWithBatchPages() {
        if (batchPagePaths.isEmpty()) {
            Toast.makeText(this, "Capture at least one page first", Toast.LENGTH_SHORT).show();
            return;
        }

        if (isAddingPage) {
            Intent resultIntent = new Intent();
            resultIntent.putStringArrayListExtra(ReviewScanActivity.EXTRA_PAGE_PATHS, new ArrayList<>(batchPagePaths));
            setResult(RESULT_OK, resultIntent);
            finish();
        } else {
            Intent reviewIntent = new Intent(this, ReviewScanActivity.class);
            reviewIntent.putStringArrayListExtra(ReviewScanActivity.EXTRA_PAGE_PATHS, new ArrayList<>(batchPagePaths));
            startActivity(reviewIntent);
        }
    }

    private void routeIdComposite(String compositePath) {
        if (isAddingPage) {
            Intent resultIntent = new Intent();
            resultIntent.putExtra(EXTRA_CROPPED_PATH, compositePath);
            ArrayList<String> pages = new ArrayList<>();
            pages.add(compositePath);
            resultIntent.putStringArrayListExtra(ReviewScanActivity.EXTRA_PAGE_PATHS, pages);
            setResult(RESULT_OK, resultIntent);
            finish();
        } else {
            // Direct to ReviewScanActivity with the clean A4 ID composite
            Intent reviewIntent = new Intent(CameraActivity.this, ReviewScanActivity.class);
            ArrayList<String> pages = new ArrayList<>();
            pages.add(compositePath);
            reviewIntent.putStringArrayListExtra(ReviewScanActivity.EXTRA_PAGE_PATHS, pages);
            reviewIntent.putStringArrayListExtra(ReviewScanActivity.EXTRA_ORIGINAL_PAGE_PATHS, pages);
            startActivity(reviewIntent);
        }
    }

    private void resetIdCardToStep1() {
        idCardStep = DocumentOverlayView.ID_STEP_FRONT;
        idFrontTempPath = null;
        binding.overlayView.setIdCardStep(idCardStep);
        binding.tvIdCardStepTitle.setText(R.string.id_card_step1_title);
        binding.tvIdCardStepDesc.setText(R.string.id_card_step1_hint);
        binding.tvFramingHint.setText(R.string.id_card_step1_hint);
        binding.btnIdCardRetakeFront.setVisibility(View.GONE);
    }

    @Override
    protected void onPause() {
        if (binding != null && binding.adView != null) {
            binding.adView.pause();
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (binding != null && binding.adView != null) {
            binding.adView.resume();
        }
        if (bgExecutor != null && recentAdapter != null) {
            loadRecentScans();
        }
    }

    private void loadRecentScans() {
        bgExecutor.execute(() -> {
            List<DocumentEntity> recent =
                    AppDatabase.getInstance(this).documentDao().getRecentDocuments(5);
            new Handler(Looper.getMainLooper()).post(() ->
                    recentAdapter.submitList(recent));
        });
    }

    // ── CameraX setup ────────────────────────────────────────────────────

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);

        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();

                // 1) Preview
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(binding.previewView.getSurfaceProvider());

                // 2) ImageCapture (full resolution for final scan)
                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .build();

                // 3) ImageAnalysis (downscaled for real-time edge detection)
                imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(640, 480))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                        .build();

                imageAnalysis.setAnalyzer(analysisExecutor, this::analyzeFrame);

                CameraSelector selector = CameraSelector.DEFAULT_BACK_CAMERA;

                provider.unbindAll();
                camera = provider.bindToLifecycle(
                        this, selector, preview, imageCapture, imageAnalysis);

            } catch (ExecutionException | InterruptedException e) {
                Toast.makeText(this, R.string.camera_init_error, Toast.LENGTH_SHORT).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // ── Real-time Frame Analysis ─────────────────────────────────────────

    /**
     * Analyzes each camera frame for document edges using OpenCV.
     *
     * <p><b>Memory contract:</b> The ImageProxy is ALWAYS closed in a
     * finally block. Only one frame is processed at a time — if analysis
     * is already running, the frame is dropped immediately.</p>
     *
     * <p>Extracts the Y-plane (grayscale) from YUV_420_888, passes it
     * to {@link ImageProcessor#detectDocumentEdgesFromFrame}, then maps
     * the resulting corners onto the {@link DocumentOverlayView}.</p>
     */
    private void analyzeFrame(@NonNull ImageProxy imageProxy) {
        // Drop frame if we're still processing the previous one
        if (!isAnalyzing.compareAndSet(false, true)) {
            imageProxy.close();
            return;
        }

        // Bypass edge detection completely when in ID Card mode
        if (currentScanMode == SCAN_MODE_ID_CARD) {
            imageProxy.close();
            isAnalyzing.set(false);
            return;
        }

        try {
            int rotation = imageProxy.getImageInfo().getRotationDegrees();
            Bitmap rawBitmap = imageProxy.toBitmap();
            Bitmap frameBitmap = rawBitmap;
            if (rawBitmap != null && rotation != 0) {
                frameBitmap = ImageProcessor.rotateBitmap(rawBitmap, rotation);
                rawBitmap.recycle();
            }

            if (frameBitmap != null) {
                // Set upright analysis dimensions on the overlay for coordinate mapping
                binding.overlayView.setAnalysisDimensions(frameBitmap.getWidth(), frameBitmap.getHeight());
            }

            // Run corner detection on the upright frame bitmap
            Point[] corners = null;
            if (frameBitmap != null) {
                try {
                    corners = DocumentDetector.getInstance(CameraActivity.this).detectCorners(frameBitmap);
                } finally {
                    frameBitmap.recycle();
                }
            }

            // Store the detected corners for use during capture
            lastDetectedCorners = corners;

            // Update the overlay on the UI thread
            binding.overlayView.setDetectedCorners(corners);

        } catch (Exception e) {
            // Silently ignore analysis errors to avoid crashing the preview
        } finally {
            imageProxy.close();
            isAnalyzing.set(false);
        }
    }

    private void toggleFlash() {
        if (camera != null && camera.getCameraInfo().hasFlashUnit()) {
            isFlashOn = !isFlashOn;
            camera.getCameraControl().enableTorch(isFlashOn);
            binding.btnFlash.setImageResource(
                    isFlashOn ? R.drawable.ic_flash_on : R.drawable.ic_flash_off);
        }
    }

    // ── Capture ──────────────────────────────────────────────────────────

    private void takePhoto() {
        if (imageCapture == null) return;

        // Snapshot current mode and detected corners before capture
        final int mode = currentScanMode;
        final Point[] captureCorners = lastDetectedCorners;

        imageCapture.takePicture(ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageCapturedCallback() {
                    @Override
                    public void onCaptureSuccess(@NonNull ImageProxy imageProxy) {
                        bgExecutor.execute(() -> {
                            try {
                                Bitmap bitmap = imageProxy.toBitmap();
                                int rotation = imageProxy.getImageInfo().getRotationDegrees();
                                if (rotation != 0) {
                                    Bitmap rotated = ImageProcessor.rotateBitmap(bitmap, rotation);
                                    bitmap.recycle();
                                    bitmap = rotated;
                                }

                                if (mode == SCAN_MODE_BATCH) {
                                    // Batch Mode: Save and append, keep camera active
                                    String path = CacheManager.saveTempBitmap(
                                            CameraActivity.this, bitmap, "batch_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString());
                                    bitmap.recycle();

                                    runOnUiThread(() -> {
                                        if (path != null) {
                                            batchPagePaths.add(path);
                                            batchAdapter.addPage(path);
                                            binding.rvBatchThumbs.smoothScrollToPosition(batchPagePaths.size() - 1);
                                            updateBatchUI();

                                            // Quick shutter flash effect
                                            binding.previewView.animate().alpha(0.35f).setDuration(40)
                                                    .withEndAction(() -> binding.previewView.animate().alpha(1.0f).setDuration(80).start())
                                                    .start();
                                            Toast.makeText(CameraActivity.this,
                                                    getString(R.string.batch_pages_counter, batchPagePaths.size()) + " captured",
                                                    Toast.LENGTH_SHORT).show();
                                        }
                                    });

                                } else if (mode == SCAN_MODE_ID_CARD) {
                                    // ID Card Mode: Crop directly to ID card guide frame (no Canny edge detection)
                                    android.graphics.Rect cropRect = binding.overlayView.getIdCardCropRect(
                                            bitmap.getWidth(), bitmap.getHeight());
                                    Bitmap cardBitmap = ImageProcessor.cropIdCardFrame(bitmap, cropRect);
                                    bitmap.recycle();

                                    if (idCardStep == DocumentOverlayView.ID_STEP_FRONT) {
                                        // Step 1: Front captured
                                        String frontPath = CacheManager.saveTempBitmap(
                                                CameraActivity.this, cardBitmap, "id_front_" + System.currentTimeMillis());
                                        cardBitmap.recycle();
                                        idFrontTempPath = frontPath;

                                        runOnUiThread(() -> {
                                            idCardStep = DocumentOverlayView.ID_STEP_BACK;
                                            binding.overlayView.setIdCardStep(idCardStep);
                                            binding.tvIdCardStepTitle.setText(R.string.id_card_step2_title);
                                            binding.tvIdCardStepDesc.setText(R.string.id_card_step2_hint);
                                            binding.tvFramingHint.setText(R.string.id_card_step2_hint);
                                            binding.btnIdCardRetakeFront.setVisibility(View.VISIBLE);
                                            Toast.makeText(CameraActivity.this, R.string.id_card_front_captured, Toast.LENGTH_SHORT).show();
                                        });

                                    } else {
                                        // Step 2: Back captured -> composite both sides onto A4
                                        String backPath = CacheManager.saveTempBitmap(
                                                CameraActivity.this, cardBitmap, "id_back_" + System.currentTimeMillis());
                                        cardBitmap.recycle();

                                        runOnUiThread(() ->
                                                Toast.makeText(CameraActivity.this, R.string.id_card_compositing, Toast.LENGTH_SHORT).show());

                                        Bitmap frontBmp = BitmapFactory.decodeFile(idFrontTempPath);
                                        Bitmap backBmp = BitmapFactory.decodeFile(backPath);
                                        Bitmap composite = ImageProcessor.compositeIdCard(frontBmp, backBmp);

                                        String compositePath = CacheManager.saveTempBitmap(
                                                CameraActivity.this, composite, "id_composite_" + System.currentTimeMillis());
                                        composite.recycle();

                                        runOnUiThread(() -> {
                                            resetIdCardToStep1();
                                            routeIdComposite(compositePath);
                                        });
                                    }

                                } else {
                                    // Default Document Mode (Single Page)
                                    Point[] mappedCorners = null;
                                    if (captureCorners != null) {
                                        mappedCorners = binding.overlayView.getCornersForCapture(
                                                bitmap.getWidth(), bitmap.getHeight());
                                    }

                                    String path = CacheManager.saveTempBitmap(
                                            CameraActivity.this, bitmap, UUID.randomUUID().toString());
                                    bitmap.recycle();

                                    launchCropPreview(path, mappedCorners);
                                }

                            } finally {
                                imageProxy.close();
                            }
                        });
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException exception) {
                        Toast.makeText(CameraActivity.this,
                                R.string.camera_capture_error, Toast.LENGTH_SHORT).show();
                    }
                });
    }

    // ── Gallery Import (Photo Picker API) ────────────────────────────────

    private void openGalleryPicker() {
        pickMediaLauncher.launch(
                new PickVisualMediaRequest.Builder()
                        .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                        .build());
    }

    /**
     * Copies the selected gallery images to temp cache files on a background
     * thread. If multiple images are selected, routes directly to ReviewScanActivity.
     * If a single image is selected, routes to CropPreviewActivity.
     */
    private void handleGalleryImport(@NonNull List<Uri> uris) {
        bgExecutor.execute(() -> {
            ArrayList<String> savedPaths = new ArrayList<>();
            long timestamp = System.currentTimeMillis();

            for (int i = 0; i < uris.size(); i++) {
                Uri uri = uris.get(i);
                try {
                    InputStream inputStream = getContentResolver().openInputStream(uri);
                    if (inputStream == null) continue;

                    Bitmap bitmap = BitmapFactory.decodeStream(inputStream);
                    inputStream.close();

                    if (bitmap == null) continue;

                    String fileName = "scan_" + timestamp + "_" + i;
                    String cachedPath = CacheManager.saveTempBitmap(this, bitmap, fileName);
                    bitmap.recycle();

                    if (cachedPath != null) {
                        savedPaths.add(cachedPath);
                    }
                } catch (Exception e) {
                    // Skip failed image
                }
            }

            if (savedPaths.isEmpty()) {
                showGalleryError();
                return;
            }

            new Handler(Looper.getMainLooper()).post(() -> {
                if (savedPaths.size() == 1) {
                    // Single image: route to CropPreviewActivity
                    launchCropPreview(savedPaths.get(0), null);
                } else {
                    // Multiple images: route directly to ReviewScanActivity
                    if (isAddingPage) {
                        Intent resultIntent = new Intent();
                        resultIntent.putStringArrayListExtra(ReviewScanActivity.EXTRA_PAGE_PATHS, savedPaths);
                        setResult(RESULT_OK, resultIntent);
                        finish();
                    } else {
                        Intent reviewIntent = new Intent(CameraActivity.this, ReviewScanActivity.class);
                        reviewIntent.putStringArrayListExtra(ReviewScanActivity.EXTRA_PAGE_PATHS, savedPaths);
                        startActivity(reviewIntent);
                    }
                }
            });
        });
    }

    private void showGalleryError() {
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(this, R.string.camera_gallery_error, Toast.LENGTH_SHORT).show());
    }

    // ── Navigation ───────────────────────────────────────────────────────

    /**
     * Launches CropPreviewActivity with the given image path and optional
     * pre-detected document corners.
     *
     * <p><b>KEY FIX:</b> In add-page mode we use {@link #cropForResultLauncher}
     * (startActivityForResult) so that CropPreviewActivity's
     * {@code setResult(RESULT_OK, …)} propagates the cropped path back
     * through this Activity to ReviewScanActivity's addPageLauncher.</p>
     *
     * <p>In normal (new-session) mode we simply use {@code startActivity()}
     * because CropPreview will launch ReviewScanActivity directly.</p>
     */
    private void launchCropPreview(String path, Point[] corners) {
        new Handler(Looper.getMainLooper()).post(() -> {
            Intent intent = new Intent(CameraActivity.this, CropPreviewActivity.class);
            intent.putExtra(EXTRA_IMAGE_PATH, path);
            intent.putExtra(EXTRA_IS_ADDING_PAGE, isAddingPage);

            // Pass detected corners as a double array [x0,y0,x1,y1,...,x3,y3]
            if (corners != null && corners.length == 4) {
                double[] cornerArray = new double[8];
                for (int i = 0; i < 4; i++) {
                    cornerArray[i * 2] = corners[i].x;
                    cornerArray[i * 2 + 1] = corners[i].y;
                }
                intent.putExtra(EXTRA_DETECTED_CORNERS, cornerArray);
            }

            if (isAddingPage) {
                // Launch for result — CropPreview will setResult() back,
                // cropForResultLauncher relays it to ReviewScanActivity
                cropForResultLauncher.launch(intent);
            } else {
                // Normal flow — CropPreview handles navigation itself
                startActivity(intent);
            }
        });
    }

    // ── Standalone External PDF Compression ───────────────────────────────

    private void showCompressionLevelDialog(Uri uri) {
        final String[] levels = new String[] {
                getString(R.string.compression_high),
                getString(R.string.compression_medium),
                getString(R.string.compression_low)
        };
        final int[] qualities = new int[] { 100, 60, 30 };
        final int[] selectedQuality = new int[] { 60 }; // Default: Medium (60%)

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.compression_quality_title)
                .setSingleChoiceItems(levels, 1, (dialog, which) -> {
                    selectedQuality[0] = qualities[which];
                })
                .setPositiveButton(R.string.compress_action_compress, (dialog, which) -> {
                    compressExternalPdf(uri, selectedQuality[0]);
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void compressExternalPdf(Uri sourceUri, int quality) {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_progress, null);
        TextView tvMessage = dialogView.findViewById(R.id.tvProgressMessage);
        tvMessage.setText(R.string.compressing_pdf);

        AlertDialog progressDialog = new MaterialAlertDialogBuilder(this)
                .setView(dialogView)
                .setCancelable(false)
                .create();
        progressDialog.show();

        bgExecutor.execute(() -> {
            try {
                long timestamp = System.currentTimeMillis();
                String originalFileName = StorageHelper.getFileName(getContentResolver(), sourceUri);
                if (originalFileName == null || originalFileName.trim().isEmpty()) {
                    originalFileName = "Doc_" + timestamp;
                }
                if (originalFileName.toLowerCase().endsWith(".pdf")) {
                    originalFileName = originalFileName.substring(0, originalFileName.length() - 4);
                }
                String compressedFileName = originalFileName + "_compressed";

                File tempOutputFile = new File(getCacheDir(), "compressed_" + timestamp + ".pdf");

                // Core compression engine
                long compressedSize = PdfGenerator.compressPdf(this, sourceUri, tempOutputFile, quality);
                int pageCount = PdfGenerator.getPdfPageCount(this, sourceUri);

                // Save new compressed PDF to public Scoped Storage via MediaStore
                Uri savedUri = StorageHelper.savePdfToPublicStorage(this, tempOutputFile, compressedFileName + ".pdf");
                tempOutputFile.delete();

                if (savedUri != null) {
                    // Generate permanent thumbnail for Recent Scans row
                    String thumbPath = PdfGenerator.generateThumbnailFromPdf(this, savedUri, timestamp);
                    String qualityLabel = quality == 100 ? "High (100%)" : (quality == 60 ? "Medium (60%)" : "Low (30%)");

                    DocumentEntity entity = new DocumentEntity(
                            compressedFileName,
                            "PDF",
                            qualityLabel,
                            pageCount,
                            compressedSize,
                            savedUri.toString(),
                            thumbPath,
                            timestamp
                    );
                    AppDatabase.getInstance(this).documentDao().insertDocument(entity);

                    runOnUiThread(() -> {
                        if (progressDialog.isShowing()) {
                            progressDialog.dismiss();
                        }
                        Toast.makeText(this, R.string.compression_complete, Toast.LENGTH_SHORT).show();
                        loadRecentScans();
                    });
                } else {
                    throw new IOException("Failed to export compressed PDF to storage");
                }

            } catch (Exception e) {
                Log.e(TAG, "External PDF compression failed", e);
                runOnUiThread(() -> {
                    if (progressDialog.isShowing()) {
                        progressDialog.dismiss();
                    }
                    Toast.makeText(this, R.string.compress_error, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (binding != null && binding.adView != null) {
            binding.adView.destroy();
        }
        if (bgExecutor != null && !bgExecutor.isShutdown()) {
            bgExecutor.shutdown();
        }
        if (analysisExecutor != null && !analysisExecutor.isShutdown()) {
            analysisExecutor.shutdown();
        }
        super.onDestroy();
        binding = null;
    }
}
