package com.jbp.service;

import com.jbp.dto.DraftAnswerRequest;
import com.jbp.dto.DraftAnswerResponse;

/**
 * Drafts a screening answer for the signed-in candidate, applying everything that has to be true
 * before and after the model is asked.
 *
 * <p>Lives between the controller and {@link ScreeningAnswerAssistant} because the assistant is a
 * pure "question plus profile in, draft out" capability, and four things around it are not: whose
 * profile it is, whether that profile can ground an answer at all, whether this candidate has any of
 * today's allowance left, and giving that allowance back when the attempt fails. Putting them in the
 * controller would leave them behind the moment a second caller appeared.
 */
public interface ScreeningAnswerDraftService {

    /**
     * @throws com.jbp.exception.RateLimitExceededException  the candidate's allowance is spent (429)
     * @throws com.jbp.exception.InsufficientProfileException nothing in the profile can ground an
     *                                                       answer, or the assistant declined (422)
     * @throws com.jbp.exception.LlmUnavailableException     the model could not be used (503)
     */
    DraftAnswerResponse draftAnswer(DraftAnswerRequest request);
}
