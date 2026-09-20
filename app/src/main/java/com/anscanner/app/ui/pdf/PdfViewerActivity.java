package com.anscanner.app.ui.pdf;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.RectF;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.print.PrintHelper;

import com.anscanner.app.R;
import com.anscanner.app.databinding.ActivityPdfViewerBinding;
import com.anscanner.app.service.CacheManager;
import com.anscanner.app.service.OcrHelper;
import com.anscanner.app.ui.crop.CropPreviewActivity;
import com.anscanner.app.ui.custom.OcrTextOverlayView;
import com.anscanner.app.ui.editor.UnifiedEditorActivity;
import com.anscanner.app.util.PdfRendererHelper;
import com.github.barteksc.pdfviewer.PDFView;
import com.github.barteksc.pdfviewer.scroll.DefaultScrollHandle;
import com.github.barteksc.pdfviewer.util.FitPolicy;
import com.google.android.gms.ads.AdRequest;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.mlkit.vision.text.Text;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;

/**
 * Production-standard Multi-format Document Viewer Activity supporting PDF and JPG/PNG documents.
 *
 * <p>Supports:
 * <ul>
 *   <li>System-wide "Open With" handling via {@link Intent#ACTION_VIEW} for PDFs and Images.</li>
 *   <li>Production standard PDFium-backed continuous document viewport with global zoom and pan.</li>
 *   <li>Seamless toggle support between Continuous Vertical and Continuous Horizontal flow.</li>
 *   <li>Interactive in-place OCR text overlay with direct word selection & copy bar.</li>
 *   <li>Native {@link com.github.chrisbanes.photoview.PhotoView} pinch-to-zoom for JPG scans.</li>
 *   <li>Seamless launch of {@link UnifiedEditorActivity} for multi-page editing and annotations.</li>
 *   <li>Native system printing via {@link PrintManager} or {@link PrintHelper}.</li>
 * </ul>
 * </p>
 */
public class PdfViewerActivity extends AppCompatActivity {

    private static final String TAG = "PdfViewerActivity";

    public static final String EXTRA_PDF_PATH = "extra_pdf_path";
    public static final String EXTRA_PDF_URI = "extra_pdf_uri";
    public static final String EXTRA_DOCUMENT_TITLE = "extra_document_title";

    private static final String PREFS_NAME = "anscanner_pdf_viewer_prefs";
    private static final String PREF_SWIPE_HORIZONTAL = "pref_swipe_horizontal";

    private ActivityPdfViewerBinding binding;

    private Uri resolvedUri;
    private File resolvedFile;
    private File decryptedTempFile = null;
    private String documentTitle = "Document";
    private int pageCount = 0;

    // Viewing mode state: false = continuous vertical, true = continuous horizontal
    private boolean isSwipeHorizontal = false;

    // Image viewer and annotation states
    private Bitmap currentImageBitmap;
    private boolean isAnnotationMode = false;

    // In-place interactive OCR state
    private int currentOcrPageIndex = 0;
    private Bitmap currentOcrBitmap;

    // Scrubber slider state
    private boolean isScrubbing = false;

    // Landscape fullscreen optimization state
    private boolean isLandscapeFullscreen = false;

    private boolean isLandscape() {
        return getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
    }

    private void toggleLandscapeFullscreen() {
        setLandscapeFullscreen(!isLandscapeFullscreen);
    }

