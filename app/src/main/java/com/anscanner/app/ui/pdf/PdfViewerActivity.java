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
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.provider.OpenableColumns;
import android.content.SharedPreferences;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.print.PrintHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.anscanner.app.R;
import com.anscanner.app.databinding.ActivityPdfViewerBinding;
import com.anscanner.app.service.CacheManager;
import com.anscanner.app.service.OcrHelper;
import com.anscanner.app.ui.crop.CropPreviewActivity;
import com.anscanner.app.ui.crop.EditPdfActivity;
import com.anscanner.app.ui.custom.OcrTextOverlayView;
import com.anscanner.app.ui.editor.UnifiedEditorActivity;
import com.anscanner.app.util.PdfRendererHelper;
import com.google.android.gms.ads.AdRequest;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.mlkit.vision.text.Text;

import java.io.File;
import java.util.ArrayList;
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
 *   <li>Kindle-style reading mode with themes (Light/Sepia/Dark/Night) and page margins.</li>
 *   <li>Interactive in-place OCR text overlay with direct word selection & copy bar.</li>
 *   <li>Native {@link com.github.chrisbanes.photoview.PhotoView} pinch-to-zoom for JPG scans.</li>
 *   <li>High-performance continuous vertical page scrolling for multi-page PDFs with {@link PdfRenderer}.</li>
 *   <li>Flattened vector signature/drawing annotation engine with in-place document overwrite.</li>
 *   <li>Native system printing via {@link PrintManager} or {@link PrintHelper}.</li>
 * </ul>
 * </p>
 */
public class PdfViewerActivity extends AppCompatActivity implements ReadingModeBottomSheet.OnReadingSettingsChangedListener {

    private static final String TAG = "PdfViewerActivity";

    public static final String EXTRA_PDF_PATH = "extra_pdf_path";
    public static final String EXTRA_PDF_URI = "extra_pdf_uri";
    public static final String EXTRA_DOCUMENT_TITLE = "extra_document_title";

    private ActivityPdfViewerBinding binding;
    private ParcelFileDescriptor pfd;
    private PdfRenderer pdfRenderer;
    private PdfPageAdapter pageAdapter;
    private LinearLayoutManager layoutManager;
    private PagerSnapHelper pagerSnapHelper;

    private Uri resolvedUri;
    private File resolvedFile;
    private File decryptedTempFile = null;
    private String documentTitle = "Document";
    private int pageCount = 0;

    // Image viewer and annotation states
    private Bitmap currentImageBitmap;
    private Bitmap currentAnnotatedPageBitmap;
    private int currentAnnotatedPageIndex = 0;
    private boolean isAnnotationMode = false;

    // In-place interactive OCR state
    private int currentOcrPageIndex = 0;
    private Bitmap currentOcrBitmap;

    // Kindle-Style E-Reader mode state
    private boolean isInEreaderMode = false;
    private final Handler badgeHandler = new Handler(Looper.getMainLooper());
    private final Runnable hideBadgeRunnable = () -> {
        if (binding != null && binding.tvEreaderPageBadge != null) {
            binding.tvEreaderPageBadge.animate().alpha(0f).setDuration(400).withEndAction(() -> {
                if (binding != null && binding.tvEreaderPageBadge != null) {
                    binding.tvEreaderPageBadge.setVisibility(View.GONE);
                }
            }).start();
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityPdfViewerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setupToolbar();
        setupOcrControls();
        setupAnnotationControls();
        setupAds();
        loadPdfDocument();
    }

    private void setupToolbar() {
        binding.btnBack.setOnClickListener(v -> finish());
        binding.btnPrint.setOnClickListener(v -> printDocument());
        binding.btnReadingMode.setOnClickListener(v -> enableKindleReadingMode());
        binding.btnExtractText.setOnClickListener(v -> onExtractTextClicked());
        binding.btnAnnotate.setOnClickListener(v -> toggleAnnotationMode());
        binding.btnEdit.setOnClickListener(v -> editCurrentPage());

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (isInEreaderMode) {
                    exitKindleReadingMode();
                } else if (isOcrActive()) {
                    dismissOcrOverlay();
                } else if (isAnnotationMode) {
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

        // 1. Resolve URI, file path, and title from Intent (unless already unlocked from decrypted temp file)
        if (decryptedTempFile != null && decryptedTempFile.exists()) {
            resolvedFile = decryptedTempFile;
            resolvedUri = Uri.fromFile(decryptedTempFile);
        } else {
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
        }
        if (documentTitle != null) {
            binding.tvTitle.setText(documentTitle);
        }

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
            pageAdapter.setOnPageClickListener(position -> toggleImmersiveReadingMode());
            loadInitialReadingPreferences();

            updatePageIndicator(1);

            binding.rvPdfPages.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                    super.onScrolled(recyclerView, dx, dy);
                    if (dy != 0 || dx != 0) {
                        dismissLensOverlay();
                        if (isOcrActive()) {
                            dismissOcrOverlay();
                        }
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

        } catch (SecurityException se) {
            Log.w(TAG, "PDF is password protected", se);
            binding.pbLoading.setVisibility(View.GONE);
            promptPdfPassword();
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().toLowerCase().contains("password")) {
                binding.pbLoading.setVisibility(View.GONE);
                promptPdfPassword();
            } else {
                Log.e(TAG, "Error opening PDF", e);
                showError();
            }
        }
    }

    private void promptPdfPassword() {
        if (isFinishing() || isDestroyed()) return;

        android.widget.FrameLayout container = new android.widget.FrameLayout(this);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        container.setPadding(padding, padding / 2, padding, 0);

        com.google.android.material.textfield.TextInputLayout til = new com.google.android.material.textfield.TextInputLayout(this);
        til.setHint(getString(R.string.save_password_hint));
        til.setEndIconMode(com.google.android.material.textfield.TextInputLayout.END_ICON_PASSWORD_TOGGLE);
        til.setBoxBackgroundMode(com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE);
        til.setBoxStrokeColor(ContextCompat.getColor(this, R.color.accent_mint));
        til.setDefaultHintTextColor(android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, R.color.text_secondary)));

