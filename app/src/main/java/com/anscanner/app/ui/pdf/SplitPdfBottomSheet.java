package com.anscanner.app.ui.pdf;


import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
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
import androidx.recyclerview.widget.RecyclerView;

import com.anscanner.app.R;
import com.anscanner.app.data.entity.DocumentEntity;
import com.anscanner.app.databinding.BottomSheetSplitPdfBinding;
import com.anscanner.app.databinding.ItemSplitPageThumbnailBinding;
import com.anscanner.app.util.PdfRendererHelper;
import com.anscanner.app.util.PdfToolsHelper;
import com.anscanner.app.util.HapticHelper;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.snackbar.Snackbar;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Modern Material Bottom Sheet for splitting and extracting PDF pages.
 * Supports:
 * <ul>
 *   <li>Visual thumbnail grid with individual page selection</li>
 *   <li>Extracting selected pages into a single new PDF document</li>
 *   <li>Splitting the entire document into individual 1-page PDF files</li>
 *   <li>Seamless integration with Room database and Scoped Storage</li>
 * </ul>
 */
public class SplitPdfBottomSheet extends BottomSheetDialogFragment {

    private static final String TAG = "SplitPdfBottomSheet";
    private static final String ARG_PDF_URI = "arg_pdf_uri";
    private static final String ARG_DOCUMENT_TITLE = "arg_document_title";

    public interface OnPdfSplitListener {
        void onPdfSplitSuccess();
    }

    private BottomSheetSplitPdfBinding binding;
    private Uri pdfUri;
    private String documentTitle = "Document";
    private int totalPages = 0;

    private SplitPageAdapter adapter;
    private File tempSeekablePdf = null;
    private boolean isProcessing = false;

    private final ExecutorService thumbnailExecutor = Executors.newFixedThreadPool(2);
    private final ExecutorService processExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static SplitPdfBottomSheet newInstance(@NonNull Uri pdfUri, @Nullable String documentTitle) {
        SplitPdfBottomSheet fragment = new SplitPdfBottomSheet();
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
        binding = BottomSheetSplitPdfBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        if (getArguments() != null) {
            pdfUri = getArguments().getParcelable(ARG_PDF_URI);
            String title = getArguments().getString(ARG_DOCUMENT_TITLE);
            if (title != null && !title.trim().isEmpty()) {
                documentTitle = title.trim();
            }
        }

        if (pdfUri == null) {
            Toast.makeText(requireContext(), R.string.pdf_load_error, Toast.LENGTH_SHORT).show();
            dismiss();
            return;
        }

        binding.btnClose.setOnClickListener(v -> dismiss());
        binding.btnSelectAll.setOnClickListener(v -> toggleSelectAll());

        binding.btnExtractPages.setOnClickListener(v -> extractSelectedPages());
        binding.btnSplitAll.setOnClickListener(v -> splitIntoSinglePages());

        setupRecyclerView();
        loadPdfDocument();
    }

    private void setupRecyclerView() {
        adapter = new SplitPageAdapter(requireContext(), () -> updateSelectionState());
        binding.rvPages.setLayoutManager(new GridLayoutManager(requireContext(), 3));
        binding.rvPages.setAdapter(adapter);
    }

