package com.anscanner.app.ui.save;

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
    private BottomSheetSaveScanBinding binding;
    private ArrayList<String> pagePaths;
    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    public static SaveScanBottomSheet newInstance(ArrayList<String> pagePaths) {
        SaveScanBottomSheet fragment = new SaveScanBottomSheet();
        Bundle args = new Bundle();
        args.putStringArrayList(ARG_PAGE_PATHS, pagePaths);
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
        }
        if (pagePaths == null) {
            pagePaths = new ArrayList<>();
        }
        
        binding.tvPageCount.setText(getString(R.string.review_page_number, pagePaths.size()));
        
        String defaultName = "Doc_" + new SimpleDateFormat("yyyy_MM_dd", Locale.getDefault()).format(new Date());
        binding.etFileName.setText(defaultName);
        
        binding.toggleFormat.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) updateSizeEstimate();
        });
        
        binding.toggleQuality.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) updateSizeEstimate();
        });
        
        updateSizeEstimate();
        
        binding.btnSaveShare.setOnClickListener(v -> saveAndShare());
    }
    
    private void updateSizeEstimate() {
        boolean isPdf = binding.toggleFormat.getCheckedButtonId() == R.id.btnPdf;
        String sizeStr = StorageHelper.estimateFileSize(pagePaths, isPdf);
        binding.tvSizeBadge.setText(sizeStr + " · Stored locally");
    }
    
    private void saveAndShare() {
        binding.btnSaveShare.setEnabled(false);
        binding.btnSaveShare.setText("Generating...");
        
        String fileName = binding.etFileName.getText() != null ? binding.etFileName.getText().toString() : "Document";
        boolean isPdf = binding.toggleFormat.getCheckedButtonId() == R.id.btnPdf;
        boolean isHighQuality = binding.toggleQuality.getCheckedButtonId() == R.id.btnHigh;
        
        executor.execute(() -> {
            try {
                Uri savedUri = null;
                long fileSize = 0;
                
                if (isPdf) {
                    File tempPdf = new File(requireContext().getCacheDir(), "temp_doc.pdf");
                    PdfGenerator.createPdfFromPages(pagePaths, tempPdf, isHighQuality);
                    savedUri = StorageHelper.savePdfToPublicStorage(requireContext(), tempPdf, fileName);
                    fileSize = tempPdf.length();
                    tempPdf.delete();
                } else {
                    if (!pagePaths.isEmpty()) {
                        int quality = isHighQuality ? 95 : 70;
                        savedUri = StorageHelper.saveJpgToPublicStorage(requireContext(), pagePaths.get(0), fileName, quality);
                        fileSize = new File(pagePaths.get(0)).length(); // Approximation
                    }
                }
                
                if (savedUri != null) {
                    String thumbPath = CacheManager.savePersistentThumbnail(requireContext(), pagePaths.get(0), System.currentTimeMillis());
                    
                    DocumentEntity doc = new DocumentEntity(fileName, isPdf ? "PDF" : "JPG", 
                            isHighQuality ? "High" : "Normal", pagePaths.size(), fileSize, savedUri.toString(), thumbPath, System.currentTimeMillis());
                            
                    List<PageEntity> pages = new ArrayList<>();
                    for (int i = 0; i < pagePaths.size(); i++) {
                        pages.add(new PageEntity(0, i + 1, pagePaths.get(i), "Original", System.currentTimeMillis()));
                    }
                    
                    DocumentDao dao = AppDatabase.getInstance(requireContext()).documentDao();
                    dao.insertDocumentWithPages(doc, pages);
                    
                    CacheManager.clearScanCache(requireContext());
                    
                    final Uri finalUri = savedUri;
                    mainHandler.post(() -> {
                        Toast.makeText(requireContext(), R.string.save_success, Toast.LENGTH_SHORT).show();
                        Intent shareIntent = StorageHelper.createShareIntent(finalUri, isPdf ? "application/pdf" : "image/jpeg", fileName);
                        startActivity(Intent.createChooser(shareIntent, "Share via"));
                        
                        Intent intent = new Intent();
                        intent.setClassName(requireContext(), "com.anscanner.app.ui.library.LibraryActivity");
                        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                        startActivity(intent);
                        dismiss();
                    });
                } else {
                    throw new Exception("Failed to save file to storage");
                }
            } catch (Exception e) {
                e.printStackTrace();
                mainHandler.post(() -> {
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
