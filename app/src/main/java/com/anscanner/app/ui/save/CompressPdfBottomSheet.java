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
        binding.btnPresetMed.setOnClickListener(v -> binding.sbTargetSize.setProgress(55));
        binding.btnPresetHigh.setOnClickListener(v -> binding.sbTargetSize.setProgress(85));

        // Initial progress: 55% (Medium)
        binding.sbTargetSize.setProgress(55);
        updateTargetSizeLabels(55);
    }

    private long calculateTargetSizeBytes(int progress) {
        long minSize = Math.max(30 * 1024L, (long) (originalSizeBytes * 0.15));
        long maxSize = originalSizeBytes;
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
        final String baseName = originalFileName.toLowerCase().endsWith(".pdf")
                ? originalFileName.substring(0, originalFileName.length() - 4)
                : originalFileName;
        final String compressedFileName = baseName + "_compressed";

        binding.btnCompressOpen.setEnabled(false);
        binding.sbTargetSize.setEnabled(false);
        binding.btnPresetLow.setEnabled(false);
        binding.btnPresetMed.setEnabled(false);
        binding.btnPresetHigh.setEnabled(false);
        binding.layoutProgress.setVisibility(View.VISIBLE);
        binding.progressBar.setMax(pageCount);
        binding.progressBar.setProgress(0);

        executor.execute(() -> {
            try {
                long timestamp = System.currentTimeMillis();
                File tempOutputFile = new File(appContext.getCacheDir(), "compressed_" + timestamp + ".pdf");

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

                // Save to MediaStore Scoped Storage
                Uri savedUri = StorageHelper.savePdfToPublicStorage(appContext, tempOutputFile, compressedFileName + ".pdf");
                tempOutputFile.delete();

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
                mainHandler.post(() -> {
                    if (!isAdded()) return;
                    Toast.makeText(appContext, R.string.compress_error, Toast.LENGTH_LONG).show();
                    binding.btnCompressOpen.setEnabled(true);
                    binding.sbTargetSize.setEnabled(true);
                    binding.btnPresetLow.setEnabled(true);
                    binding.btnPresetMed.setEnabled(true);
                    binding.btnPresetHigh.setEnabled(true);
                    binding.layoutProgress.setVisibility(View.GONE);
                });
            }
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}
