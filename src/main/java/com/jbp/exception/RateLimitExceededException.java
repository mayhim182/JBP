package com.jbp.exception;

/**
 * A per-user allowance is spent — answered 429.
 *
 * <p>Distinct from {@link LlmUnavailableException}, which means "the model could not be reached".
 * This means "you may not ask again yet", and the two lead to opposite advice: retry now, versus
 * come back tomorrow. Story 14.2's apply dialog renders them as design 22b's F and D respectively.
 *
 * <p>Never a blocker on the surrounding action. A candidate who has spent their drafts can still
 * write every answer themselves and still submit the application — a cost control that stopped
 * someone applying would be a worse bug than the cost it was protecting against.
 */
public class RateLimitExceededException extends RuntimeException {

    public RateLimitExceededException(String message) {
        super(message);
    }
}
