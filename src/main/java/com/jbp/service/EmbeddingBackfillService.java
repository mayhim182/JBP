package com.jbp.service;

/**
 * Fills in embeddings for records that already existed before Story 13.2, or that were missed because
 * the provider was unavailable at the time.
 *
 * <p>Safe to run repeatedly: {@link EmbeddingStore} skips anything whose text already hashes to what is
 * stored, so a second run over the same data makes no provider calls at all. That is what "idempotent"
 * buys — the operator never has to work out where the last run stopped.
 */
public interface EmbeddingBackfillService {

    /** Every PUBLISHED job. Drafts and jobs awaiting moderation are excluded — nothing can match them. */
    BackfillSummary backfillPublishedJobs();

    /** Every candidate profile. */
    BackfillSummary backfillCandidateProfiles();

    /**
     * @param scanned      records examined
     * @param embedded     records that actually needed a provider call
     * @param stoppedEarly true when the run halted because the provider refused — almost always the
     *                     rate limit. The remaining records keep their old or absent vectors, which is
     *                     harmless, and the next run resumes from where this one gave up without being
     *                     told where that was.
     */
    record BackfillSummary(int scanned, int embedded, boolean stoppedEarly) {
    }
}
