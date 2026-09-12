package com.anscanner.app.data.dao;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;
import androidx.room.Update;

import com.anscanner.app.data.entity.DocumentEntity;
import com.anscanner.app.data.entity.PageEntity;

import java.util.List;

/**
 * Data Access Object for document and page CRUD operations.
 *
 * <p>All queries run synchronously on background threads.
 * Callers must ensure they are NOT invoked on the main thread.</p>
 */
@Dao
public interface DocumentDao {

    // ══════════════════════════════════════════════════════════════════════
    // Document Operations
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Insert a new document and return its auto-generated ID.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insertDocument(DocumentEntity document);

    /**
     * Update an existing document record.
     */
    @Update
    void updateDocument(DocumentEntity document);

    /**
     * Delete a document. Associated pages are cascade-deleted via foreign key.
     */
    @Delete
    void deleteDocument(DocumentEntity document);

    /**
     * Delete a document by its ID. Pages are cascade-deleted.
     */
    @Query("DELETE FROM documents WHERE id = :documentId")
    void deleteDocumentById(long documentId);

    /**
     * Get all documents ordered by creation date (newest first).
     * Used by the Library screen.
     */
    @Query("SELECT * FROM documents ORDER BY created_at DESC")
    List<DocumentEntity> getAllDocumentsOrderByDate();

    /**
     * Get a single document by its ID.
     */
    @Query("SELECT * FROM documents WHERE id = :documentId LIMIT 1")
    DocumentEntity getDocumentById(long documentId);

    /**
     * Search documents by title (case-insensitive partial match).
     * Used by the Library SearchView.
     */
    @Query("SELECT * FROM documents WHERE title LIKE '%' || :query || '%' ORDER BY created_at DESC")
    List<DocumentEntity> searchByTitle(String query);

    /**
     * Get the most recent N documents for the camera screen's
     * "Recent Scans" horizontal strip.
     */
    @Query("SELECT * FROM documents ORDER BY created_at DESC LIMIT :limit")
    List<DocumentEntity> getRecentDocuments(int limit);

    /**
     * Get documents created today (since midnight).
     */
    @Query("SELECT * FROM documents WHERE created_at >= :todayStartMillis ORDER BY created_at DESC")
    List<DocumentEntity> getDocumentsCreatedToday(long todayStartMillis);

    /**
     * Get documents created in the previous 7 days (between weekAgo and todayStart).
     */
    @Query("SELECT * FROM documents WHERE created_at >= :weekAgoMillis AND created_at < :todayStartMillis ORDER BY created_at DESC")
    List<DocumentEntity> getDocumentsPreviousWeek(long weekAgoMillis, long todayStartMillis);

    /**
     * Get documents older than 7 days.
     */
    @Query("SELECT * FROM documents WHERE created_at < :weekAgoMillis ORDER BY created_at DESC")
    List<DocumentEntity> getDocumentsOlder(long weekAgoMillis);

    /**
     * Get total count of all documents.
     */
    @Query("SELECT COUNT(*) FROM documents")
    int getDocumentCount();

    // ══════════════════════════════════════════════════════════════════════
    // Page Operations
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Insert a single page.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insertPage(PageEntity page);

    /**
     * Insert multiple pages in a batch (used when saving a multi-page scan).
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    List<Long> insertPages(List<PageEntity> pages);

    /**
     * Delete a specific page.
     */
    @Delete
    void deletePage(PageEntity page);

    /**
     * Get all pages for a given document, ordered by page number.
     */
    @Query("SELECT * FROM pages WHERE document_id = :documentId ORDER BY page_number ASC")
    List<PageEntity> getPagesForDocument(long documentId);

    /**
     * Get a single page by document ID and page number.
     */
    @Query("SELECT * FROM pages WHERE document_id = :documentId AND page_number = :pageNumber LIMIT 1")
    PageEntity getPage(long documentId, int pageNumber);

    /**
     * Get page count for a specific document.
     */
    @Query("SELECT COUNT(*) FROM pages WHERE document_id = :documentId")
    int getPageCount(long documentId);

    // ══════════════════════════════════════════════════════════════════════
    // Transactional Operations
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Insert a document and all its pages in a single transaction.
     * Returns the document ID.
     */
    @Transaction
    default long insertDocumentWithPages(DocumentEntity document, List<PageEntity> pages) {
        long docId = insertDocument(document);
        for (PageEntity page : pages) {
            page.documentId = docId;
        }
        insertPages(pages);
        return docId;
    }
}
