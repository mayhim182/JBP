package com.jbp.config;

/**
 * How alike two postings must be before one is called a near-duplicate, and how many to report.
 *
 * <p>A bean rather than a {@code @Value} on the detector, so every {@code app.duplicate-detection.*}
 * key is read in {@link DuplicateDetectionConfig} and nowhere else — the rule Story 11.1 set and
 * {@link AiTaskBudget} already follows.
 *
 * @param similarityThreshold cosine above which two postings are reported as near-duplicates
 * @param maxReported         how many to name, most similar first
 */
public record DuplicateDetectionSettings(double similarityThreshold, int maxReported) {
}
