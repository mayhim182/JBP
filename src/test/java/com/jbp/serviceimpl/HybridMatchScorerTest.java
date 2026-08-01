package com.jbp.serviceimpl;

import com.jbp.model.CandidateProfile;
import com.jbp.model.EmbeddingOwnerType;
import com.jbp.model.Experience;
import com.jbp.model.Job;
import com.jbp.model.MatchFactorKind;
import com.jbp.model.ScorerMode;
import com.jbp.model.SeniorityLevel;
import com.jbp.service.EmbeddingStore;
import com.jbp.service.MatchScorer.MatchFactor;
import com.jbp.service.MatchScorer.MatchResult;
import com.jbp.util.SemanticScoreCalibration;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/** Story 13.4 — rules and meaning combined, with both still visible. */
class HybridMatchScorerTest {

    /** Cosine 0.992 against {@link #NEARLY_FRONTEND}, which calibrates to a semantic 100. */
    private static final float[] FRONTEND = {0.6f, 0.0f, 0.8f};
    private static final float[] NEARLY_FRONTEND = {0.64f, 0.1f, 0.76f};
    /** Orthogonal, so cosine 0 — below the floor, which calibrates to a semantic 0. */
    private static final float[] UNRELATED = {0.0f, 1.0f, 0.0f};

    private static final int DEFAULT_RULE_WEIGHT = 70;

    /**
     * A fixture band, deliberately <strong>not</strong> the production one. Re-deriving the real band
     * from a new embedding model must never break a scorer's unit tests — what is under test here is the
     * combination arithmetic, and the vectors above are chosen to clamp to a clean 100 and 0 under any
     * plausible band.
     */
    private static final SemanticScoreCalibration FIXTURE_BAND = new SemanticScoreCalibration(0.55, 0.90);

    private final EmbeddingStore embeddingStore = Mockito.mock(EmbeddingStore.class);
    private final RuleBasedMatchScorer ruleBased = new RuleBasedMatchScorer();

    private final HybridMatchScorer scorer = hybridWeighted(DEFAULT_RULE_WEIGHT);

    @Test
    void reproducesTheWeightsDesignTwentyDraws() {
        givenVectors(FRONTEND, NEARLY_FRONTEND);

        MatchResult result = scorer.score(perfectProfile(), perfectJob());

        assertThat(result.factors()).extracting(MatchFactor::kind)
                .containsExactly(MatchFactorKind.SKILLS, MatchFactorKind.SENIORITY,
                        MatchFactorKind.LOCATION, MatchFactorKind.EXPERIENCE, MatchFactorKind.SEMANTIC);
        assertThat(result.factors()).extracting(MatchFactor::weight)
                .as("design 20 shows 35 / 14 / 11 / 10 against the rule rows and 30 against semantic")
                .containsExactly(35, 14, 11, 10, 30);
    }

    @Test
    void weightsStillSumToOneHundredSoTheBarsAddUp() {
        givenVectors(FRONTEND, NEARLY_FRONTEND);

        assertThat(scorer.score(perfectProfile(), perfectJob()).factors().stream()
                .mapToInt(MatchFactor::weight).sum())
                .isEqualTo(100);
    }

    @Test
    void totalEqualsTheSumOfContributions() {
        givenVectors(FRONTEND, NEARLY_FRONTEND);

        MatchResult result = scorer.score(
                profile(SeniorityLevel.JUNIOR, Set.of("java"), 1, "Pune"),
                job(SeniorityLevel.SENIOR, Set.of("java", "aws", "sql"), "Mumbai"));

        assertThat(result.score())
                .isEqualTo(result.factors().stream().mapToInt(MatchFactor::contribution).sum());
    }

    @Test
    void reportsHybridMode() {
        givenVectors(FRONTEND, NEARLY_FRONTEND);

        assertThat(scorer.score(perfectProfile(), perfectJob()).mode()).isEqualTo(ScorerMode.HYBRID);
    }

