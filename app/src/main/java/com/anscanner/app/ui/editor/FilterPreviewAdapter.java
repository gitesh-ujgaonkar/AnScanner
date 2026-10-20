package com.anscanner.app.ui.editor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.anscanner.app.R;
import com.anscanner.app.databinding.ItemFilterPreviewBinding;
import com.anscanner.app.ui.editor.UnifiedEditorActivity.DocumentFilter;

import java.util.List;
import java.util.Map;

/**
 * Adapter for the horizontal filter preview carousel in {@link UnifiedEditorActivity}.
 * Displays thumbnail cards with live filtered previews and highlighted active selection states.
 */
public class FilterPreviewAdapter extends RecyclerView.Adapter<FilterPreviewAdapter.FilterViewHolder> {

    public static class FilterItem {
        public final DocumentFilter filter;
        public final String displayName;
        public Bitmap previewBitmap;

        public FilterItem(DocumentFilter filter, String displayName) {
            this.filter = filter;
            this.displayName = displayName;
        }
    }

    public interface OnFilterClickListener {
        void onFilterClick(DocumentFilter filter, int position);
    }

    private final List<FilterItem> items;
    private final OnFilterClickListener listener;
    private DocumentFilter selectedFilter = DocumentFilter.ORIGINAL;
    private int selectedPosition = 0;

    public FilterPreviewAdapter(List<FilterItem> items, OnFilterClickListener listener) {
        this.items = items;
        this.listener = listener;
    }

    public DocumentFilter getSelectedFilter() {
        return selectedFilter;
    }

    public int getSelectedPosition() {
        return selectedPosition;
    }

    public void setSelectedFilter(DocumentFilter filter) {
        if (filter == null) {
            filter = DocumentFilter.ORIGINAL;
        }
        if (this.selectedFilter != filter) {
            int oldPos = selectedPosition;
            this.selectedFilter = filter;
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).filter == filter) {
                    selectedPosition = i;
                    break;
                }
            }
            if (oldPos >= 0 && oldPos < items.size()) {
                notifyItemChanged(oldPos);
            }
            if (selectedPosition >= 0 && selectedPosition < items.size()) {
                notifyItemChanged(selectedPosition);
            }
        }
    }

    public void setSelectedPosition(int position) {
        if (position >= 0 && position < items.size() && selectedPosition != position) {
            int oldPos = selectedPosition;
            selectedPosition = position;
            selectedFilter = items.get(position).filter;
            notifyItemChanged(oldPos);
            notifyItemChanged(selectedPosition);
        }
    }

    public void setPreviews(Map<DocumentFilter, Bitmap> previews) {
        if (previews == null) return;
        for (FilterItem item : items) {
            item.previewBitmap = previews.get(item.filter);
        }
        notifyDataSetChanged();
    }

    public void clearPreviews() {
        for (FilterItem item : items) {
            item.previewBitmap = null;
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public FilterViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemFilterPreviewBinding binding = ItemFilterPreviewBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new FilterViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull FilterViewHolder holder, int position) {
        FilterItem item = items.get(position);
        Context context = holder.itemView.getContext();

        holder.binding.tvFilterName.setText(item.displayName);

        if (item.previewBitmap != null && !item.previewBitmap.isRecycled()) {
            holder.binding.ivFilterThumbnail.setImageBitmap(item.previewBitmap);
            holder.binding.pbFilterLoading.setVisibility(View.GONE);
        } else {
            holder.binding.ivFilterThumbnail.setImageDrawable(null);
            holder.binding.pbFilterLoading.setVisibility(View.VISIBLE);
        }

        boolean isSelected = (item.filter == selectedFilter);
        int strokeColor = isSelected
                ? ContextCompat.getColor(context, R.color.accent_mint)
                : ContextCompat.getColor(context, R.color.border_subtle);
        int strokeWidth = isSelected ? dpToPx(context, 2) : dpToPx(context, 1);
        float elevation = isSelected ? dpToPx(context, 4) : dpToPx(context, 1);
        holder.binding.cardFilterPreview.setStrokeColor(strokeColor);
        holder.binding.cardFilterPreview.setStrokeWidth(strokeWidth);
        holder.binding.cardFilterPreview.setCardElevation(elevation);
        holder.binding.cardFilterPreview.setScaleX(1.0f);
        holder.binding.cardFilterPreview.setScaleY(1.0f);

        holder.binding.ivSelectedBadge.setVisibility(isSelected ? View.VISIBLE : View.GONE);
        holder.binding.tvFilterName.setTextColor(isSelected
                ? ContextCompat.getColor(context, R.color.accent_mint)
                : ContextCompat.getColor(context, R.color.text_secondary));
        holder.binding.tvFilterName.setTypeface(null, isSelected ? Typeface.BOLD : Typeface.NORMAL);

        holder.itemView.setOnClickListener(v -> {
            int currentPos = holder.getBindingAdapterPosition();
            if (currentPos != RecyclerView.NO_POSITION && listener != null) {
                setSelectedPosition(currentPos);
                listener.onFilterClick(items.get(currentPos).filter, currentPos);
            }
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    private static int dpToPx(Context context, int dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }

    public static class FilterViewHolder extends RecyclerView.ViewHolder {
        final ItemFilterPreviewBinding binding;

        public FilterViewHolder(@NonNull ItemFilterPreviewBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
