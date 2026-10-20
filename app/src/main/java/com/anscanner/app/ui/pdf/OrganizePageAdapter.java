package com.anscanner.app.ui.pdf;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.anscanner.app.R;
import com.anscanner.app.databinding.ItemOrganizePageBinding;
import com.anscanner.app.util.HapticHelper;
import com.anscanner.app.util.PdfToolsHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * RecyclerView Adapter supporting smooth ItemTouchHelper drag-and-drop reordering,
 * per-page rotation (90° clockwise), and deletion.
 */
public class OrganizePageAdapter extends RecyclerView.Adapter<OrganizePageAdapter.PageViewHolder> {

    public interface OnPageActionListener {
        void onPageDeleted(int position, OrganizePageItem item);
        void onPageRotated(int position, int newRotationDegrees);
        void onPagesReordered();
    }

    public static class OrganizePageItem {
        public final int originalPageIndex;
        public int rotationDegrees; // 0, 90, 180, 270
        public Bitmap thumbnail;

        public OrganizePageItem(int originalPageIndex) {
            this.originalPageIndex = originalPageIndex;
            this.rotationDegrees = 0;
            this.thumbnail = null;
        }
    }

    private final Context context;
    private final List<OrganizePageItem> items = new ArrayList<>();
    private final OnPageActionListener listener;

    public OrganizePageAdapter(@NonNull Context context, int totalPages, @Nullable OnPageActionListener listener) {
        this.context = context;
        this.listener = listener;
        for (int i = 0; i < totalPages; i++) {
            items.add(new OrganizePageItem(i));
        }
    }

    public List<OrganizePageItem> getItems() {
        return items;
    }

    public List<PdfToolsHelper.PageOrderInfo> getPageOrderInfos() {
        List<PdfToolsHelper.PageOrderInfo> result = new ArrayList<>(items.size());
        for (OrganizePageItem item : items) {
            result.add(new PdfToolsHelper.PageOrderInfo(item.originalPageIndex, item.rotationDegrees));
        }
        return result;
    }

    public void setPageThumbnail(int originalPageIndex, @NonNull Bitmap bitmap) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).originalPageIndex == originalPageIndex) {
                items.get(i).thumbnail = bitmap;
                notifyItemChanged(i, "PAYLOAD_THUMBNAIL");
                break;
            }
        }
    }

    public void removePage(int position) {
        if (position >= 0 && position < items.size()) {
            items.remove(position);
            notifyItemRemoved(position);
            notifyItemRangeChanged(position, items.size() - position);
            if (listener != null) {
                listener.onPagesReordered();
            }
        }
    }

    @NonNull
    @Override
    public PageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemOrganizePageBinding binding = ItemOrganizePageBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new PageViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull PageViewHolder holder, int position) {
        holder.bind(items.get(position), position);
    }

    @Override
    public void onBindViewHolder(@NonNull PageViewHolder holder, int position, @NonNull List<Object> payloads) {
        if (!payloads.isEmpty()) {
            OrganizePageItem item = items.get(position);
            if (payloads.contains("PAYLOAD_THUMBNAIL")) {
                holder.updateThumbnail(item);
                return;
            }
        }
        super.onBindViewHolder(holder, position, payloads);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    class PageViewHolder extends RecyclerView.ViewHolder {
        final ItemOrganizePageBinding binding;

        PageViewHolder(ItemOrganizePageBinding binding) {
            super(binding.getRoot());
            this.binding = binding;

            binding.btnRotate.setOnClickListener(v -> {
                int pos = getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION && pos < items.size()) {
                    OrganizePageItem item = items.get(pos);
                    item.rotationDegrees = (item.rotationDegrees + 90) % 360;
                    binding.ivThumbnail.animate()
                            .rotation(item.rotationDegrees)
                            .setDuration(200)
                            .start();
                    if (listener != null) {
                        listener.onPageRotated(pos, item.rotationDegrees);
                    }
                }
            });

            binding.btnDelete.setOnClickListener(v -> {
                int pos = getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION && pos < items.size()) {
                    OrganizePageItem item = items.get(pos);
                    if (listener != null) {
                        listener.onPageDeleted(pos, item);
                    }
                }
            });
        }

        void bind(OrganizePageItem item, int position) {
            binding.tvPageBadge.setText(context.getString(R.string.organize_pages_page_badge, position + 1));
            binding.ivThumbnail.setRotation(item.rotationDegrees);
            updateThumbnail(item);
        }

        void updateThumbnail(OrganizePageItem item) {
            if (item.thumbnail != null && !item.thumbnail.isRecycled()) {
                binding.ivThumbnail.setImageBitmap(item.thumbnail);
                binding.ivThumbnail.setVisibility(View.VISIBLE);
                binding.pbLoading.setVisibility(View.GONE);
            } else {
                binding.ivThumbnail.setImageDrawable(null);
                binding.pbLoading.setVisibility(View.VISIBLE);
            }
        }
    }

    /**
     * ItemTouchHelper callback configured for 3-column drag and drop page reordering.
     */
    public ItemTouchHelper.Callback createTouchHelperCallback() {
        return new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN | ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT, 0) {

            private boolean hasMoved = false;

            @Override
            public boolean onMove(
                    @NonNull RecyclerView recyclerView,
                    @NonNull RecyclerView.ViewHolder viewHolder,
                    @NonNull RecyclerView.ViewHolder target) {

                int fromPos = viewHolder.getBindingAdapterPosition();
                int toPos = target.getBindingAdapterPosition();
                if (fromPos == RecyclerView.NO_POSITION || toPos == RecyclerView.NO_POSITION || fromPos == toPos) {
                    return false;
                }

                Collections.swap(items, fromPos, toPos);
                notifyItemMoved(fromPos, toPos);
                hasMoved = true;
                HapticHelper.snapTick(recyclerView);
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                // Swipe to dismiss not enabled; pages are deleted via trash button
            }

            @Override
            public void onSelectedChanged(@Nullable RecyclerView.ViewHolder viewHolder, int actionState) {
                super.onSelectedChanged(viewHolder, actionState);
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                    hasMoved = false;
                    HapticHelper.reorderFeedback(viewHolder.itemView);
                    viewHolder.itemView.animate().scaleX(1.06f).scaleY(1.06f).alpha(0.9f).setDuration(150).start();
                    if (viewHolder instanceof PageViewHolder) {
                        ((PageViewHolder) viewHolder).binding.cardPage.setStrokeColor(
                                ContextCompat.getColor(context, R.color.accent_mint));
                    }
                }
            }

            @Override
            public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                super.clearView(recyclerView, viewHolder);
                viewHolder.itemView.animate().scaleX(1.0f).scaleY(1.0f).alpha(1.0f).setDuration(150).start();
                if (viewHolder instanceof PageViewHolder) {
                    ((PageViewHolder) viewHolder).binding.cardPage.setStrokeColor(
                            ContextCompat.getColor(context, R.color.divider));
                }

                // Refresh badges across all items to reflect new positions
                notifyItemRangeChanged(0, items.size());

                if (hasMoved && listener != null) {
                    hasMoved = false;
                    listener.onPagesReordered();
                }
            }

            @Override
            public boolean isLongPressDragEnabled() {
                return true;
            }
        };
    }
}
