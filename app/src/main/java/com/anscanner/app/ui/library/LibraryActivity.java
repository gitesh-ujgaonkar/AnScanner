package com.anscanner.app.ui.library;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.anscanner.app.R;
import com.anscanner.app.data.AppDatabase;
import com.anscanner.app.data.dao.DocumentDao;
import com.anscanner.app.data.entity.DocumentEntity;
import com.anscanner.app.databinding.ActivityLibraryBinding;
import com.anscanner.app.service.StorageHelper;
import com.anscanner.app.ui.camera.CameraActivity;
import com.anscanner.app.ui.settings.SettingsActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LibraryActivity extends AppCompatActivity {
    private ActivityLibraryBinding binding;
    private DocumentListAdapter adapter;
    private DocumentDao documentDao;
    private ExecutorService executor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityLibraryBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        documentDao = AppDatabase.getInstance(this).documentDao();
        executor = Executors.newSingleThreadExecutor();

        setupRecyclerView();
        setupBottomNav();
        setupSearch();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshDocuments("");
    }

    private void setupRecyclerView() {
        adapter = new DocumentListAdapter(this, new DocumentListAdapter.DocumentClickListener() {
            @Override
            public void onDocumentClick(DocumentEntity document) {
                // Open share intent
                Uri uri = document.fileUri != null ? Uri.parse(document.fileUri) : null;
                String mimeType = "PDF".equalsIgnoreCase(document.format) ? "application/pdf" : "image/jpeg";
                Intent shareIntent = StorageHelper.createShareIntent(uri, mimeType, document.title);
                startActivity(Intent.createChooser(shareIntent, "Share Document"));
            }

            @Override
            public void onDocumentLongClick(DocumentEntity document) {
                showDeleteConfirmation(document);
            }
        });
        binding.rvDocuments.setLayoutManager(new LinearLayoutManager(this));
        binding.rvDocuments.setAdapter(adapter);
    }

    private void setupBottomNav() {
        binding.fabCamera.setOnClickListener(v -> {
            startActivity(new Intent(this, CameraActivity.class));
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

    private void refreshDocuments(String query) {
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
                    binding.emptyState.setVisibility(View.VISIBLE);
                    binding.rvDocuments.setVisibility(View.GONE);
                } else {
                    binding.emptyState.setVisibility(View.GONE);
                    binding.rvDocuments.setVisibility(View.VISIBLE);
                }
            });
        });
    }

    private void showDeleteConfirmation(DocumentEntity document) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.library_delete_confirm)
                .setMessage("Are you sure you want to delete '" + document.title + "'?")
                .setPositiveButton("Delete", (dialog, which) -> {
                    deleteDocument(document);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteDocument(DocumentEntity document) {
        executor.execute(() -> {
            documentDao.deleteDocumentById(document.id);
            runOnUiThread(() -> {
                Toast.makeText(this, R.string.library_deleted, Toast.LENGTH_SHORT).show();
                refreshDocuments(binding.searchView.getText().toString());
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}
