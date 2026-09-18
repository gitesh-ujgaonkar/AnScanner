package com.anscanner.app.ui.save;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.anscanner.app.R;
import com.anscanner.app.data.AppDatabase;
import com.anscanner.app.data.dao.DocumentDao;
import com.anscanner.app.data.entity.DocumentEntity;
import com.anscanner.app.data.entity.PageEntity;
import com.anscanner.app.databinding.BottomSheetSaveScanBinding;
import com.anscanner.app.service.CacheManager;
import com.anscanner.app.service.PdfGenerator;
import com.anscanner.app.service.ReviewHelper;
import com.anscanner.app.service.StorageHelper;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SaveScanBottomSheet extends BottomSheetDialogFragment {

    private static final String ARG_PAGE_PATHS = "arg_page_paths";
    private static final String ARG_PDF_PATH = "arg_pdf_path";
    private BottomSheetSaveScanBinding binding;
    private ArrayList<String> pagePaths;
    private String preGeneratedPdfPath;
    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    public static SaveScanBottomSheet newInstance(ArrayList<String> pagePaths) {
        return newInstance(pagePaths, null);
    }

    public static SaveScanBottomSheet newInstance(ArrayList<String> pagePaths, String pdfPath) {
        SaveScanBottomSheet fragment = new SaveScanBottomSheet();
        Bundle args = new Bundle();
        args.putStringArrayList(ARG_PAGE_PATHS, pagePaths);
        if (pdfPath != null) {
            args.putString(ARG_PDF_PATH, pdfPath);
        }
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
        binding = BottomSheetSaveScanBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        if (getArguments() != null) {
            pagePaths = getArguments().getStringArrayList(ARG_PAGE_PATHS);
            preGeneratedPdfPath = getArguments().getString(ARG_PDF_PATH);
        }
        if (pagePaths == null) {
            pagePaths = new ArrayList<>();
        }
        
        binding.tvPageCount.setText(getString(R.string.review_page_number, pagePaths.size()));
        
        String defaultName = "AnScanner_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        binding.tilFileName.setHintEnabled(false);
        binding.etFileName.setText(defaultName);
        binding.etFileName.setSelection(defaultName.length());
        
        binding.toggleFormat.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) {
                boolean isPdf = checkedId == R.id.btnPdf;
                binding.layoutPdfSecurity.setVisibility(isPdf ? View.VISIBLE : View.GONE);
                updateSizeEstimate();
            }
        });

        binding.switchPasswordProtect.setOnCheckedChangeListener((buttonView, isChecked) -> {
            binding.layoutPasswordInputs.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            if (!isChecked) {
                binding.tilPassword.setError(null);
                binding.tilConfirmPassword.setError(null);
            }
        });

        binding.sbQuality.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    binding.rgQuality.clearCheck();
                }
                updateSizeEstimate();
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar seekBar) {}
        });
        
        binding.rgQuality.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.rbLow) {
                binding.sbQuality.setProgress(30);
            } else if (checkedId == R.id.rbMedium) {
                binding.sbQuality.setProgress(60);
            } else if (checkedId == R.id.rbHigh) {
                binding.sbQuality.setProgress(100);
            }
            updateSizeEstimate();
        });
        
        updateSizeEstimate();
        
        binding.btnSaveShare.setOnClickListener(v -> saveAndShare());
    }

    private int getSelectedQuality() {
        int progress = binding.sbQuality.getProgress();
        return Math.max(5, progress);
    }
    
    private void updateSizeEstimate() {
        boolean isPdf = binding.toggleFormat.getCheckedButtonId() == R.id.btnPdf;
        int quality = getSelectedQuality();
        String sizeStr = StorageHelper.estimateFileSize(pagePaths, isPdf, quality);
        binding.tvTargetSize.setText(String.format(getString(R.string.compress_target_size_format), sizeStr));
        binding.tvSizeBadge.setText(sizeStr + " · Stored locally");
    }
    
    private void saveAndShare() {
        if (pagePaths == null || pagePaths.isEmpty()) {
            Toast.makeText(requireContext(), "No pages to save", Toast.LENGTH_SHORT).show();
            return;
        }

        final boolean isPdf = binding.toggleFormat.getCheckedButtonId() == R.id.btnPdf;
        final boolean isPasswordProtected = isPdf && binding.switchPasswordProtect.isChecked();
        final String password;
        if (isPasswordProtected) {
            String pass = binding.etPassword.getText() != null ? binding.etPassword.getText().toString().trim() : "";
            String confirm = binding.etConfirmPassword.getText() != null ? binding.etConfirmPassword.getText().toString().trim() : "";
            if (pass.isEmpty()) {
                binding.tilPassword.setError(getString(R.string.save_password_error_empty));
                return;
            }
            if (!pass.equals(confirm)) {
                binding.tilConfirmPassword.setError(getString(R.string.save_password_error_mismatch));
                return;
            }
            binding.tilPassword.setError(null);
            binding.tilConfirmPassword.setError(null);
            password = pass;
        } else {
            password = null;
        }

        binding.btnSaveShare.setEnabled(false);
        binding.btnSaveShare.setText(isPasswordProtected ? getString(R.string.encrypting_pdf) : "Generating...");

        final Context appContext = requireContext().getApplicationContext();
        String nameInput = binding.etFileName.getText() != null ? binding.etFileName.getText().toString().trim() : "";
        if (nameInput.isEmpty()) {
            nameInput = "Scan_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        }
        final String fileName = nameInput;
        final int quality = getSelectedQuality();
        final String qualityLabel = quality == 100 ? "High (100%)" : (quality == 60 ? "Medium (60%)" : "Low (30%)");
        final List<String> currentPages = new ArrayList<>(pagePaths);

        executor.execute(() -> {
            try {
                Uri savedUri = null;
                long fileSize = 0;
                long docTimestamp = System.currentTimeMillis();

                if (isPdf) {
                    File tempPdf = new File(appContext.getCacheDir(), "temp_doc_" + docTimestamp + ".pdf");
                    PdfGenerator.createPdfFromPages(currentPages, tempPdf, quality);

                    if (isPasswordProtected && password != null) {
                        File encryptedPdf = new File(appContext.getCacheDir(), "temp_enc_" + docTimestamp + ".pdf");
                        PdfGenerator.encryptPdf(appContext, tempPdf, encryptedPdf, password);
                        tempPdf.delete();
                        savedUri = StorageHelper.savePdfToPublicStorage(appContext, encryptedPdf, fileName);
                        fileSize = encryptedPdf.length();
                        encryptedPdf.delete();
                    } else {
                        savedUri = StorageHelper.savePdfToPublicStorage(appContext, tempPdf, fileName);
                        fileSize = tempPdf.length();
                        tempPdf.delete();
                    }
                } else {
                    savedUri = StorageHelper.saveJpgToPublicStorage(appContext, currentPages.get(0), fileName, quality);
                    fileSize = new File(currentPages.get(0)).length();
                }

                if (savedUri != null) {
                    // Downscale first page to 200x200 and save permanently to getFilesDir()
                    String thumbPath = CacheManager.savePermanentThumbnail(appContext, currentPages.get(0), docTimestamp);

                    DocumentEntity doc = new DocumentEntity(fileName, isPdf ? "PDF" : "JPG",
                            qualityLabel, currentPages.size(), fileSize,
                            savedUri.toString(), thumbPath, docTimestamp);

                    List<PageEntity> pages = new ArrayList<>();
                    for (int i = 0; i < currentPages.size(); i++) {
                        pages.add(new PageEntity(0, i + 1, currentPages.get(i), "Original", docTimestamp));
                    }

                    DocumentDao dao = AppDatabase.getInstance(appContext).documentDao();
                    dao.insertDocumentWithPages(doc, pages);

                    // Clear temporary scan cache files after thumbnail and doc are committed
                    CacheManager.clearScanCache(appContext);

                    final Uri finalUri = savedUri;
                    mainHandler.post(() -> {
                        if (!isAdded()) return;
                        Toast.makeText(appContext, R.string.save_success, Toast.LENGTH_SHORT).show();
                        Intent shareIntent = StorageHelper.createShareIntent(finalUri, isPdf ? "application/pdf" : "image/jpeg", fileName);
                        startActivity(Intent.createChooser(shareIntent, "Share via"));

                        // Trigger Google Play In-App Review prompt (prompts on 1st scan, then every 3rd scan: 4, 7, 10...)
                        if (getActivity() != null) {
                            com.anscanner.app.service.InAppReviewHelper.onScanCompleted(getActivity());
                        }

                        Intent intent = new Intent(appContext, com.anscanner.app.ui.library.LibraryActivity.class);
                        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                        dismiss();
                    });
                } else {
                    throw new Exception("Failed to save file to storage");
                }
            } catch (Exception e) {
                e.printStackTrace();
                mainHandler.post(() -> {
                    if (!isAdded()) return;
                    Toast.makeText(requireContext(), R.string.save_error, Toast.LENGTH_SHORT).show();
                    binding.btnSaveShare.setEnabled(true);
                    binding.btnSaveShare.setText(R.string.save_button);
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
