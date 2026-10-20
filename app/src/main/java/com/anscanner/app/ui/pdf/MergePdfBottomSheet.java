package com.anscanner.app.ui.pdf;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.anscanner.app.R;
import com.anscanner.app.data.entity.DocumentEntity;
import com.anscanner.app.databinding.BottomSheetMergePdfBinding;
import com.anscanner.app.databinding.ItemMergePdfDocumentBinding;
import com.anscanner.app.util.HapticHelper;
import com.anscanner.app.util.PdfToolsHelper;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.snackbar.Snackbar;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Modern Material Bottom Sheet for reordering and merging multiple PDF documents.
 * Features:
 * <ul>
 *   <li>Visual list of PDFs with file name, page count, and size</li>
 *   <li>Native drag handles for touch reordering sequence</li>
 *   <li>Custom title input with sensible default (e.g. Merged_2026-10-19)</li>
 *   <li>Lossless stream-based PDF compilation in background thread</li>
 *   <li>Direct library registration into Room database and Scoped Storage</li>
 * </ul>
 */
public class MergePdfBottomSheet extends BottomSheetDialogFragment {

    private static final String TAG = "MergePdfBottomSheet";
    private static final String ARG_URIS = "arg_uris";
    private static final String ARG_TITLES = "arg_titles";
    private static final String ARG_PAGE_COUNTS = "arg_page_counts";
    private static final String ARG_FILE_SIZES = "arg_file_sizes";

    public interface OnPdfMergedListener {
        void onPdfMergedSuccess();
    }

    public static class MergeItem {
        public final Uri uri;
        public final String title;
        public int pageCount;
        public long fileSize;

        public MergeItem(@NonNull Uri uri, @NonNull String title, int pageCount, long fileSize) {
            this.uri = uri;
            this.title = title;
            this.pageCount = pageCount;
            this.fileSize = fileSize;
        }
    }

    private BottomSheetMergePdfBinding binding;
    private final List<MergeItem> mergeItems = new ArrayList<>();
    private MergeItemAdapter adapter;
    private ItemTouchHelper itemTouchHelper;

    private boolean isMerging = false;
    private final ExecutorService processExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static MergePdfBottomSheet newInstance(
            @NonNull ArrayList<Uri> uris,
            @NonNull ArrayList<String> titles) {
        MergePdfBottomSheet fragment = new MergePdfBottomSheet();
        Bundle args = new Bundle();
        args.putParcelableArrayList(ARG_URIS, uris);
        args.putStringArrayList(ARG_TITLES, titles);
        fragment.setArguments(args);
        return fragment;
    }

    public static MergePdfBottomSheet newInstanceFromDocuments(@NonNull List<DocumentEntity> docs) {
        MergePdfBottomSheet fragment = new MergePdfBottomSheet();
        Bundle args = new Bundle();
        ArrayList<Uri> uris = new ArrayList<>();
        ArrayList<String> titles = new ArrayList<>();
        int[] pageCounts = new int[docs.size()];
        long[] fileSizes = new long[docs.size()];

        for (int i = 0; i < docs.size(); i++) {
            DocumentEntity d = docs.get(i);
            uris.add(d.fileUri != null ? Uri.parse(d.fileUri) : Uri.EMPTY);
            titles.add(d.title != null ? d.title : "Document");
            pageCounts[i] = d.pageCount;
            fileSizes[i] = d.fileSizeBytes;
        }

        args.putParcelableArrayList(ARG_URIS, uris);
        args.putStringArrayList(ARG_TITLES, titles);
        args.putIntArray(ARG_PAGE_COUNTS, pageCounts);
        args.putLongArray(ARG_FILE_SIZES, fileSizes);
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
        binding = BottomSheetMergePdfBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        parseArguments();

        if (mergeItems.size() < 2) {
            Toast.makeText(requireContext(), R.string.merge_min_files_error, Toast.LENGTH_SHORT).show();
            dismiss();
            return;
        }

        // Set default merged file name: "Merged_YYYY-MM-DD"
        String defaultName = "Merged_" + new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
        binding.etFileName.setText(defaultName);
        if (binding.etFileName.getText() != null) {
            binding.etFileName.setSelection(binding.etFileName.getText().length());
        }

        binding.btnClose.setOnClickListener(v -> dismiss());
        binding.btnMerge.setOnClickListener(v -> executeMerge());

        setupRecyclerView();
        updateMergeButtonState();
    }

