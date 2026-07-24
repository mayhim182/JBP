package com.jbp.service;

import com.jbp.dto.JobMatchResponse;

import java.util.List;

/** Candidate-facing matching surface: match scores for published jobs. */
public interface MatchService {

    /** All published jobs scored for the current candidate, ranked best-first. */
    List<JobMatchResponse> getJobMatchesForCurrentCandidate();

    /** The current candidate's match for a single published job. */
    JobMatchResponse getJobMatchForCurrentCandidate(Long jobId);
}
