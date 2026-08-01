package com.jbp.config;

/**
 * The embedding model and size the application is currently configured to use.
 *
 * <p>A carrier so that {@code app.ai.*} keys stay read in {@code AiClientConfig} and nowhere else —
 * the same reason {@code AiTaskBudget} exists. Services take this and remain free of Spring's property
 * annotations, which is also what keeps them directly unit-testable.
 *
 * <p>Both values are recorded on every stored vector, so a change to either makes existing rows stale
 * rather than silently incomparable.
 */
public record EmbeddingSettings(String model, int dimension) {
}
