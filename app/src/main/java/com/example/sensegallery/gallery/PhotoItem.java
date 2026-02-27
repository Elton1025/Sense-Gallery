package com.example.sensegallery.gallery;

import android.net.Uri;

/**
 * Simple UI model representing a photo from the MediaStore.
 */
public class PhotoItem {

    private final Uri contentUri;
    private final long dateTaken;

    public PhotoItem(Uri contentUri, long dateTaken) {
        this.contentUri = contentUri;
        this.dateTaken = dateTaken;
    }

    public Uri getContentUri() {
        return contentUri;
    }

    public long getDateTaken() {
        return dateTaken;
    }
}
