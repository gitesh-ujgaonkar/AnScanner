package com.anscanner.app.ui.export;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;

import com.anscanner.app.R;
import com.anscanner.app.databinding.BottomSheetExportImagesBinding;
import com.anscanner.app.service.StorageHelper;
import com.anscanner.app.util.PdfRendererHelper;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.snackbar.Snackbar;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Modern Material Bottom Sheet for selecting and exporting PDF pages
 * as high-resolution JPG or PNG images directly to the device's Pictures folder
 * or sharing them immediately via the system share sheet.
 */
public class ExportImagesBottomSheet extends BottomSheetDialogFragment {

    private static final String TAG = "ExportImagesBottomSheet";
    private static final String ARG_PDF_URI = "arg_pdf_uri";
    private static final String ARG_DOCUMENT_TITLE = "arg_document_title";

    private BottomSheetExportImagesBinding binding;
    private Uri pdfUri;
    private String documentTitle = "Document";
    private int totalPages = 0;

    private ExportPageAdapter adapter;
    private File tempSeekablePdf = null;
    private boolean isExporting = false;

    private final ExecutorService thumbnailExecutor = Executors.newFixedThreadPool(2);
    private final ExecutorService exportExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static ExportImagesBottomSheet newInstance(@NonNull Uri pdfUri, @Nullable String documentTitle) {
        ExportImagesBottomSheet fragment = new ExportImagesBottomSheet();
        Bundle args = new Bundle();
        args.putParcelable(ARG_PDF_URI, pdfUri);
        args.putString(ARG_DOCUMENT_TITLE, documentTitle != null ? documentTitle : "Document");
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public int getTheme() {
        return R.style.Theme_AnScanner_BottomSheet;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        BottomSheetDialog dialog = (BottomSheetDialog) super.onCreateDialog(savedInstanceState);
        dialog.setOnShowListener(d -> {
            BottomSheetDialog bsd = (BottomSheetDialog) d;
            FrameLayout bottomSheet = bsd.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) {
                BottomSheetBehavior<FrameLayout> behavior = BottomSheetBehavior.from(bottomSheet);
                behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
                behavior.setSkipCollapsed(true);
            }
        });
        return dialog;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = BottomSheetExportImagesBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        if (getArguments() != null) {
            pdfUri = getArguments().getParcelable(ARG_PDF_URI);
            documentTitle = getArguments().getString(ARG_DOCUMENT_TITLE, "Document");
        }

        if (pdfUri == null) {
            Toast.makeText(requireContext(), R.string.pdf_load_error, Toast.LENGTH_SHORT).show();
            dismiss();
            return;
        }

        setupHeaderAndControls();
        inspectPdfAndLoadThumbnails();
    }

    private void setupHeaderAndControls() {
        binding.tvDocumentSubtitle.setText(documentTitle);
        binding.btnClose.setOnClickListener(v -> {
            if (!isExporting) {
                dismiss();
            }
        });

        binding.btnSelectAll.setOnClickListener(v -> {
            if (adapter == null || isExporting) return;
            if (adapter.isAllSelected()) {
                adapter.deselectAll();
            } else {
                adapter.selectAll();
            }
        });

        binding.btnSaveToDevice.setOnClickListener(v -> startExport(false));
        binding.btnShare.setOnClickListener(v -> startExport(true));
    }

