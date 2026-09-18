package com.anscanner.app.ui.pdf;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.pdf.PdfRenderer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.anscanner.app.R;
import com.anscanner.app.databinding.ItemPdfPageBinding;
import com.anscanner.app.util.PdfRendererHelper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * RecyclerView adapter for rendering PDF pages using Android's native {@link PdfRenderer}.
 *
 * <p>Supports:
 * <ul>
 *   <li>Guaranteed opaque white background fill before rendering via {@link PdfRendererHelper}.</li>
 *   <li>Kindle-style reading themes (Light, Sepia warm paper, Charcoal Dark, and OLED Inverted Night).</li>
 *   <li>Dynamic page margin adjustment (Compact, Normal, Wide).</li>
 *   <li>Single-tap callbacks for immersive fullscreen reading toggle.</li>
 *   <li>Pinch-to-zoom support up to 5x with automatic bitmap lifecycle management.</li>
 * </ul>
 * </p>
 */
public class PdfPageAdapter extends RecyclerView.Adapter<PdfPageAdapter.ViewHolder> {

    private static final String TAG = "PdfPageAdapter";

    public enum ReadingTheme {
        LIGHT,
        SEPIA,
        DARK,
        NIGHT
    }

    public enum ReadingMargin {
        COMPACT(4),
        NORMAL(16),
        WIDE(32);

        public final int marginDp;
        ReadingMargin(int dp) { this.marginDp = dp; }
    }

    public interface OnPageClickListener {
        void onPageClick(int position);
    }

    public interface OnPageRenderedListener {
        void onPageRendered(Bitmap bitmap);
        void onRenderFailed(Exception e);
    }

    private final PdfRenderer pdfRenderer;
    private final int pageCount;
    private final ExecutorService renderExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ReadingTheme currentTheme = ReadingTheme.LIGHT;
    private ReadingMargin currentMargin = ReadingMargin.NORMAL;
    private OnPageClickListener pageClickListener;

    public PdfPageAdapter(@NonNull PdfRenderer pdfRenderer) {
        this.pdfRenderer = pdfRenderer;
        synchronized (this.pdfRenderer) {
            this.pageCount = this.pdfRenderer.getPageCount();
        }
    }

    public void setReadingTheme(@NonNull ReadingTheme theme) {
        if (this.currentTheme != theme) {
            this.currentTheme = theme;
            notifyDataSetChanged();
        }
    }

    @NonNull
    public ReadingTheme getReadingTheme() {
        return currentTheme;
    }

    public void setReadingMargin(@NonNull ReadingMargin margin) {
        if (this.currentMargin != margin) {
            this.currentMargin = margin;
            notifyDataSetChanged();
        }
    }

    @NonNull
    public ReadingMargin getReadingMargin() {
        return currentMargin;
    }

