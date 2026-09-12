package com.anscanner.app.data.dao;

import android.database.Cursor;
import androidx.annotation.NonNull;
import androidx.room.EntityDeletionOrUpdateAdapter;
import androidx.room.EntityInsertionAdapter;
import androidx.room.RoomDatabase;
import androidx.room.RoomSQLiteQuery;
import androidx.room.SharedSQLiteStatement;
import androidx.room.util.CursorUtil;
import androidx.room.util.DBUtil;
import androidx.sqlite.db.SupportSQLiteStatement;
import com.anscanner.app.data.entity.DocumentEntity;
import com.anscanner.app.data.entity.PageEntity;
import java.lang.Class;
import java.lang.Long;
import java.lang.Override;
import java.lang.String;
import java.lang.SuppressWarnings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.processing.Generated;

@Generated("androidx.room.RoomProcessor")
@SuppressWarnings({"unchecked", "deprecation"})
public final class DocumentDao_Impl implements DocumentDao {
  private final RoomDatabase __db;

  private final EntityInsertionAdapter<DocumentEntity> __insertionAdapterOfDocumentEntity;

  private final EntityInsertionAdapter<PageEntity> __insertionAdapterOfPageEntity;

  private final EntityDeletionOrUpdateAdapter<DocumentEntity> __deletionAdapterOfDocumentEntity;

  private final EntityDeletionOrUpdateAdapter<PageEntity> __deletionAdapterOfPageEntity;

  private final EntityDeletionOrUpdateAdapter<DocumentEntity> __updateAdapterOfDocumentEntity;

  private final SharedSQLiteStatement __preparedStmtOfDeleteDocumentById;

