package com.example.sensegallery.ai;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;

import com.example.sensegallery.data.AppDatabase;
import com.example.sensegallery.data.EmbeddingTypeConverter;
import com.example.sensegallery.data.FaceDao;
import com.example.sensegallery.data.FaceEntity;
import com.example.sensegallery.data.PhotoDao;
import com.example.sensegallery.data.PhotoEntity;

import java.io.InputStream;
import java.util.List;

/**
 * Face processing pipeline using production-grade InsightFace models:
 *
 * 1. Load bitmap at 2048px
 * 2. Detect faces with RetinaFace (det_10g.onnx) — returns 5-point landmarks
 * 3. Filter blurry/tiny faces
 * 4. 5-point similarity-transform alignment → 112×112 canonical face
 * 5. Extract 512-d embedding via ArcFace R50 (w600k_r50.onnx)
 * 6. Save to Room database
 *
 * This pipeline matches the accuracy of InsightFace's Python implementation.
 */
public class FacePipelineProcessor {

    private static final String TAG = "FacePipelineProcessor";

    private static final int MAX_BITMAP_DIMENSION = 2048;
    private static final int MIN_FACE_SIZE = 40;
    private static final double BLUR_THRESHOLD = 30.0;

    // ArcFace standard 5-point alignment template for 112×112 output.
    // These are the exact reference coordinates used by InsightFace.
    private static final float[][] ARCFACE_TEMPLATE = {
            { 38.2946f, 51.6963f }, // Left eye
            { 73.5318f, 51.5014f }, // Right eye
            { 56.0252f, 71.7366f }, // Nose tip
            { 41.5493f, 92.3655f }, // Left mouth corner
            { 70.7299f, 92.2041f } // Right mouth corner
    };
    private static final int ALIGNED_SIZE = 112; // ArcFace input size

    public interface ProgressCallback {
        void onProgress(int currentPhoto, int totalPhotos, int facesFound);

        void onComplete(int totalPhotosProcessed, int totalFacesFound);

        void onError(String message);
    }

    private final Context context;
    private final AppDatabase database;

    public FacePipelineProcessor(Context context) {
        this.context = context.getApplicationContext();
        this.database = AppDatabase.getInstance(context);
    }

    public void processAllPhotos(ProgressCallback callback) {
        FaceDetectionHelper detector = null;
        FaceEmbeddingHelper embedder = null;

        try {
            validateModelFiles(callback);
            syncPhotosToDb();

            detector = new FaceDetectionHelper(context);
            if (!detector.isInitialized()) {
                throw new RuntimeException("RetinaFace failed: " + detector.getInitError());
            }

            embedder = new FaceEmbeddingHelper(context);
            if (!embedder.isInitialized()) {
                throw new RuntimeException("ArcFace failed: " + embedder.getInitError());
            }

            PhotoDao photoDao = database.photoDao();
            FaceDao faceDao = database.faceDao();

            List<PhotoEntity> photos = photoDao.getUnprocessedPhotos();
            int totalPhotos = photos.size();
            int totalFaces = 0;

            Log.d(TAG, "Starting ARCFACE+RETINAFACE pipeline for " + totalPhotos + " photos");

            for (int i = 0; i < totalPhotos; i++) {
                PhotoEntity photo = photos.get(i);

                List<FaceEntity> existingFaces = faceDao.getFacesForPhoto(photo.id);
                if (!existingFaces.isEmpty()) {
                    if (callback != null)
                        callback.onProgress(i + 1, totalPhotos, totalFaces);
                    continue;
                }

                try {
                    int faces = processPhoto(photo, detector, embedder, faceDao);
                    totalFaces += faces;

                    // Mark as processed regardless of faces found
                    photoDao.markFaceProcessed(photo.id);

                } catch (Exception e) {
                    Log.e(TAG, "Error processing photo " + photo.id + ": " + e.getMessage());
                }

                if (callback != null)
                    callback.onProgress(i + 1, totalPhotos, totalFaces);
            }

            Log.d(TAG, "Pipeline complete: " + totalFaces + " faces from " + totalPhotos + " photos");
            if (callback != null)
                callback.onComplete(totalPhotos, totalFaces);

        } catch (Exception e) {
            Log.e(TAG, "Pipeline error: " + e.getMessage(), e);
            if (callback != null)
                callback.onError(e.getMessage());
            throw e;
        } finally {
            if (detector != null)
                detector.close();
            if (embedder != null)
                embedder.close();
        }
    }

