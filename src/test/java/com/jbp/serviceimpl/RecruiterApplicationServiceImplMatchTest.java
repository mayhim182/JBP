package com.jbp.serviceimpl;

import com.jbp.dto.ApplicationResponse;
import com.jbp.dto.ApplicationReviewRequest;
import com.jbp.dto.ApplicationStatusUpdateRequest;
import com.jbp.event.ApplicationStatusChangePublisher;
import com.jbp.mapper.ApplicationMapper;
import com.jbp.model.Application;
import com.jbp.model.ApplicationStatus;
import com.jbp.model.CandidateProfile;
import com.jbp.model.Company;
import com.jbp.model.Job;
import com.jbp.model.User;
import com.jbp.model.VerificationStatus;
import com.jbp.repository.ApplicationRepository;
import com.jbp.repository.JobRepository;
import com.jbp.security.CurrentUserProvider;
import com.jbp.service.CandidateProfileService;
import com.jbp.service.MatchScorer;
import com.jbp.util.ApplicationStageTransitionValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every recruiter-facing response carries the applicant's match.
 *
 * <p><strong>This was a live defect, found while reading design 24 against the drawer it lands in.</strong>
 * Only the list was scored; opening an applicant, moving their stage and saving a review all returned
 * a response with a null score and reason. The board merges each response over the row it already
 * holds, so those nulls overwrote the real values: the ring vanished on open, "Why this rank" read
 * "No match reason provided", and the funnel's average match drifted down as a recruiter triaged.
 *
 * <p>Every path is asserted rather than just the one that was reported, because the mistake was not
 * knowing which paths existed — a fifth one added later with the plain mapper would reintroduce it
 * silently, and that is exactly what this test is here to stop.
 */
class RecruiterApplicationServiceImplMatchTest {

    private static final Long RECRUITER_ID = 7L;
    private static final Long APPLICATION_ID = 3L;
    private static final Long JOB_ID = 11L;
    private static final int SCORE = 82;
    private static final String REASON = "skills 4/5 · seniority match · remote · 3 roles";

    private final ApplicationRepository applicationRepository = Mockito.mock(ApplicationRepository.class);
    private final JobRepository jobRepository = Mockito.mock(JobRepository.class);
    private final CurrentUserProvider currentUserProvider = Mockito.mock(CurrentUserProvider.class);
    private final ApplicationStatusChangePublisher statusChangePublisher =
            Mockito.mock(ApplicationStatusChangePublisher.class);
    private final CandidateProfileService candidateProfileService = Mockito.mock(CandidateProfileService.class);
    private final MatchScorer matchScorer = Mockito.mock(MatchScorer.class);

    private final RecruiterApplicationServiceImpl service = new RecruiterApplicationServiceImpl(
            applicationRepository,
            jobRepository,
            currentUserProvider,
            new ApplicationMapper(),
            Mockito.mock(ApplicationStageTransitionValidator.class),
            statusChangePublisher,
            candidateProfileService,
            matchScorer);

    @BeforeEach
    void givenAnApplicationOnTheSignedInRecruitersJob() {
        Mockito.when(currentUserProvider.getCurrentUserId()).thenReturn(RECRUITER_ID);
        Mockito.when(applicationRepository.findById(APPLICATION_ID)).thenReturn(Optional.of(application()));
        Mockito.when(candidateProfileService.findProfileForCandidate(Mockito.any()))
                .thenReturn(Optional.of(CandidateProfile.builder().build()));
        Mockito.when(matchScorer.score(Mockito.any(), Mockito.any()))
                .thenReturn(new MatchScorer.MatchResult(SCORE, REASON));
    }

    /** The path that was broken: opening an applicant is what the drawer does. */
    @Test
    void keepsTheMatchWhenAnApplicantIsOpened() {
        assertThatCarriesTheMatch(service.openApplication(APPLICATION_ID));
    }

    @Test
    void keepsTheMatchWhenTheStageIsMoved() {
        assertThatCarriesTheMatch(service.updateStatus(APPLICATION_ID,
                ApplicationStatusUpdateRequest.builder().status(ApplicationStatus.SHORTLISTED).build()));
    }

    @Test
    void keepsTheMatchWhenNotesOrARatingAreSaved() {
        assertThatCarriesTheMatch(service.updateReview(APPLICATION_ID,
                ApplicationReviewRequest.builder().notes("Strong on ledgers.").rating(4).build()));
    }

    /** The path that always worked, asserted so the shared helper cannot regress it. */
    @Test
    void keepsTheMatchOnEveryRowOfTheApplicantList() {
        Mockito.when(jobRepository.findById(JOB_ID)).thenReturn(Optional.of(job()));
        Mockito.when(applicationRepository.findByJobId(JOB_ID)).thenReturn(List.of(application()));

        assertThat(service.getApplicantsForJob(JOB_ID)).singleElement()
                .satisfies(this::assertThatCarriesTheMatch);
    }

    private void assertThatCarriesTheMatch(ApplicationResponse response) {
        assertThat(response.getMatchScore())
                .as("a null here blanks the ring the board already had")
                .isEqualTo(SCORE);
        assertThat(response.getMatchReason()).isEqualTo(REASON);
    }

    private Application application() {
        return Application.builder()
                .id(APPLICATION_ID)
                .status(ApplicationStatus.APPLIED)
                .job(job())
                .candidate(User.builder().id(42L).name("Amara Okafor").email("amara@example.com").build())
                .screeningAnswers(new ArrayList<>())
                .build();
    }

    private Job job() {
        return Job.builder()
                .id(JOB_ID)
                .title("Senior Backend Engineer")
                .skills(new HashSet<>())
                .company(Company.builder()
                        .id(5L)
                        .name("Acme")
                        .status(VerificationStatus.VERIFIED)
                        .owner(User.builder().id(RECRUITER_ID).build())
                        .build())
                .build();
    }
}
