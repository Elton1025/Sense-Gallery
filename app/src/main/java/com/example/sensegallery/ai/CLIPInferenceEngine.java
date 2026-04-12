package com.example.sensegallery.ai;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.util.Log;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CLIP inference engine using ONNX Runtime.
 *
 * Image encoder: NNAPI-accelerated, input [1,3,224,224] NCHW with CLIP
 * normalization.
 * Text encoder: CPU-only (NNAPI silently fails on attention/transformer ops).
 *
 * Models are loaded directly from assets into memory-backed ORT sessions.
 * Legacy on-disk copies are removed to avoid unnecessary app storage growth.
 */
public class CLIPInferenceEngine implements AutoCloseable {

    private static final String TAG = "CLIPInferenceEngine";

    private static final String IMAGE_MODEL = "image_encoder.onnx";
    private static final String TEXT_MODEL = "text_encoder.onnx";

    private static final int IMAGE_SIZE = 224;
    private static final int CONTEXT_LENGTH = 77;
    private static final String MODEL_CACHE_DIR = "models";

    // CLIP normalization constants
    private static final float[] MEAN = { 0.48145466f, 0.4578275f, 0.40821073f };
    private static final float[] STD = { 0.26862954f, 0.26130258f, 0.27577711f };

    private final OrtEnvironment env;
    private OrtSession imageSession;
    private OrtSession textSession;
    private CLIPTokenizer tokenizer;

    private String imageInitError = null;
    private String textInitError = null;
    private final List<File> cachedModelFiles = new ArrayList<>();

    public CLIPInferenceEngine(Context context) {
        Log.d(TAG, "Initializing CLIP inference engine...");
        env = OrtEnvironment.getEnvironment();
        cleanupLegacyFilesDirCopies(context);

        try {
            OrtSession.SessionOptions imageOptions = new OrtSession.SessionOptions();
            imageOptions.setIntraOpNumThreads(4);
            try {
                imageOptions.addNnapi();
                Log.d(TAG, "NNAPI enabled for image encoder");
            } catch (Exception e) {
                Log.w(TAG, "NNAPI not available, using CPU for image encoder: " + e.getMessage());
            }

            imageSession = createSessionFromAsset(context, IMAGE_MODEL, imageOptions);
            Log.d(TAG, "Image encoder loaded. Inputs: " + imageSession.getInputNames()
                    + " Outputs: " + imageSession.getOutputNames());
        } catch (Exception e) {
            imageInitError = e.getMessage();
            Log.e(TAG, "Image encoder init failed: " + imageInitError, e);
        }

        try {
            OrtSession.SessionOptions textOptions = new OrtSession.SessionOptions();
            textOptions.setIntraOpNumThreads(4);

            textSession = createSessionFromAsset(context, TEXT_MODEL, textOptions);
            Log.d(TAG, "Text encoder loaded. Inputs: " + textSession.getInputNames()
                    + " Outputs: " + textSession.getOutputNames());
        } catch (Exception e) {
            textInitError = e.getMessage();
            Log.e(TAG, "Text encoder init failed: " + textInitError, e);
        }

        try {
            tokenizer = new CLIPTokenizer(context);
            if (!tokenizer.isInitialized()) {
                Log.e(TAG, "Tokenizer init failed: " + tokenizer.getInitError());
            }
        } catch (Exception e) {
            Log.e(TAG, "Tokenizer init failed: " + e.getMessage(), e);
        }
    }

    public boolean isImageEncoderReady() {
        return imageSession != null;
    }

    public boolean isTextEncoderReady() {
        return textSession != null && tokenizer != null && tokenizer.isInitialized();
    }

