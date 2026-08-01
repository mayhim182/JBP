package com.jbp.serviceimpl;

import com.jbp.exception.LlmUnavailableException;
import com.jbp.service.InterviewQuestionGenerator;

/**
 * What serves when the interview-prep capability is switched off.
 *
 * <p>The client will not call the endpoint at all in that case — it gates the whole section on
 * {@code GET /api/config} before first paint, which is what makes design 21b's state C an *absence*
 * rather than a disabled control. This exists because a direct API call must still answer honestly,
 * and because "the UI won't ask" is not a security or correctness property.
 *
 * <p>Raises the same failure a real outage would, so the endpoint answers 503 through the handler
 * that already exists and nothing downstream needs a second code path.
 */
public class DisabledInterviewQuestionGenerator implements InterviewQuestionGenerator {

    @Override
    public InterviewQuestions generate(JobBrief brief) {
        throw new LlmUnavailableException("Interview preparation is switched off", false);
    }
}
