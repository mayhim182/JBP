package com.jbp.serviceimpl;

import com.jbp.model.CandidateProfile;
import com.jbp.model.EmbeddingOwnerType;
import com.jbp.model.Job;
import com.jbp.model.MatchFactorKind;
import com.jbp.model.ScorerMode;
import com.jbp.model.SeniorityLevel;
import com.jbp.service.EmbeddingStore;
import com.jbp.service.MatchScorer.MatchResult;
import com.jbp.util.SemanticScoreCalibration;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 13.3, and Story 13.2's last acceptance criterion: <strong>a missing or stale embedding never
 * breaks search — it falls back to rules.</strong> That fallback lives here, which is why 13.2 left the
 * criterion open rather than claiming behaviour with nowhere to happen.
 */
class EmbeddingMatchScorerTest {

    private static final float[] FRONTEND = {0.6f, 0.0f, 0.8f};
    private static final float[] NEARLY_FRONTEND = {0.64f, 0.1f, 0.76f};
    private static final float[] UNRELATED = {0.0f, 1.0f, 0.0f};

    /**
     * A fixture band, deliberately <strong>not</strong> the production one. Re-deriving the real band from
     * a new embedding model must never break a scorer's unit tests — the vectors above clamp to a clean
     * 100 and 0 under any plausible band.
     */
    private static final SemanticScoreCalibration FIXTURE_BAND = new SemanticScoreCalibration(0.55, 0.90);

    private final EmbeddingStore embeddingStore = Mockito.mock(EmbeddingStore.class);
    private final RuleBasedMatchScorer ruleBased = new RuleBasedMatchScorer();

    private final EmbeddingMatchScorer scorer =
            new EmbeddingMatchScorer(embeddingStore, ruleBased, FIXTURE_BAND);

    @Test
    void scoresOnMeaningWhenBothVectorsExist() {
        givenVectors(FRONTEND, NEARLY_FRONTEND);

        MatchResult result = scorer.score(profile(), job());

        assertThat(result.mode()).isEqualTo(ScorerMode.EMBEDDING);
        assertThat(result.factors()).singleElement()
                .satisfies(factor -> assertThat(factor.kind()).isEqualTo(MatchFactorKind.SEMANTIC));
        assertThat(result.score()).isBetween(1, 100);
    }

    @Test
    void reportsThatMeaningSurfacedTheMatch() {
        givenVectors(FRONTEND, NEARLY_FRONTEND);

        assertThat(scorer.score(profile(), job()).surfacedByMeaning())
                .as("meaning is the only contributor in this mode, so the claim is true by construction")
                .isTrue();
    }

    @Test
    void scoresAnUnrelatedJobAtZeroRatherThanAMisleadingMiddle() {
        givenVectors(FRONTEND, UNRELATED);

        assertThat(scorer.score(profile(), job()).score()).isZero();
    }

    @Test
    void fallsBackToRulesWhenTheCandidateHasNoVector() {
        Mockito.when(embeddingStore.findVector(EmbeddingOwnerType.CANDIDATE_PROFILE, 1L))
                .thenReturn(Optional.empty());
        Mockito.when(embeddingStore.findVector(EmbeddingOwnerType.JOB, 2L))
                .thenReturn(Optional.of(FRONTEND));

        MatchResult result = scorer.score(profile(), job());

        assertThat(result.mode())
                .as("Story 13.2 AC: a missing vector must never break scoring")
                .isEqualTo(ScorerMode.RULE);
        assertThat(result.score()).isEqualTo(ruleBased.score(profile(), job()).score());
    }

    @Test
    void fallsBackToRulesWhenTheJobHasNoVector() {
        Mockito.when(embeddingStore.findVector(EmbeddingOwnerType.CANDIDATE_PROFILE, 1L))
                .thenReturn(Optional.of(FRONTEND));
        Mockito.when(embeddingStore.findVector(EmbeddingOwnerType.JOB, 2L)).thenReturn(Optional.empty());

        assertThat(scorer.score(profile(), job()).mode()).isEqualTo(ScorerMode.RULE);
    }

    @Test
    void fallsBackWhenAStoredVectorIsStale() {
        // The store reports a vector from another model or dimension as absent — Story 13.2's design,
        // and the reason a configuration change degrades instead of computing a meaningless cosine.
        Mockito.when(embeddingStore.findVector(Mockito.any(), Mockito.any()))
                .thenReturn(Optional.empty());

        MatchResult result = scorer.score(profile(), job());

        assertThat(result.mode()).isEqualTo(ScorerMode.RULE);
        assertThat(result.factors()).hasSize(4);
    }

    @Test
    void fallsBackForACandidateWhoHasNeverSavedAProfile() {
        MatchResult result = scorer.score(CandidateProfile.builder().build(), job());

        assertThat(result.mode()).isEqualTo(ScorerMode.RULE);
        Mockito.verify(embeddingStore, Mockito.never())
                .findVector(Mockito.eq(EmbeddingOwnerType.CANDIDATE_PROFILE), Mockito.isNull());
    }

    @Test
    void makesNoProviderCallBecauseItReadsStoredVectors() {
        givenVectors(FRONTEND, NEARLY_FRONTEND);

        scorer.score(profile(), job());

        // EmbeddingStore is the only collaborator; there is no EmbeddingClient here at all, which is
        // what makes a page of matches cost no quota and immune to provider latency.
        Mockito.verify(embeddingStore).findVector(EmbeddingOwnerType.CANDIDATE_PROFILE, 1L);
        Mockito.verify(embeddingStore).findVector(EmbeddingOwnerType.JOB, 2L);
        Mockito.verifyNoMoreInteractions(embeddingStore);
    }

    @Test
    void producesTheSameScoreForTheSameInputsEveryTime() {
        givenVectors(FRONTEND, NEARLY_FRONTEND);

        assertThat(scorer.score(profile(), job()).score())
                .isEqualTo(scorer.score(profile(), job()).score());
    }

    private void givenVectors(float[] candidateVector, float[] jobVector) {
        Mockito.when(embeddingStore.findVector(EmbeddingOwnerType.CANDIDATE_PROFILE, 1L))
                .thenReturn(Optional.of(candidateVector));
        Mockito.when(embeddingStore.findVector(EmbeddingOwnerType.JOB, 2L))
                .thenReturn(Optional.of(jobVector));
    }

    private CandidateProfile profile() {
        return CandidateProfile.builder().id(1L).seniority(SeniorityLevel.MID)
                .skills(Set.of("java")).location("Pune").build();
    }

    private Job job() {
        return Job.builder().id(2L).seniority(SeniorityLevel.MID)
                .skills(Set.of("java")).location("Pune").build();
    }
}
