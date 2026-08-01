package com.jbp.repository;

import com.jbp.model.EmbeddingOwnerType;
import com.jbp.model.EmbeddingVector;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface EmbeddingVectorRepository extends JpaRepository<EmbeddingVector, Long> {

    Optional<EmbeddingVector> findByOwnerTypeAndOwnerId(EmbeddingOwnerType ownerType, Long ownerId);

    /**
     * Every stored vector for a set of owners, in one query.
     *
     * <p>This is what Story 13.3 scores a page with, and what the backfill uses to decide which rows
     * still need work — asking per owner would put the N+1 that Story 13.0 removed from the HTTP layer
     * straight back into the database layer.
     */
    List<EmbeddingVector> findByOwnerTypeAndOwnerIdIn(
            EmbeddingOwnerType ownerType, Collection<Long> ownerIds);
}
