package com.jbp.model;

/**
 * How much a quality finding matters.
 *
 * <p>Three tiers and deliberately no "blocking" one: publishing is never prevented by a finding, so a
 * fourth level meaning "you must fix this" would be a promise the product does not keep. The editor
 * renders each tier with a distinct glyph and word as well as a colour, so the ranking survives for a
 * reader who cannot distinguish them.
 */
public enum QualitySeverity {

    /** Worth fixing — the posting will measurably underperform without it. */
    HIGH,

    /** Consider — a real improvement, but the posting works as it stands. */
    MEDIUM,

    /** Polish — a refinement, safe to ignore. */
    LOW
}
