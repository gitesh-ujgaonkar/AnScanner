package com.anscanner.app.ui.pdf;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.anscanner.app.databinding.ItemPdfPageBinding;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * RecyclerView adapter for rendering PDF pages using Android's native {@link PdfRenderer}.
 *
 * <p><strong>Memory & Concurrency Contract:</strong></p>
 * <ul>
 *   <li>All {@link PdfRenderer} access is synchronized on the renderer instance.</li>
 *   <li>Page decoding happens on a background executor.</li>
 *   <li>Bitmaps are recycled immediately when a view is recycled in {@link #onViewRecycled(ViewHolder)}.</li>
 * </ul>
 */
public class PdfPageAdapter extends RecyclerView.Adapter<PdfPageAdapter.ViewHolder> {

    private static final String TAG = "PdfPageAdapter";

    private final PdfRenderer pdfRenderer;
    private final int pageCount;
    private final ExecutorService renderExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public PdfPageAdapter(@NonNull PdfRenderer pdfRenderer) {
        this.pdfRenderer = pdfRenderer;
        synchronized (this.pdfRenderer) {
            this.pageCount = this.pdfRenderer.getPageCount();
        }
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemPdfPageBinding binding = ItemPdfPageBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new ViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(position);
    }

    @Override
    public void onViewRecycled(@NonNull ViewHolder holder) {
        super.onViewRecycled(holder);
        holder.recycle();
    }

    @Override
    public int getItemCount() {
        return pageCount;
    }

    /**
     * Renders a specific page at high resolution on a background thread.
     * Used by the "Edit" action to extract the current page into the crop pipeline.
     */
    public void renderPageHighRes(int pageIndex, int targetWidth, OnPageRenderedListener listener) {
        if (pageIndex < 0 || pageIndex >= pageCount) {
            listener.onRenderFailed(new IllegalArgumentException("Invalid page index: " + pageIndex));
            return;
        }

        renderExecutor.execute(() -> {
            Bitmap pageBitmap = null;
            try {
                synchronized (pdfRenderer) {
                    PdfRenderer.Page page = pdfRenderer.openPage(pageIndex);
                    int pageWidth = page.getWidth();
                    int pageHeight = page.getHeight();

                    int renderWidth = targetWidth > 0 ? targetWidth : pageWidth * 2;
                    int renderHeight = (int) ((float) renderWidth / pageWidth * pageHeight);

                    pageBitmap = Bitmap.createBitmap(renderWidth, renderHeight, Bitmap.Config.ARGB_8888);
                    pageBitmap.eraseColor(Color.WHITE);

                    page.render(pageBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    page.close();
                }

                final Bitmap resultBitmap = pageBitmap;
                mainHandler.post(() -> listener.onPageRendered(resultBitmap));

            } catch (Exception e) {
                Log.e(TAG, "Failed to render high-res page: " + pageIndex, e);
                if (pageBitmap != null && !pageBitmap.isRecycled()) {
                    pageBitmap.recycle();
                }
                mainHandler.post(() -> listener.onRenderFailed(e));
            }
        });
    }

    public interface OnPageRenderedListener {
        void onPageRendered(Bitmap bitmap);
        void onRenderFailed(Exception e);
    }

    public void cleanup() {
        if (!renderExecutor.isShutdown()) {
            renderExecutor.shutdown();
        }
    }

    class ViewHolder extends RecyclerView.ViewHolder {
        private final ItemPdfPageBinding binding;
        private Bitmap currentBitmap = null;
        private int boundPosition = -1;

        ViewHolder(ItemPdfPageBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(int position) {
            this.boundPosition = position;
            recycleCurrentBitmap();

            binding.ivPdfPage.setImageBitmap(null);
            binding.pbPageLoading.setVisibility(View.VISIBLE);
            binding.tvPageNumber.setText(String.valueOf(position + 1));

            renderExecutor.execute(() -> {
                Bitmap rendered = null;
                try {
                    synchronized (pdfRenderer) {
                        if (position >= pdfRenderer.getPageCount()) return;
                        PdfRenderer.Page page = pdfRenderer.openPage(position);

                        int displayWidth = binding.getRoot().getContext().getResources().getDisplayMetrics().widthPixels;
                        // Subtract margins (~32dp)
                        int targetWidth = Math.max(displayWidth - 64, 400);
                        int targetHeight = (int) ((float) targetWidth / page.getWidth() * page.getHeight());

                        rendered = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888);
                        rendered.eraseColor(Color.WHITE);

                        page.render(rendered, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        page.close();
                    }

                    final Bitmap finalBitmap = rendered;
                    mainHandler.post(() -> {
                        if (boundPosition == position) {
                            recycleCurrentBitmap();
                            currentBitmap = finalBitmap;
                            binding.ivPdfPage.setImageBitmap(finalBitmap);
                            binding.pbPageLoading.setVisibility(View.GONE);
                        } else {
                            // View was recycled for another position in the meantime
                            if (finalBitmap != null && !finalBitmap.isRecycled()) {
                                finalBitmap.recycle();
                            }
                        }
                    });

                } catch (Exception e) {
                    Log.e(TAG, "Error rendering page " + position, e);
                    if (rendered != null && !rendered.isRecycled()) {
                        rendered.recycle();
                    }
                    mainHandler.post(() -> {
                        if (boundPosition == position) {
                            binding.pbPageLoading.setVisibility(View.GONE);
                        }
                    });
                }
            });
        }

        void recycle() {
            boundPosition = -1;
            binding.ivPdfPage.setImageBitmap(null);
            binding.pbPageLoading.setVisibility(View.GONE);
            recycleCurrentBitmap();
        }

        private void recycleCurrentBitmap() {
            if (currentBitmap != null) {
                if (!currentBitmap.isRecycled()) {
                    currentBitmap.recycle();
                }
                currentBitmap = null;
            }
        }
    }
}
