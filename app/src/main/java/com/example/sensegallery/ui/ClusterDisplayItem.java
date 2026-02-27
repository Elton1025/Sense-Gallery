package com.example.sensegallery.ui;

import androidx.annotation.Nullable;

/**
 * Display-ready data for a cluster card in the clusters list.
 * Contains the resolved cover photo URI (not just the ID) and face count.
 */
public class ClusterDisplayItem {

    public final long clusterId;
    public final String label;
    @Nullable
    public final String coverPhotoUri; // Resolved MediaStore content URI string
    public final int faceCount;
    public final float boxLeft;
    public final float boxTop;
    public final float boxRight;
    public final float boxBottom;

    public ClusterDisplayItem(long clusterId, String label,
            @Nullable String coverPhotoUri, int faceCount,
            float boxLeft, float boxTop, float boxRight, float boxBottom) {
        this.clusterId = clusterId;
        this.label = label;
        this.coverPhotoUri = coverPhotoUri;
        this.faceCount = faceCount;
        this.boxLeft = boxLeft;
        this.boxTop = boxTop;
        this.boxRight = boxRight;
        this.boxBottom = boxBottom;
    }
}
