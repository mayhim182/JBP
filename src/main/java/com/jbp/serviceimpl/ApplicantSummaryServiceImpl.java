package com.jbp.serviceimpl;

import com.jbp.dto.ApplicantSummary;
import com.jbp.exception.ConflictException;
import com.jbp.exception.InsufficientProfileException;
import com.jbp.exception.RateLimitExceededException;
import com.jbp.exception.ResourceNotFoundException;
import com.jbp.model.Application;
import com.jbp.model.ApplicationStatus;
import com.jbp.model.CandidateProfile;
import com.jbp.model.Job;
import com.jbp.repository.ApplicationRepository;
import com.jbp.security.CurrentUserProvider;
import com.jbp.service.ApplicantSummarizer;
import com.jbp.service.ApplicantSummaryService;
import com.jbp.service.CandidateProfileService;
import com.jbp.service.MatchScorer;
import com.jbp.util.PerUserCallBudget;
import com.jbp.util.ScoreVersion;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ApplicantSummaryServiceImpl implements ApplicantSummaryService {

    /**
     * Stages with no decision left to make. Design 24 B3's second condition: the summary is a
     * decision aid, and re-litigating a closed decision on AI prose is worse than not offering it.
     */
    private static final Set<ApplicationStatus> DECIDED =
            Set.of(ApplicationStatus.REJECTED, ApplicationStatus.CLOSED);

    private static final String NOTHING_TO_READ =
            "Not enough in this profile to write a read.";

    private final ApplicationRepository applicationRepository;
    private final CurrentUserProvider currentUserProvider;
    private final CandidateProfileService candidateProfileService;
    private final MatchScorer matchScorer;
    private final ApplicantSummarizer applicantSummarizer;
    /**
     * Two {@link PerUserCallBudget} beans exist — this one and Story 14.2's draft budget — so the
     * field name is what picks between them: Spring falls back to matching the constructor parameter
     * against the bean name. <strong>Renaming this field silently swaps in the other allowance.</strong>
     */
    private final PerUserCallBudget applicantSummaryCeiling;

    /**
     * The order matters, and each step is here rather than later for a reason.
     *
     * <p>Ownership and stage are checked before the ceiling is touched, so a request that was never
     * going to produce a read cannot spend a slot. The ceiling is then taken before the model is
     * asked — and, unlike Story 14.2's draft budget, it is <strong>never refunded</strong>. That
     * asymmetry is deliberate: 14.2's is a budget the candidate is told about, where a provider
     * outage must not cost them something they never received. This is a ceiling whose entire job is
     * to bound a runaway retry loop, and refunding failed attempts would make it bound nothing.
     */
    @Override
    public ApplicantSummary summariseApplicant(Long applicationId) {
        Application application = findOwnApplicationOrThrow(applicationId);
        if (DECIDED.contains(application.getStatus())) {
            throw new ConflictException("This application has already been decided");
        }

        Long recruiterId = currentUserProvider.getCurrentUserId();
        if (!applicantSummaryCeiling.tryReserveCall(recruiterId)) {
            log.warn("Recruiter {} is requesting summaries faster than the ceiling of {} allows",
                    recruiterId, applicantSummaryCeiling.maxCallsPerWindow());
            throw new RateLimitExceededException("Too many summaries at once. Try again shortly.");
        }

        Job job = application.getJob();
        CandidateProfile profile = candidateProfileService
                .findProfileForCandidate(application.getCandidate().getId())
                .orElseGet(() -> CandidateProfile.builder().build());
        MatchScorer.MatchResult match = matchScorer.score(profile, job);

        ApplicantSummary summary = applicantSummarizer.summarise(new ApplicantSummarizer.ApplicantBrief(
                applicationId,
                ScoreVersion.of(match, profile, job),
                profile,
                job,
                bandedFactors(match)));

        if (summary.wasDeclined()) {
            log.info("No read written for application {} — the profile has too little to work from",
                    applicationId);
            throw new InsufficientProfileException(NOTHING_TO_READ);
        }
        // Length only, never content: a summary is a written claim about a named person and has no
        // business in a log file.
        log.info("Summarised application {} in {} characters", applicationId, lengthOf(summary));
        return summary;
    }

    /**
     * The breakdown, with every number removed — see {@link ApplicantSummarizer.ApplicantBrief}. The
     * model is told which dimensions are strong or thin and never by how much, which is what makes
     * "must not restate the score" structural rather than a promise the prompt asks for.
     */
    private List<ApplicantSummarizer.FactorSignal> bandedFactors(MatchScorer.MatchResult match) {
        if (match.factors() == null) {
            return List.of();
        }
        return match.factors().stream()
                .map(factor -> new ApplicantSummarizer.FactorSignal(
                        factor.kind(), ApplicantSummarizer.FactorStrength.of(factor.score())))
                .toList();
    }

    private Application findOwnApplicationOrThrow(Long applicationId) {
        Application application = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Application not found with id: " + applicationId));
        Long currentUserId = currentUserProvider.getCurrentUserId();
        if (!application.getJob().getCompany().getOwner().getId().equals(currentUserId)) {
            log.warn("User {} attempted to summarise an applicant on somebody else's job", currentUserId);
            throw new AccessDeniedException("You can only view applicants for your own jobs");
        }
        return application;
    }

    private int lengthOf(ApplicantSummary summary) {
        return summary.getStrongestFit().length()
                + summary.getMainGap().length()
                + summary.getWorthProbing().length();
    }
}
