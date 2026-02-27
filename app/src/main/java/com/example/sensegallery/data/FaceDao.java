package com.example.sensegallery.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

/**
 * Data Access Object for {@link FaceEntity}.
 */
@Dao
public interface FaceDao {

    @Insert
    long insertFace(FaceEntity face);

    @Insert
    void insertFaces(List<FaceEntity> faces);

    @Query("SELECT * FROM faces WHERE photoId = :photoId")
    List<FaceEntity> getFacesForPhoto(long photoId);

    @Query("SELECT * FROM faces WHERE clusterId IS NULL AND faceEmbedding IS NOT NULL")
    List<FaceEntity> getUnclusteredFaces();

    @Query("SELECT * FROM faces WHERE clusterId = :clusterId")
    List<FaceEntity> getFacesInCluster(long clusterId);

    @androidx.room.Update
    void updateFace(FaceEntity face);

    @Query("SELECT COUNT(*) FROM faces")
    int getFaceCount();

    @Query("SELECT p.* FROM photos p INNER JOIN faces f ON p.id = f.photoId WHERE f.clusterId = :clusterId")
    List<PhotoEntity> getPhotosForCluster(long clusterId);

    @Query("SELECT * FROM faces WHERE photoId = :photoId AND clusterId = :clusterId LIMIT 1")
    FaceEntity getFaceForCover(long photoId, long clusterId);
}