    private void inspectPdfAndLoadThumbnails() {
        Context context = getContext();
        if (context == null) return;

        binding.pbGridLoading.setVisibility(View.VISIBLE);

        thumbnailExecutor.execute(() -> {
            ParcelFileDescriptor pfd = null;
            PdfRenderer renderer = null;
            try {
                pfd = openSeekablePfd(context, pdfUri);
                if (pfd == null) {
                    mainHandler.post(() -> onPdfLoadFailed("Cannot open PDF descriptor"));
                    return;
                }

                renderer = new PdfRenderer(pfd);
                totalPages = renderer.getPageCount();

                if (totalPages <= 0) {
                    mainHandler.post(() -> onPdfLoadFailed("PDF has 0 pages"));
                    return;
                }

                final int count = totalPages;
                final PdfRenderer finalRenderer = renderer;
                final ParcelFileDescriptor finalPfd = pfd;

                mainHandler.post(() -> setupGridUi(count));

                // Asynchronously render thumbnails
                for (int i = 0; i < count; i++) {
                    if (isExporting || isRemoving() || !isAdded()) break;

                    PdfRenderer.Page page = null;
                    Bitmap thumb = null;
                    try {
                        page = finalRenderer.openPage(i);
                        int targetW = 160;
                        int targetH = Math.max(1, (int) (((float) page.getHeight() / Math.max(1, page.getWidth())) * targetW));
                        thumb = PdfRendererHelper.renderPageWithWhiteBackground(
                                page, targetW, targetH, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    } catch (Exception e) {
                        Log.w(TAG, "Error rendering thumbnail for page " + i, e);
                    } finally {
                        if (page != null) {
                            try { page.close(); } catch (Exception ignored) {}
                        }
                    }

                    if (thumb != null) {
                        final int pageIdx = i;
                        final Bitmap finalThumb = thumb;
                        mainHandler.post(() -> {
                            if (adapter != null) {
                                adapter.setThumbnail(pageIdx, finalThumb);
                            } else {
                                finalThumb.recycle();
                            }
                        });
                    }
                }

            } catch (Exception e) {
                Log.e(TAG, "Failed to inspect PDF", e);
                mainHandler.post(() -> onPdfLoadFailed(e.getMessage()));
            } finally {
                if (renderer != null) {
                    try { renderer.close(); } catch (Exception ignored) {}
                }
                if (pfd != null) {
                    try { pfd.close(); } catch (Exception ignored) {}
                }
            }
        });
    }

    private void setupGridUi(int count) {
        if (!isAdded() || binding == null) return;

        binding.pbGridLoading.setVisibility(View.GONE);

        String pagesStr = count == 1 ? "1 page" : count + " pages";
        binding.tvDocumentSubtitle.setText(String.format(Locale.getDefault(), "%s • %s", documentTitle, pagesStr));

        int spanCount = getResources().getInteger(R.integer.export_grid_columns);
        binding.rvPages.setLayoutManager(new GridLayoutManager(requireContext(), spanCount));

        // Adjust RecyclerView height: wrap content if <= spanCount, fixed scrollable height otherwise
        float density = getResources().getDisplayMetrics().density;
        boolean isLandscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        if (count <= spanCount) {
            binding.rvPages.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
        } else {
            binding.rvPages.getLayoutParams().height = (int) ((isLandscape ? 160 : 220) * density);
        }

        adapter = new ExportPageAdapter(count, (selectedCount, total) -> updateSelectionUi(selectedCount, total));
        binding.rvPages.setAdapter(adapter);

        updateSelectionUi(count, count);
    }

    private void updateSelectionUi(int selectedCount, int total) {
        if (binding == null) return;

        if (total == 1) {
            binding.tvSelectionCounter.setText(selectedCount == 1
                    ? getString(R.string.export_images_counter_singular)
                    : getString(R.string.export_images_no_selection));
        } else {
            binding.tvSelectionCounter.setText(getString(R.string.export_images_counter, selectedCount, total));
        }

        boolean allSelected = selectedCount == total && total > 0;
        binding.btnSelectAll.setText(allSelected ? R.string.export_images_deselect_all : R.string.export_images_select_all);

        boolean hasSelection = selectedCount > 0;
        binding.btnSaveToDevice.setEnabled(hasSelection && !isExporting);
        binding.btnShare.setEnabled(hasSelection && !isExporting);
        binding.btnSaveToDevice.setAlpha(hasSelection ? 1.0f : 0.45f);
        binding.btnShare.setAlpha(hasSelection ? 1.0f : 0.45f);
    }

    private void onPdfLoadFailed(String reason) {
        if (!isAdded() || binding == null) return;
        binding.pbGridLoading.setVisibility(View.GONE);
        Toast.makeText(requireContext(), R.string.pdf_load_error, Toast.LENGTH_SHORT).show();
        dismiss();
    }

    private void startExport(boolean isShareMode) {
        if (adapter == null || isExporting) return;

        Set<Integer> selectedPages = adapter.getSelectedPages();
        if (selectedPages.isEmpty()) {
            Toast.makeText(requireContext(), R.string.export_images_no_selection, Toast.LENGTH_SHORT).show();
            return;
        }

        boolean isPng = binding.toggleFormat.getCheckedButtonId() == R.id.btnFormatPng;
        Bitmap.CompressFormat format = isPng ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG;
        int quality = isPng ? 100 : 95;
        String extension = isPng ? ".png" : ".jpg";
        String mimeType = isPng ? "image/png" : "image/jpeg";

        isExporting = true;
        setCancelable(false);
        binding.btnClose.setEnabled(false);
        binding.layoutActions.setVisibility(View.GONE);
        binding.layoutProgress.setVisibility(View.VISIBLE);
        binding.progressExport.setIndeterminate(false);
        binding.progressExport.setMax(selectedPages.size());
        binding.progressExport.setProgress(0);

        Context context = requireContext().getApplicationContext();
        Activity activity = getActivity();

        String rawBaseName = documentTitle;
        if (rawBaseName.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            rawBaseName = rawBaseName.substring(0, rawBaseName.length() - 4);
        }
        rawBaseName = rawBaseName.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (rawBaseName.isEmpty()) {
            rawBaseName = "Scanned_Document";
        }
        final String baseName = rawBaseName;

        exportExecutor.execute(() -> {
            ParcelFileDescriptor pfd = null;
            PdfRenderer renderer = null;
            ArrayList<Uri> savedUris = new ArrayList<>();
            ArrayList<Uri> shareUris = new ArrayList<>();
            boolean success = false;

            try {
                pfd = openSeekablePfd(context, pdfUri);
                if (pfd != null) {
                    renderer = new PdfRenderer(pfd);
                    int currentStep = 0;
                    int totalSteps = selectedPages.size();

                    for (int pageIdx : selectedPages) {
                        currentStep++;
                        final int step = currentStep;
                        mainHandler.post(() -> {
                            if (binding != null) {
                                binding.progressExport.setProgress(step);
                                binding.tvProgressStatus.setText(
                                        getString(R.string.export_images_progress, step, totalSteps));
                            }
                        });

                        PdfRenderer.Page page = renderer.openPage(pageIdx);
                        Bitmap highResBmp = null;
                        try {
                            // Calculate crisp, print-quality target resolution
                            int targetW = Math.min(2400, Math.max(page.getWidth() * 2, 1200));
                            int targetH = Math.round((float) targetW * page.getHeight() / Math.max(1, page.getWidth()));

                            try {
                                highResBmp = PdfRendererHelper.renderPageWithWhiteBackground(
                                        page, targetW, targetH, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
                            } catch (OutOfMemoryError oom) {
                                try {
                                    highResBmp = PdfRendererHelper.renderPageWithWhiteBackground(
                                            page, Math.max(1, targetW / 2), Math.max(1, targetH / 2),
                                            PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                                } catch (Throwable ignored) {}
                            }

                            if (highResBmp != null) {
                                String fileName = (totalPages == 1)
                                        ? baseName + extension
                                        : String.format(Locale.US, "%s_Page_%d%s", baseName, pageIdx + 1, extension);

                                if (isShareMode) {
                                    Uri shareUri = StorageHelper.saveBitmapToShareCache(
                                            context, highResBmp, fileName, format, quality);
                                    if (shareUri != null) {
                                        shareUris.add(shareUri);
                                    }
                                } else {
                                    Uri savedUri = StorageHelper.saveBitmapToPublicPictures(
                                            context, highResBmp, fileName, format, quality, mimeType);
                                    if (savedUri != null) {
                                        savedUris.add(savedUri);
                                    }
                                }
                            }
                        } finally {
                            if (highResBmp != null && !highResBmp.isRecycled()) {
                                highResBmp.recycle();
                            }
                            page.close();
                        }
                    }
                    success = isShareMode ? !shareUris.isEmpty() : !savedUris.isEmpty();
                }
            } catch (Exception e) {
                Log.e(TAG, "Export failed", e);
            } finally {
                if (renderer != null) {
                    try { renderer.close(); } catch (Exception ignored) {}
                }
                if (pfd != null) {
                    try { pfd.close(); } catch (Exception ignored) {}
                }
            }

            final boolean finalSuccess = success;
            mainHandler.post(() -> {
                isExporting = false;
                if (!isAdded()) return;

                if (!finalSuccess) {
                    setCancelable(true);
                    binding.btnClose.setEnabled(true);
                    binding.layoutProgress.setVisibility(View.GONE);
                    binding.layoutActions.setVisibility(View.VISIBLE);
                    Toast.makeText(context, R.string.export_images_error, Toast.LENGTH_SHORT).show();
                    return;
                }

                dismiss();

                if (isShareMode) {
                    launchSystemShareSheet(context, shareUris, mimeType);
                } else {
                    showSavedConfirmation(activity, context, savedUris, mimeType);
                }
            });
        });
    }

    private void launchSystemShareSheet(Context context, ArrayList<Uri> shareUris, String mimeType) {
        if (shareUris.isEmpty()) return;

        Intent shareIntent = new Intent();
        if (shareUris.size() == 1) {
            shareIntent.setAction(Intent.ACTION_SEND);
            shareIntent.putExtra(Intent.EXTRA_STREAM, shareUris.get(0));
        } else {
            shareIntent.setAction(Intent.ACTION_SEND_MULTIPLE);
            shareIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, shareUris);
        }
        shareIntent.setType(mimeType);
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        Intent chooser = Intent.createChooser(shareIntent, getString(R.string.export_images_btn_share));
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(chooser);
    }

    private void showSavedConfirmation(Activity activity, Context context, ArrayList<Uri> savedUris, String mimeType) {
        int count = savedUris.size();
        String toastMsg = count == 1
                ? context.getString(R.string.export_images_success_singular)
                : context.getString(R.string.export_images_success, count);
        Toast.makeText(context, toastMsg, Toast.LENGTH_LONG).show();

        if (activity != null && !activity.isFinishing()) {
            View rootView = activity.findViewById(android.R.id.content);
            if (rootView != null) {
                Snackbar snackbar = Snackbar.make(rootView, toastMsg, Snackbar.LENGTH_LONG);
                if (!savedUris.isEmpty() && savedUris.get(0) != null) {
                    final Uri firstUri = savedUris.get(0);
                    snackbar.setAction(R.string.action_view, v -> {
                        Intent viewIntent = new Intent(Intent.ACTION_VIEW);
                        viewIntent.setDataAndType(firstUri, mimeType);
                        viewIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        try {
                            activity.startActivity(Intent.createChooser(viewIntent, "View Image"));
                        } catch (Exception e) {
                            try {
                                Intent galleryIntent = new Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
                                activity.startActivity(galleryIntent);
                            } catch (Exception ignored) {}
                        }
                    });
                    snackbar.setActionTextColor(ContextCompat.getColor(activity, R.color.accent_mint));
                }
                snackbar.show();
            }
        }
    }

    @Nullable
    private ParcelFileDescriptor openSeekablePfd(Context context, Uri uri) {
        try {
            if ("file".equalsIgnoreCase(uri.getScheme()) && uri.getPath() != null) {
                File file = new File(uri.getPath());
                if (file.exists() && file.canRead()) {
                    return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
                }
            }

            // Attempt direct open
            ParcelFileDescriptor pfd = null;
            try {
                pfd = context.getContentResolver().openFileDescriptor(uri, "r");
            } catch (Exception ignored) {}

            if (pfd != null) {
                return pfd;
            }

            // Fallback: Copy to temporary seekable cache file for SAF or content providers
            tempSeekablePdf = new File(context.getCacheDir(), "export_pfd_" + UUID.randomUUID() + ".pdf");
            try (InputStream in = context.getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(tempSeekablePdf)) {
                if (in == null) return null;
                byte[] buffer = new byte[16384];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
                out.flush();
                return ParcelFileDescriptor.open(tempSeekablePdf, ParcelFileDescriptor.MODE_READ_ONLY);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to open seekable PFD for " + uri, e);
            return null;
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        thumbnailExecutor.shutdownNow();
        exportExecutor.shutdownNow();

        if (adapter != null) {
            adapter.recycleAllBitmaps();
        }

        if (tempSeekablePdf != null && tempSeekablePdf.exists()) {
            try { tempSeekablePdf.delete(); } catch (Exception ignored) {}
        }
        binding = null;
    }
}
