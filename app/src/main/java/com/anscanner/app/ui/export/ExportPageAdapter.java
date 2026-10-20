package com.anscanner.app.ui.export;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.anscanner.app.R;
import com.anscanner.app.databinding.ItemExportPageThumbnailBinding;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * RecyclerView adapter for selecting and previewing PDF pages to export as images.
 */
public class ExportPageAdapter extends RecyclerView.Adapter<ExportPageAdapter.PageViewHolder> {

    public interface OnSelectionChangedListener {
        void onSelectionChanged(int selectedCount, int totalCount);
    }

    private final int totalPages;
    private final Set<Integer> selectedPages = new TreeSet<>();
    private final Map<Integer, Bitmap> thumbnailMap = new HashMap<>();
    private final OnSelectionChangedListener listener;

    public ExportPageAdapter(int totalPages, OnSelectionChangedListener listener) {
        this.totalPages = totalPages;
        this.listener = listener;
        for (int i = 0; i < totalPages; i++) {
            selectedPages.add(i);
        }
    }

    public Set<Integer> getSelectedPages() {
        return new TreeSet<>(selectedPages);
    }

    public int getSelectedCount() {
        return selectedPages.size();
    }

    public boolean isAllSelected() {
        return selectedPages.size() == totalPages && totalPages > 0;
    }

    public void selectAll() {
        selectedPages.clear();
        for (int i = 0; i < totalPages; i++) {
            selectedPages.add(i);
        }
        notifyDataSetChanged();
        if (listener != null) {
            listener.onSelectionChanged(selectedPages.size(), totalPages);
        }
    }

    public void deselectAll() {
        selectedPages.clear();
        notifyDataSetChanged();
        if (listener != null) {
            listener.onSelectionChanged(0, totalPages);
        }
    }

    public void setThumbnail(int pageIndex, Bitmap bitmap) {
        if (pageIndex >= 0 && pageIndex < totalPages && bitmap != null) {
            thumbnailMap.put(pageIndex, bitmap);
            notifyItemChanged(pageIndex);
        }
    }

    public void recycleAllBitmaps() {
        for (Bitmap bmp : thumbnailMap.values()) {
            if (bmp != null && !bmp.isRecycled()) {
                bmp.recycle();
            }
        }
        thumbnailMap.clear();
    }

    @NonNull
    @Override
    public PageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemExportPageThumbnailBinding binding = ItemExportPageThumbnailBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new PageViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull PageViewHolder holder, int position) {
        holder.bind(position);
    }

    @Override
    public int getItemCount() {
        return totalPages;
    }

    class PageViewHolder extends RecyclerView.ViewHolder {
        private final ItemExportPageThumbnailBinding binding;

        PageViewHolder(ItemExportPageThumbnailBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(int pageIndex) {
            Context context = itemView.getContext();
            binding.tvPageNumber.setText(String.valueOf(pageIndex + 1));

            boolean isSelected = selectedPages.contains(pageIndex);
            float density = context.getResources().getDisplayMetrics().density;

            if (isSelected) {
                binding.cardPage.setStrokeColor(ContextCompat.getColor(context, R.color.accent_mint));
                binding.cardPage.setStrokeWidth((int) (2 * density));
                binding.viewUnselectedOverlay.setVisibility(View.GONE);

                binding.ivSelectionIndicator.setImageResource(R.drawable.ic_check);
                binding.ivSelectionIndicator.setBackgroundResource(R.drawable.bg_selection_circle_selected);
                binding.ivSelectionIndicator.setColorFilter(ContextCompat.getColor(context, R.color.text_on_accent));
            } else {
                binding.cardPage.setStrokeColor(ContextCompat.getColor(context, R.color.border_subtle));
                binding.cardPage.setStrokeWidth((int) (1 * density));
                binding.viewUnselectedOverlay.setVisibility(View.VISIBLE);

                binding.ivSelectionIndicator.setImageDrawable(null);
                binding.ivSelectionIndicator.setBackgroundResource(R.drawable.bg_selection_circle_unselected);
                binding.ivSelectionIndicator.clearColorFilter();
            }

            Bitmap thumb = thumbnailMap.get(pageIndex);
            if (thumb != null && !thumb.isRecycled()) {
                binding.ivThumbnail.setImageBitmap(thumb);
                binding.pbThumbnailLoading.setVisibility(View.GONE);
            } else {
                binding.ivThumbnail.setImageDrawable(null);
                binding.pbThumbnailLoading.setVisibility(View.VISIBLE);
            }

            itemView.setOnClickListener(v -> {
                int pos = getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return;

                if (selectedPages.contains(pos)) {
                    selectedPages.remove(pos);
                } else {
                    selectedPages.add(pos);
                }
                notifyItemChanged(pos);

                if (listener != null) {
                    listener.onSelectionChanged(selectedPages.size(), totalPages);
                }
            });
        }
    }
}
