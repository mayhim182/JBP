package com.jbp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Call 2 of Story 13.5's two-call split: design 20's "in plain language" panel.
 *
 * <p>Carries no score, by design. The explanation is presentational and cannot move a number, and the
 * cleanest way to guarantee that is for no number capable of being displayed as the score to travel on
 * this response at all.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MatchExplanationResponse {

    /** Two sentences, or the deterministic reason when the model was unavailable. */
    private String summary;

    /** Design 20's "one thing that would help" sentence, or null when nothing is missing. */
    private String actionText;

    /** The skill named inside {@code actionText}, so the client can emphasise it without parsing. */
    private String actionSkill;

    /**
     * True only when a model wrote {@code summary}. Drives the "AI-written · does not affect your
     * score" label, which must not appear above wording the rule scorer produced — labelling
     * deterministic text as AI-written would be a small lie in the one place the design is making a
     * point about provenance.
     */
    private boolean generated;

    /** Must equal the score call's {@code scoreVersion}; the client discards the pair if it does not. */
    private String scoreVersion;
}
