package com.jbp.exception;

/**
 * Thrown when a language-model call cannot be completed — provider error, timeout,
 * rate limit, or the AI layer being switched off.
 *
 * <p>This is the single failure type AI features handle: they catch it and fall back to
 * their non-AI behaviour, so a model outage never fails the user's action. Callers do not
 * need to know which provider was configured or how it failed.
 *
 * <p>{@code retryable} distinguishes transient failures (timeout, 5xx) from permanent ones
 * (bad request, invalid key), so a caller or decorator can retry without inspecting causes.
 */
public class LlmUnavailableException extends RuntimeException {

    private final boolean retryable;

    public LlmUnavailableException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public LlmUnavailableException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