    public void setOnPageClickListener(@Nullable OnPageClickListener listener) {
        this.pageClickListener = listener;
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
     * Renders a specific page at high resolution on a background thread with guaranteed opaque white background.
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
                    try {
                        int pageWidth = page.getWidth();
                        int pageHeight = page.getHeight();

                        int renderWidth = targetWidth > 0 ? targetWidth : pageWidth * 2;
                        int renderHeight = (int) ((float) renderWidth / pageWidth * pageHeight);

                        pageBitmap = PdfRendererHelper.renderPageWithWhiteBackground(
                                page, renderWidth, renderHeight, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    } finally {
                        page.close();
                    }
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
            this.binding.ivPdfPage.setMinimumScale(1.0f);
            this.binding.ivPdfPage.setMediumScale(2.5f);
            this.binding.ivPdfPage.setMaximumScale(5.0f);

            this.binding.ivPdfPage.setOnClickListener(v -> {
                int pos = getAdapterPosition();
                if (pos != RecyclerView.NO_POSITION && pageClickListener != null) {
                    pageClickListener.onPageClick(pos);
                }
            });
        }

        void bind(int position) {
            this.boundPosition = position;
            recycleCurrentBitmap();

            // 1. Dynamic margins
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) binding.getRoot().getLayoutParams();
            if (lp != null) {
                float density = binding.getRoot().getResources().getDisplayMetrics().density;
                int hMargin = (int) (currentMargin.marginDp * density);
                int vMargin = (int) (8 * density);
                lp.setMargins(hMargin, vMargin, hMargin, vMargin);
                binding.getRoot().setLayoutParams(lp);
            }

            // 2. Reading theme card & filter styling
            applyReadingThemeStyling();

            binding.ivPdfPage.setScale(1.0f, false);
            binding.ivPdfPage.setImageBitmap(null);
            binding.pbPageLoading.setVisibility(View.VISIBLE);
            binding.tvPageNumber.setText(String.valueOf(position + 1));

            renderExecutor.execute(() -> {
                Bitmap rendered = null;
                try {
                    synchronized (pdfRenderer) {
                        if (position >= pdfRenderer.getPageCount()) return;
                        PdfRenderer.Page page = pdfRenderer.openPage(position);
                        try {
                            int displayWidth = binding.getRoot().getContext().getResources().getDisplayMetrics().widthPixels;
                            int targetWidth = Math.min(Math.max((int) ((displayWidth - 64) * 1.5f), 600), 2000);
                            int targetHeight = (int) ((float) targetWidth / page.getWidth() * page.getHeight());

                            rendered = PdfRendererHelper.renderPageWithWhiteBackground(
                                    page, targetWidth, targetHeight, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        } finally {
                            page.close();
                        }
                    }

                    final Bitmap finalBitmap = rendered;
                    mainHandler.post(() -> {
                        if (boundPosition == position) {
                            recycleCurrentBitmap();
                            currentBitmap = finalBitmap;
                            binding.ivPdfPage.setScale(1.0f, false);
                            binding.ivPdfPage.setImageBitmap(finalBitmap);
                            binding.pbPageLoading.setVisibility(View.GONE);
                        } else {
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

        private void applyReadingThemeStyling() {
            switch (currentTheme) {
                case SEPIA:
                    binding.getRoot().setCardBackgroundColor(Color.parseColor("#FFF4ECD8"));
                    binding.getRoot().setStrokeColor(Color.parseColor("#FFE0D4B8"));
                    // Warm sepia matrix warming whites to parchment
                    ColorMatrix sepiaMatrix = new ColorMatrix(new float[] {
                            0.96f, 0.00f, 0.00f, 0f, 0f,
                            0.00f, 0.90f, 0.00f, 0f, 0f,
                            0.00f, 0.00f, 0.78f, 0f, 0f,
                            0.00f, 0.00f, 0.00f, 1f, 0f
                    });
                    binding.ivPdfPage.setColorFilter(new ColorMatrixColorFilter(sepiaMatrix));
                    break;

                case DARK:
                    binding.getRoot().setCardBackgroundColor(Color.parseColor("#FF2A2A2A"));
                    binding.getRoot().setStrokeColor(Color.parseColor("#FF3A3A3A"));
                    // Charcoal dimming matrix
                    ColorMatrix darkMatrix = new ColorMatrix(new float[] {
                            0.82f, 0.00f, 0.00f, 0f, 0f,
                            0.00f, 0.82f, 0.00f, 0f, 0f,
                            0.00f, 0.00f, 0.82f, 0f, 0f,
                            0.00f, 0.00f, 0.00f, 1f, 0f
                    });
                    binding.ivPdfPage.setColorFilter(new ColorMatrixColorFilter(darkMatrix));
                    break;

                case NIGHT:
                    binding.getRoot().setCardBackgroundColor(Color.BLACK);
                    binding.getRoot().setStrokeColor(Color.parseColor("#FF222222"));
                    // Pure OLED black inversion matrix (white->black, black->white)
                    ColorMatrix invertMatrix = new ColorMatrix(new float[] {
                            -1.0f,  0.0f,  0.0f, 0.0f, 255f,
                             0.0f, -1.0f,  0.0f, 0.0f, 255f,
                             0.0f,  0.0f, -1.0f, 0.0f, 255f,
                             0.0f,  0.0f,  0.0f, 1.0f,   0f
                    });
                    binding.ivPdfPage.setColorFilter(new ColorMatrixColorFilter(invertMatrix));
                    break;

                case LIGHT:
                default:
                    binding.getRoot().setCardBackgroundColor(Color.WHITE);
                    binding.getRoot().setStrokeColor(ContextCompat.getColor(binding.getRoot().getContext(), R.color.divider));
                    binding.ivPdfPage.setColorFilter(null);
                    break;
            }
        }

        void recycle() {
            boundPosition = -1;
            binding.ivPdfPage.setScale(1.0f, false);
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
