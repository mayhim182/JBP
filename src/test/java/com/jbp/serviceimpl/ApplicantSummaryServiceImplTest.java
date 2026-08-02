package com.jbp.serviceimpl;

import com.jbp.dto.ApplicantSummary;
import com.jbp.exception.ConflictException;
import com.jbp.exception.InsufficientProfileException;
import com.jbp.exception.LlmUnavailableException;
import com.jbp.exception.RateLimitExceededException;
import com.jbp.model.Application;
import com.jbp.model.ApplicationStatus;
import com.jbp.model.CandidateProfile;
import com.jbp.model.Company;
import com.jbp.model.Job;
import com.jbp.model.MatchFactorKind;
import com.jbp.model.ScorerMode;
import com.jbp.model.User;
import com.jbp.repository.ApplicationRepository;
import com.jbp.security.CurrentUserProvider;
import com.jbp.service.ApplicantSummarizer;
import com.jbp.service.CandidateProfileService;
import com.jbp.service.MatchScorer;
import com.jbp.util.ControllableClock;
import com.jbp.util.PerUserCallBudget;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 14.3 — everything that has to be true around the model call: whose application it is, whether
 * a decision is still open, whether the ceiling has been hit, and what the read is a read of.
 */
class ApplicantSummaryServiceImplTest {

    private static final Long RECRUITER_ID = 7L;
    private static final Long APPLICATION_ID = 3L;
    private static final int CEILING = 2;

    private final ApplicationRepository applicationRepository = Mockito.mock(ApplicationRepository.class);
    private final CurrentUserProvider currentUserProvider = Mockito.mock(CurrentUserProvider.class);
    private final CandidateProfileService candidateProfileService = Mockito.mock(CandidateProfileService.class);
    private final MatchScorer matchScorer = Mockito.mock(MatchScorer.class);
    private final ApplicantSummarizer summarizer = Mockito.mock(ApplicantSummarizer.class);
    private final PerUserCallBudget ceiling =
            new PerUserCallBudget(CEILING, Duration.ofMinutes(1), 100, new ControllableClock());

    private final ApplicantSummaryServiceImpl service = new ApplicantSummaryServiceImpl(
            applicationRepository,
            currentUserProvider,
            candidateProfileService,
            matchScorer,
            summarizer,
            ceiling);

    @Test
    void returnsTheThreeLines() {
        givenAnOpenApplication();
        Mockito.when(summarizer.summarise(Mockito.any())).thenReturn(aRead());

        ApplicantSummary summary = service.summariseApplicant(APPLICATION_ID);

        assertThat(summary.getStrongestFit()).isEqualTo("Ran ledgers.");
        assertThat(summary.hasAllThreeLines()).isTrue();
    }

    /**
     * The brief carries the application and its score version for the cache, the job and profile for
     * the model — and a breakdown with every number already removed.
     */
    @Test
    void handsTheSummarizerABandedBreakdownAndAVersionedIdentity() {
        givenAnOpenApplication();
        Mockito.when(summarizer.summarise(Mockito.any())).thenReturn(aRead());

        service.summariseApplicant(APPLICATION_ID);

        ArgumentCaptor<ApplicantSummarizer.ApplicantBrief> brief =
                ArgumentCaptor.forClass(ApplicantSummarizer.ApplicantBrief.class);
        Mockito.verify(summarizer).summarise(brief.capture());
        assertThat(brief.getValue().applicationId()).isEqualTo(APPLICATION_ID);
        assertThat(brief.getValue().scoreVersion()).isNotBlank();
        assertThat(brief.getValue().factors())
                .as("30/100 is thin, 90/100 is strong — and neither number survives")
                .containsExactly(
                        new ApplicantSummarizer.FactorSignal(
                                MatchFactorKind.SKILLS, ApplicantSummarizer.FactorStrength.WEAK),
                        new ApplicantSummarizer.FactorSignal(
                                MatchFactorKind.SENIORITY, ApplicantSummarizer.FactorStrength.STRONG));
    }

    /** Design 24 B4: the model was reached and found nothing to write from. 422, not 503. */
    @Test
    void reportsADeclineAsAProfileProblem() {
        givenAnOpenApplication();
        Mockito.when(summarizer.summarise(Mockito.any()))
                .thenReturn(ApplicantSummary.builder().strongestFit("").mainGap("").worthProbing("").build());

        assertThatThrownBy(() -> service.summariseApplicant(APPLICATION_ID))
                .isInstanceOf(InsufficientProfileException.class);
    }

    /** Design 24 B2: the model could not be reached, and the client offers a retry. */
    @Test
    void letsAModelFailureThrough() {
        givenAnOpenApplication();
        Mockito.when(summarizer.summarise(Mockito.any()))
                .thenThrow(new LlmUnavailableException("Model did not respond", true));

        assertThatThrownBy(() -> service.summariseApplicant(APPLICATION_ID))
                .isInstanceOf(LlmUnavailableException.class);
    }

