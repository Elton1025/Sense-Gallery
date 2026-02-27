package com.example.sensegallery.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;

import androidx.annotation.NonNull;

import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool;
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation;

import java.security.MessageDigest;

/**
 * A custom Glide mapping that extracts a specific bounding box (face)
 * out of a full image and pads it into a perfect square for avatar rendering.
 */
public class FaceCropTransformation extends BitmapTransformation {

    private final float boxLeft;
    private final float boxTop;
    private final float boxRight;
    private final float boxBottom;
    private final float paddingPercentage; // e.g., 0.5f for 50% extra space around the face

    public FaceCropTransformation(float boxLeft, float boxTop, float boxRight, float boxBottom,
            float paddingPercentage) {
        this.boxLeft = boxLeft;
        this.boxTop = boxTop;
        this.boxRight = boxRight;
        this.boxBottom = boxBottom;
        this.paddingPercentage = paddingPercentage;
    }

    @Override
    protected Bitmap transform(@NonNull BitmapPool pool, @NonNull Bitmap toTransform, int outWidth, int outHeight) {
        if (boxLeft == 0f && boxTop == 0f && boxRight == 1f && boxBottom == 1f) {
            // Full image fallback, no transformation needed
            return toTransform;
        }

        int width = toTransform.getWidth();
        int height = toTransform.getHeight();

        // Convert normalized bounds (0-1) to actual pixel coordinates
        float pixelLeft = boxLeft * width;
        float pixelTop = boxTop * height;
        float pixelRight = boxRight * width;
        float pixelBottom = boxBottom * height;

        float faceWidth = pixelRight - pixelLeft;
        float faceHeight = pixelBottom - pixelTop;

        // Apply Padding
        float padX = faceWidth * paddingPercentage;
        float padY = faceHeight * paddingPercentage;

        float paddedLeft = Math.max(0, pixelLeft - padX);
        float paddedTop = Math.max(0, pixelTop - padY);
        float paddedRight = Math.min(width, pixelRight + padX);
        float paddedBottom = Math.min(height, pixelBottom + padY);

        // Make it a perfect Square by taking the maximum dimension size
        float finalWidth = paddedRight - paddedLeft;
        float finalHeight = paddedBottom - paddedTop;
        float size = Math.max(finalWidth, finalHeight);

        // Re-center the square over the face exactly
        float cx = (paddedLeft + paddedRight) / 2f;
        float cy = (paddedTop + paddedBottom) / 2f;

        float squareLeft = Math.max(0, cx - (size / 2f));
        float squareTop = Math.max(0, cy - (size / 2f));
        float squareRight = Math.min(width, cx + (size / 2f));
        float squareBottom = Math.min(height, cy + (size / 2f));

        Rect srcRect = new Rect((int) squareLeft, (int) squareTop, (int) squareRight, (int) squareBottom);
        int finalCropSize = Math.max(srcRect.width(), srcRect.height());

        // Construct empty bitmap perfectly sized for the final crop
        Bitmap result = pool.get(finalCropSize, finalCropSize, Bitmap.Config.ARGB_8888);
        if (result == null) {
            result = Bitmap.createBitmap(finalCropSize, finalCropSize, Bitmap.Config.ARGB_8888);
        }

        Canvas canvas = new Canvas(result);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        // Draw the exact subsection into the empty square
        Rect destRect = new Rect(0, 0, finalCropSize, finalCropSize);
        canvas.drawBitmap(toTransform, srcRect, destRect, paint);

        return result;
    }

    @Override
    public void updateDiskCacheKey(@NonNull MessageDigest messageDigest) {
        String id = "com.example.sensegallery.ui.FaceCropTransformation-"
                + boxLeft + "-" + boxTop + "-" + boxRight + "-" + boxBottom;
        messageDigest.update(id.getBytes(CHARSET));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        FaceCropTransformation that = (FaceCropTransformation) o;
        return Float.compare(that.boxLeft, boxLeft) == 0 &&
                Float.compare(that.boxTop, boxTop) == 0 &&
                Float.compare(that.boxRight, boxRight) == 0 &&
                Float.compare(that.boxBottom, boxBottom) == 0;
    }

    @Override
    public int hashCode() {
        int result = (boxLeft != +0.0f ? Float.floatToIntBits(boxLeft) : 0);
        result = 31 * result + (boxTop != +0.0f ? Float.floatToIntBits(boxTop) : 0);
        result = 31 * result + (boxRight != +0.0f ? Float.floatToIntBits(boxRight) : 0);
        result = 31 * result + (boxBottom != +0.0f ? Float.floatToIntBits(boxBottom) : 0);
        return result;
    }
}
