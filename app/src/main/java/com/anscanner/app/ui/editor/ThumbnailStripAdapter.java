package com.anscanner.app.ui.editor;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.anscanner.app.R;
import com.anscanner.app.databinding.ItemEditorAddPageThumbnailBinding;
import com.anscanner.app.databinding.ItemEditorThumbnailBinding;
import com.anscanner.app.service.CacheManager;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ThumbnailStripAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public static final int VIEW_TYPE_PAGE = 0;
    public static final int VIEW_TYPE_ADD = 1;

    public interface OnThumbnailClickListener {
        void onThumbnailClick(int position);
    }

    public interface OnAddPageClickListener {
        void onAddPageClick();
    }

    private final ArrayList<String> pagePaths;
    private final OnThumbnailClickListener listener;
    private OnAddPageClickListener addPageClickListener;
    private int selectedPosition = 0;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public ThumbnailStripAdapter(ArrayList<String> pagePaths, int initialSelected, OnThumbnailClickListener listener) {
        this(pagePaths, initialSelected, listener, null);
    }

    public ThumbnailStripAdapter(ArrayList<String> pagePaths, int initialSelected,
                                 OnThumbnailClickListener listener,
                                 @Nullable OnAddPageClickListener addPageClickListener) {
        this.pagePaths = pagePaths;
        this.selectedPosition = initialSelected;
        this.listener = listener;
        this.addPageClickListener = addPageClickListener;
    }

    public void setOnAddPageClickListener(@Nullable OnAddPageClickListener addPageClickListener) {
        this.addPageClickListener = addPageClickListener;
    }

    public void setSelectedPosition(int position) {
        if (selectedPosition != position) {
            int old = selectedPosition;
            selectedPosition = position;
            if (old >= 0 && old < pagePaths.size()) {
                notifyItemChanged(old);
            }
            if (selectedPosition >= 0 && selectedPosition < pagePaths.size()) {
                notifyItemChanged(selectedPosition);
            }
        }
    }

    @Override
    public int getItemViewType(int position) {
        if (position == pagePaths.size()) {
            return VIEW_TYPE_ADD;
        }
        return VIEW_TYPE_PAGE;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == VIEW_TYPE_ADD) {
            ItemEditorAddPageThumbnailBinding binding = ItemEditorAddPageThumbnailBinding.inflate(
                    LayoutInflater.from(parent.getContext()), parent, false);
            return new AddPageViewHolder(binding);
        } else {
            ItemEditorThumbnailBinding binding = ItemEditorThumbnailBinding.inflate(
                    LayoutInflater.from(parent.getContext()), parent, false);
            return new ThumbViewHolder(binding);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof AddPageViewHolder) {
            AddPageViewHolder addHolder = (AddPageViewHolder) holder;
            View.OnClickListener clickListener = v -> {
                if (addPageClickListener != null) {
                    addPageClickListener.onAddPageClick();
                }
            };
            addHolder.itemView.setOnClickListener(clickListener);
            addHolder.binding.cardAddThumbnail.setOnClickListener(clickListener);
        } else if (holder instanceof ThumbViewHolder) {
            ThumbViewHolder thumbHolder = (ThumbViewHolder) holder;
            thumbHolder.binding.tvPageNumber.setText(String.valueOf(position + 1));

            boolean isSelected = (position == selectedPosition);
            int strokeColor = isSelected
                    ? ContextCompat.getColor(thumbHolder.itemView.getContext(), R.color.accent_mint)
                    : ContextCompat.getColor(thumbHolder.itemView.getContext(), R.color.border_subtle);
            int strokeWidth = isSelected ? 2 : 1;

            float density = thumbHolder.itemView.getResources().getDisplayMetrics().density;
            thumbHolder.binding.cardThumbnail.setStrokeColor(strokeColor);
            thumbHolder.binding.cardThumbnail.setStrokeWidth((int) (strokeWidth * density));

            thumbHolder.itemView.setOnClickListener(v -> {
                int currentPos = thumbHolder.getBindingAdapterPosition();
                if (currentPos != RecyclerView.NO_POSITION && currentPos < pagePaths.size() && listener != null) {
                    listener.onThumbnailClick(currentPos);
                }
            });

            String path = pagePaths.get(position);
            thumbHolder.itemView.setTag(path);
            executor.execute(() -> {
                Bitmap bmp = CacheManager.loadThumbnail(path, 140);
                if (bmp == null) {
                    bmp = CacheManager.loadBitmap(path);
                }
                final Bitmap finalBmp = bmp;
                if (finalBmp != null) {
                    mainHandler.post(() -> {
                        if (path.equals(thumbHolder.itemView.getTag())) {
                            thumbHolder.binding.ivThumbnail.setImageBitmap(finalBmp);
                        }
                    });
                }
            });
        }
    }

    @Override
    public int getItemCount() {
        return pagePaths.size() + 1;
    }

    public static class ThumbViewHolder extends RecyclerView.ViewHolder {
        public final ItemEditorThumbnailBinding binding;

        public ThumbViewHolder(@NonNull ItemEditorThumbnailBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }

    public static class AddPageViewHolder extends RecyclerView.ViewHolder {
        public final ItemEditorAddPageThumbnailBinding binding;

        public AddPageViewHolder(@NonNull ItemEditorAddPageThumbnailBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}