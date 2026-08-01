package com.jbp.service;

import com.jbp.exception.LlmUnavailableException;
import com.jbp.model.EmbeddingOwnerType;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * Stores and reads embeddings, re-embedding only when the text behind one has actually changed.
 *
 * <p>The quota rule lives here rather than in each caller: a job edited without touching its wording,
 * or a profile saved twice, costs nothing. Callers hand over text and this decides whether that needs a
 * provider call.
 *
 * <p>Reads return a vector only when it is <strong>current</strong> — same model, same dimension as the
 * running configuration. A mismatch reads as absent, which is the behaviour Story 13.3 needs: absent
 * means fall back to the rule scorer, and that is strictly better than comparing two vectors from
 * different models and reporting the meaningless number it produces.
 */
public interface EmbeddingStore {

    /**
     * Embeds and stores {@code sourceText} for one owner, if it differs from what is already stored.
     *
     * @throws LlmUnavailableException if a call was needed and the provider could not serve it
     */
    void refresh(EmbeddingOwnerType ownerType, Long ownerId, String sourceText);

    /**
     * The batch form, and the one bulk work must use.
     *
     * <p>Owners whose text is unchanged are skipped without a call; the remainder go to the provider in
     * <strong>a single request</strong>, which is one rate-limit slot rather than one per row. That is
     * what makes the backfill finish inside the free tier.
     *
     * <p>Blank text is skipped rather than rejected — a job with no title or description is a real state
     * in the database and must not stop a run over thousands of rows.
     *
     * @param sourceTextsByOwnerId owner id → the text to embed for it
     * @return how many owners were actually embedded, so a caller can report progress honestly
     * @throws LlmUnavailableException if the provider could not serve the batch. Deliberately propagated
     *                                rather than swallowed: the backfill needs to know it has hit the
     *                                rate limit so it can stop, while the event listener catches it and
     *                                leaves the vector missing, which is harmless.
     */
    int refreshAll(EmbeddingOwnerType ownerType, Map<Long, String> sourceTextsByOwnerId);

    /** The current vector for one owner, or empty if absent or stale. */
    Optional<float[]> findVector(EmbeddingOwnerType ownerType, Long ownerId);

    /**
     * Current vectors for several owners in one query. Owners without a current vector are simply absent
     * from the map, so a caller can score what it has and fall back for the rest.
     */
    Map<Long, float[]> findVectors(EmbeddingOwnerType ownerType, Collection<Long> ownerIds);
}
