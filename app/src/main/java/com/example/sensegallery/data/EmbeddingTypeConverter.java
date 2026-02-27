package com.example.sensegallery.data;

import androidx.room.TypeConverter;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Room TypeConverter to convert float[] embeddings (from TFLite models)
 * into byte[] BLOBs for efficient Room/SQLite storage, and vice-versa.
 *
 * Uses IEEE 754 little-endian encoding (4 bytes per float).
 */
public class EmbeddingTypeConverter {

    @TypeConverter
    public static byte[] fromFloatArray(float[] floats) {
        if (floats == null) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.allocate(floats.length * 4);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.asFloatBuffer().put(floats);
        return buffer.array();
    }

    @TypeConverter
    public static float[] toFloatArray(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        float[] floats = new float[bytes.length / 4];
        buffer.asFloatBuffer().get(floats);
        return floats;
    }
}
