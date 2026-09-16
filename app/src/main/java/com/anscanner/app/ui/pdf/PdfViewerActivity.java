package com.anscanner.app.ui.pdf;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import android.graphics.pdf.PdfDocument;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.print.PrintHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.anscanner.app.R;
import com.anscanner.app.databinding.ActivityPdfViewerBinding;
import com.anscanner.app.service.CacheManager;
import com.anscanner.app.service.OcrHelper;
import com.anscanner.app.ui.crop.CropPreviewActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.mlkit.vision.text.Text;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.UUID;

/**
 * Multi-format Document Viewer Activity supporting PDF and JPG/PNG documents.
 *
 * <p>Supports:
 * <ul>
 *   <li>System-wide "Open With" handling via {@link Intent#ACTION_VIEW} for PDFs and Images.</li>
 *   <li>Native {@link com.github.chrisbanes.photoview.PhotoView} pinch-to-zoom for JPG scans.</li>
 *   <li>High-performance continuous vertical page scrolling for multi-page PDFs with {@link PdfRenderer}.</li>
 *   <li>Multi-page ML Kit OCR text extraction loop with ProgressDialog and full text export.</li>
 *   <li>On-image Google Lens OCR overlay.</li>
 *   <li>Flattened vector signature/drawing annotation engine with in-place document overwrite.</li>
 *   <li>Native system printing via {@link PrintManager} or {@link PrintHelper}.</li>
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

    // Image viewer and annotation states
    private Bitmap currentImageBitmap;
    private Bitmap currentAnnotatedPageBitmap;
    private int currentAnnotatedPageIndex = 0;
    private boolean isAnnotationMode = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityPdfViewerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setupToolbar();
        setupAnnotationControls();
        loadPdfDocument();
    }

    private void setupToolbar() {
        binding.btnBack.setOnClickListener(v -> finish());
        binding.btnPrint.setOnClickListener(v -> printDocument());
        binding.btnExtractText.setOnClickListener(v -> onExtractTextClicked());
        binding.btnAnnotate.setOnClickListener(v -> toggleAnnotationMode());
        binding.btnEdit.setOnClickListener(v -> editCurrentPage());

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (isAnnotationMode) {
                    exitAnnotationMode();
                } else if (binding.lensOverlay.getVisibility() == View.VISIBLE) {
                    dismissLensOverlay();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
    }

    private void setupAnnotationControls() {
        binding.btnCloseAnnotation.setOnClickListener(v -> exitAnnotationMode());
        binding.btnUndoDrawing.setOnClickListener(v -> binding.drawingOverlay.undo());
        binding.btnClearDrawing.setOnClickListener(v -> binding.drawingOverlay.clear());
        binding.btnSaveAnnotations.setOnClickListener(v -> saveAnnotations());

        binding.colorMint.setOnClickListener(v -> binding.drawingOverlay.setStrokeColor(Color.parseColor("#48BB78")));
        binding.colorRed.setOnClickListener(v -> binding.drawingOverlay.setStrokeColor(Color.parseColor("#E53E3E")));
        binding.colorBlack.setOnClickListener(v -> binding.drawingOverlay.setStrokeColor(Color.BLACK));
        binding.colorWhite.setOnClickListener(v -> binding.drawingOverlay.setStrokeColor(Color.WHITE));
    }

    private void dismissLensOverlay() {
        if (binding.lensOverlay.getVisibility() == View.VISIBLE) {
            binding.lensOverlay.setVisibility(View.GONE);
            binding.lensOverlay.clear();
        }
    }

    private boolean isImageDocument() {
        if (documentTitle != null) {
            String lower = documentTitle.toLowerCase(Locale.US);
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")) {
                return true;
            }
        }
        if (resolvedFile != null) {
            String lower = resolvedFile.getName().toLowerCase(Locale.US);
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")) {
                return true;
            }
        }
        if (resolvedUri != null) {
            try {
                String type = getContentResolver().getType(resolvedUri);
                if (type != null && type.startsWith("image/")) {
                    return true;
                }
            } catch (Exception ignored) {}
            String path = resolvedUri.getPath();
            if (path != null) {
                String lower = path.toLowerCase(Locale.US);
                if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")) {
                    return true;
                }
            }
        }
        return false;
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

        // 3. Check for JPG / Image document
        if (isImageDocument()) {
            loadImageDocument();
            return;
        }

        // 4. Otherwise, open as PDF
        try {
            cleanupPdfResources();

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

            // 5. Initialize PdfRenderer
            pdfRenderer = new PdfRenderer(pfd);
            pageCount = pdfRenderer.getPageCount();

            if (pageCount == 0) {
                showError();
                return;
            }

            binding.photoView.setVisibility(View.GONE);
            binding.rvPdfPages.setVisibility(View.VISIBLE);

            // 6. Setup RecyclerView & Adapter
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

    private void loadImageDocument() {
        new Thread(() -> {
            try {
                Bitmap bitmap = null;
                if (resolvedUri != null) {
                    try (InputStream is = getContentResolver().openInputStream(resolvedUri)) {
                        bitmap = BitmapFactory.decodeStream(is);
                    }
                } else if (resolvedFile != null) {
                    bitmap = BitmapFactory.decodeFile(resolvedFile.getAbsolutePath());
                }

                if (bitmap == null) {
                    runOnUiThread(this::showError);
                    return;
                }

                final Bitmap finalBitmap = bitmap;
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        finalBitmap.recycle();
                        return;
                    }
                    if (currentImageBitmap != null && !currentImageBitmap.isRecycled()) {
                        currentImageBitmap.recycle();
                    }
                    currentImageBitmap = finalBitmap;
                    pageCount = 1;

                    binding.rvPdfPages.setVisibility(View.GONE);
                    binding.photoView.setImageBitmap(currentImageBitmap);
                    binding.photoView.setVisibility(View.VISIBLE);
                    binding.tvPageIndicator.setText("1 of 1");
                    binding.pbLoading.setVisibility(View.GONE);
                });

            } catch (Exception e) {
                Log.e(TAG, "Failed to load image document", e);
                runOnUiThread(this::showError);
            }
        }).start();
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
        if (pdfRenderer != null && pageCount > 0) {
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
                Log.e(TAG, "Printing PDF failed", e);
                Toast.makeText(this, R.string.print_error, Toast.LENGTH_SHORT).show();
            }
        } else if (currentImageBitmap != null) {
            try {
                PrintHelper photoPrinter = new PrintHelper(this);
                photoPrinter.setScaleMode(PrintHelper.SCALE_MODE_FIT);
                photoPrinter.printBitmap(documentTitle, currentImageBitmap);
            } catch (Exception e) {
                Log.e(TAG, "Printing image failed", e);
                Toast.makeText(this, R.string.print_error, Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(this, R.string.print_error, Toast.LENGTH_SHORT).show();
        }
    }

    // ── Editing Integration ──────────────────────────────────────────────

    private void editCurrentPage() {
        if (pdfRenderer != null && pageAdapter != null && layoutManager != null) {
            int currentVisiblePage = layoutManager.findFirstVisibleItemPosition();
            if (currentVisiblePage < 0) currentVisiblePage = 0;

            final int pageToEdit = currentVisiblePage;
            binding.pbLoading.setVisibility(View.VISIBLE);

            pageAdapter.renderPageHighRes(pageToEdit, 1600, new PdfPageAdapter.OnPageRenderedListener() {
                @Override
                public void onPageRendered(Bitmap bitmap) {
                    binding.pbLoading.setVisibility(View.GONE);
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
        } else if (currentImageBitmap != null) {
            String tempPath = CacheManager.saveTempBitmap(
                    this, currentImageBitmap, UUID.randomUUID().toString());
            if (tempPath != null) {
                Intent cropIntent = new Intent(this, CropPreviewActivity.class);
                cropIntent.putExtra(CropPreviewActivity.EXTRA_IMAGE_PATH, tempPath);
                startActivity(cropIntent);
            } else {
                Toast.makeText(this, R.string.error_generic, Toast.LENGTH_SHORT).show();
            }
        }
    }

    // ── OCR Text Extraction (Multi-Page & Lens) ──────────────────────────

    private void onExtractTextClicked() {
        if (binding.lensOverlay.getVisibility() == View.VISIBLE) {
            dismissLensOverlay();
            return;
        }

        if (pdfRenderer != null) {
            // Multi-Page PDF OCR extraction using OcrHelper
            OcrHelper.extractTextFromPdf(this, pdfRenderer, new OcrHelper.MultiPageOcrCallback() {
                @Override
                public void onSuccess(String fullText) {
                    if (isFinishing() || isDestroyed()) return;
                    if (fullText == null || fullText.trim().isEmpty()) {
                        Toast.makeText(PdfViewerActivity.this, R.string.ocr_no_text, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    showExtractedTextResult(fullText);
                }

                @Override
                public void onError(Exception e) {
                    if (isFinishing() || isDestroyed()) return;
                    Log.e(TAG, "Multi-page OCR extraction failed", e);
                    Toast.makeText(PdfViewerActivity.this, R.string.ocr_error, Toast.LENGTH_SHORT).show();
                }
            });

        } else if (currentImageBitmap != null) {
            // Interactive Lens OCR overlay for single image
            binding.pbLoading.setVisibility(View.VISIBLE);
            OcrHelper.extractText(currentImageBitmap, this, new OcrHelper.OcrCallback() {
                @Override
                public void onSuccess(Text visionText) {
                    binding.pbLoading.setVisibility(View.GONE);
                    if (isFinishing() || isDestroyed()) return;

                    if (visionText == null || visionText.getTextBlocks().isEmpty()) {
                        Toast.makeText(PdfViewerActivity.this, R.string.ocr_empty, Toast.LENGTH_SHORT).show();
                        return;
                    }

                    RectF displayRect = binding.photoView.getDisplayRect();
                    binding.lensOverlay.setTargetRect(displayRect);
                    binding.lensOverlay.setVisionText(visionText, currentImageBitmap.getWidth(), currentImageBitmap.getHeight());
                    binding.lensOverlay.setVisibility(View.VISIBLE);
                    Toast.makeText(PdfViewerActivity.this, R.string.ocr_lens_hint, Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onError(Exception e) {
                    binding.pbLoading.setVisibility(View.GONE);
                    if (isFinishing() || isDestroyed()) return;
                    Toast.makeText(PdfViewerActivity.this, R.string.ocr_error, Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private void showExtractedTextResult(String text) {
        ScrollView scrollView = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextIsSelectable(true);
        tv.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        tv.setTextSize(14f);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad, pad, pad);
        scrollView.addView(tv);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.ocr_dialog_title)
                .setView(scrollView)
                .setPositiveButton(R.string.ocr_dialog_copy, (dialog, which) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("Extracted Text", text));
                        Toast.makeText(this, R.string.ocr_copied, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNeutralButton(R.string.ocr_dialog_share, (dialog, which) -> {
                    Intent shareIntent = new Intent(Intent.ACTION_SEND);
                    shareIntent.setType("text/plain");
                    shareIntent.putExtra(Intent.EXTRA_TEXT, text);
                    startActivity(Intent.createChooser(shareIntent, "Share Extracted Text"));
                })
                .setNegativeButton(R.string.action_close, null)
                .show();
    }

    // ── Flattened Annotation Engine (Sign/Draw) ──────────────────────────

    private void toggleAnnotationMode() {
        if (isAnnotationMode) {
            exitAnnotationMode();
            return;
        }

        if (pdfRenderer != null && pageAdapter != null && layoutManager != null) {
            int visiblePage = layoutManager.findFirstVisibleItemPosition();
            if (visiblePage < 0 || visiblePage >= pageCount) visiblePage = 0;
            currentAnnotatedPageIndex = visiblePage;

            binding.pbLoading.setVisibility(View.VISIBLE);
            pageAdapter.renderPageHighRes(visiblePage, 1600, new PdfPageAdapter.OnPageRenderedListener() {
                @Override
                public void onPageRendered(Bitmap bitmap) {
                    binding.pbLoading.setVisibility(View.GONE);
                    if (currentAnnotatedPageBitmap != null && !currentAnnotatedPageBitmap.isRecycled()) {
                        currentAnnotatedPageBitmap.recycle();
                    }
                    currentAnnotatedPageBitmap = bitmap;
                    binding.photoView.setImageBitmap(bitmap);
                    binding.photoView.setVisibility(View.VISIBLE);
                    binding.rvPdfPages.setVisibility(View.GONE);
                    startDrawingSession();
                }

                @Override
                public void onRenderFailed(Exception e) {
                    binding.pbLoading.setVisibility(View.GONE);
                    Toast.makeText(PdfViewerActivity.this, R.string.error_generic, Toast.LENGTH_SHORT).show();
                }
            });
        } else if (currentImageBitmap != null) {
            startDrawingSession();
        } else {
            Toast.makeText(this, R.string.error_generic, Toast.LENGTH_SHORT).show();
        }
    }

    private void startDrawingSession() {
        isAnnotationMode = true;
        dismissLensOverlay();
        binding.drawingOverlay.clear();
        binding.drawingOverlay.setVisibility(View.VISIBLE);
        binding.drawingOverlay.setDrawingEnabled(true);
        binding.layoutAnnotationBar.setVisibility(View.VISIBLE);
    }

    private void exitAnnotationMode() {
        isAnnotationMode = false;
        binding.drawingOverlay.clear();
        binding.drawingOverlay.setVisibility(View.GONE);
        binding.drawingOverlay.setDrawingEnabled(false);
        binding.layoutAnnotationBar.setVisibility(View.GONE);

        if (pdfRenderer != null) {
            binding.photoView.setVisibility(View.GONE);
            binding.rvPdfPages.setVisibility(View.VISIBLE);
            if (currentAnnotatedPageBitmap != null && !currentAnnotatedPageBitmap.isRecycled()) {
                currentAnnotatedPageBitmap.recycle();
                currentAnnotatedPageBitmap = null;
            }
        }
    }

    private void saveAnnotations() {
        if (binding.drawingOverlay.isEmpty()) {
            Toast.makeText(this, R.string.annotate_empty, Toast.LENGTH_SHORT).show();
            return;
        }

        binding.pbLoading.setVisibility(View.VISIBLE);

        final RectF displayRect = binding.photoView.getDisplayRect();
        final int annotatedPage = currentAnnotatedPageIndex;

        new Thread(() -> {
            try {
                if (pdfRenderer != null) {
                    // Create new PdfDocument and flatten drawing directly on top
                    PdfDocument newPdf = new PdfDocument();
                    int totalPages = pdfRenderer.getPageCount();

                    for (int i = 0; i < totalPages; i++) {
                        PdfRenderer.Page origPage = pdfRenderer.openPage(i);
                        int pWidth = origPage.getWidth();
                        int pHeight = origPage.getHeight();

                        PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(pWidth, pHeight, i + 1).create();
                        PdfDocument.Page newPage = newPdf.startPage(pageInfo);
                        Canvas canvas = newPage.getCanvas();

                        // Render original page to temporary bitmap
                        Bitmap pageBitmap = Bitmap.createBitmap(pWidth, pHeight, Bitmap.Config.ARGB_8888);
                        origPage.render(pageBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        canvas.drawBitmap(pageBitmap, 0, 0, null);
                        pageBitmap.recycle();
                        origPage.close();

                        // Draw vector strokes directly on top of annotated page
                        if (i == annotatedPage) {
                            binding.drawingOverlay.drawToCanvas(canvas, displayRect, pWidth, pHeight);
                        }

                        newPdf.finishPage(newPage);
                    }

                    // Write new PDF to cache
                    File tempPdf = new File(getCacheDir(), "annotated_" + System.currentTimeMillis() + ".pdf");
                    try (FileOutputStream fos = new FileOutputStream(tempPdf)) {
                        newPdf.writeTo(fos);
                    }
                    newPdf.close();

                    // Replace old file with the new flattened PDF
                    replaceDocumentFile(tempPdf);
                    tempPdf.delete();

                } else if (currentImageBitmap != null) {
                    // Flatten drawing onto image bitmap
                    int w = currentImageBitmap.getWidth();
                    int h = currentImageBitmap.getHeight();
                    Bitmap flattened = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    Canvas canvas = new Canvas(flattened);
                    canvas.drawBitmap(currentImageBitmap, 0, 0, null);
                    binding.drawingOverlay.drawToCanvas(canvas, displayRect, w, h);

                    // Write flattened JPG to cache
                    File tempImg = new File(getCacheDir(), "annotated_" + System.currentTimeMillis() + ".jpg");
                    try (FileOutputStream fos = new FileOutputStream(tempImg)) {
                        flattened.compress(Bitmap.CompressFormat.JPEG, 95, fos);
                    }

                    // Replace old file with the new flattened JPG
                    replaceDocumentFile(tempImg);
                    tempImg.delete();

                    if (!currentImageBitmap.isRecycled()) {
                        currentImageBitmap.recycle();
                    }
                    currentImageBitmap = flattened;
                }

                runOnUiThread(() -> {
                    binding.pbLoading.setVisibility(View.GONE);
                    Toast.makeText(this, R.string.annotate_saved, Toast.LENGTH_SHORT).show();
                    exitAnnotationMode();

                    if (pdfRenderer != null) {
                        loadPdfDocument();
                    } else if (currentImageBitmap != null) {
                        binding.photoView.setImageBitmap(currentImageBitmap);
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "Failed to save flattened annotations", e);
                runOnUiThread(() -> {
                    binding.pbLoading.setVisibility(View.GONE);
                    Toast.makeText(this, R.string.error_storage, Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private void replaceDocumentFile(File sourceFile) throws IOException {
        cleanupPdfResources();

        if (resolvedFile != null && resolvedFile.exists()) {
            try (InputStream in = new FileInputStream(sourceFile);
                 OutputStream out = new FileOutputStream(resolvedFile)) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
                out.flush();
            }
        } else if (resolvedUri != null) {
            try (InputStream in = new FileInputStream(sourceFile);
                 OutputStream out = getContentResolver().openOutputStream(resolvedUri, "wt")) {
                if (out != null) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) > 0) {
                        out.write(buf, 0, len);
                    }
                    out.flush();
                }
            }
        }
    }

    private void cleanupPdfResources() {
        if (pageAdapter != null) {
            pageAdapter.cleanup();
            pageAdapter = null;
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
        cleanupPdfResources();

        if (currentImageBitmap != null && !currentImageBitmap.isRecycled()) {
            currentImageBitmap.recycle();
            currentImageBitmap = null;
        }

        if (currentAnnotatedPageBitmap != null && !currentAnnotatedPageBitmap.isRecycled()) {
            currentAnnotatedPageBitmap.recycle();
            currentAnnotatedPageBitmap = null;
        }
    }
}
