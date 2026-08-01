package com.jbp.model;

/**
 * What a stored embedding belongs to.
 *
 * <p>A discriminator rather than one table per owner, so the storage, staleness and backfill logic is
 * written once. Jobs and profiles differ only in how their text is built — see
 * {@code EmbeddingTexts} — and that difference does not belong in the persistence layer.
 */
public enum EmbeddingOwnerType {
    JOB,
    CANDIDATE_PROFILE
}
