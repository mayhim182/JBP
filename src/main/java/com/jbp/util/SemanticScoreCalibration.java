package com.jbp.util;

/**
 * Turns a raw cosine into a 0-100 score. <strong>The only place that mapping exists.</strong>
 *
 * <p><strong>Why a mapping is needed at all.</strong> Embedding cosines are not percentages and must
 * never be shown as one. Measured with {@code gemini-embedding-001} on real profile/job documents, a
 * candidate and a job with <em>nothing whatsoever in common</em> — a React developer against a diesel
 * mechanic vacancy — still score <strong>0.553-0.580</strong>. So {@code (int) (cosine * 100)} would tell
 * a candidate an irrelevant job is a 58% match, and would squeeze every real distinction into the top
 * third of the scale.
 *
 * <p><strong>The mapping.</strong> Linear rescale of {@code [floor, ceiling]} onto {@code [0, 100]},
 * clamped at both ends.
 *
 * <p><strong>Both numbers are measured, not estimated</strong> — re-derived on 2026-08-01 by
 * {@code MatchScorerComparisonHarness} over eight real fixture pairs, recorded in
 * {@code docs/match-scoring-comparison.md}. Floor 0.580 is the highest cosine any unrelated pair reached;
 * ceiling 0.868 is the mean over pairs that are the same role described the same way. The resulting
 * separation is clean and monotone:
 *
 * <pre>
 *   same role, same wording      0.847-0.879  ->  93-100
 *   same role, different words   0.753-0.784  ->  60-71   &lt;- the case Epic 13 exists for
 *   adjacent discipline          0.699        ->  41
 *   different industry           0.553-0.580  ->  0
 * </pre>
 *
 * <p>They replaced an estimated 0.55/0.90 taken from two short phrases, which was wrong at <em>both</em>
 * ends: 0.55 sat below the worst unrelated pair, so irrelevant jobs scored 1-8 rather than 0, and 0.90 sat
 * above every real strong pair, so a genuine match could never reach 100. Short phrases and real documents
 * do not occupy the same range, and only real vectors could have shown that.
 *
 * <p>Still configuration rather than constants, because <strong>the band is a property of the embedding
 * model</strong>: change the model or the dimension count and it must be re-derived.
 */
public final class SemanticScoreCalibration {

    private final double floor;
    private final double ceiling;

    public SemanticScoreCalibration(double floor, double ceiling) {
        if (ceiling <= floor) {
            throw new IllegalArgumentException(
                    "Semantic ceiling (" + ceiling + ") must exceed the floor (" + floor + ")");
        }
        this.floor = floor;
        this.ceiling = ceiling;
    }

    /**
     * @param cosine raw similarity, typically 0.5-1.0 from this model
     * @return 0-100. At or below the floor this is 0 — "no measurable relationship" rather than "53%".
     */
    public int toScore(double cosine) {
        double positionInBand = (cosine - floor) / (ceiling - floor);
        double clamped = Math.max(0.0, Math.min(1.0, positionInBand));
        return (int) Math.round(clamped * 100);
    }

    public double floor() {
        return floor;
    }

    public double ceiling() {
        return ceiling;
    }
}
