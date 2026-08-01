package com.jbp.serviceimpl;

import com.jbp.dto.JobMatchResponse;
import com.jbp.dto.JobMatchScoreResponse;
import com.jbp.dto.JobResponse;
import com.jbp.exception.ResourceNotFoundException;
import com.jbp.mapper.JobMapper;
import com.jbp.model.CandidateProfile;
import com.jbp.model.Job;
import com.jbp.model.JobStatus;
import com.jbp.repository.JobRepository;
import com.jbp.security.CurrentUserProvider;
import com.jbp.dto.MatchExplanationResponse;
import com.jbp.dto.MatchFactorResponse;
import com.jbp.model.MatchFactorKind;
import com.jbp.model.ScorerMode;
import com.jbp.service.CandidateProfileService;
import com.jbp.service.MatchExplainer;
import com.jbp.service.MatchExplainer.MatchExplanation;
import com.jbp.service.MatchExplainer.MatchExplanationInput;
import com.jbp.service.MatchScorer;
import com.jbp.service.MatchScorer.MatchFactor;
import com.jbp.service.MatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 13.0 — one match request per list, not one per row.
 *
 * <p>The invocation-count assertions are the point of this class, not decoration. A batch endpoint
 * that loops the existing single-job method internally would satisfy every value assertion here
 * while leaving the redundant profile loads exactly as they were, so the counts are what stop that
 * from being written later.
 */
class MatchServiceImplTest {

    private static final Long CANDIDATE_ID = 7L;
    private static final int SCORE = 70;
    private static final String REASON = "3 of 5 skills overlap";

    private final JobRepository jobRepository = Mockito.mock(JobRepository.class);
    private final CandidateProfileService candidateProfileService =
            Mockito.mock(CandidateProfileService.class);
    private final CurrentUserProvider currentUserProvider = Mockito.mock(CurrentUserProvider.class);
    private final MatchScorer matchScorer = Mockito.mock(MatchScorer.class);
    private final MatchExplainer matchExplainer = Mockito.mock(MatchExplainer.class);
    private final JobMapper jobMapper = Mockito.mock(JobMapper.class);

    private final MatchServiceImpl matchService = new MatchServiceImpl(
            jobRepository, candidateProfileService, currentUserProvider, matchScorer,
            matchExplainer, jobMapper);

    @BeforeEach
    void currentCandidateHasAProfileAndEveryJobScoresTheSame() {
        Mockito.when(currentUserProvider.getCurrentUserId()).thenReturn(CANDIDATE_ID);
        Mockito.when(candidateProfileService.findProfileForCandidate(CANDIDATE_ID))
                .thenReturn(Optional.of(CandidateProfile.builder().build()));
        Mockito.when(matchScorer.score(Mockito.any(), Mockito.any()))
                .thenReturn(new MatchScorer.MatchResult(SCORE, REASON));
        Mockito.when(jobMapper.toResponse(Mockito.any(Job.class)))
                .thenReturn(JobResponse.builder().build());
    }

    @Test
    void resolvesTheCandidateProfileOnceForAWholeBatchRatherThanOncePerJob() {
        List<Long> tenJobIds = idsUpTo(10);
        Mockito.when(jobRepository.findByIdInAndStatus(tenJobIds, JobStatus.PUBLISHED))
                .thenReturn(publishedJobs(10));

        matchService.getJobMatchScoresForCurrentCandidate(tenJobIds);

        Mockito.verify(candidateProfileService, Mockito.times(1))
                .findProfileForCandidate(CANDIDATE_ID);
    }

    @Test
    void readsTheJobsInOneQueryForAWholeBatch() {
        List<Long> tenJobIds = idsUpTo(10);
        Mockito.when(jobRepository.findByIdInAndStatus(tenJobIds, JobStatus.PUBLISHED))
                .thenReturn(publishedJobs(10));

        matchService.getJobMatchScoresForCurrentCandidate(tenJobIds);

        Mockito.verify(jobRepository, Mockito.times(1))
                .findByIdInAndStatus(tenJobIds, JobStatus.PUBLISHED);
        Mockito.verify(jobRepository, Mockito.never()).findById(Mockito.anyLong());
    }

    @Test
    void doesNotMapTheWholeJobForABatchScoreBecauseTheCallerAlreadyHasIt() {
        List<Long> threeJobIds = idsUpTo(3);
        Mockito.when(jobRepository.findByIdInAndStatus(threeJobIds, JobStatus.PUBLISHED))
                .thenReturn(publishedJobs(3));

        matchService.getJobMatchScoresForCurrentCandidate(threeJobIds);

        Mockito.verify(jobMapper, Mockito.never()).toResponse(Mockito.any(Job.class));
    }

