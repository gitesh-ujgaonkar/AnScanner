package com.anscanner.app.ui.camera;

import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.anscanner.app.data.entity.DocumentEntity;
import com.anscanner.app.databinding.ItemRecentScanBinding;
import com.anscanner.app.service.CacheManager;

import java.util.ArrayList;
import java.util.List;

public class RecentScansAdapter extends RecyclerView.Adapter<RecentScansAdapter.ViewHolder> {
    private List<DocumentEntity> documents = new ArrayList<>();
    private final OnItemClickListener listener;

    public interface OnItemClickListener {
        void onDocumentClick(DocumentEntity document);
    }

    public RecentScansAdapter(OnItemClickListener listener) {
        this.listener = listener;
    }

    public void submitList(List<DocumentEntity> newDocs) {
        this.documents = new ArrayList<>(newDocs);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemRecentScanBinding binding = ItemRecentScanBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new ViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(documents.get(position));
    }

    @Override
    public int getItemCount() {
        return documents.size();
    }

    class ViewHolder extends RecyclerView.ViewHolder {
        private final ItemRecentScanBinding binding;

        ViewHolder(ItemRecentScanBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(DocumentEntity entity) {
            binding.getRoot().setOnClickListener(v -> listener.onDocumentClick(entity));
            if (entity.thumbnailPath != null && !entity.thumbnailPath.isEmpty()) {
                Bitmap thumbnail = CacheManager.loadThumbnail(entity.thumbnailPath, 64);
                if (thumbnail != null) {
                    binding.ivThumbnail.setImageBitmap(thumbnail);
                }
            }
        }
    }
}
