package com.jbp.serviceimpl;

import com.jbp.config.AiCapabilities;
import com.jbp.dto.DraftAnswerRequest;
import com.jbp.dto.DraftAnswerResponse;
import com.jbp.dto.DraftedScreeningAnswer;
import com.jbp.exception.InsufficientProfileException;
import com.jbp.exception.LlmUnavailableException;
import com.jbp.exception.RateLimitExceededException;
import com.jbp.model.CandidateProfile;
import com.jbp.model.CandidateProject;
import com.jbp.model.Experience;
import com.jbp.model.ScreeningQuestionType;
import com.jbp.security.CurrentUserProvider;
import com.jbp.service.CandidateProfileService;
import com.jbp.service.ScreeningAnswerAssistant;
import com.jbp.util.ControllableClock;
import com.jbp.util.PerUserCallBudget;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 14.2 — everything that has to be true around the model call: whose profile, whether it can
 * ground an answer, whether the candidate has allowance left, and giving that allowance back when
 * the attempt produced nothing.
 */
class ScreeningAnswerDraftServiceImplTest {

    private static final Long CANDIDATE_ID = 7L;
    private static final int ALLOWANCE = 3;

    private final CurrentUserProvider currentUserProvider = Mockito.mock(CurrentUserProvider.class);
    private final CandidateProfileService candidateProfileService = Mockito.mock(CandidateProfileService.class);
    private final ScreeningAnswerAssistant assistant = Mockito.mock(ScreeningAnswerAssistant.class);
    private final PerUserCallBudget budget =
            new PerUserCallBudget(ALLOWANCE, Duration.ofHours(24), 100, new ControllableClock());

    private final ScreeningAnswerDraftServiceImpl service = new ScreeningAnswerDraftServiceImpl(
            currentUserProvider,
            candidateProfileService,
            assistant,
            budget,
            capabilitiesWithScreeningAnswerAssist(true));

    @Test
    void returnsTheDraftAndWhatIsLeftOfTheAllowance() {
        givenACandidateWhoCanBeDraftedFor();
        Mockito.when(assistant.draft(Mockito.any())).thenReturn(draftOf("Six years, mostly payments."));

        DraftAnswerResponse response = service.draftAnswer(request(ScreeningQuestionType.SHORT_ANSWER));

        assertThat(response.getDraft()).isEqualTo("Six years, mostly payments.");
        assertThat(response.getRemaining())
                .as("the client cannot count this itself — a reload or a second tab would be wrong")
                .isEqualTo(ALLOWANCE - 1);
    }

    /** Decision 5: the posting is never an input, so nothing about a job may reach the assistant. */
    @Test
    void tellsTheAssistantOnlyTheQuestionTheTypeAndTheCandidatesOwnProfile() {
        CandidateProfile profile = usableProfile();
        givenACandidate(profile);
        Mockito.when(assistant.draft(Mockito.any())).thenReturn(draftOf("A draft."));

        service.draftAnswer(request(ScreeningQuestionType.LONG_ANSWER));

        ArgumentCaptor<ScreeningAnswerAssistant.AnswerBrief> brief =
                ArgumentCaptor.forClass(ScreeningAnswerAssistant.AnswerBrief.class);
        Mockito.verify(assistant).draft(brief.capture());
        assertThat(brief.getValue().question()).isEqualTo("Describe a failure you debugged.");
        assertThat(brief.getValue().answerType()).isEqualTo(ScreeningQuestionType.LONG_ANSWER);
        assertThat(brief.getValue().profile()).isSameAs(profile);
    }

    @Test
    void refusesWhenTheProfileHasNothingToGroundAnAnswerIn() {
        givenACandidate(CandidateProfile.builder().build());

        assertThatThrownBy(() -> service.draftAnswer(request(ScreeningQuestionType.LONG_ANSWER)))
                .isInstanceOf(InsufficientProfileException.class);
        Mockito.verifyNoInteractions(assistant);
    }