    /**
     * Encodes a bitmap into a CLIP image embedding.
     *
     * @param bitmap Input image (will be scaled to 224x224)
     * @return L2-normalized float array, or null on failure
     */
    public float[] encodeImage(Bitmap bitmap) {
        if (imageSession == null || bitmap == null)
            return null;

        OnnxTensor inputTensor = null;
        OrtSession.Result output = null;
        try {
            Bitmap scaled = Bitmap.createScaledBitmap(bitmap, IMAGE_SIZE, IMAGE_SIZE, true);

            int[] pixels = new int[IMAGE_SIZE * IMAGE_SIZE];
            scaled.getPixels(pixels, 0, IMAGE_SIZE, 0, 0, IMAGE_SIZE, IMAGE_SIZE);

            float[] inputData = new float[3 * IMAGE_SIZE * IMAGE_SIZE];
            for (int i = 0; i < pixels.length; i++) {
                int pixel = pixels[i];
                float r = ((pixel >> 16) & 0xFF) / 255.0f;
                float g = ((pixel >> 8) & 0xFF) / 255.0f;
                float b = (pixel & 0xFF) / 255.0f;

                inputData[i] = (r - MEAN[0]) / STD[0];
                inputData[IMAGE_SIZE * IMAGE_SIZE + i] = (g - MEAN[1]) / STD[1];
                inputData[2 * IMAGE_SIZE * IMAGE_SIZE + i] = (b - MEAN[2]) / STD[2];
            }

            if (scaled != bitmap)
                scaled.recycle();

            long[] shape = { 1, 3, IMAGE_SIZE, IMAGE_SIZE };
            inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(inputData), shape);

            String inputName = imageSession.getInputNames().iterator().next();
            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put(inputName, inputTensor);

            output = imageSession.run(inputs);

            float[] embedding = extractFloatEmbedding(output);
            if (embedding != null) {
                l2Normalize(embedding);
            }
            return embedding;

        } catch (Exception e) {
            Log.e(TAG, "Image encoding failed: " + e.getMessage(), e);
            return null;
        } finally {
            if (inputTensor != null) {
                try {
                    inputTensor.close();
                } catch (Exception ignored) {
                }
            }
            if (output != null) {
                try {
                    output.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * Encodes a text query into a CLIP text embedding.
     *
     * @param text The search query
     * @return L2-normalized float array, or null on failure
     */
    public float[] encodeText(String text) {
        if (textSession == null || tokenizer == null || !tokenizer.isInitialized())
            return null;

        OnnxTensor inputTensor = null;
        OrtSession.Result output = null;
        try {
            long[] tokenIds = tokenizer.tokenize(text);

            long[] shape = { 1, CONTEXT_LENGTH };
            inputTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(tokenIds), shape);

            String inputName = textSession.getInputNames().iterator().next();
            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put(inputName, inputTensor);

            output = textSession.run(inputs);

            float[] embedding = extractFloatEmbedding(output);
            if (embedding != null) {
                l2Normalize(embedding);
            }
            return embedding;

        } catch (Exception e) {
            Log.e(TAG, "Text encoding failed: " + e.getMessage(), e);
            return null;
        } finally {
            if (inputTensor != null) {
                try {
                    inputTensor.close();
                } catch (Exception ignored) {
                }
            }
            if (output != null) {
                try {
                    output.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * Extracts the float embedding from an ONNX output.
     * Handles both float[][] (shape [1, 512]) and float[][][] (shape [1, 1, 512]).
     */
    private float[] extractFloatEmbedding(OrtSession.Result output) throws Exception {
        Object value = output.get(0).getValue();

        if (value instanceof float[][]) {
            return ((float[][]) value)[0];
        } else if (value instanceof float[][][]) {
            return ((float[][][]) value)[0][0];
        } else if (value instanceof float[]) {
            return (float[]) value;
        } else {
            Log.e(TAG, "Unexpected output type: " + value.getClass().getName());
            return null;
        }
    }

    /**
     * Computes cosine similarity between two L2-normalized embeddings.
     * Since both are already normalized, cosine similarity = dot product.
     */
    public static float cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length)
            return 0f;
        float dot = 0f;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
        }
        return dot;
    }

    private void l2Normalize(float[] embedding) {
        float sumSquares = 0f;
        for (float v : embedding)
            sumSquares += v * v;
        float norm = (float) Math.sqrt(sumSquares);
        if (norm > 0) {
            for (int i = 0; i < embedding.length; i++) {
                embedding[i] /= norm;
            }
        }
    }

    /**
     * Copies an ONNX model from assets into filesDir. The copy is written to a
     * temp file first and only replaces the cached model when the byte count
     * matches the packaged asset.
     */
    private String copyModelToCacheDir(Context context, String fileName, boolean forceRefresh) throws Exception {
        File modelDir = new File(context.getCacheDir(), MODEL_CACHE_DIR);
        if (!modelDir.exists() && !modelDir.mkdirs()) {
            throw new IOException("Could not create model cache directory: " + modelDir.getAbsolutePath());
        }

        File destFile = new File(modelDir, fileName);
        long assetLength = getAssetLength(context, fileName);

        if (!forceRefresh && isUsableCachedModel(destFile, assetLength)) {
            Log.d(TAG, "Model already cached: " + destFile.getAbsolutePath()
                    + " (" + formatMegabytes(destFile.length()) + " MB)");
            return destFile.getAbsolutePath();
        }

        if (destFile.exists()) {
            Log.w(TAG, "Replacing cached model " + destFile.getAbsolutePath()
                    + " (cached=" + destFile.length() + " bytes, asset=" + assetLength + " bytes)");
        }

        File tempFile = new File(modelDir, fileName + ".tmp");
        if (tempFile.exists() && !tempFile.delete()) {
            throw new IOException("Could not clear temp model file: " + tempFile.getAbsolutePath());
        }

        Log.d(TAG, "Copying " + fileName + " from assets to filesDir...");
        long start = System.currentTimeMillis();

        byte[] buffer = new byte[65536];
        long totalBytes = 0;
        try (InputStream is = context.getAssets().open(fileName);
                FileOutputStream fos = new FileOutputStream(tempFile)) {
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
                totalBytes += bytesRead;
            }
            fos.flush();
        }

        if (assetLength > 0 && totalBytes != assetLength) {
            if (!tempFile.delete()) {
                Log.w(TAG, "Failed to delete incomplete temp model: " + tempFile.getAbsolutePath());
            }
            throw new IOException("Copied " + totalBytes + " bytes for " + fileName
                    + " but expected " + assetLength);
        }

        if (destFile.exists() && !destFile.delete()) {
            throw new IOException("Could not replace cached model: " + destFile.getAbsolutePath());
        }
        if (!tempFile.renameTo(destFile)) {
            throw new IOException("Could not move temp model into place: " + destFile.getAbsolutePath());
        }

        long elapsed = System.currentTimeMillis() - start;
        Log.d(TAG, "Model cached: " + formatMegabytes(totalBytes) + " MB in " + elapsed + "ms");
        cachedModelFiles.add(destFile);
        return destFile.getAbsolutePath();
    }

    private OrtSession createSessionWithRecovery(
            Context context,
            String modelPath,
            String fileName,
            OrtSession.SessionOptions options) throws Exception {
        try {
            return env.createSession(modelPath, options);
        } catch (Exception firstError) {
            Log.w(TAG, "Failed to open cached " + fileName + ", forcing recopy", firstError);
            String refreshedPath = copyModelToCacheDir(context, fileName, true);
            return env.createSession(refreshedPath, options);
        }
    }

    private OrtSession createSessionFromAsset(
            Context context,
            String fileName,
            OrtSession.SessionOptions options) throws Exception {
        try {
            byte[] modelBytes = readAssetBytes(context, fileName);
            return env.createSession(modelBytes, options);
        } catch (Exception firstError) {
            Log.w(TAG, "Direct asset session load failed for " + fileName + ", retrying once", firstError);
            byte[] retryBytes = readAssetBytes(context, fileName);
            return env.createSession(retryBytes, options);
        }
    }

    private byte[] readAssetBytes(Context context, String fileName) throws IOException {
        try (InputStream is = context.getAssets().open(fileName);
                ByteArrayOutputStream bos = new ByteArrayOutputStream(1024 * 1024)) {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = is.read(buffer)) != -1) {
                bos.write(buffer, 0, read);
            }
            return bos.toByteArray();
        }
    }

