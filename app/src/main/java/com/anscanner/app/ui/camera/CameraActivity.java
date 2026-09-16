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
import com.anscanner.app.processing.ImageProcessor;
import com.anscanner.app.service.CacheManager;
import com.anscanner.app.service.CrashManager;
import com.anscanner.app.service.PdfGenerator;
import com.anscanner.app.service.StorageHelper;
import com.anscanner.app.ui.crop.CropPreviewActivity;
import com.anscanner.app.ui.library.LibraryActivity;
import com.anscanner.app.ui.review.ReviewScanActivity;
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
                    showCompressionLevelDialog(uri);
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

        try {
            // Extract Y-plane bytes (grayscale)
            ImageProxy.PlaneProxy yPlane = imageProxy.getPlanes()[0];
            ByteBuffer yBuffer = yPlane.getBuffer();
            int rowStride = yPlane.getRowStride();
            int width = imageProxy.getWidth();
            int height = imageProxy.getHeight();

            byte[] yData = new byte[yBuffer.remaining()];
            yBuffer.get(yData);

            // Set analysis dimensions on the overlay for coordinate mapping
            binding.overlayView.setAnalysisDimensions(width, height);

            // Run OpenCV edge detection on the grayscale frame
            Point[] corners = ImageProcessor.detectDocumentEdgesFromFrame(
                    yData, width, height, rowStride);

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

        // Snapshot the currently detected corners before capture
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

                                // Map analysis-space corners to captured-image-space corners
                                Point[] mappedCorners = null;
                                if (captureCorners != null) {
                                    mappedCorners = binding.overlayView.getCornersForCapture(
                                            bitmap.getWidth(), bitmap.getHeight());
                                }

                                String path = CacheManager.saveTempBitmap(
                                        CameraActivity.this, bitmap, UUID.randomUUID().toString());
                                bitmap.recycle();

                                launchCropPreview(path, mappedCorners);
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
