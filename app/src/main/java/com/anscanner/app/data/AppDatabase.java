package com.anscanner.app.data;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

import com.anscanner.app.data.dao.DocumentDao;
import com.anscanner.app.data.entity.DocumentEntity;
import com.anscanner.app.data.entity.PageEntity;

/**
 * Room database for AnScanner.
 *
 * <p>Singleton access via {@link #getInstance(Context)}.
 * Database file is stored in the app's private data directory
 * ({@code /data/data/com.anscanner.app/databases/anscanner_db}).</p>
 *
 * <p>Contains two tables:</p>
 * <ul>
 *   <li>{@link DocumentEntity} — scanned document metadata</li>
 *   <li>{@link PageEntity} — individual page images within documents</li>
 * </ul>
 */
@Database(
    entities = {
        DocumentEntity.class,
        PageEntity.class
    },
    version = 1,
    exportSchema = true
)
public abstract class AppDatabase extends RoomDatabase {

    private static final String DATABASE_NAME = "anscanner_db";

    private static volatile AppDatabase INSTANCE;

    /**
     * Returns the DAO for document and page operations.
     */
    public abstract DocumentDao documentDao();

    /**
     * Thread-safe singleton accessor for the Room database.
     *
     * @param context Application or Activity context (Application context is used internally).
     * @return The singleton {@link AppDatabase} instance.
     */
    public static AppDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                            context.getApplicationContext(),
                            AppDatabase.class,
                            DATABASE_NAME
                        )
                        // Allow destructive migration for v1.0 — no user data to preserve yet.
                        // Replace with addMigrations() for production schema upgrades.
                        .fallbackToDestructiveMigration()
                        .build();
                }
            }
        }
        return INSTANCE;
    }
}