        com.google.android.material.textfield.TextInputEditText et = new com.google.android.material.textfield.TextInputEditText(this);
        et.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        et.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        et.setHintTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        til.addView(et);
        container.addView(til);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.pdf_password_protected_dialog_title)
                .setMessage(R.string.pdf_password_dialog_prompt)
                .setView(container)
                .setCancelable(false)
                .setPositiveButton(R.string.action_unlock, (dialog, which) -> {
                    String password = et.getText() != null ? et.getText().toString().trim() : "";
                    unlockAndOpenPdf(password);
                })
                .setNegativeButton(R.string.action_cancel, (dialog, which) -> finish())
                .show();
    }

    private void unlockAndOpenPdf(String password) {
        binding.pbLoading.setVisibility(View.VISIBLE);
        new Thread(() -> {
            File tempLocked = null;
            try {
                // Copy source PDF to temp cache file for PDFBox processing
                tempLocked = new File(getCacheDir(), "temp_locked_" + System.currentTimeMillis() + ".pdf");
                if (resolvedUri != null) {
                    try (InputStream in = getContentResolver().openInputStream(resolvedUri);
                         FileOutputStream out = new FileOutputStream(tempLocked)) {
                        byte[] buf = new byte[8192];
                        int len;
                        while ((len = in.read(buf)) > 0) {
                            out.write(buf, 0, len);
                        }
                    }
                } else if (resolvedFile != null) {
                    try (InputStream in = new FileInputStream(resolvedFile);
                         FileOutputStream out = new FileOutputStream(tempLocked)) {
                        byte[] buf = new byte[8192];
                        int len;
                        while ((len = in.read(buf)) > 0) {
                            out.write(buf, 0, len);
                        }
                    }
                }

                com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(getApplicationContext());
                try (com.tom_roush.pdfbox.pdmodel.PDDocument doc = com.tom_roush.pdfbox.pdmodel.PDDocument.load(tempLocked, password)) {
                    doc.setAllSecurityToBeRemoved(true);
                    File unlockedFile = new File(getCacheDir(), "unlocked_" + System.currentTimeMillis() + ".pdf");
                    doc.save(unlockedFile);

                    runOnUiThread(() -> {
                        if (decryptedTempFile != null && decryptedTempFile.exists() && !decryptedTempFile.equals(unlockedFile)) {
                            decryptedTempFile.delete();
                        }
                        decryptedTempFile = unlockedFile;
                        resolvedFile = unlockedFile;
                        resolvedUri = Uri.fromFile(unlockedFile);
                        loadPdfDocument();
                    });
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to decrypt PDF with password", e);
                runOnUiThread(() -> {
                    binding.pbLoading.setVisibility(View.GONE);
                    Toast.makeText(this, R.string.pdf_wrong_password, Toast.LENGTH_SHORT).show();
                    promptPdfPassword();
                });
            } finally {
                if (tempLocked != null && tempLocked.exists()) {
                    tempLocked.delete();
                }
            }
        }).start();
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
                    binding.photoView.setScale(1.0f, false);
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
        if (pdfRenderer != null) {
            int currentVisiblePage = 0;
            if (layoutManager != null) {
                currentVisiblePage = layoutManager.findFirstVisibleItemPosition();
                if (currentVisiblePage < 0) currentVisiblePage = 0;
            }
            final int initialIndex = currentVisiblePage;
            binding.pbLoading.setVisibility(View.VISIBLE);

            new Thread(() -> {
                ArrayList<String> allPages = null;
                if (resolvedUri != null) {
                    allPages = CropPreviewActivity.extractAllPagesFromPdf(PdfViewerActivity.this, resolvedUri);
                } else if (resolvedFile != null) {
                    allPages = CropPreviewActivity.extractAllPagesFromPdf(PdfViewerActivity.this, Uri.fromFile(resolvedFile));
                }

                final ArrayList<String> finalPages = allPages;
                runOnUiThread(() -> {
                    binding.pbLoading.setVisibility(View.GONE);
                    if (finalPages != null && !finalPages.isEmpty()) {
                        Intent cropIntent = new Intent(PdfViewerActivity.this, UnifiedEditorActivity.class);
                        cropIntent.putStringArrayListExtra(UnifiedEditorActivity.EXTRA_PAGE_PATHS, finalPages);
                        cropIntent.putStringArrayListExtra(UnifiedEditorActivity.EXTRA_ORIGINAL_PAGE_PATHS, new ArrayList<>(finalPages));
                        cropIntent.putExtra(UnifiedEditorActivity.EXTRA_PAGE_INDEX, initialIndex);
                        cropIntent.putExtra(UnifiedEditorActivity.EXTRA_MODE, UnifiedEditorActivity.MODE_PDF_EDIT);
                        if (resolvedUri != null) {
                            cropIntent.putExtra(UnifiedEditorActivity.EXTRA_PDF_URI, resolvedUri);
                        }
                        startActivity(cropIntent);
                    } else {
                        Toast.makeText(PdfViewerActivity.this, R.string.error_generic, Toast.LENGTH_SHORT).show();
                    }
                });
            }).start();
        } else if (currentImageBitmap != null) {
            String tempPath = CacheManager.saveTempBitmap(
                    this, currentImageBitmap, UUID.randomUUID().toString());
            if (tempPath != null) {
                Intent cropIntent = new Intent(this, UnifiedEditorActivity.class);
                cropIntent.putExtra(UnifiedEditorActivity.EXTRA_IMAGE_PATH, tempPath);
                cropIntent.putExtra(UnifiedEditorActivity.EXTRA_MODE, UnifiedEditorActivity.MODE_PDF_EDIT);
                startActivity(cropIntent);
            } else {
                Toast.makeText(this, R.string.error_generic, Toast.LENGTH_SHORT).show();
            }
        }
    }

    // ── Interactive In-Place OCR Text Overlay (No Popups/Dialogs) ────────

    private void setupOcrControls() {
        binding.btnCloseOcr.setOnClickListener(v -> dismissOcrOverlay());

        binding.btnCopySelected.setOnClickListener(v -> {
            String selected = binding.ocrOverlay.getSelectedText();
            if (selected != null && !selected.trim().isEmpty()) {
                copyTextToClipboard(selected.trim());
                Toast.makeText(this, R.string.ocr_copied, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, R.string.ocr_nothing_selected, Toast.LENGTH_SHORT).show();
            }
        });

        binding.btnCopyAllText.setOnClickListener(v -> {
            String allText = binding.ocrOverlay.getAllText();
            if (!allText.isEmpty()) {
                copyTextToClipboard(allText);
                Toast.makeText(this, R.string.ocr_copied, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, R.string.ocr_empty, Toast.LENGTH_SHORT).show();
            }
        });

        binding.ocrOverlay.setOnTextSelectedListener(new OcrTextOverlayView.OnTextSelectedListener() {
            @Override
            public void onBlockSelected(@NonNull Text.TextBlock block, @NonNull String text) {
                binding.btnCopySelected.setEnabled(true);
                binding.btnCopySelected.setAlpha(1.0f);
            }

            @Override
            public void onSelectionCleared() {
                // Keep buttons accessible
            }
        });
    }

    private boolean isOcrActive() {
        return binding.ocrOverlay.getVisibility() == View.VISIBLE || binding.layoutOcrBar.getVisibility() == View.VISIBLE;
    }

    private void dismissOcrOverlay() {
        binding.ocrOverlay.clear();
        binding.ocrOverlay.setVisibility(View.GONE);
        binding.layoutOcrBar.setVisibility(View.GONE);

        if (currentOcrBitmap != null) {
            if (!currentOcrBitmap.isRecycled() && currentOcrBitmap != currentImageBitmap) {
                currentOcrBitmap.recycle();
            }
            currentOcrBitmap = null;
        }

        if (pdfRenderer != null) {
            binding.photoView.setImageBitmap(null);
            binding.photoView.setVisibility(View.GONE);
            binding.rvPdfPages.setVisibility(View.VISIBLE);
            if (layoutManager != null && currentOcrPageIndex >= 0 && currentOcrPageIndex < pageCount) {
                layoutManager.scrollToPositionWithOffset(currentOcrPageIndex, 0);
                updatePageIndicator(currentOcrPageIndex + 1);
            }
        }
    }

    private void onExtractTextClicked() {
        if (isOcrActive()) {
            dismissOcrOverlay();
            return;
        }
        if (isAnnotationMode) {
            exitAnnotationMode();
        }

        if (pdfRenderer != null) {
            int currentPos = 0;
            if (layoutManager != null) {
                currentPos = layoutManager.findFirstVisibleItemPosition();
                if (currentPos < 0) currentPos = 0;
            }
            currentOcrPageIndex = currentPos;
            extractTextFromPdfPage(currentPos);
        } else if (currentImageBitmap != null) {
            extractTextFromImage(currentImageBitmap);
        }
    }

    private void extractTextFromPdfPage(int pageIndex) {
        binding.pbLoading.setVisibility(View.VISIBLE);

        new Thread(() -> {
            Bitmap pageBitmap = null;
            try {
                synchronized (pdfRenderer) {
                    if (pageIndex < 0 || pageIndex >= pdfRenderer.getPageCount()) {
                        runOnUiThread(() -> {
                            binding.pbLoading.setVisibility(View.GONE);
                            Toast.makeText(PdfViewerActivity.this, R.string.ocr_error, Toast.LENGTH_SHORT).show();
                        });
                        return;
                    }

                    PdfRenderer.Page page = pdfRenderer.openPage(pageIndex);
                    try {
                        int w = Math.min(1600, Math.max(page.getWidth() * 2, 720));
                        int h = Math.round((float) w * page.getHeight() / Math.max(1, page.getWidth()));
                        pageBitmap = PdfRendererHelper.renderPageWithWhiteBackground(page, w, h, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    } finally {
                        page.close();
                    }
                }

                final Bitmap finalBitmap = pageBitmap;
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        if (finalBitmap != null) finalBitmap.recycle();
                        return;
                    }
                    processOcrBitmap(finalBitmap);
                });

            } catch (Exception e) {
                Log.e(TAG, "Error rendering page for OCR", e);
                runOnUiThread(() -> {
                    binding.pbLoading.setVisibility(View.GONE);
                    Toast.makeText(PdfViewerActivity.this, R.string.ocr_error, Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private void extractTextFromImage(Bitmap imageBitmap) {
        binding.pbLoading.setVisibility(View.VISIBLE);
        processOcrBitmap(imageBitmap);
    }

    private void processOcrBitmap(Bitmap bitmap) {
        if (bitmap == null) {
            binding.pbLoading.setVisibility(View.GONE);
            Toast.makeText(this, R.string.ocr_error, Toast.LENGTH_SHORT).show();
            return;
        }

        currentOcrBitmap = bitmap;

        OcrHelper.extractText(bitmap, this, new OcrHelper.OcrCallback() {
            @Override
            public void onSuccess(Text visionText) {
                binding.pbLoading.setVisibility(View.GONE);
                if (isFinishing() || isDestroyed()) return;

                if (visionText == null || visionText.getTextBlocks().isEmpty()) {
                    Toast.makeText(PdfViewerActivity.this, R.string.ocr_empty, Toast.LENGTH_SHORT).show();
                    dismissOcrOverlay();
                    return;
                }

                // Render interactive overlay directly on top of the page bitmap
                if (pdfRenderer != null) {
                    binding.rvPdfPages.setVisibility(View.GONE);
                    binding.photoView.setVisibility(View.VISIBLE);
                    binding.photoView.setScale(1.0f, false);
                    binding.photoView.setImageBitmap(bitmap);
                }

                binding.photoView.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    RectF displayRect = binding.photoView.getDisplayRect();
                    binding.ocrOverlay.setTargetRect(displayRect);
                    binding.ocrOverlay.setVisionText(visionText, bitmap.getWidth(), bitmap.getHeight());
                    binding.ocrOverlay.setVisibility(View.VISIBLE);
                    binding.layoutOcrBar.setVisibility(View.VISIBLE);
                    Toast.makeText(PdfViewerActivity.this, R.string.ocr_lens_hint, Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(Exception e) {
                binding.pbLoading.setVisibility(View.GONE);
                if (isFinishing() || isDestroyed()) return;
                Log.e(TAG, "OCR recognition error", e);
                Toast.makeText(PdfViewerActivity.this, R.string.ocr_error, Toast.LENGTH_SHORT).show();
                dismissOcrOverlay();
            }
        });
    }

    private void copyTextToClipboard(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("Extracted Text", text));
        }
    }

    // ── Kindle-Style Reading Mode (Single-Page & Realistic Page Curl) ────

    public void enableKindleReadingMode() {
        if (pdfRenderer == null) {
            Toast.makeText(this, "Reading mode is only available for PDF documents", Toast.LENGTH_SHORT).show();
            return;
        }

        if (isOcrActive()) {
            dismissOcrOverlay();
        }
        if (isAnnotationMode) {
            exitAnnotationMode();
        }
        if (binding.lensOverlay.getVisibility() == View.VISIBLE) {
            dismissLensOverlay();
        }

        isInEreaderMode = true;

        // Hide standard toolbars, action buttons, bottom bars, and ads
        binding.topBar.setVisibility(View.GONE);
        binding.topBarDivider.setVisibility(View.GONE);
        if (binding.adView != null) {
            binding.adView.setVisibility(View.GONE);
        }
        binding.rvPdfPages.setVisibility(View.GONE);
        binding.photoView.setVisibility(View.GONE);

        // Find current visible position to resume reading seamlessly
        int currentPos = 0;
        if (layoutManager != null) {
            currentPos = Math.max(0, layoutManager.findFirstVisibleItemPosition());
        }

        // Initialize PageCurlView
        binding.pageCurlView.setVisibility(View.VISIBLE);
        binding.pageCurlView.initialize(pdfRenderer, currentPos);

        // Bind callbacks
        binding.pageCurlView.setOnPageTurnListener((newIndex, total) -> {
            updateEreaderBadge(newIndex + 1, total);
            showAndFadeEreaderBadge();
            if (binding.tvEreaderPageIndicator != null) {
                binding.tvEreaderPageIndicator.setText(getString(R.string.pdf_page_indicator, newIndex + 1, total));
            }
        });

        binding.pageCurlView.setOnCenterTapListener(this::toggleEreaderControls);

        // Setup overlays & controls
        setupEreaderControls();

        // Initial badge and indicator
        if (binding.tvEreaderTitle != null) {
            binding.tvEreaderTitle.setText(documentTitle != null ? documentTitle : "Document");
        }
        if (binding.tvEreaderPageIndicator != null) {
            binding.tvEreaderPageIndicator.setText(getString(R.string.pdf_page_indicator, currentPos + 1, pageCount));
        }
        updateEreaderBadge(currentPos + 1, pageCount);
        showAndFadeEreaderBadge();
    }

    public void exitKindleReadingMode() {
        if (!isInEreaderMode) return;
        isInEreaderMode = false;

        badgeHandler.removeCallbacks(hideBadgeRunnable);

        int lastPage = 0;
        if (binding.pageCurlView != null) {
            lastPage = binding.pageCurlView.getCurrentPageIndex();
            binding.pageCurlView.cleanup();
            binding.pageCurlView.setVisibility(View.GONE);
        }

        binding.layoutEreaderControls.setVisibility(View.GONE);
        binding.tvEreaderPageBadge.setVisibility(View.GONE);

        // Restore standard view
        binding.rvPdfPages.setVisibility(View.VISIBLE);
        binding.topBar.setVisibility(View.VISIBLE);
        binding.topBarDivider.setVisibility(View.VISIBLE);
        if (binding.adView != null) {
            binding.adView.setVisibility(View.VISIBLE);
        }

        // Sync standard RecyclerView scroll position to last read page
        if (layoutManager != null && lastPage >= 0 && lastPage < pageCount) {
            layoutManager.scrollToPositionWithOffset(lastPage, 0);
            updatePageIndicator(lastPage + 1);
        }
    }

    private void toggleEreaderControls() {
        if (binding.layoutEreaderControls == null) return;
        boolean isVisible = binding.layoutEreaderControls.getVisibility() == View.VISIBLE;
        if (isVisible) {
            binding.layoutEreaderControls.setVisibility(View.GONE);
        } else {
            binding.layoutEreaderControls.setVisibility(View.VISIBLE);
            if (binding.pageCurlView != null) {
                updateToneCardSelection(binding.pageCurlView.getPaperWarmth());
                binding.switchEInk.setChecked(binding.pageCurlView.isEInkMode());
                binding.switchPaperTexture.setChecked(binding.pageCurlView.isPaperTextureEnabled());
            }
        }
    }

    private void setupEreaderControls() {
        binding.btnExitEreader.setOnClickListener(v -> exitKindleReadingMode());

        binding.cardToneWhite.setOnClickListener(v -> setEreaderWarmth(PageCurlView.PaperWarmth.WHITE));
        binding.cardToneWarm.setOnClickListener(v -> setEreaderWarmth(PageCurlView.PaperWarmth.WARM));
        binding.cardToneSepia.setOnClickListener(v -> setEreaderWarmth(PageCurlView.PaperWarmth.SEPIA));
        binding.cardToneDark.setOnClickListener(v -> setEreaderWarmth(PageCurlView.PaperWarmth.DARK));
        binding.cardToneNight.setOnClickListener(v -> setEreaderWarmth(PageCurlView.PaperWarmth.NIGHT));

        binding.switchEInk.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (binding.pageCurlView != null) {
                binding.pageCurlView.setEInkMode(isChecked);
            }
        });

        binding.switchPaperTexture.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (binding.pageCurlView != null) {
                binding.pageCurlView.setPaperTextureEnabled(isChecked);
            }
        });

        if (binding.pageCurlView != null) {
            updateToneCardSelection(binding.pageCurlView.getPaperWarmth());
            binding.switchEInk.setChecked(binding.pageCurlView.isEInkMode());
            binding.switchPaperTexture.setChecked(binding.pageCurlView.isPaperTextureEnabled());
        }
    }

    private void setEreaderWarmth(PageCurlView.PaperWarmth warmth) {
        if (binding.pageCurlView != null) {
            binding.pageCurlView.setPaperWarmth(warmth);
        }
        updateToneCardSelection(warmth);
    }

    private void updateToneCardSelection(PageCurlView.PaperWarmth warmth) {
        int activeStroke = ContextCompat.getColor(this, R.color.accent_mint);
        int inactiveStroke = ContextCompat.getColor(this, R.color.divider);
        int activeWidth = (int) (2 * getResources().getDisplayMetrics().density);
        int inactiveWidth = (int) (1 * getResources().getDisplayMetrics().density);

        binding.cardToneWhite.setStrokeColor(warmth == PageCurlView.PaperWarmth.WHITE ? activeStroke : inactiveStroke);
        binding.cardToneWhite.setStrokeWidth(warmth == PageCurlView.PaperWarmth.WHITE ? activeWidth : inactiveWidth);

        binding.cardToneWarm.setStrokeColor(warmth == PageCurlView.PaperWarmth.WARM ? activeStroke : inactiveStroke);
        binding.cardToneWarm.setStrokeWidth(warmth == PageCurlView.PaperWarmth.WARM ? activeWidth : inactiveWidth);

        binding.cardToneSepia.setStrokeColor(warmth == PageCurlView.PaperWarmth.SEPIA ? activeStroke : inactiveStroke);
        binding.cardToneSepia.setStrokeWidth(warmth == PageCurlView.PaperWarmth.SEPIA ? activeWidth : inactiveWidth);

        binding.cardToneDark.setStrokeColor(warmth == PageCurlView.PaperWarmth.DARK ? activeStroke : inactiveStroke);
        binding.cardToneDark.setStrokeWidth(warmth == PageCurlView.PaperWarmth.DARK ? activeWidth : inactiveWidth);

        binding.cardToneNight.setStrokeColor(warmth == PageCurlView.PaperWarmth.NIGHT ? activeStroke : inactiveStroke);
        binding.cardToneNight.setStrokeWidth(warmth == PageCurlView.PaperWarmth.NIGHT ? activeWidth : inactiveWidth);
    }

    private void updateEreaderBadge(int current, int total) {
        if (binding.tvEreaderPageBadge != null) {
            binding.tvEreaderPageBadge.setText(String.format(Locale.US, "%d / %d", current, total));
        }
    }

    private void showAndFadeEreaderBadge() {
        if (binding == null || binding.tvEreaderPageBadge == null) return;
        badgeHandler.removeCallbacks(hideBadgeRunnable);
        binding.tvEreaderPageBadge.animate().cancel();
        binding.tvEreaderPageBadge.setAlpha(1f);
        binding.tvEreaderPageBadge.setVisibility(View.VISIBLE);
        badgeHandler.postDelayed(hideBadgeRunnable, 2000);
    }

    @Override
    public void onThemeChanged(@NonNull PdfPageAdapter.ReadingTheme theme) {
        if (pageAdapter != null) {
            pageAdapter.setReadingTheme(theme);
        }
        applyContainerTheme(theme);
    }

    @Override
    public void onMarginChanged(@NonNull PdfPageAdapter.ReadingMargin margin) {
        if (pageAdapter != null) {
            pageAdapter.setReadingMargin(margin);
        }
    }

    @Override
    public void onPageSnapChanged(boolean snapEnabled) {
        applyPageSnap(snapEnabled);
    }

    @Override
    public void onKeepAwakeChanged(boolean keepAwakeEnabled) {
        applyKeepAwake(keepAwakeEnabled);
    }

    private void applyContainerTheme(PdfPageAdapter.ReadingTheme theme) {
        int bgColor;
        switch (theme) {
            case SEPIA:
                bgColor = Color.parseColor("#FFFBF0D9");
                break;
            case DARK:
                bgColor = Color.parseColor("#FF1E1E1E");
                break;
            case NIGHT:
                bgColor = Color.BLACK;
                break;
            case LIGHT:
            default:
                bgColor = ContextCompat.getColor(this, R.color.background_primary);
                break;
        }
        binding.flPdfContainer.setBackgroundColor(bgColor);
        binding.rvPdfPages.setBackgroundColor(bgColor);
    }

    private void applyPageSnap(boolean snapEnabled) {
        if (snapEnabled) {
            if (pagerSnapHelper == null) {
                pagerSnapHelper = new PagerSnapHelper();
            }
            try {
                pagerSnapHelper.attachToRecyclerView(binding.rvPdfPages);
            } catch (IllegalStateException ignored) {
                // Already attached
            }
        } else {
            if (pagerSnapHelper != null) {
                try {
                    pagerSnapHelper.attachToRecyclerView(null);
                } catch (Exception ignored) {}
            }
        }
    }

    private void applyKeepAwake(boolean keepAwakeEnabled) {
        if (keepAwakeEnabled) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void loadInitialReadingPreferences() {
        SharedPreferences prefs = getSharedPreferences("anscanner_reading_settings", MODE_PRIVATE);
        String themeStr = prefs.getString(ReadingModeBottomSheet.PREF_THEME, PdfPageAdapter.ReadingTheme.LIGHT.name());
        PdfPageAdapter.ReadingTheme theme;
        try {
            theme = PdfPageAdapter.ReadingTheme.valueOf(themeStr);
        } catch (Exception e) {
            theme = PdfPageAdapter.ReadingTheme.LIGHT;
        }
        applyContainerTheme(theme);
        if (pageAdapter != null) {
            pageAdapter.setReadingTheme(theme);
        }

        String marginStr = prefs.getString(ReadingModeBottomSheet.PREF_MARGIN, PdfPageAdapter.ReadingMargin.NORMAL.name());
        PdfPageAdapter.ReadingMargin margin;
        try {
            margin = PdfPageAdapter.ReadingMargin.valueOf(marginStr);
        } catch (Exception e) {
            margin = PdfPageAdapter.ReadingMargin.NORMAL;
        }
        if (pageAdapter != null) {
            pageAdapter.setReadingMargin(margin);
        }

        boolean snap = prefs.getBoolean(ReadingModeBottomSheet.PREF_PAGE_SNAP, false);
        applyPageSnap(snap);

        boolean keepAwake = prefs.getBoolean(ReadingModeBottomSheet.PREF_KEEP_AWAKE, false);
        applyKeepAwake(keepAwake);
    }

    private void toggleImmersiveReadingMode() {
        if (isAnnotationMode || isOcrActive()) return;
        boolean isVisible = binding.topBar.getVisibility() == View.VISIBLE;
        binding.topBar.setVisibility(isVisible ? View.GONE : View.VISIBLE);
        binding.topBarDivider.setVisibility(isVisible ? View.GONE : View.VISIBLE);
        if (binding.adView != null) {
            binding.adView.setVisibility(isVisible ? View.GONE : View.VISIBLE);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_pdf_viewer, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_reading_mode) {
            enableKindleReadingMode();
            return true;
        } else if (id == R.id.action_ocr) {
            onExtractTextClicked();
            return true;
        } else if (id == R.id.action_annotate) {
            toggleAnnotationMode();
            return true;
        } else if (id == R.id.action_edit) {
            editCurrentPage();
            return true;
        } else if (id == R.id.action_print) {
            printDocument();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }


    // ── Flattened Annotation Engine (Sign/Draw) ──────────────────────────

    private void toggleAnnotationMode() {
        if (pdfRenderer != null) {
            int currentVisiblePage = 0;
            if (layoutManager != null) {
                currentVisiblePage = layoutManager.findFirstVisibleItemPosition();
                if (currentVisiblePage < 0) currentVisiblePage = 0;
            }
            final int initialIndex = currentVisiblePage;
            binding.pbLoading.setVisibility(View.VISIBLE);

            new Thread(() -> {
                ArrayList<String> allPages = null;
                if (resolvedUri != null) {
                    allPages = CropPreviewActivity.extractAllPagesFromPdf(PdfViewerActivity.this, resolvedUri);
                } else if (resolvedFile != null) {
                    allPages = CropPreviewActivity.extractAllPagesFromPdf(PdfViewerActivity.this, Uri.fromFile(resolvedFile));
                }

                final ArrayList<String> finalPages = allPages;
                runOnUiThread(() -> {
                    binding.pbLoading.setVisibility(View.GONE);
                    if (finalPages != null && !finalPages.isEmpty()) {
                        Intent editorIntent = new Intent(PdfViewerActivity.this, UnifiedEditorActivity.class);
                        editorIntent.putStringArrayListExtra(UnifiedEditorActivity.EXTRA_PAGE_PATHS, finalPages);
                        editorIntent.putStringArrayListExtra(UnifiedEditorActivity.EXTRA_ORIGINAL_PAGE_PATHS, new ArrayList<>(finalPages));
                        editorIntent.putExtra(UnifiedEditorActivity.EXTRA_PAGE_INDEX, initialIndex);
                        editorIntent.putExtra(UnifiedEditorActivity.EXTRA_MODE, UnifiedEditorActivity.MODE_PDF_EDIT);
                        editorIntent.putExtra(UnifiedEditorActivity.EXTRA_START_ANNOTATE, true);
                        if (resolvedUri != null) {
                            editorIntent.putExtra(UnifiedEditorActivity.EXTRA_PDF_URI, resolvedUri);
                        }
                        startActivity(editorIntent);
                    } else {
                        Toast.makeText(PdfViewerActivity.this, R.string.error_generic, Toast.LENGTH_SHORT).show();
                    }
                });
            }).start();
        } else if (currentImageBitmap != null) {
            String tempPath = CacheManager.saveTempBitmap(
                    this, currentImageBitmap, UUID.randomUUID().toString());
            if (tempPath != null) {
                Intent editorIntent = new Intent(this, UnifiedEditorActivity.class);
                editorIntent.putExtra(UnifiedEditorActivity.EXTRA_IMAGE_PATH, tempPath);
                editorIntent.putExtra(UnifiedEditorActivity.EXTRA_MODE, UnifiedEditorActivity.MODE_PDF_EDIT);
                editorIntent.putExtra(UnifiedEditorActivity.EXTRA_START_ANNOTATE, true);
                startActivity(editorIntent);
            } else {
                Toast.makeText(this, R.string.error_generic, Toast.LENGTH_SHORT).show();
            }
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

                        // Render original page to temporary bitmap with opaque white background
                        Bitmap pageBitmap = com.anscanner.app.util.PdfRendererHelper.renderPageWithWhiteBackground(
                                origPage, pWidth, pHeight, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
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

    private void setupAds() {
        if (binding != null && binding.adView != null) {
            AdRequest adRequest = new AdRequest.Builder().build();
            binding.adView.loadAd(adRequest);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (binding != null && binding.adView != null) {
            binding.adView.resume();
        }
    }

    @Override
    protected void onPause() {
        if (binding != null && binding.adView != null) {
            binding.adView.pause();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (badgeHandler != null) {
            badgeHandler.removeCallbacks(hideBadgeRunnable);
        }

        if (binding != null && binding.pageCurlView != null) {
            binding.pageCurlView.cleanup();
        }

        if (binding != null && binding.adView != null) {
            binding.adView.destroy();
        }

        super.onDestroy();
        cleanupPdfResources();

        if (decryptedTempFile != null && decryptedTempFile.exists()) {
            decryptedTempFile.delete();
            decryptedTempFile = null;
        }

        if (currentImageBitmap != null && !currentImageBitmap.isRecycled()) {
            currentImageBitmap.recycle();
            currentImageBitmap = null;
        }

        if (currentAnnotatedPageBitmap != null && !currentAnnotatedPageBitmap.isRecycled()) {
            currentAnnotatedPageBitmap.recycle();
            currentAnnotatedPageBitmap = null;
        }

        if (currentOcrBitmap != null && !currentOcrBitmap.isRecycled() && currentOcrBitmap != currentImageBitmap) {
            currentOcrBitmap.recycle();
            currentOcrBitmap = null;
        }

        binding = null;

    }
}
