package com.example.sensegallery.ai;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PointF;
import android.graphics.RectF;
import android.util.Log;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RetinaFace face detector via ONNX Runtime.
 *
 * Uses InsightFace's det_10g.onnx model which provides:
 * - High-accuracy face detection
 * - 5-point facial landmarks (left eye, right eye, nose, left mouth, right
 * mouth)
 *
 * Input: [1, 3, H, W] float32 (RGB, pixel values 0-255 range, NOT normalized)
 * Output: Decoded bounding boxes, scores, and 5-point landmarks per face
 *
 * The model uses a Feature Pyramid Network (FPN) with 3 stride levels (8, 16,
 * 32).
 * Each stride level outputs:
 * - scores: [1, num_anchors, 1] — face confidence
 * - bboxes: [1, num_anchors, 4] — (x1, y1, x2, y2) in pixel coordinates
 * - kps: [1, num_anchors, 10] — 5 landmarks × 2 (x, y) in pixel coordinates
 */
public class FaceDetectionHelper implements AutoCloseable {

    private static final String TAG = "FaceDetectionHelper";
    private static final String MODEL_NAME = "det_10g.onnx";
    private static final float CONFIDENCE_THRESHOLD = 0.5f;
    private static final float NMS_THRESHOLD = 0.4f;
    private static final int INPUT_SIZE = 640;

    private OrtEnvironment env;
    private OrtSession session;
    private String initError = null;

    public FaceDetectionHelper(Context context) {
        try {
            env = OrtEnvironment.getEnvironment();

            // Copy model from assets to cache for file-based loading
            String modelPath = copyModelToCache(context, MODEL_NAME);

            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            options.setIntraOpNumThreads(4);

            session = env.createSession(modelPath, options);
            Log.d(TAG, "RetinaFace initialized successfully");
            Log.d(TAG, "Input names: " + session.getInputNames());
            Log.d(TAG, "Output names: " + session.getOutputNames());
        } catch (Exception e) {
            initError = e.getMessage();
            Log.e(TAG, "Failed to initialize RetinaFace: " + initError, e);
        }
    }

    public boolean isInitialized() {
        return session != null;
    }

    public String getInitError() {
        return initError;
    }