    @Test
    void scoresAPerfectMatchOnBothSignalsAtOneHundred() {
        givenVectors(FRONTEND, NEARLY_FRONTEND);

        assertThat(scorer.score(perfectProfile(), perfectJob()).score()).isEqualTo(100);
    }

    @Test
    void capsAPerfectRuleMatchAtTheRuleWeightWhenMeaningContributesNothing() {
        givenVectors(FRONTEND, UNRELATED);

        assertThat(scorer.score(perfectProfile(), perfectJob()).score())
                .as("35 + 14 + 11 + 10 with a semantic row of zero — the cost of blending, stated plainly")
                .isEqualTo(70);
    }

    @Test
    void surfacesAMatchNoKeywordWouldFindWhichIsWhyEpicThirteenExists() {
        givenVectors(FRONTEND, NEARLY_FRONTEND);

        // Not one shared skill, but the embeddings are nearly identical — "built single page apps"
        // against "React developer". Rules alone would score this on seniority and location only.
        MatchResult result = scorer.score(
                profile(SeniorityLevel.MID, Set.of("python"), 3, "Pune"),
                job(SeniorityLevel.MID, Set.of("java"), "Pune"));

        assertThat(result.surfacedByMeaning())
                .as("semantic contributed 30 against a skills contribution of 0")
                .isTrue();
        assertThat(result.score()).isEqualTo(65);
    }

    @Test
    void doesNotClaimMeaningSurfacedAMatchTheSkillsAlreadyExplain() {
        givenVectors(FRONTEND, UNRELATED);

        assertThat(scorer.score(perfectProfile(), perfectJob()).surfacedByMeaning())
                .as("skills contributed 35 against a semantic contribution of 0")
                .isFalse();
    }

    @Test
    void reusesTheRuleScorersOwnWordingRatherThanRecomputingIt() {
        givenVectors(FRONTEND, NEARLY_FRONTEND);
        CandidateProfile profile = profile(SeniorityLevel.MID, Set.of("java"), 0, "Pune");
        Job job = job(SeniorityLevel.MID, Set.of("java", "aws"), "Mumbai");

        List<String> hybridDetails = scorer.score(profile, job).factors().stream()
                .filter(factor -> factor.kind() != MatchFactorKind.SEMANTIC)
                .map(MatchFactor::detail)
                .toList();

        assertThat(hybridDetails)
                .as("two places deciding what \"skills 1/2\" means would disagree the first time one changed")
                .isEqualTo(ruleBased.score(profile, job).factors().stream()
                        .map(MatchFactor::detail).toList());
    }

    @Test
    void fallsBackToAFullRuleScoreWhenTheCandidateHasNoVector() {
        Mockito.when(embeddingStore.findVector(EmbeddingOwnerType.CANDIDATE_PROFILE, 1L))
                .thenReturn(Optional.empty());
        Mockito.when(embeddingStore.findVector(EmbeddingOwnerType.JOB, 2L))
                .thenReturn(Optional.of(FRONTEND));

        MatchResult result = scorer.score(perfectProfile(), perfectJob());

        assertThat(result.mode()).isEqualTo(ScorerMode.RULE);
        assertThat(result.score())
                .as("scoring a missing vector as semantic-zero would cap a perfect match at 70 — a wrong "
                        + "number rather than an error, and the whole reason the template method is final")
                .isEqualTo(100)
                .isEqualTo(ruleBased.score(perfectProfile(), perfectJob()).score());
    }

    @Test
    void fallsBackToAFullRuleScoreWhenTheJobHasNoVector() {
        Mockito.when(embeddingStore.findVector(EmbeddingOwnerType.CANDIDATE_PROFILE, 1L))
                .thenReturn(Optional.of(FRONTEND));
        Mockito.when(embeddingStore.findVector(EmbeddingOwnerType.JOB, 2L)).thenReturn(Optional.empty());

        MatchResult result = scorer.score(perfectProfile(), perfectJob());

        assertThat(result.mode()).isEqualTo(ScorerMode.RULE);
        assertThat(result.score()).isEqualTo(100);
    }

