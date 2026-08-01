package com.jbp.serviceimpl;

import com.jbp.model.CandidateProfile;
import com.jbp.model.Job;
import com.jbp.model.MatchFactorKind;
import com.jbp.model.ScorerMode;
import com.jbp.service.EmbeddingStore;
import com.jbp.util.ProportionalWeights;
import com.jbp.util.SemanticScoreCalibration;

import java.util.ArrayList;
import java.util.List;

/**
 * Combines the four rule dimensions with semantic similarity, keeping every one of them visible.
 *
 * <p><strong>Why hybrid is the mode a candidate should actually see.</strong> Rules alone miss a profile
 * whose wording differs from the advert — the whole reason Epic 13 exists. Meaning alone throws away
 * signals that are simply facts: a required skill is either listed or it is not, and a location either
 * matches or the job is remote. Measured on 2026-08-01, meaning alone scored a pair the rules rate 92 at
 * 11. Neither number is right on its own; both dimensions being visible is the point.
 *
 * <p><strong>The ratio is not a free parameter.</strong> Design 20 already draws 35 / 14 / 11 / 10 against
 * the four rule rows and 30 against the semantic one. Scale the rule scorer's own 50 / 20 / 15 / 15 by
 * 0.7 and that is exactly what comes out, so the design had already decided 70 rules / 30 meaning and
 * this class inherits it rather than choosing it. It stays configurable through
 * {@code app.match.rule-weight} so the harness can argue with it, but the default is evidence, not taste.
 *
 * <p><strong>Rule factors are reused, never recomputed.</strong> {@link RuleBasedMatchScorer#factorsFor}
 * is the single source of each dimension's result and wording; this class only restates their weights.
 * Recomputing them here would mean two places deciding what "skills 2/3" means, and they would disagree
 * the first time one changed.
 *
 * <p><strong>A missing vector still yields a full rule score, not a capped one.</strong> That branch is in
 * {@link VectorBackedMatchScorer#score} — see the note there on why it is final.
 */
public class HybridMatchScorer extends VectorBackedMatchScorer {

    private final int ruleWeight;

    /**
     * @param ruleWeight the share of the total given to the four rule dimensions, 1-99. Meaning gets the
     *                   remainder — one property rather than two, because two could be configured to sum
     *                   to something other than 100 and then every score would silently be wrong.
     */
    public HybridMatchScorer(EmbeddingStore embeddingStore,
                             RuleBasedMatchScorer ruleBasedFallback,
                             SemanticScoreCalibration calibration,
                             int ruleWeight) {
        super(embeddingStore, ruleBasedFallback, calibration);
        if (ruleWeight < 1 || ruleWeight > 99) {
            throw new IllegalArgumentException("app.match.rule-weight must be between 1 and 99, was "
                    + ruleWeight + ". At 0 the rules are ignored and at 100 meaning is, and either way "
                    + "the hybrid scorer is not hybrid — use app.match.scorer=embedding or rule instead.");
        }
        this.ruleWeight = ruleWeight;
    }

    @Override
    protected MatchResult combine(CandidateProfile profile, Job job, int semanticScore) {
        List<MatchFactor> ruleFactors = ruleBasedFallback.factorsFor(profile, job);
        int[] scaledWeights = ProportionalWeights.scale(weightsOf(ruleFactors), ruleWeight);

        List<MatchFactor> factors = new ArrayList<>(ruleFactors.size() + 1);
        for (int index = 0; index < ruleFactors.size(); index++) {
            MatchFactor ruleFactor = ruleFactors.get(index);
            factors.add(new MatchFactor(
                    ruleFactor.kind(), scaledWeights[index], ruleFactor.score(), ruleFactor.detail()));
        }
        MatchFactor semantic = semanticFactor(100 - ruleWeight, semanticScore);
        factors.add(semantic);

        List<MatchFactor> breakdown = List.copyOf(factors);
        return new MatchResult(
                RuleBasedMatchScorer.totalOf(breakdown),
                RuleBasedMatchScorer.reasonFrom(breakdown),
                breakdown,
                ScorerMode.HYBRID,
                // Design 20's "surfaced by meaning" chip, and the first time this claim means anything:
                // in embedding-only mode it is true by construction, here it is a real comparison.
                semantic.contribution() > contributionOf(breakdown, MatchFactorKind.SKILLS));
    }

    private int[] weightsOf(List<MatchFactor> factors) {
        return factors.stream().mapToInt(MatchFactor::weight).toArray();
    }

    private int contributionOf(List<MatchFactor> factors, MatchFactorKind kind) {
        return factors.stream()
                .filter(factor -> factor.kind() == kind)
                .mapToInt(MatchFactor::contribution)
                .sum();
    }
}
