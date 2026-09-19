package com.anscanner.app.ui.editor;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.anscanner.app.databinding.ItemPageEditorBinding;
import com.anscanner.app.service.CacheManager;
import com.anscanner.app.ui.crop.CropOverlayView;
import com.anscanner.app.ui.custom.AnnotationDrawingView;
import com.anscanner.app.ui.custom.TouchImageView;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ViewPager2 adapter for multi-page editing workspace.
 * Backed by an ordered list of image file paths (one per document page).
 *
 * Each page item encapsulates:
 * 1. Base image layer (TouchImageView with 1x-5x pinch zoom)
 * 2. Interactive CropOverlayView (hidden by default, toggled in crop mode)
 * 3. AnnotationDrawingView (transparent overlay supporting pen, highlighter, text, signature)
 */
public class PageEditorAdapter extends RecyclerView.Adapter<PageEditorAdapter.PageViewHolder> {

    public interface PageBitmapProvider {
        @Nullable
        Bitmap provideBitmap(int position, String path);
    }

    private final ArrayList<String> pagePaths;
    private final PageBitmapProvider bitmapProvider;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public PageEditorAdapter(@NonNull ArrayList<String> pagePaths, @Nullable PageBitmapProvider bitmapProvider) {
        this.pagePaths = pagePaths;
        this.bitmapProvider = bitmapProvider;
    }

    @NonNull
    @Override
    public PageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemPageEditorBinding binding = ItemPageEditorBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new PageViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull PageViewHolder holder, int position) {
        String path = pagePaths.get(position);

        // Reset overlays and view states for reused viewholders
        holder.cropOverlay.setVisibility(View.GONE);
        holder.cropOverlay.resetCornerMoved();
        holder.drawingOverlay.setVisibility(View.GONE);
        holder.ivPageImage.setScale(1.0f, false);
        holder.drawingOverlay.bindImageView(holder.ivPageImage);

        if (bitmapProvider != null) {
            Bitmap bmp = bitmapProvider.provideBitmap(position, path);
            if (bmp != null) {
                holder.ivPageImage.setImageBitmap(bmp);
                holder.drawingOverlay.setDocumentDimensions(bmp.getWidth(), bmp.getHeight());
                return;
            }
        }

        // Asynchronously decode bitmap to keep swiping fluid
        final int targetPos = position;
        executor.execute(() -> {
            Bitmap bmp = CacheManager.loadBitmap(path);
            if (bmp != null) {
                mainHandler.post(() -> {
                    if (holder.getBindingAdapterPosition() == targetPos) {
                        holder.ivPageImage.setImageBitmap(bmp);
                        holder.drawingOverlay.setDocumentDimensions(bmp.getWidth(), bmp.getHeight());
                    }
                });
            }
        });
    }

    @Override
    public int getItemCount() {
        return pagePaths.size();
    }

    @Nullable
    public static PageViewHolder getViewHolder(@Nullable ViewPager2 viewPager, int position) {
        if (viewPager == null) return null;
        RecyclerView rv = (RecyclerView) viewPager.getChildAt(0);
        if (rv != null) {
            RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(position);
            if (vh instanceof PageViewHolder) {
                return (PageViewHolder) vh;
            }
        }
        return null;
    }

    public static class PageViewHolder extends RecyclerView.ViewHolder {
        public final TouchImageView ivPageImage;
        public final CropOverlayView cropOverlay;
        public final AnnotationDrawingView drawingOverlay;

        public PageViewHolder(@NonNull ItemPageEditorBinding binding) {
            super(binding.getRoot());
            this.ivPageImage = binding.ivPageImage;
            this.cropOverlay = binding.cropOverlay;
            this.drawingOverlay = binding.drawingOverlay;
        }
    }
}