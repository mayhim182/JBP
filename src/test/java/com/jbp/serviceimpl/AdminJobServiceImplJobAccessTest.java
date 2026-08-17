package com.jbp.serviceimpl;

import com.jbp.dto.AdminJobResponse;
import com.jbp.dto.DuplicateCheck;
import com.jbp.dto.DuplicateJob;
import com.jbp.event.EmbeddingRefreshPublisher;
import com.jbp.exception.ResourceNotFoundException;
import com.jbp.mapper.JobMapper;
import com.jbp.model.Company;
import com.jbp.model.Job;
import com.jbp.model.JobStatus;
import com.jbp.model.User;
import com.jbp.model.VerificationStatus;
import com.jbp.repository.JobRepository;
import com.jbp.service.JobDuplicateDetector;
import com.jbp.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What an admin may read by id — Story 14.6's widened job route.
 *
 * <p>The DRAFT exclusion is an access decision rather than a filter, so it is asserted here rather
 * than left to the endpoint's javadoc. Widening admin reads to cover drafts would hand an admin
 * recruiter work-in-progress that no moderation flow has ever exposed.
 */
class AdminJobServiceImplJobAccessTest {

    private static final Long JOB_ID = 3L;

    private final JobRepository jobRepository = Mockito.mock(JobRepository.class);
    private final JobDuplicateDetector jobDuplicateDetector = Mockito.mock(JobDuplicateDetector.class);

    private final AdminJobServiceImpl service = new AdminJobServiceImpl(
            jobRepository,
            new JobMapper(),
            Mockito.mock(NotificationService.class),
            Mockito.mock(EmbeddingRefreshPublisher.class),
            jobDuplicateDetector);

    @Test
    void servesAPublishedPosting() {
        givenTheRepositoryHolds(job(JobStatus.PUBLISHED));
        givenNoDuplicates();

        AdminJobResponse response = service.getJob(JOB_ID);

        assertThat(response.id()).isEqualTo(JOB_ID);
        assertThat(response.job().getStatus()).isEqualTo(JobStatus.PUBLISHED);
    }

    /**
     * Any status but DRAFT, because a duplicate an admin follows may be closed or rejected — the
     * whole reason the route was widened.
     */
    @Test
    void servesAClosedPosting() {
        givenTheRepositoryHolds(job(JobStatus.CLOSED));
        givenNoDuplicates();

        assertThat(service.getJob(JOB_ID).job().getStatus()).isEqualTo(JobStatus.CLOSED);
    }

    /**
     * Not found rather than forbidden: a draft's existence is itself the recruiter's business, and a
     * 403 would confirm that a posting is there to be refused.
     */
    @Test
    void refusesADraftAsThoughItWereAbsent() {
        givenTheRepositoryHolds(job(JobStatus.DRAFT));

        assertThatThrownBy(() -> service.getJob(JOB_ID))
                .isInstanceOf(ResourceNotFoundException.class);
        // A draft is never embedded, so it can never be named as a duplicate either — the exclusion
        // costs nothing, which is why it is safe to make it absolute.
        Mockito.verifyNoInteractions(jobDuplicateDetector);
    }

    @Test
    void reportsAMissingJobAsAbsent() {
        Mockito.when(jobRepository.findById(JOB_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getJob(JOB_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void carriesTheDuplicatesItWasGiven() {
        givenTheRepositoryHolds(job(JobStatus.PUBLISHED));
        Mockito.when(jobDuplicateDetector.check(Mockito.any())).thenReturn(DuplicateCheck.of(
                List.of(new DuplicateJob(9L, "Backend Engineer (Platform)", JobStatus.CLOSED))));

        assertThat(service.getJob(JOB_ID).duplicates())
                .singleElement()
                .satisfies(duplicate -> {
                    assertThat(duplicate.id()).isEqualTo(9L);
                    assertThat(duplicate.status()).isEqualTo(JobStatus.CLOSED);
                });
    }

    /** A posting the detector could not assess reads the same as one with nothing to report. */
    @Test
    void carriesNoDuplicatesWhenThePostingCouldNotBeAssessed() {
        givenTheRepositoryHolds(job(JobStatus.PUBLISHED));
        Mockito.when(jobDuplicateDetector.check(Mockito.any()))
                .thenReturn(DuplicateCheck.notAssessable());

        assertThat(service.getJob(JOB_ID).duplicates()).isEmpty();
    }

    private void givenTheRepositoryHolds(Job job) {
        Mockito.when(jobRepository.findById(JOB_ID)).thenReturn(Optional.of(job));
    }

    private void givenNoDuplicates() {
        Mockito.when(jobDuplicateDetector.check(Mockito.any())).thenReturn(DuplicateCheck.of(List.of()));
    }

    private Job job(JobStatus status) {
        return Job.builder()
                .id(JOB_ID)
                .title("Backend Engineer")
                .status(status)
                .skills(new HashSet<>())
                .screeningQuestions(new ArrayList<>())
                .company(Company.builder()
                        .id(11L)
                        .name("Acme")
                        .status(VerificationStatus.VERIFIED)
                        .owner(User.builder().id(7L).build())
                        .build())
                .build();
    }
}