    @Test
    void scoresEveryJobItWasHandedAndIdentifiesEachByItsId() {
        List<Long> threeJobIds = idsUpTo(3);
        Mockito.when(jobRepository.findByIdInAndStatus(threeJobIds, JobStatus.PUBLISHED))
                .thenReturn(publishedJobs(3));

        List<JobMatchScoreResponse> scores =
                matchService.getJobMatchScoresForCurrentCandidate(threeJobIds);

        assertThat(scores).extracting(JobMatchScoreResponse::getJobId)
                .containsExactly(1L, 2L, 3L);
        assertThat(scores).allSatisfy(score -> {
            assertThat(score.getMatchScore()).isEqualTo(SCORE);
            assertThat(score.getMatchReason()).isEqualTo(REASON);
        });
    }

    @Test
    void agreesWithTheSingleJobEndpointForTheSameInputs() {
        Job job = publishedJob(1L);
        Mockito.when(jobRepository.findByIdInAndStatus(List.of(1L), JobStatus.PUBLISHED))
                .thenReturn(List.of(job));
        Mockito.when(jobRepository.findById(1L)).thenReturn(Optional.of(job));

        JobMatchScoreResponse batched =
                matchService.getJobMatchScoresForCurrentCandidate(List.of(1L)).get(0);
        JobMatchResponse single = matchService.getJobMatchForCurrentCandidate(1L);

        assertThat(batched.getMatchScore())
                .as("batching must be an efficiency change, never a scoring change")
                .isEqualTo(single.getMatchScore());
        assertThat(batched.getMatchReason()).isEqualTo(single.getMatchReason());
    }

    @Test
    void omitsIdsThatAreUnknownOrNotPublishedInsteadOfFailingTheWholeRequest() {
        List<Long> threeJobIds = idsUpTo(3);
        Mockito.when(jobRepository.findByIdInAndStatus(threeJobIds, JobStatus.PUBLISHED))
                .thenReturn(List.of(publishedJob(1L), publishedJob(3L)));

        List<JobMatchScoreResponse> scores =
                matchService.getJobMatchScoresForCurrentCandidate(threeJobIds);

        assertThat(scores).extracting(JobMatchScoreResponse::getJobId)
                .as("a missing job means a missing ring, exactly as when each score was its own call")
                .containsExactly(1L, 3L);
    }

    @Test
    void refusesMoreIdsThanTheLimitWithoutTouchingTheDatabase() {
        List<Long> tooMany = idsUpTo(MatchService.MAX_JOBS_PER_SCORE_REQUEST + 1);

        assertThatThrownBy(() -> matchService.getJobMatchScoresForCurrentCandidate(tooMany))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("At most " + MatchService.MAX_JOBS_PER_SCORE_REQUEST)
                .hasMessageContaining("51 were given");

        Mockito.verifyNoInteractions(jobRepository, candidateProfileService, matchScorer);
    }

    @Test
    void acceptsExactlyTheLimit() {
        List<Long> theLimit = idsUpTo(MatchService.MAX_JOBS_PER_SCORE_REQUEST);
        Mockito.when(jobRepository.findByIdInAndStatus(theLimit, JobStatus.PUBLISHED))
                .thenReturn(publishedJobs(MatchService.MAX_JOBS_PER_SCORE_REQUEST));

        assertThat(matchService.getJobMatchScoresForCurrentCandidate(theLimit))
                .hasSize(MatchService.MAX_JOBS_PER_SCORE_REQUEST);
    }

    @Test
    void returnsNothingForAnEmptyRequestWithoutLoadingAnything() {
        assertThat(matchService.getJobMatchScoresForCurrentCandidate(List.of())).isEmpty();

        Mockito.verifyNoInteractions(jobRepository, candidateProfileService, currentUserProvider);
    }

    @Test
    void scansOnlyTheNewestJobsForTheMatchesListInsteadOfTheWholeTable() {
        givenTheNewestPublishedJobsAre(publishedJobs(10));

        matchService.getJobMatchesForCurrentCandidate();

        Mockito.verify(jobRepository).findByStatus(
                JobStatus.PUBLISHED,
                PageRequest.of(0, MatchService.MAX_JOBS_SCANNED_FOR_MATCHES,
                        Sort.by(Sort.Direction.DESC, "id")));
        Mockito.verify(jobRepository, Mockito.never())
                .findByStatus(Mockito.any(JobStatus.class));
    }