    /**
     * Detects faces in the given bitmap using RetinaFace.
     * Returns normalized coordinates (0-1 range).
     */
    public List<FaceDetectionResult> detectFaces(Bitmap bitmap) {
        List<FaceDetectionResult> results = new ArrayList<>();

        if (session == null || bitmap == null)
            return results;

        try {
            int origWidth = bitmap.getWidth();
            int origHeight = bitmap.getHeight();

            // Resize to INPUT_SIZE maintaining aspect ratio, pad to square
            float scale = (float) INPUT_SIZE / Math.max(origWidth, origHeight);
            int scaledWidth = Math.round(origWidth * scale);
            int scaledHeight = Math.round(origHeight * scale);

            Bitmap resized = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true);

            // Create input tensor [1, 3, INPUT_SIZE, INPUT_SIZE]
            float[] inputData = new float[3 * INPUT_SIZE * INPUT_SIZE];
            int[] pixels = new int[scaledWidth * scaledHeight];
            resized.getPixels(pixels, 0, scaledWidth, 0, 0, scaledWidth, scaledHeight);

            // Fill CHW format (BGR vs RGB - SCRFD uses RGB or BGR, we'll try RGB,
            // normalized)
            for (int y = 0; y < scaledHeight; y++) {
                for (int x = 0; x < scaledWidth; x++) {
                    int pixel = pixels[y * scaledWidth + x];
                    int idx = y * INPUT_SIZE + x;

                    // Normalization: (value - 127.5) / 128.0
                    inputData[0 * INPUT_SIZE * INPUT_SIZE + idx] = (((pixel >> 16) & 0xFF) - 127.5f) / 128.0f; // R
                    inputData[1 * INPUT_SIZE * INPUT_SIZE + idx] = (((pixel >> 8) & 0xFF) - 127.5f) / 128.0f; // G
                    inputData[2 * INPUT_SIZE * INPUT_SIZE + idx] = ((pixel & 0xFF) - 127.5f) / 128.0f; // B
                }
            }

            if (resized != bitmap)
                resized.recycle();

            // Run inference
            long[] inputShape = { 1, 3, INPUT_SIZE, INPUT_SIZE };
            OnnxTensor inputTensor = OnnxTensor.createTensor(env,
                    FloatBuffer.wrap(inputData), inputShape);

            String inputName = session.getInputNames().iterator().next();
            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put(inputName, inputTensor);

            OrtSession.Result output = session.run(inputs);

            List<float[]> allBoxes = new ArrayList<>();
            List<Float> allScores = new ArrayList<>();
            List<float[]> allKeypoints = new ArrayList<>();

            int outputCount = output.size();
            Log.d(TAG, "RetinaFace output count: " + outputCount);

            int[] strides = { 8, 16, 32 };

            if (outputCount >= 9) {
                // det_10g outputs 9 tensors. The order is either by stride or by feature type.
                // We will collect all 9, group by feature length, and sort by anchor count.
                List<float[][]> scoreOutputs = new ArrayList<>();
                List<float[][]> bboxOutputs = new ArrayList<>();
                List<float[][]> kpsOutputs = new ArrayList<>();

                for (int i = 0; i < 9; i++) {
                    float[][] arr = (float[][]) output.get(i).getValue();
                    if (arr.length == 0)
                        continue;
                    int featureLen = arr[0].length;
                    if (featureLen == 1)
                        scoreOutputs.add(arr);
                    else if (featureLen == 4)
                        bboxOutputs.add(arr);
                    else if (featureLen == 10)
                        kpsOutputs.add(arr);
                }

                // Sort descending by number of anchors.
                // Stride 8 has the most anchors, Stride 16 has medium, Stride 32 has the least.
                Comparator<float[][]> byLengthDesc = (a, b) -> Integer.compare(b.length, a.length);
                Collections.sort(scoreOutputs, byLengthDesc);
                Collections.sort(bboxOutputs, byLengthDesc);
                Collections.sort(kpsOutputs, byLengthDesc);

                if (scoreOutputs.size() == 3 && bboxOutputs.size() == 3 && kpsOutputs.size() == 3) {
                    for (int strideIdx = 0; strideIdx < 3; strideIdx++) {
                        int stride = strides[strideIdx];
                        float[][] scores = scoreOutputs.get(strideIdx);
                        float[][] bboxes = bboxOutputs.get(strideIdx);
                        float[][] kps = kpsOutputs.get(strideIdx);

                        int gridW = INPUT_SIZE / stride;
                        int gridH = INPUT_SIZE / stride;

                        for (int i = 0; i < scores.length; i++) {
                            float score = scores[i][0];
                            if (score < CONFIDENCE_THRESHOLD)
                                continue;

                            // Calculate center point for this anchor
                            // i = (y * gridW + x) * 2 + a
                            int y = (i / 2) / gridW;
                            int x = (i / 2) % gridW;
                            float cx = x * stride;
                            float cy = y * stride;

                            // Distance to bounding box
                            float dx1 = bboxes[i][0];
                            float dy1 = bboxes[i][1];
                            float dx2 = bboxes[i][2];
                            float dy2 = bboxes[i][3];

                            // SCRFD sometimes scales distance by stride, sometimes doesn't.
                            // Standard SCRFD distances are multiplied by stride inside the model,
                            // so we just subtract distance directly from center point.
                            float x1 = cx - dx1 * stride;
                            float y1 = cy - dy1 * stride;
                            float x2 = cx + dx2 * stride;
                            float y2 = cy + dy2 * stride;

                            // Decode keypoints
                            float[] decodedKps = new float[10];
                            for (int k = 0; k < 5; k++) {
                                decodedKps[k * 2] = cx + kps[i][k * 2] * stride;
                                decodedKps[k * 2 + 1] = cy + kps[i][k * 2 + 1] * stride;
                            }

                            allScores.add(score);
                            allBoxes.add(new float[] { x1, y1, x2, y2 });
                            allKeypoints.add(decodedKps);
                        } // end for scores
                    } // end for stride
                } // end if size==3
            } else if (outputCount == 3) {
                // Not typical for pure SCRFD without merged outputs, but just in case
                float[][] out0 = (float[][]) output.get(0).getValue();
                float[][] out1 = (float[][]) output.get(1).getValue();
                float[][] out2 = (float[][]) output.get(2).getValue();

                float[][] scores = null, bboxes = null, kps = null;
                float[][][] arrays = { out0, out1, out2 };
                for (float[][] arr : arrays) {
                    if (arr.length == 0)
                        continue;
                    int featureLen = arr[0].length;
                    if (featureLen == 1)
                        scores = arr;
                    else if (featureLen == 4)
                        bboxes = arr;
                    else if (featureLen == 10)
                        kps = arr;
                }

                if (scores != null && bboxes != null && kps != null) {
                    int idx = 0;
                    for (int strideIdx = 0; strideIdx < 3; strideIdx++) {
                        int stride = strides[strideIdx];
                        int gridW = INPUT_SIZE / stride;
                        int gridH = INPUT_SIZE / stride;
                        int anchorsInStride = gridW * gridH * 2;

                        for (int i = 0; i < anchorsInStride && idx < scores.length; i++, idx++) {
                            float score = scores[idx][0];
                            if (score < CONFIDENCE_THRESHOLD)
                                continue;

                            int y = (i / 2) / gridW;
                            int x = (i / 2) % gridW;
                            float cx = x * stride;
                            float cy = y * stride;

                            float x1 = cx - bboxes[idx][0] * stride;
                            float y1 = cy - bboxes[idx][1] * stride;
                            float x2 = cx + bboxes[idx][2] * stride;
                            float y2 = cy + bboxes[idx][3] * stride;

                            float[] decodedKps = new float[10];
                            for (int k = 0; k < 5; k++) {
                                decodedKps[k * 2] = cx + kps[idx][k * 2] * stride;
                                decodedKps[k * 2 + 1] = cy + kps[idx][k * 2 + 1] * stride;
                            }

                            allScores.add(score);
                            allBoxes.add(new float[] { x1, y1, x2, y2 });
                            allKeypoints.add(decodedKps);
                        }
                    }
                }
            }

            Log.d(TAG, "Before NMS: " + allScores.size() + " candidates");

            // Apply NMS
            List<Integer> keepIndices = applyNMS(allBoxes, allScores, NMS_THRESHOLD);

            Log.d(TAG, "After NMS: " + keepIndices.size() + " faces");

            for (int idx : keepIndices) {
                float[] box = allBoxes.get(idx);
                float score = allScores.get(idx);
                float[] kps = allKeypoints.get(idx);

                // Convert from scaled pixel coords back to normalized coords (0-1)
                float x1 = Math.max(0, box[0] / scaledWidth);
                float y1 = Math.max(0, box[1] / scaledHeight);
                float x2 = Math.min(1.0f, box[2] / scaledWidth);
                float y2 = Math.min(1.0f, box[3] / scaledHeight);

                RectF boundingBox = new RectF(x1, y1, x2, y2);

                // 5 keypoints: [lEyeX, lEyeY, rEyeX, rEyeY, noseX, noseY, lMouthX, lMouthY,
                // rMouthX, rMouthY]
                PointF leftEye = new PointF(kps[0] / scaledWidth, kps[1] / scaledHeight);
                PointF rightEye = new PointF(kps[2] / scaledWidth, kps[3] / scaledHeight);
                PointF noseTip = new PointF(kps[4] / scaledWidth, kps[5] / scaledHeight);
                PointF leftMouth = new PointF(kps[6] / scaledWidth, kps[7] / scaledHeight);
                PointF rightMouth = new PointF(kps[8] / scaledWidth, kps[9] / scaledHeight);

                results.add(new FaceDetectionResult(boundingBox, leftEye, rightEye,
                        noseTip, leftMouth, rightMouth, score));

                Log.d(TAG, String.format("Face: conf=%.3f box=[%.3f,%.3f,%.3f,%.3f]",
                        score, x1, y1, x2, y2));
            }

            // Cleanup
            inputTensor.close();
            output.close();

        } catch (Exception e) {
            Log.e(TAG, "RetinaFace detection failed: " + e.getMessage(), e);
        }