    private void parseArguments() {
        mergeItems.clear();
        Bundle args = getArguments();
        if (args == null) return;

        ArrayList<Uri> uris = args.getParcelableArrayList(ARG_URIS);
        ArrayList<String> titles = args.getStringArrayList(ARG_TITLES);
        int[] pageCounts = args.getIntArray(ARG_PAGE_COUNTS);
        long[] fileSizes = args.getLongArray(ARG_FILE_SIZES);

        if (uris != null && titles != null) {
            int n = Math.min(uris.size(), titles.size());
            for (int i = 0; i < n; i++) {
                Uri u = uris.get(i);
                if (u == null || Uri.EMPTY.equals(u)) continue;
                String t = titles.get(i);
                int pc = (pageCounts != null && i < pageCounts.length) ? pageCounts[i] : 0;
                long fs = (fileSizes != null && i < fileSizes.length) ? fileSizes[i] : 0;
                mergeItems.add(new MergeItem(u, t, pc, fs));
            }
        }
    }

    private void setupRecyclerView() {
        adapter = new MergeItemAdapter(mergeItems, (holder) -> {
            if (itemTouchHelper != null) {
                itemTouchHelper.startDrag(holder);
            }
        }, (position) -> {
            if (position >= 0 && position < mergeItems.size()) {
                mergeItems.remove(position);
                adapter.notifyItemRemoved(position);
                updateMergeButtonState();
                if (mergeItems.size() < 2) {
                    Toast.makeText(requireContext(), R.string.merge_min_files_error, Toast.LENGTH_SHORT).show();
                }
            }
        });

        binding.rvMergeDocs.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.rvMergeDocs.setAdapter(adapter);

        // Native ItemTouchHelper for drag reordering
        ItemTouchHelper.SimpleCallback callback = new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView,
                                  @NonNull RecyclerView.ViewHolder viewHolder,
                                  @NonNull RecyclerView.ViewHolder target) {
                int from = viewHolder.getBindingAdapterPosition();
                int to = target.getBindingAdapterPosition();
                if (from != RecyclerView.NO_POSITION && to != RecyclerView.NO_POSITION) {
                    Collections.swap(mergeItems, from, to);
                    adapter.notifyItemMoved(from, to);
                    return true;
                }
                return false;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                // No swipe to dismiss
            }

