package com.jbp.util;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VectorCodecTest {

    @Test
    void roundTripsAVectorExactly() {
        float[] vector = {0.6f, 0.0f, -0.8f, 1.0f};

        assertThat(VectorCodec.toFloats(VectorCodec.toBytes(vector)))
                .as("float32 in, float32 out — no precision is lost by the storage format itself")
                .containsExactly(vector);
    }

    @Test
    void usesFourBytesPerComponent() {
        assertThat(VectorCodec.toBytes(new float[768]))
                .as("768 dimensions must be 3,072 bytes, which is the whole argument for BLOB over JSON")
                .hasSize(3072);
    }

    @Test
    void writesBigEndianBecauseThatIsTheOnDiskContract() {
        byte[] bytes = VectorCodec.toBytes(new float[]{1.0f});

        assertThat(ByteBuffer.wrap(bytes).getFloat()).isEqualTo(1.0f);
        assertThat(bytes[0])
                .as("1.0f is 0x3F800000, so a big-endian first byte is 0x3F")
                .isEqualTo((byte) 0x3F);
    }

    @Test
    void rejectsBytesThatAreNotAWholeNumberOfFloats() {
        assertThatThrownBy(() -> VectorCodec.toFloats(new byte[]{1, 2, 3}))
                .as("a shifted vector read silently would be far worse than a failure")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a whole number");
    }

    @Test
    void refusesToStoreOrReadNothing() {
        assertThatThrownBy(() -> VectorCodec.toBytes(new float[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VectorCodec.toFloats(new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
