package com.jbp.service;

import com.jbp.dto.InterviewPrepResponse;
import com.jbp.exception.LlmUnavailableException;
import com.jbp.exception.ResourceNotFoundException;

/** Candidate-facing interview preparation for a single published job (Story 14.1). */
public interface InterviewPrepService {

    /**
     * Likely interview questions for one job.
     *
     * <p>The questions describe the <em>job</em> and nothing about the caller, which is what makes one
     * cached answer correct for everyone — and what the UI states plainly rather than leaving the
     * candidate to assume they are personalised.
     *
     * @throws ResourceNotFoundException if the job does not exist or is not published
     * @throws LlmUnavailableException   if the capability is off, the provider is unreachable, or the
     *                                   reply was unusable. Answered as 503 so the client can draw
     *                                   design 21b's state D
     */
    InterviewPrepResponse getInterviewPrepForJob(Long jobId);
}
