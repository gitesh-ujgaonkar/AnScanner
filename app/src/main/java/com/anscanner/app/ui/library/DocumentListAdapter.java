package com.anscanner.app.ui.library;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.anscanner.app.R;
import com.anscanner.app.data.entity.DocumentEntity;
import com.anscanner.app.databinding.ItemDateHeaderBinding;
import com.anscanner.app.databinding.ItemDocumentDraftBinding;
import com.anscanner.app.databinding.ItemDocumentRowBinding;
import com.anscanner.app.model.draft.DraftManager;
import java.io.File;
import com.anscanner.app.service.CacheManager;
import com.anscanner.app.service.PdfThumbnailLoader;

import android.os.Handler;
import android.os.Looper;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DocumentListAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    public static final String PAYLOAD_SELECTION = "payload_selection";

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_DOCUMENT = 1;
    private static final int TYPE_DRAFT = 2;

    private final ExecutorService thumbnailExecutor = Executors.newFixedThreadPool(2);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private List<Object> items = new ArrayList<>();
    private final Context context;
    private final DocumentClickListener listener;
    private final SimpleDateFormat dateFormat;

    private boolean isMultiSelectMode = false;
    private final LinkedHashMap<String, DocumentEntity> selectedDocuments = new LinkedHashMap<>();

    public interface DocumentClickListener {
        void onDocumentClick(DocumentEntity document);
        void onDocumentShare(DocumentEntity document);
        void onDocumentExportImages(DocumentEntity document);
        void onDocumentOrganizePages(DocumentEntity document);
        void onDocumentSplitPdf(DocumentEntity document);
        void onDocumentProperties(DocumentEntity document);
        void onDocumentRemoveAppOnly(DocumentEntity document);
        void onDocumentDeletePermanently(DocumentEntity document);
        void onSelectionModeChanged(boolean isMultiSelect, int selectedCount);
        default void onDraftClick(DraftManager.DraftSession draft) {}
        default void onDraftDeleteClick(DraftManager.DraftSession draft) {}
    }

    public DocumentListAdapter(Context context, DocumentClickListener listener) {
        this.context = context;
        this.listener = listener;
        this.dateFormat = new SimpleDateFormat("MMM dd", Locale.getDefault());
    }

    public boolean isMultiSelectMode() {
        return isMultiSelectMode;
    }

    public void startMultiSelect(@NonNull DocumentEntity initialDoc) {
        isMultiSelectMode = true;
        selectedDocuments.clear();
        if (initialDoc.fileUri != null) {
            selectedDocuments.put(initialDoc.fileUri, initialDoc);
        }
        notifyItemRangeChanged(0, getItemCount(), PAYLOAD_SELECTION);
        if (listener != null) {
            listener.onSelectionModeChanged(true, selectedDocuments.size());
        }
    }

    public void exitMultiSelect() {
        if (!isMultiSelectMode) return;
        isMultiSelectMode = false;
        selectedDocuments.clear();
        notifyItemRangeChanged(0, getItemCount(), PAYLOAD_SELECTION);
        if (listener != null) {
            listener.onSelectionModeChanged(false, 0);
        }
    }

    public void selectAllDocuments() {
        for (Object item : items) {
            if (item instanceof DocumentEntity) {
                DocumentEntity d = (DocumentEntity) item;
                if (d.fileUri != null) {
                    selectedDocuments.put(d.fileUri, d);
                }
            }
        }
        notifyItemRangeChanged(0, getItemCount(), PAYLOAD_SELECTION);
        if (listener != null) {
            listener.onSelectionModeChanged(true, selectedDocuments.size());
        }
    }

    public void clearSelection() {
        selectedDocuments.clear();
        notifyItemRangeChanged(0, getItemCount(), PAYLOAD_SELECTION);
        if (listener != null) {
            listener.onSelectionModeChanged(true, 0);
        }
    }

    public void toggleSelection(@NonNull DocumentEntity doc) {
        if (doc.fileUri == null) return;
        if (selectedDocuments.containsKey(doc.fileUri)) {
            selectedDocuments.remove(doc.fileUri);
        } else {
            selectedDocuments.put(doc.fileUri, doc);
        }
        int index = -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i) instanceof DocumentEntity) {
                DocumentEntity d = (DocumentEntity) items.get(i);
                if (doc.fileUri.equals(d.fileUri)) {
                    index = i;
                    break;
                }
            }
        }
        if (index != -1) {
            notifyItemChanged(index, PAYLOAD_SELECTION);
        } else {
            notifyItemRangeChanged(0, getItemCount(), PAYLOAD_SELECTION);
        }
        if (listener != null) {
            listener.onSelectionModeChanged(true, selectedDocuments.size());
        }
    }

    public List<DocumentEntity> getSelectedDocuments() {
        return new ArrayList<>(selectedDocuments.values());
    }

    public int getSelectedCount() {
        return selectedDocuments.size();
    }

    public List<Object> getItems() {
        return items;
    }

    public void updateItems(List<Object> newItems) {
        this.items.clear();
        this.items.addAll(newItems);
        notifyDataSetChanged();
    }

    /**
     * Removes a document from the adapter list and notifies the RecyclerView.
     * Also removes any empty section header left behind.
     */
    public int removeItem(DocumentEntity document) {
        int index = -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i) instanceof DocumentEntity) {
                DocumentEntity d = (DocumentEntity) items.get(i);
                if (d.id == document.id) {
                    index = i;
                    break;
                }
            }
        }
        if (index != -1) {
            items.remove(index);
            // If the preceding item is a section header and there are no other items under it, remove the header too
            int headerIndex = index - 1;
            boolean removeHeader = false;
            if (headerIndex >= 0 && items.get(headerIndex) instanceof String) {
                if (index >= items.size() || items.get(index) instanceof String) {
                    items.remove(headerIndex);
                    removeHeader = true;
                }
            }
            if (removeHeader) {
                notifyItemRangeRemoved(headerIndex, 2);
            } else {
                notifyItemRemoved(index);
            }
        }
        return index;
    }

    /**
     * Checks if there are any documents left in the adapter.
     */
    public boolean removeDraftItem() {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i) instanceof DraftManager.DraftSession) {
                items.remove(i);
                notifyItemRemoved(i);
                return true;
            }
        }
        return false;
    }

    public boolean isEmpty() {
        for (Object item : items) {
            if (item instanceof DocumentEntity || item instanceof DraftManager.DraftSession) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int getItemViewType(int position) {
        Object item = items.get(position);
        if (item instanceof String) {
            return TYPE_HEADER;
        } else if (item instanceof DraftManager.DraftSession) {
            return TYPE_DRAFT;
        }
        return TYPE_DOCUMENT;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_HEADER) {
            return new HeaderViewHolder(ItemDateHeaderBinding.inflate(inflater, parent, false));
        } else if (viewType == TYPE_DRAFT) {
            return new DraftViewHolder(ItemDocumentDraftBinding.inflate(inflater, parent, false));
        } else {
            return new DocumentViewHolder(ItemDocumentRowBinding.inflate(inflater, parent, false));
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof HeaderViewHolder) {
            ((HeaderViewHolder) holder).bind((String) items.get(position));
        } else if (holder instanceof DraftViewHolder) {
            ((DraftViewHolder) holder).bind((DraftManager.DraftSession) items.get(position));
        } else if (holder instanceof DocumentViewHolder) {
            ((DocumentViewHolder) holder).bind((DocumentEntity) items.get(position));
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position, @NonNull List<Object> payloads) {
        if (!payloads.isEmpty() && payloads.contains(PAYLOAD_SELECTION)) {
            if (holder instanceof DocumentViewHolder) {
                DocumentViewHolder docHolder = (DocumentViewHolder) holder;
                Object item = items.get(position);
                if (item instanceof DocumentEntity) {
                    DocumentEntity doc = (DocumentEntity) item;
                    boolean isSelected = isMultiSelectMode && doc.fileUri != null && selectedDocuments.containsKey(doc.fileUri);
                    docHolder.bindSelectionState(isSelected, isMultiSelectMode);
                }
            }
        } else {
            super.onBindViewHolder(holder, position, payloads);
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    class HeaderViewHolder extends RecyclerView.ViewHolder {
        private final ItemDateHeaderBinding binding;

        HeaderViewHolder(ItemDateHeaderBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(String header) {
            binding.tvDateHeader.setText(header);
        }
    }

    class DocumentViewHolder extends RecyclerView.ViewHolder {
        private final ItemDocumentRowBinding binding;

        DocumentViewHolder(ItemDocumentRowBinding binding) {
            super(binding.getRoot());
            this.binding = binding;

            binding.getRoot().setOnClickListener(v -> {
                int pos = getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION && items.get(pos) instanceof DocumentEntity) {
                    DocumentEntity doc = (DocumentEntity) items.get(pos);
                    if (isMultiSelectMode) {
                        toggleSelection(doc);
                    } else {
                        listener.onDocumentClick(doc);
                    }
                }
            });

            binding.btnMore.setOnClickListener(v -> {
                int pos = getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION && items.get(pos) instanceof DocumentEntity) {
                    showPopupMenu(binding.btnMore, (DocumentEntity) items.get(pos));
                }
            });

            binding.getRoot().setOnLongClickListener(v -> {
                int pos = getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION && items.get(pos) instanceof DocumentEntity) {
                    DocumentEntity doc = (DocumentEntity) items.get(pos);
                    if (!isMultiSelectMode) {
                        startMultiSelect(doc);
                        return true;
                    }
                }
                return false;
            });
        }

        private void showPopupMenu(android.view.View anchor, DocumentEntity document) {
            android.widget.PopupMenu popup = new android.widget.PopupMenu(anchor.getContext(), anchor);
            popup.getMenuInflater().inflate(R.menu.menu_document_item, popup.getMenu());

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                popup.setForceShowIcon(true);
            }

            // Make "Delete Permanently" item look destructive using colorError
            android.view.MenuItem deleteItem = popup.getMenu().findItem(R.id.action_delete_permanently);
            if (deleteItem != null) {
                android.util.TypedValue typedValue = new android.util.TypedValue();
                if (anchor.getContext().getTheme().resolveAttribute(com.google.android.material.R.attr.colorError, typedValue, true)) {
                    android.text.SpannableString span = new android.text.SpannableString(deleteItem.getTitle());
                    span.setSpan(new android.text.style.ForegroundColorSpan(typedValue.data), 0, span.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    deleteItem.setTitle(span);
                }
            }

            // Hide "Remove from App" for external PDFs (Tab 2)
            if (document.id < 0) {
                android.view.MenuItem removeItem = popup.getMenu().findItem(R.id.action_remove_from_app);
                if (removeItem != null) {
                    removeItem.setVisible(false);
                }
            }

            popup.setOnMenuItemClickListener(item -> {
                int id = item.getItemId();
                if (id == R.id.action_open) {
                    listener.onDocumentClick(document);
                    return true;
                } else if (id == R.id.action_share) {
                    listener.onDocumentShare(document);
                    return true;
                } else if (id == R.id.action_export_images) {
                    listener.onDocumentExportImages(document);
                    return true;
                } else if (id == R.id.action_organize_pages) {
                    listener.onDocumentOrganizePages(document);
                    return true;
                } else if (id == R.id.action_split_pdf) {
                    listener.onDocumentSplitPdf(document);
                    return true;
                } else if (id == R.id.action_properties) {
                    listener.onDocumentProperties(document);
                    return true;
                } else if (id == R.id.action_remove_from_app) {
                    listener.onDocumentRemoveAppOnly(document);
                    return true;
                } else if (id == R.id.action_delete_permanently) {
                    listener.onDocumentDeletePermanently(document);
                    return true;
                }
                return false;
            });
            popup.show();
        }

        void bind(DocumentEntity document) {
            binding.tvDocTitle.setText(document.title);
            binding.tvDocDate.setText(dateFormat.format(new Date(document.createdAt)));
            
            String pagesText;
            if (document.pageCount > 0) {
                pagesText = document.pageCount == 1 ? "1 page" : document.pageCount + " pages";
            } else if (document.fileSizeBytes > 0) {
                pagesText = android.text.format.Formatter.formatFileSize(context, document.fileSizeBytes);
            } else {
                pagesText = "PDF";
            }
            binding.tvDocPages.setText(pagesText);
            
            binding.tvDocFormat.setText(document.format.toUpperCase(Locale.getDefault()));

            boolean isSelected = isMultiSelectMode && document.fileUri != null && selectedDocuments.containsKey(document.fileUri);
            bindSelectionState(isSelected, isMultiSelectMode);

            // Safe thumbnail loading via PdfThumbnailLoader:
            // Prevents "BitmapDrawable created with null Bitmap" and handles SecurityException gracefully
            PdfThumbnailLoader.loadThumbnail(context, binding.ivDocThumb, document);
        }

        void bindSelectionState(boolean isSelected, boolean isMultiSelect) {
            if (isMultiSelect) {
                binding.cbSelected.setVisibility(View.VISIBLE);
                binding.cbSelected.setChecked(isSelected);
                binding.btnMore.setVisibility(View.GONE);
                binding.cardDocument.setStrokeColor(ContextCompat.getColor(context, isSelected ? R.color.accent_mint : R.color.border_subtle));
                binding.cardDocument.setStrokeWidth(isSelected ? (int) (2 * context.getResources().getDisplayMetrics().density) : (int) (1 * context.getResources().getDisplayMetrics().density));
            } else {
                binding.cbSelected.setVisibility(View.GONE);
                binding.btnMore.setVisibility(View.VISIBLE);
                binding.cardDocument.setStrokeColor(ContextCompat.getColor(context, R.color.border_subtle));
                binding.cardDocument.setStrokeWidth((int) (1 * context.getResources().getDisplayMetrics().density));
            }
        }
    }
    class DraftViewHolder extends RecyclerView.ViewHolder {
        private final ItemDocumentDraftBinding binding;
        private final SimpleDateFormat draftDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());

        DraftViewHolder(ItemDocumentDraftBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(DraftManager.DraftSession draft) {
            String formattedDate = draftDateFormat.format(new Date(draft.timestamp));
            binding.tvDraftTitle.setText("Draft - " + formattedDate);

            int pageCount = (draft.pagePaths != null) ? draft.pagePaths.size() : 1;
            String pagesText = pageCount + (pageCount == 1 ? " page" : " pages") + " · Unsaved";
            binding.tvDraftPages.setText(pagesText);

            String thumbPath = null;
            if (draft.pagePaths != null && !draft.pagePaths.isEmpty()) {
                thumbPath = draft.pagePaths.get(0);
            } else if (draft.originalPagePaths != null && !draft.originalPagePaths.isEmpty()) {
                thumbPath = draft.originalPagePaths.get(0);
            }

            binding.ivDraftThumb.setImageResource(R.drawable.bg_card);
            if (thumbPath != null && new File(thumbPath).exists()) {
                final String path = thumbPath;
                binding.ivDraftThumb.setTag(path);
                thumbnailExecutor.execute(() -> {
                    Bitmap bmp = CacheManager.loadThumbnail(path, 160);
                    if (bmp != null) {
                        mainHandler.post(() -> {
                            if (path.equals(binding.ivDraftThumb.getTag())) {
                                binding.ivDraftThumb.setImageBitmap(bmp);
                            }
                        });
                    }
                });
            }

            binding.cardDraft.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onDraftClick(draft);
                }
            });

            binding.btnDeleteDraft.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onDraftDeleteClick(draft);
                }
            });

            if (isMultiSelectMode) {
                binding.cardDraft.setAlpha(0.5f);
                binding.btnDeleteDraft.setEnabled(false);
            } else {
                binding.cardDraft.setAlpha(1.0f);
                binding.btnDeleteDraft.setEnabled(true);
            }
        }
    }
}
