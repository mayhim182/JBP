package com.jbp.serviceimpl;

import com.jbp.exception.LlmUnavailableException;
import com.jbp.service.ChatCompletionClient;

/**
 * Stands in for the provider when {@code app.ai.enabled=false}, reporting the AI layer as
 * unavailable on every call.
 *
 * <p>No provider is configured, contacted or required: there is no HTTP client, no API key and
 * no model name involved, so the application starts cleanly with AI switched off — which is the
 * default, and the only sane state for a developer machine or a test run without a key.
 *
 * <p>Being a client rather than an absent bean is what keeps the features simple. Every AI
 * feature already handles {@link LlmUnavailableException} by falling back to its non-AI
 * behaviour, so switching the layer off exercises that same path instead of forcing every
 * caller to also handle a missing dependency.
 */
public class DisabledChatClient implements ChatCompletionClient {

    @Override
    public String complete(String systemPrompt, String userMessage) {
        throw new LlmUnavailableException("AI features are disabled", false);
    }
}
