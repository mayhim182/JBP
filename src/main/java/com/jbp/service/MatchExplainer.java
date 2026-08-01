package com.jbp.service;

import com.jbp.service.MatchScorer.MatchFactor;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Turns an <strong>already-computed</strong> match into two sentences of plain language plus one
 * concrete thing the candidate could fix.
 *
 * <p><strong>The score is an input here, never an output.</strong> That is the acceptance criterion and
 * it is enforced by the shape of this interface rather than by a rule someone has to remember: nothing
 * an implementation can return is capable of changing a number, because no number leaves it. Design 20
 * makes the same guarantee visible — the computed panel sits on a plain surface, the generated prose on
 * a tinted one, and the tinted one is labelled "does not affect your score".
 *
 * <p>Deliberately not a {@code MatchScorer}: scoring is deterministic and must never call a model, which
 * {@code ScoringNeverCallsAModelTest} enforces structurally. This is the opposite — it always calls a
 * model, and always degrades to the rule scorer's own wording when it cannot.
 */
public interface MatchExplainer {

    MatchExplanation explain(MatchExplanationInput input);

    /**
     * Everything the explanation is generated from — and therefore everything
     * {@code scoreVersion} has to cover.
     *
     * @param candidateId       part of the cache key, never sent to the model
     * @param jobId             part of the cache key, never sent to the model
     * @param scoreVersion      the staleness discriminator; see {@code ScoreVersion}
     * @param jobTitle          what the role is called
     * @param score             the computed 0-100, passed so the prose can agree with the ring
     * @param ruleReason        the deterministic wording, used verbatim when the model is unavailable
     * @param factors           the structured breakdown, so the model reasons over dimensions rather
     *                          than re-parsing a joined string
     * @param missingSkill      the highest-value skill this job names that the profile does not, or
     *                          {@code null} when there is nothing to suggest. Chosen deterministically
     *                          rather than by the model, because it is a factual claim about the
     *                          candidate's own data
     * @param strongMatchesNaming how many of the candidate's strongest matches also name
     *                          {@code missingSkill}, and out of how many — design 20's "6 of your 10".
     *                          <strong>Deferred deliberately.</strong> Counting it costs a scoring pass
     *                          over the candidate's matches, and the cache in front of this interface
     *                          returns before the delegate runs, so on a cache hit the work must not
     *                          happen. A plain value would be computed on every request including the
     *                          ones the cache exists to make free
     */
    record MatchExplanationInput(
            Long candidateId,
            Long jobId,
            String scoreVersion,
            String jobTitle,
            int score,
            String ruleReason,
            List<MatchFactor> factors,
            Set<String> jobSkills,
            Set<String> candidateSkills,
            String missingSkill,
            Supplier<SkillDemand> strongMatchesNaming) {
    }

    /**
     * How common a skill is across the candidate's strongest matches, which is what makes the
     * suggestion credible rather than a guess about one job.
     *
     * @param namingCount how many of those matches require the skill
     * @param consideredCount how many matches were looked at
     */
    record SkillDemand(int namingCount, int consideredCount) {

        public boolean isWorthStating() {
            return namingCount > 0 && consideredCount > 0;
        }
    }

    /**
     * @param summary       two sentences, or the rule reason when the model was unavailable
     * @param actionText    the full "one thing that would help" sentence, or {@code null} when the
     *                      profile already covers everything this job asks for
     * @param actionSkill   the skill named inside {@code actionText}. Returned separately so the UI can
     *                      emphasise it — design 20 bolds it — without inferring structure from prose
     * @param generated     true when a model wrote {@code summary}. Drives design 20's
     *                      "AI-written · does not affect your score" label, which must not appear above
     *                      wording the rule scorer produced
     */
    record MatchExplanation(String summary, String actionText, String actionSkill, boolean generated) {

        /** The non-AI answer: the deterministic wording the candidate would have seen anyway. */
        public static MatchExplanation fromRules(String ruleReason, String actionText, String actionSkill) {
            return new MatchExplanation(ruleReason, actionText, actionSkill, false);
        }
    }
}
