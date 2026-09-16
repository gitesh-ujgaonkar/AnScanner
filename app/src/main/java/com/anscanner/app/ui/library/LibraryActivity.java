package com.anscanner.app.ui.library;

import android.content.ContentUris;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Toast;

import android.util.Log;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.anscanner.app.R;
import com.anscanner.app.data.AppDatabase;
import com.anscanner.app.data.dao.DocumentDao;
import com.anscanner.app.data.entity.DocumentEntity;
import com.anscanner.app.databinding.ActivityLibraryBinding;
import com.anscanner.app.service.CrashManager;
import com.anscanner.app.service.PdfGenerator;
import com.anscanner.app.service.StorageHelper;
import com.anscanner.app.ui.camera.CameraActivity;
import com.anscanner.app.ui.settings.SettingsActivity;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.MobileAds;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.tabs.TabLayout;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LibraryActivity extends AppCompatActivity {
    private static final String TAG = "LibraryActivity";

    private ActivityLibraryBinding binding;
    private DocumentListAdapter adapter;
    private DocumentDao documentDao;
    private ExecutorService executor;
    private int currentTab = 0; // 0 = Scanned in App, 1 = All Device PDFs

    private final ActivityResultLauncher<String> pickPdfForCompressLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    showCompressionLevelDialog(uri);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityLibraryBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        documentDao = AppDatabase.getInstance(this).documentDao();
        executor = Executors.newSingleThreadExecutor();

        // Prompt user if a crash occurred previously
        CrashManager.checkAndPromptCrashLog(this);

        MobileAds.initialize(this, initializationStatus -> {});
        AdRequest adRequest = new AdRequest.Builder().build();
        binding.adView.loadAd(adRequest);

        setupRecyclerView();
        setupBottomNav();
        setupSearch();
        setupTabLayout();
    }

    @Override
    protected void onPause() {
        if (binding.adView != null) {
            binding.adView.pause();
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (binding.adView != null) {
            binding.adView.resume();
        }
        refreshDocuments("");
    }

    private void setupRecyclerView() {
        adapter = new DocumentListAdapter(this, new DocumentListAdapter.DocumentClickListener() {
            @Override
            public void onDocumentClick(DocumentEntity document) {
                openDocument(document);
            }

            @Override
            public void onDocumentShare(DocumentEntity document) {
                Uri uri = document.fileUri != null ? Uri.parse(document.fileUri) : null;
                String mimeType = "PDF".equalsIgnoreCase(document.format) ? "application/pdf" : "image/jpeg";
                Intent shareIntent = StorageHelper.createShareIntent(uri, mimeType, document.title);
                startActivity(Intent.createChooser(shareIntent, "Share " + document.title));
            }

            @Override
            public void onDocumentRemoveAppOnly(DocumentEntity document) {
                showRemoveAppOnlyConfirmation(document);
            }

            @Override
            public void onDocumentDeletePermanently(DocumentEntity document) {
                showDeletePermanentlyConfirmation(document);
            }
        });
        binding.rvDocuments.setLayoutManager(new LinearLayoutManager(this));
        binding.rvDocuments.setAdapter(adapter);
    }

    private void openDocument(DocumentEntity document) {
        if ("PDF".equalsIgnoreCase(document.format)) {
            Intent intent = new Intent(this, com.anscanner.app.ui.pdf.PdfViewerActivity.class);
            if (document.fileUri != null) {
                intent.putExtra(com.anscanner.app.ui.pdf.PdfViewerActivity.EXTRA_PDF_URI, document.fileUri);
            }
            intent.putExtra(com.anscanner.app.ui.pdf.PdfViewerActivity.EXTRA_DOCUMENT_TITLE, document.title);
            startActivity(intent);
        } else {
            Uri uri = document.fileUri != null ? Uri.parse(document.fileUri) : null;
            if (uri != null) {
                Intent viewIntent = new Intent(Intent.ACTION_VIEW);
                viewIntent.setDataAndType(uri, "image/jpeg");
                viewIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(viewIntent, document.title));
            }
        }
    }

    private void setupBottomNav() {
        binding.fabCamera.setOnClickListener(v -> {
            startActivity(new Intent(this, CameraActivity.class));
        });

        binding.fabCompress.setOnClickListener(v -> {
            pickPdfForCompressLauncher.launch("application/pdf");
        });

        binding.navSettings.setOnClickListener(v -> {
            startActivity(new Intent(this, SettingsActivity.class));
        });
        
        binding.navLibrary.setOnClickListener(v -> {
            binding.rvDocuments.smoothScrollToPosition(0);
        });
    }

    private void setupSearch() {
        binding.searchView.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                refreshDocuments(s.toString());
            }
        });
    }

    private void setupTabLayout() {
        binding.tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                currentTab = tab.getPosition();
                refreshDocuments(binding.searchView.getText().toString());
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {}

            @Override
            public void onTabReselected(TabLayout.Tab tab) {}
        });
    }

    private void refreshDocuments(String query) {
        if (currentTab == 0) {
            refreshAppDocuments(query);
        } else {
            refreshDevicePdfs(query);
        }
    }

    private void refreshAppDocuments(String query) {
        executor.execute(() -> {
            List<Object> items = new ArrayList<>();
            List<DocumentEntity> allDocs;

            if (query != null && !query.trim().isEmpty()) {
                allDocs = documentDao.searchByTitle("%" + query.trim() + "%");
                if (!allDocs.isEmpty()) {
                    items.add("SEARCH RESULTS");
                    items.addAll(allDocs);
                }
            } else {
                Calendar cal = Calendar.getInstance();
                cal.set(Calendar.HOUR_OF_DAY, 0);
                cal.set(Calendar.MINUTE, 0);
                cal.set(Calendar.SECOND, 0);
                cal.set(Calendar.MILLISECOND, 0);
                long todayStart = cal.getTimeInMillis();

                cal.add(Calendar.DAY_OF_YEAR, -7);
                long weekAgo = cal.getTimeInMillis();

                List<DocumentEntity> todayDocs = documentDao.getDocumentsCreatedToday(todayStart);
                if (!todayDocs.isEmpty()) {
                    items.add(getString(R.string.library_date_today));
                    items.addAll(todayDocs);
                }

                List<DocumentEntity> prevWeekDocs = documentDao.getDocumentsPreviousWeek(weekAgo, todayStart);
                if (!prevWeekDocs.isEmpty()) {
                    items.add(getString(R.string.library_date_previous_7));
                    items.addAll(prevWeekDocs);
                }

                List<DocumentEntity> olderDocs = documentDao.getDocumentsOlder(weekAgo);
                if (!olderDocs.isEmpty()) {
                    items.add(getString(R.string.library_date_older));
                    items.addAll(olderDocs);
                }
            }

            runOnUiThread(() -> {
                adapter.updateItems(items);
                if (items.isEmpty()) {
                    binding.tvEmptyTitle.setText(R.string.library_empty_title);
                    binding.tvEmptySubtitle.setText(R.string.library_empty_subtitle);
                    binding.emptyState.setVisibility(View.VISIBLE);
                    binding.rvDocuments.setVisibility(View.GONE);
                } else {
                    binding.emptyState.setVisibility(View.GONE);
                    binding.rvDocuments.setVisibility(View.VISIBLE);
                }
            });
        });
    }

    private void refreshDevicePdfs(String query) {
        executor.execute(() -> {
            List<Object> items = new ArrayList<>();
            List<DocumentEntity> pdfList = new ArrayList<>();

            Uri collection = MediaStore.Files.getContentUri("external");
            String[] projection = new String[] {
                    MediaStore.Files.FileColumns._ID,
                    MediaStore.Files.FileColumns.DISPLAY_NAME,
                    MediaStore.Files.FileColumns.DATA,
                    MediaStore.Files.FileColumns.SIZE,
                    MediaStore.Files.FileColumns.DATE_MODIFIED
            };
            String selection = MediaStore.Files.FileColumns.MIME_TYPE + "=?";
            String[] selectionArgs = new String[]{"application/pdf"};
            String sortOrder = MediaStore.Files.FileColumns.DATE_MODIFIED + " DESC";

            try (Cursor cursor = getContentResolver().query(collection, projection, selection, selectionArgs, sortOrder)) {
                if (cursor != null) {
                    int idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID);
                    int nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME);
                    int dataCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA);
                    int sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE);
                    int dateCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED);

                    while (cursor.moveToNext()) {
                        long id = cursor.getLong(idCol);
                        String name = cursor.getString(nameCol);
                        String data = dataCol != -1 ? cursor.getString(dataCol) : null;
                        long size = cursor.getLong(sizeCol);
                        long dateModifiedSec = cursor.getLong(dateCol);
                        long dateModifiedMillis = dateModifiedSec * 1000L;

                        Uri contentUri = ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), id);

                        DocumentEntity entity = new DocumentEntity();
                        entity.id = -id; // Negative ID denotes external non-Room document
                        entity.title = name != null ? name : "PDF Document";
                        entity.format = "PDF";
                        entity.quality = "NORMAL";
                        entity.pageCount = 0;
                        entity.fileSizeBytes = size;
                        entity.fileUri = contentUri.toString();
                        entity.thumbnailPath = null;
                        entity.createdAt = dateModifiedMillis > 0 ? dateModifiedMillis : System.currentTimeMillis();
                        entity.updatedAt = entity.createdAt;

                        if (query == null || query.trim().isEmpty()
                                || entity.title.toLowerCase(Locale.getDefault()).contains(query.toLowerCase(Locale.getDefault()).trim())) {
                            pdfList.add(entity);
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error querying device PDFs via MediaStore", e);
            }

            if (query != null && !query.trim().isEmpty()) {
                if (!pdfList.isEmpty()) {
                    items.add("SEARCH RESULTS");
                    items.addAll(pdfList);
                }
            } else {
                Calendar cal = Calendar.getInstance();
                cal.set(Calendar.HOUR_OF_DAY, 0);
                cal.set(Calendar.MINUTE, 0);
                cal.set(Calendar.SECOND, 0);
                cal.set(Calendar.MILLISECOND, 0);
                long todayStart = cal.getTimeInMillis();

                cal.add(Calendar.DAY_OF_YEAR, -7);
                long weekAgo = cal.getTimeInMillis();

                List<DocumentEntity> todayDocs = new ArrayList<>();
                List<DocumentEntity> prevWeekDocs = new ArrayList<>();
                List<DocumentEntity> olderDocs = new ArrayList<>();

                for (DocumentEntity doc : pdfList) {
                    if (doc.createdAt >= todayStart) {
                        todayDocs.add(doc);
                    } else if (doc.createdAt >= weekAgo) {
                        prevWeekDocs.add(doc);
                    } else {
                        olderDocs.add(doc);
                    }
                }

                if (!todayDocs.isEmpty()) {
                    items.add(getString(R.string.library_date_today));
                    items.addAll(todayDocs);
                }
                if (!prevWeekDocs.isEmpty()) {
                    items.add(getString(R.string.library_date_previous_7));
                    items.addAll(prevWeekDocs);
                }
                if (!olderDocs.isEmpty()) {
                    items.add(getString(R.string.library_date_older));
                    items.addAll(olderDocs);
                }
            }

            runOnUiThread(() -> {
                adapter.updateItems(items);
                if (items.isEmpty()) {
                    binding.tvEmptyTitle.setText(R.string.tab_all_device_pdfs);
                    binding.tvEmptySubtitle.setText(R.string.library_empty_device_pdfs);
                    binding.emptyState.setVisibility(View.VISIBLE);
                    binding.rvDocuments.setVisibility(View.GONE);
                } else {
                    binding.emptyState.setVisibility(View.GONE);
                    binding.rvDocuments.setVisibility(View.VISIBLE);
                }
            });
        });
    }

    private void showRemoveAppOnlyConfirmation(DocumentEntity document) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.library_remove_confirm, document.title))
                .setMessage(R.string.library_remove_confirm_message)
                .setPositiveButton(R.string.action_remove_from_app, (dialog, which) -> {
                    removeDocumentAppOnly(document);
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void removeDocumentAppOnly(DocumentEntity document) {
        executor.execute(() -> {
            // Strictly delete the document record from the Room Database. Do not touch the file system.
            documentDao.deleteDocumentById(document.id);
            runOnUiThread(() -> {
                adapter.removeItem(document);
                if (adapter.isEmpty()) {
                    binding.tvEmptyTitle.setText(R.string.library_empty_title);
                    binding.tvEmptySubtitle.setText(R.string.library_empty_subtitle);
                    binding.emptyState.setVisibility(View.VISIBLE);
                    binding.rvDocuments.setVisibility(View.GONE);
                }
                Toast.makeText(this, R.string.library_removed_from_app, Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void showDeletePermanentlyConfirmation(DocumentEntity document) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.library_delete_permanently_confirm, document.title))
                .setMessage(R.string.library_delete_permanently_message)
                .setPositiveButton(R.string.action_delete_permanently, (dialog, which) -> {
                    deleteDocumentPermanently(document);
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void deleteDocumentPermanently(DocumentEntity document) {
        executor.execute(() -> {
            // 1. Delete the document record from the Room Database if app-internal document
            if (document.id > 0) {
                documentDao.deleteDocumentById(document.id);
            }

            // 2. Instantiate the physical file: File pdfFile = new File(document.getFilePath()); and call pdfFile.delete().
            String path = document.getFilePath();
            if (path != null && !path.isEmpty()) {
                try {
                    File pdfFile = new File(path);
                    if (pdfFile.exists()) {
                        pdfFile.delete();
                    }
                } catch (Exception ignored) {}
            }

            // Also delete via ContentResolver if it is a MediaStore URI (Android Scoped Storage)
            if (document.fileUri != null && document.fileUri.startsWith("content://")) {
                try {
                    getContentResolver().delete(Uri.parse(document.fileUri), null, null);
                } catch (Exception ignored) {}
            }

            // 3. Instantiate the thumbnail file (if stored separately) and call .delete() on it as well.
            if (document.thumbnailPath != null && !document.thumbnailPath.isEmpty()) {
                try {
                    File thumbFile = new File(document.thumbnailPath);
                    if (thumbFile.exists()) {
                        thumbFile.delete();
                    }
                } catch (Exception ignored) {}
            }

            // 4. Run notifyItemRemoved() on the UI thread to update the list.
            runOnUiThread(() -> {
                adapter.removeItem(document);
                if (adapter.isEmpty()) {
                    if (currentTab == 0) {
                        binding.tvEmptyTitle.setText(R.string.library_empty_title);
                        binding.tvEmptySubtitle.setText(R.string.library_empty_subtitle);
                    } else {
                        binding.tvEmptyTitle.setText(R.string.tab_all_device_pdfs);
                        binding.tvEmptySubtitle.setText(R.string.library_empty_device_pdfs);
                    }
                    binding.emptyState.setVisibility(View.VISIBLE);
                    binding.rvDocuments.setVisibility(View.GONE);
                }
                Toast.makeText(this, R.string.library_deleted_permanently, Toast.LENGTH_SHORT).show();
            });
        });
    }

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

        executor.execute(() -> {
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
                    // Generate permanent thumbnail for Library row
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
                    documentDao.insertDocument(entity);

                    runOnUiThread(() -> {
                        if (progressDialog.isShowing()) {
                            progressDialog.dismiss();
                        }
                        Toast.makeText(this, R.string.compression_complete, Toast.LENGTH_SHORT).show();
                        refreshDocuments(binding.searchView.getText().toString());
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
        if (binding.adView != null) {
            binding.adView.destroy();
        }
        super.onDestroy();
        executor.shutdown();
    }
}
