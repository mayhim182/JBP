package com.jbp.util;

/**
 * Cosine similarity between two embeddings, computed in the application.
 *
 * <p>A bare dot product, which is only cosine because {@code EmbeddingClient} guarantees unit-length
 * vectors — that guarantee is why {@code GeminiEmbeddingClient} renormalises truncated output, and why
 * this class does not divide by two magnitudes on every comparison. At a page of jobs per request that
 * saving is the difference the contract was written for.
 *
 * <p>No vector database, per Story 13.2: 768 multiply-adds is on the order of a microsecond, and the
 * cost that matters is loading the vectors, not multiplying them.
 */
public final class CosineSimilarity {

    private CosineSimilarity() {
    }

    /**
     * @return cosine in [-1, 1] for unit vectors; in practice this model returns roughly [0.5, 1.0]
     * @throws IllegalArgumentException if the lengths differ, which means one was produced by a
     *                                 different configuration and the result would be meaningless.
     *                                 Story 13.2 keeps a {@code dimension} column precisely so callers
     *                                 can treat that as stale rather than reach this exception.
     */
    public static double between(float[] first, float[] second) {
        if (first == null || second == null) {
            throw new IllegalArgumentException("Cannot compare a missing vector");
        }
        if (first.length != second.length) {
            throw new IllegalArgumentException("Cannot compare a " + first.length
                    + "-dimension vector with a " + second.length + "-dimension one");
        }
        double total = 0;
        for (int axis = 0; axis < first.length; axis++) {
            total += (double) first[axis] * second[axis];
        }
        return total;
    }
}
