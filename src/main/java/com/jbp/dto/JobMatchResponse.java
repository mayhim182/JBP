package com.jbp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * A job paired with the current candidate's match score and its breakdown.
 *
 * <p><strong>This is call 1 of Story 13.5's two-call split</strong> — everything here is computed and
 * renders immediately. The generated prose is fetched separately, so a slow or unavailable model cannot
 * delay the number, the bars or the ring.
 *
 * <p>{@code matchReason} is kept as-is. The breakdown supersedes it for design 20, but the dashboard
 * and matches list still read it, and Story 13.0 was the reminder that this response's shape has
 * consumers with no compile-time link to it.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JobMatchResponse {

    private JobResponse job;
    private int matchScore;
    private String matchReason;

    /** One entry per contributing dimension, in display order. Added by Story 13.5; resolves P11. */
    private List<MatchFactorResponse> factors;

    /**
     * {@code RULE}, {@code EMBEDDING} or {@code HYBRID} — design 20's "hybrid · rules 70 / meaning 30"
     * chip. Sent rather than inferred because it is a function of server configuration: a client
     * deriving it would duplicate that config and drift the first time the ratio was retuned, and a
     * wrong weight beside a right bar is worse than no weight at all.
     */
    private String scorerMode;

    /** Design 20's "surfaced by meaning" chip: semantic out-contributed skills. */
    private boolean surfacedByMeaning;

    /**
     * Fingerprint of everything that can move this score.
     *
     * <p>The explanation call returns it too and <strong>the client discards a mismatch</strong>.
     * Without that there is a real race: this call returns a score at V1, the candidate saves their
     * profile, the explanation call returns prose generated for V2 — and the screen shows V1's number
     * beside V2's explanation. The score would never have been altered by the explanation, exactly as
     * the acceptance criterion promises, and the candidate would be misled anyway.
     */
    private String scoreVersion;
}
