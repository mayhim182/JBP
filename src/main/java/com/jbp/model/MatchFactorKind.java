package com.jbp.model;

/**
 * The dimensions a match score is built from.
 *
 * <p>An enum rather than free text because design 20 draws one row per factor and Story 13.5 feeds the
 * breakdown to a model — both need a stable key, not a label that can be reworded.
 */
public enum MatchFactorKind {
    SKILLS("Skills"),
    SENIORITY("Seniority"),
    LOCATION("Location"),
    EXPERIENCE("Experience"),

    /**
     * Cosine similarity between the stored embeddings. Absent when no scorer contributes meaning.
     *
     * <p>Labelled <strong>"Role similarity"</strong>, which design 20 calls out explicitly: a candidate
     * cannot act on "cosine similarity 0.86". The label lives beside the constant rather than in the
     * frontend so the name and the thing it names cannot drift apart, matching how {@code detail} is
     * already produced server-side.
     */
    SEMANTIC("Role similarity");

    private final String label;

    MatchFactorKind(String label) {
        this.label = label;
    }

    /** The row heading design 20 draws for this factor. */
    public String getLabel() {
        return label;
    }
}
