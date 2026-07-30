package com.jbp.service;

import com.jbp.dto.GeneratedJobDescription;
import com.jbp.dto.JobDescriptionRequest;
import com.jbp.dto.JobQualityFinding;
import com.jbp.dto.JobRequest;
import com.jbp.dto.JobResponse;

import java.util.List;

public interface JobService {

    JobResponse createJob(JobRequest request);

    /**
     * Writes a first-draft description for a role the current recruiter is authoring.
     *
     * <p>Lives here because it needs the recruiter's own company, which this service already
     * resolves for {@link #createJob}. The AI itself stays behind {@code JobDescriptionGenerator} —
     * this only supplies the context and returns the draft, and nothing is saved.
     */
    GeneratedJobDescription generateDescription(JobDescriptionRequest request);

    /**
     * The deterministic half of the quality check: instant, and unaffected by whether AI is on.
     *
     * <p>Split from {@link #checkQualityWithAi} so the editor can paint findings immediately rather
     * than waiting on a model — the designs show rules landing first and AI arriving after. Owner-only,
     * and nothing is stored: a check is recomputed on demand.
     */
    List<JobQualityFinding> checkQualityWithRules(Long jobId);

    /**
     * The wording half. Returns an empty list when AI is unavailable rather than failing, because the
     * rules have already given the recruiter something to act on.
     */
    List<JobQualityFinding> checkQualityWithAi(Long jobId);

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
