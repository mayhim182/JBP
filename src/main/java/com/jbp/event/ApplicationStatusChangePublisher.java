package com.jbp.event;

import com.jbp.model.Application;
import com.jbp.model.ApplicationStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Single place that builds and publishes {@link ApplicationStatusChangedEvent},
 * so the candidate and recruiter flows don't duplicate event construction (DRY).
 */
@Component
@RequiredArgsConstructor
public class ApplicationStatusChangePublisher {

    private final ApplicationEventPublisher eventPublisher;

    public void publish(Application application, ApplicationStatus oldStatus, ApplicationStatus newStatus) {
        eventPublisher.publishEvent(new ApplicationStatusChangedEvent(
                application.getId(),
                application.getCandidate().getId(),
                application.getJob().getId(),
                oldStatus,
                newStatus,
                application.getRejectionReason()));
    }
}
