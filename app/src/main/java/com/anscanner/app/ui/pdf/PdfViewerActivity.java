package com.anscanner.app.ui.pdf;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.RectF;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewPropertyAnimator;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.print.PrintHelper;

import com.anscanner.app.R;
import com.anscanner.app.databinding.ActivityPdfViewerBinding;
import com.anscanner.app.model.ocr.OcrPage;
import com.anscanner.app.service.CacheManager;
import com.anscanner.app.service.OcrHelper;
import com.anscanner.app.service.StorageHelper;
import com.anscanner.app.ui.crop.CropPreviewActivity;
import com.anscanner.app.ui.custom.OcrTextOverlayView;
import com.anscanner.app.ui.editor.UnifiedEditorActivity;
import com.anscanner.app.util.InsetsHelper;
import com.anscanner.app.util.PdfRendererHelper;
import com.github.barteksc.pdfviewer.PDFView;
import com.github.barteksc.pdfviewer.scroll.DefaultScrollHandle;
import com.github.barteksc.pdfviewer.util.FitPolicy;
import com.github.chrisbanes.photoview.PhotoView;
import com.google.android.gms.ads.AdRequest;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.mlkit.vision.text.Text;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Production-standard Multi-format Document Viewer Activity supporting PDF and JPG/PNG documents.
 *
 * <p>Supports:
 * <ul>
 *   <li>System-wide "Open With" handling via {@link Intent#ACTION_VIEW} for PDFs and Images.</li>
 *   <li>Production standard PDFium-backed continuous document viewport with global zoom and pan.</li>
 *   <li>Seamless toggle support between Continuous Vertical and Continuous Horizontal flow.</li>
 *   <li>Interactive in-place OCR text overlay with direct word selection & copy bar.</li>
 *   <li>Native {@link PhotoView} pinch-to-zoom for JPG scans.</li>
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
    public static final String EXTRA_DOCUMENT_ID = "extra_document_id";

    private static final String PREFS_NAME = "anscanner_pdf_viewer_prefs";
    private static final String PREF_SWIPE_HORIZONTAL = "pref_swipe_horizontal";

    private ActivityPdfViewerBinding binding;

    private Uri resolvedUri;
    private File resolvedFile;
    private File decryptedTempFile = null;
    private String documentTitle = "Document";
    private long documentId = -1L;
    private int pageCount = 0;
    private final ExecutorService viewerExecutor = Executors.newSingleThreadExecutor();

    private final ActivityResultLauncher<Intent> organizePagesLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    boolean reorganized = result.getData().getBooleanExtra("KEY_PAGES_REORGANIZED", false);
                    if (reorganized) {
                        loadPdfDocument();
                    }
                }
            });

    // Viewing mode state: false = continuous vertical, true = continuous horizontal
    private boolean isSwipeHorizontal = false;

    // Image viewer and annotation states
    private Bitmap currentImageBitmap;
    private boolean isAnnotationMode = false;

    // In-place interactive OCR state
    private int currentOcrPageIndex = 0;
    private Bitmap currentOcrBitmap;

    // Floating page indicator state & auto-hide controller
    private static final long INDICATOR_HIDE_DELAY_MS = 1500L;
    private final Handler indicatorHandler = new Handler(Looper.getMainLooper());
    private final Runnable hideIndicatorRunnable = this::fadeOutPageIndicator;
    private ViewPropertyAnimator indicatorAnimator = null;

    // Landscape fullscreen optimization state
    private boolean isLandscapeFullscreen = false;

    private boolean isLandscape() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
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
            WindowInsetsControllerCompat controller =
                    WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
            if (controller != null) {
                controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(WindowInsetsCompat.Type.systemBars());
            }
        } else {
            binding.topBar.setVisibility(View.VISIBLE);
            binding.topBarDivider.setVisibility(View.VISIBLE);
            WindowInsetsControllerCompat controller =
                    WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
            if (controller != null) {
                controller.show(WindowInsetsCompat.Type.systemBars());
            }
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        boolean isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE;
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
        InsetsHelper.applyEdgeToEdge(this);
        super.onCreate(savedInstanceState);
        binding = ActivityPdfViewerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        InsetsHelper.handleSystemBars(binding.getRoot(), binding.topBar, binding.bottomAdContainer);

        setupToolbar();
        setupOcrControls();
        setupAnnotationControls();
        setupAds();
        loadPdfDocument();

        restoreOcrState(savedInstanceState);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (binding != null && isOcrActive()) {
            outState.putBoolean("KEY_OCR_ACTIVE", true);
            outState.putInt("KEY_OCR_PAGE_INDEX", currentOcrPageIndex);
            OcrPage ocrPage = binding.ocrOverlay.getOcrPage();
            if (ocrPage != null) {
                outState.putParcelable("KEY_OCR_PAGE", ocrPage);
                outState.putInt("KEY_OCR_SEL_START", binding.ocrOverlay.getSelectionStart());
                outState.putInt("KEY_OCR_SEL_END", binding.ocrOverlay.getSelectionEnd());
            }
        }
    }

    private void restoreOcrState(@Nullable Bundle savedInstanceState) {
        if (savedInstanceState == null || !savedInstanceState.getBoolean("KEY_OCR_ACTIVE", false)) {
            return;
        }
        currentOcrPageIndex = savedInstanceState.getInt("KEY_OCR_PAGE_INDEX", 0);
        OcrPage savedPage = savedInstanceState.getParcelable("KEY_OCR_PAGE");
        int selStart = savedInstanceState.getInt("KEY_OCR_SEL_START", -1);
        int selEnd = savedInstanceState.getInt("KEY_OCR_SEL_END", -1);

        if (savedPage != null) {
            binding.ocrOverlay.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                RectF displayRect = binding.photoView.getDisplayRect();
                binding.ocrOverlay.setTargetRect(displayRect);
                binding.ocrOverlay.setOcrPage(savedPage);
                if (selStart >= 0 && selEnd >= 0) {
                    binding.ocrOverlay.setSelection(selStart, selEnd);
                }
                binding.ocrOverlay.setVisibility(View.VISIBLE);
                binding.layoutOcrBar.setVisibility(View.VISIBLE);
                hidePageIndicatorImmediately();
            });
        }
    }

    private void setupToolbar() {
        binding.btnBack.setOnClickListener(v -> finish());
        binding.btnOrganizePages.setOnClickListener(v -> openOrganizePages());
        binding.btnMore.setOnClickListener(this::showActionsMenu);
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
        // Mode is reflected dynamically in the actions menu
    }

    private void showActionsMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor, Gravity.END);
        popup.getMenuInflater().inflate(R.menu.menu_pdf_viewer, popup.getMenu());
        popup.setForceShowIcon(true);

        Menu menu = popup.getMenu();

        // 1. Dynamic Orientation item state
        MenuItem orientationItem = menu.findItem(R.id.action_toggle_orientation);
        if (orientationItem != null) {
            if (isImageDocument()) {
                orientationItem.setVisible(false);
            } else {
                orientationItem.setVisible(true);
                if (isSwipeHorizontal) {
                    orientationItem.setTitle(R.string.pdf_view_mode_vertical);
                    orientationItem.setIcon(R.drawable.ic_view_agenda);
                } else {
                    orientationItem.setTitle(R.string.pdf_view_mode_horizontal);
                    orientationItem.setIcon(R.drawable.ic_view_carousel);
                }
            }
        }

        MenuItem organizeItem = menu.findItem(R.id.action_organize_pages);
        if (organizeItem != null) {
            organizeItem.setVisible(!isImageDocument());
        }
        MenuItem splitItem = menu.findItem(R.id.action_split_pdf);
        if (splitItem != null) {
            splitItem.setVisible(!isImageDocument());
        }

        // 2. Tint icons with text_primary and apply subtle inset for a refined, modern look
        int primaryColor = ContextCompat.getColor(this, R.color.text_primary);
        int inset = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2, getResources().getDisplayMetrics());
        for (int i = 0; i < menu.size(); i++) {
            MenuItem item = menu.getItem(i);
            Drawable icon = item.getIcon();
            if (icon != null) {
                Drawable tinted = DrawableCompat.wrap(icon.mutate());
                DrawableCompat.setTint(tinted, primaryColor);
                item.setIcon(new InsetDrawable(tinted, inset));
            }
        }

        popup.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_share) {
                shareDocument();
                return true;
            } else if (id == R.id.action_export_images) {
                showExportImagesDialog();
                return true;
            } else if (id == R.id.action_organize_pages) {
                openOrganizePages();
                return true;
            } else if (id == R.id.action_split_pdf) {
                showSplitPdfDialog();
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
            } else if (id == R.id.action_toggle_orientation) {
                togglePageOrientation();
                return true;
            } else if (id == R.id.action_print) {
                printDocument();
                return true;
            }
            return false;
        });

        popup.show();
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

            boolean isActionView = Intent.ACTION_VIEW.equals(intent.getAction());
            boolean isInternal = (getCallingPackage() != null && getCallingPackage().equals(getPackageName()))
                    || (getPackageName().equals(intent.getPackage()) && !isActionView);

            if (isActionView && intent.getData() != null) {
                // If launched externally via ACTION_VIEW, strictly use intent.getData()
                Uri dataUri = intent.getData();
                if (isSafeUri(dataUri)) {
                    resolvedUri = dataUri;
                } else {
                    Log.w(TAG, "Blocked unsafe file URI in ACTION_VIEW");
                    showError();
                    return;
                }
            } else if (uriExtra != null) {
                Uri parsedUri = Uri.parse(uriExtra);
                if (isInternal || isSafeUri(parsedUri)) {
                    resolvedUri = parsedUri;
                } else {
                    Log.w(TAG, "Blocked unsafe file URI in EXTRA_PDF_URI");
                    showError();
                    return;
                }
            } else if (pathExtra != null) {
                if (isInternal) {
                    resolvedFile = new File(pathExtra);
                    resolvedUri = Uri.fromFile(resolvedFile);
                } else {
                    Log.w(TAG, "Ignored EXTRA_PDF_PATH from unverified external intent");
                    showError();
                    return;
                }
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

        documentId = intent.getLongExtra(EXTRA_DOCUMENT_ID, -1L);

        // 3. Check for JPG / Image document
        if (isImageDocument()) {
            binding.btnOrganizePages.setVisibility(View.GONE);
            loadImageDocument();
            return;
        }

        binding.btnOrganizePages.setVisibility(View.VISIBLE);

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
                    if (pageCount > 1 && !isAnnotationMode && !isOcrActive()) {
                        showPageIndicator(page + 1);
                    }
                })
                .onPageChange((page, pageCountTotal) -> {
                    pageCount = pageCountTotal;
                    if (pageCount > 1 && !isAnnotationMode && !isOcrActive()) {
                        showPageIndicator(page + 1);
                    }
                })
                .onLoad(nbPages -> {
                    pageCount = nbPages;
                    binding.pbLoading.setVisibility(View.GONE);
                    int current = binding.pdfView.getCurrentPage();
                    if (pageCount > 1) {
                        showPageIndicator(current + 1);
                    } else {
                        hidePageIndicatorImmediately();
                    }
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
        viewerExecutor.execute(() -> {
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

                PDFBoxResourceLoader.init(getApplicationContext());
                try (PDDocument doc = PDDocument.load(tempLocked, password)) {
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
        });
    }

    private void loadImageDocument() {
        viewerExecutor.execute(() -> {
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
                    pageCount = 1;
                    hidePageIndicatorImmediately();
                    binding.pbLoading.setVisibility(View.GONE);
                });

            } catch (Exception e) {
                Log.e(TAG, "Failed to load image document", e);
                runOnUiThread(this::showError);
            }
        });
    }

    private void updatePageIndicator(int currentPage) {
        showPageIndicator(currentPage);
    }

    /**
     * Shows the floating page indicator in "X / Y" format, smoothly fades it in,
     * and resets the auto-hide timer to 1.5 seconds.
     */
    private void showPageIndicator(int currentPage) {
        if (pageCount <= 1 || isAnnotationMode || isOcrActive()) {
            hidePageIndicatorImmediately();
            return;
        }

        indicatorHandler.removeCallbacks(hideIndicatorRunnable);

        if (indicatorAnimator != null) {
            indicatorAnimator.cancel();
            indicatorAnimator = null;
        }

        if (binding != null && binding.tvPageIndicator != null) {
            binding.tvPageIndicator.setText(currentPage + " / " + pageCount);

            if (binding.tvPageIndicator.getVisibility() != View.VISIBLE) {
                binding.tvPageIndicator.setAlpha(0f);
                binding.tvPageIndicator.setVisibility(View.VISIBLE);
            }

            if (binding.tvPageIndicator.getAlpha() < 1.0f) {
                indicatorAnimator = binding.tvPageIndicator.animate()
                        .alpha(1.0f)
                        .setDuration(150);
                indicatorAnimator.start();
            }

            indicatorHandler.postDelayed(hideIndicatorRunnable, INDICATOR_HIDE_DELAY_MS);
        }
    }

    /**
     * Smoothly fades out the floating page indicator and sets its visibility to GONE.
     */
    private void fadeOutPageIndicator() {
        if (binding == null || binding.tvPageIndicator == null) return;
        if (binding.tvPageIndicator.getVisibility() != View.VISIBLE) return;

        indicatorAnimator = binding.tvPageIndicator.animate()
                .alpha(0.0f)
                .setDuration(250)
                .withEndAction(() -> {
                    if (binding != null && binding.tvPageIndicator != null) {
                        binding.tvPageIndicator.setVisibility(View.GONE);
                    }
                });
        indicatorAnimator.start();
    }

    /**
     * Immediately cancels animators and hides the floating page indicator.
     */
    private void hidePageIndicatorImmediately() {
        if (indicatorHandler != null) {
            indicatorHandler.removeCallbacks(hideIndicatorRunnable);
        }
        if (indicatorAnimator != null) {
            indicatorAnimator.cancel();
            indicatorAnimator = null;
        }
        if (binding != null && binding.tvPageIndicator != null) {
            binding.tvPageIndicator.setVisibility(View.GONE);
        }
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

            viewerExecutor.execute(() -> {
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
            });
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
            binding.ocrOverlay.copySelectedTextToClipboard();
        });

        binding.btnCopyAllText.setOnClickListener(v -> {
            binding.ocrOverlay.showFullTextBottomSheet();
        });

        binding.ocrOverlay.setOnSelectionChangeListener((selectedText, hasSelection) -> {
            binding.btnCopySelected.setEnabled(hasSelection);
            binding.btnCopySelected.setAlpha(hasSelection ? 1.0f : 0.5f);
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
                showPageIndicator(currentOcrPageIndex + 1);
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
                        showPageIndicator(targetPage);
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

        viewerExecutor.execute(() -> {
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
        });
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
                    hidePageIndicatorImmediately();
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
        if (id == R.id.action_share) {
            shareDocument();
            return true;
        } else if (id == R.id.action_toggle_orientation) {
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
        } else if (id == R.id.action_export_images) {
            showExportImagesDialog();
            return true;
        } else if (id == R.id.action_print) {
            printDocument();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void shareDocument() {
        Uri targetUri = resolvedUri != null ? resolvedUri : (resolvedFile != null ? Uri.fromFile(resolvedFile) : null);
        if (targetUri == null) {
            Toast.makeText(this, R.string.pdf_load_error, Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            Uri shareUri = targetUri;
            boolean isFileUri = "file".equalsIgnoreCase(targetUri.getScheme());
            boolean isNonMediaStoreContent = "content".equalsIgnoreCase(targetUri.getScheme())
                    && (targetUri.getAuthority() == null || !targetUri.getAuthority().startsWith("media"));

            if (isFileUri || resolvedFile != null || isNonMediaStoreContent) {
                File shareDir = new File(getCacheDir(), "shared_export");
                if (!shareDir.exists()) {
                    shareDir.mkdirs();
                }

                String shareFileName = (documentTitle != null && !documentTitle.trim().isEmpty())
                        ? documentTitle.trim() : "shared_document";
                if (isImageDocument()) {
                    if (!shareFileName.toLowerCase(Locale.US).endsWith(".jpg")
                            && !shareFileName.toLowerCase(Locale.US).endsWith(".jpeg")
                            && !shareFileName.toLowerCase(Locale.US).endsWith(".png")) {
                        shareFileName += ".jpg";
                    }
                } else {
                    if (!shareFileName.toLowerCase(Locale.US).endsWith(".pdf")) {
                        shareFileName += ".pdf";
                    }
                }

                File destFile = new File(shareDir, shareFileName);
                if (isFileUri || resolvedFile != null) {
                    File srcFile = resolvedFile != null ? resolvedFile : new File(targetUri.getPath());
                    if (!destFile.getAbsolutePath().equals(srcFile.getAbsolutePath())) {
                        try (InputStream in = new FileInputStream(srcFile);
                             OutputStream out = new FileOutputStream(destFile)) {
                            byte[] buf = new byte[8192];
                            int len;
                            while ((len = in.read(buf)) > 0) {
                                out.write(buf, 0, len);
                            }
                            out.flush();
                        }
                    }
                } else {
                    try (InputStream in = getContentResolver().openInputStream(targetUri);
                         OutputStream out = new FileOutputStream(destFile)) {
                        if (in != null) {
                            byte[] buf = new byte[8192];
                            int len;
                            while ((len = in.read(buf)) > 0) {
                                out.write(buf, 0, len);
                            }
                            out.flush();
                        }
                    }
                }

                shareUri = androidx.core.content.FileProvider.getUriForFile(
                        this, getPackageName() + ".provider", destFile);
            }

            String mimeType = isImageDocument() ? "image/jpeg" : "application/pdf";
            String title = documentTitle != null ? documentTitle : getString(R.string.app_name);
            Intent shareIntent = StorageHelper.createShareIntent(shareUri, mimeType, title);
            startActivity(shareIntent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to share document", e);
            Toast.makeText(this, R.string.error_generic, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Protects against internal path traversal or arbitrary private file access
     * when URIs are received from third-party or untrusted external intents.
     */
    private boolean isSafeUri(Uri uri) {
        if (uri == null) return false;
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            String path = uri.getPath();
            if (path == null) return false;
            try {
                File targetFile = new File(path).getCanonicalFile();
                File dataDir = getApplicationInfo().dataDir != null ? new File(getApplicationInfo().dataDir).getCanonicalFile() : null;
                File filesDir = getFilesDir() != null ? getFilesDir().getCanonicalFile() : null;
                File appPrivateRoot = (dataDir != null) ? dataDir : (filesDir != null ? filesDir.getParentFile() : null);

                if (appPrivateRoot != null && targetFile.getPath().startsWith(appPrivateRoot.getPath())) {
                    Log.w(TAG, "Blocked access to internal private app path: " + targetFile.getPath());
                    return false;
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to resolve canonical path for URI security validation", e);
                return false;
            }
        }
        return true;
    }

    private void showExportImagesDialog() {
        Uri targetUri = resolvedUri != null ? resolvedUri : (resolvedFile != null ? Uri.fromFile(resolvedFile) : null);
        if (targetUri == null) {
            Toast.makeText(this, R.string.pdf_load_error, Toast.LENGTH_SHORT).show();
            return;
        }
        com.anscanner.app.ui.export.ExportImagesBottomSheet sheet =
                com.anscanner.app.ui.export.ExportImagesBottomSheet.newInstance(targetUri, documentTitle);
        sheet.show(getSupportFragmentManager(), "ExportImagesBottomSheet");
    }

    private void openOrganizePages() {
        Uri targetUri = resolvedUri != null ? resolvedUri : (resolvedFile != null ? Uri.fromFile(resolvedFile) : null);
        if (targetUri == null) {
            Toast.makeText(this, R.string.pdf_load_error, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, OrganizePagesActivity.class);
        intent.putExtra(OrganizePagesActivity.EXTRA_PDF_URI, targetUri);
        intent.putExtra(OrganizePagesActivity.EXTRA_DOCUMENT_TITLE, documentTitle);
        if (documentId > 0) {
            intent.putExtra(OrganizePagesActivity.EXTRA_DOCUMENT_ID, documentId);
        }
        organizePagesLauncher.launch(intent);
    }

    private void showSplitPdfDialog() {
        Uri targetUri = resolvedUri != null ? resolvedUri : (resolvedFile != null ? Uri.fromFile(resolvedFile) : null);
        if (targetUri == null) {
            Toast.makeText(this, R.string.pdf_load_error, Toast.LENGTH_SHORT).show();
            return;
        }
        SplitPdfBottomSheet sheet = SplitPdfBottomSheet.newInstance(targetUri, documentTitle);
        sheet.show(getSupportFragmentManager(), "SplitPdfBottomSheet");
    }

    // ── Flattened Annotation Engine (Sign/Draw) ──────────────────────────

    private void toggleAnnotationMode() {
        if (binding.pdfView.getVisibility() == View.VISIBLE) {
            final int initialIndex = binding.pdfView.getCurrentPage();
            binding.pbLoading.setVisibility(View.VISIBLE);

            viewerExecutor.execute(() -> {
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
            });
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
    protected void onStop() {
        super.onStop();
        if (indicatorHandler != null) {
            indicatorHandler.removeCallbacks(hideIndicatorRunnable);
        }
        if (indicatorAnimator != null) {
            indicatorAnimator.cancel();
            indicatorAnimator = null;
        }
    }

    @Override
    protected void onDestroy() {
        if (viewerExecutor != null && !viewerExecutor.isShutdown()) {
            viewerExecutor.shutdownNow();
        }
        if (indicatorHandler != null) {
            indicatorHandler.removeCallbacksAndMessages(null);
        }
        if (indicatorAnimator != null) {
            indicatorAnimator.cancel();
            indicatorAnimator = null;
        }
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
