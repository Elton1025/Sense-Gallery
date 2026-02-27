package com.example.sensegallery.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

/**
 * Data Access Object for {@link PhotoEntity}.
 */
@Dao
public interface PhotoDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insertPhoto(PhotoEntity photo);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    void insertPhotos(List<PhotoEntity> photos);

    @Query("SELECT * FROM photos ORDER BY dateTaken DESC")
    List<PhotoEntity> getAllPhotos();

    @Query("SELECT * FROM photos WHERE clipEmbedding IS NOT NULL")
    List<PhotoEntity> getPhotosWithEmbeddings();

    @Query("SELECT * FROM photos WHERE clipEmbedding IS NULL")
    List<PhotoEntity> getPhotosWithoutEmbeddings();

    @Query("UPDATE photos SET clipEmbedding = :embedding WHERE id = :photoId")
    void updateClipEmbedding(long photoId, byte[] embedding);

    @Query("SELECT * FROM photos WHERE mediaStoreUri = :uri LIMIT 1")
    PhotoEntity getPhotoByUri(String uri);

    @Query("SELECT * FROM photos WHERE isFaceProcessed = 0 ORDER BY dateTaken DESC")
    List<PhotoEntity> getUnprocessedPhotos();

    @Query("UPDATE photos SET isFaceProcessed = 1 WHERE id = :photoId")
    void markFaceProcessed(long photoId);

    @Query("SELECT * FROM photos WHERE isClipProcessed = 0 ORDER BY dateTaken DESC")
    List<PhotoEntity> getClipUnprocessedPhotos();

    @Query("UPDATE photos SET isClipProcessed = 1 WHERE id = :photoId")
    void markClipProcessed(long photoId);

    @Query("SELECT * FROM photos WHERE clipEmbedding IS NOT NULL")
    List<PhotoEntity> getPhotosWithClipEmbeddings();

    @Query("SELECT COUNT(*) FROM photos")
    int getPhotoCount();
}
