package com.jbp.serviceimpl;

import com.jbp.model.CandidateProfile;
import com.jbp.model.Job;
import com.jbp.model.ScorerMode;
import com.jbp.service.EmbeddingStore;
import com.jbp.util.SemanticScoreCalibration;

import java.util.List;

/**
 * Scores on meaning alone: cosine similarity between the candidate's and the job's stored embeddings.
 *
 * <p>This is the scorer Epic 13 exists for. A profile saying "built single page apps" and a job saying
 * "React developer" share no keyword, so the rule scorer sees nothing; their embeddings do not.
 *
 * <p>The lookup, the fallback to rules and the calibration all live in {@link VectorBackedMatchScorer},
 * which Story 13.4 extracted when the hybrid scorer needed the same skeleton. What is left here is the
 * one thing that makes this scorer itself: meaning is the <em>only</em> signal.
 *
 * <p><strong>Diagnostic, not the default.</strong> Measured 2026-07-31 against real data, a
 * candidate/job pair the rule scorer rates 92 scored 11 in this mode, because the calibration band was
 * derived from short phrase pairs rather than real documents. Its value now is as the isolated semantic
 * signal the Story 13.4 harness compares against; {@link HybridMatchScorer} is what a candidate should
 * ever see.
 */
public class EmbeddingMatchScorer extends VectorBackedMatchScorer {

    /** Meaning is the whole score in this mode, so the semantic row carries the entire weight. */
    private static final int SEMANTIC_WEIGHT = 100;

    public EmbeddingMatchScorer(EmbeddingStore embeddingStore,
                                RuleBasedMatchScorer ruleBasedFallback,
                                SemanticScoreCalibration calibration) {
        super(embeddingStore, ruleBasedFallback, calibration);
    }

    @Override
    protected MatchResult combine(CandidateProfile profile, Job job, int semanticScore) {
        List<MatchFactor> factors = List.of(semanticFactor(SEMANTIC_WEIGHT, semanticScore));
        return new MatchResult(
                RuleBasedMatchScorer.totalOf(factors),
                RuleBasedMatchScorer.reasonFrom(factors),
                factors,
                ScorerMode.EMBEDDING,
                // Nothing else contributed, so meaning did surface this match by definition. Vacuously
                // true, which is exactly why the hybrid scorer's version of this claim is the useful one.
                true);
    }
}
