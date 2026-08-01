package com.jbp.util;

import com.jbp.model.ScorerMode;
import com.jbp.service.MatchScorer.MatchFactor;

import java.util.List;
import java.util.stream.Collectors;

/**
 * A fingerprint of everything that can move a match score, used as the cache key for its explanation
 * and returned on both calls of Story 13.5's two-call split.
 *
 * <p><strong>Derived, never hand-bumped.</strong> A maintained integer goes stale silently the first
 * time someone retunes a weight and forgets, and the symptom is a candidate reading yesterday's
 * explanation of today's number.
 *
 * <p>What goes in, and why each part is not optional:
 * <ul>
 *   <li><strong>The algorithm version.</strong> Covers what hashing data and configuration cannot: a
 *       change to the scoring <em>code</em>, such as how a cosine is normalised. Config drift and code
 *       drift are different, and only one of them is visible in properties. <strong>Bump this constant
 *       whenever scoring maths changes.</strong></li>
 *   <li><strong>The scorer mode.</strong> Switching rule → hybrid moves every score in the system.</li>
 *   <li><strong>The score and the full factor breakdown.</strong> The weights live here rather than
 *       being read back out of configuration: they <em>are</em> the weight set, already resolved, so a
 *       retune cannot be missed. Including the score itself makes the version change whenever anything
 *       on screen changes, which no purely configuration-derived key can promise.</li>
 *   <li><strong>Both source hashes.</strong> Deriving from configuration alone leaves the key unchanged
 *       when a candidate edits their profile while the score moves. These are the same
 *       {@code sha256(EmbeddingTexts…)} values Story 13.2 stores, so the system has <em>one</em>
 *       change-detection mechanism rather than two that can disagree — and they are computed rather
 *       than read back, so this works with AI switched off and no embedding row present.</li>
 * </ul>
 *
 * <p>Both the score and the source hashes are included even though they overlap: the breakdown catches
 * anything that changes the number, and the source hashes catch profile wording changes that alter the
 * <em>prose</em> without moving the number — a rewritten headline against unchanged skills, for example.
 * The explanation is generated from both, so the key has to cover both.
 */
public final class ScoreVersion {

    /**
     * Bump when scoring maths changes in a way that data and configuration do not capture.
     * <p>v1 — Story 13.5, first version. Scoring as of the 13.4 hybrid scorer.
     */
    private static final String ALGORITHM_VERSION = "v1";

    /**
     * 16 hex characters, 64 bits. This discriminates staleness <em>within</em> one candidate/job pair —
     * both ids are already part of the cache key — so it is not a security token and does not need the
     * full digest. Short enough to sit in a URL and a client-side cache key without noise.
     */
    private static final int FINGERPRINT_LENGTH = 16;

    private static final String FIELD_SEPARATOR = "|";

    private ScoreVersion() {
    }

    /**
     * @param candidateSourceHash {@code TextHash.sha256Hex(EmbeddingTexts.forCandidateProfile(profile))}
     * @param jobSourceHash       {@code TextHash.sha256Hex(EmbeddingTexts.forJob(job))}
     */
    public static String of(ScorerMode mode,
                            int score,
                            List<MatchFactor> factors,
                            String candidateSourceHash,
                            String jobSourceHash) {
        String fingerprint = String.join(FIELD_SEPARATOR,
                ALGORITHM_VERSION,
                String.valueOf(mode),
                String.valueOf(score),
                breakdownSignatureOf(factors),
                String.valueOf(candidateSourceHash),
                String.valueOf(jobSourceHash));
        return TextHash.sha256Hex(fingerprint).substring(0, FINGERPRINT_LENGTH);
    }

    /**
     * Every component of every factor, in order. {@code detail} is included because it is user-visible
     * wording — "skills 1/2" changing to "skills 2/2" must invalidate an explanation that quoted it,
     * even in the rare case where the total lands on the same number.
     */
    private static String breakdownSignatureOf(List<MatchFactor> factors) {
        if (factors == null || factors.isEmpty()) {
            return "";
        }
        return factors.stream()
                .map(factor -> factor.kind() + ":" + factor.weight() + ":" + factor.score()
                        + ":" + factor.detail())
                .collect(Collectors.joining(";"));
    }
}
