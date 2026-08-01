package com.jbp.event;

import com.jbp.model.EmbeddingOwnerType;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Single place that publishes {@link EmbeddingRefreshRequestedEvent}, so the job and profile flows do
 * not each construct it — the same reason {@link ApplicationStatusChangePublisher} exists.
 *
 * <p>What this buys the callers is more than tidiness: {@code JobServiceImpl} and
 * {@code CandidateProfileServiceImpl} end up knowing nothing about embeddings, models or vectors. They
 * announce that something was saved. Whether that means a provider call is entirely downstream, and a
 * future owner type is a new enum value and a new text builder, with no edit to either service.
 */
@Component
@RequiredArgsConstructor
public class EmbeddingRefreshPublisher {

    private final ApplicationEventPublisher eventPublisher;

    public void jobChanged(Long jobId) {
        eventPublisher.publishEvent(
                new EmbeddingRefreshRequestedEvent(EmbeddingOwnerType.JOB, jobId));
    }

    public void candidateProfileChanged(Long profileId) {
        eventPublisher.publishEvent(
                new EmbeddingRefreshRequestedEvent(EmbeddingOwnerType.CANDIDATE_PROFILE, profileId));
    }
}
