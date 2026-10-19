package com.anscanner.app.ui.library;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.anscanner.app.R;
import com.anscanner.app.data.AppDatabase;
import com.anscanner.app.data.dao.DocumentDao;
import com.anscanner.app.data.entity.DocumentEntity;
import com.anscanner.app.databinding.FragmentDocumentListBinding;
import com.anscanner.app.service.FilePropertiesHelper;
import com.anscanner.app.service.StorageHelper;
import com.anscanner.app.ui.pdf.MergePdfBottomSheet;
import com.anscanner.app.ui.pdf.OrganizePagesActivity;
import com.anscanner.app.ui.pdf.PdfViewerActivity;
import com.anscanner.app.ui.pdf.SplitPdfBottomSheet;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tab fragment displaying documents scanned in-app and saved to internal / app storage.
 */
public class ScannedDocsFragment extends Fragment implements
        DocumentListAdapter.DocumentClickListener,
        MergePdfBottomSheet.OnPdfMergedListener,
        SplitPdfBottomSheet.OnPdfSplitListener {

    private FragmentDocumentListBinding binding;
    private DocumentListAdapter adapter;
    private DocumentDao documentDao;
    private ExecutorService executor;
    private String currentSearchQuery = "";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentDocumentListBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        documentDao = AppDatabase.getInstance(requireContext()).documentDao();
        executor = Executors.newSingleThreadExecutor();

        adapter = new DocumentListAdapter(requireContext(), this);
        binding.rvDocuments.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.rvDocuments.setAdapter(adapter);

        binding.swipeRefreshLayout.setColorSchemeColors(
                ContextCompat.getColor(requireContext(), R.color.accent_mint));
        binding.swipeRefreshLayout.setProgressBackgroundColorSchemeColor(
                ContextCompat.getColor(requireContext(), R.color.surface_card));
        binding.swipeRefreshLayout.setOnRefreshListener(() -> loadDocuments(currentSearchQuery));

        binding.btnCloseSelection.setOnClickListener(v -> {
            if (adapter != null) adapter.exitMultiSelect();
        });

        binding.btnSelectAll.setOnClickListener(v -> {
            if (adapter != null) {
                if (adapter.getSelectedCount() == getDocumentCount()) {
                    adapter.clearSelection();
                } else {
                    adapter.selectAllDocuments();
                }
            }
        });

        binding.btnMergeSelection.setOnClickListener(v -> {
            if (adapter == null) return;
            List<DocumentEntity> selected = adapter.getSelectedDocuments();
            if (selected.size() < 2) {
                Toast.makeText(requireContext(), R.string.merge_min_files_error, Toast.LENGTH_SHORT).show();
                return;
            }
            MergePdfBottomSheet sheet = MergePdfBottomSheet.newInstanceFromDocuments(selected);
            sheet.show(getChildFragmentManager(), "MergePdfBottomSheet");
        });

        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (adapter != null && adapter.isMultiSelectMode()) {
                    adapter.exitMultiSelect();
                } else {
                    setEnabled(false);
                    requireActivity().getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        loadDocuments(currentSearchQuery);
    }

    @Override
    public void onResume() {
        super.onResume();
        loadDocuments(currentSearchQuery);
    }

    public void setFilterQuery(String query) {
        this.currentSearchQuery = query != null ? query : "";
        if (isAdded()) {
            loadDocuments(currentSearchQuery);
        }
    }

    public void scrollToTop() {
        if (binding != null && binding.rvDocuments != null) {
            binding.rvDocuments.smoothScrollToPosition(0);
        }
    }

    private boolean doesDocumentExist(Context context, DocumentEntity doc) {
        if (doc == null || doc.fileUri == null || doc.fileUri.trim().isEmpty()) {
            return false;
        }
        String uriString = doc.fileUri.trim();
        if (uriString.startsWith("/") || uriString.startsWith("file://")) {
            String path = uriString.startsWith("file://") ? uriString.substring(7) : uriString;
            File f = new File(path);
            return f.exists() && f.length() > 0;
        } else if (uriString.startsWith("content://")) {
            try {
                Uri uri = Uri.parse(uriString);
                try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(uri, "r")) {
                    return pfd != null && pfd.getStatSize() > 0;
                }
            } catch (Exception e) {
                return false;
            }
        } else {
            File f = new File(uriString);
            return f.exists() && f.length() > 0;
        }
    }

    private List<DocumentEntity> filterAndCleanDocs(Context context, List<DocumentEntity> rawDocs) {
        if (rawDocs == null || rawDocs.isEmpty()) return new ArrayList<>();
        List<DocumentEntity> valid = new ArrayList<>();
        for (DocumentEntity doc : rawDocs) {
            if (doesDocumentExist(context, doc)) {
                valid.add(doc);
            } else {
                // File deleted externally; clean up stale record and thumbnail
                try {
                    documentDao.deleteDocument(doc);
                    if (doc.thumbnailPath != null) {
                        File thumb = new File(doc.thumbnailPath);
                        if (thumb.exists()) {
                            thumb.delete();
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
        return valid;
    }

    private void loadDocuments(String query) {
        if (executor == null || executor.isShutdown()) return;

        executor.execute(() -> {
            if (!isAdded()) return;
            Context context = getContext();
            if (context == null) return;
            Context appContext = context.getApplicationContext();

            List<Object> items = new ArrayList<>();

            if (query != null && !query.trim().isEmpty()) {
                List<DocumentEntity> allDocs = filterAndCleanDocs(appContext, documentDao.searchByTitle("%" + query.trim() + "%"));
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

                List<DocumentEntity> todayDocs = filterAndCleanDocs(appContext, documentDao.getDocumentsCreatedToday(todayStart));
                if (!todayDocs.isEmpty()) {
                    items.add(getString(R.string.library_date_today));
                    items.addAll(todayDocs);
                }

                List<DocumentEntity> prevWeekDocs = filterAndCleanDocs(appContext, documentDao.getDocumentsPreviousWeek(weekAgo, todayStart));
                if (!prevWeekDocs.isEmpty()) {
                    items.add(getString(R.string.library_date_previous_7));
                    items.addAll(prevWeekDocs);
                }

                List<DocumentEntity> olderDocs = filterAndCleanDocs(appContext, documentDao.getDocumentsOlder(weekAgo));
                if (!olderDocs.isEmpty()) {
                    items.add(getString(R.string.library_date_older));
                    items.addAll(olderDocs);
                }
            }

            if (!isAdded()) return;

            requireActivity().runOnUiThread(() -> {
                if (binding == null) return;
                if (binding.swipeRefreshLayout.isRefreshing()) {
                    binding.swipeRefreshLayout.setRefreshing(false);
                }
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

    @Override
    public void onDocumentClick(DocumentEntity document) {
        Intent intent = new Intent(requireContext(), PdfViewerActivity.class);
        if (document.fileUri != null) {
            intent.putExtra(PdfViewerActivity.EXTRA_PDF_URI, document.fileUri);
        }
        intent.putExtra(PdfViewerActivity.EXTRA_DOCUMENT_TITLE, document.title);
        startActivity(intent);
    }

    @Override
    public void onDocumentShare(DocumentEntity document) {
        Uri uri = document.fileUri != null ? Uri.parse(document.fileUri) : null;
        String mimeType = "PDF".equalsIgnoreCase(document.format) ? "application/pdf" : "image/jpeg";
        Intent shareIntent = StorageHelper.createShareIntent(uri, mimeType, document.title);
        startActivity(Intent.createChooser(shareIntent, "Share " + document.title));
    }

    @Override
    public void onDocumentExportImages(DocumentEntity document) {
        if (document == null || document.fileUri == null) return;
        Uri uri = Uri.parse(document.fileUri);
        com.anscanner.app.ui.export.ExportImagesBottomSheet sheet =
                com.anscanner.app.ui.export.ExportImagesBottomSheet.newInstance(uri, document.title);
        sheet.show(getChildFragmentManager(), "ExportImagesBottomSheet");
    }

    @Override
    public void onDocumentOrganizePages(DocumentEntity document) {
        if (document == null || document.fileUri == null) return;
        Intent intent = new Intent(requireContext(), OrganizePagesActivity.class);
        intent.putExtra(OrganizePagesActivity.EXTRA_PDF_URI, Uri.parse(document.fileUri));
        intent.putExtra(OrganizePagesActivity.EXTRA_DOCUMENT_TITLE, document.title);
        intent.putExtra(OrganizePagesActivity.EXTRA_DOCUMENT_ID, document.id);
        startActivity(intent);
    }

    @Override
    public void onDocumentSplitPdf(DocumentEntity document) {
        if (document == null || document.fileUri == null) return;
        Uri uri = Uri.parse(document.fileUri);
        SplitPdfBottomSheet sheet = SplitPdfBottomSheet.newInstance(uri, document.title);
        sheet.show(getChildFragmentManager(), "SplitPdfBottomSheet");
    }

    @Override
    public void onSelectionModeChanged(boolean isMultiSelect, int selectedCount) {
        if (binding == null) return;
        if (isMultiSelect) {
            binding.cardSelectionBar.setVisibility(View.VISIBLE);
            binding.btnBrowseBottom.setVisibility(View.GONE);
            binding.tvSelectionCount.setText(getString(R.string.selection_count_format, selectedCount));
            binding.btnMergeSelection.setEnabled(selectedCount >= 2);
            binding.btnMergeSelection.setAlpha(selectedCount >= 2 ? 1.0f : 0.5f);
            int total = getDocumentCount();
            boolean allSelected = selectedCount > 0 && selectedCount == total;
            binding.btnSelectAll.setText(allSelected ? R.string.action_deselect_all : R.string.action_select_all);
        } else {
            binding.cardSelectionBar.setVisibility(View.GONE);
            binding.btnMergeSelection.setEnabled(false);
        }
    }

    @Override
    public void onPdfMergedSuccess() {
        if (adapter != null) adapter.exitMultiSelect();
        loadDocuments(currentSearchQuery);
    }

    @Override
    public void onPdfSplitSuccess() {
        loadDocuments(currentSearchQuery);
    }

    public void reload() {
        loadDocuments(currentSearchQuery);
    }

    private int getDocumentCount() {
        int count = 0;
        if (adapter != null) {
            for (Object item : adapter.getItems()) {
                if (item instanceof DocumentEntity) count++;
            }
        }
        return count;
    }

    @Override
    public void onDocumentProperties(DocumentEntity document) {
        FilePropertiesHelper.showPropertiesDialog(requireContext(), document);
    }

    @Override
    public void onDocumentRemoveAppOnly(DocumentEntity document) {
        new MaterialAlertDialogBuilder(requireContext())
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
            documentDao.deleteDocumentById(document.id);
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                adapter.removeItem(document);
                if (adapter.isEmpty() && binding != null) {
                    binding.tvEmptyTitle.setText(R.string.library_empty_title);
                    binding.tvEmptySubtitle.setText(R.string.library_empty_subtitle);
                    binding.emptyState.setVisibility(View.VISIBLE);
                    binding.rvDocuments.setVisibility(View.GONE);
                }
                Toast.makeText(requireContext(), R.string.library_removed_from_app, Toast.LENGTH_SHORT).show();
            });
        });
    }

    @Override
    public void onDocumentDeletePermanently(DocumentEntity document) {
        new MaterialAlertDialogBuilder(requireContext())
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
            if (document.id > 0) {
                documentDao.deleteDocumentById(document.id);
            }

            String path = document.getFilePath();
            if (path != null && !path.isEmpty()) {
                try {
                    File pdfFile = new File(path);
                    if (pdfFile.exists()) {
                        pdfFile.delete();
                    }
                } catch (Exception ignored) {}
            }

            if (document.fileUri != null && document.fileUri.startsWith("content://")) {
                try {
                    requireContext().getContentResolver().delete(Uri.parse(document.fileUri), null, null);
                } catch (Exception ignored) {}
            }

            if (document.thumbnailPath != null && !document.thumbnailPath.isEmpty()) {
                try {
                    File thumbFile = new File(document.thumbnailPath);
                    if (thumbFile.exists()) {
                        thumbFile.delete();
                    }
                } catch (Exception ignored) {}
            }

            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                adapter.removeItem(document);
                if (adapter.isEmpty() && binding != null) {
                    binding.tvEmptyTitle.setText(R.string.library_empty_title);
                    binding.tvEmptySubtitle.setText(R.string.library_empty_subtitle);
                    binding.emptyState.setVisibility(View.VISIBLE);
                    binding.rvDocuments.setVisibility(View.GONE);
                }
                Toast.makeText(requireContext(), R.string.library_deleted_permanently, Toast.LENGTH_SHORT).show();
            });
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (executor != null) {
            executor.shutdown();
        }
    }
}
