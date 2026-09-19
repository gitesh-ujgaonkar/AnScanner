package com.anscanner.app.ui.crop;

import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.anscanner.app.R;
import com.anscanner.app.service.CacheManager;
import com.anscanner.app.ui.custom.TouchImageView;

import java.util.List;

public class CropPageAdapter extends RecyclerView.Adapter<CropPageAdapter.PageViewHolder> {

    public interface PageBitmapProvider {
        Bitmap getBitmapForPage(int position, String path);
    }

    private final List<String> pagePaths;
    private final PageBitmapProvider bitmapProvider;

    public CropPageAdapter(List<String> pagePaths, PageBitmapProvider bitmapProvider) {
        this.pagePaths = pagePaths;
        this.bitmapProvider = bitmapProvider;
    }

    @NonNull
    @Override
    public PageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_crop_page, parent, false);
        return new PageViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull PageViewHolder holder, int position) {
        String path = pagePaths.get(position);
        Bitmap bitmap = null;
        if (bitmapProvider != null) {
            bitmap = bitmapProvider.getBitmapForPage(position, path);
        }
        if (bitmap == null && path != null) {
            bitmap = CacheManager.loadBitmap(path);
        }

        if (bitmap != null) {
            holder.ivPage.setImageBitmap(bitmap);
            holder.ivPage.setScale(1.0f, false);
        } else {
            holder.ivPage.setImageDrawable(null);
        }
    }

    @Override
    public int getItemCount() {
        return pagePaths != null ? pagePaths.size() : 0;
    }

    public static class PageViewHolder extends RecyclerView.ViewHolder {
        public final TouchImageView ivPage;

        public PageViewHolder(@NonNull View itemView) {
            super(itemView);
            ivPage = itemView.findViewById(R.id.ivPage);
        }
    }
}
