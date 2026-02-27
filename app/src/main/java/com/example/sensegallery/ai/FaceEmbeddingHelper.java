package com.example.sensegallery.ai;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.util.Log;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ArcFace R50 face embedding extractor via ONNX Runtime.
 *
 * Uses InsightFace's w600k_r50.onnx / arcface_w600k_r50.onnx model:
 * - Trained on WebFace600K with ArcFace loss
 * - 99.77% accuracy on LFW benchmark
 * - 512-dimensional embeddings
 *
 * Input: [1, 3, 112, 112] float32 (RGB, normalized to [-1, 1])
 * Output: [1, 512] float32 embedding
 *
 * TTA Strategy (with proper 5-point alignment):
 * Pass 1: Original aligned face
 * Pass 2: Horizontally mirrored face
 * Final: Average of both, L2-normalized
 *
 * With ArcFace + proper alignment, just 2 passes gives better accuracy
 * than 24 passes with FaceNet + poor alignment.
 */
public class FaceEmbeddingHelper implements AutoCloseable {

    private static final String TAG = "FaceEmbeddingHelper";
    private static final String MODEL_NAME = "arcface_w600k_r50.onnx";
    private static final int INPUT_SIZE = 112;
    private static final int EMBEDDING_DIM = 512;
    private static final float IMAGE_MEAN = 127.5f;
    private static final float IMAGE_STD = 127.5f;

    private OrtEnvironment env;
    private OrtSession session;
    private String initError = null;

    public FaceEmbeddingHelper(Context context) {
        Log.d(TAG, "Initializing ArcFace R50 via ONNX Runtime");
        try {
            env = OrtEnvironment.getEnvironment();

            // Copy model from assets to cache (avoids OOM from 166MB byte[])
            String modelPath = copyModelToCache(context, MODEL_NAME);
            Log.d(TAG, "Model copied to: " + modelPath);

            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            options.setIntraOpNumThreads(4);

            session = env.createSession(modelPath, options);
            Log.d(TAG, "ArcFace R50 initialized — 512-d embeddings");
            Log.d(TAG, "Input: " + session.getInputNames());
            Log.d(TAG, "Output: " + session.getOutputNames());
        } catch (Exception e) {
            initError = e.getMessage();
            Log.e(TAG, "Failed to load ArcFace model: " + initError, e);
        }
    }

    public boolean isInitialized() {
        return session != null;
    }

    public String getInitError() {
        return initError;
    }

