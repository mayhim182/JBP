package com.jbp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One row of design 20's factor breakdown.
 *
 * <p>Returned <strong>structurally</strong> rather than inside the joined reason string — that is what
 * resolves {@code PLACEHOLDERS.md} P11, where the bars were drawn but unfed. A client that had to parse
 * "skills 1/2; seniority match; …" back into rows would be re-deriving something the server already
 * knows, and would break the first time the wording changed.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MatchFactorResponse {

    /** {@code SKILLS}, {@code SEMANTIC}, … — a stable key, so the client never matches on the label. */
    private String kind;

    /** The row heading, e.g. "Role similarity". */
    private String label;

    /** The short phrase already shown today, e.g. "skills 1 of 4 matched". */
    private String detail;

    /** This factor's share of the total, 0-100. Weights across a response sum to 100. */
    private int weight;

    /** How well the candidate did on this dimension alone, 0-100 — the bar's fill. */
    private int score;

    /** Points this factor contributed to the total, which is what design 20 labels a contribution. */
    private int contribution;
}
