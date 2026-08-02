package com.jbp.service;

import com.jbp.dto.ApplicantSummary;
import com.jbp.model.CandidateProfile;
import com.jbp.model.Job;
import com.jbp.model.MatchFactorKind;

import java.util.List;

/**
 * Writes a three-line read on one applicant against one job: their strongest fit, the main gap, and
 * one thing worth probing in an interview.
 *
 * <p>One capability, one interface — the same shape as {@link JobDescriptionGenerator},
 * {@link ScreeningAnswerAssistant} and {@link InterviewQuestionGenerator}.
 *
 * <p><strong>It declines rather than invents.</strong> A profile that cannot ground a read comes back
 * declined, which the caller turns into design 24's state B4. This matters more here than anywhere
 * else in the system: an invented "strongest fit" is not a bad suggestion shown to the person who
 * asked for it, it is a fabricated claim about a third party feeding a hiring decision.
 *
 * <p><strong>It never sees a number.</strong> See {@link ApplicantBrief}.
 */
public interface ApplicantSummarizer {

    /**
     * A decline comes back as a <em>value</em>; a failure is <em>thrown</em>. The asymmetry is what
     * makes the cache correct, and it is the same split {@link ScreeningQuestionSuggester} uses.
     *
     * <p>A decline is a fact about the profile — it stays true until the candidate edits it, at which
     * point the cache key moves anyway — so it is worth storing. A failure is a fact about a moment,
     * and design 24 B2 offers a plain "Try again" with no countdown, which is only honest if the next
     * attempt can actually reach the model. Caffeine's loader stores nothing when the loader throws,
     * so throwing is what keeps that true.
     *
     * @throws com.jbp.exception.LlmUnavailableException when AI is off, this capability is off, the
     *                                                   provider is unreachable, or the reply could
     *                                                   not be used
     */
    ApplicantSummary summarise(ApplicantBrief brief);

    /**
     * What identifies a summary, and what the model is told.
     *
     * <p>{@code applicationId} and {@code scoreVersion} are the first two because they are the cache
     * key and nothing else — they are never rendered into the prompt. Same split as Story 13.5's
     * {@code MatchExplanationInput}, which carries its ids for the same reason.
     *
     * <p><strong>No score, no percentage, no ratio — not even in the breakdown.</strong> The
     * acceptance criterion says the summary must complement the score rather than restate it, and
     * design 24 C makes that checkable: no percentage, no x-of-y, no ranking language. The cheapest
     * way to guarantee it is not to prompt against it but to withhold the arithmetic — a model that
     * has never seen "82" or "skills 4/5" cannot echo them. So the breakdown arrives as
     * {@link FactorStrength}, which says <em>where</em> the candidate is strong or thin without saying
     * by how much, and {@code MatchFactor.detail} is deliberately dropped on the way in because it is
     * itself an x-of-y phrase.
     */
    record ApplicantBrief(Long applicationId,
                          String scoreVersion,
                          CandidateProfile profile,
                          Job job,
                          List<FactorSignal> factors) {
    }

    /** One dimension of the match, banded rather than scored. */
    record FactorSignal(MatchFactorKind kind, FactorStrength strength) {
    }

    /**
     * How well a candidate did on one dimension, coarsely enough that no number survives the
     * translation. Three bands rather than five: the model needs to know which way to lean, and a
     * finer scale would only invite it to reconstruct a percentage.
     */
    enum FactorStrength {
        STRONG,
        PARTIAL,
        WEAK;

        /** The 0-100 per-factor score {@code MatchFactor} carries, reduced to a band. */
        public static FactorStrength of(int score) {
            if (score >= 75) {
                return STRONG;
            }
            return score >= 40 ? PARTIAL : WEAK;
        }
    }
}
