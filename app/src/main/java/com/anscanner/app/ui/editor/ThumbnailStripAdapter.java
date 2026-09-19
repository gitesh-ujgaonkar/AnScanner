package com.anscanner.app.ui.editor;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.anscanner.app.R;
import com.anscanner.app.databinding.ItemEditorThumbnailBinding;
import com.anscanner.app.service.CacheManager;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ThumbnailStripAdapter extends RecyclerView.Adapter<ThumbnailStripAdapter.ThumbViewHolder> {

    public interface OnThumbnailClickListener {
        void onThumbnailClick(int position);
    }

    private final ArrayList<String> pagePaths;
    private final OnThumbnailClickListener listener;
    private int selectedPosition = 0;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public ThumbnailStripAdapter(ArrayList<String> pagePaths, int initialSelected, OnThumbnailClickListener listener) {
        this.pagePaths = pagePaths;
        this.selectedPosition = initialSelected;
        this.listener = listener;
    }

    public void setSelectedPosition(int position) {
        if (selectedPosition != position) {
            int old = selectedPosition;
            selectedPosition = position;
            notifyItemChanged(old);
            notifyItemChanged(selectedPosition);
        }
    }

    @NonNull
    @Override
    public ThumbViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemEditorThumbnailBinding binding = ItemEditorThumbnailBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new ThumbViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull ThumbViewHolder holder, int position) {
        holder.binding.tvPageNumber.setText(String.valueOf(position + 1));

        boolean isSelected = (position == selectedPosition);
        int strokeColor = isSelected
                ? ContextCompat.getColor(holder.itemView.getContext(), R.color.accent_mint)
                : ContextCompat.getColor(holder.itemView.getContext(), R.color.border_subtle);
        int strokeWidth = isSelected ? 2 : 1;

        float density = holder.itemView.getResources().getDisplayMetrics().density;
        holder.binding.cardThumbnail.setStrokeColor(strokeColor);
        holder.binding.cardThumbnail.setStrokeWidth((int) (strokeWidth * density));

        holder.itemView.setOnClickListener(v -> {
            int currentPos = holder.getBindingAdapterPosition();
            if (currentPos != RecyclerView.NO_POSITION && listener != null) {
                listener.onThumbnailClick(currentPos);
            }
        });

        String path = pagePaths.get(position);
        final int targetPos = position;
        executor.execute(() -> {
            Bitmap bmp = CacheManager.loadBitmap(path);
            if (bmp != null) {
                mainHandler.post(() -> {
                    if (holder.getBindingAdapterPosition() == targetPos) {
                        holder.binding.ivThumbnail.setImageBitmap(bmp);
                    }
                });
            }
        });
    }

    @Override
    public int getItemCount() {
        return pagePaths.size();
    }

    public static class ThumbViewHolder extends RecyclerView.ViewHolder {
        final ItemEditorThumbnailBinding binding;

        public ThumbViewHolder(@NonNull ItemEditorThumbnailBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}