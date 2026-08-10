package com.jbp.event;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Single place that publishes {@link JobModerationRequestedEvent}, so the job flow does not construct
 * it — the same reason {@link EmbeddingRefreshPublisher} and {@link ApplicationStatusChangePublisher}
 * exist.
 *
 * <p>What it buys the caller is that {@code JobServiceImpl} announces a submission and knows nothing
 * about models, risk levels or moderation at all.
 */
@Component
@RequiredArgsConstructor
public class JobModerationPublisher {

    private final ApplicationEventPublisher eventPublisher;

    public void submittedForModeration(Long jobId) {
        eventPublisher.publishEvent(new JobModerationRequestedEvent(jobId));
    }
}