    /**
     * Extracts a 512-d face embedding using ArcFace R50.
     *
     * Uses 2-pass TTA (original + mirror) which is the standard
     * ArcFace inference protocol used by InsightFace.
     *
     * @param alignedFace A 112×112 aligned face bitmap
     * @return L2-normalized 512-d float embedding, or null on failure
     */
    public float[] getEmbedding(Bitmap alignedFace) {
        if (session == null || alignedFace == null)
            return null;

        long startTime = System.currentTimeMillis();

        try {
            // Ensure correct input size
            Bitmap input = alignedFace;
            if (alignedFace.getWidth() != INPUT_SIZE || alignedFace.getHeight() != INPUT_SIZE) {
                input = Bitmap.createScaledBitmap(alignedFace, INPUT_SIZE, INPUT_SIZE, true);
            }

            // Pass 1: Original
            float[] emb1 = runInference(input);

            // Pass 2: Horizontal mirror
            Bitmap mirrored = mirrorBitmap(input);
            float[] emb2 = runInference(mirrored);
            mirrored.recycle();

            if (input != alignedFace)
                input.recycle();

            if (emb1 == null)
                return null;

            // Average embeddings
            float[] finalEmbedding;
            if (emb2 != null) {
                finalEmbedding = new float[EMBEDDING_DIM];
                for (int i = 0; i < EMBEDDING_DIM; i++) {
                    finalEmbedding[i] = (emb1[i] + emb2[i]) / 2.0f;
                }
            } else {
                finalEmbedding = emb1;
            }

            // L2 normalize
            l2Normalize(finalEmbedding);

            long elapsed = System.currentTimeMillis() - startTime;
            Log.d(TAG, "ArcFace embedding: " + elapsed + "ms (512-d, 2-pass TTA)");

            return finalEmbedding;

        } catch (Exception e) {
            Log.e(TAG, "Embedding extraction failed: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * Run a single forward pass through ArcFace.
     * Input: [1, 3, 112, 112] CHW format, normalized to [-1, 1]
     */
    private float[] runInference(Bitmap bitmap) {
        try {
            int[] pixels = new int[INPUT_SIZE * INPUT_SIZE];
            bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE);

            // Convert to CHW format, normalized: (pixel - 127.5) / 127.5
            float[] inputData = new float[3 * INPUT_SIZE * INPUT_SIZE];
            for (int i = 0; i < pixels.length; i++) {
                int pixel = pixels[i];
                inputData[i] = (((pixel >> 16) & 0xFF) - IMAGE_MEAN) / IMAGE_STD; // R
                inputData[INPUT_SIZE * INPUT_SIZE + i] = (((pixel >> 8) & 0xFF) - IMAGE_MEAN) / IMAGE_STD; // G
                inputData[2 * INPUT_SIZE * INPUT_SIZE + i] = ((pixel & 0xFF) - IMAGE_MEAN) / IMAGE_STD; // B
            }

            long[] shape = { 1, 3, INPUT_SIZE, INPUT_SIZE };
            OnnxTensor inputTensor = OnnxTensor.createTensor(env,
                    FloatBuffer.wrap(inputData), shape);

            String inputName = session.getInputNames().iterator().next();
            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put(inputName, inputTensor);

            OrtSession.Result output = session.run(inputs);

            // Output: [1, 512]
            float[][] result = (float[][]) output.get(0).getValue();
            float[] embedding = result[0];

            inputTensor.close();
            output.close();

            return embedding;

        } catch (Exception e) {
            Log.e(TAG, "Inference failed: " + e.getMessage(), e);
            return null;
        }
    }

    private Bitmap mirrorBitmap(Bitmap src) {
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.preScale(-1.0f, 1.0f);
        return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
    }

    private void l2Normalize(float[] embedding) {
        float sumSquares = 0f;
        for (float v : embedding)
            sumSquares += v * v;
        float norm = (float) Math.sqrt(sumSquares);
        if (norm > 0) {
            for (int i = 0; i < embedding.length; i++)
                embedding[i] /= norm;
        }
    }

    /**
     * Copies model from assets to cache directory (only if not already cached).
     * ONNX Runtime can then load via file path with memory-mapping,
     * avoiding the need to hold the entire 166MB model in a byte[].
     */
    private String copyModelToCache(Context context, String fileName) throws Exception {
        java.io.File cacheFile = new java.io.File(context.getCacheDir(), fileName);
        if (cacheFile.exists() && cacheFile.length() > 0) {
            Log.d(TAG, "Model already cached: " + cacheFile.getAbsolutePath()
                    + " (" + (cacheFile.length() / 1024 / 1024) + " MB)");
            return cacheFile.getAbsolutePath();
        }

        Log.d(TAG, "Copying " + fileName + " from assets to cache...");
        long start = System.currentTimeMillis();

        InputStream is = context.getAssets().open(fileName);
        java.io.FileOutputStream fos = new java.io.FileOutputStream(cacheFile);
        byte[] buffer = new byte[65536]; // 64KB buffer
        int bytesRead;
        long totalBytes = 0;
        while ((bytesRead = is.read(buffer)) != -1) {
            fos.write(buffer, 0, bytesRead);
            totalBytes += bytesRead;
        }
        fos.flush();
        fos.close();
        is.close();

        long elapsed = System.currentTimeMillis() - start;
        Log.d(TAG, "Model cached: " + (totalBytes / 1024 / 1024) + " MB in " + elapsed + "ms");
        return cacheFile.getAbsolutePath();
    }

    @Override
    public void close() {
        try {
            if (session != null) {
                session.close();
                session = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error closing session", e);
        }
    }
}
