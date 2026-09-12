package com.anscanner.app.ui.review;

import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.anscanner.app.databinding.ItemAddPageBinding;
import com.anscanner.app.databinding.ItemPageThumbnailBinding;
import com.anscanner.app.service.CacheManager;
import java.util.ArrayList;
import java.util.List;

public class PageGridAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_PAGE = 0;
    private static final int TYPE_ADD_PAGE = 1;

    private List<String> pagePaths;
    private final PageInteractionListener listener;

    public interface PageInteractionListener {
        void onPageDeleteClick(int position);
        void onAddPageClick();
        void onPageClick(int position);
    }

    public PageGridAdapter(List<String> pagePaths, PageInteractionListener listener) {
        this.pagePaths = new ArrayList<>(pagePaths);
        this.listener = listener;
    }

    public void updatePages(List<String> paths) {
        this.pagePaths.clear();
        this.pagePaths.addAll(paths);
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        if (position == pagePaths.size()) {
            return TYPE_ADD_PAGE;
        }
        return TYPE_PAGE;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_ADD_PAGE) {
            ItemAddPageBinding binding = ItemAddPageBinding.inflate(inflater, parent, false);
            return new AddPageViewHolder(binding);
        } else {
            ItemPageThumbnailBinding binding = ItemPageThumbnailBinding.inflate(inflater, parent, false);
            return new PageViewHolder(binding);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof PageViewHolder) {
            ((PageViewHolder) holder).bind(pagePaths.get(position), position);
        } else if (holder instanceof AddPageViewHolder) {
            ((AddPageViewHolder) holder).bind();
        }
    }

    @Override
    public int getItemCount() {
        return pagePaths.size() + 1;
    }

    class PageViewHolder extends RecyclerView.ViewHolder {
        private final ItemPageThumbnailBinding binding;

        PageViewHolder(ItemPageThumbnailBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(String path, int position) {
            binding.tvPageNumber.setText(String.valueOf(position + 1));
            
            Bitmap thumbnail = CacheManager.loadThumbnail(path, 200);
            if (thumbnail != null) {
                binding.ivPageThumb.setImageBitmap(thumbnail);
            } else {
                binding.ivPageThumb.setImageBitmap(null);
            }
            
            binding.btnDeletePage.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onPageDeleteClick(getAdapterPosition());
                }
            });
            
            binding.getRoot().setOnClickListener(v -> {
                if (listener != null) {
                    listener.onPageClick(getAdapterPosition());
                }
            });
        }
    }

    class AddPageViewHolder extends RecyclerView.ViewHolder {
        private final ItemAddPageBinding binding;

        AddPageViewHolder(ItemAddPageBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind() {
            binding.getRoot().setOnClickListener(v -> {
                if (listener != null) {
                    listener.onAddPageClick();
                }
            });
        }
    }
}
