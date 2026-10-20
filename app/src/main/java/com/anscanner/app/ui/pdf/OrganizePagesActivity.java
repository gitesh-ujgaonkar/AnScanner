package com.anscanner.app.ui.pdf;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.ItemTouchHelper;

import com.anscanner.app.R;
import com.anscanner.app.data.AppDatabase;
import com.anscanner.app.data.dao.DocumentDao;
import com.anscanner.app.data.entity.DocumentEntity;
import com.anscanner.app.databinding.ActivityOrganizePagesBinding;
import com.anscanner.app.service.CacheManager;
import com.anscanner.app.service.StorageHelper;
import com.anscanner.app.util.InsetsHelper;
import com.anscanner.app.util.HapticHelper;
import com.anscanner.app.util.PdfToolsHelper;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Modern, professional page organization screen allowing users to:
 * <ul>
 *   <li>Smoothly reorder pages via drag-and-drop on a 3-column thumbnail grid</li>
 *   <li>Rotate individual pages in 90° clockwise increments</li>
 *   <li>Delete unwanted pages from the document</li>
 *   <li>Losslessly compile and save the updated PDF</li>
 * </ul>
 */
public class OrganizePagesActivity extends AppCompatActivity implements OrganizePageAdapter.OnPageActionListener {

    private static final String TAG = "OrganizePagesActivity";

    public static final String EXTRA_PDF_URI = "extra_pdf_uri";
    public static final String EXTRA_DOCUMENT_TITLE = "extra_document_title";
    public static final String EXTRA_DOCUMENT_ID = "extra_document_id";

    private ActivityOrganizePagesBinding binding;
    private Uri pdfUri;
    private String documentTitle = "Document";
    private long documentId = -1L;
    private int totalPages = 0;

    private OrganizePageAdapter adapter;
    private boolean isSaving = false;
    private boolean hasModified = false;

    private final ExecutorService thumbnailExecutor = Executors.newFixedThreadPool(2);
    private final ExecutorService taskExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityOrganizePagesBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        InsetsHelper.handleSystemBars(binding.getRoot(), binding.topBar, null);

        // 1. Parse Extras
        Intent intent = getIntent();
        if (intent != null) {
            if (intent.hasExtra(EXTRA_PDF_URI)) {
                Object uriObj = intent.getParcelableExtra(EXTRA_PDF_URI);
                if (uriObj instanceof Uri) {
                    pdfUri = (Uri) uriObj;
                } else {
                    String str = intent.getStringExtra(EXTRA_PDF_URI);
                    if (str != null) pdfUri = Uri.parse(str);
                }
            }
            if (intent.hasExtra(EXTRA_DOCUMENT_TITLE)) {
                String t = intent.getStringExtra(EXTRA_DOCUMENT_TITLE);
                if (t != null && !t.trim().isEmpty()) documentTitle = t.trim();
            }
            documentId = intent.getLongExtra(EXTRA_DOCUMENT_ID, -1L);
        }

