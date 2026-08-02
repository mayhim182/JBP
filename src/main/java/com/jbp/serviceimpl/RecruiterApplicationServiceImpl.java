package com.jbp.serviceimpl;

import com.jbp.dto.ApplicationResponse;
import com.jbp.dto.ApplicationReviewRequest;
import com.jbp.dto.ApplicationStatusUpdateRequest;
import com.jbp.event.ApplicationStatusChangePublisher;
import com.jbp.exception.ResourceNotFoundException;
import com.jbp.mapper.ApplicationMapper;
import com.jbp.model.Application;
import com.jbp.model.ApplicationStatus;
import com.jbp.model.CandidateProfile;
import com.jbp.model.Job;
import com.jbp.repository.ApplicationRepository;
import com.jbp.repository.JobRepository;
import com.jbp.security.CurrentUserProvider;
import com.jbp.service.CandidateProfileService;
import com.jbp.service.MatchScorer;
import com.jbp.service.RecruiterApplicationService;
import com.jbp.util.ApplicationStageTransitionValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecruiterApplicationServiceImpl implements RecruiterApplicationService {

    private static final int MIN_RATING = 1;
    private static final int MAX_RATING = 5;

    private final ApplicationRepository applicationRepository;
    private final JobRepository jobRepository;
    private final CurrentUserProvider currentUserProvider;
    private final ApplicationMapper applicationMapper;
    private final ApplicationStageTransitionValidator transitionValidator;
    private final ApplicationStatusChangePublisher statusChangePublisher;
    private final CandidateProfileService candidateProfileService;
    private final MatchScorer matchScorer;

    @Override
    public List<ApplicationResponse> getApplicantsForJob(Long jobId) {
        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Job not found with id: " + jobId));
        ensureRecruiterOwnsJob(job);
        // Applicants arrive ranked by match score (best first), not apply-order.
        return applicationRepository.findByJobId(jobId).stream()
                .map(application -> toScoredResponse(application, job))
                .sorted(Comparator.comparingInt(ApplicationResponse::getMatchScore).reversed())
                .toList();
    }

    /**
     * The recruiter view of one application, always carrying its match.
     *
     * <p><strong>Every path returns this, not {@code applicationMapper.toRecruiterResponse} alone.</strong>
     * Opening an applicant, moving their stage and saving a review all return an
     * {@link ApplicationResponse} that the board merges over the row it already holds, so a response
     * that omitted the match would blank a score the list had correctly loaded — which is exactly what
     * used to happen: the ring vanished the moment a recruiter opened an applicant, "Why this rank"
     * read "No match reason provided", and the funnel's average drifted down as they triaged.
     *
     * <p>Scored on demand rather than stored: the score is a function of a live profile and a live
     * job, and persisting it would make it a snapshot that silently disagrees with both.
     */
    private ApplicationResponse toScoredResponse(Application application, Job job) {
        ApplicationResponse response = applicationMapper.toRecruiterResponse(application);
        CandidateProfile profile = candidateProfileService
                .findProfileForCandidate(application.getCandidate().getId())
                .orElseGet(() -> CandidateProfile.builder().build());
        MatchScorer.MatchResult match = matchScorer.score(profile, job);
        response.setMatchScore(match.score());
        response.setMatchReason(match.reason());
        return response;
    }

    /** For the three single-application paths, where the job comes from the application itself. */
    private ApplicationResponse toScoredResponse(Application application) {
        return toScoredResponse(application, application.getJob());
    }

    @Override
    @Transactional
    public ApplicationResponse openApplication(Long applicationId) {
        Application application = findApplicationOrThrow(applicationId);
        ensureRecruiterOwnsApplication(application);
        // Opening an applicant that hasn't been seen advances it to VIEWED.
        if (application.getStatus() == ApplicationStatus.APPLIED) {
            changeStatus(application, ApplicationStatus.VIEWED, null);
        }
        return toScoredResponse(application);
    }

    @Override
    @Transactional
    public ApplicationResponse updateStatus(Long applicationId, ApplicationStatusUpdateRequest request) {
        Application application = findApplicationOrThrow(applicationId);
        ensureRecruiterOwnsApplication(application);
        changeStatus(application, request.getStatus(), request.getRejectionReason());
        return toScoredResponse(application);
    }

    @Override
    @Transactional
    public ApplicationResponse updateReview(Long applicationId, ApplicationReviewRequest request) {
        Application application = findApplicationOrThrow(applicationId);
        ensureRecruiterOwnsApplication(application);

        if (request.getRating() != null && (request.getRating() < MIN_RATING || request.getRating() > MAX_RATING)) {
            throw new IllegalArgumentException("Rating must be between " + MIN_RATING + " and " + MAX_RATING);
        }
        if (request.getNotes() != null) {
            application.setRecruiterNotes(request.getNotes());
        }
        if (request.getRating() != null) {
            application.setRating(request.getRating());
        }
        applicationRepository.save(application);
        log.info("Recruiter updated review for application {}", applicationId);
        return toScoredResponse(application);
    }

    // Applies a validated stage change and publishes the transparency event (single source of truth).
    private void changeStatus(Application application, ApplicationStatus newStatus, String rejectionReason) {
        ApplicationStatus oldStatus = application.getStatus();
        transitionValidator.validateTransition(oldStatus, newStatus);

        application.setStatus(newStatus);
        if (newStatus == ApplicationStatus.REJECTED && rejectionReason != null) {
            application.setRejectionReason(rejectionReason);
        }
        applicationRepository.save(application);
        log.info("Application {} moved {} -> {}", application.getId(), oldStatus, newStatus);
        statusChangePublisher.publish(application, oldStatus, newStatus);
    }

    private void ensureRecruiterOwnsJob(Job job) {
        Long currentUserId = currentUserProvider.getCurrentUserId();
        if (!job.getCompany().getOwner().getId().equals(currentUserId)) {
            throw new AccessDeniedException("You can only view applicants for your own jobs");
        }
    }

    private void ensureRecruiterOwnsApplication(Application application) {
        Long currentUserId = currentUserProvider.getCurrentUserId();
        if (!application.getJob().getCompany().getOwner().getId().equals(currentUserId)) {
            throw new AccessDeniedException("You can only manage applicants for your own jobs");
        }
    }

    private Application findApplicationOrThrow(Long id) {
        return applicationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Application not found with id: " + id));
    }
}
