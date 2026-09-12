package com.anscanner.app.ui.camera;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
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
import com.anscanner.app.ui.crop.CropPreviewActivity;
import com.anscanner.app.ui.library.LibraryActivity;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CameraActivity extends AppCompatActivity {
    public static final String EXTRA_IMAGE_PATH = "extra_image_path";
    
    private ActivityCameraBinding binding;
    private ImageCapture imageCapture;
    private boolean isFlashOn = false;
    private Camera camera;
    private ExecutorService dbExecutor;
    private RecentScansAdapter recentAdapter;

    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    startCamera();
                } else {
                    Toast.makeText(this, "Camera permission required.", Toast.LENGTH_SHORT).show();
                    finish();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityCameraBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        dbExecutor = Executors.newSingleThreadExecutor();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA);
        }

        setupUI();
    }

    private void setupUI() {
        binding.btnFlash.setOnClickListener(v -> toggleFlash());
        binding.btnScan.setOnClickListener(v -> takePhoto());
        binding.tvSeeAll.setOnClickListener(v -> {
            startActivity(new Intent(this, LibraryActivity.class));
        });

        recentAdapter = new RecentScansAdapter(entity -> {
            // Handle clicking recent scan
        });
        binding.rvRecentScans.setAdapter(recentAdapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadRecentScans();
    }

    private void loadRecentScans() {
        dbExecutor.execute(() -> {
            List<DocumentEntity> recent = AppDatabase.getInstance(this).documentDao().getRecentDocuments(5);
            new Handler(Looper.getMainLooper()).post(() -> {
                recentAdapter.submitList(recent);
            });
        });
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(binding.previewView.getSurfaceProvider());

                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .build();

                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

                cameraProvider.unbindAll();
                camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture);

            } catch (ExecutionException | InterruptedException e) {
                // Log exception
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void toggleFlash() {
        if (camera != null && camera.getCameraInfo().hasFlashUnit()) {
            isFlashOn = !isFlashOn;
            camera.getCameraControl().enableTorch(isFlashOn);
            binding.btnFlash.setImageResource(isFlashOn ? R.drawable.ic_flash_on : R.drawable.ic_flash_off);
        }
    }

    private void takePhoto() {
        if (imageCapture == null) return;

        imageCapture.takePicture(ContextCompat.getMainExecutor(this), new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(@NonNull ImageProxy imageProxy) {
                dbExecutor.execute(() -> {
                    try {
                        Bitmap bitmap = imageProxy.toBitmap();
                        int rotation = imageProxy.getImageInfo().getRotationDegrees();
                        if (rotation != 0) {
                            Bitmap rotated = ImageProcessor.rotateBitmap(bitmap, rotation);
                            bitmap.recycle();
                            bitmap = rotated;
                        }

                        String path = CacheManager.saveTempBitmap(CameraActivity.this, bitmap, UUID.randomUUID().toString());
                        bitmap.recycle();

                        new Handler(Looper.getMainLooper()).post(() -> {
                            Intent intent = new Intent(CameraActivity.this, CropPreviewActivity.class);
                            intent.putExtra(EXTRA_IMAGE_PATH, path);
                            startActivity(intent);
                        });
                    } finally {
                        imageProxy.close();
                    }
                });
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                // Log exception
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (dbExecutor != null && !dbExecutor.isShutdown()) {
            dbExecutor.shutdown();
        }
    }
}
