package com.jbp.event;

/**
 * Says that a job has entered moderation and should be assessed.
 *
 * <p>Carries an id, not an entity, for the reason {@link EmbeddingRefreshRequestedEvent} does: the
 * listener runs after the transaction commits and on another thread, where a detached entity would be
 * a lazy-loading failure waiting to happen.
 */
public record JobModerationRequestedEvent(Long jobId) {
}