    @Test
    void ranksTheMatchesListBestFirst() {
        givenTheNewestPublishedJobsAre(publishedJobs(3));
        // Job 1 scores worst, job 3 best, so a correct list reads 3, 2, 1.
        Mockito.when(matchScorer.score(Mockito.any(), Mockito.any())).thenAnswer(call -> {
            Job job = call.getArgument(1);
            return new MatchScorer.MatchResult(job.getId().intValue() * 10, REASON);
        });

        assertThat(matchService.getJobMatchesForCurrentCandidate())
                .extracting(JobMatchResponse::getMatchScore)
                .containsExactly(30, 20, 10);
    }

    @Test
    void stillReturnsAPlainListSoTheDashboardAndMatchesPageKeepWorking() {
        givenTheNewestPublishedJobsAre(publishedJobs(4));

        List<JobMatchResponse> matches = matchService.getJobMatchesForCurrentCandidate();

        assertThat(matches)
                .as("both consumers read this as a bare array and neither would fail at compile time")
                .hasSize(4);
        assertThat(matches).allSatisfy(match -> assertThat(match.getJob()).isNotNull());
    }

    @Test
    void resolvesTheCandidateProfileOnceForTheWholeMatchesListToo() {
        givenTheNewestPublishedJobsAre(publishedJobs(10));

        matchService.getJobMatchesForCurrentCandidate();

        Mockito.verify(candidateProfileService, Mockito.times(1))
                .findProfileForCandidate(CANDIDATE_ID);
    }

