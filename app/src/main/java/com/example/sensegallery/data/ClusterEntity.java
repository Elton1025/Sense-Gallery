package com.example.sensegallery.data;

import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * Represents a cluster of faces that belong to the same person.
 * Has a human-readable label and a cover photo for display.
 */
@Entity(tableName = "clusters")
public class ClusterEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "label")
    public String label;

    /**
     * ID of the photo used as the cover image for this cluster.
     * May be null if no cover is chosen yet.
     */
    @Nullable
    @ColumnInfo(name = "coverPhotoId")
    public Long coverPhotoId;

    public ClusterEntity(String label, @Nullable Long coverPhotoId) {
        this.label = label;
        this.coverPhotoId = coverPhotoId;
    }
}