    private int processPhoto(PhotoEntity photo, FaceDetectionHelper detector,
            FaceEmbeddingHelper embedder, FaceDao faceDao) {
        Uri uri = Uri.parse(photo.mediaStoreUri);
        Bitmap bitmap = loadBitmap(uri);
        if (bitmap == null)
            return 0;

        try {
            int imgWidth = bitmap.getWidth();
            int imgHeight = bitmap.getHeight();

            long photoStart = System.currentTimeMillis();
            Log.i(TAG, "========================================");
            Log.i(TAG, "[START] Photo " + photo.id + " (" + imgWidth + "x" + imgHeight + ")");

            // --- RetinaFace Detection ---
            long detectStart = System.currentTimeMillis();
            Log.i(TAG, "  [RetinaFace] Starting detection...");
            List<FaceDetectionResult> detections = detector.detectFaces(bitmap);
            long detectTime = System.currentTimeMillis() - detectStart;
            Log.i(TAG, "  [RetinaFace] Done — " + detections.size() + " face(s) found in " + detectTime + "ms");

            if (detections.isEmpty()) {
                Log.i(TAG, "  [SKIP] No faces detected");
                Log.i(TAG, "[END] Photo " + photo.id + " — 0 faces (" + detectTime + "ms total)");
                return 0;
            }

            int facesFound = 0;

            for (FaceDetectionResult det : detections) {
                RectF box = det.boundingBox;

                // --- Step 1: Size filter ---
                int faceW = (int) ((box.right - box.left) * imgWidth);
                int faceH = (int) ((box.bottom - box.top) * imgHeight);

                if (faceW < MIN_FACE_SIZE || faceH < MIN_FACE_SIZE) {
                    Log.d(TAG, "Skipping tiny face: " + faceW + "x" + faceH);
                    continue;
                }

                // --- Step 2: Crop face for blur check ---
                int left = Math.max(0, (int) (box.left * imgWidth));
                int top = Math.max(0, (int) (box.top * imgHeight));
                int right = Math.min(imgWidth, (int) (box.right * imgWidth));
                int bottom = Math.min(imgHeight, (int) (box.bottom * imgHeight));

                Bitmap croppedFace = Bitmap.createBitmap(bitmap, left, top,
                        right - left, bottom - top);

                double blurScore = calculateBlurScore(croppedFace);
                croppedFace.recycle();

                if (blurScore < BLUR_THRESHOLD) {
                    Log.d(TAG, "Skipping blurry face (score=" + String.format("%.1f", blurScore) + ")");
                    continue;
                }

                // --- Step 3: 5-point Similarity Transform Alignment ---
                Bitmap alignedFace;
                if (det.has5Landmarks()) {
                    // Convert normalized landmarks to pixel coordinates
                    float[][] srcPoints = {
                            { det.leftEye.x * imgWidth, det.leftEye.y * imgHeight },
                            { det.rightEye.x * imgWidth, det.rightEye.y * imgHeight },
                            { det.nose.x * imgWidth, det.nose.y * imgHeight },
                            { det.leftMouth.x * imgWidth, det.leftMouth.y * imgHeight },
                            { det.rightMouth.x * imgWidth, det.rightMouth.y * imgHeight }
                    };

                    alignedFace = alignFace5Point(bitmap, srcPoints, ARCFACE_TEMPLATE,
                            ALIGNED_SIZE, ALIGNED_SIZE);

                    Log.d(TAG, "5-point aligned face to " + ALIGNED_SIZE + "×" + ALIGNED_SIZE);
                } else {
                    // Fallback: simple crop + resize
                    Log.w(TAG, "No 5-point landmarks, using crop fallback");
                    float margin = 0.3f;
                    int mL = Math.max(0, (int) (left - faceW * margin));
                    int mT = Math.max(0, (int) (top - faceH * margin));
                    int mR = Math.min(imgWidth, (int) (right + faceW * margin));
                    int mB = Math.min(imgHeight, (int) (bottom + faceH * margin));
                    Bitmap crop = Bitmap.createBitmap(bitmap, mL, mT, mR - mL, mB - mT);
                    alignedFace = Bitmap.createScaledBitmap(crop, ALIGNED_SIZE, ALIGNED_SIZE, true);
                    if (crop != alignedFace)
                        crop.recycle();
                }

                // --- Step 4: Extract embedding ---
                long embStart = System.currentTimeMillis();
                float[] embedding = embedder.getEmbedding(alignedFace);
                long embTime = System.currentTimeMillis() - embStart;
                alignedFace.recycle();

                if (embedding != null) {
                    byte[] embeddingBytes = EmbeddingTypeConverter.fromFloatArray(embedding);

                    FaceEntity face = new FaceEntity(
                            photo.id,
                            box.left, box.top, box.right, box.bottom,
                            embeddingBytes,
                            null);

                    faceDao.insertFace(face);
                    facesFound++;
                    Log.i(TAG, "  Face #" + facesFound + " saved — ArcFace " + embTime + "ms, conf="
                            + String.format("%.3f", det.confidence));
                } else {
                    Log.w(TAG, "  ArcFace embedding returned null");
                }
            }

            long totalPhotoTime = System.currentTimeMillis() - photoStart;
            Log.i(TAG,
                    "[END] Photo " + photo.id + " — " + facesFound + " face(s) saved (" + totalPhotoTime + "ms total)");
            Log.i(TAG, "========================================");
            return facesFound;

        } finally {
            bitmap.recycle();
        }
    }

