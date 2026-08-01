package com.jbp.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class CosineSimilarityTest {

    @Test
    void scoresIdenticalUnitVectorsAsOne() {
        float[] unit = {0.6f, 0.0f, 0.8f};

        assertThat(CosineSimilarity.between(unit, unit)).isCloseTo(1.0, within(1.0e-6));
    }

    @Test
    void scoresPerpendicularUnitVectorsAsZero() {
        assertThat(CosineSimilarity.between(new float[]{1, 0, 0}, new float[]{0, 1, 0}))
                .isCloseTo(0.0, within(1.0e-6));
    }

    @Test
    void scoresOppositeUnitVectorsAsMinusOne() {
        assertThat(CosineSimilarity.between(new float[]{1, 0, 0}, new float[]{-1, 0, 0}))
                .isCloseTo(-1.0, within(1.0e-6));
    }

    @Test
    void isSymmetricAndDeterministic() {
        float[] first = {0.6f, 0.0f, 0.8f};
        float[] second = {0.0f, 1.0f, 0.0f};

        assertThat(CosineSimilarity.between(first, second))
                .isEqualTo(CosineSimilarity.between(second, first));
    }

    @Test
    void refusesVectorsOfDifferentLengths() {
        assertThatThrownBy(() -> CosineSimilarity.between(new float[768], new float[3072]))
                .as("vectors from different configurations have no shared meaning to compare")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("768-dimension vector with a 3072-dimension one");
    }

    @Test
    void refusesAMissingVector() {
        assertThatThrownBy(() -> CosineSimilarity.between(null, new float[3]))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