        if (pdfUri == null) {
            Toast.makeText(this, R.string.pdf_load_error, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // 2. Setup Top Bar
        binding.btnBack.setOnClickListener(v -> handleBackPress());
        binding.btnSaveChanges.setOnClickListener(v -> saveChanges());
        binding.tvTitle.setText(R.string.organize_pages_title);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleBackPress();
            }
        });

        // 3. Load PDF Page Count and Setup Grid
        loadDocumentPages();
    }

    private void loadDocumentPages() {
        taskExecutor.execute(() -> {
            totalPages = PdfToolsHelper.getPdfPageCount(this, pdfUri);
            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (totalPages <= 0) {
                    Toast.makeText(this, R.string.pdf_load_error, Toast.LENGTH_SHORT).show();
                    finish();
                    return;
                }

                updatePageCountBadge();

                // Setup 3-Column Grid RecyclerView
                adapter = new OrganizePageAdapter(this, totalPages, this);
                binding.rvPages.setLayoutManager(new GridLayoutManager(this, 3));
                binding.rvPages.setAdapter(adapter);

                ItemTouchHelper touchHelper = new ItemTouchHelper(adapter.createTouchHelperCallback());
                touchHelper.attachToRecyclerView(binding.rvPages);

                // Asynchronously render thumbnails
                loadThumbnails();
            });
        });
    }

    private void loadThumbnails() {
        for (int i = 0; i < totalPages; i++) {
            final int pageIndex = i;
            thumbnailExecutor.execute(() -> {
                if (isFinishing() || isDestroyed()) return;
                Bitmap thumb = PdfToolsHelper.renderPageThumbnail(this, pdfUri, pageIndex, 300);
                if (thumb != null) {
                    mainHandler.post(() -> {
                        if (!isFinishing() && !isDestroyed() && adapter != null) {
                            adapter.setPageThumbnail(pageIndex, thumb);
                        }
                    });
                }
            });
        }
    }

    private void updatePageCountBadge() {
        int currentCount = adapter != null ? adapter.getItemCount() : totalPages;
        binding.tvPageCount.setText(getString(R.string.review_page_number, currentCount));
        binding.btnSaveChanges.setEnabled(currentCount > 0 && !isSaving);
    }

    @Override
    public void onPageRotated(int position, int newRotationDegrees) {
        hasModified = true;
    }

    @Override
    public void onPageDeleted(int position, OrganizePageAdapter.OrganizePageItem item) {
        if (adapter.getItemCount() <= 1) {
            Toast.makeText(this, R.string.organize_pages_cannot_delete_all, Toast.LENGTH_SHORT).show();
            return;
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.organize_pages_delete_confirm_title)
                .setMessage(getString(R.string.organize_pages_delete_confirm_msg, position + 1))
                .setPositiveButton(R.string.action_delete, (dialog, which) -> {
                    HapticHelper.warningPulse(binding != null ? binding.getRoot() : null);
                    hasModified = true;
                    adapter.removePage(position);
                    updatePageCountBadge();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    @Override
    public void onPagesReordered() {
        hasModified = true;
        updatePageCountBadge();
    }

    private void handleBackPress() {
        if (hasModified && !isSaving) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.organize_pages_unsaved_title)
                    .setMessage(R.string.organize_pages_unsaved_message)
                    .setPositiveButton(R.string.organize_pages_save, (dialog, which) -> saveChanges())
                    .setNegativeButton(R.string.action_discard, (dialog, which) -> finish())
                    .setNeutralButton(R.string.action_cancel, null)
                    .show();
        } else {
            finish();
        }
    }

    private void saveChanges() {
        if (adapter == null || adapter.getItemCount() == 0 || isSaving) return;

        isSaving = true;
        binding.layoutSavingOverlay.setVisibility(View.VISIBLE);

        final List<PdfToolsHelper.PageOrderInfo> orderInfos = adapter.getPageOrderInfos();
        final Context appContext = getApplicationContext();

        taskExecutor.execute(() -> {
            File tempOutputFile = new File(getCacheDir(), "reorganized_" + UUID.randomUUID() + ".pdf");
            boolean success = PdfToolsHelper.reorganizePdf(appContext, pdfUri, orderInfos, tempOutputFile);

            if (!success || !tempOutputFile.exists() || tempOutputFile.length() == 0) {
                mainHandler.post(() -> {
                    isSaving = false;
                    binding.layoutSavingOverlay.setVisibility(View.GONE);
                    Toast.makeText(appContext, R.string.save_error, Toast.LENGTH_SHORT).show();
                });
                return;
            }

            // Write back to original destination or public storage
            boolean committed = false;
            try {
                // If it is a local file or writable content URI, overwrite in place
                try (OutputStream os = getContentResolver().openOutputStream(pdfUri, "wt");
                     InputStream is = new FileInputStream(tempOutputFile)) {
                    if (os != null) {
                        byte[] buffer = new byte[16384];
                        int bytesRead;
                        while ((bytesRead = is.read(buffer)) > 0) {
                            os.write(buffer, 0, bytesRead);
                        }
                        os.flush();
                        committed = true;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Direct overwrite to URI failed, attempting file overwrite", e);
                }

                if (!committed && "file".equalsIgnoreCase(pdfUri.getScheme()) && pdfUri.getPath() != null) {
                    File localFile = new File(pdfUri.getPath());
                    if (tempOutputFile.renameTo(localFile)) {
                        committed = true;
                    } else {
                        try (InputStream in = new FileInputStream(tempOutputFile);
                             OutputStream out = new FileOutputStream(localFile)) {
                            byte[] buffer = new byte[16384];
                            int read;
                            while ((read = in.read(buffer)) > 0) {
                                out.write(buffer, 0, read);
                            }
                            out.flush();
                            committed = true;
                        }
                    }
                }

                // If URI was read-only external or could not be overwritten in place, save to Scoped Storage
                if (!committed) {
                    Uri newSavedUri = StorageHelper.savePdfToPublicStorage(appContext, tempOutputFile, documentTitle);
                    if (newSavedUri != null) {
                        pdfUri = newSavedUri;
                        committed = true;
                    }
                }

                // Update Room Database record if exists
                DocumentDao dao = AppDatabase.getInstance(appContext).documentDao();
                DocumentEntity docEntity = null;
                if (documentId > 0) {
                    docEntity = dao.getDocumentById(documentId);
                }
                if (docEntity == null && pdfUri != null) {
                    List<DocumentEntity> allDocs = dao.getAllDocumentsOrderByDate();
                    for (DocumentEntity d : allDocs) {
                        if (pdfUri.toString().equals(d.fileUri)) {
                            docEntity = d;
                            break;
                        }
                    }
                }

                if (docEntity != null) {
                    docEntity.pageCount = orderInfos.size();
                    docEntity.fileSizeBytes = tempOutputFile.length();
                    // Regenerate thumbnail for the new first page
                    Bitmap newThumb = PdfToolsHelper.renderPageThumbnail(appContext, pdfUri, 0, 300);
                    if (newThumb != null) {
                        String newThumbPath = CacheManager.savePermanentThumbnail(appContext, newThumb, docEntity.id);
                        if (newThumbPath != null) {
                            docEntity.thumbnailPath = newThumbPath;
                        }
                    }
                    dao.updateDocument(docEntity);
                }

            } catch (Exception e) {
                Log.e(TAG, "Error finalizing reorganized PDF", e);
            } finally {
                if (tempOutputFile.exists()) {
                    tempOutputFile.delete();
                }
            }

            final boolean finalCommitted = committed;
            mainHandler.post(() -> {
                isSaving = false;
                binding.layoutSavingOverlay.setVisibility(View.GONE);
                if (finalCommitted) {
                    Toast.makeText(appContext, R.string.organize_pages_saved, Toast.LENGTH_SHORT).show();
                    Intent result = new Intent();
                    result.putExtra(EXTRA_PDF_URI, pdfUri.toString());
                    result.putExtra("KEY_PAGES_REORGANIZED", true);
                    setResult(RESULT_OK, result);
                    com.anscanner.app.service.AdCacheManager.getInstance().showInterstitial(this, this::finish);
                } else {
                    Toast.makeText(appContext, R.string.save_error, Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        thumbnailExecutor.shutdownNow();
        taskExecutor.shutdown();
    }
}
