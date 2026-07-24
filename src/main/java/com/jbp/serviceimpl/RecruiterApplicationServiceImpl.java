package com.jbp.serviceimpl;

import com.jbp.dto.ApplicationResponse;
import com.jbp.dto.ApplicationReviewRequest;
import com.jbp.dto.ApplicationStatusUpdateRequest;
import com.jbp.event.ApplicationStatusChangePublisher;
import com.jbp.exception.ResourceNotFoundException;
import com.jbp.mapper.ApplicationMapper;
import com.jbp.model.Application;
import com.jbp.model.ApplicationStatus;
import com.jbp.model.Job;
import com.jbp.repository.ApplicationRepository;
import com.jbp.repository.JobRepository;
import com.jbp.security.CurrentUserProvider;
import com.jbp.service.RecruiterApplicationService;
import com.jbp.util.ApplicationStageTransitionValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    @Override
    public List<ApplicationResponse> getApplicantsForJob(Long jobId) {
        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Job not found with id: " + jobId));
        ensureRecruiterOwnsJob(job);
        return applicationRepository.findByJobId(jobId).stream()
                .map(applicationMapper::toRecruiterResponse)
                .toList();
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
        return applicationMapper.toRecruiterResponse(application);
    }

    @Override
    @Transactional
    public ApplicationResponse updateStatus(Long applicationId, ApplicationStatusUpdateRequest request) {
        Application application = findApplicationOrThrow(applicationId);
        ensureRecruiterOwnsApplication(application);
        changeStatus(application, request.getStatus(), request.getRejectionReason());
        return applicationMapper.toRecruiterResponse(application);
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
        return applicationMapper.toRecruiterResponse(application);
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
