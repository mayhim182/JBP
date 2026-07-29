package com.jbp.serviceimpl;

import com.jbp.exception.LlmUnavailableException;
import com.jbp.service.ChatCompletionClient;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;

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
 * <p>The window slides rather than resetting on a fixed boundary, which stops a burst spanning
 * two adjacent windows from sending twice the limit. Counting is per instance and therefore per
 * application node, which matches the single-node MVP deployment; a multi-node deployment would
 * need the timestamps held somewhere shared.
 */
public class RateLimitedChatClient implements ChatCompletionClient {

    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final ChatCompletionClient delegate;
    private final int maxCallsPerWindow;
    private final Clock clock;
    private final Deque<Long> callTimestamps = new ArrayDeque<>();

    public RateLimitedChatClient(ChatCompletionClient delegate, int maxCallsPerWindow, Clock clock) {
        this.delegate = delegate;
        this.maxCallsPerWindow = maxCallsPerWindow;
        this.clock = clock;
    }

    @Override
    public String complete(String systemPrompt, String userMessage) {
        if (!tryReserveCallSlot()) {
            throw new LlmUnavailableException(
                    "Model call limit of " + maxCallsPerWindow + " per minute reached", false);
        }
        return delegate.complete(systemPrompt, userMessage);
    }

    /**
     * Records a call against the current window, or reports that the window is full.
     * Synchronized because concurrent requests share the timestamp history.
     */
    private synchronized boolean tryReserveCallSlot() {
        long now = clock.millis();
        discardCallsOlderThanWindow(now);
        if (callTimestamps.size() >= maxCallsPerWindow) {
            return false;
        }
        callTimestamps.addLast(now);
        return true;
    }

    private void discardCallsOlderThanWindow(long now) {
        long windowStart = now - WINDOW.toMillis();
        while (!callTimestamps.isEmpty() && callTimestamps.peekFirst() <= windowStart) {
            callTimestamps.pollFirst();
        }
    }
}
