package com.anscanner.app.service;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.UUID;

/**
 * Manages temporary bitmap storage on disk for the scanning pipeline.
 *
 * <p><strong>Memory management contract:</strong></p>
 * <ul>
 *   <li>Captured/processed bitmaps are compressed to JPEG and written to
 *       {@code context.getCacheDir()/scan_temp/}.</li>
 *   <li>Only the file path string is passed between Activities via Intent extras.</li>
 *   <li>The original in-memory Bitmap MUST be recycled by the caller immediately
 *       after calling {@link #saveTempBitmap(Bitmap, String)}.</li>
 *   <li>Thumbnails are loaded at reduced resolution via {@link #loadThumbnail(String, int)}
 *       to avoid OOM during multi-page grid display.</li>
 *   <li>{@link #clearScanCache()} wipes all temp files after a successful save.</li>
 * </ul>
 */
public final class CacheManager {

    private static final String TAG = "CacheManager";

    /** Subdirectory within getCacheDir() for temporary scan bitmaps */
    private static final String SCAN_TEMP_DIR = "scan_temp";

    /** Subdirectory for persistent document thumbnails (survive cache clears) */
    private static final String THUMBNAILS_DIR = "thumbnails";

    /** JPEG compression quality for temporary cache files (0-100) */
    private static final int TEMP_JPEG_QUALITY = 90;

    /** JPEG compression quality for thumbnails */
    private static final int THUMBNAIL_JPEG_QUALITY = 75;

    private CacheManager() {
        // Static utility class
    }

    // ════════════════════════════════════════════════════════════════════
    // Temporary Bitmap Storage (scan pipeline)
    // ════════════════════════════════════════════════════════════════════

