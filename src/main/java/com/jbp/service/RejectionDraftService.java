package com.jbp.service;

import com.jbp.dto.DraftedRejectionReason;

/**
 * Story 14.4's entry point: drafts the rejection a recruiter is about to send, for one application
 * they own.
 *
 * <p>Separate from {@link RejectionReasonDrafter} for the reason {@link ApplicantSummaryService} is
 * separate from {@link ApplicantSummarizer} — the drafter's job is to turn a brief into prose, and
 * this one's is to decide whether the request is allowed, gather what the brief needs, and keep the
 * things the acceptance criteria forbid out of it. Collapsing them would put ownership checks and
 * rate limiting inside a class whose only other concern is a prompt.
 */
public interface RejectionDraftService {

    /**
     * @param applicationId the application being turned down; must belong to a job the caller owns
     * @return prose for the recruiter to edit — never sent anywhere by this call
     * @throws org.springframework.security.access.AccessDeniedException      if the job is not the
     *                                                                        caller's
     * @throws com.jbp.exception.ResourceNotFoundException                    if no such application
     * @throws com.jbp.exception.ConflictException                            if it is already decided
     * @throws com.jbp.exception.RateLimitExceededException                   above the ceiling
     * @throws com.jbp.exception.LlmUnavailableException                      if nothing could be drafted
     */
    DraftedRejectionReason draftRejectionReason(Long applicationId);
}
