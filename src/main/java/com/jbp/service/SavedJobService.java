package com.jbp.service;

import com.jbp.dto.JobResponse;

import java.util.List;

public interface SavedJobService {

    /** Saves the job for the current candidate. Idempotent: saving twice is a no-op. */
    void saveJob(Long jobId);

    /** Removes the saved job for the current candidate. Idempotent if not saved. */
    void unsaveJob(Long jobId);

    List<JobResponse> getSavedJobs();
}