    private void loadPdfDocument() {
        binding.pbGridLoading.setVisibility(View.VISIBLE);

        processExecutor.execute(() -> {
            Context context = getContext();
            if (context == null) return;

            tempSeekablePdf = resolveUriToTempFile(context, pdfUri);
            if (tempSeekablePdf == null || !tempSeekablePdf.exists()) {
                mainHandler.post(() -> {
                    if (binding == null) return;
                    binding.pbGridLoading.setVisibility(View.GONE);
                    Toast.makeText(context, R.string.pdf_load_error, Toast.LENGTH_SHORT).show();
                    dismiss();
                });
                return;
            }

            int count = 0;
            ParcelFileDescriptor pfd = null;
            PdfRenderer renderer = null;
            try {
                pfd = ParcelFileDescriptor.open(tempSeekablePdf, ParcelFileDescriptor.MODE_READ_ONLY);
                renderer = new PdfRenderer(pfd);
                count = renderer.getPageCount();
            } catch (Exception e) {
                Log.e(TAG, "Failed to read PDF page count", e);
            } finally {
                if (renderer != null) {
                    try { renderer.close(); } catch (Exception ignored) {}
                }
                if (pfd != null) {
                    try { pfd.close(); } catch (Exception ignored) {}
                }
            }

            final int resolvedCount = count;
            mainHandler.post(() -> {
                if (binding == null) return;
                binding.pbGridLoading.setVisibility(View.GONE);
                if (resolvedCount <= 0) {
                    Toast.makeText(context, R.string.pdf_load_error, Toast.LENGTH_SHORT).show();
                    dismiss();
                    return;
                }

                totalPages = resolvedCount;
                String subtitle = documentTitle + " • " + totalPages + (totalPages == 1 ? " page" : " pages");
                binding.tvDocumentSubtitle.setText(subtitle);

                adapter.setPageCount(totalPages);
                updateSelectionState();
            });
        });
    }

    private void toggleSelectAll() {
        if (adapter == null) return;
        boolean allSelected = adapter.getSelectedCount() == totalPages;
        if (allSelected) {
            adapter.clearSelection();
        } else {
            adapter.selectAll();
        }
        updateSelectionState();
    }

    private void updateSelectionState() {
        if (binding == null || adapter == null) return;
        int selected = adapter.getSelectedCount();

        if (totalPages == 1) {
            binding.tvSelectionCounter.setText(getString(R.string.export_images_counter_singular));
        } else {
            binding.tvSelectionCounter.setText(getString(R.string.export_images_counter, selected, totalPages));
        }

        boolean allSelected = (selected == totalPages && totalPages > 0);
        binding.btnSelectAll.setText(allSelected ? R.string.export_images_deselect_all : R.string.export_images_select_all);

        binding.btnExtractPages.setEnabled(selected > 0 && !isProcessing);
        binding.btnSplitAll.setEnabled(totalPages > 1 && !isProcessing);
    }

    private void setProcessingUi(boolean processing, int statusStringRes) {
        this.isProcessing = processing;
        if (binding == null) return;

        binding.layoutProgress.setVisibility(processing ? View.VISIBLE : View.GONE);
        if (statusStringRes != 0) {
            binding.tvProgressStatus.setText(statusStringRes);
        }

        binding.btnExtractPages.setEnabled(!processing && adapter != null && adapter.getSelectedCount() > 0);
        binding.btnSplitAll.setEnabled(!processing && totalPages > 1);
        binding.btnSelectAll.setEnabled(!processing);
        binding.btnClose.setEnabled(!processing);
    }

