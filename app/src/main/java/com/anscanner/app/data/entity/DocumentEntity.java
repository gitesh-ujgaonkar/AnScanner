package com.anscanner.app.data.entity;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * Room entity representing a scanned document.
 * Each document may contain one or more pages (see {@link PageEntity}).
 *
 * <p>Documents are created when the user completes the Save flow.
 * The file is stored in public Scoped Storage (Documents/AnScanner/)
 * and the content URI is recorded in {@link #fileUri}.</p>
 */
@Entity(tableName = "documents")
public class DocumentEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    /** User-facing filename, e.g. "Doc_2026_09_12" */
    @ColumnInfo(name = "title")
    public String title;

    /** Export format: "PDF" or "JPG" */
    @ColumnInfo(name = "format")
    public String format;

    /** Export quality: "HIGH" or "NORMAL" */
    @ColumnInfo(name = "quality")
    public String quality;

    /** Number of pages in this document */
    @ColumnInfo(name = "page_count")
    public int pageCount;

    /** File size in bytes (for UI badge display) */
    @ColumnInfo(name = "file_size_bytes")
    public long fileSizeBytes;

    /** Content URI string from MediaStore after saving to public storage */
    @ColumnInfo(name = "file_uri")
    public String fileUri;

    /** Absolute path to the thumbnail JPEG in app-internal storage */
    @ColumnInfo(name = "thumbnail_path")
    public String thumbnailPath;

    /** Epoch millis when this document was first created */
    @ColumnInfo(name = "created_at")
    public long createdAt;

    /** Epoch millis when this document was last modified */
    @ColumnInfo(name = "updated_at")
    public long updatedAt;

    // ── Convenience Constructor ─────────────────────────────────────────

    public DocumentEntity() {
        // Required empty constructor for Room
    }

    /**
     * Full constructor for creating a new document record.
     */
    public DocumentEntity(String title, String format, String quality,
                          int pageCount, long fileSizeBytes, String fileUri,
                          String thumbnailPath, long createdAt) {
        this.title = title;
        this.format = format;
        this.quality = quality;
        this.pageCount = pageCount;
        this.fileSizeBytes = fileSizeBytes;
        this.fileUri = fileUri;
        this.thumbnailPath = thumbnailPath;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }
}
