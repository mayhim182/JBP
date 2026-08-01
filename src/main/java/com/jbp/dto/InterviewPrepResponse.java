package com.jbp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * The questions a candidate is likely to be asked for one job — design 21's expanded body.
 *
 * <p>Groups arrive in display order with empty ones already removed, so the client renders them in
 * sequence and never sorts or skips. Design 21 requires an empty group to produce no overline at all,
 * which is simplest to guarantee by not sending it.
 *
 * <p><strong>No question numbers.</strong> Design 21 numbers them 1..N continuously across groups,
 * which is a pure function of render order — and the design also fixes DOM order to match display
 * order, so the client can count as it renders. Sending a number would create a second source of
 * truth that could disagree with the position it sits at.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InterviewPrepResponse {

    private List<InterviewQuestionGroupResponse> groups;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class InterviewQuestionGroupResponse {

        /** {@code TECHNICAL}, {@code BEHAVIOURAL}, {@code ROLE_SPECIFIC} — a stable key. */
        private String kind;

        /** The overline, e.g. "Role-specific". Rendered upper-case by the client. */
        private String label;

        private List<String> questions;
    }
}