        return results;
    }

    /**
     * Non-Maximum Suppression to remove overlapping detections.
     */
    private List<Integer> applyNMS(List<float[]> boxes, List<Float> scores, float threshold) {
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < scores.size(); i++)
            indices.add(i);

        // Sort by score descending
        Collections.sort(indices, (a, b) -> Float.compare(scores.get(b), scores.get(a)));

        List<Integer> keep = new ArrayList<>();
        boolean[] suppressed = new boolean[indices.size()];

        for (int i = 0; i < indices.size(); i++) {
            if (suppressed[i])
                continue;
            int idx = indices.get(i);
            keep.add(idx);

            float[] boxA = boxes.get(idx);

            for (int j = i + 1; j < indices.size(); j++) {
                if (suppressed[j])
                    continue;
                float[] boxB = boxes.get(indices.get(j));

                float iou = calculateIoU(boxA, boxB);
                if (iou > threshold) {
                    suppressed[j] = true;
                }
            }
        }

        return keep;
    }

    private float calculateIoU(float[] a, float[] b) {
        float x1 = Math.max(a[0], b[0]);
        float y1 = Math.max(a[1], b[1]);
        float x2 = Math.min(a[2], b[2]);
        float y2 = Math.min(a[3], b[3]);

        float intersection = Math.max(0, x2 - x1) * Math.max(0, y2 - y1);
        float areaA = (a[2] - a[0]) * (a[3] - a[1]);
        float areaB = (b[2] - b[0]) * (b[3] - b[1]);
        float union = areaA + areaB - intersection;

        return union > 0 ? intersection / union : 0;
    }

    private String copyModelToCache(Context context, String fileName) throws Exception {
        java.io.File cacheFile = new java.io.File(context.getCacheDir(), fileName);
        if (cacheFile.exists() && cacheFile.length() > 0) {
            Log.d(TAG, "Model already cached: " + fileName);
            return cacheFile.getAbsolutePath();
        }

        Log.d(TAG, "Copying " + fileName + " from assets to cache...");
        InputStream is = context.getAssets().open(fileName);
        java.io.FileOutputStream fos = new java.io.FileOutputStream(cacheFile);
        byte[] buffer = new byte[65536];
        int bytesRead;
        while ((bytesRead = is.read(buffer)) != -1) {
            fos.write(buffer, 0, bytesRead);
        }
        fos.flush();
        fos.close();
        is.close();
        Log.d(TAG, "Model cached: " + fileName + " (" + (cacheFile.length() / 1024 / 1024) + " MB)");
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
