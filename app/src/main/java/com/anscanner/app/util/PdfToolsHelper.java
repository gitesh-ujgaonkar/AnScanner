package com.anscanner.app.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.anscanner.app.data.AppDatabase;
import com.anscanner.app.data.dao.DocumentDao;
import com.anscanner.app.data.entity.DocumentEntity;
import com.anscanner.app.service.CacheManager;
import com.anscanner.app.service.StorageHelper;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Modern, production-standard PDF Tools suite in 100% pure Java.
 * Provides stream-based lossless page rearrangement, rotation, deletion,
 * merging, and splitting using PDFBox and Android's native PdfRenderer.
 */
public final class PdfToolsHelper {

    private static final String TAG = "PdfToolsHelper";

    private PdfToolsHelper() {}

    /**
     * Data holder representing a page to retain, its source page index, and applied rotation.
     */
    public static class PageOrderInfo {
        public final int originalPageIndex;
        public final int rotationDegrees; // 0, 90, 180, 270 clockwise

        public PageOrderInfo(int originalPageIndex, int rotationDegrees) {
            this.originalPageIndex = originalPageIndex;
            this.rotationDegrees = ((rotationDegrees % 360) + 360) % 360;
        }
    }

    private static void ensurePdfBoxInitialized(@NonNull Context context) {
        try {
            PDFBoxResourceLoader.init(context.getApplicationContext());
        } catch (Throwable t) {
            Log.w(TAG, "PDFBox initialization note: " + t.getMessage());
        }
    }

