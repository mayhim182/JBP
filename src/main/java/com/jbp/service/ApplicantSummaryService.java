package com.jbp.service;

import com.jbp.dto.ApplicantSummary;

/**
 * The three-line read on one applicant, for the recruiter who owns the job.
 *
 * <p>Lives between the controller and {@link ApplicantSummarizer} because the summarizer is a pure
 * "job plus profile in, three lines out" capability, and four things around it are not: whose
 * application it is, whether there is still a decision left to aid, whether this recruiter is asking
 * faster than any human could, and what version of the match the answer belongs to.
 */
public interface ApplicantSummaryService {

    /**
     * @throws com.jbp.exception.ResourceNotFoundException   no such application
     * @throws org.springframework.security.access.AccessDeniedException not this recruiter's job
     * @throws com.jbp.exception.ConflictException           the application is rejected or closed, so
     *                                                       there is no decision left to aid
     * @throws com.jbp.exception.RateLimitExceededException  asking far faster than a human triages
     * @throws com.jbp.exception.InsufficientProfileException the profile cannot ground a read (422,
     *                                                       design 24 B4)
     * @throws com.jbp.exception.LlmUnavailableException     the model could not be used (503, B2)
     */
    ApplicantSummary summariseApplicant(Long applicationId);
}
