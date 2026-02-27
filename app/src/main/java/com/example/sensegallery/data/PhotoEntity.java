package com.example.sensegallery.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Stores information about a photo from the device's MediaStore,
 * along with its optional 512-dimension CLIP semantic embedding.
 */
@Entity(tableName = "photos", indices = { @Index(value = "mediaStoreUri", unique = true) })
public class PhotoEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @NonNull
    @ColumnInfo(name = "mediaStoreUri")
    public String mediaStoreUri;

    @ColumnInfo(name = "dateTaken")
    public long dateTaken;

    @ColumnInfo(name = "isFaceProcessed", defaultValue = "0")
    public boolean isFaceProcessed;

    @ColumnInfo(name = "isClipProcessed", defaultValue = "0")
    public boolean isClipProcessed;

    /**
     * 512-dimension CLIP vector embedding stored as a byte[] BLOB.
     * Null until the CLIP model processes this photo.
     */
    @Nullable
    @ColumnInfo(name = "clipEmbedding", typeAffinity = ColumnInfo.BLOB)
    public byte[] clipEmbedding;

    public PhotoEntity(@NonNull String mediaStoreUri, long dateTaken, @Nullable byte[] clipEmbedding) {
        this.mediaStoreUri = mediaStoreUri;
        this.dateTaken = dateTaken;
        this.clipEmbedding = clipEmbedding;
        this.isFaceProcessed = false;
    }
}
