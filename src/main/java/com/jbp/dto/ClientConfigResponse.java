package com.jbp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * What the client needs to know before it renders anything — currently which AI features are live.
 *
 * <p><strong>Public and deliberately boolean-only.</strong> It is fetched before sign-in, so it must
 * carry nothing about a user, a provider, a model name or a key. Adding a non-boolean here should
 * prompt the question of whether it belongs on an authenticated endpoint instead.
 *
 * <p>Nested under {@code ai} rather than flattened, so a later non-AI flag has an obvious home and
 * clients can pass the whole group around as one object.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ClientConfigResponse {

    private AiFeatureFlags ai;

    /**
     * One flag per AI capability. A capability is on only when AI as a whole is on <em>and</em> that
     * feature's own flag is set, so these can be read directly with no further conditions.
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class AiFeatureFlags {

        /** Story 14.1. When false the "Prepare for this interview" section is not rendered at all. */
        private boolean interviewPrep;

        /** Story 13.5. When false the match panel shows the deterministic reason only. */
        private boolean matchExplanation;

        /** Story 12.1. When false the "Generate with AI" control is shown disabled, per design 17b. */
        private boolean jobDescription;

        /**
         * Story 14.2. When false the apply dialog renders <em>no</em> action row under a free-text
         * answer — not a disabled trigger. Design 22b draws seven states and none of them is "the
         * feature is switched off", so a disabled control here would need copy nobody has written;
         * the row is simply absent, exactly as it is on a Yes/No question.
         */
        private boolean screeningAnswerAssist;

        /**
         * Story 14.3. When false the applicant review drawer renders <em>no</em> summary panel —
         * design 24 B3: no header, no placeholder, no "unavailable" line. Nobody was promised the
         * panel, so explaining its absence would advertise a feature the org has switched off.
         * Gated on this rather than on a failed fetch, so it never appears and then vanishes.
         */
        private boolean applicantSummary;
    }
}
