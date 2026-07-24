package com.jbp.event;

import com.jbp.model.ApplicationStatus;

/**
 * Published whenever an application's stage changes (including initial apply).
 * Consumed by the notifications module (Epic 8). {@code oldStatus} is null on apply.
 */
public record ApplicationStatusChangedEvent(
        Long applicationId,
        Long candidateId,
        Long jobId,
        ApplicationStatus oldStatus,
        ApplicationStatus newStatus,
        String rejectionReason) {
}
