package com.jbp.dto;

import java.time.Instant;

/**
 * One row of the admin moderation queue: the posting, and what the assistant made of it.
 *
 * <p><strong>A separate type rather than two more fields on {@link JobResponse}.</strong> JobResponse
 * is served to guests on every published job and every search hit, and "this post looks like a scam"
 * is an admin-only judgement about something still under review. Hanging it on the shared response and
 * leaving it null everywhere else would put that leak one careless mapper call away; a reviewer would
 * have to notice the absence of a field to catch it.
 *
 * <p>{@code id} is repeated from the nested job so a row has a stable key without reaching through the
 * payload — one duplicated scalar, against the fifteen this type would restate if it flattened the
 * posting instead of nesting it.
 *
 * @param moderation  null when the job has not been assessed, which the queue draws as "NOT ASSESSED"
 *                    rather than as "NO FLAGS"
 * @param submittedAt when the job entered moderation; null for anything submitted before the field
 *                    existed, and what the queue's oldest-first view orders by
 */
public record PendingJobResponse(Long id,
                                 JobResponse job,
                                 JobRiskAssessment moderation,
                                 Instant submittedAt) {
}
