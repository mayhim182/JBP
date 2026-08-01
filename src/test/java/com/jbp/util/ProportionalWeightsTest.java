package com.jbp.util;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/** Story 13.4 — the weight arithmetic design 20's bars depend on. */
class ProportionalWeightsTest {

    /** The rule scorer's weights, in the order {@code factorsFor} returns them. */
    private static final int[] RULE_WEIGHTS = {50, 20, 15, 15};

    @Test
    void reproducesTheWeightsDesignTwentyAlreadyShows() {
        assertThat(ProportionalWeights.scale(RULE_WEIGHTS, 70))
                .as("the design draws 35 / 14 / 11 / 10 beside a semantic row of 30")
                .containsExactly(35, 14, 11, 10);
    }

    @Test
    void sumsToTheBudgetExactlyRatherThanApproximately() {
        // Independent rounding gives 35 + 14 + 11 + 11 = 71, which is the bug this class exists for.
        assertThat(ProportionalWeights.scale(RULE_WEIGHTS, 70)).containsExactly(35, 14, 11, 10);
        assertThat(sumOf(ProportionalWeights.scale(RULE_WEIGHTS, 70))).isEqualTo(70);
    }

    @Test
    void sumsToTheBudgetForEveryBudgetAScorerCouldBeConfiguredWith() {
        for (int budget = 0; budget <= 100; budget++) {
            assertThat(sumOf(ProportionalWeights.scale(RULE_WEIGHTS, budget)))
                    .as("budget %d", budget)
                    .isEqualTo(budget);
        }
    }

    @Test
    void leavesWeightsUntouchedWhenTheBudgetIsTheirOwnTotal() {
        assertThat(ProportionalWeights.scale(RULE_WEIGHTS, 100)).containsExactly(50, 20, 15, 15);
    }

    @Test
    void givesASingleFactorTheWholeBudget() {
        assertThat(ProportionalWeights.scale(new int[]{100}, 30)).containsExactly(30);
    }

    @Test
    void neverReturnsANegativeWeightEvenWhenTheBudgetIsSmallerThanTheRoundingDrift() {
        int smallestWeight = Arrays.stream(ProportionalWeights.scale(new int[]{1, 1, 1, 97}, 1))
                .min()
                .orElseThrow();

        assertThat(smallestWeight)
                .as("a negative bar would be worse than an empty last row")
                .isNotNegative();
    }

    @Test
    void rejectsAnEmptySetOfWeights() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ProportionalWeights.scale(new int[0], 70))
                .withMessageContaining("empty");
    }

    @Test
    void rejectsANegativeBudget() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ProportionalWeights.scale(RULE_WEIGHTS, -1))
                .withMessageContaining("negative");
    }

    @Test
    void rejectsWeightsThatSumToZeroRatherThanDividingByIt() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ProportionalWeights.scale(new int[]{0, 0}, 70))
                .withMessageContaining("more than zero");
    }

    private int sumOf(int[] weights) {
        int total = 0;
        for (int weight : weights) {
            total += weight;
        }
        return total;
    }
}
