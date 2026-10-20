package com.anscanner.app.ui.library;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.anscanner.app.R;
import com.anscanner.app.data.entity.DocumentEntity;
import com.anscanner.app.databinding.FragmentDocumentListBinding;
import com.anscanner.app.service.DevicePdfScanner;
import com.anscanner.app.service.FilePropertiesHelper;
import com.anscanner.app.service.PdfThumbnailLoader;
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
 * Tab fragment displaying public PDF documents discovered from the device using MediaStore API
 * and policy-safe SAF tree folder sync on modern Android (API 33-36).
 * Scoped Storage compliant without MANAGE_EXTERNAL_STORAGE.
 */
public class DeviceDocsFragment extends Fragment implements
        DocumentListAdapter.DocumentClickListener,
        MergePdfBottomSheet.OnPdfMergedListener,
        SplitPdfBottomSheet.OnPdfSplitListener {

    private static final String TAG = "DeviceDocsFragment";

    private FragmentDocumentListBinding binding;
    private DocumentListAdapter adapter;
    private ExecutorService executor;
    private String currentSearchQuery = "";
    private boolean userDismissedSyncBanner = false;

    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    loadDevicePdfs(currentSearchQuery);
                } else {
                    showPermissionDeniedState();
                }
            });

    private final ActivityResultLauncher<String[]> openDocumentLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null && isAdded()) {
                    try {
                        requireContext().getContentResolver().takePersistableUriPermission(
                                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (Exception ignored) {}
                    Intent intent = new Intent(requireContext(), PdfViewerActivity.class);
                    intent.putExtra(PdfViewerActivity.EXTRA_PDF_URI, uri.toString());
                    intent.putExtra(PdfViewerActivity.EXTRA_DOCUMENT_TITLE, StorageHelper.queryFileName(requireContext(), uri));
                    startActivity(intent);
                }
            });

    private final ActivityResultLauncher<Intent> syncFolderLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == android.app.Activity.RESULT_OK && result.getData() != null) {
                    Intent data = result.getData();
                    Uri treeUri = data.getData();
                    if (treeUri != null && isAdded()) {
                        try {
                            int takeFlags = data.getFlags()
                                    & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                            requireContext().getContentResolver().takePersistableUriPermission(
                                    treeUri, takeFlags != 0 ? takeFlags : Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        } catch (Exception e) {
                            Log.w(TAG, "Failed taking persistable permission for: " + treeUri, e);
                        }
                        DevicePdfScanner.saveSyncedFolderUri(requireContext(), treeUri);
                        if (binding != null && binding.cardSyncBanner != null) {
                            binding.cardSyncBanner.setVisibility(View.GONE);
                        }
                        loadDevicePdfs(currentSearchQuery);
                        Toast.makeText(requireContext(), R.string.sync_folder_success, Toast.LENGTH_SHORT).show();
                    }
                }
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentDocumentListBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        executor = Executors.newSingleThreadExecutor();

        adapter = new DocumentListAdapter(requireContext(), this);
        binding.rvDocuments.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.rvDocuments.setAdapter(adapter);

        binding.swipeRefreshLayout.setColorSchemeColors(
                ContextCompat.getColor(requireContext(), R.color.accent_mint));
        binding.swipeRefreshLayout.setProgressBackgroundColorSchemeColor(
                ContextCompat.getColor(requireContext(), R.color.surface_card));
        binding.swipeRefreshLayout.setOnRefreshListener(() -> loadDevicePdfs(currentSearchQuery));

        binding.btnSyncBannerAction.setOnClickListener(v -> launchSyncDownloadsPicker());
        binding.btnDismissSyncBanner.setOnClickListener(v -> {
            userDismissedSyncBanner = true;
            binding.cardSyncBanner.setVisibility(View.GONE);
        });

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

        loadDevicePdfs(currentSearchQuery);
    }

    @Override
    public void onResume() {
        super.onResume();
        loadDevicePdfs(currentSearchQuery);
    }

    public void setFilterQuery(String query) {
        this.currentSearchQuery = query != null ? query : "";
        if (isAdded()) {
            loadDevicePdfs(currentSearchQuery);
        }
    }

    public void scrollToTop() {
        if (binding != null && binding.rvDocuments != null) {
            binding.rvDocuments.smoothScrollToPosition(0);
        }
    }

    public boolean hasStoragePermission() {
        Context context = getContext();
        if (context == null) return false;
        return DevicePdfScanner.hasStoragePermission(context);
    }

    private void openDocumentPicker() {
        try {
            openDocumentLauncher.launch(new String[]{"application/pdf"});
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch document picker", e);
            Toast.makeText(requireContext(), "Unable to open document picker", Toast.LENGTH_SHORT).show();
        }
    }

    private void launchSyncDownloadsPicker() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                intent.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI);
            }
            syncFolderLauncher.launch(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch folder picker", e);
            openDocumentPicker();
        }
    }

    private void showPermissionDeniedState() {
        if (binding == null) return;
        if (binding.swipeRefreshLayout.isRefreshing()) {
            binding.swipeRefreshLayout.setRefreshing(false);
        }
        binding.tvEmptyTitle.setText(R.string.permission_storage_title);
        binding.tvEmptySubtitle.setText(R.string.permission_storage_desc);
        binding.btnEmptyAction.setText(R.string.permission_grant);
        binding.btnEmptyAction.setVisibility(View.VISIBLE);
        binding.btnEmptyAction.setOnClickListener(v ->
                requestPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE));
        binding.btnEmptySecondary.setVisibility(View.GONE);
        binding.emptyState.setVisibility(View.VISIBLE);
        binding.rvDocuments.setVisibility(View.GONE);
        binding.btnBrowseBottom.setVisibility(View.GONE);
        binding.cardSyncBanner.setVisibility(View.GONE);
    }

    private void loadDevicePdfs(String query) {
        if (!isAdded()) return;
        Context context = getContext();
        if (context == null) return;

        if (!DevicePdfScanner.hasStoragePermission(context)) {
            requestPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE);
            showPermissionDeniedState();
            return;
        }

        DevicePdfScanner.scanPublicDocumentsAsync(context, query, new DevicePdfScanner.DocumentCallback() {
            @Override
            public void onDocumentsLoaded(@NonNull List<DocumentEntity> pdfList) {
                if (!isAdded() || binding == null) return;
                if (binding.swipeRefreshLayout.isRefreshing()) {
                    binding.swipeRefreshLayout.setRefreshing(false);
                }

                List<Object> items = groupDocumentsByDate(pdfList, query);
                adapter.updateItems(items);

                if (items.isEmpty()) {
                    binding.tvEmptyTitle.setText(R.string.tab_all_device_pdfs);
                    binding.tvEmptySubtitle.setText(R.string.library_empty_device_pdfs);
                    binding.btnEmptyAction.setText(R.string.action_sync_downloads_full);
                    binding.btnEmptyAction.setVisibility(View.VISIBLE);
                    binding.btnEmptyAction.setOnClickListener(v -> launchSyncDownloadsPicker());

                    binding.btnEmptySecondary.setText(R.string.action_browse_pdf);
                    binding.btnEmptySecondary.setVisibility(View.VISIBLE);
                    binding.btnEmptySecondary.setOnClickListener(v -> openDocumentPicker());

                    binding.emptyState.setVisibility(View.VISIBLE);
                    binding.rvDocuments.setVisibility(View.GONE);
                    binding.btnBrowseBottom.setVisibility(View.GONE);
                    binding.cardSyncBanner.setVisibility(View.GONE);
                } else {
                    binding.emptyState.setVisibility(View.GONE);
                    binding.rvDocuments.setVisibility(View.VISIBLE);
                    binding.btnBrowseBottom.setVisibility(View.VISIBLE);
                    binding.btnBrowseBottom.setOnClickListener(v -> openDocumentPicker());

                    // Show folder sync banner if few items are found and user hasn't synced or dismissed
                    if (!userDismissedSyncBanner && !DevicePdfScanner.hasSyncedFolder(requireContext()) && pdfList.size() <= 3) {
                        binding.cardSyncBanner.setVisibility(View.VISIBLE);
                    } else {
                        binding.cardSyncBanner.setVisibility(View.GONE);
                    }
                }

                // Asynchronously generate thumbnails for any items missing cached thumbnails
                Context ctx = getContext();
                if (ctx != null) {
                    PdfThumbnailLoader.generateThumbnailsAsync(ctx, pdfList, () -> {
                        if (isAdded() && adapter != null) {
                            adapter.notifyDataSetChanged();
                        }
                    });
                }
            }

            @Override
            public void onError(@NonNull Exception exception) {
                Log.e(TAG, "Error scanning public PDFs", exception);
                if (!isAdded() || binding == null) return;
                if (binding.swipeRefreshLayout.isRefreshing()) {
                    binding.swipeRefreshLayout.setRefreshing(false);
                }

                if (adapter.isEmpty()) {
                    binding.tvEmptyTitle.setText(R.string.tab_all_device_pdfs);
                    binding.tvEmptySubtitle.setText(R.string.library_empty_device_pdfs);
                    binding.btnEmptyAction.setText(R.string.action_sync_downloads_full);
                    binding.btnEmptyAction.setVisibility(View.VISIBLE);
                    binding.btnEmptyAction.setOnClickListener(v -> launchSyncDownloadsPicker());

                    binding.btnEmptySecondary.setText(R.string.action_browse_pdf);
                    binding.btnEmptySecondary.setVisibility(View.VISIBLE);
                    binding.btnEmptySecondary.setOnClickListener(v -> openDocumentPicker());

                    binding.emptyState.setVisibility(View.VISIBLE);
                    binding.rvDocuments.setVisibility(View.GONE);
                    binding.btnBrowseBottom.setVisibility(View.GONE);
                    binding.cardSyncBanner.setVisibility(View.GONE);
                }
            }
        });
    }

    private List<Object> groupDocumentsByDate(List<DocumentEntity> pdfList, String query) {
        List<Object> items = new ArrayList<>();
        if (query != null && !query.trim().isEmpty()) {
            if (!pdfList.isEmpty()) {
                items.add("SEARCH RESULTS");
                items.addAll(pdfList);
            }
            return items;
        }

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

        return items;
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
        Intent shareIntent = StorageHelper.createShareIntent(uri, "application/pdf", document.title);
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
            binding.cardSyncBanner.setVisibility(View.GONE);
            binding.tvSelectionCount.setText(getString(R.string.selection_count_format, selectedCount));
            binding.btnMergeSelection.setEnabled(selectedCount >= 2);
            binding.btnMergeSelection.setAlpha(selectedCount >= 2 ? 1.0f : 0.5f);
            int total = getDocumentCount();
            boolean allSelected = selectedCount > 0 && selectedCount == total;
            binding.btnSelectAll.setText(allSelected ? R.string.action_deselect_all : R.string.action_select_all);
        } else {
            binding.cardSelectionBar.setVisibility(View.GONE);
            binding.btnMergeSelection.setEnabled(false);
            if (!userDismissedSyncBanner && !DevicePdfScanner.hasSyncedFolder(requireContext()) && getDocumentCount() <= 3) {
                binding.cardSyncBanner.setVisibility(View.VISIBLE);
            }
        }
    }

    @Override
    public void onPdfMergedSuccess() {
        if (adapter != null) adapter.exitMultiSelect();
        loadDevicePdfs(currentSearchQuery);
    }

    @Override
    public void onPdfSplitSuccess() {
        loadDevicePdfs(currentSearchQuery);
    }

    public void reload() {
        loadDevicePdfs(currentSearchQuery);
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
        // Not applicable for external device PDFs
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
        if (executor == null || executor.isShutdown()) return;
        executor.execute(() -> {
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

            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                adapter.removeItem(document);
                if (adapter.isEmpty() && binding != null) {
                    binding.tvEmptyTitle.setText(R.string.tab_all_device_pdfs);
                    binding.tvEmptySubtitle.setText(R.string.library_empty_device_pdfs);
                    binding.btnEmptyAction.setText(R.string.action_sync_downloads_full);
                    binding.btnEmptyAction.setVisibility(View.VISIBLE);
                    binding.btnEmptyAction.setOnClickListener(v -> launchSyncDownloadsPicker());

                    binding.btnEmptySecondary.setText(R.string.action_browse_pdf);
                    binding.btnEmptySecondary.setVisibility(View.VISIBLE);
                    binding.btnEmptySecondary.setOnClickListener(v -> openDocumentPicker());

                    binding.emptyState.setVisibility(View.VISIBLE);
                    binding.rvDocuments.setVisibility(View.GONE);
                    binding.btnBrowseBottom.setVisibility(View.GONE);
                    binding.cardSyncBanner.setVisibility(View.GONE);
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