    private void setLandscapeFullscreen(boolean fullscreen) {
        isLandscapeFullscreen = fullscreen;
        if (binding == null) return;

        if (fullscreen && isLandscape()) {
            binding.topBar.setVisibility(View.GONE);
            binding.topBarDivider.setVisibility(View.GONE);
            if (binding.layoutPageScrubber != null) {
                binding.layoutPageScrubber.setVisibility(View.GONE);
            }
            androidx.core.view.WindowInsetsControllerCompat controller =
                    androidx.core.view.WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
            if (controller != null) {
                controller.setSystemBarsBehavior(androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars());
            }
        } else {
            binding.topBar.setVisibility(View.VISIBLE);
            binding.topBarDivider.setVisibility(View.VISIBLE);
            if (pageCount > 1 && !isAnnotationMode && !isOcrActive() && binding.layoutPageScrubber != null) {
                binding.layoutPageScrubber.setVisibility(View.VISIBLE);
            }
            androidx.core.view.WindowInsetsControllerCompat controller =
                    androidx.core.view.WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
            if (controller != null) {
                controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars());
            }
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        boolean isLandscape = newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        if (isLandscape) {
            if (binding.pdfView != null && binding.pdfView.getVisibility() == View.VISIBLE && pageCount > 0) {
                binding.pdfView.post(() -> {
                    if (binding.pdfView != null) {
                        int currentPage = binding.pdfView.getCurrentPage();
                        binding.pdfView.fitToWidth(currentPage);
                    }
                });
            } else if (binding.photoView != null && binding.photoView.getVisibility() == View.VISIBLE) {
                binding.photoView.setScale(1.0f, false);
            }
        } else {
            setLandscapeFullscreen(false);
            if (binding.pdfView != null && binding.pdfView.getVisibility() == View.VISIBLE && pageCount > 0) {
                binding.pdfView.post(() -> {
                    if (binding.pdfView != null) {
                        int currentPage = binding.pdfView.getCurrentPage();
                        binding.pdfView.fitToWidth(currentPage);
                    }
                });
            }
        }
    }

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
        binding.btnToggleOrientation.setOnClickListener(v -> togglePageOrientation());
        binding.btnExtractText.setOnClickListener(v -> onExtractTextClicked());
        binding.btnAnnotate.setOnClickListener(v -> toggleAnnotationMode());
        binding.btnEdit.setOnClickListener(v -> editCurrentPage());
        binding.tvPageIndicator.setOnClickListener(v -> showJumpToPageDialog());

        updateOrientationButtonState();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (isOcrActive()) {
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

    private void updateOrientationButtonState() {
        if (binding == null || binding.btnToggleOrientation == null) return;
        if (isSwipeHorizontal) {
            binding.btnToggleOrientation.setImageResource(R.drawable.ic_view_agenda);
            binding.btnToggleOrientation.setContentDescription(getString(R.string.pdf_view_mode_vertical));
        } else {
            binding.btnToggleOrientation.setImageResource(R.drawable.ic_view_carousel);
            binding.btnToggleOrientation.setContentDescription(getString(R.string.pdf_view_mode_horizontal));
        }
    }

    private void togglePageOrientation() {
        if (binding == null || binding.pdfView == null || pageCount <= 0) return;
        isSwipeHorizontal = !isSwipeHorizontal;
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_SWIPE_HORIZONTAL, isSwipeHorizontal)
                .apply();

        updateOrientationButtonState();
        int currentPage = binding.pdfView.getCurrentPage();
        loadPdfWithEngine(currentPage);
        String modeName = getString(isSwipeHorizontal ? R.string.pdf_view_mode_horizontal : R.string.pdf_view_mode_vertical);
        Toast.makeText(this, modeName, Toast.LENGTH_SHORT).show();
    }

    private void setupAnnotationControls() {
        binding.btnCloseAnnotation.setOnClickListener(v -> exitAnnotationMode());
        binding.btnUndoDrawing.setOnClickListener(v -> binding.drawingOverlay.undo());
        binding.btnClearDrawing.setOnClickListener(v -> binding.drawingOverlay.clear());

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

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        isSwipeHorizontal = prefs.getBoolean(PREF_SWIPE_HORIZONTAL, false);
        updateOrientationButtonState();

        // 1. Resolve URI, file path, and title from Intent
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
            binding.btnToggleOrientation.setVisibility(View.GONE);
            loadImageDocument();
            return;
        }

        binding.btnToggleOrientation.setVisibility(View.VISIBLE);

