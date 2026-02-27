package com.example.sensegallery.ai;

import android.graphics.PointF;
import android.graphics.RectF;

/**
 * Holds the result of a face detection: bounding box and 5-point landmarks.
 *
 * RetinaFace provides 5 landmarks:
 * 0: Left eye center
 * 1: Right eye center
 * 2: Nose tip
 * 3: Left mouth corner
 * 4: Right mouth corner
 *
 * All coordinates are normalized to [0, 1] relative to the original image.
 */
public class FaceDetectionResult {
    public final RectF boundingBox;
    public final PointF leftEye;
    public final PointF rightEye;
    public final PointF nose;
    public final PointF mouth; // kept for backward compatibility (mouth center)
    public final PointF leftMouth; // left mouth corner (RetinaFace)
    public final PointF rightMouth; // right mouth corner (RetinaFace)
    public final float confidence;

    /** Legacy constructor (4 landmarks, BlazeFace compatible) */
    public FaceDetectionResult(RectF boundingBox, PointF leftEye, PointF rightEye,
            PointF nose, PointF mouth) {
        this(boundingBox, leftEye, rightEye, nose, mouth, null, null, 1.0f);
    }

    /** Full constructor (5 landmarks from RetinaFace) */
    public FaceDetectionResult(RectF boundingBox, PointF leftEye, PointF rightEye,
            PointF nose, PointF leftMouth, PointF rightMouth,
            float confidence) {
        this.boundingBox = boundingBox;
        this.leftEye = leftEye;
        this.rightEye = rightEye;
        this.nose = nose;
        this.mouth = leftMouth; // backward compat
        this.leftMouth = leftMouth;
        this.rightMouth = rightMouth;
        this.confidence = confidence;
    }

    /** Private full constructor */
    private FaceDetectionResult(RectF boundingBox, PointF leftEye, PointF rightEye,
            PointF nose, PointF mouth, PointF leftMouth,
            PointF rightMouth, float confidence) {
        this.boundingBox = boundingBox;
        this.leftEye = leftEye;
        this.rightEye = rightEye;
        this.nose = nose;
        this.mouth = mouth;
        this.leftMouth = leftMouth;
        this.rightMouth = rightMouth;
        this.confidence = confidence;
    }

    /** Returns true if all 5 RetinaFace landmarks are available */
    public boolean has5Landmarks() {
        return leftEye != null && rightEye != null && nose != null
                && leftMouth != null && rightMouth != null;
    }
}