            @Override
            public boolean isLongPressDragEnabled() {
                return true;
            }
        };

        itemTouchHelper = new ItemTouchHelper(callback);
        itemTouchHelper.attachToRecyclerView(binding.rvMergeDocs);

        // Async populate page counts and sizes if not already present
        fetchMissingMetadata();
    }

    private void fetchMissingMetadata() {
        processExecutor.execute(() -> {
            Context ctx = getContext();
            if (ctx == null) return;
            Context appContext = ctx.getApplicationContext();

            boolean changed = false;
            for (MergeItem item : mergeItems) {
                if (item.pageCount <= 0) {
                    int pages = PdfToolsHelper.getPdfPageCount(appContext, item.uri);
                    if (pages > 0) {
                        item.pageCount = pages;
                        changed = true;
                    }
                }
            }

            if (changed) {
                mainHandler.post(() -> {
                    if (adapter != null) adapter.notifyDataSetChanged();
                });
            }
        });
    }

    private void updateMergeButtonState() {
        if (binding == null) return;
        boolean canMerge = mergeItems.size() >= 2 && !isMerging;
        binding.btnMerge.setEnabled(canMerge);
    }

    private void setMergingUi(boolean merging) {
        this.isMerging = merging;
        setCancelable(!merging);
        if (binding == null) return;

        binding.layoutProgress.setVisibility(merging ? View.VISIBLE : View.GONE);
        binding.btnMerge.setEnabled(!merging && mergeItems.size() >= 2);
        binding.btnClose.setEnabled(!merging);
        binding.etFileName.setEnabled(!merging);
    }

    private void executeMerge() {
        if (mergeItems.size() < 2) {
            Toast.makeText(requireContext(), R.string.merge_min_files_error, Toast.LENGTH_SHORT).show();
            return;
        }

        String userTitle = binding.etFileName.getText() != null ? binding.etFileName.getText().toString().trim() : "";
        if (userTitle.isEmpty()) {
            userTitle = "Merged_" + new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
        }
        userTitle = userTitle.replaceAll("(?i)\\.pdf$", "").trim();
        final String finalTitle = userTitle;

        List<Uri> sourceUris = new ArrayList<>();
        for (MergeItem item : mergeItems) {
            sourceUris.add(item.uri);
        }

        setMergingUi(true);
        Context appContext = requireContext().getApplicationContext();
        Activity activity = getActivity();

        processExecutor.execute(() -> {
            File tempOutputFile = new File(appContext.getCacheDir(), "merged_" + UUID.randomUUID() + ".pdf");
            boolean success = false;
            DocumentEntity createdDoc = null;

            try {
                success = PdfToolsHelper.mergePdfs(appContext, sourceUris, tempOutputFile);
                if (success && tempOutputFile.exists() && tempOutputFile.length() > 0) {
                    int mergedPages = PdfToolsHelper.getPdfPageCount(appContext, Uri.fromFile(tempOutputFile));
                    if (mergedPages <= 0) {
                        for (MergeItem item : mergeItems) {
                            mergedPages += Math.max(1, item.pageCount);
                        }
                    }
                    createdDoc = PdfToolsHelper.commitPdfToLibrary(appContext, tempOutputFile, finalTitle, mergedPages);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error merging PDFs", e);
            } finally {
                if (tempOutputFile.exists()) {
                    tempOutputFile.delete();
                }
            }

            final DocumentEntity finalCreated = createdDoc;
            final boolean finalSuccess = success && finalCreated != null;

            mainHandler.post(() -> {
                if (finalSuccess) {
                    notifyCallerListener();
                }
                if (!isAdded()) return;
                setMergingUi(false);

                if (finalSuccess) {
                    dismiss();

                    if (activity != null && !activity.isFinishing()) {
                        View root = activity.findViewById(android.R.id.content);
                        if (root != null) {
                            HapticHelper.confirmTap(root);
                            Snackbar snackbar = Snackbar.make(root, R.string.merge_success, Snackbar.LENGTH_LONG);
                            snackbar.setAction(R.string.action_open_file, v -> {
                                Intent viewIntent = new Intent(activity, PdfViewerActivity.class);
                                viewIntent.putExtra(PdfViewerActivity.EXTRA_PDF_URI, finalCreated.fileUri);
                                viewIntent.putExtra(PdfViewerActivity.EXTRA_DOCUMENT_TITLE, finalCreated.title);
                                com.anscanner.app.service.AdCacheManager.getInstance().showInterstitial(activity, () -> {
                                    activity.startActivity(viewIntent);
                                });
                            });
                            snackbar.setActionTextColor(ContextCompat.getColor(activity, R.color.accent_mint));
                            snackbar.show();
                        }
                    }
                } else {
                    Toast.makeText(appContext, R.string.merge_error, Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void notifyCallerListener() {
        if (getParentFragment() instanceof OnPdfMergedListener) {
            ((OnPdfMergedListener) getParentFragment()).onPdfMergedSuccess();
        } else if (getActivity() instanceof OnPdfMergedListener) {
            ((OnPdfMergedListener) getActivity()).onPdfMergedSuccess();
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
        processExecutor.shutdown();
    }

    // ── Adapter for Reorderable Merge List ────────────────────────────────

    interface OnStartDragListener {
        void onStartDrag(RecyclerView.ViewHolder holder);
    }

    interface OnRemoveItemListener {
        void onRemove(int position);
    }

    private static class MergeItemAdapter extends RecyclerView.Adapter<MergeItemAdapter.ViewHolder> {

        private final List<MergeItem> items;
        private final OnStartDragListener dragListener;
        private final OnRemoveItemListener removeListener;

        MergeItemAdapter(List<MergeItem> items, OnStartDragListener dragListener, OnRemoveItemListener removeListener) {
            this.items = items;
            this.dragListener = dragListener;
            this.removeListener = removeListener;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ItemMergePdfDocumentBinding b = ItemMergePdfDocumentBinding.inflate(
                    LayoutInflater.from(parent.getContext()), parent, false);
            return new ViewHolder(b);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            holder.bind(items.get(position));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            private final ItemMergePdfDocumentBinding b;

            ViewHolder(ItemMergePdfDocumentBinding b) {
                super(b.getRoot());
                this.b = b;

                b.ivDragHandle.setOnTouchListener((v, event) -> {
                    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        dragListener.onStartDrag(ViewHolder.this);
                    }
                    return false;
                });

                b.btnRemove.setOnClickListener(v -> {
                    int pos = getBindingAdapterPosition();
                    if (pos != RecyclerView.NO_POSITION) {
                        removeListener.onRemove(pos);
                    }
                });
            }

            void bind(MergeItem item) {
                b.tvDocTitle.setText(item.title);

                StringBuilder sb = new StringBuilder();
                if (item.pageCount > 0) {
                    sb.append(item.pageCount).append(item.pageCount == 1 ? " page" : " pages");
                }
                if (item.fileSize > 0) {
                    if (sb.length() > 0) sb.append(" • ");
                    sb.append(Formatter.formatFileSize(b.getRoot().getContext(), item.fileSize));
                }
                if (sb.length() == 0) {
                    sb.append("PDF Document");
                }
                b.tvDocDetails.setText(sb.toString());
            }
        }
    }
}