    @Test
    void stillHidesAnUnpublishedJobFromTheSingleJobEndpoint() {
        Mockito.when(jobRepository.findById(9L))
                .thenReturn(Optional.of(Job.builder().id(9L).status(JobStatus.DRAFT).build()));

        assertThatThrownBy(() -> matchService.getJobMatchForCurrentCandidate(9L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void scoresAgainstAnEmptyProfileWhenTheCandidateHasNotBuiltOneYet() {
        Mockito.when(candidateProfileService.findProfileForCandidate(CANDIDATE_ID))
                .thenReturn(Optional.empty());
        Mockito.when(jobRepository.findByIdInAndStatus(List.of(1L), JobStatus.PUBLISHED))
                .thenReturn(List.of(publishedJob(1L)));

        assertThat(matchService.getJobMatchScoresForCurrentCandidate(List.of(1L)))
                .as("a candidate with no profile still sees rings, they are just low")
                .hasSize(1);
    }

    // ---------------------------------------------------------------- Story 13.5

    @Test
    void returnsTheBreakdownStructurallyRatherThanOnlyAsAJoinedString() {
        givenTheSingleJobScoresWithAFullBreakdown();

        List<MatchFactorResponse> factors = matchService.getJobMatchForCurrentCandidate(1L).getFactors();

        assertThat(factors)
                .as("P11: the bars were drawn but unfed — a client must not have to parse the reason back")
                .extracting(MatchFactorResponse::getKind)
                .containsExactly("SKILLS", "SEMANTIC");
        assertThat(factors).extracting(MatchFactorResponse::getLabel)
                .as("design 20: a candidate cannot act on \"cosine similarity\"")
                .containsExactly("Skills", "Role similarity");
        assertThat(factors).extracting(MatchFactorResponse::getContribution)
                .containsExactly(35, 30);
    }

    @Test
    void sendsTheScorerModeAndMeaningChipRatherThanLettingTheClientInferThem() {
        givenTheSingleJobScoresWithAFullBreakdown();

        JobMatchResponse match = matchService.getJobMatchForCurrentCandidate(1L);

        assertThat(match.getScorerMode()).isEqualTo("HYBRID");
        assertThat(match.isSurfacedByMeaning()).isTrue();
    }

    @Test
    void givesTheSameScoreVersionForTheSameInputsEveryTime() {
        givenTheSingleJobScoresWithAFullBreakdown();

        assertThat(matchService.getJobMatchForCurrentCandidate(1L).getScoreVersion())
                .isEqualTo(matchService.getJobMatchForCurrentCandidate(1L).getScoreVersion());
    }

    @Test
    void changesTheScoreVersionWhenTheScoreMoves() {
        givenTheSingleJobScoresWithAFullBreakdown();
        String before = matchService.getJobMatchForCurrentCandidate(1L).getScoreVersion();

        Mockito.when(matchScorer.score(Mockito.any(), Mockito.any())).thenReturn(new MatchScorer.MatchResult(
                71, REASON, List.of(new MatchFactor(MatchFactorKind.SKILLS, 35, 100, "skills 2 of 2")),
                ScorerMode.HYBRID, true));

        assertThat(matchService.getJobMatchForCurrentCandidate(1L).getScoreVersion())
                .as("a stale key would show yesterday's explanation of today's number")
                .isNotEqualTo(before);
    }

    @Test
    void explanationCarriesTheSameScoreVersionAsTheScoreItExplains() {
        givenTheSingleJobScoresWithAFullBreakdown();
        Mockito.when(matchExplainer.explain(Mockito.any()))
                .thenReturn(new MatchExplanation("Reads close to the role.", null, null, true));

        String scoreVersion = matchService.getJobMatchForCurrentCandidate(1L).getScoreVersion();
        MatchExplanationResponse explanation =
                matchService.getJobMatchExplanationForCurrentCandidate(1L);

        assertThat(explanation.getScoreVersion())
                .as("the client discards a mismatch; the two calls must agree when nothing changed")
                .isEqualTo(scoreVersion);
        assertThat(explanation.getSummary()).isEqualTo("Reads close to the role.");
        assertThat(explanation.isGenerated()).isTrue();
    }

    @Test
    void handsTheExplainerTheAlreadyComputedScoreAndBreakdownRatherThanRecomputingAnything() {
        givenTheSingleJobScoresWithAFullBreakdown();
        Mockito.when(matchExplainer.explain(Mockito.any()))
                .thenReturn(MatchExplanation.fromRules(REASON, null, null));

        matchService.getJobMatchExplanationForCurrentCandidate(1L);

        ArgumentCaptor<MatchExplanationInput> input =
                ArgumentCaptor.forClass(MatchExplanationInput.class);
        Mockito.verify(matchExplainer).explain(input.capture());
        assertThat(input.getValue().score()).isEqualTo(65);
        assertThat(input.getValue().ruleReason()).isEqualTo(REASON);
        assertThat(input.getValue().factors()).hasSize(2);
    }

    @Test
    void doesNotCountSkillDemandUntilTheExplainerActuallyAsksForIt() {
        givenTheSingleJobScoresWithAFullBreakdown();
        Mockito.when(matchExplainer.explain(Mockito.any()))
                .thenReturn(MatchExplanation.fromRules(REASON, null, null));

        matchService.getJobMatchExplanationForCurrentCandidate(1L);

        // The cache in front of the explainer returns before the delegate runs, so on a hit this
        // scoring pass must not happen at all. A plain value would have been computed already.
        Mockito.verify(jobRepository, Mockito.never())
                .findByStatus(Mockito.eq(JobStatus.PUBLISHED), Mockito.any(Pageable.class));
    }

    @Test
    void stillHidesAnUnpublishedJobFromTheExplanationEndpoint() {
        Mockito.when(jobRepository.findById(9L))
                .thenReturn(Optional.of(Job.builder().id(9L).status(JobStatus.DRAFT).build()));

        assertThatThrownBy(() -> matchService.getJobMatchExplanationForCurrentCandidate(9L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    /** A hybrid result whose skills row contributes 35 and semantic row 30 — design 20's shape. */
    private void givenTheSingleJobScoresWithAFullBreakdown() {
        Mockito.when(jobRepository.findById(1L)).thenReturn(Optional.of(publishedJob(1L)));
        Mockito.when(matchScorer.score(Mockito.any(), Mockito.any())).thenReturn(new MatchScorer.MatchResult(
                65,
                REASON,
                List.of(
                        new MatchFactor(MatchFactorKind.SKILLS, 35, 100, "skills 2 of 2"),
                        new MatchFactor(MatchFactorKind.SEMANTIC, 30, 100, "strong")),
                ScorerMode.HYBRID,
                true));
    }

    private void givenTheNewestPublishedJobsAre(List<Job> jobs) {
        Mockito.when(jobRepository.findByStatus(Mockito.eq(JobStatus.PUBLISHED), Mockito.any(Pageable.class)))
                .thenReturn(new PageImpl<>(jobs));
    }

    private List<Long> idsUpTo(int count) {
        return LongStream.rangeClosed(1, count).boxed().toList();
    }

    private List<Job> publishedJobs(int count) {
        return LongStream.rangeClosed(1, count).mapToObj(this::publishedJob).toList();
    }

    private Job publishedJob(long id) {
        return Job.builder().id(id).status(JobStatus.PUBLISHED).build();
    }
}
