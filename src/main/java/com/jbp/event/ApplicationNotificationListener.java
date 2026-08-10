package com.jbp.event;

import com.jbp.model.ApplicationStatus;
import com.jbp.model.Job;
import com.jbp.model.NotificationType;
import com.jbp.repository.JobRepository;
import com.jbp.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Turns application status changes into notifications for the candidate. Runs AFTER the
 * triggering transaction commits, asynchronously, and swallows its own errors — so a
 * notification failure can never break the apply/status-change that produced the event.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApplicationNotificationListener {

    private final NotificationService notificationService;
    private final JobRepository jobRepository;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onApplicationStatusChanged(ApplicationStatusChangedEvent event) {
        try {
            String jobTitle = jobRepository.findById(event.jobId())
                    .map(Job::getTitle)
                    .orElse("a job");
            String message = buildMessage(jobTitle, event);
            notificationService.createNotification(
                    event.candidateId(), NotificationType.APPLICATION_STATUS_CHANGED, message);
        } catch (Exception e) {
            log.error("Failed to create notification for application {}", event.applicationId(), e);
        }
    }

    private String buildMessage(String jobTitle, ApplicationStatusChangedEvent event) {
        if (event.oldStatus() == null) {
            return "Your application for '" + jobTitle + "' has been submitted.";
        }
        if (event.newStatus() == ApplicationStatus.REJECTED && event.rejectionReason() != null) {
            return "Your application for '" + jobTitle + "' was not successful: " + event.rejectionReason();
        }
        return "Your application for '" + jobTitle + "' has " + describe(event.newStatus()) + ".";
    }

    /**
     * The stage in words a candidate would use. Previously this interpolated the enum directly, so the
     * message read <em>"is now REJECTED"</em> — shouting a constant at the person it is about.
     *
     * <p>Deliberately an exhaustive switch with <strong>no {@code default}</strong>: a new
     * {@link ApplicationStatus} then fails the build here rather than reaching a candidate as its own
     * name. That compile error is the point of the method.
     */
    private String describe(ApplicationStatus status) {
        return switch (status) {
            case APPLIED -> "been received";
            case VIEWED -> "been viewed by the recruiter";
            case SHORTLISTED -> "been shortlisted";
            case INTERVIEW -> "moved to the interview stage";
            case OFFER -> "reached the offer stage";
            case REJECTED -> "not been successful";
            case CLOSED -> "been closed";
        };
    }
}