  public DocumentDao_Impl(@NonNull final RoomDatabase __db) {
    this.__db = __db;
    this.__insertionAdapterOfDocumentEntity = new EntityInsertionAdapter<DocumentEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT OR REPLACE INTO `documents` (`id`,`title`,`format`,`quality`,`page_count`,`file_size_bytes`,`file_uri`,`thumbnail_path`,`created_at`,`updated_at`) VALUES (nullif(?, 0),?,?,?,?,?,?,?,?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          final DocumentEntity entity) {
        statement.bindLong(1, entity.id);
        if (entity.title == null) {
          statement.bindNull(2);
        } else {
          statement.bindString(2, entity.title);
        }
        if (entity.format == null) {
          statement.bindNull(3);
        } else {
          statement.bindString(3, entity.format);
        }
        if (entity.quality == null) {
          statement.bindNull(4);
        } else {
          statement.bindString(4, entity.quality);
        }
        statement.bindLong(5, entity.pageCount);
        statement.bindLong(6, entity.fileSizeBytes);
        if (entity.fileUri == null) {
          statement.bindNull(7);
        } else {
          statement.bindString(7, entity.fileUri);
        }
        if (entity.thumbnailPath == null) {
          statement.bindNull(8);
        } else {
          statement.bindString(8, entity.thumbnailPath);
        }
        statement.bindLong(9, entity.createdAt);
        statement.bindLong(10, entity.updatedAt);
      }
    };
    this.__insertionAdapterOfPageEntity = new EntityInsertionAdapter<PageEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT OR REPLACE INTO `pages` (`id`,`document_id`,`page_number`,`image_path`,`filter_applied`,`created_at`) VALUES (nullif(?, 0),?,?,?,?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          final PageEntity entity) {
        statement.bindLong(1, entity.id);
        statement.bindLong(2, entity.documentId);
        statement.bindLong(3, entity.pageNumber);
        if (entity.imagePath == null) {
          statement.bindNull(4);
        } else {
          statement.bindString(4, entity.imagePath);
        }
        if (entity.filterApplied == null) {
          statement.bindNull(5);
        } else {
          statement.bindString(5, entity.filterApplied);
        }
        statement.bindLong(6, entity.createdAt);
      }
    };
    this.__deletionAdapterOfDocumentEntity = new EntityDeletionOrUpdateAdapter<DocumentEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "DELETE FROM `documents` WHERE `id` = ?";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          final DocumentEntity entity) {
        statement.bindLong(1, entity.id);
      }
    };
    this.__deletionAdapterOfPageEntity = new EntityDeletionOrUpdateAdapter<PageEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "DELETE FROM `pages` WHERE `id` = ?";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          final PageEntity entity) {
        statement.bindLong(1, entity.id);
      }
    };
    this.__updateAdapterOfDocumentEntity = new EntityDeletionOrUpdateAdapter<DocumentEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "UPDATE OR ABORT `documents` SET `id` = ?,`title` = ?,`format` = ?,`quality` = ?,`page_count` = ?,`file_size_bytes` = ?,`file_uri` = ?,`thumbnail_path` = ?,`created_at` = ?,`updated_at` = ? WHERE `id` = ?";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          final DocumentEntity entity) {
        statement.bindLong(1, entity.id);
        if (entity.title == null) {
          statement.bindNull(2);
        } else {
          statement.bindString(2, entity.title);
        }
        if (entity.format == null) {
          statement.bindNull(3);
        } else {
          statement.bindString(3, entity.format);
        }
        if (entity.quality == null) {
          statement.bindNull(4);
        } else {
          statement.bindString(4, entity.quality);
        }
        statement.bindLong(5, entity.pageCount);
        statement.bindLong(6, entity.fileSizeBytes);
        if (entity.fileUri == null) {
          statement.bindNull(7);
        } else {
          statement.bindString(7, entity.fileUri);
        }
        if (entity.thumbnailPath == null) {
          statement.bindNull(8);
        } else {
          statement.bindString(8, entity.thumbnailPath);
        }
        statement.bindLong(9, entity.createdAt);
        statement.bindLong(10, entity.updatedAt);
        statement.bindLong(11, entity.id);
      }
    };
    this.__preparedStmtOfDeleteDocumentById = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "DELETE FROM documents WHERE id = ?";
        return _query;
      }
    };
  }

  @Override
  public long insertDocument(final DocumentEntity document) {
    __db.assertNotSuspendingTransaction();
    __db.beginTransaction();
    try {
      final long _result = __insertionAdapterOfDocumentEntity.insertAndReturnId(document);
      __db.setTransactionSuccessful();
      return _result;
    } finally {
      __db.endTransaction();
    }
  }

  @Override
  public long insertPage(final PageEntity page) {
    __db.assertNotSuspendingTransaction();
    __db.beginTransaction();
    try {
      final long _result = __insertionAdapterOfPageEntity.insertAndReturnId(page);
      __db.setTransactionSuccessful();
      return _result;
    } finally {
      __db.endTransaction();
    }
  }

  @Override
  public List<Long> insertPages(final List<PageEntity> pages) {
    __db.assertNotSuspendingTransaction();
    __db.beginTransaction();
    try {
      final List<Long> _result = __insertionAdapterOfPageEntity.insertAndReturnIdsList(pages);
      __db.setTransactionSuccessful();
      return _result;
    } finally {
      __db.endTransaction();
    }
  }

  @Override
  public void deleteDocument(final DocumentEntity document) {
    __db.assertNotSuspendingTransaction();
    __db.beginTransaction();
    try {
      __deletionAdapterOfDocumentEntity.handle(document);
      __db.setTransactionSuccessful();
    } finally {
      __db.endTransaction();
    }
  }

  @Override
  public void deletePage(final PageEntity page) {
    __db.assertNotSuspendingTransaction();
    __db.beginTransaction();
    try {
      __deletionAdapterOfPageEntity.handle(page);
      __db.setTransactionSuccessful();
    } finally {
      __db.endTransaction();
    }
  }

  @Override
  public void updateDocument(final DocumentEntity document) {
    __db.assertNotSuspendingTransaction();
    __db.beginTransaction();
    try {
      __updateAdapterOfDocumentEntity.handle(document);
      __db.setTransactionSuccessful();
    } finally {
      __db.endTransaction();
    }
  }

  @Override
  public long insertDocumentWithPages(final DocumentEntity document, final List<PageEntity> pages) {
    __db.beginTransaction();
    try {
      final long _result;
      _result = DocumentDao.super.insertDocumentWithPages(document, pages);
      __db.setTransactionSuccessful();
      return _result;
    } finally {
      __db.endTransaction();
    }
  }

  @Override
  public void deleteDocumentById(final long documentId) {
    __db.assertNotSuspendingTransaction();
    final SupportSQLiteStatement _stmt = __preparedStmtOfDeleteDocumentById.acquire();
    int _argIndex = 1;
    _stmt.bindLong(_argIndex, documentId);
    try {
      __db.beginTransaction();
      try {
        _stmt.executeUpdateDelete();
        __db.setTransactionSuccessful();
      } finally {
        __db.endTransaction();
      }
    } finally {
      __preparedStmtOfDeleteDocumentById.release(_stmt);
    }
  }

  @Override
  public List<DocumentEntity> getAllDocumentsOrderByDate() {
    final String _sql = "SELECT * FROM documents ORDER BY created_at DESC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    __db.assertNotSuspendingTransaction();
    final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
    try {
      final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
      final int _cursorIndexOfTitle = CursorUtil.getColumnIndexOrThrow(_cursor, "title");
      final int _cursorIndexOfFormat = CursorUtil.getColumnIndexOrThrow(_cursor, "format");
      final int _cursorIndexOfQuality = CursorUtil.getColumnIndexOrThrow(_cursor, "quality");
      final int _cursorIndexOfPageCount = CursorUtil.getColumnIndexOrThrow(_cursor, "page_count");
      final int _cursorIndexOfFileSizeBytes = CursorUtil.getColumnIndexOrThrow(_cursor, "file_size_bytes");
      final int _cursorIndexOfFileUri = CursorUtil.getColumnIndexOrThrow(_cursor, "file_uri");
      final int _cursorIndexOfThumbnailPath = CursorUtil.getColumnIndexOrThrow(_cursor, "thumbnail_path");
      final int _cursorIndexOfCreatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "created_at");
      final int _cursorIndexOfUpdatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "updated_at");
      final List<DocumentEntity> _result = new ArrayList<DocumentEntity>(_cursor.getCount());
      while (_cursor.moveToNext()) {
        final DocumentEntity _item;
        _item = new DocumentEntity();
        _item.id = _cursor.getLong(_cursorIndexOfId);
        if (_cursor.isNull(_cursorIndexOfTitle)) {
          _item.title = null;
        } else {
          _item.title = _cursor.getString(_cursorIndexOfTitle);
        }
        if (_cursor.isNull(_cursorIndexOfFormat)) {
          _item.format = null;
        } else {
          _item.format = _cursor.getString(_cursorIndexOfFormat);
        }
        if (_cursor.isNull(_cursorIndexOfQuality)) {
          _item.quality = null;
        } else {
          _item.quality = _cursor.getString(_cursorIndexOfQuality);
        }
        _item.pageCount = _cursor.getInt(_cursorIndexOfPageCount);
        _item.fileSizeBytes = _cursor.getLong(_cursorIndexOfFileSizeBytes);
        if (_cursor.isNull(_cursorIndexOfFileUri)) {
          _item.fileUri = null;
        } else {
          _item.fileUri = _cursor.getString(_cursorIndexOfFileUri);
        }
        if (_cursor.isNull(_cursorIndexOfThumbnailPath)) {
          _item.thumbnailPath = null;
        } else {
          _item.thumbnailPath = _cursor.getString(_cursorIndexOfThumbnailPath);
        }
        _item.createdAt = _cursor.getLong(_cursorIndexOfCreatedAt);
        _item.updatedAt = _cursor.getLong(_cursorIndexOfUpdatedAt);
        _result.add(_item);
      }
      return _result;
    } finally {
      _cursor.close();
      _statement.release();
    }
  }

  @Override
  public DocumentEntity getDocumentById(final long documentId) {
    final String _sql = "SELECT * FROM documents WHERE id = ? LIMIT 1";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindLong(_argIndex, documentId);
    __db.assertNotSuspendingTransaction();
    final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
    try {
      final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
      final int _cursorIndexOfTitle = CursorUtil.getColumnIndexOrThrow(_cursor, "title");
      final int _cursorIndexOfFormat = CursorUtil.getColumnIndexOrThrow(_cursor, "format");
      final int _cursorIndexOfQuality = CursorUtil.getColumnIndexOrThrow(_cursor, "quality");
      final int _cursorIndexOfPageCount = CursorUtil.getColumnIndexOrThrow(_cursor, "page_count");
      final int _cursorIndexOfFileSizeBytes = CursorUtil.getColumnIndexOrThrow(_cursor, "file_size_bytes");
      final int _cursorIndexOfFileUri = CursorUtil.getColumnIndexOrThrow(_cursor, "file_uri");
      final int _cursorIndexOfThumbnailPath = CursorUtil.getColumnIndexOrThrow(_cursor, "thumbnail_path");
      final int _cursorIndexOfCreatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "created_at");
      final int _cursorIndexOfUpdatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "updated_at");
      final DocumentEntity _result;
      if (_cursor.moveToFirst()) {
        _result = new DocumentEntity();
        _result.id = _cursor.getLong(_cursorIndexOfId);
        if (_cursor.isNull(_cursorIndexOfTitle)) {
          _result.title = null;
        } else {
          _result.title = _cursor.getString(_cursorIndexOfTitle);
        }
        if (_cursor.isNull(_cursorIndexOfFormat)) {
          _result.format = null;
        } else {
          _result.format = _cursor.getString(_cursorIndexOfFormat);
        }
        if (_cursor.isNull(_cursorIndexOfQuality)) {
          _result.quality = null;
        } else {
          _result.quality = _cursor.getString(_cursorIndexOfQuality);
        }
        _result.pageCount = _cursor.getInt(_cursorIndexOfPageCount);
        _result.fileSizeBytes = _cursor.getLong(_cursorIndexOfFileSizeBytes);
        if (_cursor.isNull(_cursorIndexOfFileUri)) {
          _result.fileUri = null;
        } else {
          _result.fileUri = _cursor.getString(_cursorIndexOfFileUri);
        }
        if (_cursor.isNull(_cursorIndexOfThumbnailPath)) {
          _result.thumbnailPath = null;
        } else {
          _result.thumbnailPath = _cursor.getString(_cursorIndexOfThumbnailPath);
        }
        _result.createdAt = _cursor.getLong(_cursorIndexOfCreatedAt);
        _result.updatedAt = _cursor.getLong(_cursorIndexOfUpdatedAt);
      } else {
        _result = null;
      }
      return _result;
    } finally {
      _cursor.close();
      _statement.release();
    }
  }

  @Override
  public List<DocumentEntity> searchByTitle(final String query) {
    final String _sql = "SELECT * FROM documents WHERE title LIKE '%' || ? || '%' ORDER BY created_at DESC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    if (query == null) {
      _statement.bindNull(_argIndex);
    } else {
      _statement.bindString(_argIndex, query);
    }
    __db.assertNotSuspendingTransaction();
    final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
    try {
      final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
      final int _cursorIndexOfTitle = CursorUtil.getColumnIndexOrThrow(_cursor, "title");
      final int _cursorIndexOfFormat = CursorUtil.getColumnIndexOrThrow(_cursor, "format");
      final int _cursorIndexOfQuality = CursorUtil.getColumnIndexOrThrow(_cursor, "quality");
      final int _cursorIndexOfPageCount = CursorUtil.getColumnIndexOrThrow(_cursor, "page_count");
      final int _cursorIndexOfFileSizeBytes = CursorUtil.getColumnIndexOrThrow(_cursor, "file_size_bytes");
      final int _cursorIndexOfFileUri = CursorUtil.getColumnIndexOrThrow(_cursor, "file_uri");
      final int _cursorIndexOfThumbnailPath = CursorUtil.getColumnIndexOrThrow(_cursor, "thumbnail_path");
      final int _cursorIndexOfCreatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "created_at");
      final int _cursorIndexOfUpdatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "updated_at");
      final List<DocumentEntity> _result = new ArrayList<DocumentEntity>(_cursor.getCount());
      while (_cursor.moveToNext()) {
        final DocumentEntity _item;
        _item = new DocumentEntity();
        _item.id = _cursor.getLong(_cursorIndexOfId);
        if (_cursor.isNull(_cursorIndexOfTitle)) {
          _item.title = null;
        } else {
          _item.title = _cursor.getString(_cursorIndexOfTitle);
        }
        if (_cursor.isNull(_cursorIndexOfFormat)) {
          _item.format = null;
        } else {
          _item.format = _cursor.getString(_cursorIndexOfFormat);
        }
        if (_cursor.isNull(_cursorIndexOfQuality)) {
          _item.quality = null;
        } else {
          _item.quality = _cursor.getString(_cursorIndexOfQuality);
        }
        _item.pageCount = _cursor.getInt(_cursorIndexOfPageCount);
        _item.fileSizeBytes = _cursor.getLong(_cursorIndexOfFileSizeBytes);
        if (_cursor.isNull(_cursorIndexOfFileUri)) {
          _item.fileUri = null;
        } else {
          _item.fileUri = _cursor.getString(_cursorIndexOfFileUri);
        }
        if (_cursor.isNull(_cursorIndexOfThumbnailPath)) {
          _item.thumbnailPath = null;
        } else {
          _item.thumbnailPath = _cursor.getString(_cursorIndexOfThumbnailPath);
        }
        _item.createdAt = _cursor.getLong(_cursorIndexOfCreatedAt);
        _item.updatedAt = _cursor.getLong(_cursorIndexOfUpdatedAt);
        _result.add(_item);
      }
      return _result;
    } finally {
      _cursor.close();
      _statement.release();
    }
  }

  @Override
  public List<DocumentEntity> getRecentDocuments(final int limit) {
    final String _sql = "SELECT * FROM documents ORDER BY created_at DESC LIMIT ?";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindLong(_argIndex, limit);
    __db.assertNotSuspendingTransaction();
    final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
    try {
      final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
      final int _cursorIndexOfTitle = CursorUtil.getColumnIndexOrThrow(_cursor, "title");
      final int _cursorIndexOfFormat = CursorUtil.getColumnIndexOrThrow(_cursor, "format");
      final int _cursorIndexOfQuality = CursorUtil.getColumnIndexOrThrow(_cursor, "quality");
      final int _cursorIndexOfPageCount = CursorUtil.getColumnIndexOrThrow(_cursor, "page_count");
      final int _cursorIndexOfFileSizeBytes = CursorUtil.getColumnIndexOrThrow(_cursor, "file_size_bytes");
      final int _cursorIndexOfFileUri = CursorUtil.getColumnIndexOrThrow(_cursor, "file_uri");
      final int _cursorIndexOfThumbnailPath = CursorUtil.getColumnIndexOrThrow(_cursor, "thumbnail_path");
      final int _cursorIndexOfCreatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "created_at");
      final int _cursorIndexOfUpdatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "updated_at");
      final List<DocumentEntity> _result = new ArrayList<DocumentEntity>(_cursor.getCount());
      while (_cursor.moveToNext()) {
        final DocumentEntity _item;
        _item = new DocumentEntity();
        _item.id = _cursor.getLong(_cursorIndexOfId);
        if (_cursor.isNull(_cursorIndexOfTitle)) {
          _item.title = null;
        } else {
          _item.title = _cursor.getString(_cursorIndexOfTitle);
        }
        if (_cursor.isNull(_cursorIndexOfFormat)) {
          _item.format = null;
        } else {
          _item.format = _cursor.getString(_cursorIndexOfFormat);
        }
        if (_cursor.isNull(_cursorIndexOfQuality)) {
          _item.quality = null;
        } else {
          _item.quality = _cursor.getString(_cursorIndexOfQuality);
        }
        _item.pageCount = _cursor.getInt(_cursorIndexOfPageCount);
        _item.fileSizeBytes = _cursor.getLong(_cursorIndexOfFileSizeBytes);
        if (_cursor.isNull(_cursorIndexOfFileUri)) {
          _item.fileUri = null;
        } else {
          _item.fileUri = _cursor.getString(_cursorIndexOfFileUri);
        }
        if (_cursor.isNull(_cursorIndexOfThumbnailPath)) {
          _item.thumbnailPath = null;
        } else {
          _item.thumbnailPath = _cursor.getString(_cursorIndexOfThumbnailPath);
        }
        _item.createdAt = _cursor.getLong(_cursorIndexOfCreatedAt);
        _item.updatedAt = _cursor.getLong(_cursorIndexOfUpdatedAt);
        _result.add(_item);
      }
      return _result;
    } finally {
      _cursor.close();
      _statement.release();
    }
  }

  @Override
  public List<DocumentEntity> getDocumentsCreatedToday(final long todayStartMillis) {
    final String _sql = "SELECT * FROM documents WHERE created_at >= ? ORDER BY created_at DESC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindLong(_argIndex, todayStartMillis);
    __db.assertNotSuspendingTransaction();
    final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
    try {
      final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
      final int _cursorIndexOfTitle = CursorUtil.getColumnIndexOrThrow(_cursor, "title");
      final int _cursorIndexOfFormat = CursorUtil.getColumnIndexOrThrow(_cursor, "format");
      final int _cursorIndexOfQuality = CursorUtil.getColumnIndexOrThrow(_cursor, "quality");
      final int _cursorIndexOfPageCount = CursorUtil.getColumnIndexOrThrow(_cursor, "page_count");
      final int _cursorIndexOfFileSizeBytes = CursorUtil.getColumnIndexOrThrow(_cursor, "file_size_bytes");
      final int _cursorIndexOfFileUri = CursorUtil.getColumnIndexOrThrow(_cursor, "file_uri");
      final int _cursorIndexOfThumbnailPath = CursorUtil.getColumnIndexOrThrow(_cursor, "thumbnail_path");
      final int _cursorIndexOfCreatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "created_at");
      final int _cursorIndexOfUpdatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "updated_at");
      final List<DocumentEntity> _result = new ArrayList<DocumentEntity>(_cursor.getCount());
      while (_cursor.moveToNext()) {
        final DocumentEntity _item;
        _item = new DocumentEntity();
        _item.id = _cursor.getLong(_cursorIndexOfId);
        if (_cursor.isNull(_cursorIndexOfTitle)) {
          _item.title = null;
        } else {
          _item.title = _cursor.getString(_cursorIndexOfTitle);
        }
        if (_cursor.isNull(_cursorIndexOfFormat)) {
          _item.format = null;
        } else {
          _item.format = _cursor.getString(_cursorIndexOfFormat);
        }
        if (_cursor.isNull(_cursorIndexOfQuality)) {
          _item.quality = null;
        } else {
          _item.quality = _cursor.getString(_cursorIndexOfQuality);
        }
        _item.pageCount = _cursor.getInt(_cursorIndexOfPageCount);
        _item.fileSizeBytes = _cursor.getLong(_cursorIndexOfFileSizeBytes);
        if (_cursor.isNull(_cursorIndexOfFileUri)) {
          _item.fileUri = null;
        } else {
          _item.fileUri = _cursor.getString(_cursorIndexOfFileUri);
        }
        if (_cursor.isNull(_cursorIndexOfThumbnailPath)) {
          _item.thumbnailPath = null;
        } else {
          _item.thumbnailPath = _cursor.getString(_cursorIndexOfThumbnailPath);
        }
        _item.createdAt = _cursor.getLong(_cursorIndexOfCreatedAt);
        _item.updatedAt = _cursor.getLong(_cursorIndexOfUpdatedAt);
        _result.add(_item);
      }
      return _result;
    } finally {
      _cursor.close();
      _statement.release();
    }
  }

  @Override
  public List<DocumentEntity> getDocumentsPreviousWeek(final long weekAgoMillis,
      final long todayStartMillis) {
    final String _sql = "SELECT * FROM documents WHERE created_at >= ? AND created_at < ? ORDER BY created_at DESC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 2);
    int _argIndex = 1;
    _statement.bindLong(_argIndex, weekAgoMillis);
    _argIndex = 2;
    _statement.bindLong(_argIndex, todayStartMillis);
    __db.assertNotSuspendingTransaction();
    final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
    try {
      final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
      final int _cursorIndexOfTitle = CursorUtil.getColumnIndexOrThrow(_cursor, "title");
      final int _cursorIndexOfFormat = CursorUtil.getColumnIndexOrThrow(_cursor, "format");
      final int _cursorIndexOfQuality = CursorUtil.getColumnIndexOrThrow(_cursor, "quality");
      final int _cursorIndexOfPageCount = CursorUtil.getColumnIndexOrThrow(_cursor, "page_count");
      final int _cursorIndexOfFileSizeBytes = CursorUtil.getColumnIndexOrThrow(_cursor, "file_size_bytes");
      final int _cursorIndexOfFileUri = CursorUtil.getColumnIndexOrThrow(_cursor, "file_uri");
      final int _cursorIndexOfThumbnailPath = CursorUtil.getColumnIndexOrThrow(_cursor, "thumbnail_path");
      final int _cursorIndexOfCreatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "created_at");
      final int _cursorIndexOfUpdatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "updated_at");
      final List<DocumentEntity> _result = new ArrayList<DocumentEntity>(_cursor.getCount());
      while (_cursor.moveToNext()) {
        final DocumentEntity _item;
        _item = new DocumentEntity();
        _item.id = _cursor.getLong(_cursorIndexOfId);
        if (_cursor.isNull(_cursorIndexOfTitle)) {
          _item.title = null;
        } else {
          _item.title = _cursor.getString(_cursorIndexOfTitle);
        }
        if (_cursor.isNull(_cursorIndexOfFormat)) {
          _item.format = null;
        } else {
          _item.format = _cursor.getString(_cursorIndexOfFormat);
        }
        if (_cursor.isNull(_cursorIndexOfQuality)) {
          _item.quality = null;
        } else {
          _item.quality = _cursor.getString(_cursorIndexOfQuality);
        }
        _item.pageCount = _cursor.getInt(_cursorIndexOfPageCount);
        _item.fileSizeBytes = _cursor.getLong(_cursorIndexOfFileSizeBytes);
        if (_cursor.isNull(_cursorIndexOfFileUri)) {
          _item.fileUri = null;
        } else {
          _item.fileUri = _cursor.getString(_cursorIndexOfFileUri);
        }
        if (_cursor.isNull(_cursorIndexOfThumbnailPath)) {
          _item.thumbnailPath = null;
        } else {
          _item.thumbnailPath = _cursor.getString(_cursorIndexOfThumbnailPath);
        }
        _item.createdAt = _cursor.getLong(_cursorIndexOfCreatedAt);
        _item.updatedAt = _cursor.getLong(_cursorIndexOfUpdatedAt);
        _result.add(_item);
      }
      return _result;
    } finally {
      _cursor.close();
      _statement.release();
    }
  }

  @Override
  public List<DocumentEntity> getDocumentsOlder(final long weekAgoMillis) {
    final String _sql = "SELECT * FROM documents WHERE created_at < ? ORDER BY created_at DESC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindLong(_argIndex, weekAgoMillis);
    __db.assertNotSuspendingTransaction();
    final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
    try {
      final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
      final int _cursorIndexOfTitle = CursorUtil.getColumnIndexOrThrow(_cursor, "title");
      final int _cursorIndexOfFormat = CursorUtil.getColumnIndexOrThrow(_cursor, "format");
      final int _cursorIndexOfQuality = CursorUtil.getColumnIndexOrThrow(_cursor, "quality");
      final int _cursorIndexOfPageCount = CursorUtil.getColumnIndexOrThrow(_cursor, "page_count");
      final int _cursorIndexOfFileSizeBytes = CursorUtil.getColumnIndexOrThrow(_cursor, "file_size_bytes");
      final int _cursorIndexOfFileUri = CursorUtil.getColumnIndexOrThrow(_cursor, "file_uri");
      final int _cursorIndexOfThumbnailPath = CursorUtil.getColumnIndexOrThrow(_cursor, "thumbnail_path");
      final int _cursorIndexOfCreatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "created_at");
      final int _cursorIndexOfUpdatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "updated_at");
      final List<DocumentEntity> _result = new ArrayList<DocumentEntity>(_cursor.getCount());
      while (_cursor.moveToNext()) {
        final DocumentEntity _item;
        _item = new DocumentEntity();
        _item.id = _cursor.getLong(_cursorIndexOfId);
        if (_cursor.isNull(_cursorIndexOfTitle)) {
          _item.title = null;
        } else {
          _item.title = _cursor.getString(_cursorIndexOfTitle);
        }
        if (_cursor.isNull(_cursorIndexOfFormat)) {
          _item.format = null;
        } else {
          _item.format = _cursor.getString(_cursorIndexOfFormat);
        }
        if (_cursor.isNull(_cursorIndexOfQuality)) {
          _item.quality = null;
        } else {
          _item.quality = _cursor.getString(_cursorIndexOfQuality);
        }
        _item.pageCount = _cursor.getInt(_cursorIndexOfPageCount);
        _item.fileSizeBytes = _cursor.getLong(_cursorIndexOfFileSizeBytes);
        if (_cursor.isNull(_cursorIndexOfFileUri)) {
          _item.fileUri = null;
        } else {
          _item.fileUri = _cursor.getString(_cursorIndexOfFileUri);
        }
        if (_cursor.isNull(_cursorIndexOfThumbnailPath)) {
          _item.thumbnailPath = null;
        } else {
          _item.thumbnailPath = _cursor.getString(_cursorIndexOfThumbnailPath);
        }
        _item.createdAt = _cursor.getLong(_cursorIndexOfCreatedAt);
        _item.updatedAt = _cursor.getLong(_cursorIndexOfUpdatedAt);
        _result.add(_item);
      }
      return _result;
    } finally {
      _cursor.close();
      _statement.release();
    }
  }

  @Override
  public int getDocumentCount() {
    final String _sql = "SELECT COUNT(*) FROM documents";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    __db.assertNotSuspendingTransaction();
    final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
    try {
      final int _result;
      if (_cursor.moveToFirst()) {
        _result = _cursor.getInt(0);
      } else {
        _result = 0;
      }
      return _result;
    } finally {
      _cursor.close();
      _statement.release();
    }
  }

  @Override
  public List<PageEntity> getPagesForDocument(final long documentId) {
    final String _sql = "SELECT * FROM pages WHERE document_id = ? ORDER BY page_number ASC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindLong(_argIndex, documentId);
    __db.assertNotSuspendingTransaction();
    final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
    try {
      final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
      final int _cursorIndexOfDocumentId = CursorUtil.getColumnIndexOrThrow(_cursor, "document_id");
      final int _cursorIndexOfPageNumber = CursorUtil.getColumnIndexOrThrow(_cursor, "page_number");
      final int _cursorIndexOfImagePath = CursorUtil.getColumnIndexOrThrow(_cursor, "image_path");
      final int _cursorIndexOfFilterApplied = CursorUtil.getColumnIndexOrThrow(_cursor, "filter_applied");
      final int _cursorIndexOfCreatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "created_at");
      final List<PageEntity> _result = new ArrayList<PageEntity>(_cursor.getCount());
      while (_cursor.moveToNext()) {
        final PageEntity _item;
        _item = new PageEntity();
        _item.id = _cursor.getLong(_cursorIndexOfId);
        _item.documentId = _cursor.getLong(_cursorIndexOfDocumentId);
        _item.pageNumber = _cursor.getInt(_cursorIndexOfPageNumber);
        if (_cursor.isNull(_cursorIndexOfImagePath)) {
          _item.imagePath = null;
        } else {
          _item.imagePath = _cursor.getString(_cursorIndexOfImagePath);
        }
        if (_cursor.isNull(_cursorIndexOfFilterApplied)) {
          _item.filterApplied = null;
        } else {
          _item.filterApplied = _cursor.getString(_cursorIndexOfFilterApplied);
        }
        _item.createdAt = _cursor.getLong(_cursorIndexOfCreatedAt);
        _result.add(_item);
      }
      return _result;
    } finally {
      _cursor.close();
      _statement.release();
    }
  }

  @Override
  public PageEntity getPage(final long documentId, final int pageNumber) {
    final String _sql = "SELECT * FROM pages WHERE document_id = ? AND page_number = ? LIMIT 1";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 2);
    int _argIndex = 1;
    _statement.bindLong(_argIndex, documentId);
    _argIndex = 2;
    _statement.bindLong(_argIndex, pageNumber);
    __db.assertNotSuspendingTransaction();
    final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
    try {
      final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
      final int _cursorIndexOfDocumentId = CursorUtil.getColumnIndexOrThrow(_cursor, "document_id");
      final int _cursorIndexOfPageNumber = CursorUtil.getColumnIndexOrThrow(_cursor, "page_number");
      final int _cursorIndexOfImagePath = CursorUtil.getColumnIndexOrThrow(_cursor, "image_path");
      final int _cursorIndexOfFilterApplied = CursorUtil.getColumnIndexOrThrow(_cursor, "filter_applied");
      final int _cursorIndexOfCreatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "created_at");
      final PageEntity _result;
      if (_cursor.moveToFirst()) {
        _result = new PageEntity();
        _result.id = _cursor.getLong(_cursorIndexOfId);
        _result.documentId = _cursor.getLong(_cursorIndexOfDocumentId);
        _result.pageNumber = _cursor.getInt(_cursorIndexOfPageNumber);
        if (_cursor.isNull(_cursorIndexOfImagePath)) {
          _result.imagePath = null;
        } else {
          _result.imagePath = _cursor.getString(_cursorIndexOfImagePath);
        }
        if (_cursor.isNull(_cursorIndexOfFilterApplied)) {
          _result.filterApplied = null;
        } else {
          _result.filterApplied = _cursor.getString(_cursorIndexOfFilterApplied);
        }
        _result.createdAt = _cursor.getLong(_cursorIndexOfCreatedAt);
      } else {
        _result = null;
      }
      return _result;
    } finally {
      _cursor.close();
      _statement.release();
    }
  }

  @Override
  public int getPageCount(final long documentId) {
    final String _sql = "SELECT COUNT(*) FROM pages WHERE document_id = ?";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindLong(_argIndex, documentId);
    __db.assertNotSuspendingTransaction();
    final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
    try {
      final int _result;
      if (_cursor.moveToFirst()) {
        _result = _cursor.getInt(0);
      } else {
        _result = 0;
      }
      return _result;
    } finally {
      _cursor.close();
      _statement.release();
    }
  }

  @NonNull
  public static List<Class<?>> getRequiredConverters() {
    return Collections.emptyList();
  }
}
