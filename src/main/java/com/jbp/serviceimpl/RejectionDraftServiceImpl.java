package com.jbp.serviceimpl;

import com.jbp.dto.DraftedRejectionReason;
import com.jbp.exception.ConflictException;
import com.jbp.exception.RateLimitExceededException;
import com.jbp.exception.ResourceNotFoundException;
import com.jbp.model.Application;
import com.jbp.model.ApplicationStatus;
import com.jbp.model.CandidateProfile;
import com.jbp.model.Job;
import com.jbp.repository.ApplicationRepository;
import com.jbp.security.CurrentUserProvider;
import com.jbp.service.CandidateProfileService;
import com.jbp.service.RejectionDraftService;
import com.jbp.service.RejectionReasonDrafter;
import com.jbp.util.PerUserCallBudget;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Decides whether a draft may be asked for, assembles the brief, and — the part that matters —
 * decides what the brief does <em>not</em> contain.
 *
 * <p>Modelled on {@link ApplicantSummaryServiceImpl}, whose ordering and ceiling rationale apply
 * unchanged.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RejectionDraftServiceImpl implements RejectionDraftService {

    /**
     * Stages with no decision left to make. Rejecting an already-rejected application is not a thing
     * the drawer can reach — {@code REJECTED} is terminal and the move controls are gone by then — so
     * a request for one is a stale client or a direct call, and drafting for it would spend a model
     * request on prose nobody can send.
     */
    private static final Set<ApplicationStatus> DECIDED =
            Set.of(ApplicationStatus.REJECTED, ApplicationStatus.CLOSED);

    private final ApplicationRepository applicationRepository;
    private final CurrentUserProvider currentUserProvider;
    private final CandidateProfileService candidateProfileService;
    private final RejectionReasonDrafter rejectionReasonDrafter;
    /**
     * As in {@link ApplicantSummaryServiceImpl}, several {@link PerUserCallBudget} beans exist and the
     * <strong>field name is what selects between them</strong>. Renaming this silently swaps in
     * another feature's allowance.
     */
    private final PerUserCallBudget rejectionDraftCeiling;

    /**
     * Ownership and stage before the ceiling, ceiling before the model — the same order, and the same
     * reasons, as Story 14.3: a request that was never going to produce a draft cannot spend a slot,
     * and the ceiling is never refunded because a ceiling that refunds failures bounds nothing.
     */
    @Override
    public DraftedRejectionReason draftRejectionReason(Long applicationId) {
        Application application = findOwnApplicationOrThrow(applicationId);
        if (DECIDED.contains(application.getStatus())) {
            throw new ConflictException("This application has already been decided");
        }

        Long recruiterId = currentUserProvider.getCurrentUserId();
        if (!rejectionDraftCeiling.tryReserveCall(recruiterId)) {
            log.warn("Recruiter {} is requesting rejection drafts faster than the ceiling of {} allows",
                    recruiterId, rejectionDraftCeiling.maxCallsPerWindow());
            throw new RateLimitExceededException("Too many drafts at once. Try again shortly.");
        }

        Job job = application.getJob();
        DraftedRejectionReason drafted = rejectionReasonDrafter.draft(
                new RejectionReasonDrafter.RejectionBrief(job, requirementsWithNoEvidence(application, job)));

        // Length only, never content. This prose is about a named person and is on its way to them;
        // a log file is the last place it should also exist.
        log.info("Drafted a rejection for application {} in {} characters",
                applicationId, drafted.getReason().length());
        return drafted;
    }

    /**
     * The match gap, in the job's own words: required skills this application shows no evidence for.
     *
     * <p><strong>This mirrors {@code RuleBasedMatchScorer.skillsFactor} deliberately and must keep
     * mirroring it.</strong> That method counts a required skill as met when the candidate lists the
     * identical string, compared lower-case; both scorers use it, because the vector-backed one adds a
     * semantic factor rather than replacing the skills one. Matching that rule exactly is what stops
     * the draft naming a gap the score already credited — a contradiction the candidate would be the
     * first to notice, and one they could not check.
     *
     * <p>The <em>job's</em> casing is returned, not the lower-cased form used for comparison: the
     * candidate reads this, and "kafka" in a sentence looks like a typo.
     *
     * <p>Only the profile's skills are read here, and only to subtract them. The profile itself never
     * reaches {@link RejectionReasonDrafter.RejectionBrief} — see its javadoc for why that boundary is
     * the acceptance criteria rather than a preference.
     */
    private List<String> requirementsWithNoEvidence(Application application, Job job) {
        if (job.getSkills() == null || job.getSkills().isEmpty()) {
            return List.of();
        }
        Set<String> evidenced = candidateProfileService
                .findProfileForCandidate(application.getCandidate().getId())
                .map(CandidateProfile::getSkills)
                .map(RejectionDraftServiceImpl::toLowerSet)
                .orElseGet(Set::of);

        return job.getSkills().stream()
                .filter(skill -> skill != null && !skill.isBlank())
                .filter(skill -> !evidenced.contains(skill.trim().toLowerCase(Locale.ROOT)))
                .toList();
    }

    private static Set<String> toLowerSet(Collection<String> values) {
        if (values == null) {
            return Set.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }

    private Application findOwnApplicationOrThrow(Long applicationId) {
        Application application = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Application not found with id: " + applicationId));
        Long currentUserId = currentUserProvider.getCurrentUserId();
        if (!application.getJob().getCompany().getOwner().getId().equals(currentUserId)) {
            log.warn("User {} attempted to draft a rejection on somebody else's job", currentUserId);
            throw new AccessDeniedException("You can only reject applicants for your own jobs");
        }
        return application;
    }
}
