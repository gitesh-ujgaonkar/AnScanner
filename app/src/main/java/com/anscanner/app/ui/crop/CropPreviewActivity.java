package com.anscanner.app.ui.crop;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

import com.anscanner.app.databinding.ActivityCropPreviewBinding;
import com.anscanner.app.processing.ImageProcessor;
import com.anscanner.app.service.CacheManager;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CropPreviewActivity extends AppCompatActivity {
    public static final String EXTRA_IMAGE_PATH = "extra_image_path";
    public static final String EXTRA_PAGE_PATHS = "extra_page_paths";
    public static final String EXTRA_PAGE_INDEX = "extra_page_index";

    private ActivityCropPreviewBinding binding;
    private String currentImagePath;
    private Bitmap currentBitmap;
    private ArrayList<String> pagePaths;
    private int pageIndex;
    
    private ExecutorService executor;
    private enum Filter { ORIGINAL, MAGIC, BW }
    private Filter currentFilter = Filter.ORIGINAL;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityCropPreviewBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        executor = Executors.newSingleThreadExecutor();

        Intent intent = getIntent();
        currentImagePath = intent.getStringExtra(EXTRA_IMAGE_PATH);
        if (intent.hasExtra(EXTRA_PAGE_PATHS)) {
            pagePaths = intent.getStringArrayListExtra(EXTRA_PAGE_PATHS);
        } else {
            pagePaths = new ArrayList<>();
            pagePaths.add(currentImagePath);
        }
        pageIndex = intent.getIntExtra(EXTRA_PAGE_INDEX, 0);

        binding.tvTitle.setText("Page " + (pageIndex + 1) + " of " + pagePaths.size());

        setupUI();
        loadImageAndDetectEdges();
    }

    private void setupUI() {
        binding.btnClose.setOnClickListener(v -> finish());
        binding.cropOverlay.setImageView(binding.ivDocument);

        binding.btnCrop.setOnClickListener(v -> doCrop());
        binding.btnFilter.setOnClickListener(v -> {
            boolean isVisible = binding.filterContainer.getVisibility() == View.VISIBLE;
            binding.filterContainer.setVisibility(isVisible ? View.GONE : View.VISIBLE);
        });
        
        binding.btnFilterOriginal.setOnClickListener(v -> applyFilter(Filter.ORIGINAL));
        binding.btnFilterMagic.setOnClickListener(v -> applyFilter(Filter.MAGIC));
        binding.btnFilterBW.setOnClickListener(v -> applyFilter(Filter.BW));
        
        binding.btnRotate.setOnClickListener(v -> doRotate());
        binding.btnDelete.setOnClickListener(v -> showDeleteConfirmation());
        
        binding.btnSaveDocument.setOnClickListener(v -> {
            Intent intent = new Intent();
            intent.setClassName(this, "com.anscanner.app.ui.review.ReviewScanActivity");
            intent.putStringArrayListExtra(EXTRA_PAGE_PATHS, pagePaths);
            startActivity(intent);
        });
    }

    private void loadImageAndDetectEdges() {
        executor.execute(() -> {
            Bitmap bmp = CacheManager.loadBitmap(currentImagePath);
            if (bmp != null) {
                if (currentBitmap != null && !currentBitmap.isRecycled()) {
                    currentBitmap.recycle();
                }
                currentBitmap = bmp;
                org.opencv.core.Point[] opencvEdges = ImageProcessor.detectDocumentEdges(currentBitmap);
                Point[] edges = toAndroidPoints(opencvEdges);
                
                new Handler(Looper.getMainLooper()).post(() -> {
                    binding.ivDocument.setImageBitmap(currentBitmap);
                    binding.cropOverlay.setCorners(edges);
                    binding.cropOverlay.setVisibility(View.VISIBLE);
                });
            }
        });
    }

    private void doCrop() {
        if (currentBitmap == null) return;
        Point[] androidCorners = binding.cropOverlay.getCornerPoints();
        if (androidCorners == null) return;
        org.opencv.core.Point[] corners = toOpenCvPoints(androidCorners);

        executor.execute(() -> {
            Bitmap cropped = ImageProcessor.perspectiveWarp(currentBitmap, corners);
            if (currentBitmap != null && !currentBitmap.isRecycled()) {
                currentBitmap.recycle();
            }
            currentBitmap = cropped;
            String newPath = CacheManager.saveTempBitmap(this, currentBitmap, UUID.randomUUID().toString());
            currentImagePath = newPath;
            pagePaths.set(pageIndex, newPath);

            new Handler(Looper.getMainLooper()).post(() -> {
                binding.ivDocument.setImageBitmap(currentBitmap);
                binding.cropOverlay.setVisibility(View.GONE);
            });
        });
    }

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

    private void applyFilter(Filter filter) {
        if (currentBitmap == null || filter == currentFilter) return;
        currentFilter = filter;
        
        executor.execute(() -> {
            Bitmap filtered;
            switch (filter) {
                case MAGIC:
                    filtered = ImageProcessor.applyCLAHE(currentBitmap);
                    break;
                case BW:
                    filtered = ImageProcessor.applyOtsuBW(currentBitmap);
                    break;
                case ORIGINAL:
                default:
                    filtered = ImageProcessor.applyOriginalFilter(currentBitmap);
                    break;
            }
            
            if (currentBitmap != null && !currentBitmap.isRecycled()) {
                currentBitmap.recycle();
            }
            currentBitmap = filtered;
            String newPath = CacheManager.saveTempBitmap(this, currentBitmap, UUID.randomUUID().toString());
            currentImagePath = newPath;
            pagePaths.set(pageIndex, newPath);

            new Handler(Looper.getMainLooper()).post(() -> {
                binding.ivDocument.setImageBitmap(currentBitmap);
            });
        });
    }

    private void doRotate() {
        if (currentBitmap == null) return;
        
        executor.execute(() -> {
            Bitmap rotated = ImageProcessor.rotateBitmap(currentBitmap, 90);
            if (currentBitmap != null && !currentBitmap.isRecycled()) {
                currentBitmap.recycle();
            }
            currentBitmap = rotated;
            String newPath = CacheManager.saveTempBitmap(this, currentBitmap, UUID.randomUUID().toString());
            currentImagePath = newPath;
            pagePaths.set(pageIndex, newPath);

            new Handler(Looper.getMainLooper()).post(() -> {
                binding.ivDocument.setImageBitmap(currentBitmap);
                // Hide crop overlay on rotate for simplicity, could recalculate corners instead
                binding.cropOverlay.setVisibility(View.GONE);
            });
        });
    }

    private void showDeleteConfirmation() {
        new AlertDialog.Builder(this)
            .setTitle("Delete Page")
            .setMessage("Are you sure you want to delete this page?")
            .setPositiveButton("Delete", (dialog, which) -> {
                pagePaths.remove(pageIndex);
                if (pagePaths.isEmpty()) {
                    finish();
                } else {
                    // Logic to show another page, simplified to finish for now
                    finish();
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }
        if (currentBitmap != null && !currentBitmap.isRecycled()) {
            currentBitmap.recycle();
        }
    }
}
