package com.jbp.serviceimpl;

import com.jbp.dto.JobRequest;
import com.jbp.dto.JobResponse;
import com.jbp.dto.ScreeningQuestionAnswerCount;
import com.jbp.dto.ScreeningQuestionDto;
import com.jbp.mapper.JobMapper;
import com.jbp.model.Company;
import com.jbp.model.Job;
import com.jbp.model.JobStatus;
import com.jbp.model.ScreeningQuestion;
import com.jbp.model.ScreeningQuestionType;
import com.jbp.model.User;
import com.jbp.model.VerificationStatus;
import com.jbp.repository.ApplicationRepository;
import com.jbp.repository.ApplicationRepository.ScreeningAnswerCount;
import com.jbp.repository.JobRepository;
import com.jbp.security.CurrentUserProvider;
import com.jbp.service.CompanyService;
import com.jbp.service.JobDescriptionGenerator;
import com.jbp.service.JobQualityChecker;
import com.jbp.util.JobQualityRules;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.security.access.AccessDeniedException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Story 14.0 — screening questions carry an answer type, and nothing about that type gates a save.
 *
 * <p>The real {@link JobMapper} rather than a mock, because half of what is being checked here is that
 * a type survives the round trip out to the response.
 */
class JobServiceImplScreeningQuestionsTest {

    private static final Long RECRUITER_ID = 7L;
    private static final Long JOB_ID = 3L;

    private final JobRepository jobRepository = Mockito.mock(JobRepository.class);
    private final ApplicationRepository applicationRepository = Mockito.mock(ApplicationRepository.class);
    private final CompanyService companyService = Mockito.mock(CompanyService.class);
    private final CurrentUserProvider currentUserProvider = Mockito.mock(CurrentUserProvider.class);

    private final JobServiceImpl service = new JobServiceImpl(
            jobRepository,
            applicationRepository,
            companyService,
            currentUserProvider,
            new JobMapper(),
            Mockito.mock(JobDescriptionGenerator.class),
            Mockito.mock(JobQualityRules.class),
            Mockito.mock(JobQualityChecker.class));

    @Test
    void keepsTheAnswerTypeTheRecruiterChoseForEachQuestion() {
        givenSignedInAs(RECRUITER_ID);
        givenTheRecruiterOwnsACompany();
        givenSaveReturnsWhateverItIsGiven();

        JobResponse response = service.createJob(requestAsking(
                new ScreeningQuestionDto("How many years of Java?", ScreeningQuestionType.SHORT_ANSWER),
                new ScreeningQuestionDto("Describe your last outage.", ScreeningQuestionType.LONG_ANSWER)));

        assertThat(response.getScreeningQuestions())
                .extracting(ScreeningQuestionDto::getQuestion, ScreeningQuestionDto::getAnswerType)
                .containsExactly(
                        tuple("How many years of Java?", ScreeningQuestionType.SHORT_ANSWER),
                        tuple("Describe your last outage.", ScreeningQuestionType.LONG_ANSWER));
    }

    /**
     * A silent default is a choice the recruiter never made (design 23 C1), so an unchosen type has to
     * survive a save as unchosen — not become {@code SHORT_ANSWER} on the way through.
     */
    @Test
    void storesAnUnchosenAnswerTypeAsNullRatherThanDefaultingIt() {
        givenSignedInAs(RECRUITER_ID);
        givenTheRecruiterOwnsACompany();
        givenSaveReturnsWhateverItIsGiven();

        JobResponse response = service.createJob(
                requestAsking(new ScreeningQuestionDto("Which tools have you operated?", null)));

        assertThat(response.getScreeningQuestions()).singleElement()
                .extracting(ScreeningQuestionDto::getAnswerType)
                .isNull();
    }

    @Test
    void treatsAJobSavedWithNoScreeningQuestionsAsAskingNone() {
        givenSignedInAs(RECRUITER_ID);
        givenTheRecruiterOwnsACompany();
        givenSaveReturnsWhateverItIsGiven();

        JobResponse response = service.createJob(JobRequest.builder().title("Backend Engineer").build());

        assertThat(response.getScreeningQuestions()).isEmpty();
    }

    /** Publishing is gated on company verification and job status, and on nothing about types. */
    @Test
    void publishesAJobWhoseQuestionsHaveNoAnswerTypeYet() {
        givenSignedInAs(RECRUITER_ID);
        givenTheJob(jobAsking(JobStatus.DRAFT,
                new ScreeningQuestion("Are you available in June?", null)));
        Mockito.when(companyService.isRecruiterVerified(RECRUITER_ID)).thenReturn(true);
        givenSaveReturnsWhateverItIsGiven();

        JobResponse response = service.publishJob(JOB_ID);

        assertThat(response.getStatus())
                .as("an untyped question is a prompt to the recruiter, not a blocker")
                .isEqualTo(JobStatus.PENDING_MODERATION);
    }

