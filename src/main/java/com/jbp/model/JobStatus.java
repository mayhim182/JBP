package com.jbp.model;

/**
 * Lifecycle of a job posting.
 * Normal flow: DRAFT -> (publish) -> PUBLISHED -> (close) -> CLOSED.
 * PENDING_MODERATION and REJECTED are modeled for the future admin-moderation
 * flow (Epic 9); they are unused while publishing is auto-approved.
 */
public enum JobStatus {
    DRAFT,
    PENDING_MODERATION,
    PUBLISHED,
    CLOSED,
    REJECTED
}
