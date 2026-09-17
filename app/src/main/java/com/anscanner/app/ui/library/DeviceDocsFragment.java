package com.anscanner.app.ui.library;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

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
import com.anscanner.app.service.FilePropertiesHelper;
import com.anscanner.app.service.StorageHelper;
import com.anscanner.app.ui.pdf.PdfViewerActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tab fragment displaying public PDF documents queried from the device using MediaStore
 * across all external storage volumes, with runtime permission checks and SAF integration.
 */
public class DeviceDocsFragment extends Fragment implements DocumentListAdapter.DocumentClickListener {

    private static final String TAG = "DeviceDocsFragment";

    private FragmentDocumentListBinding binding;
    private DocumentListAdapter adapter;
    private ExecutorService executor;
    private String currentSearchQuery = "";

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                if (hasStoragePermission()) {
                    loadDevicePdfs(currentSearchQuery);
                } else {
                    boolean anyRationale = false;
                    for (String perm : getRequiredPermissions()) {
                        if (shouldShowRequestPermissionRationale(perm)) {
                            anyRationale = true;
                            break;
                        }
                    }
                    if (anyRationale) {
                        showPermissionRationaleDialog();
                    } else {
                        showSettingsDialog();
                    }
                }
            });

    private final ActivityResultLauncher<String[]> openDocumentLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null && isAdded()) {
                    Intent intent = new Intent(requireContext(), PdfViewerActivity.class);
                    intent.putExtra(PdfViewerActivity.EXTRA_PDF_URI, uri.toString());
                    intent.putExtra(PdfViewerActivity.EXTRA_DOCUMENT_TITLE, StorageHelper.queryFileName(requireContext(), uri));
                    startActivity(intent);
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

        if (!hasStoragePermission()) {
            checkAndRequestStoragePermission();
        } else {
            loadDevicePdfs(currentSearchQuery);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (hasStoragePermission()) {
            loadDevicePdfs(currentSearchQuery);
        } else {
            showPermissionRequiredUi();
        }
    }

    public void setFilterQuery(String query) {
        this.currentSearchQuery = query != null ? query : "";
        if (isAdded() && hasStoragePermission()) {
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            return true;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED;
        } else {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
    }

    public String[] getRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return new String[]{Manifest.permission.READ_MEDIA_IMAGES};
        } else {
            return new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
        }
    }

    private void checkAndRequestStoragePermission() {
        if (hasStoragePermission()) {
            loadDevicePdfs(currentSearchQuery);
            return;
        }

        boolean shouldShowRationale = false;
        for (String perm : getRequiredPermissions()) {
            if (shouldShowRequestPermissionRationale(perm)) {
                shouldShowRationale = true;
                break;
            }
        }

        if (shouldShowRationale) {
            showPermissionRationaleDialog();
        } else {
            permissionLauncher.launch(getRequiredPermissions());
        }
    }

    private void showPermissionRationaleDialog() {
        if (!isAdded()) return;

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.permission_storage_rationale_title)
                .setMessage(R.string.permission_storage_rationale_message)
                .setPositiveButton(R.string.permission_grant, (dialog, which) -> {
                    permissionLauncher.launch(getRequiredPermissions());
                })
                .setNegativeButton(R.string.action_cancel, (dialog, which) -> {
                    showPermissionRequiredUi();
                })
                .show();
    }

    private void showSettingsDialog() {
        if (!isAdded()) return;

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.permission_storage_rationale_title)
                .setMessage(R.string.permission_storage_rationale_message)
                .setPositiveButton(R.string.permission_open_settings, (dialog, which) -> {
                    try {
                        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        Uri uri = Uri.fromParts("package", requireContext().getPackageName(), null);
                        intent.setData(uri);
                        startActivity(intent);
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to launch app details settings", e);
                    }
                })
                .setNegativeButton(R.string.action_cancel, (dialog, which) -> {
                    showPermissionRequiredUi();
                })
                .show();
    }

    private void showPermissionRequiredUi() {
        if (binding == null) return;
        adapter.updateItems(new ArrayList<>());
        binding.tvEmptyTitle.setText(R.string.permission_storage_title);
        binding.tvEmptySubtitle.setText(R.string.permission_storage_desc);
        binding.btnEmptyAction.setText(R.string.permission_grant);
        binding.btnEmptyAction.setVisibility(View.VISIBLE);
        binding.btnEmptyAction.setOnClickListener(v -> checkAndRequestStoragePermission());
        binding.emptyState.setVisibility(View.VISIBLE);
        binding.rvDocuments.setVisibility(View.GONE);
    }

    private void openDocumentPicker() {
        try {
            openDocumentLauncher.launch(new String[]{"application/pdf"});
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch document picker", e);
            Toast.makeText(requireContext(), "Unable to open document picker", Toast.LENGTH_SHORT).show();
        }
    }

    private void loadDevicePdfs(String query) {
        if (!hasStoragePermission()) {
            showPermissionRequiredUi();
            return;
        }

        if (executor == null || executor.isShutdown()) return;

        executor.execute(() -> {
            List<Object> items = new ArrayList<>();
            List<DocumentEntity> pdfList = new ArrayList<>();
            Set<String> seenUris = new HashSet<>();

            if (!isAdded()) return;

            Context context = getContext();
            if (context == null) return;

            List<Uri> contentUris = new ArrayList<>();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    Set<String> volumeNames = MediaStore.getExternalVolumeNames(context);
                    for (String volume : volumeNames) {
                        contentUris.add(MediaStore.Files.getContentUri(volume));
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to retrieve external volume names", e);
                }
            }
            if (contentUris.isEmpty()) {
                contentUris.add(MediaStore.Files.getContentUri("external"));
            }

            String[] projection = new String[] {
                    MediaStore.Files.FileColumns._ID,
                    MediaStore.Files.FileColumns.DISPLAY_NAME,
                    MediaStore.Files.FileColumns.DATA,
                    MediaStore.Files.FileColumns.SIZE,
                    MediaStore.Files.FileColumns.DATE_ADDED,
                    MediaStore.Files.FileColumns.DATE_MODIFIED
            };

            String selection = "(" + MediaStore.Files.FileColumns.MIME_TYPE + "=? OR "
                    + MediaStore.Files.FileColumns.DISPLAY_NAME + " LIKE '%.pdf' OR "
                    + MediaStore.Files.FileColumns.DATA + " LIKE '%.pdf')";
            String[] selectionArgs = new String[]{"application/pdf"};
            String sortOrder = MediaStore.Files.FileColumns.DATE_ADDED + " DESC";

            for (Uri baseUri : contentUris) {
                try (Cursor cursor = context.getContentResolver().query(baseUri, projection, selection, selectionArgs, sortOrder)) {
                    if (cursor != null) {
                        int idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID);
                        int nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME);
                        int dataCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA);
                        int sizeCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.SIZE);
                        int dateAddedCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_ADDED);
                        int dateModifiedCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED);

                        while (cursor.moveToNext()) {
                            long id = cursor.getLong(idCol);
                            String name = cursor.getString(nameCol);
                            String data = dataCol != -1 ? cursor.getString(dataCol) : null;
                            long size = sizeCol != -1 ? cursor.getLong(sizeCol) : 0;

                            long dateAddedSec = dateAddedCol != -1 ? cursor.getLong(dateAddedCol) : 0;
                            long dateModifiedSec = dateModifiedCol != -1 ? cursor.getLong(dateModifiedCol) : 0;
                            long timestampSec = dateAddedSec > 0 ? dateAddedSec : dateModifiedSec;
                            long timestampMillis = timestampSec * 1000L;

                            Uri itemContentUri = ContentUris.withAppendedId(baseUri, id);
                            String uriKey = itemContentUri.toString();
                            if (seenUris.contains(uriKey)) {
                                continue;
                            }
                            seenUris.add(uriKey);

                            DocumentEntity entity = new DocumentEntity();
                            entity.id = -id; // Negative ID denotes external non-Room document
                            entity.title = name != null ? name : (data != null ? new File(data).getName() : "PDF Document");
                            entity.format = "PDF";
                            entity.quality = "NORMAL";
                            entity.pageCount = 0;
                            entity.fileSizeBytes = size;
                            entity.fileUri = uriKey;

                            File cachedThumb = new File(context.getCacheDir(), "thumb_device_" + Math.abs(id) + ".jpg");
                            if (cachedThumb.exists() && cachedThumb.length() > 0) {
                                entity.thumbnailPath = cachedThumb.getAbsolutePath();
                            } else {
                                entity.thumbnailPath = null;
                            }

                            entity.createdAt = timestampMillis > 0 ? timestampMillis : System.currentTimeMillis();
                            entity.updatedAt = entity.createdAt;

                            if (query == null || query.trim().isEmpty()
                                    || entity.title.toLowerCase(Locale.getDefault()).contains(query.toLowerCase(Locale.getDefault()).trim())) {
                                pdfList.add(entity);
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error querying device PDFs via volume: " + baseUri, e);
                }
            }

            // Sort aggregated list by date added/created descending
            Collections.sort(pdfList, (d1, d2) -> Long.compare(d2.createdAt, d1.createdAt));

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

            if (!isAdded()) return;

            requireActivity().runOnUiThread(() -> {
                if (binding == null) return;
                adapter.updateItems(items);
                if (items.isEmpty()) {
                    binding.tvEmptyTitle.setText(R.string.tab_all_device_pdfs);
                    binding.tvEmptySubtitle.setText(R.string.library_empty_device_pdfs);
                    binding.btnEmptyAction.setText(R.string.action_browse_pdf);
                    binding.btnEmptyAction.setVisibility(View.VISIBLE);
                    binding.btnEmptyAction.setOnClickListener(v -> openDocumentPicker());
                    binding.emptyState.setVisibility(View.VISIBLE);
                    binding.rvDocuments.setVisibility(View.GONE);
                } else {
                    binding.emptyState.setVisibility(View.GONE);
                    binding.rvDocuments.setVisibility(View.VISIBLE);
                }
            });

            // Asynchronously generate thumbnails for any items that lack a cached thumbnail
            generateMissingThumbnailsAsync(pdfList);
        });
    }

    private void generateMissingThumbnailsAsync(List<DocumentEntity> documents) {
        if (executor == null || executor.isShutdown()) return;

        executor.execute(() -> {
            boolean anyGenerated = false;
            for (DocumentEntity doc : documents) {
                if (doc.thumbnailPath == null && doc.fileUri != null) {
                    String path = getOrGeneratePdfThumbnail(Uri.parse(doc.fileUri), -doc.id);
                    if (path != null) {
                        doc.thumbnailPath = path;
                        anyGenerated = true;
                    }
                }
            }
            if (anyGenerated && isAdded()) {
                requireActivity().runOnUiThread(() -> {
                    if (adapter != null) {
                        adapter.notifyDataSetChanged();
                    }
                });
            }
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
        Intent shareIntent = StorageHelper.createShareIntent(uri, "application/pdf", document.title);
        startActivity(Intent.createChooser(shareIntent, "Share " + document.title));
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
                    binding.btnEmptyAction.setText(R.string.action_browse_pdf);
                    binding.btnEmptyAction.setVisibility(View.VISIBLE);
                    binding.btnEmptyAction.setOnClickListener(v -> openDocumentPicker());
                    binding.emptyState.setVisibility(View.VISIBLE);
                    binding.rvDocuments.setVisibility(View.GONE);
                }
                Toast.makeText(requireContext(), R.string.library_deleted_permanently, Toast.LENGTH_SHORT).show();
            });
        });
    }

    /**
     * Resiliently generates a thumbnail for an external PDF using PdfRenderer.
     * Catches and safely recovers from corruption, password protection, or decoding errors.
     */
    @Nullable
    private String getOrGeneratePdfThumbnail(Uri uri, long docId) {
        Context context = getContext();
        if (context == null) return null;

        File thumbFile = new File(context.getCacheDir(), "thumb_device_" + Math.abs(docId) + ".jpg");
        if (thumbFile.exists() && thumbFile.length() > 0) {
            return thumbFile.getAbsolutePath();
        }

        ParcelFileDescriptor pfd = null;
        PdfRenderer renderer = null;
        PdfRenderer.Page page = null;
        try {
            pfd = context.getContentResolver().openFileDescriptor(uri, "r");
            if (pfd != null) {
                renderer = new PdfRenderer(pfd);
                if (renderer.getPageCount() > 0) {
                    page = renderer.openPage(0);
                    int width = 160;
                    int height = Math.max(1, (int) (((float) page.getHeight() / page.getWidth()) * width));
                    Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);

                    try (FileOutputStream fos = new FileOutputStream(thumbFile)) {
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 75, fos);
                        fos.flush();
                    } finally {
                        bitmap.recycle();
                    }
                    return thumbFile.getAbsolutePath();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to render PDF thumbnail for " + uri + " (corrupted or password-protected): " + e.getMessage());
            return null;
        } finally {
            if (page != null) {
                try { page.close(); } catch (Exception ignored) {}
            }
            if (renderer != null) {
                try { renderer.close(); } catch (Exception ignored) {}
            }
            if (pfd != null) {
                try { pfd.close(); } catch (Exception ignored) {}
            }
        }
        return null;
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
