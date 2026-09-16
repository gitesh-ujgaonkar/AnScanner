package com.anscanner.app.ui.pdf;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.anscanner.app.R;
import com.anscanner.app.databinding.ActivityPdfViewerBinding;
import com.anscanner.app.service.CacheManager;
import com.anscanner.app.service.OcrHelper;
import com.anscanner.app.ui.crop.CropPreviewActivity;
import com.google.mlkit.vision.text.Text;

import java.io.File;
import java.util.UUID;

/**
 * Native PDF Viewer Activity using {@link PdfRenderer} and {@link PrintManager}.
 *
 * <p>Supports:
 * <ul>
 *   <li>System-wide "Open With" handling via {@link Intent#ACTION_VIEW} for {@code application/pdf}.</li>
 *   <li>Internal app navigation via file path or MediaStore content URI.</li>
 *   <li>High-performance continuous vertical page scrolling with strict Bitmap recycling.</li>
 *   <li>Native system printing via {@link PrintManager} and {@link PdfPrintDocumentAdapter}.</li>
 *   <li>In-viewer page editing via routing back into {@link CropPreviewActivity}.</li>
 * </ul>
 * </p>
 */
public class PdfViewerActivity extends AppCompatActivity {

    private static final String TAG = "PdfViewerActivity";

    public static final String EXTRA_PDF_PATH = "extra_pdf_path";
    public static final String EXTRA_PDF_URI = "extra_pdf_uri";
    public static final String EXTRA_DOCUMENT_TITLE = "extra_document_title";

    private ActivityPdfViewerBinding binding;
    private ParcelFileDescriptor pfd;
    private PdfRenderer pdfRenderer;
    private PdfPageAdapter pageAdapter;
    private LinearLayoutManager layoutManager;

