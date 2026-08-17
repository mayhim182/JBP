package com.jbp.dto;

import java.time.Instant;
import java.util.List;

/**
 * A job as an admin sees it: the posting, plus the two judgements only an admin is shown.
 *
 * <p><strong>A separate type rather than fields on {@link JobResponse}.</strong> JobResponse is served
 * to guests on every published job and every search hit, and neither "this post looks like a scam" nor
 * "this resembles two other postings" belongs there. Hanging them on the shared response and leaving
 * them null everywhere else would put that leak one careless mapper call away.
 *
 * <p>Renamed from {@code PendingJobResponse} by Story 14.6, which serves it for postings of any status
 * — a duplicate an admin follows may be published, closed or rejected, so a name asserting "pending"
 * became false the moment the detail route was widened.
 *
 * @param moderation  null when the job has not been assessed, which the queue draws as "NOT ASSESSED"
 *                    rather than as "NO FLAGS"
 * @param submittedAt when the job entered moderation; null for anything submitted before the field
 *                    existed, and what the queue's oldest-first view orders by
 * @param duplicates  postings by the same company that closely resemble this one; empty both when
 *                    none were found and when none could be looked for
 */
public record AdminJobResponse(Long id,
                               JobResponse job,
                               JobRiskAssessment moderation,
                               Instant submittedAt,
                               List<DuplicateJob> duplicates) {

    /** Never null, so callers can stream it without a guard. */
    @Override
    public List<DuplicateJob> duplicates() {
        return duplicates == null ? List.of() : duplicates;
    }
}
