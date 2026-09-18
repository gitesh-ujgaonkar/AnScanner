package com.anscanner.app.ui.save;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.SeekBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.anscanner.app.R;
import com.anscanner.app.data.AppDatabase;
import com.anscanner.app.data.entity.DocumentEntity;
import com.anscanner.app.databinding.BottomSheetCompressPdfBinding;
import com.anscanner.app.service.PdfGenerator;
import com.anscanner.app.service.StorageHelper;
import com.anscanner.app.ui.pdf.PdfViewerActivity;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Bottom sheet dialog allowing users to compress an external PDF to a specific target size
 * using an iterative binary search loop, and auto-open it upon completion.
 */
public class CompressPdfBottomSheet extends BottomSheetDialogFragment {

    private static final String TAG = "CompressPdfBottomSheet";
    private static final String ARG_PDF_URI = "arg_pdf_uri";

    private BottomSheetCompressPdfBinding binding;
    private Uri pdfUri;
    private long originalSizeBytes = 0;
    private int pageCount = 1;
    private String originalFileName = "Document.pdf";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static CompressPdfBottomSheet newInstance(Uri pdfUri) {
        CompressPdfBottomSheet fragment = new CompressPdfBottomSheet();
        Bundle args = new Bundle();
        args.putParcelable(ARG_PDF_URI, pdfUri);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public int getTheme() {
        return R.style.Theme_AnScanner_BottomSheet;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = BottomSheetCompressPdfBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        if (getArguments() != null) {
            pdfUri = getArguments().getParcelable(ARG_PDF_URI);
        }

        if (pdfUri == null) {
            Toast.makeText(requireContext(), R.string.compress_error, Toast.LENGTH_SHORT).show();
            dismiss();
            return;
        }

        binding.btnClose.setOnClickListener(v -> dismiss());

        inspectSourcePdf();
        setupSliderAndPresets();
        binding.btnCompressOpen.setOnClickListener(v -> startTargetCompression());
    }

    private String defaultCompressedName = "";

    private void inspectSourcePdf() {
        Context context = requireContext();
        String name = StorageHelper.getFileName(context.getContentResolver(), pdfUri);
        if (name != null && !name.trim().isEmpty()) {
            originalFileName = name;
        }

        try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(pdfUri, "r")) {
            if (pfd != null) {
                originalSizeBytes = pfd.getStatSize();
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not read stat size for URI", e);
        }

        if (originalSizeBytes <= 0) {
            originalSizeBytes = 1024 * 1024; // 1 MB default fallback
        }

        pageCount = PdfGenerator.getPdfPageCount(context, pdfUri);

        binding.tvOriginalFileName.setText(originalFileName);
        String sizeFormatted = StorageHelper.formatFileSize(originalSizeBytes);
        String pageSuffix = pageCount == 1 ? "1 page" : pageCount + " pages";
        binding.tvOriginalSize.setText(getString(R.string.compress_original_size_format, sizeFormatted) + " · " + pageSuffix);

        // Pre-fill editable file name field: originalFileNameWithoutExt + "_compressed"
        String baseName = originalFileName;
        if (baseName.toLowerCase().endsWith(".pdf")) {
            baseName = baseName.substring(0, baseName.length() - 4);
        }
        defaultCompressedName = baseName + "_compressed";
        binding.etFileName.setText(defaultCompressedName);

        // Check if PDF is already highly optimized (<= pageCount * 60 KB)
        boolean isOptimized = originalSizeBytes <= ((long) pageCount * 60 * 1024L);
        binding.layoutOptimizedNotice.setVisibility(isOptimized ? View.VISIBLE : View.GONE);
    }

    private void setupSliderAndPresets() {
        binding.sbTargetSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateTargetSizeLabels(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        binding.btnPresetLow.setOnClickListener(v -> binding.sbTargetSize.setProgress(25));
        binding.btnPresetMed.setOnClickListener(v -> binding.sbTargetSize.setProgress(60));
        binding.btnPresetHigh.setOnClickListener(v -> binding.sbTargetSize.setProgress(90));

        // Initial progress: 60% (Medium)
        binding.sbTargetSize.setProgress(60);
        updateTargetSizeLabels(60);
    }

    private long getMinTargetSizeBytes() {
        long min = (long) pageCount * 45 * 1024L;
        long max = getMaxTargetSizeBytes();
        if (min >= max) {
            min = Math.max(10 * 1024L, (long) (originalSizeBytes * 0.40f));
        }
        return min;
    }

    private long getMaxTargetSizeBytes() {
        return Math.max(15 * 1024L, (long) (originalSizeBytes * 0.90f));
    }

    private long calculateTargetSizeBytes(int progress) {
        long minSize = getMinTargetSizeBytes();
        long maxSize = getMaxTargetSizeBytes();
        if (minSize >= maxSize) {
            minSize = Math.max(5 * 1024L, maxSize - 1024L);
        }
        return minSize + (long) ((maxSize - minSize) * (progress / 100.0f));
    }

    private void updateTargetSizeLabels(int progress) {
        long targetBytes = calculateTargetSizeBytes(progress);
        String sizeFormatted = StorageHelper.formatFileSize(targetBytes);
        binding.tvTargetSize.setText(getString(R.string.compress_target_size_format, sizeFormatted));

        int reduction = 100 - (int) ((targetBytes * 100.0f) / Math.max(1, originalSizeBytes));
        if (reduction > 0) {
            binding.tvReduction.setText(getString(R.string.compress_reduction_format, reduction));
        } else {
            binding.tvReduction.setText("");
        }
    }

    private void startTargetCompression() {
        if (pdfUri == null) return;

        final Context appContext = requireContext().getApplicationContext();
        final long targetSize = calculateTargetSizeBytes(binding.sbTargetSize.getProgress());

        // Extract and sanitize custom file name
        String inputName = binding.etFileName.getText() != null ? binding.etFileName.getText().toString().trim() : "";
        if (inputName.isEmpty()) {
            inputName = defaultCompressedName;
        }
        if (inputName.toLowerCase().endsWith(".pdf")) {
            inputName = inputName.substring(0, inputName.length() - 4);
        }
        final String compressedFileName = inputName;

        setUiLoading(true);

        executor.execute(() -> {
            File tempOutputFile = null;
            try {
                long timestamp = System.currentTimeMillis();
                tempOutputFile = new File(appContext.getCacheDir(), "compressed_" + timestamp + ".pdf");

                long compressedSize = PdfGenerator.compressPdfToTargetSize(
                        appContext,
                        pdfUri,
                        tempOutputFile,
                        targetSize,
                        (currentPage, totalPages) -> mainHandler.post(() -> {
                            if (binding != null) {
                                binding.progressBar.setProgress(currentPage);
                                binding.tvProgressStatus.setText("Compressing page " + currentPage + " of " + totalPages + "…");
                            }
                        })
                );

                // Guardrail: Never save/replace if resulting size is >= original
                if (compressedSize >= originalSizeBytes) {
                    if (tempOutputFile.exists()) {
                        tempOutputFile.delete();
                    }
                    mainHandler.post(() -> {
                        if (!isAdded()) return;
                        Toast.makeText(appContext, R.string.compress_already_optimal, Toast.LENGTH_LONG).show();
                        setUiLoading(false);
                    });
                    return;
                }

                // Save to MediaStore Scoped Storage
                Uri savedUri = StorageHelper.savePdfToPublicStorage(appContext, tempOutputFile, compressedFileName + ".pdf");
                if (tempOutputFile.exists()) {
                    tempOutputFile.delete();
                }

                if (savedUri != null) {
                    // Generate thumbnail for library
                    String thumbPath = PdfGenerator.generateThumbnailFromPdf(appContext, savedUri, timestamp);

                    DocumentEntity entity = new DocumentEntity(
                            compressedFileName,
                            "PDF",
                            "COMPRESSED",
                            pageCount,
                            compressedSize,
                            savedUri.toString(),
                            thumbPath,
                            timestamp
                    );
                    AppDatabase.getInstance(appContext).documentDao().insertDocument(entity);

                    mainHandler.post(() -> {
                        if (!isAdded()) return;
                        Toast.makeText(appContext, R.string.compress_success, Toast.LENGTH_SHORT).show();

                        // Auto-Open: Route directly to internal PdfViewerActivity
                        Intent viewerIntent = new Intent(appContext, PdfViewerActivity.class);
                        viewerIntent.putExtra(PdfViewerActivity.EXTRA_PDF_URI, savedUri.toString());
                        viewerIntent.putExtra(PdfViewerActivity.EXTRA_DOCUMENT_TITLE, compressedFileName);
                        viewerIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(viewerIntent);

                        dismiss();
                    });
                } else {
                    throw new IOException("Failed to export compressed PDF to storage");
                }

            } catch (Exception e) {
                Log.e(TAG, "Target compression failed", e);
                if (tempOutputFile != null && tempOutputFile.exists()) {
                    tempOutputFile.delete();
                }
                mainHandler.post(() -> {
                    if (!isAdded()) return;
                    Toast.makeText(appContext, R.string.compress_error, Toast.LENGTH_LONG).show();
                    setUiLoading(false);
                });
            }
        });
    }

    private void setUiLoading(boolean loading) {
        if (binding == null) return;
        binding.btnCompressOpen.setEnabled(!loading);
        binding.sbTargetSize.setEnabled(!loading);
        binding.btnPresetLow.setEnabled(!loading);
        binding.btnPresetMed.setEnabled(!loading);
        binding.btnPresetHigh.setEnabled(!loading);
        binding.tilFileName.setEnabled(!loading);
        binding.etFileName.setEnabled(!loading);
        binding.layoutProgress.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (loading) {
            binding.progressBar.setMax(pageCount);
            binding.progressBar.setProgress(0);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}