    /**
     * Computes a similarity transform from 5 source landmarks to the ArcFace
     * standard template, then warps the full image to produce an aligned face.
     *
     * Uses least-squares fitting of the similarity transform:
     * [x'] = [s·cosθ -s·sinθ] [x - cx] + [tx]
     * [y'] [s·sinθ s·cosθ] [y - cy] [ty]
     *
     * The 5 points overdetermine the 4 DOF (scale, rotation, tx, ty),
     * so we get a robust least-squares solution.
     */
    private Bitmap alignFace5Point(Bitmap src, float[][] srcPoints, float[][] dstPoints,
            int outputW, int outputH) {
        // Compute centroids
        float srcCx = 0, srcCy = 0, dstCx = 0, dstCy = 0;
        int n = Math.min(srcPoints.length, dstPoints.length);
        for (int i = 0; i < n; i++) {
            srcCx += srcPoints[i][0];
            srcCy += srcPoints[i][1];
            dstCx += dstPoints[i][0];
            dstCy += dstPoints[i][1];
        }
        srcCx /= n;
        srcCy /= n;
        dstCx /= n;
        dstCy /= n;

        // Compute least-squares similarity transform parameters
        // Using the Umeyama algorithm (simplified for 2D similarity)
        float sxx = 0, syy = 0, sxy = 0, syx = 0;
        float srcVar = 0;
        for (int i = 0; i < n; i++) {
            float dx = srcPoints[i][0] - srcCx;
            float dy = srcPoints[i][1] - srcCy;
            float ddx = dstPoints[i][0] - dstCx;
            float ddy = dstPoints[i][1] - dstCy;

            sxx += dx * ddx;
            syy += dy * ddy;
            sxy += dx * ddy;
            syx += dy * ddx;
            srcVar += dx * dx + dy * dy;
        }

        if (srcVar < 1e-6f)
            return null;

        // Optimal rotation and scale
        float a = (sxx + syy) / srcVar; // s * cos(theta)
        float b = (sxy - syx) / srcVar; // s * sin(theta)

        // Translation
        float tx = dstCx - a * srcCx + b * srcCy;
        float ty = dstCy - b * srcCx - a * srcCy;

        // Build Android Matrix (forward transform: src → dst)
        Matrix matrix = new Matrix();
        float[] values = {
                a, -b, tx,
                b, a, ty,
                0, 0, 1
        };
        matrix.setValues(values);

        // Warp
        Bitmap aligned = Bitmap.createBitmap(outputW, outputH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(aligned);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        canvas.drawBitmap(src, matrix, paint);

        return aligned;
    }

    /**
     * Blur detection via Laplacian variance.
     */
    private double calculateBlurScore(Bitmap bitmap) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();

        int targetSize = 64;
        Bitmap small;
        if (w > targetSize || h > targetSize) {
            float scale = (float) targetSize / Math.max(w, h);
            small = Bitmap.createScaledBitmap(bitmap, (int) (w * scale), (int) (h * scale), true);
        } else {
            small = bitmap;
        }

        int sw = small.getWidth();
        int sh = small.getHeight();
        int[] pixels = new int[sw * sh];
        small.getPixels(pixels, 0, sw, 0, 0, sw, sh);

        float[] gray = new float[sw * sh];
        for (int i = 0; i < pixels.length; i++) {
            int r = (pixels[i] >> 16) & 0xFF;
            int g = (pixels[i] >> 8) & 0xFF;
            int b = pixels[i] & 0xFF;
            gray[i] = 0.299f * r + 0.587f * g + 0.114f * b;
        }

        double sum = 0, sumSq = 0;
        int count = 0;
        for (int y = 1; y < sh - 1; y++) {
            for (int x = 1; x < sw - 1; x++) {
                float lap = -4 * gray[y * sw + x]
                        + gray[(y - 1) * sw + x]
                        + gray[(y + 1) * sw + x]
                        + gray[y * sw + (x - 1)]
                        + gray[y * sw + (x + 1)];
                sum += lap;
                sumSq += lap * lap;
                count++;
            }
        }

        if (count == 0)
            return 0;
        double mean = sum / count;
        double variance = (sumSq / count) - (mean * mean);

        if (small != bitmap)
            small.recycle();
        return variance;
    }

