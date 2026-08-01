package com.jbp.serviceimpl;

import com.jbp.exception.LlmUnavailableException;
import com.jbp.service.EmbeddingClient;

import java.util.List;

/**
 * Stands in for the provider when {@code app.ai.enabled=false}, reporting the AI layer as
 * unavailable on every call. The embedding twin of {@link DisabledChatClient}.
 *
 * <p>Being a client rather than an absent bean is what keeps callers simple: semantic scoring falls
 * back to the rule-based scorer on {@link LlmUnavailableException} anyway, so switching the layer
 * off exercises that same path instead of forcing every caller to also handle a missing dependency.
 *
 * <p>An empty batch still reports unavailable rather than returning an empty list. With AI off the
 * honest answer to "embed these" is "there is no embedder", and returning empty would let a caller
 * mistake a switched-off provider for a completed run that found nothing to do.
 */
public class DisabledEmbeddingClient implements EmbeddingClient {

    @Override
    public float[] embed(String text) {
        throw new LlmUnavailableException("AI features are disabled", false);
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        throw new LlmUnavailableException("AI features are disabled", false);
    }
}
