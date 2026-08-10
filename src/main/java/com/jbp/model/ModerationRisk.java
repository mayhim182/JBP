package com.jbp.model;

/**
 * How risky a job posting looked to the moderation assistant.
 *
 * <p><strong>There is deliberately no "not assessed" constant.</strong> The absence of an assessment
 * is the absence of a value, so a job nobody has assessed holds null. Design 28 C turns on exactly
 * that distinction — {@code NONE} is a claim we checked, null is the absence of one — and a constant
 * standing in for null would let the two be confused at every point they are read.
 */
public enum ModerationRisk {

    /** Flags serious enough to justify rejecting the post. */
    HIGH(0),

    /** Flags worth reading before approving. */
    MEDIUM(1),

    /** Assessed and clean — the queue's "NO FLAGS". */
    NONE(3);

    /**
     * Where a job with no assessment sorts: below a positive MEDIUM finding, above checked-and-clean.
     *
     * <p>Deliberately not 0. Sorting an unknown above evidence would put ignorance ahead of what we
     * know, and on day one every job in the backlog is unassessed — pinning them to the top would
     * make the unassessed block the whole queue rather than a section of it.
     */
    public static final int UNASSESSED_QUEUE_RANK = 2;

    private final int queueRank;

    ModerationRisk(int queueRank) {
        this.queueRank = queueRank;
    }

    /**
     * Position in the queue's highest-risk-first order (design 28 C):
     * HIGH → MEDIUM → not assessed → NONE.
     *
     * <p>Checked-and-clean sorts last rather than first because it is the one state an admin never
     * needs to reach for — the ranking answers "what should I look at next", not "what is best".
     */
    public int queueRank() {
        return queueRank;
    }
}
