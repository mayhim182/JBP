package com.jbp.util;

import java.nio.ByteBuffer;

/**
 * Converts an embedding between {@code float[]} and the bytes stored in the database.
 *
 * <p><strong>float32, big-endian</strong> — {@link ByteBuffer}'s default order, stated here because it
 * is the on-disk format and therefore a contract, not an implementation detail. Anything that ever
 * reads these rows outside this class has to agree with it.
 *
 * <p>Chosen over JSON: 768 floats is <strong>3,072 bytes</strong> here against roughly 11KB as text,
 * and reading is a single {@code asFloatBuffer()} rather than parsing 768 decimal numbers on every
 * comparison. At the scale Story 13.3 does comparisons in, that difference is the whole point.
 */
public final class VectorCodec {

    private VectorCodec() {
    }

    public static byte[] toBytes(float[] vector) {
        if (vector == null || vector.length == 0) {
            throw new IllegalArgumentException("Cannot store an empty vector");
        }
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES);
        buffer.asFloatBuffer().put(vector);
        return buffer.array();
    }

    /**
     * @throws IllegalArgumentException if the byte count is not a whole number of floats, which means
     *                                 the row was written by something that did not agree with this
     *                                 format — better to fail than to return a silently shifted vector
     */
    public static float[] toFloats(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("Cannot read a vector from no bytes");
        }
        if (bytes.length % Float.BYTES != 0) {
            throw new IllegalArgumentException(
                    "Stored vector is " + bytes.length + " bytes, which is not a whole number of "
                            + Float.BYTES + "-byte floats");
        }
        float[] vector = new float[bytes.length / Float.BYTES];
        ByteBuffer.wrap(bytes).asFloatBuffer().get(vector);
        return vector;
    }
}
