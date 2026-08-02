package com.jbp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One drafted screening answer, and what the candidate has left.
 *
 * <p>{@code remaining} travels with the draft because the client cannot derive it: design 22b's D2
 * banner shows the number only at two remaining and below, so a client counting its own calls would
 * be wrong after a page reload, in a second tab, or on any draft this browser did not make.
 *
 * <p>Only ever returned on success. A refusal carries the reason as an HTTP status instead — 429 when
 * the allowance is spent, 422 when the profile cannot ground an answer, 503 when the model could not
 * be reached — because those three lead to different advice and must not be told apart by inspecting
 * a body.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DraftAnswerResponse {

    private String draft;

    private int remaining;
}
