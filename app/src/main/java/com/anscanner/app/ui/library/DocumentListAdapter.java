package com.anscanner.app.ui.library;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.anscanner.app.R;
import com.anscanner.app.data.entity.DocumentEntity;
import com.anscanner.app.databinding.ItemDateHeaderBinding;
import com.anscanner.app.databinding.ItemDocumentRowBinding;
import com.anscanner.app.service.CacheManager;

import android.os.Handler;
import android.os.Looper;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DocumentListAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final int TYPE_HEADER = 0;
    private static final int TYPE_DOCUMENT = 1;

    private List<Object> items = new ArrayList<>();
    private final Context context;
    private final DocumentClickListener listener;
    private final SimpleDateFormat dateFormat;
    private final ExecutorService imageExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface DocumentClickListener {
        void onDocumentClick(DocumentEntity document);
        void onDocumentShare(DocumentEntity document);
        void onDocumentProperties(DocumentEntity document);
        void onDocumentRemoveAppOnly(DocumentEntity document);
        void onDocumentDeletePermanently(DocumentEntity document);
    }

    public DocumentListAdapter(Context context, DocumentClickListener listener) {
        this.context = context;
        this.listener = listener;
        this.dateFormat = new SimpleDateFormat("MMM dd", Locale.getDefault());
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
    public boolean isEmpty() {
        for (Object item : items) {
            if (item instanceof DocumentEntity) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int getItemViewType(int position) {
        if (items.get(position) instanceof String) {
            return TYPE_HEADER;
        }
        return TYPE_DOCUMENT;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_HEADER) {
            return new HeaderViewHolder(ItemDateHeaderBinding.inflate(inflater, parent, false));
        } else {
            return new DocumentViewHolder(ItemDocumentRowBinding.inflate(inflater, parent, false));
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof HeaderViewHolder) {
            ((HeaderViewHolder) holder).bind((String) items.get(position));
        } else if (holder instanceof DocumentViewHolder) {
            ((DocumentViewHolder) holder).bind((DocumentEntity) items.get(position));
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
                    listener.onDocumentClick((DocumentEntity) items.get(pos));
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
                    showPopupMenu(binding.btnMore, (DocumentEntity) items.get(pos));
                    return true;
                }
                return false;
            });
        }

        private void showPopupMenu(android.view.View anchor, DocumentEntity document) {
            android.widget.PopupMenu popup = new android.widget.PopupMenu(anchor.getContext(), anchor);
            popup.getMenuInflater().inflate(R.menu.menu_document_item, popup.getMenu());

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

            // 1. Clear ImageView at the start to prevent recycled views from showing wrong image
            binding.ivDocThumb.setImageBitmap(null);

            // 2. Read the permanent thumbnail path from the database record
            final String thumbnailPath = document.thumbnailPath;
            if (thumbnailPath != null && !thumbnailPath.isEmpty()) {
                binding.ivDocThumb.setTag(thumbnailPath);

                // 3. Use single-thread Executor to decode in background using BitmapFactory.Options
                imageExecutor.execute(() -> {
                    Bitmap thumbnail = CacheManager.decodeSampledBitmap(thumbnailPath, 112, 112);

                    // 4. Post decoded Bitmap back to main UI thread
                    mainHandler.post(() -> {
                        if (thumbnailPath.equals(binding.ivDocThumb.getTag())) {
                            if (thumbnail != null) {
                                binding.ivDocThumb.setImageBitmap(thumbnail);
                            } else {
                                binding.ivDocThumb.setImageResource(R.drawable.bg_card);
                            }
                        } else {
                            if (thumbnail != null && !thumbnail.isRecycled()) {
                                thumbnail.recycle();
                            }
                        }
                    });
                });
            } else {
                binding.ivDocThumb.setTag(null);
                binding.ivDocThumb.setImageResource(R.drawable.bg_card);
            }
        }
    }
}
