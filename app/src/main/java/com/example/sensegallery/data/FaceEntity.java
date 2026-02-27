package com.example.sensegallery.data;

import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Stores a detected face's bounding box, its 128-dimension MobileFaceNet
 * embedding, and links it to the source photo and optional cluster.
 */
@Entity(tableName = "faces", foreignKeys = {
        @ForeignKey(entity = PhotoEntity.class, parentColumns = "id", childColumns = "photoId", onDelete = ForeignKey.CASCADE),
        @ForeignKey(entity = ClusterEntity.class, parentColumns = "id", childColumns = "clusterId", onDelete = ForeignKey.SET_NULL)
}, indices = {
        @Index("photoId"),
        @Index("clusterId")
})
public class FaceEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "photoId")
    public long photoId;

    /** Bounding box coordinates (normalized 0–1 relative to image dimensions). */
    @ColumnInfo(name = "boxLeft")
    public float boxLeft;

    @ColumnInfo(name = "boxTop")
    public float boxTop;

    @ColumnInfo(name = "boxRight")
    public float boxRight;

    @ColumnInfo(name = "boxBottom")
    public float boxBottom;

    /**
     * 128-dimension MobileFaceNet vector embedding stored as a byte[] BLOB.
     */
    @Nullable
    @ColumnInfo(name = "faceEmbedding", typeAffinity = ColumnInfo.BLOB)
    public byte[] faceEmbedding;

    /**
     * ID of the cluster this face belongs to. Null if unclustered.
     */
    @Nullable
    @ColumnInfo(name = "clusterId")
    public Long clusterId;

    public FaceEntity(long photoId, float boxLeft, float boxTop, float boxRight, float boxBottom,
            @Nullable byte[] faceEmbedding, @Nullable Long clusterId) {
        this.photoId = photoId;
        this.boxLeft = boxLeft;
        this.boxTop = boxTop;
        this.boxRight = boxRight;
        this.boxBottom = boxBottom;
        this.faceEmbedding = faceEmbedding;
        this.clusterId = clusterId;
    }
}
