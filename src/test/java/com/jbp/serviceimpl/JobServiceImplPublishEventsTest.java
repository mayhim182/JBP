package com.jbp.serviceimpl;

import com.jbp.event.EmbeddingRefreshPublisher;
import com.jbp.event.JobModerationPublisher;
import com.jbp.mapper.JobMapper;
import com.jbp.model.Company;
import com.jbp.model.Job;
import com.jbp.model.JobStatus;
import com.jbp.model.User;
import com.jbp.model.VerificationStatus;
import com.jbp.repository.ApplicationRepository;
import com.jbp.repository.JobRepository;
import com.jbp.security.CurrentUserProvider;
import com.jbp.service.CompanyService;
import com.jbp.service.JobDescriptionGenerator;
import com.jbp.service.JobDuplicateDetector;
import com.jbp.service.JobQualityChecker;
import com.jbp.util.JobQualityRules;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What submitting a job for moderation announces, and what it stamps.
 *
 * <p>Both events are easy to delete by accident and neither shows up in a response, so they are pinned
 * here rather than left to a log line. The embedding one in particular is load-bearing for Story 14.6:
 * without it, no job in the moderation queue has a vector and duplicate detection is a permanent no-op.
 */
class JobServiceImplPublishEventsTest {

    private static final Long RECRUITER_ID = 7L;
    private static final Long JOB_ID = 3L;

    private final JobRepository jobRepository = Mockito.mock(JobRepository.class);
    private final CompanyService companyService = Mockito.mock(CompanyService.class);
    private final CurrentUserProvider currentUserProvider = Mockito.mock(CurrentUserProvider.class);
    private final JobModerationPublisher jobModerationPublisher =
            Mockito.mock(JobModerationPublisher.class);
    private final EmbeddingRefreshPublisher embeddingRefreshPublisher =
            Mockito.mock(EmbeddingRefreshPublisher.class);

    private final JobServiceImpl service = new JobServiceImpl(
            jobRepository,
            Mockito.mock(ApplicationRepository.class),
            companyService,
            currentUserProvider,
            new JobMapper(),
            Mockito.mock(JobDescriptionGenerator.class),
            Mockito.mock(JobQualityRules.class),
            Mockito.mock(JobQualityChecker.class),
            jobModerationPublisher,
            embeddingRefreshPublisher,
            Mockito.mock(JobDuplicateDetector.class));

    @BeforeEach
    void givenAVerifiedRecruiterWithADraft() {
        Mockito.when(currentUserProvider.getCurrentUserId()).thenReturn(RECRUITER_ID);
        Mockito.when(companyService.isRecruiterVerified(RECRUITER_ID)).thenReturn(true);
        Mockito.when(jobRepository.findById(JOB_ID)).thenReturn(Optional.of(draft()));
        Mockito.when(jobRepository.save(Mockito.any(Job.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void announcesTheSubmissionForAssessment() {
        service.publishJob(JOB_ID);

        Mockito.verify(jobModerationPublisher).submittedForModeration(JOB_ID);
    }

    /**
     * Story 14.6 moved this from approval time. An unapproved job used to need no vector because a
     * vector's only use was search; duplicate detection has to compare a posting to its siblings before
     * an admin decides, so a vector that only appears on approval can never fire.
     */
    @Test
    void announcesTheSubmissionForEmbedding() {
        service.publishJob(JOB_ID);

        Mockito.verify(embeddingRefreshPublisher).jobChanged(JOB_ID);
    }

    @Test
    void stampsTheMomentTheQueueStartedWaiting() {
        service.publishJob(JOB_ID);

        ArgumentCaptor<Job> saved = ArgumentCaptor.forClass(Job.class);
        Mockito.verify(jobRepository).save(saved.capture());
        assertThat(saved.getValue().getSubmittedAt()).isNotNull();
        assertThat(saved.getValue().getStatus()).isEqualTo(JobStatus.PENDING_MODERATION);
    }

    /** Nothing is announced when the submission itself was refused. */
    @Test
    void announcesNothingWhenTheCompanyIsNotVerified() {
        Mockito.when(companyService.isRecruiterVerified(RECRUITER_ID)).thenReturn(false);

        try {
            service.publishJob(JOB_ID);
        } catch (RuntimeException refused) {
            // The conflict itself is JobServiceImplScreeningQuestionsTest's ground; what matters here
            // is that a refused submission announces nothing.
        }

        Mockito.verifyNoInteractions(jobModerationPublisher, embeddingRefreshPublisher);
    }

    private Job draft() {
        return Job.builder()
                .id(JOB_ID)
                .title("Backend Engineer")
                .status(JobStatus.DRAFT)
                .skills(new HashSet<>())
                .screeningQuestions(new ArrayList<>())
                .company(Company.builder()
                        .id(11L)
                        .name("Acme")
                        .status(VerificationStatus.VERIFIED)
                        .owner(User.builder().id(RECRUITER_ID).build())
                        .build())
                .build();
    }
}
