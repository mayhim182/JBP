package com.jbp.serviceimpl;

import com.jbp.exception.LlmUnavailableException;
import com.jbp.service.EmbeddingClient;
import com.jbp.util.CallRateLimiter;

import java.util.List;

/**
 * Caps how many embedding calls leave the application per minute, protecting the Gemini free-tier
 * quota. The embedding twin of {@link RateLimitedChatClient}, sharing its window implementation
 * through {@link CallRateLimiter}.
 *
 * <p><strong>One batch is one call.</strong> Embedding fifty texts together consumes a single slot,
 * embedding them one at a time consumes fifty — which is why {@code embedAll} exists and why
 * Story 13.2's backfill has to use it. The limiter counts provider round trips, because that is
 * what the provider counts.
 *
 * <p>Whether this shares its budget with the chat transport is decided in
 * {@code AiClientConfig}. They are wired separately today because the free tier documents quota per
 * model, and chat and embeddings are different models; one shared instance is the one-line change
 * if that turns out to be wrong.
 */
public class RateLimitedEmbeddingClient implements EmbeddingClient {

    private final EmbeddingClient delegate;
    private final CallRateLimiter rateLimiter;

    public RateLimitedEmbeddingClient(EmbeddingClient delegate, CallRateLimiter rateLimiter) {
        this.delegate = delegate;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public float[] embed(String text) {
        reserveCallSlotOrFail();
        return delegate.embed(text);
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        reserveCallSlotOrFail();
        return delegate.embedAll(texts);
    }

    private void reserveCallSlotOrFail() {
        if (!rateLimiter.tryReserveCallSlot()) {
            throw new LlmUnavailableException(
                    "Embedding call limit of " + rateLimiter.maxCallsPerWindow()
                            + " per minute reached", false);
        }
    }
}
