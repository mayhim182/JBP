package com.jbp.service;

import com.jbp.dto.JobResponse;
import com.jbp.dto.PendingJobResponse;

import java.util.List;

/** Admin job moderation (Story 9.2), ranked by moderation risk (Story 14.5). */
public interface AdminJobService {

    /** Highest risk first. See {@code AdminJobServiceImpl} for what that means when nothing is assessed. */
    List<PendingJobResponse> getPendingJobs();

    JobResponse approveJob(Long jobId);

    JobResponse rejectJob(Long jobId, String reason);
}
