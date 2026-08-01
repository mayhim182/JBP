package com.jbp.util;

import com.jbp.model.MatchFactorKind;
import com.jbp.model.ScorerMode;
import com.jbp.service.MatchScorer.MatchFactor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Story 13.5 — the derived fingerprint that keeps an explanation from outliving its score. */
class ScoreVersionTest {

    private static final List<MatchFactor> FACTORS = List.of(
            new MatchFactor(MatchFactorKind.SKILLS, 35, 100, "skills 2 of 2"),
            new MatchFactor(MatchFactorKind.SEMANTIC, 30, 80, "strong"));

    private static final String CANDIDATE_HASH = "aaaa1111";
    private static final String JOB_HASH = "bbbb2222";

    @Test
    void isStableForIdenticalInputs() {
        assertThat(version()).isEqualTo(version());
    }

    @Test
    void changesWhenTheScoreMoves() {
        assertThat(ScoreVersion.of(ScorerMode.HYBRID, 71, FACTORS, CANDIDATE_HASH, JOB_HASH))
                .isNotEqualTo(version());
    }

    @Test
    void changesWhenTheScorerModeChanges() {
        assertThat(ScoreVersion.of(ScorerMode.RULE, 70, FACTORS, CANDIDATE_HASH, JOB_HASH))
                .as("switching rule to hybrid moves every score in the system")
                .isNotEqualTo(version());
    }

    @Test
    void changesWhenAWeightIsRetunedEvenIfTheTotalHappensToLandTheSame() {
        List<MatchFactor> retuned = List.of(
                new MatchFactor(MatchFactorKind.SKILLS, 50, 100, "skills 2 of 2"),
                new MatchFactor(MatchFactorKind.SEMANTIC, 15, 80, "strong"));

        assertThat(ScoreVersion.of(ScorerMode.HYBRID, 70, retuned, CANDIDATE_HASH, JOB_HASH))
                .as("the weights are the weight set, so a retune cannot be missed")
                .isNotEqualTo(version());
    }

    @Test
    void changesWhenAFactorsWordingChanges() {
        List<MatchFactor> reworded = List.of(
                new MatchFactor(MatchFactorKind.SKILLS, 35, 100, "skills 3 of 3"),
                new MatchFactor(MatchFactorKind.SEMANTIC, 30, 80, "strong"));

        assertThat(ScoreVersion.of(ScorerMode.HYBRID, 70, reworded, CANDIDATE_HASH, JOB_HASH))
                .as("an explanation that quoted the old wording must not survive it")
                .isNotEqualTo(version());
    }

    @Test
    void changesWhenTheCandidateEditsTheirProfile() {
        assertThat(ScoreVersion.of(ScorerMode.HYBRID, 70, FACTORS, "cccc3333", JOB_HASH))
                .as("configuration alone would leave the key unchanged while the prose went stale")
                .isNotEqualTo(version());
    }

    @Test
    void changesWhenTheJobIsEdited() {
        assertThat(ScoreVersion.of(ScorerMode.HYBRID, 70, FACTORS, CANDIDATE_HASH, "dddd4444"))
                .isNotEqualTo(version());
    }

    @Test
    void isShortEnoughToLiveInAUrlAndACacheKey() {
        assertThat(version()).hasSize(16).matches("[0-9a-f]+");
    }

    @Test
    void handlesAResultWithNoBreakdownAtAll() {
        assertThat(ScoreVersion.of(ScorerMode.RULE, 0, List.of(), CANDIDATE_HASH, JOB_HASH))
                .as("the rule-only MatchResult shorthand carries no factors")
                .isNotBlank();
    }

    private String version() {
        return ScoreVersion.of(ScorerMode.HYBRID, 70, FACTORS, CANDIDATE_HASH, JOB_HASH);
    }
}