    private Uri resolvedUri;
    private File resolvedFile;
    private String documentTitle = "Document";
    private int pageCount = 0;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityPdfViewerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setupToolbar();
        loadPdfDocument();
    }

    private void setupToolbar() {
        binding.btnBack.setOnClickListener(v -> finish());
        binding.btnPrint.setOnClickListener(v -> printDocument());
        binding.btnExtractText.setOnClickListener(v -> extractTextFromCurrentPage());
        binding.btnEdit.setOnClickListener(v -> editCurrentPage());

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (binding.lensOverlay.getVisibility() == View.VISIBLE) {
                    dismissLensOverlay();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
    }

    private void dismissLensOverlay() {
        if (binding.lensOverlay.getVisibility() == View.VISIBLE) {
            binding.lensOverlay.setVisibility(View.GONE);
            binding.lensOverlay.clear();
        }
    }

    private void loadPdfDocument() {
        binding.pbLoading.setVisibility(View.VISIBLE);
        binding.tvError.setVisibility(View.GONE);

        Intent intent = getIntent();
        if (intent == null) {
            showError();
            return;
        }

        // 1. Resolve URI, file path, and title from Intent
        String pathExtra = intent.getStringExtra(EXTRA_PDF_PATH);
        String uriExtra = intent.getStringExtra(EXTRA_PDF_URI);
        String customTitle = intent.getStringExtra(EXTRA_DOCUMENT_TITLE);

        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            resolvedUri = intent.getData();
        } else if (uriExtra != null) {
            resolvedUri = Uri.parse(uriExtra);
        } else if (pathExtra != null) {
            resolvedFile = new File(pathExtra);
            resolvedUri = Uri.fromFile(resolvedFile);
        }

        // 2. Resolve document title
        if (customTitle != null && !customTitle.trim().isEmpty()) {
            documentTitle = customTitle;
        } else if (resolvedUri != null) {
            documentTitle = queryFileName(resolvedUri);
        } else if (resolvedFile != null) {
            documentTitle = resolvedFile.getName();
        }
        binding.tvTitle.setText(documentTitle);

        // 3. Open ParcelFileDescriptor
        try {
            if (resolvedUri != null) {
                if ("file".equalsIgnoreCase(resolvedUri.getScheme())) {
                    resolvedFile = new File(resolvedUri.getPath());
                    pfd = ParcelFileDescriptor.open(resolvedFile, ParcelFileDescriptor.MODE_READ_ONLY);
                } else {
                    pfd = getContentResolver().openFileDescriptor(resolvedUri, "r");
                }
            } else if (resolvedFile != null) {
                pfd = ParcelFileDescriptor.open(resolvedFile, ParcelFileDescriptor.MODE_READ_ONLY);
            }

            if (pfd == null) {
                Log.e(TAG, "Failed to open file descriptor for PDF");
                showError();
                return;
            }

            // 4. Initialize PdfRenderer
            pdfRenderer = new PdfRenderer(pfd);
            pageCount = pdfRenderer.getPageCount();

            if (pageCount == 0) {
                showError();
                return;
            }

            // 5. Setup RecyclerView & Adapter
            layoutManager = new LinearLayoutManager(this);
            binding.rvPdfPages.setLayoutManager(layoutManager);

            pageAdapter = new PdfPageAdapter(pdfRenderer);
            binding.rvPdfPages.setAdapter(pageAdapter);

            updatePageIndicator(1);

            binding.rvPdfPages.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                    super.onScrolled(recyclerView, dx, dy);
                    if (dy != 0 || dx != 0) {
                        dismissLensOverlay();
                    }
                    if (layoutManager != null) {
                        int firstVisible = layoutManager.findFirstVisibleItemPosition();
                        if (firstVisible >= 0 && firstVisible < pageCount) {
                            updatePageIndicator(firstVisible + 1);
                        }
                    }
                }
            });

            binding.pbLoading.setVisibility(View.GONE);

        } catch (Exception e) {
            Log.e(TAG, "Error opening PDF", e);
            showError();
        }
    }

    private void updatePageIndicator(int currentPage) {
        binding.tvPageIndicator.setText(getString(R.string.pdf_page_indicator, currentPage, pageCount));
    }

    private void showError() {
        binding.pbLoading.setVisibility(View.GONE);
        binding.tvError.setVisibility(View.VISIBLE);
        Toast.makeText(this, R.string.pdf_load_error, Toast.LENGTH_SHORT).show();
    }

    // ── Printing Integration ─────────────────────────────────────────────

    private void printDocument() {
        if (pageCount == 0) {
            Toast.makeText(this, R.string.print_error, Toast.LENGTH_SHORT).show();
            return;
        }

        PrintManager printManager = (PrintManager) getSystemService(Context.PRINT_SERVICE);
        if (printManager == null) {
            Toast.makeText(this, R.string.print_error, Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            PdfPrintDocumentAdapter printAdapter;
            if (resolvedUri != null) {
                printAdapter = new PdfPrintDocumentAdapter(this, resolvedUri, documentTitle, pageCount);
            } else if (resolvedFile != null) {
                printAdapter = new PdfPrintDocumentAdapter(this, resolvedFile, documentTitle, pageCount);
            } else {
                Toast.makeText(this, R.string.print_error, Toast.LENGTH_SHORT).show();
                return;
            }

            String jobName = getString(R.string.app_name) + " - " + documentTitle;
            printManager.print(jobName, printAdapter, new PrintAttributes.Builder().build());

        } catch (Exception e) {
            Log.e(TAG, "Printing failed", e);
            Toast.makeText(this, R.string.print_error, Toast.LENGTH_SHORT).show();
        }
    }

    // ── Editing Integration ──────────────────────────────────────────────

    private void editCurrentPage() {
        if (pageAdapter == null || layoutManager == null) return;

        int currentVisiblePage = layoutManager.findFirstVisibleItemPosition();
        if (currentVisiblePage < 0) currentVisiblePage = 0;

        final int pageToEdit = currentVisiblePage;
        binding.pbLoading.setVisibility(View.VISIBLE);

        // Render visible page at high resolution (1600px width)
        pageAdapter.renderPageHighRes(pageToEdit, 1600, new PdfPageAdapter.OnPageRenderedListener() {
            @Override
            public void onPageRendered(Bitmap bitmap) {
                binding.pbLoading.setVisibility(View.GONE);

                // Save to temporary scan cache on disk and recycle in-memory bitmap
                String tempPath = CacheManager.saveTempBitmap(
                        PdfViewerActivity.this, bitmap, UUID.randomUUID().toString());
                bitmap.recycle();

                if (tempPath != null) {
                    Intent cropIntent = new Intent(PdfViewerActivity.this, CropPreviewActivity.class);
                    cropIntent.putExtra(CropPreviewActivity.EXTRA_IMAGE_PATH, tempPath);
                    startActivity(cropIntent);
                } else {
                    Toast.makeText(PdfViewerActivity.this, R.string.error_generic, Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onRenderFailed(Exception e) {
                binding.pbLoading.setVisibility(View.GONE);
                Toast.makeText(PdfViewerActivity.this, R.string.error_generic, Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ── OCR Text Extraction (ML Kit) ─────────────────────────────────────

    private void extractTextFromCurrentPage() {
        if (binding.lensOverlay.getVisibility() == View.VISIBLE) {
            dismissLensOverlay();
            return;
        }

        if (pageAdapter == null || layoutManager == null) return;

        int currentVisiblePage = layoutManager.findFirstVisibleItemPosition();
        if (currentVisiblePage < 0) currentVisiblePage = 0;

        final int pageToExtract = currentVisiblePage;
        binding.pbLoading.setVisibility(View.VISIBLE);

        // Render visible page at high resolution (1600px width for maximum OCR accuracy)
        pageAdapter.renderPageHighRes(pageToExtract, 1600, new PdfPageAdapter.OnPageRenderedListener() {
            @Override
            public void onPageRendered(Bitmap bitmap) {
                if (bitmap == null) {
                    binding.pbLoading.setVisibility(View.GONE);
                    Toast.makeText(PdfViewerActivity.this, R.string.ocr_error, Toast.LENGTH_SHORT).show();
                    return;
                }

                final int bitmapWidth = bitmap.getWidth();
                final int bitmapHeight = bitmap.getHeight();

                OcrHelper.extractText(bitmap, PdfViewerActivity.this, new OcrHelper.OcrCallback() {
                    @Override
                    public void onSuccess(Text visionText) {
                        binding.pbLoading.setVisibility(View.GONE);
                        if (!bitmap.isRecycled()) {
                            bitmap.recycle();
                        }

                        if (isFinishing() || isDestroyed()) return;

                        if (visionText == null || visionText.getTextBlocks().isEmpty()) {
                            Toast.makeText(PdfViewerActivity.this, R.string.ocr_empty, Toast.LENGTH_SHORT).show();
                            return;
                        }

                        // Align overlay perfectly over the visible page item
                        View pageView = layoutManager.findViewByPosition(pageToExtract);
                        View pageImageView = (pageView != null) ? pageView.findViewById(R.id.ivPdfPage) : null;
                        if (pageImageView != null && pageImageView.getWidth() > 0 && pageImageView.getHeight() > 0) {
                            int[] overlayLocation = new int[2];
                            binding.lensOverlay.getLocationOnScreen(overlayLocation);
                            int[] imgLocation = new int[2];
                            pageImageView.getLocationOnScreen(imgLocation);
                            float left = imgLocation[0] - overlayLocation[0];
                            float top = imgLocation[1] - overlayLocation[1];
                            RectF targetRect = new RectF(left, top, left + pageImageView.getWidth(), top + pageImageView.getHeight());
                            binding.lensOverlay.setTargetRect(targetRect);
                        } else {
                            binding.lensOverlay.setTargetRect(null);
                        }

                        binding.lensOverlay.setVisionText(visionText, bitmapWidth, bitmapHeight);
                        binding.lensOverlay.setVisibility(View.VISIBLE);
                        Toast.makeText(PdfViewerActivity.this, R.string.ocr_lens_hint, Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onError(Exception e) {
                        binding.pbLoading.setVisibility(View.GONE);
                        if (!bitmap.isRecycled()) {
                            bitmap.recycle();
                        }
                        Log.e(TAG, "OCR text extraction failed", e);
                        Toast.makeText(PdfViewerActivity.this, R.string.ocr_error, Toast.LENGTH_SHORT).show();
                    }
                });
            }

            @Override
            public void onRenderFailed(Exception e) {
                binding.pbLoading.setVisibility(View.GONE);
                Toast.makeText(PdfViewerActivity.this, R.string.ocr_error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private String queryFileName(Uri uri) {
        String result = null;
        if ("content".equalsIgnoreCase(uri.getScheme())) {
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (nameIndex != -1) {
                        result = cursor.getString(nameIndex);
                    }
                }
            } catch (Exception ignored) {}
        }
        if (result == null) {
            result = uri.getLastPathSegment();
        }
        return result != null ? result : "Document.pdf";
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (pageAdapter != null) {
            pageAdapter.cleanup();
        }

        if (pdfRenderer != null) {
            try {
                pdfRenderer.close();
            } catch (Exception ignored) {}
            pdfRenderer = null;
        }

        if (pfd != null) {
            try {
                pfd.close();
            } catch (Exception ignored) {}
            pfd = null;
        }
    }
}
