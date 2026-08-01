package com.jbp.service;

import com.jbp.model.CandidateProfile;
import com.jbp.model.Job;
import com.jbp.model.MatchFactorKind;
import com.jbp.model.ScorerMode;

import java.util.List;

/**
 * Computes how well a candidate fits a job. Rule-based, embedding-based and (Story 13.4) hybrid
 * implementations all sit behind this one method, so the candidate and recruiter surfaces depend on
 * the seam rather than on any scoring strategy.
 */
public interface MatchScorer {

    MatchResult score(CandidateProfile profile, Job job);

    /**
     * An explainable score (0-100) with a short human-readable reason and a structured breakdown.
     *
     * <p><strong>{@code factors}, {@code mode} and {@code surfacedByMeaning} were added by Story
     * 13.3.</strong> Existing consumers are unaffected — {@code MatchServiceImpl} and
     * {@code RecruiterApplicationServiceImpl} read {@code score()} and {@code reason()}, and added
     * record components leave them compiling. The breakdown exists because Story 13.5 must hand a model
     * the factors rather than re-parse a joined string, and design 20 draws a weight per row.
     *
     * @param score             0-100, always deterministic for the same inputs
     * @param reason            the joined human-readable summary, unchanged in shape from before
     * @param factors           one entry per contributing dimension, in display order
     * @param mode              which scorer produced this, so the UI never infers it from config
     * @param surfacedByMeaning true when {@link MatchFactorKind#SEMANTIC} contributed more than
     *                          {@link MatchFactorKind#SKILLS} — design 20's "surfaced by meaning" chip,
     *                          and the one claim Epic 13 exists to be able to make. Always false for the
     *                          rule scorer, which has no semantic factor to compare against.
     */
    record MatchResult(
            int score,
            String reason,
            List<MatchFactor> factors,
            ScorerMode mode,
            boolean surfacedByMeaning) {

        /** Rule-only shorthand, and what keeps older tests constructing results unchanged. */
        public MatchResult(int score, String reason) {
            this(score, reason, List.of(), ScorerMode.RULE, false);
        }
    }

    /**
     * One dimension's contribution.
     *
     * @param kind   which dimension
     * @param weight this factor's share of the total, 0-100. Weights across a result sum to 100.
     * @param score  how well the candidate did on this dimension alone, 0-100 — the bar's fill
     * @param detail the short phrase already shown today, e.g. {@code "skills 2/3"}
     */
    record MatchFactor(MatchFactorKind kind, int weight, int score, String detail) {

        /** Points this factor contributes to the total, which is what design 20 labels a contribution. */
        public int contribution() {
            return Math.round(weight * score / 100.0f);
        }
    }
}
