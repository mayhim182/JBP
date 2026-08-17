package com.jbp.serviceimpl;

import com.jbp.dto.DuplicateCheck;
import com.jbp.dto.DuplicateJob;
import com.jbp.event.EmbeddingRefreshPublisher;
import com.jbp.event.JobModerationPublisher;
import com.jbp.mapper.JobMapper;
import com.jbp.model.Company;
import com.jbp.model.Job;
import com.jbp.model.JobStatus;
import com.jbp.model.User;
import com.jbp.repository.ApplicationRepository;
import com.jbp.repository.JobRepository;
import com.jbp.security.CurrentUserProvider;
import com.jbp.service.CompanyService;
import com.jbp.service.JobDescriptionGenerator;
import com.jbp.service.JobDuplicateDetector;
import com.jbp.service.JobQualityChecker;
import com.jbp.util.JobQualityRules;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The recruiter's duplicate check — Story 14.6.
 *
 * <p>Thin by design: it reuses {@code findOwnJobOrThrow}, the same find-then-verify pair every other
 * owner-scoped operation uses. What is worth asserting is that it does reuse it, because this is new
 * public surface and a duplicate list names other postings by title.
 */
class JobServiceImplDuplicateCheckTest {

    private static final Long RECRUITER_ID = 7L;
    private static final Long OTHER_RECRUITER_ID = 99L;
    private static final Long JOB_ID = 3L;

    private final JobRepository jobRepository = Mockito.mock(JobRepository.class);
    private final CurrentUserProvider currentUserProvider = Mockito.mock(CurrentUserProvider.class);
    private final JobDuplicateDetector jobDuplicateDetector = Mockito.mock(JobDuplicateDetector.class);

    private final JobServiceImpl service = new JobServiceImpl(
            jobRepository,
            Mockito.mock(ApplicationRepository.class),
            Mockito.mock(CompanyService.class),
            currentUserProvider,
            new JobMapper(),
            Mockito.mock(JobDescriptionGenerator.class),
            Mockito.mock(JobQualityRules.class),
            Mockito.mock(JobQualityChecker.class),
            Mockito.mock(JobModerationPublisher.class),
            Mockito.mock(EmbeddingRefreshPublisher.class),
            jobDuplicateDetector);

    @Test
    void returnsWhatTheDetectorFound() {
        givenSignedInAs(RECRUITER_ID);
        Mockito.when(jobRepository.findById(JOB_ID)).thenReturn(Optional.of(ownedJob()));
        Mockito.when(jobDuplicateDetector.check(Mockito.any())).thenReturn(DuplicateCheck.of(
                List.of(new DuplicateJob(9L, "Backend Engineer II", JobStatus.PUBLISHED))));

        DuplicateCheck answer = service.checkDuplicates(JOB_ID);

        assertThat(answer.assessable()).isTrue();
        assertThat(answer.duplicates()).singleElement()
                .satisfies(duplicate -> assertThat(duplicate.title()).isEqualTo("Backend Engineer II"));
    }

    /** The distinction that lets the client retry rather than conclude there is nothing to show. */
    @Test
    void passesThroughThatAPostingWasNotAssessable() {
        givenSignedInAs(RECRUITER_ID);
        Mockito.when(jobRepository.findById(JOB_ID)).thenReturn(Optional.of(ownedJob()));
        Mockito.when(jobDuplicateDetector.check(Mockito.any()))
                .thenReturn(DuplicateCheck.notAssessable());

        assertThat(service.checkDuplicates(JOB_ID).assessable()).isFalse();
    }

    /** A duplicate list names other postings, so it must never cross an ownership boundary. */
    @Test
    void refusesAJobTheCallerDoesNotOwn() {
        givenSignedInAs(OTHER_RECRUITER_ID);
        Mockito.when(jobRepository.findById(JOB_ID)).thenReturn(Optional.of(ownedJob()));

        assertThatThrownBy(() -> service.checkDuplicates(JOB_ID))
                .isInstanceOf(AccessDeniedException.class);
        Mockito.verifyNoInteractions(jobDuplicateDetector);
    }

    private void givenSignedInAs(Long userId) {
        Mockito.when(currentUserProvider.getCurrentUserId()).thenReturn(userId);
    }

    private Job ownedJob() {
        return Job.builder()
                .id(JOB_ID)
                .title("Backend Engineer")
                .status(JobStatus.PUBLISHED)
                .company(Company.builder()
                        .id(11L)
                        .name("Acme")
                        .owner(User.builder().id(RECRUITER_ID).build())
                        .build())
                .build();
    }
}