    @Test
    void fallsBackForACandidateWhoHasNeverSavedAProfile() {
        MatchResult result = scorer.score(CandidateProfile.builder().build(), perfectJob());

        assertThat(result.mode()).isEqualTo(ScorerMode.RULE);
        Mockito.verify(embeddingStore, Mockito.never())
                .findVector(Mockito.eq(EmbeddingOwnerType.CANDIDATE_PROFILE), Mockito.isNull());
    }

    @Test
    void appliesAConfiguredWeightToEveryRowIncludingTheSemanticOne() {
        givenVectors(FRONTEND, NEARLY_FRONTEND);

        MatchResult result = hybridWeighted(50).score(perfectProfile(), perfectJob());

        assertThat(result.factors()).extracting(MatchFactor::weight)
                .containsExactly(25, 10, 8, 7, 50);
        assertThat(result.factors().stream().mapToInt(MatchFactor::weight).sum()).isEqualTo(100);
    }

    @Test
    void refusesAWeightThatWouldMakeItNotHybrid() {
        assertThatIllegalArgumentException().isThrownBy(() -> hybridWeighted(100))
                .withMessageContaining("must be between 1 and 99");
        assertThatIllegalArgumentException().isThrownBy(() -> hybridWeighted(0))
                .withMessageContaining("must be between 1 and 99");
    }

    @Test
    void makesNoProviderCallBecauseItReadsStoredVectors() {
        givenVectors(FRONTEND, NEARLY_FRONTEND);

        scorer.score(perfectProfile(), perfectJob());

        Mockito.verify(embeddingStore).findVector(EmbeddingOwnerType.CANDIDATE_PROFILE, 1L);
        Mockito.verify(embeddingStore).findVector(EmbeddingOwnerType.JOB, 2L);
        Mockito.verifyNoMoreInteractions(embeddingStore);
    }

    @Test
    void producesTheSameScoreForTheSameInputsEveryTime() {
        givenVectors(FRONTEND, NEARLY_FRONTEND);

        assertThat(scorer.score(perfectProfile(), perfectJob()).score())
                .isEqualTo(scorer.score(perfectProfile(), perfectJob()).score());
        assertThat(scorer.score(perfectProfile(), perfectJob()).reason())
                .isEqualTo(scorer.score(perfectProfile(), perfectJob()).reason());
    }

    private HybridMatchScorer hybridWeighted(int ruleWeight) {
        return new HybridMatchScorer(embeddingStore, ruleBased, FIXTURE_BAND, ruleWeight);
    }

    private void givenVectors(float[] candidateVector, float[] jobVector) {
        Mockito.when(embeddingStore.findVector(EmbeddingOwnerType.CANDIDATE_PROFILE, 1L))
                .thenReturn(Optional.of(candidateVector));
        Mockito.when(embeddingStore.findVector(EmbeddingOwnerType.JOB, 2L))
                .thenReturn(Optional.of(jobVector));
    }

    /** Scores 100 on every rule dimension, so any cap shows up immediately. */
    private CandidateProfile perfectProfile() {
        return profile(SeniorityLevel.MID, Set.of("java"), 3, "Pune");
    }

    private Job perfectJob() {
        return job(SeniorityLevel.MID, Set.of("java"), "Pune");
    }

    private CandidateProfile profile(SeniorityLevel seniority, Set<String> skills, int roles, String location) {
        return CandidateProfile.builder()
                .id(1L)
                .seniority(seniority)
                .skills(skills)
                .location(location)
                .experiences(roles == 0 ? List.of() : List.copyOf(
                        Collections.nCopies(roles, Experience.builder().title("Engineer").build())))
                .build();
    }

    private Job job(SeniorityLevel seniority, Set<String> skills, String location) {
        return Job.builder().id(2L).seniority(seniority).skills(skills).location(location).build();
    }
}
