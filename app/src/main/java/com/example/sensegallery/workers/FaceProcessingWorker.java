package com.example.sensegallery.workers;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.example.sensegallery.ai.FaceClusterer;
import com.example.sensegallery.ai.FacePipelineProcessor;

/**
 * Background worker that runs the face detection and clustering pipeline.
 * Designed to run when device is charging and idle.
 */
public class FaceProcessingWorker extends Worker {

    private static final String TAG = "FaceProcessingWorker";

    public FaceProcessingWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        Log.d(TAG, "Starting face processing work...");

        try {
            Context context = getApplicationContext();

            // 1. Run Face Detection & Embedding Pipeline
            FacePipelineProcessor processor = new FacePipelineProcessor(context);

            // We use a synchronous approach here since we are already in a background
            // thread provided by WorkManager
            // Note: facePipelineProcessor.processAllPhotos takes a callback. We can pass a
            // simple logging callback
            // or modify processor to blocking. For simplicity, we'll use a blocking wrapper
            // or just let it run.
            // Actually, FacePipelineProcessor logic seems properly synchronous inside its
            // loop,
            // but `processAllPhotos` itself might be designed for async?
            // Checking previous code: it returns void and takes callback. It assumes we
            // call it from BG thread.
            // It does NOT spawn its own thread, it just runs. Perfect.

            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

            processor.processAllPhotos(new FacePipelineProcessor.ProgressCallback() {
                @Override
                public void onProgress(int current, int total, int faces) {
                    if (current % 10 == 0) {
                        Log.d(TAG, "Processed " + current + "/" + total + " photos. Faces found: " + faces);
                    }
                }

                @Override
                public void onComplete(int totalPhotos, int totalFaces) {
                    Log.d(TAG, "Detection complete. Found " + totalFaces + " faces.");
                    latch.countDown();
                }

                @Override
                public void onError(String message) {
                    Log.e(TAG, "Error in detection: " + message);
                    latch.countDown();
                }
            });

            try {
                latch.await();
            } catch (InterruptedException e) {
                Log.e(TAG, "Worker interrupted", e);
            }

            // 2. Run Clustering
            Log.d(TAG, "Starting clustering phase...");
            FaceClusterer clusterer = new FaceClusterer(context);
            clusterer.clusterFaces();

            Log.d(TAG, "Face processing work finished successfully.");
            return Result.success();

        } catch (Exception e) {
            Log.e(TAG, "Error during face processing work", e);
            return Result.retry();
        }
    }
}
