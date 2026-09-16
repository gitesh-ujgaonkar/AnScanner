package com.anscanner.app.service;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * Handles saving scanned documents to public Scoped Storage via MediaStore
 * and provides sharing intents.
 *
 * <p>Documents are saved to {@code Documents/AnScanner/} (PDF) or
 * {@code Pictures/AnScanner/} (JPG) using the MediaStore API for
 * Android 10+ Scoped Storage compliance.</p>
 *
 * <p>No WRITE_EXTERNAL_STORAGE permission is needed for MediaStore inserts
 * on API 29+.</p>
 */
public final class StorageHelper {

    private static final String TAG = "StorageHelper";

    /** Subdirectory name within Documents/ or Pictures/ */
    private static final String APP_DIRECTORY = "AnScanner";

    private StorageHelper() {
        // Static utility class
    }

    // ════════════════════════════════════════════════════════════════════
    // Save to Public Storage
    // ════════════════════════════════════════════════════════════════════

    /**
     * Saves a PDF file to public Documents/AnScanner/ via MediaStore.
     *
     * @param context    Application context.
     * @param sourceFile The generated PDF file in cache.
     * @param fileName   User-facing filename (e.g., "Doc_2026_09_12.pdf").
     * @return Content URI of the saved file, or null on failure.
     */
    public static Uri savePdfToPublicStorage(Context context, File sourceFile, String fileName) {
        if (!fileName.toLowerCase(Locale.US).endsWith(".pdf")) {
            fileName = fileName + ".pdf";
        }

        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOCUMENTS + "/" + APP_DIRECTORY);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        }

        Uri collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri itemUri = resolver.insert(collection, values);

        if (itemUri == null) {
            Log.e(TAG, "Failed to create MediaStore entry for PDF");
            return null;
        }

        try {
            // Copy the file content to the MediaStore output stream
            try (OutputStream os = resolver.openOutputStream(itemUri);
                 InputStream is = new FileInputStream(sourceFile)) {

                if (os == null) {
                    Log.e(TAG, "Failed to open output stream for URI: " + itemUri);
                    resolver.delete(itemUri, null, null);
                    return null;
                }

                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    os.write(buffer, 0, bytesRead);
                }
                os.flush();
            }

            // Mark as no longer pending
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear();
                values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                resolver.update(itemUri, values, null, null);
            }

            Log.i(TAG, "PDF saved to MediaStore: " + itemUri);
            return itemUri;

        } catch (IOException e) {
            Log.e(TAG, "Failed to write PDF to MediaStore", e);
            resolver.delete(itemUri, null, null);
            return null;
        }
    }

    /**
     * Saves a JPG image to public Pictures/AnScanner/ via MediaStore.
     *
     * @param context   Application context.
     * @param imagePath Path to the processed JPEG image in cache.
     * @param fileName  User-facing filename (e.g., "Doc_2026_09_12.jpg").
     * @param quality   JPEG compression quality (0-100). Use 95 for HIGH, 70 for NORMAL.
     * @return Content URI of the saved file, or null on failure.
     */
    public static Uri saveJpgToPublicStorage(Context context, String imagePath,
                                              String fileName, int quality) {
        if (!fileName.toLowerCase(Locale.US).endsWith(".jpg")
                && !fileName.toLowerCase(Locale.US).endsWith(".jpeg")) {
            fileName = fileName + ".jpg";
        }

        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/" + APP_DIRECTORY);
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
        }

        Uri collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri itemUri = resolver.insert(collection, values);

        if (itemUri == null) {
            Log.e(TAG, "Failed to create MediaStore entry for JPG");
            return null;
        }

        // Load bitmap from cache, compress to output stream, recycle immediately
        Bitmap bitmap = BitmapFactory.decodeFile(imagePath);
        if (bitmap == null) {
            Log.e(TAG, "Failed to decode bitmap for JPG save: " + imagePath);
            resolver.delete(itemUri, null, null);
            return null;
        }

        try (OutputStream os = resolver.openOutputStream(itemUri)) {
            if (os == null) {
                Log.e(TAG, "Failed to open output stream for JPG URI: " + itemUri);
                resolver.delete(itemUri, null, null);
                return null;
            }

            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, os);
            os.flush();

        } catch (IOException e) {
            Log.e(TAG, "Failed to write JPG to MediaStore", e);
            resolver.delete(itemUri, null, null);
            return null;
        } finally {
            bitmap.recycle();
        }

        // Mark as no longer pending
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear();
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            resolver.update(itemUri, values, null, null);
        }

        Log.i(TAG, "JPG saved to MediaStore: " + itemUri);
        return itemUri;
    }

    // ════════════════════════════════════════════════════════════════════
    // Share Intent
    // ════════════════════════════════════════════════════════════════════

    /**
     * Creates a share Intent for a saved document.
     *
     * @param contentUri  Content URI from MediaStore.
     * @param mimeType    MIME type ("application/pdf" or "image/jpeg").
     * @param title       Document title for the chooser.
     * @return Configured share Intent.
     */
    public static Intent createShareIntent(Uri contentUri, String mimeType, String title) {
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType(mimeType);
        shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, title);
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return Intent.createChooser(shareIntent, "Share " + title);
    }

    // ════════════════════════════════════════════════════════════════════
    // File Size Formatting
    // ════════════════════════════════════════════════════════════════════

    /**
     * Formats a byte count into a human-readable string.
     *
     * @param bytes File size in bytes.
     * @return Formatted string like "1.2 MB", "256 KB", etc.
     */
    public static String formatFileSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
        } else {
            return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
        }
    }

    /**
     * Estimates the output file size for a given set of page paths and format.
     * Used to display the size badge in the Save Bottom Sheet before saving.
     *
     * @param pagePaths List of cached page image paths.
     * @param isPdf     True for PDF, false for JPG.
     * @return Estimated size string.
     */
    public static String estimateFileSize(java.util.List<String> pagePaths, boolean isPdf) {
        return estimateFileSize(pagePaths, isPdf, 100);
    }

    /**
     * Estimates the output file size for a given set of page paths, format, and compression quality.
     *
     * @param pagePaths List of cached page image paths.
     * @param isPdf     True for PDF, false for JPG.
     * @param quality   Compression quality (1-100).
     * @return Formatted size string.
     */
    public static String estimateFileSize(java.util.List<String> pagePaths, boolean isPdf, int quality) {
        long totalBytes = 0;
        for (String path : pagePaths) {
            File f = new File(path);
            if (f.exists()) {
                totalBytes += f.length();
            }
        }

        // Adjust estimate based on quality factor
        float factor = (float) Math.max(25, quality) / 100.0f;
        totalBytes = (long) (totalBytes * factor);

        // PDF adds ~10-15% overhead for document structure
        if (isPdf) {
            totalBytes = (long) (totalBytes * 1.12);
        }

        return formatFileSize(totalBytes);
    }

    /**
     * Resolves the display filename from a content or file URI.
     */
    public static String getFileName(ContentResolver resolver, Uri uri) {
        if (uri == null) return null;
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            return uri.getLastPathSegment();
        }
        try (android.database.Cursor cursor = resolver.query(uri,
                new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int col = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
                if (col != -1) {
                    return cursor.getString(col);
                }
            }
        } catch (Exception ignored) {}
        return uri.getLastPathSegment();
    }
}
