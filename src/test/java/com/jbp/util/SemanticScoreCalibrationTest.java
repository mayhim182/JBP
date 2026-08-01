package com.jbp.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 13.3 AC 1 — cosine is calibrated, never passed through.
 *
 * <p>The two numbers in the first test are real: measured against {@code gemini-embedding-001} on
 * 2026-07-31. They are the reason this class exists.
 */
class SemanticScoreCalibrationTest {

    private static final double RELATED_PAIR = 0.677;      // frontend engineer ~ react developer
    private static final double UNRELATED_PAIR = 0.534;    // frontend engineer ~ diesel mechanic

    private final SemanticScoreCalibration calibration = new SemanticScoreCalibration(0.55, 0.90);

    @Test
    void scoresAnUnrelatedPairAtZeroRatherThanFiftyThree() {
        assertThat(calibration.toScore(UNRELATED_PAIR))
                .as("a raw cosine would have called a diesel-mechanic job a 53%% match")
                .isZero();
    }

    @Test
    void separatesTheRelatedPairClearlyFromTheUnrelatedOne() {
        int related = calibration.toScore(RELATED_PAIR);
        int unrelated = calibration.toScore(UNRELATED_PAIR);

        assertThat(related)
                .as("0.677 sits about a third of the way up the usable band")
                .isBetween(30, 45);
        assertThat(related - unrelated)
                .as("raw cosine separated these by 14 points; calibration must widen that, not keep it")
                .isGreaterThan(30);
    }

    @Test
    void mapsTheBandEnds() {
        assertThat(calibration.toScore(0.55)).isZero();
        assertThat(calibration.toScore(0.90)).isEqualTo(100);
        assertThat(calibration.toScore(0.725)).isEqualTo(50);
    }

    @Test
    void clampsOutsideTheBand() {
        assertThat(calibration.toScore(0.10)).isZero();
        assertThat(calibration.toScore(-1.0)).isZero();
        assertThat(calibration.toScore(1.0)).isEqualTo(100);
    }

    @Test
    void isDeterministic() {
        assertThat(calibration.toScore(RELATED_PAIR)).isEqualTo(calibration.toScore(RELATED_PAIR));
    }

    @Test
    void refusesABandThatIsNotABand() {
        assertThatThrownBy(() -> new SemanticScoreCalibration(0.90, 0.55))
                .as("an inverted band would map better matches to lower scores, silently")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must exceed the floor");
        assertThatThrownBy(() -> new SemanticScoreCalibration(0.7, 0.7))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
