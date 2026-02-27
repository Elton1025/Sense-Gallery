package com.example.sensegallery.data;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.TypeConverters;

import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.room.migration.Migration;

/**
 * Main Room database for Sense Gallery.
 * Stores photos, detected faces, and face clusters.
 */
@Database(entities = { PhotoEntity.class, FaceEntity.class, ClusterEntity.class }, version = 4, exportSchema = true)
@TypeConverters({ EmbeddingTypeConverter.class })
public abstract class AppDatabase extends RoomDatabase {

    private static final String DATABASE_NAME = "sense_gallery_db";

    private static volatile AppDatabase INSTANCE;

    public abstract PhotoDao photoDao();

    public abstract FaceDao faceDao();

    public abstract ClusterDao clusterDao();

    public static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE photos ADD COLUMN isFaceProcessed INTEGER NOT NULL DEFAULT 0");
        }
    };

    public static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE photos ADD COLUMN isClipProcessed INTEGER NOT NULL DEFAULT 0");
        }
    };

    /**
     * Returns the singleton database instance, creating it if necessary.
     * Uses fallbackToDestructiveMigration for schema changes (embedding dim
     * 128→512).
     */
    public static AppDatabase getInstance(@NonNull Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                            context.getApplicationContext(),
                            AppDatabase.class,
                            DATABASE_NAME)
                            .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
                            .fallbackToDestructiveMigration()
                            .build();
                }
            }
        }
        return INSTANCE;
    }
}