    /**
     * The clone and its source must not share question objects. They did not need copying while a
     * question was a {@code String}; a {@link ScreeningQuestion} is mutable, so a shallow copy would
     * let an edit to the new draft rewrite the published job it was cloned from.
     */
    @Test
    void givesTheCloneItsOwnQuestionObjectsRatherThanTheSourcesOwn() {
        givenSignedInAs(RECRUITER_ID);
        Job source = jobAsking(JobStatus.PUBLISHED,
                new ScreeningQuestion("Are you eligible to work in India?", ScreeningQuestionType.YES_NO));
        givenTheJob(source);
        givenSaveReturnsWhateverItIsGiven();

        service.cloneJob(JOB_ID);

        ArgumentCaptor<Job> saved = ArgumentCaptor.forClass(Job.class);
        Mockito.verify(jobRepository).save(saved.capture());
        saved.getValue().getScreeningQuestions().get(0).setAnswerType(ScreeningQuestionType.LONG_ANSWER);

        assertThat(source.getScreeningQuestions().get(0).getAnswerType())
                .as("editing the draft must not reach back into the job it was cloned from")
                .isEqualTo(ScreeningQuestionType.YES_NO);
    }

    @Test
    void countsTheAnswersGivenToEachQuestionAndZeroForTheRest() {
        givenSignedInAs(RECRUITER_ID);
        givenTheJob(jobAsking(JobStatus.PUBLISHED,
                new ScreeningQuestion("Are you eligible to work in India?", ScreeningQuestionType.YES_NO),
                new ScreeningQuestion("Describe your last outage.", ScreeningQuestionType.LONG_ANSWER)));
        Mockito.when(applicationRepository.countAnswersPerQuestion(JOB_ID)).thenReturn(List.of(
                answerCount("Are you eligible to work in India?", 4L)));

        List<ScreeningQuestionAnswerCount> counts = service.getScreeningAnswerCounts(JOB_ID);

        assertThat(counts)
                .extracting(ScreeningQuestionAnswerCount::getQuestion,
                        ScreeningQuestionAnswerCount::getAnsweredCount)
                .as("one entry per question, in the job's own order, so the editor can line them up")
                .containsExactly(
                        tuple("Are you eligible to work in India?", 4L),
                        tuple("Describe your last outage.", 0L));
    }

    /**
     * How many people answered a question is how many people applied, broken down. It belongs to the
     * recruiter who posted the job and to nobody else.
     */
    @Test
    void refusesToCountAnswersOnSomebodyElsesJob() {
        givenSignedInAs(99L);
        givenTheJob(jobAsking(JobStatus.PUBLISHED,
                new ScreeningQuestion("Are you eligible to work in India?", ScreeningQuestionType.YES_NO)));

        assertThatThrownBy(() -> service.getScreeningAnswerCounts(JOB_ID))
                .isInstanceOf(AccessDeniedException.class);
        Mockito.verifyNoInteractions(applicationRepository);
    }

    private JobRequest requestAsking(ScreeningQuestionDto... questions) {
        return JobRequest.builder()
                .title("Backend Engineer")
                .screeningQuestions(new ArrayList<>(List.of(questions)))
                .build();
    }

    private Job jobAsking(JobStatus status, ScreeningQuestion... questions) {
        return Job.builder()
                .id(JOB_ID)
                .title("Backend Engineer")
                .status(status)
                .skills(new HashSet<>())
                .screeningQuestions(new ArrayList<>(List.of(questions)))
                .company(company())
                .build();
    }

    private void givenSignedInAs(Long userId) {
        Mockito.when(currentUserProvider.getCurrentUserId()).thenReturn(userId);
    }

    private void givenTheRecruiterOwnsACompany() {
        Mockito.when(companyService.getCompanyEntityForRecruiter(RECRUITER_ID)).thenReturn(company());
    }

    private void givenTheJob(Job job) {
        Mockito.when(jobRepository.findById(JOB_ID)).thenReturn(Optional.of(job));
    }

    private void givenSaveReturnsWhateverItIsGiven() {
        Mockito.when(jobRepository.save(Mockito.any(Job.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    /** The repository hands back a projection, so the fake has to be one too. */
    private ScreeningAnswerCount answerCount(String question, long answered) {
        return new ScreeningAnswerCount() {
            @Override
            public String getQuestion() {
                return question;
            }

            @Override
            public long getAnsweredCount() {
                return answered;
            }
        };
    }

    private Company company() {
        return Company.builder()
                .id(11L)
                .name("Acme")
                .status(VerificationStatus.VERIFIED)
                .owner(User.builder().id(RECRUITER_ID).build())
                .build();
    }
}
