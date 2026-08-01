package com.jbp.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One stored embedding, in its own table rather than as columns on {@code Job} and
 * {@code CandidateProfile}.
 *
 * <p><strong>Why a separate table.</strong> A {@code @Lob byte[]} on {@code Job} would be fetched by
 * every query that loads a job, because lazy fetching of a basic column needs bytecode enhancement to
 * work reliably. Story 13.0's matches list loads fifty jobs at a time — that would drag fifty vectors
 * along for a request that never looks at them. Here, job queries never touch vectors, and Story 13.3
 * loads them deliberately, in one query, for exactly the jobs it is scoring.
 *
 * <p><strong>Why {@code model} and {@code dimension} are stored.</strong> A vector is only comparable
 * with another produced by the same model at the same size. Recording both means a configuration change
 * makes existing rows *stale* rather than *wrong* — the reader treats a mismatch exactly like a
 * {@code sourceHash} miss and falls back, instead of computing a cosine between vectors that have no
 * shared meaning. Without these columns that failure would be silent and would look like bad matching.
 *
 * <p>The table is created by {@code ddl-auto=update}; no migration accompanies this story.
 */
@Entity
@Table(
        name = "embedding_vectors",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_embedding_owner",
                columnNames = {"owner_type", "owner_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmbeddingVector {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "owner_type", nullable = false)
    private EmbeddingOwnerType ownerType;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    /** The provider model that produced this vector, e.g. {@code gemini-embedding-001}. */
    @Column(nullable = false)
    private String model;

    /** Component count. 768 today; a change makes every existing row stale. */
    @Column(nullable = false)
    private int dimension;

    /** SHA-256 of the embedded text, so an edit that changes nothing costs no provider call. */
    @Column(name = "source_hash", nullable = false, length = 64)
    private String sourceHash;

    /**
     * float32, big-endian — see {@code VectorCodec}, which owns that format.
     *
     * <p><strong>{@code length} is load-bearing, not documentation.</strong> Without it Hibernate maps
     * this to {@code TINYBLOB} on MySQL, which holds <strong>255 bytes</strong> — and a 768-dimension
     * vector is 3,072. H2 maps the same annotation to a full {@code BLOB}, so the entire offline suite
     * passed against a column type production would never have used; MySQL revealed it on the first real
     * startup. 65,535 asks Hibernate for {@code BLOB}, which covers roughly 16,000 dimensions.
     */
    @Lob
    @Column(nullable = false, length = 65535)
    private byte[] vector;
}
