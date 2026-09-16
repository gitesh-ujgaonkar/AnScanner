package com.anscanner.app.ui.camera;

import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.anscanner.app.data.entity.DocumentEntity;
import com.anscanner.app.databinding.ItemRecentScanBinding;
import com.anscanner.app.service.CacheManager;

import android.os.Handler;
import android.os.Looper;
import com.anscanner.app.R;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RecentScansAdapter extends RecyclerView.Adapter<RecentScansAdapter.ViewHolder> {
    private List<DocumentEntity> documents = new ArrayList<>();
    private final OnItemClickListener listener;
    private final ExecutorService imageExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

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

            // Clear ImageView at the start to prevent recycled views from showing stale images
            binding.ivThumbnail.setImageBitmap(null);

            final String thumbnailPath = entity.thumbnailPath;
            if (thumbnailPath != null && !thumbnailPath.isEmpty()) {
                binding.ivThumbnail.setTag(thumbnailPath);

                imageExecutor.execute(() -> {
                    Bitmap thumbnail = CacheManager.decodeSampledBitmap(thumbnailPath, 128, 128);
                    mainHandler.post(() -> {
                        if (thumbnailPath.equals(binding.ivThumbnail.getTag())) {
                            if (thumbnail != null) {
                                binding.ivThumbnail.setImageBitmap(thumbnail);
                            } else {
                                binding.ivThumbnail.setImageResource(R.drawable.bg_card);
                            }
                        } else {
                            if (thumbnail != null && !thumbnail.isRecycled()) {
                                thumbnail.recycle();
                            }
                        }
                    });
                });
            } else {
                binding.ivThumbnail.setTag(null);
                binding.ivThumbnail.setImageResource(R.drawable.bg_card);
            }
        }
    }
}