        // 4. Load PDF with PDFView engine
        loadPdfWithEngine(0);
    }

    private void loadPdfWithEngine(int defaultPage) {
        binding.photoView.setVisibility(View.GONE);
        binding.pdfView.setVisibility(View.VISIBLE);
        binding.pbLoading.setVisibility(View.VISIBLE);
        binding.tvError.setVisibility(View.GONE);

        PDFView.Configurator configurator;
        if (resolvedFile != null && resolvedFile.exists()) {
            configurator = binding.pdfView.fromFile(resolvedFile);
        } else if (resolvedUri != null) {
            configurator = binding.pdfView.fromUri(resolvedUri);
        } else {
            showError();
            return;
        }

        configurator.defaultPage(defaultPage)
                .enableSwipe(true)
                .swipeHorizontal(isSwipeHorizontal)
                .enableDoubletap(true)
                .enableAnnotationRendering(true)
                .pageFitPolicy(FitPolicy.WIDTH)
                .spacing(10)
                .scrollHandle(new DefaultScrollHandle(this))
                .onTap(e -> {
                    if (isLandscape() && !isAnnotationMode && !isOcrActive()) {
                        toggleLandscapeFullscreen();
                        return true;
                    }
                    return false;
                })
                .onPageScroll((page, positionOffset) -> {
                    if (isLandscape() && !isLandscapeFullscreen && !isAnnotationMode && !isOcrActive()) {
                        setLandscapeFullscreen(true);
                    }
                })
                .onPageChange((page, pageCountTotal) -> {
                    pageCount = pageCountTotal;
                    updatePageIndicator(page + 1);
                    if (!isScrubbing && binding.sliderPageScrubber != null && pageCount > 1) {
                        float val = (float) (page + 1);
                        if (val >= binding.sliderPageScrubber.getValueFrom() && val <= binding.sliderPageScrubber.getValueTo()) {
                            if (binding.sliderPageScrubber.getValue() != val) {
                                binding.sliderPageScrubber.setValue(val);
                            }
                        }
                    }
                })
                .onLoad(nbPages -> {
                    pageCount = nbPages;
                    binding.pbLoading.setVisibility(View.GONE);
                    int current = binding.pdfView.getCurrentPage();
                    updatePageIndicator(current + 1);
                    setupPageScrubber();
                })
                .onError(t -> {
                    binding.pbLoading.setVisibility(View.GONE);
                    if (t != null && t.getMessage() != null && t.getMessage().toLowerCase().contains("password")) {
                        promptPdfPassword();
                    } else {
                        Log.e(TAG, "Error opening PDF", t);
                        showError();
                    }
                })
                .load();
    }

    private void promptPdfPassword() {
        if (isFinishing() || isDestroyed()) return;

        FrameLayout container = new FrameLayout(this);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        container.setPadding(padding, padding / 2, padding, 0);

        TextInputLayout til = new TextInputLayout(this);
        til.setHint(getString(R.string.save_password_hint));
        til.setEndIconMode(TextInputLayout.END_ICON_PASSWORD_TOGGLE);
        til.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        til.setBoxStrokeColor(ContextCompat.getColor(this, R.color.accent_mint));
        til.setDefaultHintTextColor(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.text_secondary)));

        TextInputEditText et = new TextInputEditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
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

                    binding.pdfView.setVisibility(View.GONE);
                    binding.photoView.setScale(1.0f, false);
                    binding.photoView.setImageBitmap(currentImageBitmap);
                    binding.photoView.setVisibility(View.VISIBLE);
                    binding.photoView.setOnClickListener(v -> {
                        if (isLandscape() && !isAnnotationMode && !isOcrActive()) {
                            toggleLandscapeFullscreen();
                        }
                    });
                    binding.tvPageIndicator.setText("1 of 1");
                    if (binding.layoutPageScrubber != null) {
                        binding.layoutPageScrubber.setVisibility(View.GONE);
                    }
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
        if (pageCount > 0 && (resolvedUri != null || resolvedFile != null)) {
            PrintManager printManager = (PrintManager) getSystemService(Context.PRINT_SERVICE);
            if (printManager == null) {
                Toast.makeText(this, R.string.print_error, Toast.LENGTH_SHORT).show();
                return;
            }

            try {
                PdfPrintDocumentAdapter printAdapter;
                if (resolvedUri != null) {
                    printAdapter = new PdfPrintDocumentAdapter(this, resolvedUri, documentTitle, pageCount);
                } else {
                    printAdapter = new PdfPrintDocumentAdapter(this, resolvedFile, documentTitle, pageCount);
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
        if (binding.pdfView.getVisibility() == View.VISIBLE) {
            final int initialIndex = binding.pdfView.getCurrentPage();
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
                        cropIntent.putExtra(UnifiedEditorActivity.EXTRA_ENABLE_CROP_DEFAULT, false);
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
                cropIntent.putExtra(UnifiedEditorActivity.EXTRA_ENABLE_CROP_DEFAULT, false);
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

        if (binding.pdfView.getVisibility() == View.VISIBLE) {
            binding.photoView.setImageBitmap(null);
            binding.photoView.setVisibility(View.GONE);
            if (currentOcrPageIndex >= 0 && currentOcrPageIndex < pageCount) {
                binding.pdfView.jumpTo(currentOcrPageIndex);
                updatePageIndicator(currentOcrPageIndex + 1);
            }
            if (pageCount > 1 && binding.layoutPageScrubber != null) {
                binding.layoutPageScrubber.setVisibility(View.VISIBLE);
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

        if (binding.pdfView.getVisibility() == View.VISIBLE && pageCount > 0) {
            if (pageCount > 1) {
                new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.ocr_dialog_title)
                        .setItems(new CharSequence[]{
                                getString(R.string.ocr_batch_all_pages),
                                getString(R.string.ocr_current_page_lens)
                        }, (dialog, which) -> {
                            if (which == 0) {
                                startBatchOcr();
                            } else {
                                int currentPos = binding.pdfView.getCurrentPage();
                                currentOcrPageIndex = currentPos;
                                extractTextFromPdfPage(currentPos);
                            }
                        })
                        .setNegativeButton(R.string.action_cancel, null)
                        .show();
            } else {
                currentOcrPageIndex = 0;
                extractTextFromPdfPage(0);
            }
        } else if (currentImageBitmap != null) {
            extractTextFromImage(currentImageBitmap);
        }
    }

    private void startBatchOcr() {
        ParcelFileDescriptor batchPfd = null;
        PdfRenderer batchRenderer = null;
        try {
            if (resolvedUri != null) {
                if ("file".equalsIgnoreCase(resolvedUri.getScheme())) {
                    batchPfd = ParcelFileDescriptor.open(new File(resolvedUri.getPath()), ParcelFileDescriptor.MODE_READ_ONLY);
                } else {
                    batchPfd = getContentResolver().openFileDescriptor(resolvedUri, "r");
                }
            } else if (resolvedFile != null) {
                batchPfd = ParcelFileDescriptor.open(resolvedFile, ParcelFileDescriptor.MODE_READ_ONLY);
            }
            if (batchPfd == null) return;
            batchRenderer = new PdfRenderer(batchPfd);
            final ParcelFileDescriptor fPfd = batchPfd;
            final PdfRenderer fRenderer = batchRenderer;

            OcrHelper.extractTextFromPdfBatch(this, batchRenderer, new OcrHelper.BatchOcrCallback() {
                @Override
                public void onProgress(int currentPage, int totalPages) {
                    // Managed by OcrHelper's progress dialog
                }

                @Override
                public void onSuccess(String aggregatedText) {
                    closeBatchResources(fRenderer, fPfd);
                    if (isFinishing() || isDestroyed()) return;
                    OcrHelper.showExtractedTextDialog(PdfViewerActivity.this, aggregatedText, false, pageCount);
                }

                @Override
                public void onCancelled(String partialText) {
                    closeBatchResources(fRenderer, fPfd);
                    if (isFinishing() || isDestroyed()) return;
                    if (partialText != null && !partialText.trim().isEmpty()) {
                        OcrHelper.showExtractedTextDialog(PdfViewerActivity.this, partialText, true, pageCount);
                    } else {
                        Toast.makeText(PdfViewerActivity.this, R.string.ocr_cancelled_toast, Toast.LENGTH_SHORT).show();
                    }
                }

                @Override
                public void onError(Exception e) {
                    closeBatchResources(fRenderer, fPfd);
                    if (isFinishing() || isDestroyed()) return;
                    Log.e(TAG, "Batch OCR failed", e);
                    Toast.makeText(PdfViewerActivity.this, R.string.ocr_error, Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            closeBatchResources(batchRenderer, batchPfd);
            Log.e(TAG, "Batch OCR setup failed", e);
            Toast.makeText(this, R.string.ocr_error, Toast.LENGTH_SHORT).show();
        }
    }

    private void closeBatchResources(PdfRenderer renderer, ParcelFileDescriptor pfd) {
        if (renderer != null) {
            try { renderer.close(); } catch (Exception ignored) {}
        }
        if (pfd != null) {
            try { pfd.close(); } catch (Exception ignored) {}
        }
    }

    private void setupPageScrubber() {
        if (binding.sliderPageScrubber == null || binding.layoutPageScrubber == null) return;
        if (pageCount > 1) {
            binding.sliderPageScrubber.setValueFrom(1.0f);
            binding.sliderPageScrubber.setValueTo((float) pageCount);
            binding.sliderPageScrubber.setStepSize(1.0f);
            int current = binding.pdfView.getCurrentPage();
            binding.sliderPageScrubber.setValue((float) Math.max(1, Math.min(pageCount, current + 1)));
            binding.sliderPageScrubber.setLabelFormatter(value -> "Page " + (int) value);

            binding.sliderPageScrubber.clearOnChangeListeners();
            binding.sliderPageScrubber.clearOnSliderTouchListeners();

            binding.sliderPageScrubber.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
                @Override
                public void onStartTrackingTouch(@NonNull Slider slider) {
                    isScrubbing = true;
                }

                @Override
                public void onStopTrackingTouch(@NonNull Slider slider) {
                    isScrubbing = false;
                }
            });

            binding.sliderPageScrubber.addOnChangeListener((slider, value, fromUser) -> {
                if (fromUser && binding.pdfView != null) {
                    int targetPage = (int) value - 1;
                    if (targetPage >= 0 && targetPage < pageCount) {
                        binding.pdfView.jumpTo(targetPage);
                        updatePageIndicator(targetPage + 1);
                    }
                }
            });

            binding.layoutPageScrubber.setVisibility(View.VISIBLE);
        } else {
            binding.layoutPageScrubber.setVisibility(View.GONE);
        }
    }

    private void showJumpToPageDialog() {
        if (pageCount <= 1) return;

        final int currentPage = binding.pdfView.getCurrentPage() + 1;

        FrameLayout container = new FrameLayout(this);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        container.setPadding(padding, padding / 2, padding, 0);

        TextInputLayout til = new TextInputLayout(this);
        til.setHint(getString(R.string.jump_to_page_hint, pageCount));
        til.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        til.setBoxStrokeColor(ContextCompat.getColor(this, R.color.accent_mint));
        til.setDefaultHintTextColor(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.text_secondary)));

        TextInputEditText et = new TextInputEditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        et.setText(String.valueOf(currentPage));
        et.selectAll();
        til.addView(et);
        container.addView(til);

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.jump_to_page_title)
                .setView(container)
                .setPositiveButton(R.string.jump_to_page_go, null)
                .setNegativeButton(R.string.action_cancel, (d, which) -> d.dismiss())
                .create();

        dialog.setOnShowListener(d -> {
            et.requestFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT);
            }

            Button positiveBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            positiveBtn.setOnClickListener(v -> {
                String text = et.getText() != null ? et.getText().toString().trim() : "";
                if (text.isEmpty()) {
                    til.setError(getString(R.string.jump_to_page_error, pageCount));
                    return;
                }
                try {
                    int targetPage = Integer.parseInt(text);
                    if (targetPage < 1 || targetPage > pageCount) {
                        til.setError(getString(R.string.jump_to_page_error, pageCount));
                        return;
                    }
                    til.setError(null);
                    if (imm != null) {
                        imm.hideSoftInputFromWindow(et.getWindowToken(), 0);
                    }
                    dialog.dismiss();

                    int targetIndex = targetPage - 1;
                    if (binding.pdfView != null) {
                        binding.pdfView.jumpTo(targetIndex);
                        updatePageIndicator(targetPage);
                    }
                    if (binding.sliderPageScrubber != null && pageCount > 1) {
                        binding.sliderPageScrubber.setValue((float) targetPage);
                    }
                } catch (NumberFormatException e) {
                    til.setError(getString(R.string.jump_to_page_error, pageCount));
                }
            });
        });

        dialog.show();
    }

    private void extractTextFromPdfPage(int pageIndex) {
        binding.pbLoading.setVisibility(View.VISIBLE);

        new Thread(() -> {
            Bitmap pageBitmap = null;
            ParcelFileDescriptor ocrPfd = null;
            PdfRenderer ocrRenderer = null;
            try {
                if (resolvedUri != null) {
                    if ("file".equalsIgnoreCase(resolvedUri.getScheme())) {
                        ocrPfd = ParcelFileDescriptor.open(new File(resolvedUri.getPath()), ParcelFileDescriptor.MODE_READ_ONLY);
                    } else {
                        ocrPfd = getContentResolver().openFileDescriptor(resolvedUri, "r");
                    }
                } else if (resolvedFile != null) {
                    ocrPfd = ParcelFileDescriptor.open(resolvedFile, ParcelFileDescriptor.MODE_READ_ONLY);
                }

                if (ocrPfd != null) {
                    ocrRenderer = new PdfRenderer(ocrPfd);
                    if (pageIndex >= 0 && pageIndex < ocrRenderer.getPageCount()) {
                        PdfRenderer.Page page = ocrRenderer.openPage(pageIndex);
                        try {
                            int w = Math.min(1600, Math.max(page.getWidth() * 2, 720));
                            int h = Math.round((float) w * page.getHeight() / Math.max(1, page.getWidth()));
                            pageBitmap = PdfRendererHelper.renderPageWithWhiteBackground(page, w, h, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        } finally {
                            page.close();
                        }
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
            } finally {
                if (ocrRenderer != null) {
                    try { ocrRenderer.close(); } catch (Exception ignored) {}
                }
                if (ocrPfd != null) {
                    try { ocrPfd.close(); } catch (Exception ignored) {}
                }
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
                if (binding.pdfView.getVisibility() == View.VISIBLE) {
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
                    if (binding.layoutPageScrubber != null) {
                        binding.layoutPageScrubber.setVisibility(View.GONE);
                    }
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

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_pdf_viewer, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_toggle_orientation) {
            togglePageOrientation();
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
        if (binding.pdfView.getVisibility() == View.VISIBLE) {
            final int initialIndex = binding.pdfView.getCurrentPage();
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
                        editorIntent.putExtra(UnifiedEditorActivity.EXTRA_ENABLE_CROP_DEFAULT, false);
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
                editorIntent.putExtra(UnifiedEditorActivity.EXTRA_ENABLE_CROP_DEFAULT, false);
                startActivity(editorIntent);
            } else {
                Toast.makeText(this, R.string.error_generic, Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(this, R.string.error_generic, Toast.LENGTH_SHORT).show();
        }
    }

    private void exitAnnotationMode() {
        isAnnotationMode = false;
        binding.drawingOverlay.clear();
        binding.drawingOverlay.setVisibility(View.GONE);
        binding.drawingOverlay.setDrawingEnabled(false);
        binding.layoutAnnotationBar.setVisibility(View.GONE);

        if (binding.pdfView.getVisibility() == View.VISIBLE) {
            binding.photoView.setVisibility(View.GONE);
            if (pageCount > 1 && binding.layoutPageScrubber != null) {
                binding.layoutPageScrubber.setVisibility(View.VISIBLE);
            }
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
        if (binding != null && binding.adView != null) {
            binding.adView.destroy();
        }

        super.onDestroy();

        if (decryptedTempFile != null && decryptedTempFile.exists()) {
            decryptedTempFile.delete();
            decryptedTempFile = null;
        }

        if (currentImageBitmap != null && !currentImageBitmap.isRecycled()) {
            currentImageBitmap.recycle();
            currentImageBitmap = null;
        }

        if (currentOcrBitmap != null && !currentOcrBitmap.isRecycled() && currentOcrBitmap != currentImageBitmap) {
            currentOcrBitmap.recycle();
            currentOcrBitmap = null;
        }

        binding = null;
    }
}