    /**
     * Saves a bitmap to the temporary scan cache directory as a JPEG.
     *
     * <p><strong>After calling this method, the caller MUST call
     * {@code bitmap.recycle()} to free the live RAM.</strong></p>
     *
     * @param context Application context.
     * @param bitmap  The bitmap to save. NOT recycled by this method — caller must do it.
     * @param pageId  A unique identifier for this page (e.g., "page_1", "page_2").
     *                If null, a UUID is generated.
     * @return Absolute file path to the saved JPEG, or null on failure.
     */
    public static String saveTempBitmap(Context context, Bitmap bitmap, String pageId) {
        if (bitmap == null || bitmap.isRecycled()) {
            Log.e(TAG, "Cannot save null or recycled bitmap");
            return null;
        }

        File tempDir = getTempDir(context);
        String fileName = (pageId != null ? pageId : UUID.randomUUID().toString()) + ".jpg";
        File outputFile = new File(tempDir, fileName);

        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, TEMP_JPEG_QUALITY, fos);
            fos.flush();
            Log.d(TAG, "Temp bitmap saved: " + outputFile.getAbsolutePath() +
                    " (" + outputFile.length() + " bytes)");
            return outputFile.getAbsolutePath();
        } catch (IOException e) {
            Log.e(TAG, "Failed to save temp bitmap", e);
            return null;
        }
    }

    /**
     * Copies a content URI (such as an image or PDF from ML Kit Document Scanner)
     * to a temporary file in the scan cache directory.
     *
     * @param context   Application context.
     * @param uri       Content URI from scanner provider.
     * @param extension File extension without dot (e.g. "jpg" or "pdf").
     * @return Absolute file path to the local cached file, or null on failure.
     */
    public static String copyUriToTempFile(Context context, android.net.Uri uri, String extension) {
        if (context == null || uri == null) return null;
        File tempDir = getTempDir(context);
        String fileName = UUID.randomUUID().toString() + "." + (extension != null ? extension : "tmp");
        File outputFile = new File(tempDir, fileName);

        try (java.io.InputStream is = context.getContentResolver().openInputStream(uri);
             FileOutputStream fos = new FileOutputStream(outputFile)) {
            if (is == null) return null;
            byte[] buffer = new byte[8192];
            int length;
            while ((length = is.read(buffer)) > 0) {
                fos.write(buffer, 0, length);
            }
            fos.flush();
            return outputFile.getAbsolutePath();
        } catch (Exception e) {
            Log.e(TAG, "copyUriToTempFile failed for URI: " + uri, e);
            return null;
        }
    }

    /**
     * Loads a full-resolution bitmap from a cached file path.
     *
     * <p><strong>Caller must recycle the returned bitmap when done.</strong></p>
     *
     * @param filePath Absolute path to the JPEG file.
     * @return The loaded bitmap, or null on failure.
     */
    public static Bitmap loadBitmap(String filePath) {
        if (filePath == null) return null;
        File file = new File(filePath);
        if (!file.exists()) {
            Log.e(TAG, "Bitmap file not found: " + filePath);
            return null;
        }
        return BitmapFactory.decodeFile(filePath);
    }

    /**
     * Calculates the optimal inSampleSize for downscaling images.
     */
    public static int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;

            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }

    /**
     * Decodes a down-sampled bitmap from disk on a background thread matching requested dimensions.
     *
     * @param filePath Absolute path to the JPEG file.
     * @param reqWidth Target width in pixels.
     * @param reqHeight Target height in pixels.
     * @return Decoded and sampled bitmap, or null on failure.
     */
    public static Bitmap decodeSampledBitmap(String filePath, int reqWidth, int reqHeight) {
        if (filePath == null) return null;
        File file = new File(filePath);
        if (!file.exists()) return null;

        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(filePath, options);

            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);
            options.inJustDecodeBounds = false;
            options.inPreferredConfig = Bitmap.Config.RGB_565;

            return BitmapFactory.decodeFile(filePath, options);
        } catch (Exception e) {
            Log.e(TAG, "Failed to decode sampled bitmap: " + filePath, e);
            return null;
        }
    }

    /**
     * Loads a down-sampled thumbnail from a cached file path.
     * Uses inSampleSize to avoid loading full-resolution images into RAM.
     *
     * @param filePath  Absolute path to the JPEG file.
     * @param maxDimPx  Maximum width or height in pixels for the thumbnail.
     * @return Down-sampled bitmap, or null on failure.
     */
    public static Bitmap loadThumbnail(String filePath, int maxDimPx) {
        return decodeSampledBitmap(filePath, maxDimPx, maxDimPx);
    }

    /**
     * Overwrites an existing temp bitmap file with a new bitmap.
     * Used when applying filters or crops to a page that's already cached.
     *
     * @param context  Application context.
     * @param bitmap   New bitmap to save. Caller MUST recycle after this call.
     * @param filePath Existing file path to overwrite.
     * @return The same filePath on success, or null on failure.
     */
    public static String updateTempBitmap(Context context, Bitmap bitmap, String filePath) {
        if (bitmap == null || bitmap.isRecycled() || filePath == null) return null;

        File outputFile = new File(filePath);
        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, TEMP_JPEG_QUALITY, fos);
            fos.flush();
            return filePath;
        } catch (IOException e) {
            Log.e(TAG, "Failed to update temp bitmap: " + filePath, e);
            return null;
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Persistent Thumbnail Storage (for library & recent display)
    // ════════════════════════════════════════════════════════════════════

    /**
     * Downscales the first page image to a permanent thumbnail (~200x200)
     * using BitmapFactory.Options and saves it in context.getFilesDir().
     *
     * @param context     Application context.
     * @param sourcePath  Path to the source page image.
     * @param documentId  Unique document identifier or timestamp.
     * @return Absolute file path to the permanent thumbnail JPEG, or null on failure.
     */
    public static String savePermanentThumbnail(Context context, String sourcePath, long documentId) {
        if (context == null || sourcePath == null) {
            Log.e(TAG, "savePermanentThumbnail: context or sourcePath is null");
            return null;
        }

        File sourceFile = new File(sourcePath);
        if (!sourceFile.exists()) {
            Log.e(TAG, "savePermanentThumbnail: source file does not exist: " + sourcePath);
            return null;
        }

        try {
            // 1. Decode bounds only
            BitmapFactory.Options boundsOpts = new BitmapFactory.Options();
            boundsOpts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(sourcePath, boundsOpts);

            int origWidth = boundsOpts.outWidth;
            int origHeight = boundsOpts.outHeight;
            if (origWidth <= 0 || origHeight <= 0) {
                Log.e(TAG, "savePermanentThumbnail: invalid image bounds (" + origWidth + "x" + origHeight + ")");
                return null;
            }

            // 2. Downscale targeting 200x200 using inSampleSize
            final int targetDim = 200;
            BitmapFactory.Options decodeOpts = new BitmapFactory.Options();
            decodeOpts.inSampleSize = calculateInSampleSize(boundsOpts, targetDim, targetDim);
            decodeOpts.inPreferredConfig = Bitmap.Config.RGB_565;

            Bitmap sampledBitmap = BitmapFactory.decodeFile(sourcePath, decodeOpts);
            if (sampledBitmap == null) {
                Log.e(TAG, "savePermanentThumbnail: failed to decode sampled bitmap from " + sourcePath);
                return null;
            }

            // 3. Scale precisely to max 200x200 preserving aspect ratio
            float scale = Math.min((float) targetDim / sampledBitmap.getWidth(),
                                   (float) targetDim / sampledBitmap.getHeight());
            int finalWidth = Math.max(1, Math.round(sampledBitmap.getWidth() * scale));
            int finalHeight = Math.max(1, Math.round(sampledBitmap.getHeight() * scale));

            Bitmap finalThumb = Bitmap.createScaledBitmap(sampledBitmap, finalWidth, finalHeight, true);
            if (finalThumb != sampledBitmap) {
                sampledBitmap.recycle();
            }

            // 4. Save to permanent location in context.getFilesDir() (e.g., "thumb_" + documentId + ".jpg")
            File thumbFile = new File(context.getFilesDir(), "thumb_" + documentId + ".jpg");
            try (FileOutputStream fos = new FileOutputStream(thumbFile)) {
                finalThumb.compress(Bitmap.CompressFormat.JPEG, 85, fos);
                fos.flush();
            } finally {
                finalThumb.recycle();
            }

            Log.i(TAG, "Permanent thumbnail saved: " + thumbFile.getAbsolutePath() + " (" + thumbFile.length() + " bytes)");
            return thumbFile.getAbsolutePath();

        } catch (Exception e) {
            Log.e(TAG, "savePermanentThumbnail failed", e);
            return null;
        }
    }

    /**
     * Alias for {@link #savePermanentThumbnail(Context, String, long)} for backward compatibility.
     */
    public static String savePersistentThumbnail(Context context, String sourcePath, long documentId) {
        return savePermanentThumbnail(context, sourcePath, documentId);
    }

    // ════════════════════════════════════════════════════════════════════
    // Cache Clearing & Lifecycle Scratch Purge
    // ════════════════════════════════════════════════════════════════════

    /**
     * Clears all temporary scan files from the scan_temp directory.
     * Called after a successful save/share operation.
     */
    public static void clearScanCache(Context context) {
        if (context == null) return;
        File tempDir = getTempDir(context);
        int deletedCount = deleteDirectoryContents(tempDir);
        Log.i(TAG, "Scan cache cleared: " + deletedCount + " files deleted");
    }

    /**
     * Lifecycle background purge: cleans up temporary scratch files (.tmp, temporary preview
     * bitmaps, temporary scan exports) inside internal and external cache directories when the app
     * transitions to the background.
     *
     * <p>STRICT EXCLUSIONS: Never touches code_cache, SharedPreferences, Room databases (*.db,
     * *.db-shm, *.db-wal), or permanent user scan storage.</p>
     */
    public static void purgeScratchCache(Context context) {
        if (context == null) return;
        try {
            int purged = 0;
            // 1. Purge scan_temp
            purged += deleteDirectoryContents(getTempDir(context));

            // 2. Purge scratch files in getCacheDir()
            purged += purgeScratchInDir(context.getCacheDir());

            // 3. Purge scratch files in getExternalCacheDir()
            File extCache = context.getExternalCacheDir();
            if (extCache != null) {
                purged += purgeScratchInDir(extCache);
            }
            File[] extCaches = context.getExternalCacheDirs();
            if (extCaches != null) {
                for (File ec : extCaches) {
                    if (ec != null && !ec.equals(extCache)) {
                        purged += purgeScratchInDir(ec);
                    }
                }
            }
            Log.i(TAG, "Lifecycle scratch cache purged: " + purged + " files deleted");
        } catch (Exception e) {
            Log.e(TAG, "Failed to purge scratch cache", e);
        }
    }

    /**
     * Clears ALL cache thoroughly (internal cache, external cache, scan_temp, and thumbnails).
     * Called from Settings → Clear Cache.
     * Recursively deletes subdirectories and files while strictly excluding code_cache,
     * databases, and SharedPreferences.
     */
    public static void clearAllCache(Context context) {
        if (context == null) return;
        int deletedCount = 0;

        // 1. Clear scan_temp
        deletedCount += deleteDirectoryContents(getTempDir(context));

        // 2. Clear thumbnails in filesDir
        File thumbDir = getThumbDir(context);
        deletedCount += deleteDirectoryContents(thumbDir);

        // 3. Recursively clear internal cacheDir (excluding code_cache, databases, shared_prefs)
        File cacheDir = context.getCacheDir();
        deletedCount += deleteDirectoryContentsRecursive(cacheDir);

        // 4. Recursively clear externalCacheDir(s)
        File extCache = context.getExternalCacheDir();
        if (extCache != null) {
            deletedCount += deleteDirectoryContentsRecursive(extCache);
        }
        File[] extCaches = context.getExternalCacheDirs();
        if (extCaches != null) {
            for (File ec : extCaches) {
                if (ec != null && !ec.equals(extCache)) {
                    deletedCount += deleteDirectoryContentsRecursive(ec);
                }
            }
        }

        Log.i(TAG, "All cache cleared thoroughly: " + deletedCount + " files deleted");
    }

    /**
     * Calculates total cache size (internal cache excluding code_cache + external cache + thumbnails)
     * matching what mobile Android settings reports, and returns a human-readable string.
     */
    public static String getCacheSizeFormatted(Context context) {
        long totalBytes = getCacheSizeBytes(context);

        if (totalBytes < 1024) {
            return totalBytes + " B";
        } else if (totalBytes < 1024 * 1024) {
            return String.format(Locale.US, "%.0f KB", totalBytes / 1024.0);
        } else {
            return String.format(Locale.US, "%.1f MB", totalBytes / (1024.0 * 1024.0));
        }
    }

    /**
     * Returns total cache size in bytes matching system settings calculation.
     */
    public static long getCacheSizeBytes(Context context) {
        if (context == null) return 0;
        long totalBytes = getDirectorySizeRecursive(context.getCacheDir());

        File extCache = context.getExternalCacheDir();
        if (extCache != null) {
            totalBytes += getDirectorySizeRecursive(extCache);
        }
        File[] extCaches = context.getExternalCacheDirs();
        if (extCaches != null) {
            for (File ec : extCaches) {
                if (ec != null && !ec.equals(extCache)) {
                    totalBytes += getDirectorySizeRecursive(ec);
                }
            }
        }
        totalBytes += getDirectorySizeRecursive(getThumbDir(context));
        return totalBytes;
    }

    // ════════════════════════════════════════════════════════════════════
    // Internal Helpers
    // ════════════════════════════════════════════════════════════════════

    private static File getTempDir(Context context) {
        File dir = new File(context.getCacheDir(), SCAN_TEMP_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    private static File getThumbDir(Context context) {
        File dir = new File(context.getFilesDir(), THUMBNAILS_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    private static boolean isExcluded(File file) {
        if (file == null) return true;
        String name = file.getName();
        if ("code_cache".equals(name)) return true;
        if (name.endsWith(".db") || name.endsWith(".db-shm") || name.endsWith(".db-wal")) return true;
        if ("databases".equals(name) || "shared_prefs".equals(name)) return true;
        return false;
    }

    private static int deleteDirectoryContents(File dir) {
        int count = 0;
        if (dir != null && dir.exists() && dir.isDirectory() && !isExcluded(dir)) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (isExcluded(file)) continue;
                    if (file.isFile() && file.delete()) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private static int deleteDirectoryContentsRecursive(File dir) {
        int count = 0;
        if (dir == null || !dir.exists() || isExcluded(dir)) return 0;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (isExcluded(file)) continue;
                if (file.isDirectory()) {
                    count += deleteDirectoryContentsRecursive(file);
                    file.delete(); // Delete empty directory
                } else if (file.isFile()) {
                    if (file.delete()) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private static int purgeScratchInDir(File dir) {
        int count = 0;
        if (dir == null || !dir.exists() || isExcluded(dir)) return 0;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (isExcluded(file)) continue;
                if (file.isDirectory()) {
                    count += purgeScratchInDir(file);
                } else if (file.isFile()) {
                    String name = file.getName().toLowerCase(Locale.US);
                    if (name.endsWith(".tmp") || name.endsWith(".temp")
                            || name.contains("_cropped_") || name.startsWith("backup_")
                            || name.startsWith("temp_") || name.startsWith("ocr_")
                            || name.startsWith("preview_") || name.startsWith("export_")
                            || (name.startsWith("page_") && (name.endsWith(".jpg") || name.endsWith(".png")))) {
                        if (file.delete()) {
                            count++;
                        }
                    }
                }
            }
        }
        return count;
    }

    private static long getDirectorySizeRecursive(File dir) {
        if (dir == null || !dir.exists() || isExcluded(dir)) return 0;
        if (dir.isFile()) return dir.length();
        long size = 0;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (isExcluded(file)) continue;
                if (file.isDirectory()) {
                    size += getDirectorySizeRecursive(file);
                } else if (file.isFile()) {
                    size += file.length();
                }
            }
        }
        return size;
    }
}
