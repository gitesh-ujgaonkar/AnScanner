package com.anscanner.app.ui.camera;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.anscanner.app.databinding.ItemBatchThumbnailBinding;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Lightweight horizontal RecyclerView adapter displaying captured thumbnails in Batch Mode.
 */
public class BatchThumbAdapter extends RecyclerView.Adapter<BatchThumbAdapter.BatchViewHolder> {

    public interface OnBatchItemClickListener {
        void onItemClick(int position);
    }

    private final List<String> pagePaths = new ArrayList<>();
    private final OnBatchItemClickListener listener;

    public BatchThumbAdapter(OnBatchItemClickListener listener) {
        this.listener = listener;
    }

    public void setPages(List<String> paths) {
        pagePaths.clear();
        if (paths != null) {
            pagePaths.addAll(paths);
        }
        notifyDataSetChanged();
    }

    public void addPage(String path) {
        pagePaths.add(path);
        notifyItemInserted(pagePaths.size() - 1);
    }

    @NonNull
    @Override
    public BatchViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemBatchThumbnailBinding binding = ItemBatchThumbnailBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new BatchViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull BatchViewHolder holder, int position) {
        String path = pagePaths.get(position);
        holder.binding.tvBadge.setText(String.valueOf(position + 1));

        // Downsample bitmap strictly for thumbnail display
        Bitmap thumbnail = loadDownsampledBitmap(path, 104, 136);
        if (thumbnail != null) {
            holder.binding.ivThumbnail.setImageBitmap(thumbnail);
        } else {
            holder.binding.ivThumbnail.setImageDrawable(null);
        }

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                int pos = holder.getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) {
                    listener.onItemClick(pos);
                }
            }
        });
    }

    @Override
    public int getItemCount() {
        return pagePaths.size();
    }

    private Bitmap loadDownsampledBitmap(String path, int reqWidth, int reqHeight) {
        if (path == null || !new File(path).exists()) return null;
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, options);

            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);
            options.inJustDecodeBounds = false;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(path, options);
        } catch (Exception e) {
            return null;
        }
    }

    private int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return Math.max(1, inSampleSize);
    }

    static class BatchViewHolder extends RecyclerView.ViewHolder {
        final ItemBatchThumbnailBinding binding;

        BatchViewHolder(ItemBatchThumbnailBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
