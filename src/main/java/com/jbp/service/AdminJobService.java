package com.jbp.service;

import com.jbp.dto.JobResponse;

import java.util.List;

/** Admin job moderation (Story 9.2). */
public interface AdminJobService {

    List<JobResponse> getPendingJobs();

    JobResponse approveJob(Long jobId);

    JobResponse rejectJob(Long jobId, String reason);
}
