package com.example.sensegallery;

import android.content.Context;

import com.bumptech.glide.GlideBuilder;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory;
import com.bumptech.glide.module.AppGlideModule;

/**
 * Constrains Glide disk cache growth to keep total app storage bounded.
 */
@GlideModule
public class SenseGalleryGlideModule extends AppGlideModule {

    private static final int GLIDE_DISK_CACHE_BYTES = 64 * 1024 * 1024;

    @Override
    public void applyOptions(Context context, GlideBuilder builder) {
        builder.setDiskCache(new InternalCacheDiskCacheFactory(context, GLIDE_DISK_CACHE_BYTES));
    }

    @Override
    public boolean isManifestParsingEnabled() {
        return false;
    }
}
