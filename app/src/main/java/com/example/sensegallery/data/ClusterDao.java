package com.example.sensegallery.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

/**
 * Data Access Object for {@link ClusterEntity}.
 */
@Dao
public interface ClusterDao {

    @Insert
    long insertCluster(ClusterEntity cluster);

    @Query("SELECT * FROM clusters ORDER BY label ASC")
    List<ClusterEntity> getAllClusters();

    @Query("UPDATE clusters SET label = :label WHERE id = :clusterId")
    void updateLabel(long clusterId, String label);

    @Query("UPDATE clusters SET coverPhotoId = :coverPhotoId WHERE id = :clusterId")
    void updateCoverPhoto(long clusterId, Long coverPhotoId);

    @Query("SELECT * FROM clusters WHERE id = :clusterId LIMIT 1")
    ClusterEntity getClusterById(long clusterId);

    @Query("DELETE FROM clusters WHERE id = :clusterId")
    void deleteCluster(long clusterId);

    @Query("SELECT COUNT(*) FROM clusters")
    int getClusterCount();

    @Query("SELECT p.mediaStoreUri FROM photos p WHERE p.id = :photoId LIMIT 1")
    String getPhotoUri(long photoId);

    @Query("SELECT COUNT(*) FROM faces WHERE clusterId = :clusterId")
    int getFaceCountInCluster(long clusterId);
}
