package com.anscanner.app.service;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.format.Formatter;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;

import com.anscanner.app.data.entity.DocumentEntity;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Public PDF discovery scanner using Android's MediaStore API and user-directed SAF folder sync.
 * 100% pure Java, Scoped Storage compliant without MANAGE_EXTERNAL_STORAGE.
 */
public final class DevicePdfScanner {

    private static final String TAG = "DevicePdfScanner";

    private static final String PREFS_NAME = "anscanner_device_pdf_scanner";
    private static final String KEY_SYNCED_FOLDER_URI = "key_synced_folder_uri";

    private static final ExecutorService sExecutor = Executors.newFixedThreadPool(2);
    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());

    private DevicePdfScanner() {
        // Utility class
    }

    /**
     * Immutable data model representing a discovered public PDF.
     */
    public static class PdfItem {
        public final long id;
        public final Uri uri;
        public final String title;
        public final long sizeBytes;
        public final String formattedSize;
        public final long dateModifiedMillis;
        public final String mimeType;
        public final String data;
        public final String relativePath;

        public PdfItem(long id,
                       @NonNull Uri uri,
                       @NonNull String title,
                       long sizeBytes,
                       @NonNull String formattedSize,
                       long dateModifiedMillis,
                       @NonNull String mimeType,
                       @Nullable String data,
                       @Nullable String relativePath) {
            this.id = id;
            this.uri = uri;
            this.title = title;
            this.sizeBytes = sizeBytes;
            this.formattedSize = formattedSize;
            this.dateModifiedMillis = dateModifiedMillis;
            this.mimeType = mimeType;
            this.data = data;
            this.relativePath = relativePath;
        }

        @NonNull
        public DocumentEntity toDocumentEntity() {
            DocumentEntity entity = new DocumentEntity();
            entity.id = -Math.abs(id != 0 ? id : uri.toString().hashCode());
            entity.title = (title != null && !title.trim().isEmpty()) ? title : "PDF Document";
            entity.format = "PDF";
            entity.quality = "NORMAL";
            entity.pageCount = 0;
            entity.fileSizeBytes = sizeBytes;
            entity.fileUri = uri.toString();
            entity.createdAt = dateModifiedMillis > 0 ? dateModifiedMillis : System.currentTimeMillis();
            entity.updatedAt = entity.createdAt;
            return entity;
        }
    }

    /**
     * Callback for asynchronous PDF item loading.
     */
    public interface PdfCallback {
        void onPdfsLoaded(@NonNull List<PdfItem> items);
        void onError(@NonNull Exception exception);
    }

    /**
     * Callback for asynchronous DocumentEntity loading.
     */
    public interface DocumentCallback {
        void onDocumentsLoaded(@NonNull List<DocumentEntity> documents);
        void onError(@NonNull Exception exception);
    }

    /**
     * Checks whether storage permission is granted.
     * On Android 13+ (API 33+), public files can be discovered via MediaStore without storage permissions.
     * On Android 12 and below (API <= 32), READ_EXTERNAL_STORAGE is required.
     */
    public static boolean hasStoragePermission(@NonNull Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return true;
        }
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Stores user-synced folder tree URI in SharedPreferences.
     */
    public static void saveSyncedFolderUri(@NonNull Context context, @NonNull Uri uri) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_SYNCED_FOLDER_URI, uri.toString()).apply();
    }

    /**
     * Retrieves the persisted user-synced folder tree URI, if any.
     */
    @Nullable
    public static Uri getSyncedFolderUri(@NonNull Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String uriStr = prefs.getString(KEY_SYNCED_FOLDER_URI, null);
        return uriStr != null ? Uri.parse(uriStr) : null;
    }

    /**
     * Returns true if user has already synced a folder via SAF.
     */
    public static boolean hasSyncedFolder(@NonNull Context context) {
        return getSyncedFolderUri(context) != null;
    }

    /**
     * Clears persisted synced folder URI.
     */
    public static void clearSyncedFolderUri(@NonNull Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().remove(KEY_SYNCED_FOLDER_URI).apply();
    }

    /**
     * Synchronously queries public PDFs from MediaStore.
     * Widened criteria matches standard MIME types and .pdf extensions across all external collections.
     * Must be called on a background thread.
     */
    @NonNull
    public static List<PdfItem> scanPublicPdfs(@NonNull Context context, @Nullable String searchQuery) {
        List<PdfItem> results = new ArrayList<>();
        Set<String> seenUris = new HashSet<>();
        Set<String> seenKeys = new HashSet<>();

        Set<Uri> queryUris = new LinkedHashSet<>();
        // Query primary external files collection (covers all external storage)
        queryUris.add(MediaStore.Files.getContentUri("external"));

        // Query MediaStore Downloads on Android 10+ (API 29+) to ensure downloaded PDFs are discovered
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            queryUris.add(MediaStore.Downloads.EXTERNAL_CONTENT_URI);
        }

        List<String> projList = new ArrayList<>();
        projList.add(MediaStore.Files.FileColumns._ID);
        projList.add(MediaStore.MediaColumns.DISPLAY_NAME);
        projList.add(MediaStore.MediaColumns.SIZE);
        projList.add(MediaStore.MediaColumns.DATE_MODIFIED);
        projList.add(MediaStore.MediaColumns.MIME_TYPE);
        projList.add(MediaStore.MediaColumns.DATA);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projList.add(MediaStore.MediaColumns.RELATIVE_PATH);
        }
        String[] projection = projList.toArray(new String[0]);

        // Widened selection filter to catch application/pdf, application/x-pdf, and .pdf display names
        String selection = "(" + MediaStore.Files.FileColumns.MIME_TYPE + " = ? OR "
                + MediaStore.Files.FileColumns.MIME_TYPE + " = ? OR "
                + MediaStore.MediaColumns.DISPLAY_NAME + " LIKE '%.pdf' OR "
                + MediaStore.MediaColumns.DISPLAY_NAME + " LIKE '%.PDF')";
        String[] selectionArgs = new String[] { "application/pdf", "application/x-pdf" };
        String sortOrder = MediaStore.MediaColumns.DATE_MODIFIED + " DESC";

        ContentResolver resolver = context.getContentResolver();

        for (Uri baseUri : queryUris) {
            try (Cursor cursor = resolver.query(baseUri, projection, selection, selectionArgs, sortOrder)) {
                if (cursor != null) {
                    int idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID);
                    int nameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
                    int sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE);
                    int dateModifiedCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED);
                    int mimeCol = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE);
                    int dataCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA);
                    int relPathCol = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                            ? cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH) : -1;

                    while (cursor.moveToNext()) {
                        long id = cursor.getLong(idCol);
                        String name = nameCol != -1 ? cursor.getString(nameCol) : null;
                        long size = sizeCol != -1 ? cursor.getLong(sizeCol) : 0L;
                        long dateModifiedSec = dateModifiedCol != -1 ? cursor.getLong(dateModifiedCol) : 0L;
                        String mime = mimeCol != -1 ? cursor.getString(mimeCol) : "application/pdf";
                        String data = dataCol != -1 ? cursor.getString(dataCol) : null;
                        String relPath = relPathCol != -1 ? cursor.getString(relPathCol) : null;

                        // Verify that entry is indeed a PDF (by filename, data path, or MIME type)
                        boolean isPdf = (name != null && name.toLowerCase(Locale.ROOT).endsWith(".pdf"))
                                || (data != null && data.toLowerCase(Locale.ROOT).endsWith(".pdf"))
                                || (mime != null && (mime.equalsIgnoreCase("application/pdf") || mime.equalsIgnoreCase("application/x-pdf")));
                        if (!isPdf) {
                            continue;
                        }

                        Uri contentUri = ContentUris.withAppendedId(baseUri, id);
                        String uriString = contentUri.toString();
                        if (seenUris.contains(uriString)) {
                            continue;
                        }

                        String title = (name != null && !name.trim().isEmpty())
                                ? name
                                : (data != null ? new File(data).getName() : "PDF Document");

                        // Deduplicate across volumes/collections by title and size
                        String dedupeKey = title.toLowerCase(Locale.ROOT) + "_" + size;
                        if (seenKeys.contains(dedupeKey)) {
                            continue;
                        }

                        // Apply search filter if provided
                        if (searchQuery != null && !searchQuery.trim().isEmpty()) {
                            if (!title.toLowerCase(Locale.getDefault()).contains(searchQuery.toLowerCase(Locale.getDefault()).trim())) {
                                continue;
                            }
                        }

                        seenUris.add(uriString);
                        seenKeys.add(dedupeKey);

                        String formattedSize = Formatter.formatFileSize(context, size);
                        long dateModifiedMillis = dateModifiedSec * 1000L;

                        results.add(new PdfItem(id, contentUri, title, size, formattedSize, dateModifiedMillis, mime, data, relPath));
                    }
                }
            } catch (SecurityException se) {
                Log.w(TAG, "SecurityException querying MediaStore uri: " + baseUri, se);
            } catch (Exception e) {
                Log.e(TAG, "Error querying MediaStore uri: " + baseUri, e);
            }
        }

        // Sort descending by date modified
        Collections.sort(results, (a, b) -> Long.compare(b.dateModifiedMillis, a.dateModifiedMillis));

        return results;
    }

    /**
     * Traverses a user-granted SAF document tree (e.g., Downloads folder) and extracts all PDF documents.
     */
    @NonNull
    public static List<DocumentEntity> scanFolderTree(@NonNull Context context,
                                                      @NonNull Uri treeUri,
                                                      @Nullable String searchQuery) {
        List<DocumentEntity> treeDocuments = new ArrayList<>();
        try {
            DocumentFile root = DocumentFile.fromTreeUri(context, treeUri);
            if (root != null && root.isDirectory()) {
                traverseTree(context, root, treeDocuments, searchQuery, 0);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error traversing SAF folder tree: " + treeUri, e);
        }
        return treeDocuments;
    }

    private static void traverseTree(@NonNull Context context,
                                     @NonNull DocumentFile dir,
                                     @NonNull List<DocumentEntity> results,
                                     @Nullable String searchQuery,
                                     int depth) {
        if (depth > 4) return; // Prevent excessive recursion
        DocumentFile[] files = dir.listFiles();
        if (files == null) return;

        File cacheDir = context.getCacheDir();
        for (DocumentFile file : files) {
            if (file == null) continue;
            if (file.isDirectory()) {
                traverseTree(context, file, results, searchQuery, depth + 1);
            } else if (file.isFile()) {
                String name = file.getName();
                String type = file.getType();
                boolean isPdf = (name != null && name.toLowerCase(Locale.ROOT).endsWith(".pdf"))
                        || (type != null && (type.equalsIgnoreCase("application/pdf") || type.equalsIgnoreCase("application/x-pdf")));
                if (!isPdf) continue;

                String title = (name != null && !name.trim().isEmpty()) ? name : "PDF Document";

                if (searchQuery != null && !searchQuery.trim().isEmpty()) {
                    if (!title.toLowerCase(Locale.getDefault()).contains(searchQuery.toLowerCase(Locale.getDefault()).trim())) {
                        continue;
                    }
                }

                DocumentEntity entity = new DocumentEntity();
                entity.id = -Math.abs((long) file.getUri().toString().hashCode());
                entity.title = title;
                entity.format = "PDF";
                entity.quality = "NORMAL";
                entity.pageCount = 0;
                entity.fileSizeBytes = file.length();
                entity.fileUri = file.getUri().toString();
                entity.createdAt = file.lastModified() > 0 ? file.lastModified() : System.currentTimeMillis();
                entity.updatedAt = entity.createdAt;

                File cachedThumb = new File(cacheDir, "thumb_device_" + Math.abs(entity.id) + ".jpg");
                if (cachedThumb.exists() && cachedThumb.length() > 0) {
                    entity.thumbnailPath = cachedThumb.getAbsolutePath();
                } else {
                    entity.thumbnailPath = null;
                }

                results.add(entity);
            }
        }
    }

    /**
     * Synchronously queries public PDFs from MediaStore and any persisted synced SAF tree.
     * Deduplicates across both sources and attaches cached thumbnail paths.
     * Must be called on a background thread.
     */
    @NonNull
    public static List<DocumentEntity> scanPublicDocuments(@NonNull Context context, @Nullable String searchQuery) {
        List<PdfItem> items = scanPublicPdfs(context, searchQuery);
        List<DocumentEntity> entities = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();
        File cacheDir = context.getCacheDir();

        for (PdfItem item : items) {
            DocumentEntity entity = item.toDocumentEntity();
            File cachedThumb = new File(cacheDir, "thumb_device_" + Math.abs(entity.id) + ".jpg");
            if (cachedThumb.exists() && cachedThumb.length() > 0) {
                entity.thumbnailPath = cachedThumb.getAbsolutePath();
            } else {
                entity.thumbnailPath = null;
            }
            String key = entity.title.toLowerCase(Locale.ROOT) + "_" + entity.fileSizeBytes;
            seenKeys.add(key);
            entities.add(entity);
        }

        // If user has synced a folder (e.g. Downloads), also scan that tree with guaranteed read permissions
        Uri syncedTreeUri = getSyncedFolderUri(context);
        if (syncedTreeUri != null) {
            try {
                List<DocumentEntity> treeDocs = scanFolderTree(context, syncedTreeUri, searchQuery);
                for (DocumentEntity treeDoc : treeDocs) {
                    String key = treeDoc.title.toLowerCase(Locale.ROOT) + "_" + treeDoc.fileSizeBytes;
                    if (!seenKeys.contains(key)) {
                        seenKeys.add(key);
                        entities.add(treeDoc);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed scanning synced folder tree: " + syncedTreeUri, e);
            }
        }

        // Sort descending by created/modified date
        Collections.sort(entities, (d1, d2) -> Long.compare(d2.createdAt, d1.createdAt));

        return entities;
    }

    /**
     * Asynchronously scans public PDFs and delivers DocumentEntities to the main thread callback.
     */
    public static void scanPublicDocumentsAsync(@NonNull Context context,
                                                @Nullable String searchQuery,
                                                @NonNull DocumentCallback callback) {
        Context appContext = context.getApplicationContext();
        sExecutor.execute(() -> {
            try {
                List<DocumentEntity> documents = scanPublicDocuments(appContext, searchQuery);
                sMainHandler.post(() -> callback.onDocumentsLoaded(documents));
            } catch (Exception e) {
                Log.e(TAG, "Error during scanPublicDocumentsAsync", e);
                sMainHandler.post(() -> callback.onError(e));
            }
        });
    }

    /**
     * Asynchronously scans public PDFs from MediaStore and delivers PdfItems to the main thread callback.
     */
    public static void scanPublicPdfsAsync(@NonNull Context context,
                                           @Nullable String searchQuery,
                                           @NonNull PdfCallback callback) {
        Context appContext = context.getApplicationContext();
        sExecutor.execute(() -> {
            try {
                List<PdfItem> items = scanPublicPdfs(appContext, searchQuery);
                sMainHandler.post(() -> callback.onPdfsLoaded(items));
            } catch (Exception e) {
                Log.e(TAG, "Error during scanPublicPdfsAsync", e);
                sMainHandler.post(() -> callback.onError(e));
            }
        });
    }

    /**
     * Convenience delegation to PdfThumbnailLoader for thumbnail rendering.
     */
    @Nullable
    public static String getOrGenerateThumbnail(@NonNull Context context, @NonNull Uri uri, long docId) {
        return PdfThumbnailLoader.getOrGenerateThumbnail(context, uri, docId);
    }

    /**
     * Convenience delegation to PdfThumbnailLoader for async thumbnail batching.
     */
    public static void generateThumbnailsAsync(@NonNull Context context,
                                               @NonNull List<DocumentEntity> documents,
                                               @Nullable Runnable onComplete) {
        PdfThumbnailLoader.generateThumbnailsAsync(context, documents, onComplete);
    }
}