    private Bitmap loadBitmap(Uri uri) {
        ContentResolver resolver = context.getContentResolver();
        try {
            BitmapFactory.Options boundsOptions = new BitmapFactory.Options();
            boundsOptions.inJustDecodeBounds = true;
            try (InputStream is = resolver.openInputStream(uri)) {
                BitmapFactory.decodeStream(is, null, boundsOptions);
            }

            int maxDim = Math.max(boundsOptions.outWidth, boundsOptions.outHeight);
            int sampleSize = 1;
            while (maxDim / sampleSize > MAX_BITMAP_DIMENSION) {
                sampleSize *= 2;
            }

            BitmapFactory.Options decodeOpts = new BitmapFactory.Options();
            decodeOpts.inSampleSize = sampleSize;
            decodeOpts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            try (InputStream is = resolver.openInputStream(uri)) {
                return BitmapFactory.decodeStream(is, null, decodeOpts);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load bitmap: " + uri, e);
            return null;
        }
    }

    private void syncPhotosToDb() {
        ContentResolver resolver = context.getContentResolver();
        PhotoDao photoDao = database.photoDao();

        Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String[] projection = {
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_TAKEN
        };

        try (Cursor cursor = resolver.query(collection, projection, null, null, null)) {
            if (cursor == null)
                return;

            int idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
            int dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN);

            while (cursor.moveToNext()) {
                long id = cursor.getLong(idCol);
                long dateTaken = cursor.getLong(dateCol);

                Uri contentUri = Uri.withAppendedPath(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        String.valueOf(id));

                PhotoEntity photo = new PhotoEntity(contentUri.toString(), dateTaken, null);
                photoDao.insertPhoto(photo);
            }
        }

        Log.d(TAG, "MediaStore sync: " + photoDao.getPhotoCount() + " photos in DB");
    }

    private void validateModelFiles(ProgressCallback callback) {
        String[] requiredModels = {
                "det_10g.onnx",
                "arcface_w600k_r50.onnx"
        };
        for (String model : requiredModels) {
            try {
                InputStream is = context.getAssets().open(model);
                int size = is.available();
                is.close();
                Log.d(TAG, "Model validated: " + model + " (" + (size / 1024 / 1024) + " MB)");
            } catch (Exception e) {
                String msg = "Model missing: " + model + ". Place in app/src/main/assets/";
                Log.e(TAG, msg);
                throw new RuntimeException(msg);
            }
        }
    }
}
