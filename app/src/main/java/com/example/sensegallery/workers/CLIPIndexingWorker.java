package com.example.sensegallery.workers;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.bumptech.glide.Glide;
import com.example.sensegallery.ai.CLIPInferenceEngine;
import com.example.sensegallery.data.AppDatabase;
import com.example.sensegallery.data.EmbeddingTypeConverter;
import com.example.sensegallery.data.PhotoDao;
import com.example.sensegallery.data.PhotoEntity;

import java.util.List;

/**
 * Background worker that indexes all unprocessed photos with CLIP image
 * embeddings.
 * Uses a dedicated single-thread approach to avoid blocking face processing.
 * Retries photos that are still missing embeddings, even if an older run
 * incorrectly marked them as processed.
 */
public class CLIPIndexingWorker extends Worker {

    private static final String TAG = "CLIPIndexingWorker";

    public CLIPIndexingWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        Log.i(TAG, "========================================");
        Log.i(TAG, "[CLIP INDEXING] Starting semantic search indexing...");
        Log.i(TAG, "========================================");

        long pipelineStart = System.currentTimeMillis();
        CLIPInferenceEngine engine = null;
        try {
            Context context = getApplicationContext();
            AppDatabase db = AppDatabase.getInstance(context);
            PhotoDao photoDao = db.photoDao();

            // STEP 1: Sync MediaStore to Database
            // This ensures we have work to do, even if FaceProcessing has not run yet.
            Log.i(TAG, "[CLIP] Syncing specific photos from MediaStore before indexing...");
            syncPhotosToDb(context, photoDao);

            // Get unprocessed photos
            List<PhotoEntity> unprocessed = photoDao.getClipUnprocessedPhotos();
            int total = unprocessed.size();
            int totalInDb = photoDao.getPhotoCount();
            Log.i(TAG, "[CLIP] Total photos in DB: " + totalInDb);
            Log.i(TAG, "[CLIP] Already indexed: " + (totalInDb - total));
            Log.i(TAG, "[CLIP] Remaining to index: " + total);

            if (total == 0) {
                Log.i(TAG, "[CLIP] All photos already CLIP-indexed. Nothing to do.");
                Log.i(TAG, "========================================");
                return Result.success();
            }

            // Initialize CLIP engine
            Log.i(TAG, "[CLIP] Loading CLIP image encoder (ONNX Runtime)...");
            long engineStart = System.currentTimeMillis();
            engine = new CLIPInferenceEngine(context);
            long engineTime = System.currentTimeMillis() - engineStart;

            if (!engine.isImageEncoderReady()) {
                Log.e(TAG, "[CLIP] Image encoder FAILED to initialize, aborting");
                return Result.retry();
            }
            Log.i(TAG, "[CLIP] Image encoder ready in " + engineTime + "ms");

            int processed = 0;
            int embedded = 0;
            int failed = 0;

            for (PhotoEntity photo : unprocessed) {
                long photoStart = System.currentTimeMillis();
                Log.i(TAG, "----------------------------------------");
                Log.i(TAG, "[START] Photo #" + (processed + 1) + "/" + total
                        + " (id=" + photo.id + ")");
                Log.d(TAG, "  URI: " + photo.mediaStoreUri);

                try {
                    // Load bitmap at 224x224 via Glide
                    long loadStart = System.currentTimeMillis();
                    Bitmap bitmap = Glide.with(context)
                            .asBitmap()
                            .load(Uri.parse(photo.mediaStoreUri))
                            .submit(224, 224)
                            .get();
                    long loadTime = System.currentTimeMillis() - loadStart;

                    if (bitmap != null) {
                        Log.d(TAG, "  [Glide] Loaded " + bitmap.getWidth() + "x"
                                + bitmap.getHeight() + " in " + loadTime + "ms");

                        // Encode with CLIP
                        long encodeStart = System.currentTimeMillis();
                        float[] embedding = engine.encodeImage(bitmap);
                        long encodeTime = System.currentTimeMillis() - encodeStart;
                        bitmap.recycle();

                        if (embedding != null) {
                            Log.i(TAG, "  [CLIP] Embedding: " + embedding.length
                                    + "-d vector in " + encodeTime + "ms");

                            byte[] embeddingBytes = EmbeddingTypeConverter.fromFloatArray(embedding);
                            photoDao.updateClipEmbedding(photo.id, embeddingBytes);
                            photoDao.markClipProcessed(photo.id);
                            embedded++;
                            processed++;

                            Log.d(TAG, "  [DB] Saved " + embeddingBytes.length + " bytes to database");
                        } else {
                            Log.w(TAG, "  [CLIP] Encoder returned null embedding (" + encodeTime + "ms)");
                            failed++;
                            processed++;
                        }
                    } else {
                        Log.w(TAG, "  [Glide] Failed to load bitmap (null) in " + loadTime + "ms");
                        failed++;
                        processed++;
                    }

                    long photoTime = System.currentTimeMillis() - photoStart;
                    Log.i(TAG, "[END] Photo #" + processed + "/" + total
                            + " (" + photoTime + "ms total)");

                } catch (Exception e) {
                    Log.e(TAG, "  [ERROR] Photo " + photo.id + ": " + e.getMessage());
                    processed++;
                    failed++;
                }
            }

            long pipelineTime = System.currentTimeMillis() - pipelineStart;
            Log.i(TAG, "========================================");
            Log.i(TAG, "[CLIP INDEXING COMPLETE]");
            Log.i(TAG, "  Total photos processed: " + processed);
            Log.i(TAG, "  Embeddings saved:       " + embedded);
            Log.i(TAG, "  Failed/skipped:         " + failed);
            Log.i(TAG, "  Total time:             " + formatTime(pipelineTime));
            Log.i(TAG, "  Avg per photo:          " + (processed > 0 ? (pipelineTime / processed) + "ms" : "N/A"));
            Log.i(TAG, "========================================");
            return Result.success();

        } catch (Exception e) {
            Log.e(TAG, "[CLIP] Pipeline error: " + e.getMessage(), e);
            return Result.retry();
        } finally {
            if (engine != null) {
                engine.close();
            }
        }
    }



    private void syncPhotosToDb(Context context, PhotoDao photoDao) {
        try {
            android.content.ContentResolver resolver = context.getContentResolver();
            Uri collection = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            String[] projection = {
                    android.provider.MediaStore.Images.Media._ID,
                    android.provider.MediaStore.Images.Media.DATE_TAKEN
            };

            try (android.database.Cursor cursor = resolver.query(collection, projection, null, null, null)) {
                if (cursor != null) {
                    int idCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media._ID);
                    int dateCol = cursor.getColumnIndex(android.provider.MediaStore.Images.Media.DATE_TAKEN);

                    int count = 0;

                    while (cursor.moveToNext()) {
                        long id = cursor.getLong(idCol);
                        long dateTaken = (dateCol >= 0) ? cursor.getLong(dateCol) : System.currentTimeMillis();

                        Uri contentUri = android.content.ContentUris.withAppendedId(
                                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                id);
                        
                        // Use INSERT OR IGNORE via the DAO method annotated with OnConflictStrategy.IGNORE
                        PhotoEntity photo = new PhotoEntity(contentUri.toString(), dateTaken, null);
                        photoDao.insertPhoto(photo);
                        count++;
                    }
                    Log.d(TAG, "[CLIP] Scanned " + count + " photos from MediaStore.");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error syncing MediaStore to DB: " + e.getMessage());
        }
    }

    private String formatTime(long millis) {
        if (millis < 1000)
            return millis + "ms";
        long seconds = millis / 1000;
        if (seconds < 60)
            return seconds + "s";
        long minutes = seconds / 60;
        long remainingSeconds = seconds % 60;
        return minutes + "m " + remainingSeconds + "s";
    }
}
