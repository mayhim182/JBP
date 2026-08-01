package com.jbp.serviceimpl;

import com.jbp.exception.LlmUnavailableException;
import com.jbp.service.ChatCompletionClient;
import com.jbp.util.CallRateLimiter;

/**
 * Caps how many model calls leave the application per minute, protecting the Gemini free-tier
 * quota. Wraps any {@link ChatCompletionClient} and adds nothing but the limit, so the provider
 * client stays free of throttling concerns.
 *
 * <p>Rejecting locally is deliberately cheaper than being rejected by the provider: no request
 * leaves the process, no quota is consumed, and the caller sees the same
 * {@link LlmUnavailableException} it already handles, so it falls back exactly as it would for
 * any other model failure.
 *
 * <p>The window itself lives in {@link CallRateLimiter}, shared with the embedding transport
 * rather than written twice. Taking the limiter as a collaborator also means sharing one budget
 * across transports is a wiring choice in {@code AiClientConfig}, not a rewrite here.
 */
public class RateLimitedChatClient implements ChatCompletionClient {

    private final ChatCompletionClient delegate;
    private final CallRateLimiter rateLimiter;

    public RateLimitedChatClient(ChatCompletionClient delegate, CallRateLimiter rateLimiter) {
        this.delegate = delegate;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public String complete(String systemPrompt, String userMessage) {
        if (!rateLimiter.tryReserveCallSlot()) {
            throw new LlmUnavailableException(
                    "Model call limit of " + rateLimiter.maxCallsPerWindow() + " per minute reached",
                    false);
        }
        return delegate.complete(systemPrompt, userMessage);
    }
}
