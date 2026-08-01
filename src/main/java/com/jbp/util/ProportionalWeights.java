package com.jbp.util;

/**
 * Rescales a set of weights onto a smaller budget so that they still sum to it exactly.
 *
 * <p>Story 13.4 needs this because the hybrid scorer gives meaning a share of the total and the four
 * rule dimensions the rest. Scaling 50/20/15/15 by 0.7 gives 35/14/10.5/10.5, and rounding each of
 * those independently produces 35/14/11/11 — a total of 71. Design 20 draws a weight against every row
 * and Story 13.3 asserts the weights sum to 100, so a single stray point makes the panel wrong.
 *
 * <p><strong>The last entry absorbs the residual.</strong> Every entry is rounded half-up, then the
 * final one is set to whatever is left of the budget. Deterministic, and it reproduces design 20
 * exactly: 35 + 14 + 11 leaves 10, which is the number the design already shows against experience.
 * The alternative — largest-remainder apportionment — would give the same answer here and cost more to
 * explain, so it is not worth the machinery.
 *
 * <p>Depends on nothing but arithmetic, which is why it lives in {@code util} and can be tested on its
 * own rather than through a scorer.
 */
public final class ProportionalWeights {

    private ProportionalWeights() {
    }

    /**
     * @param weights each entry's share, in display order. Must be non-empty and sum to more than zero.
     * @param budget  what the returned weights must sum to. Must not be negative.
     * @return new weights, same length and order, summing to exactly {@code budget}
     */
    public static int[] scale(int[] weights, int budget) {
        if (weights == null || weights.length == 0) {
            throw new IllegalArgumentException("Cannot scale an empty set of weights");
        }
        if (budget < 0) {
            throw new IllegalArgumentException("Budget must not be negative, was " + budget);
        }
        int total = 0;
        for (int weight : weights) {
            total += weight;
        }
        if (total <= 0) {
            throw new IllegalArgumentException("Weights must sum to more than zero, summed to " + total);
        }

        int[] scaled = new int[weights.length];
        int allocated = 0;
        for (int index = 0; index < weights.length - 1; index++) {
            scaled[index] = (int) Math.round((double) weights[index] * budget / total);
            allocated += scaled[index];
        }
        // Never negative: a caller asking for a budget smaller than the rounding drift would otherwise
        // get a weight below zero, and a negative bar is worse than a last row of nothing.
        scaled[weights.length - 1] = Math.max(0, budget - allocated);
        return scaled;
    }
}