    private void extractSelectedPages() {
        if (adapter == null || adapter.getSelectedCount() == 0) {
            Toast.makeText(requireContext(), R.string.split_no_selection_error, Toast.LENGTH_SHORT).show();
            return;
        }

        List<Integer> selectedPages = new ArrayList<>(adapter.getSelectedPages());
        Collections.sort(selectedPages);

        setProcessingUi(true, R.string.split_progress_extracting);
        Context appContext = requireContext().getApplicationContext();
        Activity activity = getActivity();

        processExecutor.execute(() -> {
            File tempOutputFile = new File(appContext.getCacheDir(), "extracted_" + UUID.randomUUID() + ".pdf");
            boolean success = false;
            DocumentEntity createdDoc = null;

            try {
                success = PdfToolsHelper.extractPages(appContext, pdfUri, selectedPages, tempOutputFile);
                if (success && tempOutputFile.exists() && tempOutputFile.length() > 0) {
                    String cleanBase = sanitizeTitle(documentTitle);
                    String newTitle = cleanBase + "_Extracted";
                    createdDoc = PdfToolsHelper.commitPdfToLibrary(appContext, tempOutputFile, newTitle, selectedPages.size());
                }
            } catch (Exception e) {
                Log.e(TAG, "Error extracting pages", e);
            } finally {
                if (tempOutputFile.exists()) {
                    tempOutputFile.delete();
                }
            }

            final DocumentEntity finalDoc = createdDoc;
            final boolean finalSuccess = success && finalDoc != null;

            mainHandler.post(() -> {
                if (!isAdded()) return;
                setProcessingUi(false, 0);

                if (finalSuccess) {
                    notifyCallerListener();
                    dismiss();
                    if (activity != null && !activity.isFinishing()) {
                        View root = activity.findViewById(android.R.id.content);
                        if (root != null) {
                            HapticHelper.confirmTap(root);
                            Snackbar snackbar = Snackbar.make(root, R.string.split_success, Snackbar.LENGTH_LONG);
                            snackbar.setAction(R.string.action_open_file, v -> {
                                Intent viewIntent = new Intent(activity, PdfViewerActivity.class);
                                viewIntent.putExtra(PdfViewerActivity.EXTRA_PDF_URI, finalDoc.fileUri);
                                viewIntent.putExtra(PdfViewerActivity.EXTRA_DOCUMENT_TITLE, finalDoc.title);
                                com.anscanner.app.service.AdCacheManager.getInstance().showInterstitial(activity, () -> {
                                    activity.startActivity(viewIntent);
                                });
                            });
                            snackbar.setActionTextColor(ContextCompat.getColor(activity, R.color.accent_mint));
                            snackbar.show();
                        }
                    }
                } else {
                    Toast.makeText(appContext, R.string.split_error, Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void splitIntoSinglePages() {
        if (totalPages <= 1) return;

        setProcessingUi(true, R.string.split_progress_splitting);
        Context appContext = requireContext().getApplicationContext();
        Activity activity = getActivity();

        processExecutor.execute(() -> {
            File splitDir = new File(appContext.getCacheDir(), "split_" + UUID.randomUUID());
            List<DocumentEntity> createdDocs = new ArrayList<>();
            boolean overallSuccess = false;

            try {
                String cleanBase = sanitizeTitle(documentTitle);
                List<File> singleFiles = PdfToolsHelper.splitIntoSinglePages(appContext, pdfUri, splitDir, cleanBase);
                if (!singleFiles.isEmpty()) {
                    for (File singleFile : singleFiles) {
                        String titleWithoutExt = singleFile.getName().replace(".pdf", "");
                        DocumentEntity doc = PdfToolsHelper.commitPdfToLibrary(appContext, singleFile, titleWithoutExt, 1);
                        if (doc != null) {
                            createdDocs.add(doc);
                        }
                        singleFile.delete();
                    }
                    overallSuccess = !createdDocs.isEmpty();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error splitting document", e);
            } finally {
                if (splitDir.exists()) {
                    File[] remaining = splitDir.listFiles();
                    if (remaining != null) {
                        for (File r : remaining) r.delete();
                    }
                    splitDir.delete();
                }
            }

            final boolean finalSuccess = overallSuccess;
            final int countCreated = createdDocs.size();
            final DocumentEntity firstCreated = createdDocs.isEmpty() ? null : createdDocs.get(0);

            mainHandler.post(() -> {
                if (!isAdded()) return;
                setProcessingUi(false, 0);

                if (finalSuccess) {
                    notifyCallerListener();
                    dismiss();
                    if (activity != null && !activity.isFinishing()) {
                        View root = activity.findViewById(android.R.id.content);
                        if (root != null) {
                            HapticHelper.confirmTap(root);
                            String msg = getString(R.string.split_success_single, countCreated);
                            Snackbar snackbar = Snackbar.make(root, msg, Snackbar.LENGTH_LONG);
                            if (firstCreated != null) {
                                snackbar.setAction(R.string.action_open_file, v -> {
                                    Intent viewIntent = new Intent(activity, PdfViewerActivity.class);
                                    viewIntent.putExtra(PdfViewerActivity.EXTRA_PDF_URI, firstCreated.fileUri);
                                    viewIntent.putExtra(PdfViewerActivity.EXTRA_DOCUMENT_TITLE, firstCreated.title);
                                    com.anscanner.app.service.AdCacheManager.getInstance().showInterstitial(activity, () -> {
                                        activity.startActivity(viewIntent);
                                    });
                                });
                                snackbar.setActionTextColor(ContextCompat.getColor(activity, R.color.accent_mint));
                            }
                            snackbar.show();
                        }
                    }
                } else {
                    Toast.makeText(appContext, R.string.split_error, Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void notifyCallerListener() {
        if (getParentFragment() instanceof OnPdfSplitListener) {
            ((OnPdfSplitListener) getParentFragment()).onPdfSplitSuccess();
        } else if (getActivity() instanceof OnPdfSplitListener) {
            ((OnPdfSplitListener) getActivity()).onPdfSplitSuccess();
        }
    }

    private String sanitizeTitle(String title) {
        if (title == null) return "Document";
        String s = title.replaceAll("(?i)\\.pdf$", "").trim();
        return s.isEmpty() ? "Document" : s;
    }

    @Nullable
    private File resolveUriToTempFile(@NonNull Context context, @NonNull Uri uri) {
        if ("file".equalsIgnoreCase(uri.getScheme()) && uri.getPath() != null) {
            File directFile = new File(uri.getPath());
            if (directFile.exists() && directFile.canRead()) {
                return directFile;
            }
        }
        File temp = new File(context.getCacheDir(), "split_temp_" + UUID.randomUUID() + ".pdf");
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(temp)) {
            if (in == null) return null;
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            out.flush();
            return temp;
        } catch (Exception e) {
            Log.e(TAG, "Failed to copy uri to temp file", e);
            if (temp.exists()) temp.delete();
            return null;
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        thumbnailExecutor.shutdownNow();
        processExecutor.shutdown();
        if (tempSeekablePdf != null && tempSeekablePdf.getName().startsWith("split_temp_") && tempSeekablePdf.exists()) {
            tempSeekablePdf.delete();
            tempSeekablePdf = null;
        }
    }

    // ── Adapter for Page Selection Grid ──────────────────────────────────

    private class SplitPageAdapter extends RecyclerView.Adapter<SplitPageAdapter.PageViewHolder> {

        private final Context context;
        private final Runnable onSelectionChanged;
        private int count = 0;
        private final Set<Integer> selectedPages = new HashSet<>();

        SplitPageAdapter(Context context, Runnable onSelectionChanged) {
            this.context = context;
            this.onSelectionChanged = onSelectionChanged;
        }

        void setPageCount(int count) {
            this.count = count;
            selectedPages.clear();
            notifyDataSetChanged();
        }

        void selectAll() {
            selectedPages.clear();
            for (int i = 0; i < count; i++) {
                selectedPages.add(i);
            }
            notifyDataSetChanged();
            if (onSelectionChanged != null) onSelectionChanged.run();
        }

        void clearSelection() {
            selectedPages.clear();
            notifyDataSetChanged();
            if (onSelectionChanged != null) onSelectionChanged.run();
        }

        int getSelectedCount() {
            return selectedPages.size();
        }

        Set<Integer> getSelectedPages() {
            return selectedPages;
        }

        @NonNull
        @Override
        public PageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ItemSplitPageThumbnailBinding b = ItemSplitPageThumbnailBinding.inflate(
                    LayoutInflater.from(parent.getContext()), parent, false);
            return new PageViewHolder(b);
        }

        @Override
        public void onBindViewHolder(@NonNull PageViewHolder holder, int position) {
            holder.bind(position);
        }

        @Override
        public int getItemCount() {
            return count;
        }

        class PageViewHolder extends RecyclerView.ViewHolder {
            private final ItemSplitPageThumbnailBinding b;

            PageViewHolder(ItemSplitPageThumbnailBinding b) {
                super(b.getRoot());
                this.b = b;

                b.getRoot().setOnClickListener(v -> {
                    int pos = getBindingAdapterPosition();
                    if (pos != RecyclerView.NO_POSITION) {
                        if (selectedPages.contains(pos)) {
                            selectedPages.remove(pos);
                        } else {
                            selectedPages.add(pos);
                        }
                        updateSelectionUi(pos);
                        if (onSelectionChanged != null) onSelectionChanged.run();
                    }
                });
            }

            void bind(int pageIndex) {
                b.tvPageNumber.setText(String.valueOf(pageIndex + 1));
                updateSelectionUi(pageIndex);

                b.ivThumbnail.setImageBitmap(null);
                b.pbThumbnailLoading.setVisibility(View.VISIBLE);
                b.ivThumbnail.setTag(pageIndex);

                thumbnailExecutor.execute(() -> {
                    if (tempSeekablePdf == null || !tempSeekablePdf.exists()) return;
                    Bitmap thumb = null;
                    ParcelFileDescriptor pfd = null;
                    PdfRenderer renderer = null;
                    try {
                        pfd = ParcelFileDescriptor.open(tempSeekablePdf, ParcelFileDescriptor.MODE_READ_ONLY);
                        renderer = new PdfRenderer(pfd);
                        if (pageIndex < renderer.getPageCount()) {
                            PdfRenderer.Page page = renderer.openPage(pageIndex);
                            try {
                                int w = 240;
                                int h = Math.round((float) w * page.getHeight() / Math.max(1, page.getWidth()));
                                thumb = PdfRendererHelper.renderPageWithWhiteBackground(
                                        page, w, h, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                            } finally {
                                page.close();
                            }
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Thumbnail rendering failed for page " + pageIndex, e);
                    } finally {
                        if (renderer != null) {
                            try { renderer.close(); } catch (Exception ignored) {}
                        }
                        if (pfd != null) {
                            try { pfd.close(); } catch (Exception ignored) {}
                        }
                    }

                    final Bitmap finalThumb = thumb;
                    mainHandler.post(() -> {
                        if (b.ivThumbnail.getTag() != null && ((int) b.ivThumbnail.getTag()) == pageIndex) {
                            b.pbThumbnailLoading.setVisibility(View.GONE);
                            if (finalThumb != null) {
                                b.ivThumbnail.setImageBitmap(finalThumb);
                            }
                        }
                    });
                });
            }

            private void updateSelectionUi(int pageIndex) {
                boolean isSelected = selectedPages.contains(pageIndex);
                if (isSelected) {
                    b.cardPage.setStrokeColor(ContextCompat.getColor(context, R.color.accent_mint));
                    b.cardPage.setStrokeWidth((int) (2 * context.getResources().getDisplayMetrics().density));
                    b.viewUnselectedOverlay.setVisibility(View.GONE);
                    b.ivSelectionIndicator.setVisibility(View.VISIBLE);
                    b.ivSelectionIndicator.setBackgroundResource(R.drawable.bg_selection_circle_selected);
                    b.ivSelectionIndicator.setImageResource(R.drawable.ic_check);
                    b.ivSelectionIndicator.setImageTintList(ContextCompat.getColorStateList(context, R.color.text_on_accent));
                } else {
                    b.cardPage.setStrokeColor(ContextCompat.getColor(context, R.color.border_subtle));
                    b.cardPage.setStrokeWidth((int) (1 * context.getResources().getDisplayMetrics().density));
                    b.viewUnselectedOverlay.setVisibility(View.VISIBLE);
                    b.ivSelectionIndicator.setVisibility(View.VISIBLE);
                    b.ivSelectionIndicator.setBackgroundResource(R.drawable.bg_selection_circle_unselected);
                    b.ivSelectionIndicator.setImageDrawable(null);
                }
            }
        }
    }
}