    /**
     * Resolves a Content or File URI to a local seekable File in cache.
     * If the URI is already a direct local file, returns that File directly.
     */
    @Nullable
    public static File resolveToSeekableFile(@NonNull Context context, @NonNull Uri uri) {
        if ("file".equalsIgnoreCase(uri.getScheme()) && uri.getPath() != null) {
            File f = new File(uri.getPath());
            if (f.exists() && f.canRead()) {
                return f;
            }
        }
        String uriStr = uri.toString();
        if (uri.getScheme() == null || uriStr.startsWith("/")) {
            File f = new File(uri.getPath() != null ? uri.getPath() : uriStr);
            if (f.exists() && f.canRead()) {
                return f;
            }
        }
        File temp = new File(context.getCacheDir(), "seekable_" + UUID.randomUUID() + ".pdf");
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(temp)) {
            if (in == null) return null;
            byte[] buf = new byte[32768];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            out.flush();
            return temp;
        } catch (Exception e) {
            Log.e(TAG, "Failed to resolve URI to seekable file: " + uri, e);
            if (temp.exists()) temp.delete();
            return null;
        }
    }

    /**
     * Compiles a reorganized PDF document with the specified page sequence and rotations.
     * Pages omitted from the list are removed.
     *
     * @param context Application context.
     * @param sourceUri Source PDF document URI.
     * @param pages Desired page sequence with rotation angles.
     * @param outputFile Target output PDF file.
     * @return true if successfully reorganized and saved, false otherwise.
     */
    public static boolean reorganizePdf(
            @NonNull Context context,
            @NonNull Uri sourceUri,
            @NonNull List<PageOrderInfo> pages,
            @NonNull File outputFile) {

        if (pages.isEmpty()) {
            Log.e(TAG, "Cannot reorganize PDF: page list is empty");
            return false;
        }

        ensurePdfBoxInitialized(context);
        File tempSource = resolveToSeekableFile(context, sourceUri);
        if (tempSource == null || !tempSource.exists()) {
            Log.e(TAG, "Cannot reorganize PDF: source file is unreadable");
            return false;
        }

        PDDocument srcDoc = null;
        PDDocument dstDoc = null;
        try {
            srcDoc = PDDocument.load(tempSource);
            if (srcDoc.isEncrypted()) {
                try {
                    srcDoc.setAllSecurityToBeRemoved(true);
                } catch (Exception secEx) {
                    Log.w(TAG, "Could not remove security: " + secEx.getMessage());
                }
            }
            dstDoc = new PDDocument();

            int totalPages = srcDoc.getNumberOfPages();
            for (PageOrderInfo item : pages) {
                if (item.originalPageIndex >= 0 && item.originalPageIndex < totalPages) {
                    PDPage page = srcDoc.getPage(item.originalPageIndex);
                    if (item.rotationDegrees != 0) {
                        int currentRot = page.getRotation();
                        page.setRotation((currentRot + item.rotationDegrees) % 360);
                    }
                    dstDoc.importPage(page);
                }
            }

            File parent = outputFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }

            dstDoc.save(outputFile);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "reorganizePdf failed", e);
            return false;
        } finally {
            if (dstDoc != null) {
                try { dstDoc.close(); } catch (Exception ignored) {}
            }
            if (srcDoc != null) {
                try { srcDoc.close(); } catch (Exception ignored) {}
            }
            if (tempSource.getName().startsWith("seekable_") && tempSource.exists()) {
                tempSource.delete();
            }
        }
    }

    /**
     * Merges multiple PDF documents in the order provided into a single output PDF.
     * All source documents and temporary seekable files are held open until after the
     * merged document is written and saved to avoid COSStream closure exceptions.
     *
     * @param context Application context.
     * @param sourceUris List of PDF URIs to merge sequentially.
     * @param outputFile Output destination file.
     * @return true if successfully merged, false otherwise.
     */
    public static boolean mergePdfs(
            @NonNull Context context,
            @NonNull List<Uri> sourceUris,
            @NonNull File outputFile) {

        if (sourceUris.size() < 2) {
            Log.e(TAG, "mergePdfs requires at least 2 source URIs");
            return false;
        }

        ensurePdfBoxInitialized(context);
        PDDocument mergedDoc = null;
        List<PDDocument> openDocs = new ArrayList<>();
        List<File> tempFilesToClean = new ArrayList<>();

        try {
            mergedDoc = new PDDocument();

            for (Uri uri : sourceUris) {
                if (uri == null || Uri.EMPTY.equals(uri)) continue;

                File tempFile = resolveToSeekableFile(context, uri);
                if (tempFile == null || !tempFile.exists() || tempFile.length() == 0) {
                    Log.w(TAG, "Skipping unreadable source URI: " + uri);
                    continue;
                }

                if (tempFile.getName().startsWith("seekable_")) {
                    tempFilesToClean.add(tempFile);
                }

                try {
                    PDDocument doc = PDDocument.load(tempFile);
                    if (doc.isEncrypted()) {
                        try {
                            doc.setAllSecurityToBeRemoved(true);
                        } catch (Exception secEx) {
                            Log.w(TAG, "Could not remove security from document: " + uri, secEx);
                        }
                    }
                    openDocs.add(doc);

                    int count = doc.getNumberOfPages();
                    for (int i = 0; i < count; i++) {
                        mergedDoc.importPage(doc.getPage(i));
                    }
                } catch (Exception loadEx) {
                    Log.e(TAG, "Failed to load/import document: " + uri, loadEx);
                }
            }

            if (mergedDoc.getNumberOfPages() == 0) {
                Log.e(TAG, "mergePdfs: no pages were imported");
                return false;
            }

            File parent = outputFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }

            mergedDoc.save(outputFile);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "mergePdfs failed", e);
            return false;
        } finally {
            if (mergedDoc != null) {
                try { mergedDoc.close(); } catch (Exception ignored) {}
            }
            for (PDDocument doc : openDocs) {
                try { doc.close(); } catch (Exception ignored) {}
            }
            for (File temp : tempFilesToClean) {
                try {
                    if (temp.exists()) temp.delete();
                } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Extracts a subset of pages from a PDF document into a new PDF.
     *
     * @param context Application context.
     * @param sourceUri Source PDF document URI.
     * @param pageIndices 0-indexed list of pages to extract.
     * @param outputFile Destination PDF file.
     * @return true if successfully extracted, false otherwise.
     */
    public static boolean extractPages(
            @NonNull Context context,
            @NonNull Uri sourceUri,
            @NonNull List<Integer> pageIndices,
            @NonNull File outputFile) {

        if (pageIndices.isEmpty()) return false;

        ensurePdfBoxInitialized(context);
        File tempSource = resolveToSeekableFile(context, sourceUri);
        if (tempSource == null || !tempSource.exists()) return false;

        PDDocument srcDoc = null;
        PDDocument dstDoc = null;
        try {
            srcDoc = PDDocument.load(tempSource);
            if (srcDoc.isEncrypted()) {
                try {
                    srcDoc.setAllSecurityToBeRemoved(true);
                } catch (Exception secEx) {
                    Log.w(TAG, "Could not remove security: " + secEx.getMessage());
                }
            }
            dstDoc = new PDDocument();
            int total = srcDoc.getNumberOfPages();

            for (int idx : pageIndices) {
                if (idx >= 0 && idx < total) {
                    dstDoc.importPage(srcDoc.getPage(idx));
                }
            }

            if (dstDoc.getNumberOfPages() == 0) return false;

            File parent = outputFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }

            dstDoc.save(outputFile);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "extractPages failed", e);
            return false;
        } finally {
            if (dstDoc != null) {
                try { dstDoc.close(); } catch (Exception ignored) {}
            }
            if (srcDoc != null) {
                try { srcDoc.close(); } catch (Exception ignored) {}
            }
            if (tempSource.getName().startsWith("seekable_") && tempSource.exists()) {
                tempSource.delete();
            }
        }
    }

    /**
     * Splits a multi-page PDF document so that each page becomes an independent single-page PDF.
     *
     * @param context Application context.
     * @param sourceUri Source PDF URI.
     * @param outputDir Directory where generated single-page PDFs should be placed.
     * @param baseName Base name prefix for files (e.g., "Report").
     * @return List of generated single-page PDF files.
     */
    @NonNull
    public static List<File> splitIntoSinglePages(
            @NonNull Context context,
            @NonNull Uri sourceUri,
            @NonNull File outputDir,
            @NonNull String baseName) {

        List<File> generated = new ArrayList<>();
        ensurePdfBoxInitialized(context);
        File tempSource = resolveToSeekableFile(context, sourceUri);
        if (tempSource == null || !tempSource.exists()) return generated;

        PDDocument srcDoc = null;
        try {
            srcDoc = PDDocument.load(tempSource);
            if (srcDoc.isEncrypted()) {
                try {
                    srcDoc.setAllSecurityToBeRemoved(true);
                } catch (Exception secEx) {
                    Log.w(TAG, "Could not remove security: " + secEx.getMessage());
                }
            }
            int count = srcDoc.getNumberOfPages();
            if (!outputDir.exists()) outputDir.mkdirs();

            for (int i = 0; i < count; i++) {
                PDDocument singleDoc = new PDDocument();
                try {
                    singleDoc.importPage(srcDoc.getPage(i));
                    File singleFile = new File(outputDir, baseName + "_Page_" + (i + 1) + ".pdf");
                    singleDoc.save(singleFile);
                    generated.add(singleFile);
                } finally {
                    try { singleDoc.close(); } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "splitIntoSinglePages failed", e);
        } finally {
            if (srcDoc != null) {
                try { srcDoc.close(); } catch (Exception ignored) {}
            }
            if (tempSource.getName().startsWith("seekable_") && tempSource.exists()) {
                tempSource.delete();
            }
        }
        return generated;
    }

    /**
     * Safely queries the page count of a PDF using Android's native PdfRenderer.
     *
     * @param context Application context.
     * @param pdfUri Source PDF URI.
     * @return Number of pages, or 0 on failure.
     */
    public static int getPdfPageCount(@NonNull Context context, @NonNull Uri pdfUri) {
        File seekable = resolveToSeekableFile(context, pdfUri);
        if (seekable == null || !seekable.exists()) return 0;

        ParcelFileDescriptor pfd = null;
        try {
            pfd = ParcelFileDescriptor.open(seekable, ParcelFileDescriptor.MODE_READ_ONLY);
            if (pfd != null) {
                try (PdfRenderer renderer = new PdfRenderer(pfd)) {
                    return renderer.getPageCount();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "getPdfPageCount failed for: " + pdfUri, e);
        } finally {
            if (pfd != null) {
                try { pfd.close(); } catch (Exception ignored) {}
            }
            if (seekable.getName().startsWith("seekable_") && seekable.exists()) {
                seekable.delete();
            }
        }
        return 0;
    }

    /**
     * Renders a single page thumbnail as an ARGB_8888 Bitmap with guaranteed opaque white background.
     *
     * @param context Application context.
     * @param pdfUri Source PDF URI.
     * @param pageIndex 0-indexed page number.
     * @param targetWidth Desired width in pixels (aspect ratio maintained).
     * @return Rendered thumbnail Bitmap, or null on failure.
     */
    @Nullable
    public static Bitmap renderPageThumbnail(
            @NonNull Context context,
            @NonNull Uri pdfUri,
            int pageIndex,
            int targetWidth) {

        File seekable = resolveToSeekableFile(context, pdfUri);
        if (seekable == null || !seekable.exists()) return null;

        ParcelFileDescriptor pfd = null;
        try {
            pfd = ParcelFileDescriptor.open(seekable, ParcelFileDescriptor.MODE_READ_ONLY);
            if (pfd != null) {
                try (PdfRenderer renderer = new PdfRenderer(pfd)) {
                    if (pageIndex < 0 || pageIndex >= renderer.getPageCount()) return null;
                    PdfRenderer.Page page = renderer.openPage(pageIndex);
                    try {
                        int w = Math.max(1, targetWidth);
                        int h = Math.max(1, Math.round((float) w * page.getHeight() / Math.max(1, page.getWidth())));
                        return PdfRendererHelper.renderPageWithWhiteBackground(
                                page, w, h, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    } finally {
                        page.close();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "renderPageThumbnail failed for page " + pageIndex, e);
        } finally {
            if (pfd != null) {
                try { pfd.close(); } catch (Exception ignored) {}
            }
            if (seekable.getName().startsWith("seekable_") && seekable.exists()) {
                seekable.delete();
            }
        }
        return null;
    }

    /**
     * Commits a generated or modified PDF file to public Scoped Storage via MediaStore
     * and creates an entry in the Room database so it appears in the Library.
     *
     * @param context Application or Activity context.
     * @param pdfFile Local generated PDF file on disk.
     * @param documentTitle Clean user-facing document title (without .pdf extension).
     * @param pageCount Total number of pages in the PDF.
     * @return Newly created DocumentEntity, or null on failure.
     */
    @Nullable
    public static DocumentEntity commitPdfToLibrary(
            @NonNull Context context,
            @NonNull File pdfFile,
            @NonNull String documentTitle,
            int pageCount) {

        if (!pdfFile.exists() || pdfFile.length() == 0) return null;

        Context appContext = context.getApplicationContext();
        long docTimestamp = System.currentTimeMillis();
        long fileSize = pdfFile.length();

        // 1. Generate permanent thumbnail for Page 1 from the local file before moving
        String thumbPath = null;
        Bitmap firstPageThumb = renderPageThumbnail(appContext, Uri.fromFile(pdfFile), 0, 300);
        if (firstPageThumb != null) {
            thumbPath = CacheManager.savePermanentThumbnail(appContext, firstPageThumb, docTimestamp);
        }

        // 2. Save to Scoped Storage via MediaStore
        Uri savedUri = StorageHelper.savePdfToPublicStorage(appContext, pdfFile, documentTitle);
        if (savedUri == null) {
            Log.e(TAG, "Failed to save PDF to public storage");
            return null;
        }

        // If thumbnail wasn't generated from local file, attempt from savedUri
        if (thumbPath == null) {
            Bitmap fallbackThumb = renderPageThumbnail(appContext, savedUri, 0, 300);
            if (fallbackThumb != null) {
                thumbPath = CacheManager.savePermanentThumbnail(appContext, fallbackThumb, docTimestamp);
            }
        }

        // 3. Create DocumentEntity and insert into Room
        DocumentEntity entity = new DocumentEntity(
                documentTitle,
                "PDF",
                "High",
                pageCount,
                fileSize,
                savedUri.toString(),
                thumbPath,
                docTimestamp
        );

        DocumentDao dao = AppDatabase.getInstance(appContext).documentDao();
        long insertedId = dao.insertDocument(entity);
        entity.id = insertedId;

        return entity;
    }
}
