package com.jbp.serviceimpl;

import com.jbp.dto.ApplicantSummary;
import com.jbp.exception.LlmUnavailableException;
import com.jbp.service.ApplicantSummarizer;

/**
 * What exists in place of the summarizer when the capability is switched off.
 *
 * <p>The client is told before it renders — {@code GET /api/config} — and design 24 B3 makes the
 * panel <em>absent</em> rather than disabled, so in normal operation this is never reached. It exists
 * because a direct API call still has to be answered honestly, and because "the capability is off"
 * and "the provider is down" should not become distinguishable to a caller who was never promised
 * the feature in the first place.
 *
 * <p>Sibling of {@link DisabledInterviewQuestionGenerator}, and the same shape.
 */
public class DisabledApplicantSummarizer implements ApplicantSummarizer {

    @Override
    public ApplicantSummary summarise(ApplicantBrief brief) {
        throw new LlmUnavailableException("Applicant summaries are switched off", false);
    }
}