    /**
     * Design 24 B3's second condition. The summary is a decision aid, and where there is no decision
     * left there is nothing to aid — nor anything worth re-litigating on AI prose.
     */
    @Test
    void refusesAnApplicationThatHasAlreadyBeenDecided() {
        givenAnApplicationAt(ApplicationStatus.REJECTED);

        assertThatThrownBy(() -> service.summariseApplicant(APPLICATION_ID))
                .isInstanceOf(ConflictException.class);
        Mockito.verifyNoInteractions(summarizer);
    }

    @Test
    void refusesAClosedApplicationForTheSameReason() {
        givenAnApplicationAt(ApplicationStatus.CLOSED);

        assertThatThrownBy(() -> service.summariseApplicant(APPLICATION_ID))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void refusesAnApplicantOnSomebodyElsesJob() {
        givenAnApplicationAt(ApplicationStatus.APPLIED);
        Mockito.when(currentUserProvider.getCurrentUserId()).thenReturn(99L);

        assertThatThrownBy(() -> service.summariseApplicant(APPLICATION_ID))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        Mockito.verifyNoInteractions(summarizer);
    }

    @Test
    void refusesOnceTheCeilingIsReached() {
        givenAnOpenApplication();
        Mockito.when(summarizer.summarise(Mockito.any())).thenReturn(aRead());
        for (int call = 0; call < CEILING; call++) {
            service.summariseApplicant(APPLICATION_ID);
        }

        assertThatThrownBy(() -> service.summariseApplicant(APPLICATION_ID))
                .isInstanceOf(RateLimitExceededException.class);
    }

    /**
     * The opposite of Story 14.2's rule, deliberately. That is a budget the candidate is told about,
     * where an outage must not cost them a draft they never received. This is a ceiling whose entire
     * job is to bound a runaway retry loop — refunding failed attempts would make it bound nothing.
     */
    @Test
    void doesNotRefundACeilingSlotWhenTheModelFails() {
        givenAnOpenApplication();
        Mockito.when(summarizer.summarise(Mockito.any()))
                .thenThrow(new LlmUnavailableException("Model did not respond", true));

        assertThatThrownBy(() -> service.summariseApplicant(APPLICATION_ID))
                .isInstanceOf(LlmUnavailableException.class);

        assertThat(ceiling.remainingCalls(RECRUITER_ID))
                .as("a retry loop is exactly what this bounds")
                .isEqualTo(CEILING - 1);
    }

    /** A refusal that costs nothing: a decided application never reaches the ceiling. */
    @Test
    void spendsNoCeilingSlotOnAnApplicationItRefusesOutright() {
        givenAnApplicationAt(ApplicationStatus.CLOSED);

        assertThatThrownBy(() -> service.summariseApplicant(APPLICATION_ID))
                .isInstanceOf(ConflictException.class);

        assertThat(ceiling.remainingCalls(RECRUITER_ID)).isEqualTo(CEILING);
    }

    private ApplicantSummary aRead() {
        return ApplicantSummary.builder()
                .strongestFit("Ran ledgers.")
                .mainGap("No Kafka.")
                .worthProbing("Ask about exactly-once.")
                .build();
    }

    private void givenAnOpenApplication() {
        givenAnApplicationAt(ApplicationStatus.APPLIED);
        Mockito.when(candidateProfileService.findProfileForCandidate(Mockito.any()))
                .thenReturn(Optional.of(CandidateProfile.builder().build()));
        Mockito.when(matchScorer.score(Mockito.any(), Mockito.any())).thenReturn(
                new MatchScorer.MatchResult(64, "skills 1/2", List.of(
                        new MatchScorer.MatchFactor(MatchFactorKind.SKILLS, 60, 30, "skills 1/2"),
                        new MatchScorer.MatchFactor(MatchFactorKind.SENIORITY, 40, 90, "seniority match")),
                        ScorerMode.RULE, false));
    }

    private void givenAnApplicationAt(ApplicationStatus status) {
        Mockito.when(currentUserProvider.getCurrentUserId()).thenReturn(RECRUITER_ID);
        Mockito.when(applicationRepository.findById(APPLICATION_ID)).thenReturn(Optional.of(
                Application.builder()
                        .id(APPLICATION_ID)
                        .status(status)
                        .candidate(User.builder().id(42L).build())
                        .job(Job.builder()
                                .id(11L)
                                .title("Senior Backend Engineer")
                                .skills(new HashSet<>())
                                .company(Company.builder()
                                        .owner(User.builder().id(RECRUITER_ID).build())
                                        .build())
                                .build())
                        .build()));
    }
}
