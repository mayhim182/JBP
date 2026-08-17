package com.jbp.service;

import com.jbp.dto.AdminJobResponse;
import com.jbp.dto.JobResponse;

import java.util.List;

/** Admin job moderation (Story 9.2), ranked by moderation risk (Story 14.5). */
public interface AdminJobService {

    /** Highest risk first. See {@code AdminJobServiceImpl} for what that means when nothing is assessed. */
    List<AdminJobResponse> getPendingJobs();

    /**
     * One posting, at any status except DRAFT — Story 14.6, so an admin can open the posting a
     * duplicate notice names.
     *
     * <p><strong>DRAFT is excluded deliberately, and it is an access decision rather than a filter.</strong>
     * A draft has never been submitted to anyone; widening admin reads to cover it would hand an admin
     * recruiter work-in-progress that no moderation flow has ever exposed. It costs nothing to exclude:
     * a draft is never embedded, so it can never be named as a duplicate, so no admin ever follows a
     * link to one.
     *
     * @throws com.jbp.exception.ResourceNotFoundException if there is no such job, or it is a draft
     */
    AdminJobResponse getJob(Long jobId);

    JobResponse approveJob(Long jobId);

    JobResponse rejectJob(Long jobId, String reason);
}
