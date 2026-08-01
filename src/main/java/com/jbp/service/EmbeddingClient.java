package com.jbp.service;

import com.jbp.exception.LlmUnavailableException;

import java.util.List;

/**
 * Turns text into a vector whose direction encodes meaning, so two texts can be compared by what
 * they mean rather than by which words they share.
 *
 * <p>The transport-layer twin of {@link ChatCompletionClient}, and deliberately just as small.
 * Callers get vectors; which provider produced them, at what size, over what wire format, is
 * known only to the implementation.
 *
 * <p><strong>Every returned vector is unit length.</strong> That is part of this contract, not an
 * implementation detail, because it is what lets similarity be computed as a plain dot product
 * instead of dividing by two magnitudes on every comparison — the difference matters when one
 * request compares a candidate against a page of jobs. Implementations normalise before
 * returning; callers may assume it and must not re-normalise.
 *
 * <p>All vectors from one configured provider have the same length, so callers may compare any two
 * of them. Vectors from *different* configurations may not be compared, which is why Story 13.2
 * stores the dimension alongside the vector and treats a mismatch as stale.
 */
public interface EmbeddingClient {

    /**
     * @param text the content to embed; must not be blank
     * @return a unit-length vector, never null
     * @throws IllegalArgumentException if {@code text} is null or blank — a caller asking to embed
     *                                 nothing is a bug in the caller, not a provider outage, and
     *                                 must not be swallowed by a fallback
     * @throws LlmUnavailableException if the provider could not be reached, timed out, was rate
     *                                limited, returned an unusable vector, or the AI layer is off
     */
    float[] embed(String text);

    /**
     * Embeds several texts in one request.
     *
     * <p>The returned list is positionally aligned with {@code texts}: element <i>n</i> is the
     * vector for input <i>n</i>. Implementations are responsible for that alignment even if the
     * provider answers out of order.
     *
     * <p>This is the method bulk work should use. One batch is one provider call and therefore one
     * rate-limit slot, which is the whole reason Story 13.2's backfill can complete inside the free
     * tier.
     *
     * @param texts the contents to embed; none may be blank. An empty list returns an empty list
     *              without contacting the provider.
     * @return one unit-length vector per input, in input order
     * @throws IllegalArgumentException if any element is null or blank
     * @throws LlmUnavailableException on any provider failure, as above
     */
    List<float[]> embedAll(List<String> texts);
}
