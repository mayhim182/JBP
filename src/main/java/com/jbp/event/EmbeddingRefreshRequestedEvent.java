package com.jbp.event;

import com.jbp.model.EmbeddingOwnerType;

/**
 * Says that an owner's text may have changed and its embedding should be reconsidered.
 *
 * <p>Carries an id, not an entity. The listener runs after the transaction commits and on another
 * thread, so a detached entity handed across that boundary would be a lazy-loading failure waiting to
 * happen — it re-reads what it needs inside its own transaction instead.
 *
 * <p>"Reconsidered", not "regenerated": whether a call is actually needed is {@code EmbeddingStore}'s
 * decision, based on whether the text hashes differently. Publishers do not need to know.
 */
public record EmbeddingRefreshRequestedEvent(EmbeddingOwnerType ownerType, Long ownerId) {
}