    @Test
    void refusesWhenTheCandidateHasNoProfileAtAll() {
        Mockito.when(currentUserProvider.getCurrentUserId()).thenReturn(CANDIDATE_ID);
        Mockito.when(candidateProfileService.findProfileForCandidate(CANDIDATE_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.draftAnswer(request(ScreeningQuestionType.LONG_ANSWER)))
                .isInstanceOf(InsufficientProfileException.class);
    }

    /** A refusal that costs nothing: the allowance must be untouched by a request never sent. */
    @Test
    void spendsNoAllowanceOnAProfileThatCouldNotBeDraftedFrom() {
        givenACandidate(CandidateProfile.builder().build());

        assertThatThrownBy(() -> service.draftAnswer(request(ScreeningQuestionType.LONG_ANSWER)))
                .isInstanceOf(InsufficientProfileException.class);

        assertThat(budget.remainingCalls(CANDIDATE_ID)).isEqualTo(ALLOWANCE);
    }

    @Test
    void refusesOnceTheAllowanceIsSpent() {
        givenACandidateWhoCanBeDraftedFor();
        Mockito.when(assistant.draft(Mockito.any())).thenReturn(draftOf("A draft."));
        for (int draft = 0; draft < ALLOWANCE; draft++) {
            service.draftAnswer(request(ScreeningQuestionType.LONG_ANSWER));
        }

        assertThatThrownBy(() -> service.draftAnswer(request(ScreeningQuestionType.LONG_ANSWER)))
                .isInstanceOf(RateLimitExceededException.class);
    }

    /**
     * Design 22b F's copy promises this outright — "a failed attempt doesn't use up one of your
     * drafts" — and a candidate told "limit reached" after three outages would never trust the
     * feature again.
     */
    @Test
    void refundsTheDraftWhenTheModelCouldNotBeReached() {
        givenACandidateWhoCanBeDraftedFor();
        Mockito.when(assistant.draft(Mockito.any())).thenReturn(DraftedScreeningAnswer.unavailable());

        assertThatThrownBy(() -> service.draftAnswer(request(ScreeningQuestionType.LONG_ANSWER)))
                .isInstanceOf(LlmUnavailableException.class);

        assertThat(budget.remainingCalls(CANDIDATE_ID)).isEqualTo(ALLOWANCE);
    }

    /**
     * The gate passed on structure and the assistant still found nothing to write from. 422 rather
     * than 503, because nothing of ours went wrong and the dialog has to send them to their profile
     * rather than offer a retry — and refunded, because they received no draft.
     */
    @Test
    void reportsADeclineAsAProfileProblemAndRefundsIt() {
        givenACandidateWhoCanBeDraftedFor();
        Mockito.when(assistant.draft(Mockito.any())).thenReturn(draftOf(""));

        assertThatThrownBy(() -> service.draftAnswer(request(ScreeningQuestionType.LONG_ANSWER)))
                .isInstanceOf(InsufficientProfileException.class);

        assertThat(budget.remainingCalls(CANDIDATE_ID)).isEqualTo(ALLOWANCE);
    }

    @Test
    void refusesAnAnswerTypeThatHasNoDraftTrigger() {
        givenACandidateWhoCanBeDraftedFor();

        assertThatThrownBy(() -> service.draftAnswer(request(ScreeningQuestionType.YES_NO)))
                .isInstanceOf(IllegalArgumentException.class);
        Mockito.verifyNoInteractions(assistant);
    }

    @Test
    void refusesAnUntypedQuestion() {
        givenACandidateWhoCanBeDraftedFor();

        assertThatThrownBy(() -> service.draftAnswer(request(null)))
                .as("an untyped question fails closed — design 23's carve-out")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesEverythingWhenTheCapabilityIsSwitchedOff() {
        ScreeningAnswerDraftServiceImpl switchedOff = new ScreeningAnswerDraftServiceImpl(
                currentUserProvider, candidateProfileService, assistant, budget,
                capabilitiesWithScreeningAnswerAssist(false));

        assertThatThrownBy(() -> switchedOff.draftAnswer(request(ScreeningQuestionType.LONG_ANSWER)))
                .isInstanceOf(LlmUnavailableException.class);
        Mockito.verifyNoInteractions(assistant);
    }

    /**
     * Every capability on except the one under test, named rather than positional.
     *
     * <p>{@link AiCapabilities} is a record that grows a component per AI feature, and these two call
     * sites were written as five positional booleans — so adding Story 14.3's flag broke this file
     * rather than the file that owns the flag. One place to fix next time, and the argument says what
     * it means.
     */
    private AiCapabilities capabilitiesWithScreeningAnswerAssist(boolean enabled) {
        return new AiCapabilities(true, true, true, enabled, true);
    }

    private DraftAnswerRequest request(ScreeningQuestionType answerType) {
        return DraftAnswerRequest.builder()
                .question("Describe a failure you debugged.")
                .answerType(answerType)
                .build();
    }

    private DraftedScreeningAnswer draftOf(String draft) {
        return DraftedScreeningAnswer.builder().draft(draft).build();
    }

    private void givenACandidateWhoCanBeDraftedFor() {
        givenACandidate(usableProfile());
    }

    private void givenACandidate(CandidateProfile profile) {
        Mockito.when(currentUserProvider.getCurrentUserId()).thenReturn(CANDIDATE_ID);
        Mockito.when(candidateProfileService.findProfileForCandidate(CANDIDATE_ID))
                .thenReturn(Optional.of(profile));
    }

    private CandidateProfile usableProfile() {
        CandidateProfile profile = CandidateProfile.builder().build();
        profile.setExperiences(new ArrayList<>(List.of(Experience.builder()
                .title("Backend Engineer").company("Acme").description("Payments.").build())));
        profile.setProjects(new ArrayList<>(List.<CandidateProject>of()));
        return profile;
    }
}
