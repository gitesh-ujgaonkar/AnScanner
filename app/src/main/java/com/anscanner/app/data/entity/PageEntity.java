package com.anscanner.app.data.entity;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Room entity representing a single page within a scanned document.
 *
 * <p>Each page stores the path to its processed image file and the
 * filter that was applied. Pages are linked to their parent document
 * via {@link #documentId} with cascade delete.</p>
 */
@Entity(
    tableName = "pages",
    foreignKeys = @ForeignKey(
        entity = DocumentEntity.class,
        parentColumns = "id",
        childColumns = "document_id",
        onDelete = ForeignKey.CASCADE
    ),
    indices = @Index(value = "document_id")
)
public class PageEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    /** Foreign key to the parent document */
    @ColumnInfo(name = "document_id")
    public long documentId;

    /** 1-based page number within the document */
    @ColumnInfo(name = "page_number")
    public int pageNumber;

    /** Absolute path to the processed page image (JPEG in internal storage) */
    @ColumnInfo(name = "image_path")
    public String imagePath;

    /**
     * Filter applied to this page.
     * Values: "ORIGINAL", "CLAHE" (Magic Color), "OTSU" (B&W Clean)
     */
    @ColumnInfo(name = "filter_applied")
    public String filterApplied;

    /** Epoch millis when this page was captured */
    @ColumnInfo(name = "created_at")
    public long createdAt;

    // ── Constructors ────────────────────────────────────────────────────

    public PageEntity() {
        // Required empty constructor for Room
    }

    /**
     * Full constructor for creating a new page record.
     */
    @androidx.room.Ignore
    public PageEntity(long documentId, int pageNumber, String imagePath,
                      String filterApplied, long createdAt) {
        this.documentId = documentId;
        this.pageNumber = pageNumber;
        this.imagePath = imagePath;
        this.filterApplied = filterApplied;
        this.createdAt = createdAt;
    }
}