    private void cleanupLegacyFilesDirCopies(Context context) {
        deleteIfExists(new File(context.getFilesDir(), IMAGE_MODEL));
        deleteIfExists(new File(context.getFilesDir(), TEXT_MODEL));
        deleteIfExists(new File(context.getFilesDir(), IMAGE_MODEL + ".tmp"));
        deleteIfExists(new File(context.getFilesDir(), TEXT_MODEL + ".tmp"));
    }

    private void deleteIfExists(File file) {
        if (file.exists() && !file.delete()) {
            Log.w(TAG, "Failed to delete legacy model file: " + file.getAbsolutePath());
        }
    }

    private boolean isUsableCachedModel(File destFile, long assetLength) {
        if (!destFile.exists() || destFile.length() <= 0) {
            return false;
        }
        if (assetLength <= 0) {
            return true;
        }
        return destFile.length() == assetLength;
    }

    private long getAssetLength(Context context, String fileName) {
        try (AssetFileDescriptor afd = context.getAssets().openFd(fileName)) {
            return afd.getLength();
        } catch (Exception e) {
            long measuredLength = measureAssetLength(context, fileName);
            if (measuredLength > 0) {
                return measuredLength;
            }
            Log.w(TAG, "Unable to determine asset length for " + fileName + ": " + e.getMessage());
            return -1L;
        }
    }

    private long measureAssetLength(Context context, String fileName) {
        byte[] buffer = new byte[16384];
        long total = 0L;
        try (InputStream is = context.getAssets().open(fileName)) {
            int read;
            while ((read = is.read(buffer)) != -1) {
                total += read;
            }
            return total;
        } catch (Exception e) {
            return -1L;
        }
    }

    private long formatMegabytes(long bytes) {
        return bytes / 1024 / 1024;
    }

    @Override
    public void close() {
        try {
            if (imageSession != null) {
                imageSession.close();
                imageSession = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error closing image session", e);
        }
        try {
            if (textSession != null) {
                textSession.close();
                textSession = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error closing text session", e);
        }
    }
}
