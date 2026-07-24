package com.jbp.service;

import com.jbp.model.CandidateProfile;
import com.jbp.model.Job;

/**
 * Computes how well a candidate fits a job. The current implementation is rule-based;
 * it can be swapped for an embeddings/LLM scorer behind this interface with no changes
 * to callers (the candidate and recruiter surfaces both depend only on this).
 */
public interface MatchScorer {

    MatchResult score(CandidateProfile profile, Job job);

    /** An explainable score (0-100) with a short human-readable reason. */
    record MatchResult(int score, String reason) {
    }
}
