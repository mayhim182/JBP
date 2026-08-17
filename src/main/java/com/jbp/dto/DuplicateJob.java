package com.jbp.dto;

import com.jbp.model.JobStatus;

/**
 * One posting that closely resembles another, as reported to a recruiter or an admin.
 *
 * <p><strong>No similarity score, deliberately.</strong> The threshold is configuration, so the same
 * 0.93 means "flagged" in one environment and "fine" in another — a number on screen would invite
 * arguing with a value that is not stable, and imply a precision this cannot defend. The posting's
 * name is checkable by the person reading it, which a score is not. The measured similarity is logged
 * instead, which is where threshold tuning should get its data.
 *
 * <p>{@code status} travels because "you already have this posting" reads very differently for a live
 * job than for one closed last year — and re-posting a closed role is explicitly legitimate.
 */
public record DuplicateJob(Long id, String title, JobStatus status) {
}
