package com.jbp.serviceimpl;

import com.jbp.model.CandidateProfile;
import com.jbp.model.EmbeddingOwnerType;
import com.jbp.model.Job;
import com.jbp.model.MatchFactorKind;
import com.jbp.service.EmbeddingStore;
import com.jbp.service.MatchScorer;
import com.jbp.util.CosineSimilarity;
import com.jbp.util.SemanticScoreCalibration;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

/**
 * Shared skeleton for every scorer that reads stored embeddings: look up both vectors, fall back to
 * rules if either is missing, otherwise turn the cosine into a calibrated 0-100 semantic score and let
 * the subclass decide what to do with it.
 *
 * <p><strong>Template Method, and {@code score} is deliberately final.</strong> The fallback branch is
 * Story 13.2's last acceptance criterion — a missing or stale vector must never break scoring — and it
 * has to behave identically in the embedding and hybrid scorers. Leaving each subclass to re-implement
 * the lookup would let the two drift, and the way they would drift is silent: a hybrid scorer that
 * treated a missing vector as "semantic scored 0" rather than "score by rules alone" would cap every
 * candidate at the rule weight, so a perfect match before the backfill ran would read 70 instead of 100.
 * That is a wrong number rather than an exception, which is exactly the kind of defect worth designing
 * out rather than testing for.
 *
 * <p>Introduced by Story 13.4 by lifting the body of {@code EmbeddingMatchScorer} unchanged. It is not
 * speculative generality: it exists because a second implementation arrived and needed the same skeleton.
 *
 * <p><strong>No model call happens in here.</strong> Scoring reads vectors written earlier by the async
 * listener or the backfill, so a page of matches costs no provider quota and cannot be slowed by provider
 * latency.
 */
@Slf4j
abstract class VectorBackedMatchScorer implements MatchScorer {

    private final EmbeddingStore embeddingStore;
    private final SemanticScoreCalibration calibration;

    /**
     * Also the fallback. Subclasses reuse its factor breakdown rather than recomputing the rule
     * dimensions, which is the point of Story 13.4 — combining signals, not duplicating them.
     */
    protected final RuleBasedMatchScorer ruleBasedFallback;

    protected VectorBackedMatchScorer(EmbeddingStore embeddingStore,
                                      RuleBasedMatchScorer ruleBasedFallback,
                                      SemanticScoreCalibration calibration) {
        this.embeddingStore = embeddingStore;
        this.ruleBasedFallback = ruleBasedFallback;
        this.calibration = calibration;
    }

    @Override
    public final MatchResult score(CandidateProfile profile, Job job) {
        Optional<float[]> candidateVector = vectorFor(EmbeddingOwnerType.CANDIDATE_PROFILE, profile.getId());
        Optional<float[]> jobVector = vectorFor(EmbeddingOwnerType.JOB, job.getId());

        if (candidateVector.isEmpty() || jobVector.isEmpty()) {
            // The concrete scorer is named explicitly: this logger belongs to the abstract class, and
            // "which mode fell back" is the first thing worth knowing when a score looks wrong.
            log.debug("{} scoring job {} for profile {} by rules — {} embedding is absent or stale",
                    getClass().getSimpleName(), job.getId(), profile.getId(),
                    candidateVector.isEmpty() ? "the candidate's" : "the job's");
            return ruleBasedFallback.score(profile, job);
        }

        double cosine = CosineSimilarity.between(candidateVector.get(), jobVector.get());
        return combine(profile, job, calibration.toScore(cosine));
    }

    /**
     * Build the result once meaning is known to be available.
     *
     * @param semanticScore 0-100, already calibrated — subclasses never see a raw cosine, because a raw
     *                      cosine is not a percentage of anything and must not be treated as one
     */
    protected abstract MatchResult combine(CandidateProfile profile, Job job, int semanticScore);

    /** The semantic row, at whatever share of the total the subclass gives meaning. */
    protected static MatchFactor semanticFactor(int weight, int semanticScore) {
        return new MatchFactor(MatchFactorKind.SEMANTIC, weight, semanticScore, describe(semanticScore));
    }

    /**
     * Empty when the owner has no id yet — a candidate who has never saved a profile is handed to the
     * scorer as an empty entity, and asking the store for vector {@code null} would be a bug rather than
     * a miss.
     */
    private Optional<float[]> vectorFor(EmbeddingOwnerType ownerType, Long ownerId) {
        if (ownerId == null) {
            return Optional.empty();
        }
        return embeddingStore.findVector(ownerType, ownerId);
    }

    /**
     * Wording for the semantic row. Deliberately about the strength of the relationship rather than a
     * number the candidate would read as a percentage of anything.
     */
    private static String describe(int semanticScore) {
        if (semanticScore >= 70) {
            return "strong overlap in what the role is about";
        }
        if (semanticScore >= 40) {
            return "related work, different wording";
        }
        if (semanticScore > 0) {
            return "some overlap in meaning";
        }
        return "little overlap in meaning";
    }
}
