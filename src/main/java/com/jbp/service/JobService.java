package com.jbp.service;

import com.jbp.dto.JobRequest;
import com.jbp.dto.JobResponse;

import java.util.List;

public interface JobService {

    JobResponse createJob(JobRequest request);

    JobResponse updateJob(Long id, JobRequest request);

    void deleteJob(Long id);

    JobResponse publishJob(Long id);

    JobResponse closeJob(Long id);

    JobResponse cloneJob(Long id);

    /** Public view: returns the job only if it is PUBLISHED, otherwise 404. */
    JobResponse getPublishedJobById(Long id);

    /** All jobs owned by the current recruiter, in any status. */
    List<JobResponse> getMyJobs();
}
