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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class DocumentListAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final int TYPE_HEADER = 0;
    private static final int TYPE_DOCUMENT = 1;

    private List<Object> items = new ArrayList<>();
    private final Context context;
    private final DocumentClickListener listener;
    private final SimpleDateFormat dateFormat;

    public interface DocumentClickListener {
        void onDocumentClick(DocumentEntity document);
        void onDocumentLongClick(DocumentEntity document);
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
                int pos = getAdapterPosition();
                if (pos != RecyclerView.NO_POSITION && items.get(pos) instanceof DocumentEntity) {
                    listener.onDocumentClick((DocumentEntity) items.get(pos));
                }
            });

            binding.getRoot().setOnLongClickListener(v -> {
                int pos = getAdapterPosition();
                if (pos != RecyclerView.NO_POSITION && items.get(pos) instanceof DocumentEntity) {
                    listener.onDocumentLongClick((DocumentEntity) items.get(pos));
                    return true;
                }
                return false;
            });
        }

        void bind(DocumentEntity document) {
            binding.tvDocTitle.setText(document.title);
            binding.tvDocDate.setText(dateFormat.format(new Date(document.createdAt)));
            
            String pagesText = document.pageCount == 1 ? "1 page" : document.pageCount + " pages";
            binding.tvDocPages.setText(pagesText);
            
            binding.tvDocFormat.setText(document.format.toUpperCase(Locale.getDefault()));

            if (document.thumbnailPath != null && !document.thumbnailPath.isEmpty()) {
                Bitmap thumbnail = CacheManager.loadThumbnail(document.thumbnailPath, 56);
                if (thumbnail != null) {
                    binding.ivDocThumb.setImageBitmap(thumbnail);
                } else {
                    binding.ivDocThumb.setImageResource(R.drawable.bg_card);
                }
            } else {
                binding.ivDocThumb.setImageResource(R.drawable.bg_card);
            }
        }
    }
}
